"""Game engine for Ronie: Chess (Stockfish & Lichess cloud eval), Poker odds & equity, and Board Game referee.

Designed for hands-free audio advice through Ray-Ban Meta glasses:
- Chess: reads FEN or spoken moves ("Knight to f3", "Ghoda f3"), calculates best moves via Stockfish,
  explains tactical ideas, and supports Coach, Teach, and Referee styles.
- Poker: calculates Texas Hold'em hand equity, pot odds, and expected value (+EV decisions)
  from hole cards and community cards.
- Board games (Monopoly, Scrabble, Uno, Catan, etc.): settles rules disputes and evaluates tactical choices.
"""
from __future__ import annotations

import asyncio
import itertools
import json
import random
import re
from dataclasses import dataclass
from typing import Any, Callable

import chess
import httpx

# Mapping of spoken piece names (English & Hindi) to standard chess pieces
PIECE_NAMES_EN = {
    "king": chess.KING, "queen": chess.QUEEN, "rook": chess.ROOK,
    "bishop": chess.BISHOP, "knight": chess.KNIGHT, "horse": chess.KNIGHT, "pawn": chess.PAWN,
}
PIECE_NAMES_HI = {
    "raja": chess.KING, "badshah": chess.KING,
    "vazir": chess.QUEEN, "rani": chess.QUEEN,
    "haathi": chess.ROOK, "hathi": chess.ROOK,
    "oont": chess.BISHOP, "unt": chess.BISHOP,
    "ghoda": chess.KNIGHT, "goda": chess.KNIGHT,
    "pyada": chess.PAWN, "piyada": chess.PAWN, "sipahi": chess.PAWN,
}
PIECE_TO_NAMES = {
    chess.KING: ("King", "Raja"),
    chess.QUEEN: ("Queen", "Vazir"),
    chess.ROOK: ("Rook", "Haathi"),
    chess.BISHOP: ("Bishop", "Oont"),
    chess.KNIGHT: ("Knight", "Ghoda"),
    chess.PAWN: ("Pawn", "Pyada"),
}

# ----------------- Chess Engine -----------------

class ChessEngine:
    def __init__(self, fen: str | None = None) -> None:
        self.board = chess.Board(fen or chess.STARTING_FEN)
        self.style = "coach"  # "coach", "teach", "referee"

    def reset(self, fen: str | None = None) -> None:
        self.board = chess.Board(fen or chess.STARTING_FEN)

    def set_fen(self, fen: str) -> None:
        self.board = chess.Board(fen)

    def fen(self) -> str:
        return self.board.fen()

    def parse_move(self, text: str) -> chess.Move | None:
        """Parses a move string in UCI ('e2e4'), SAN ('Nf3', 'e4', 'Bxc6+'), or spoken text ('knight to f3', 'ghoda f3')."""
        clean = text.strip().replace("  ", " ")
        # 1. Try UCI (e.g. e2e4)
        try:
            m = chess.Move.from_uci(clean.lower())
            if m in self.board.legal_moves:
                return m
        except ValueError:
            pass

        # 2. Try SAN (e.g. Nf3, e4, O-O, exd5)
        try:
            return self.board.parse_san(clean)
        except ValueError:
            pass

        # 3. Try case-insensitive SAN or basic corrections
        for legal in self.board.legal_moves:
            san = self.board.san(legal)
            if san.lower() == clean.lower() or san.replace("+", "").replace("#", "").lower() == clean.lower():
                return legal

        # 4. Spoken text parsing ("knight to f3", "ghoda f3", "bishop takes c6", "pawn to e4")
        lower = clean.lower().replace(" to ", " ").replace(" pe ", " ").replace(" takes ", "x").replace(" captures ", "x")
        words = lower.split()
        if not words:
            return None

        # Check for destination square in words (e.g. 'f3', 'c6', 'e4')
        dest = None
        for w in words:
            w_clean = w.replace("x", "")
            if len(w_clean) == 2 and w_clean[0] in "abcdefgh" and w_clean[1] in "12345678":
                dest = chess.parse_square(w_clean)
                break

        if dest is not None:
            # Check piece type
            piece_type = None
            for w in words:
                if w in PIECE_NAMES_EN:
                    piece_type = PIECE_NAMES_EN[w]
                    break
                if w in PIECE_NAMES_HI:
                    piece_type = PIECE_NAMES_HI[w]
                    break

            # Find matching legal moves
            candidates = [
                m for m in self.board.legal_moves
                if m.to_square == dest and (
                    piece_type is None or self.board.piece_at(m.from_square) and self.board.piece_at(m.from_square).piece_type == piece_type
                )
            ]
            if len(candidates) == 1:
                return candidates[0]
            if len(candidates) > 1 and piece_type is None:
                # Default to pawn moves if not specified (e.g. "e4")
                pawn_moves = [m for m in candidates if self.board.piece_at(m.from_square).piece_type == chess.PAWN]
                if len(pawn_moves) == 1:
                    return pawn_moves[0]

        return None

    def push_move(self, text: str) -> dict[str, Any]:
        """Plays a move on the board and returns information about the resulting state."""
        move = self.parse_move(text)
        if move is None:
            return {"error": f"Illegal or unrecognized move: {text}", "fen": self.board.fen()}

        san = self.board.san(move)
        piece = self.board.piece_at(move.from_square)
        piece_names = PIECE_TO_NAMES.get(piece.piece_type, ("Piece", "Piece")) if piece else ("Piece", "Piece")
        is_capture = self.board.is_capture(move)

        self.board.push(move)

        state = {
            "move": san,
            "uci": move.uci(),
            "fen": self.board.fen(),
            "turn": "white" if self.board.turn == chess.WHITE else "black",
            "is_check": self.board.is_check(),
            "is_checkmate": self.board.is_checkmate(),
            "is_stalemate": self.board.is_stalemate(),
            "is_game_over": self.board.is_game_over(),
        }

        spoken = f"Played {san}."
        if state["is_checkmate"]:
            spoken += " Checkmate! Game over."
        elif state["is_check"]:
            spoken += " Check!"

        state["spoken"] = spoken
        return state

    def _format_spoken_response(self, best_move: chess.Move, san: str, eval_str: str) -> tuple[str, str]:
        piece = self.board.piece_at(best_move.from_square)
        names = PIECE_TO_NAMES.get(piece.piece_type if piece else chess.PAWN, ("Piece", "Piece"))
        sq_name = chess.square_name(best_move.to_square)

        if self.style == "teach":
            spoken = f"Focus on piece coordination around {sq_name}. What would you play with your {names[0]}?"
            spoken_hi = f"{names[1]} ki chaal socho {sq_name} ke paas. Aap kya chalenge?"
        elif self.style == "referee":
            turn_name = "White" if self.board.turn == chess.WHITE else "Black"
            spoken = f"Position is legal. {turn_name} to move. Best technical move is {san}."
            spoken_hi = f"Position valid hai. {turn_name} ki baari hai. Sahi move {san} hai."
        elif self.style == "review":
            spoken = f"Evaluation is {eval_str}. Key move is {names[0]} to {sq_name} ({san})."
            spoken_hi = f"Eval {eval_str} hai. Zaroori move {names[1]} {sq_name} ({san}) hai."
        else:  # coach
            spoken = f"Best move is {names[0]} to {sq_name} ({san}). Evaluation is {eval_str}."
            spoken_hi = f"{names[1]} {sq_name} ({san}) pe chalo. Eval {eval_str}."

        return spoken, spoken_hi

    async def evaluate_best_move(self) -> dict[str, Any]:
        """Finds the best move using Lichess Cloud Stockfish API, falling back to local heuristic evaluation."""
        if self.board.is_game_over():
            return {
                "game_over": True,
                "result": self.board.result(),
                "spoken": f"The game is already over: {self.board.result()}.",
            }

        fen_str = self.board.fen()
        # 1. Try Lichess Cloud Evaluation (Stockfish depth 40-75)
        try:
            async with httpx.AsyncClient(timeout=2.5) as client:
                resp = await client.get(
                    "https://lichess.org/api/cloud-eval",
                    params={"fen": fen_str, "multiPv": 1},
                )
                if resp.status_code == 200:
                    data = resp.json()
                    pvs = data.get("pvs") or []
                    if pvs and "moves" in pvs[0]:
                        pv_moves = pvs[0]["moves"].split()
                        best_uci = pv_moves[0]
                        best_move = chess.Move.from_uci(best_uci)
                        if best_move in self.board.legal_moves:
                            san = self.board.san(best_move)
                            cp = pvs[0].get("cp")
                            mate = pvs[0].get("mate")

                            if cp is not None:
                                eval_str = f"+{cp/100:.1f}" if cp > 0 else f"{cp/100:.1f}"
                            else:
                                eval_str = f"Mate in {mate}"
                            spoken, hindi_spoken = self._format_spoken_response(best_move, san, eval_str)

                            return {
                                "source": "stockfish_cloud",
                                "depth": data.get("depth", 50),
                                "best_move_uci": best_uci,
                                "best_move_san": san,
                                "cp": cp,
                                "mate": mate,
                                "eval": eval_str,
                                "spoken": spoken,
                                "spoken_hi": hindi_spoken,
                                "fen": fen_str,
                                "style": self.style,
                            }
        except Exception:
            pass

        # 2. Local heuristic fallback (piece values + center control + mobility)
        return self._local_heuristic_best()

    def _local_heuristic_best(self) -> dict[str, Any]:
        """Fast fallback when offline or for custom novel positions."""
        piece_vals = {chess.PAWN: 100, chess.KNIGHT: 320, chess.BISHOP: 330, chess.ROOK: 500, chess.QUEEN: 900, chess.KING: 20000}
        center_sqs = {chess.D4, chess.D5, chess.E4, chess.E5}

        best_move = None
        best_score = -999999
        color = self.board.turn

        for move in self.board.legal_moves:
            score = 0
            # Captures
            if self.board.is_capture(move):
                victim = self.board.piece_at(move.to_square)
                attacker = self.board.piece_at(move.from_square)
                if victim:
                    score += piece_vals.get(victim.piece_type, 0) * 10 - piece_vals.get(attacker.piece_type, 0)
                else:
                    score += 100  # en passant

            # Center control
            if move.to_square in center_sqs:
                score += 30

            # Checks
            self.board.push(move)
            if self.board.is_check():
                score += 40
            if self.board.is_checkmate():
                score += 100000
            self.board.pop()

            if score > best_score:
                best_score = score
                best_move = move

        if best_move is None:
            best_move = next(iter(self.board.legal_moves))

        san = self.board.san(best_move)
        spoken, hindi_spoken = self._format_spoken_response(best_move, san, "heuristic")

        return {
            "source": "local_engine",
            "best_move_uci": best_move.uci(),
            "best_move_san": san,
            "spoken": spoken,
            "spoken_hi": hindi_spoken,
            "fen": self.board.fen(),
            "style": self.style,
        }

    def ascii_board(self) -> str:
        return str(self.board)


# ----------------- Poker Engine -----------------

RANKS = "23456789TJQKA"
SUITS = "cdhs"
RANK_VALUES = {r: i + 2 for i, r in enumerate(RANKS)}
RANK_NAMES = {
    "2": "Deuce", "3": "Three", "4": "Four", "5": "Five", "6": "Six", "7": "Seven",
    "8": "Eight", "9": "Nine", "T": "Ten", "J": "Jack", "Q": "Queen", "K": "King", "A": "Ace"
}

@dataclass(frozen=True)
class Card:
    rank: str
    suit: str

    def __str__(self) -> str:
        return f"{self.rank}{self.suit}"

    @property
    def val(self) -> int:
        return RANK_VALUES[self.rank]

def parse_card(text: str) -> Card | None:
    """Parses 'Ah', '10s', 'Kd', 'Ace of spades', 'jack of hearts' into Card."""
    c = text.strip()
    if not c:
        return None
    c = c.replace("10", "T").replace(" of ", " ")
    parts = c.split()
    if len(parts) == 2:
        r_str, s_str = parts[0].capitalize(), parts[1].lower()
        r_map = {"Ace": "A", "King": "K", "Queen": "Q", "Jack": "J", "Ten": "T"}
        s_map = {"spades": "s", "hearts": "h", "diamonds": "d", "clubs": "c",
                 "spade": "s", "heart": "h", "diamond": "d", "club": "c"}
        r = r_map.get(r_str, r_str[0].upper() if r_str else "A")
        s = s_map.get(s_str, s_str[0].lower() if s_str else "h")
        if r in RANKS and s in SUITS:
            return Card(r, s)

    # 2-character format like 'Ah', 'Kd', 'Ts', '2c'
    if len(c) == 2:
        r, s = c[0].upper(), c[1].lower()
        if r in RANKS and s in SUITS:
            return Card(r, s)

    return None

def parse_cards(text: str) -> list[Card]:
    """Parses card strings in various formats: 'Ah Kd 10s', 'Ace of hearts, King of diamonds', 'pocket aces'."""
    lower = text.lower().strip()
    if lower.startswith("pocket "):
        rank_word = lower.replace("pocket ", "").strip()
        r_map = {"aces": "A", "ace": "A", "kings": "K", "king": "K", "queens": "Q", "queen": "Q",
                 "jacks": "J", "jack": "J", "tens": "T", "ten": "T", "nines": "9", "eights": "8",
                 "sevens": "7", "sixes": "6", "fives": "5", "fours": "4", "threes": "3", "twos": "2", "deuces": "2"}
        r = r_map.get(rank_word, rank_word[0].upper() if rank_word else "A")
        if r in RANKS:
            return [Card(r, "h"), Card(r, "d")]

    # How people say a starting hand: "ace king suited", "AK offsuit", "queen jack" (suits don't matter, only same/different).
    words = {"ace": "A", "king": "K", "queen": "Q", "jack": "J", "ten": "T", "nine": "9", "eight": "8", "seven": "7",
             "six": "6", "five": "5", "four": "4", "three": "3", "two": "2", "deuce": "2"}
    m = re.fullmatch(r"(\w+)[\s-]+(\w+)(?:\s+(suited|offsuit|off suit|off))?", lower)
    if m and words.get(m.group(1).rstrip("s")) and words.get(m.group(2).rstrip("s")):
        r1, r2 = words[m.group(1).rstrip("s")], words[m.group(2).rstrip("s")]
        suited = m.group(3) == "suited" and r1 != r2
        return [Card(r1, "s"), Card(r2, "s" if suited else "h")]
    m = re.fullmatch(r"([2-9tjqka])([2-9tjqka])\s*(s|o|suited|offsuit)?", lower)
    if m:
        r1, r2 = m.group(1).upper(), m.group(2).upper()
        suited = (m.group(3) or "").startswith("s") and r1 != r2
        return [Card(r1, "s"), Card(r2, "s" if suited else "h")]

    clean = text.replace(",", " ").replace(";", " ")
    tokens = clean.split()
    cards = []
    i = 0
    while i < len(tokens):
        if i + 2 < len(tokens) and tokens[i + 1].lower() == "of":
            c = parse_card(f"{tokens[i]} {tokens[i+2]}")
            if c:
                cards.append(c)
                i += 3
                continue
        if i + 1 < len(tokens) and tokens[i + 1].lower() in ("hearts", "spades", "diamonds", "clubs", "heart", "spade", "diamond", "club"):
            c = parse_card(f"{tokens[i]} {tokens[i+1]}")
            if c:
                cards.append(c)
                i += 2
                continue
        c = parse_card(tokens[i])
        if c:
            cards.append(c)
        i += 1
    return cards

def evaluate_5card(cards: list[Card]) -> tuple[int, list[int]]:
    """Evaluates 5 cards. Returns (hand_rank, tie_breakers) where higher is better."""
    ranks = sorted([c.val for c in cards], reverse=True)
    suits = [c.suit for c in cards]
    is_flush = len(set(suits)) == 1

    # Check straight (including A-2-3-4-5 wheel)
    is_straight = False
    straight_high = 0
    unique_ranks = sorted(set(ranks), reverse=True)
    if len(unique_ranks) == 5:
        if unique_ranks[0] - unique_ranks[4] == 4:
            is_straight = True
            straight_high = unique_ranks[0]
        elif unique_ranks == [14, 5, 4, 3, 2]:
            is_straight = True
            straight_high = 5

    # Count rank frequencies
    counts: dict[int, int] = {}
    for r in ranks:
        counts[r] = counts.get(r, 0) + 1
    grouped = sorted(counts.items(), key=lambda x: (x[1], x[0]), reverse=True)
    freqs = [g[1] for g in grouped]
    grouped_ranks = [g[0] for g in grouped]

    if is_straight and is_flush:
        return (9, [straight_high])
    if freqs == [4, 1]:
        return (8, grouped_ranks)
    if freqs == [3, 2]:
        return (7, grouped_ranks)
    if is_flush:
        return (6, ranks)
    if is_straight:
        return (5, [straight_high])
    if freqs == [3, 1, 1]:
        return (4, grouped_ranks)
    if freqs == [2, 2, 1]:
        return (3, grouped_ranks)
    if freqs == [2, 1, 1, 1]:
        return (2, grouped_ranks)
    return (1, ranks)

def evaluate_7card(cards: list[Card]) -> tuple[int, list[int]]:
    """Evaluates 7 cards by picking the best 5-card combination."""
    best = (0, [])
    for combo in itertools.combinations(cards, 5):
        score = evaluate_5card(list(combo))
        if score > best:
            best = score
    return best

HAND_NAMES = {
    9: "Straight Flush", 8: "Four of a Kind", 7: "Full House", 6: "Flush",
    5: "Straight", 4: "Three of a Kind", 3: "Two Pair", 2: "One Pair", 1: "High Card"
}

def hand_name(score: tuple[int, list[int]]) -> str:
    if score[0] == 9 and score[1] and score[1][0] == 14:
        return "Royal Flush"
    return HAND_NAMES.get(score[0], "High Card")

class PokerEngine:
    @staticmethod
    def simulate_equity(
        hole: list[Card],
        board: list[Card],
        num_opponents: int = 1,
        iterations: int = 1200,
    ) -> dict[str, Any]:
        """Monte Carlo Texas Hold'em equity simulation."""
        if len(hole) != 2:
            return {"error": "Need exactly 2 hole cards"}

        used = set(hole + board)
        full_deck = [Card(r, s) for r in RANKS for s in SUITS if Card(r, s) not in used]

        needed_board = 5 - len(board)
        wins = 0
        ties = 0
        losses = 0

        for _ in range(iterations):
            deck_sample = random.sample(full_deck, needed_board + num_opponents * 2)
            runout_board = board + deck_sample[:needed_board]

            my_score = evaluate_7card(hole + runout_board)

            opp_best = (0, [])
            for opp_idx in range(num_opponents):
                opp_hole = deck_sample[needed_board + opp_idx * 2: needed_board + (opp_idx + 1) * 2]
                opp_score = evaluate_7card(opp_hole + runout_board)
                if opp_score > opp_best:
                    opp_best = opp_score

            if my_score > opp_best:
                wins += 1
            elif my_score == opp_best:
                ties += 1
            else:
                losses += 1

        total = iterations
        win_pct = round((wins / total) * 100, 1)
        tie_pct = round((ties / total) * 100, 1)
        equity_pct = round(((wins + ties * 0.5) / total) * 100, 1)

        # Describe current made hand if board exists
        current_hand = "Pre-flop"
        if len(board) >= 3:
            rank_val = evaluate_7card(hole + board)[0]
            current_hand = HAND_NAMES.get(rank_val, "High Card")

        return {
            "equity_pct": equity_pct,
            "win_pct": win_pct,
            "tie_pct": tie_pct,
            "current_hand": current_hand,
            "num_opponents": num_opponents,
            "board_count": len(board),
        }

    @classmethod
    def analyze(
        cls,
        hole_str: str,
        board_str: str = "",
        pot: float = 0.0,
        to_call: float = 0.0,
        num_opponents: int = 1,
    ) -> dict[str, Any]:
        """Full Texas Hold'em decision analysis with pot odds and EV recommendation."""
        hole = parse_cards(hole_str)
        board = parse_cards(board_str)

        if len(hole) < 2:
            return {"error": "Provide 2 hole cards (e.g. 'Ah Kd' or 'Ace of hearts, King of diamonds')"}

        sim = cls.simulate_equity(hole[:2], board, num_opponents=max(1, num_opponents))
        if "error" in sim:
            return sim

        equity = sim["equity_pct"]

        # Pot odds calculation
        pot_odds_pct = 0.0
        recommendation = "Check / Bet"
        if to_call > 0:
            pot_odds_pct = round((to_call / (pot + to_call)) * 100, 1)
            if equity >= pot_odds_pct + 12:
                recommendation = "Raise / Value Bet (+EV)"
            elif equity >= pot_odds_pct:
                recommendation = "Call (+EV)"
            else:
                recommendation = "Fold (-EV)"
        else:
            if equity > 65:
                recommendation = "Strong Bet / Raise"
            elif equity > 45:
                recommendation = "Value Bet / Check"
            else:
                recommendation = "Check / Pot Control"

        hole_desc = f"{RANK_NAMES.get(hole[0].rank, hole[0].rank)}-{RANK_NAMES.get(hole[1].rank, hole[1].rank)}"
        if hole[0].suit == hole[1].suit:
            hole_desc += " suited"

        spoken = f"With {hole_desc}, your equity is about {equity}%."
        if to_call > 0 and recommendation.startswith("Fold"):
            spoken += f" Pot odds require {pot_odds_pct}%, so calling loses money: fold."
        elif to_call > 0:
            spoken += f" Pot odds require {pot_odds_pct}%, so {recommendation.split()[0].lower()} is profitable."
        else:
            spoken += f" Suggesting: {recommendation}."

        return {
            **sim,
            "hole": [str(c) for c in hole[:2]],
            "board": [str(c) for c in board],
            "pot": pot,
            "to_call": to_call,
            "pot_odds_pct": pot_odds_pct,
            "recommendation": recommendation,
            "spoken": spoken,
        }


# ----------------- Board Game Referee -----------------

class BoardGameReferee:
    """Rules and referee rulings for popular board and card games (Monopoly, Uno, Scrabble, Catan)."""

    RULES: dict[str, dict[str, str]] = {
        "monopoly": {
            "jail": "You can get out of jail by: 1) Rolling doubles on any of your next 3 turns, 2) Paying a $50 fine before rolling on turns 1-3, or 3) Using a 'Get Out of Jail Free' card. If you don't roll doubles by turn 3, you MUST pay $50 and advance.",
            "free_parking": "Official rules: Free Parking is strictly a rest space. You do NOT collect tax money, fines, or jackpots. Any money pool on Free Parking is a house rule.",
            "housing_shortage": "Official rules: You cannot build houses if the bank is out of plastic houses (32 houses limit). You cannot skip directly to hotels if houses are unavailable.",
            "trading": "Unimproved properties may be traded or mortgaged at any time between turns. Any houses or hotels must be sold back to the bank for half price before trading property.",
        },
        "uno": {
            "stacking": "Official Mattel rules: You CANNOT stack Draw Two (+2) or Wild Draw Four (+4) cards! If a +2 is played on you, you must draw 2 and skip your turn.",
            "wild_draw_four": "Official rules: You can only legally play a Wild Draw Four if you do NOT have any cards matching the current color in your hand. An opponent can challenge you. If you were bluffing, you draw 4 instead!",
            "saying_uno": "If a player plays their second-to-last card and does not say 'UNO' before the next player takes a turn, and is caught, they must draw 2 penalty cards.",
            "jump_in": "Jump-In is an optional house rule: If you have the exact identical card (same color and number/action), you can play it out of turn.",
        },
        "scrabble": {
            "challenge": "If a player challenges a word and it is NOT in the official dictionary, the player takes their tiles back and scores 0 for that turn. If the word is valid, the challenger loses their next turn.",
            "blank_tiles": "Blank tiles can represent any letter. Once played, the letter it represents remains fixed for the rest of the game and scores 0 points.",
            "premium_squares": "Triple and double word/letter scores only apply on the exact turn the tile is first placed on that square.",
        },
        "catan": {
            "robber": "When a 7 is rolled: 1) Any player with more than 7 resource cards must discard half (rounded down). 2) The active player moves the Robber to any tile and steals 1 random resource card from an adjacent player.",
            "longest_road": "Requires a continuous chain of at least 5 road segments. If another player builds a settlement inside your road chain, your road is broken!",
            "trade": "Players can trade resources with the bank (4:1) or ports (3:1 or 2:1). Domestic trade (with other players) can only occur on the active player's turn.",
        },
    }

    @classmethod
    def rule_lookup(cls, game: str, topic: str = "") -> dict[str, Any]:
        g = game.lower().strip()
        t = topic.lower().strip()
        matched_game = None
        for k in cls.RULES:
            if k in g or g in k:
                matched_game = k
                break

        if not matched_game:
            return {
                "game": game,
                "found": False,
                "spoken": f"For {game}, checking standard rules. Please ask your specific rules dispute.",
            }

        game_rules = cls.RULES[matched_game]
        best_topic = None
        for rule_key in game_rules:
            if rule_key in t or any(word in t for word in rule_key.split("_")):
                best_topic = rule_key
                break

        if best_topic:
            text = game_rules[best_topic]
            return {
                "game": matched_game,
                "topic": best_topic,
                "rule": text,
                "found": True,
                "spoken": f"Official {matched_game.title()} rule: {text}",
            }

        summary = " ".join([f"{k.replace('_', ' ').title()}: {v}" for k, v in game_rules.items()])
        return {
            "game": matched_game,
            "topics": list(game_rules.keys()),
            "found": True,
            "spoken": f"Official {matched_game.title()} rules available for: {', '.join(game_rules.keys())}.",
        }



GAME_VISION_PROMPT = """You are an expert game vision system for Chess, Poker, and Board Games.
Analyze the provided photo taken from the player's glasses.
1. Determine what game is visible: "chess", "poker", "monopoly", "uno", "scrabble", or other.
2. If Chess:
   - Identify the 8x8 chessboard and the locations of all White and Black pieces.
   - Determine who is playing from this perspective (White or Black).
   - Produce a standard Forsyth-Edwards Notation (FEN) string representing the position, e.g. "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3".
   - If exact piece squares are partially obscured, produce the most likely legal FEN or candidate moves.
3. If Poker:
   - List the visible community board cards (e.g. "Ah Kd 10s").
   - List the player's visible hole cards (e.g. "Qs Qh").
4. If Board / Card Game (Monopoly, Uno, Scrabble, etc.):
   - Describe player position, cards in hand, or board status.
   - What move or action should the player take next?

Return strictly valid JSON in this exact structure:
{
  "game": "chess" | "poker" | "monopoly" | "uno" | "scrabble" | "other",
  "fen": "<standard FEN string or null>",
  "turn": "white" | "black",
  "board_cards": "<cards string or null>",
  "hole_cards": "<cards string or null>",
  "description": "<concise description of what is visible>",
  "suggested_move": "<suggested move or null>",
  "reason": "<tactical or strategic reason>"
}
"""

# ----------------- Game Manager -----------------

class GameManager:
    def __init__(self) -> None:
        self.chess = ChessEngine()
        self.active_game = "chess"  # "chess", "poker", "monopoly", "general"
        self.style = "coach"  # "coach", "teach", "referee", "review"

    def set_style(self, style: str) -> str:
        s = style.lower().strip()
        if s in ("coach", "teach", "referee", "review"):
            self.style = s
            self.chess.style = s
            return s
        return self.style


    async def analyze_vision(
        self,
        image_b64: str,
        router: Any = None,
        game_hint: str = "auto",
        mime_type: str = "image/jpeg",
    ) -> dict[str, Any]:
        """Analyzes a game photo from the glasses, extracts state, and evaluates the best move."""
        if not image_b64:
            return {"error": "No image data provided"}

        from .models.base import LLMRequest

        r_engine = router or getattr(self, "router", None)
        if not r_engine:
            return {
                "error": "No LLM router available for vision",
                "spoken": "Vision analysis needs an active model router.",
            }

        prompt = GAME_VISION_PROMPT
        if game_hint and game_hint != "auto":
            prompt += f"\nNote: The user indicated this game is: {game_hint}."

        try:
            req = LLMRequest(
                prompt=prompt,
                task="vision",
                image_b64=image_b64,
                mime_type=mime_type,
                raw=True,
                max_tokens=1024,
            )
            result = await r_engine.complete(req)
            raw_text = result.text.strip()
            json_text = raw_text
            if "```json" in raw_text:
                json_text = raw_text.split("```json", 1)[1].split("```", 1)[0].strip()
            elif "```" in raw_text:
                json_text = raw_text.split("```", 1)[1].split("```", 1)[0].strip()
            parsed = json.loads(json_text)
        except Exception as e:
            return {
                "game": game_hint if game_hint != "auto" else "chess",
                "detected": False,
                "error": f"Vision analysis could not parse board: {e}",
                "spoken": "I could see the game, but couldn't make out the exact board squares clearly. Try holding the glasses steady facing the board.",
            }

        detected_game = parsed.get("game", "chess").lower()
        description = parsed.get("description", "")
        fen = parsed.get("fen")

        if detected_game == "chess" and fen:
            try:
                self.chess.set_fen(fen)
                best_res = await self.chess.evaluate_best_move()
                best_san = best_res.get("best_move_san")
                eval_str = best_res.get("eval", "")
                spoken = f"Looking at your chess board: {description} Best move is {best_san} ({eval_str})."
                spoken_hi = f"Board dekh liya: {description}. Sahi chaal {best_san} hai, eval {eval_str}."
                return {
                    "game": "chess",
                    "fen": fen,
                    "description": description,
                    "best_move": best_san,
                    "eval": eval_str,
                    "spoken": spoken,
                    "spoken_hi": spoken_hi,
                    "stockfish": best_res,
                }
            except Exception:
                candidate = parsed.get("suggested_move") or "Control the center"
                return {
                    "game": "chess",
                    "fen": fen,
                    "description": description,
                    "best_move": candidate,
                    "spoken": f"Looking at your chess board: {description}. Consider {candidate}.",
                    "spoken_hi": f"Board dekh liya. {candidate} chal sakte hain.",
                }

        if detected_game == "poker":
            hole = parsed.get("hole_cards") or ""
            board = parsed.get("board_cards") or ""
            if hole:
                poker_analysis = await asyncio.to_thread(PokerEngine.analyze, hole, board)
                return {
                    "game": "poker",
                    "hole": hole,
                    "board": board,
                    "description": description,
                    **poker_analysis,
                }
            return {
                "game": "poker",
                "description": description,
                "spoken": f"I see the table: {description}. Tell me your hole cards to calculate win equity.",
            }

        candidate = parsed.get("suggested_move") or parsed.get("reason") or description
        return {
            "game": detected_game,
            "description": description,
            "best_move": candidate,
            "spoken": f"Looking at your {detected_game.title()} game: {candidate}",
        }

    async def dispatch(self, action: str, args: dict[str, Any] | None = None) -> dict[str, Any]:
        """Handles spoken tools and REST calls."""
        args = args or {}
        act = (action or "").lower().replace("-", "_").strip()

        # Auto-detect action if not provided or generic
        if not act or act == "game":
            if "image_b64" in args or "image" in args:
                act = "vision"
            elif "move" in args or "uci" in args or "san" in args:
                act = "chess_move"
            elif "hole" in args or "cards" in args:
                act = "poker_equity"
            elif "game" in args or "topic" in args or "rule" in args:
                act = "rules"
            elif "fen" in args:
                act = "chess_state"
            else:
                act = "chess_best"

        if act in ("set_style", "style"):
            st = self.set_style(str(args.get("style") or "coach"))
            return {"style": st, "spoken": f"Game mode coaching style set to {st}."}

        # 1. Chess actions
        if act in ("chess_move", "move"):
            move = str(args.get("move") or args.get("san") or args.get("uci") or "")
            return self.chess.push_move(move)

        if act in ("chess_best", "best_move", "suggest_move"):
            if args.get("fen"):  # the position Ronie read from a glasses photo
                try:
                    self.chess.set_fen(str(args["fen"]))
                except ValueError:
                    return {"error": "That board position (FEN) isn't valid", "fen": args["fen"]}
            style_override = args.get("style")
            if style_override:
                self.set_style(str(style_override))
            return await self.chess.evaluate_best_move()

        if act in ("chess_state", "board"):
            return {
                "fen": self.chess.fen(),
                "ascii": self.chess.ascii_board(),
                "turn": "white" if self.chess.board.turn == chess.WHITE else "black",
                "is_check": self.chess.board.is_check(),
                "legal_moves": [self.chess.board.san(m) for m in self.chess.board.legal_moves][:20],
                "style": self.style,
            }

        if act in ("chess_reset", "new_game"):
            fen = args.get("fen")
            self.chess.reset(fen)
            return {"reset": True, "fen": self.chess.fen(), "spoken": "New chess game started."}

        # 2. Poker actions
        if act in ("poker_equity", "poker", "poker_odds"):
            hole = str(args.get("hole") or args.get("cards") or "")
            board = str(args.get("board") or "")
            pot = float(args.get("pot") or 0.0)
            to_call = float(args.get("to_call") or args.get("call") or 0.0)
            opponents = int(args.get("opponents") or args.get("players") or 1)
            return await asyncio.to_thread(PokerEngine.analyze, hole, board, pot, to_call, opponents)

        # 3. Board games & rules referee
        if act in ("rules", "referee", "rule", "board_game"):
            game_name = str(args.get("game") or args.get("name") or "monopoly")
            topic = str(args.get("topic") or args.get("question") or args.get("rule") or "")
            return BoardGameReferee.rule_lookup(game_name, topic)

        # 4. Vision actions
        if act in ("vision", "vision_move", "scan", "look", "analyze_image"):
            image_b64 = str(args.get("image_b64") or args.get("image") or "")
            router = args.get("router") or getattr(self, "router", None)
            game_hint = str(args.get("game") or "auto")
            return await self.analyze_vision(image_b64, router=router, game_hint=game_hint)

        return {"error": f"Unknown game action: {action}"}
