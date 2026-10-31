import pytest
import chess
from fastapi.testclient import TestClient

from conftest import FakeKeys, FakeProvider
from ronie_brain.app import create_app
from ronie_brain.games import (
    BoardGameReferee,
    Card,
    ChessEngine,
    GameManager,
    PokerEngine,
    evaluate_7card,
    hand_name,
    parse_cards,
)
from ronie_brain.memory import Memory
from ronie_brain.tools import Tools


# ----------------- Chess Tests -----------------

def test_chess_engine_moves_uci_and_san():
    engine = ChessEngine()
    # UCI
    m = engine.parse_move("e2e4")
    assert m == chess.Move.from_uci("e2e4")

    # SAN
    m = engine.parse_move("e4")
    assert m == chess.Move.from_uci("e2e4")

    engine.push_move("e4")
    m_black = engine.parse_move("e5")
    assert m_black == chess.Move.from_uci("e7e5")


def test_chess_engine_spoken_english_and_hindi():
    engine = ChessEngine()
    # English spoken
    m1 = engine.parse_move("knight to f3")
    assert m1 == chess.Move.from_uci("g1f3")

    m2 = engine.parse_move("pawn to e4")
    assert m2 == chess.Move.from_uci("e2e4")

    # Hindi spoken
    engine.reset()
    m3 = engine.parse_move("ghoda f3")
    assert m3 == chess.Move.from_uci("g1f3")

    m4 = engine.parse_move("pyada e4")
    assert m4 == chess.Move.from_uci("e2e4")

    engine.push_move("e4")
    engine.push_move("e5")
    m5 = engine.parse_move("oont c4")
    assert m5 == chess.Move.from_uci("f1c4")


def test_chess_engine_styles():
    engine = ChessEngine()
    # Coach style (default)
    engine.style = "coach"
    res_coach = engine._local_heuristic_best()
    assert "Best move is" in res_coach["spoken"]
    assert "pe chalo" in res_coach["spoken_hi"]

    # Teach style
    engine.style = "teach"
    res_teach = engine._local_heuristic_best()
    assert "What would you play" in res_teach["spoken"]
    assert "Aap kya chalenge" in res_teach["spoken_hi"]

    # Referee style
    engine.style = "referee"
    res_ref = engine._local_heuristic_best()
    assert "Position is legal" in res_ref["spoken"]

    # Review style
    engine.style = "review"
    res_rev = engine._local_heuristic_best()
    assert "Evaluation is" in res_rev["spoken"]


@pytest.mark.asyncio
async def test_chess_engine_evaluate_best_move():
    engine = ChessEngine()
    best = await engine.evaluate_best_move()
    assert "best_move_san" in best
    assert "spoken" in best
    assert "fen" in best


# ----------------- Poker Tests -----------------

def test_poker_card_parsing():
    # Shorthand
    cards = parse_cards("Ah Kd 10s")
    assert len(cards) == 3
    assert cards[0] == Card("A", "h")
    assert cards[1] == Card("K", "d")
    assert cards[2] == Card("T", "s")

    # Natural English
    cards_nat = parse_cards("Ace of hearts, King of diamonds")
    assert len(cards_nat) == 2
    assert cards_nat[0] == Card("A", "h")
    assert cards_nat[1] == Card("K", "d")

    # Pocket pairs
    cards_pocket = parse_cards("pocket aces")
    assert len(cards_pocket) == 2
    assert cards_pocket[0].rank == "A"
    assert cards_pocket[1].rank == "A"


def test_poker_hand_evaluation():
    # Royal Flush
    rf_cards = parse_cards("Ah Kh Qh Jh Th 2c 3d")
    score = evaluate_7card(rf_cards)
    assert hand_name(score) == "Royal Flush"

    # Four of a Kind
    quad_cards = parse_cards("9h 9d 9s 9c Kh 2d 3s")
    score = evaluate_7card(quad_cards)
    assert hand_name(score) == "Four of a Kind"

    # Flush
    flush_cards = parse_cards("2h 5h 8h Jh Ah Kd Qs")
    score = evaluate_7card(flush_cards)
    assert hand_name(score) == "Flush"


def test_poker_monte_carlo_equity_and_ev():
    # Pocket Aces vs 1 opponent pre-flop
    res = PokerEngine.analyze("Ah Ad", "", pot=100, to_call=20, num_opponents=1)
    assert res["equity_pct"] > 75.0  # AA heads-up is ~80-85%
    assert res["recommendation"].startswith("Raise")
    assert "spoken" in res

    # Terrible hand with large call needed -> Fold
    res_fold = PokerEngine.analyze("7c 2d", "Ah Kh Qh", pot=20, to_call=100, num_opponents=2)
    assert res_fold["equity_pct"] < 15.0
    assert "Fold" in res_fold["recommendation"]


# ----------------- Board Game Referee Tests -----------------

def test_board_game_referee_monopoly():
    res = BoardGameReferee.rule_lookup("monopoly", "jail")
    assert res.get("found") is True
    assert "doubles" in res["rule"]
    assert "spoken" in res


def test_board_game_referee_uno():
    res = BoardGameReferee.rule_lookup("uno", "stacking")
    assert "CANNOT stack" in res["rule"]


def test_board_game_referee_catan():
    res = BoardGameReferee.rule_lookup("catan", "robber")
    assert "discard half" in res["rule"]


# ----------------- Game Manager Tests -----------------

@pytest.mark.asyncio
async def test_game_manager_dispatch():
    gm = GameManager()

    # Move push
    m_res = await gm.dispatch("chess_move", {"move": "e4"})
    assert m_res["move"] == "e4"
    assert "fen" in m_res

    # State query
    state = await gm.dispatch("chess_state")
    assert state["turn"] == "black"

    # Best move
    best = await gm.dispatch("chess_best")
    assert "best_move_san" in best

    # Reset
    reset = await gm.dispatch("chess_reset")
    assert reset["reset"] is True

    # Style change
    st = await gm.dispatch("set_style", {"style": "teach"})
    assert st["style"] == "teach"
    assert gm.chess.style == "teach"

    # Poker equity
    poker = await gm.dispatch("poker_equity", {"hole": "Ks Kd", "pot": 50, "to_call": 10})
    assert poker["equity_pct"] > 60

    # Board game referee
    ref = await gm.dispatch("rules", {"game": "uno", "topic": "stacking"})
    assert "CANNOT stack" in ref["rule"]


# ----------------- Tools Integration Tests -----------------

@pytest.mark.asyncio
async def test_tools_game_integration(settings):
    router = type("RouterMock", (), {"settings": settings})()
    memory = Memory(settings)
    web = object()
    tools = Tools(router, memory, web)

    res = await tools.run("game", {"action": "chess_move", "move": "e4"})
    assert res.get("move") == "e4"

    res_poker = await tools.run("game", {"action": "poker_equity", "hole": "Ah As", "pot": 100, "to_call": 20})
    assert res_poker["equity_pct"] > 70


# ----------------- HTTP API Tests -----------------

def client_for(settings, budget, pairing, keys):
    app = create_app(
        settings=settings,
        keys=keys,
        budget=budget,
        pairing=pairing,
        providers={"gemini": FakeProvider("gemini")},
        local_hosts=frozenset({"testclient"}),
        page_hosts=frozenset({"testserver"}),
        advertise=False,
    )
    return TestClient(app)


def test_api_game_routes(settings, budget, pairing):
    keys = FakeKeys()
    with client_for(settings, budget, pairing, keys) as c:
        # GET state
        r = c.get("/api/game/state")
        assert r.status_code == 200
        data = r.json()
        assert "fen" in data
        assert data["turn"] == "white"

        # POST chess move
        r = c.post("/api/game/chess/move", json={"move": "e4"})
        assert r.status_code == 200
        assert r.json()["move"] == "e4"

        # GET best move
        r = c.get("/api/game/chess/best")
        assert r.status_code == 200
        assert "best_move_san" in r.json()

        # POST poker equity
        r = c.post("/api/game/poker/equity", json={"hole": "Qh Qd", "pot": 80, "to_call": 10})
        assert r.status_code == 200
        assert "equity_pct" in r.json()

        # POST reset
        r = c.post("/api/game/reset", json={})
        assert r.status_code == 200
        assert r.json()["reset"] is True

        # POST vision
        r = c.post("/api/game/vision", json={"image_b64": "fake_jpeg_data", "game": "chess"})
        assert r.status_code == 200
        assert "game" in r.json()


@pytest.mark.asyncio
async def test_game_manager_vision_chess():
    gm = GameManager()

    class VisionMockRouter:
        async def complete(self, req):
            fake_json = (
                '{"game": "chess", "fen": "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3", '
                '"description": "Italian Game opening", "suggested_move": "Bc4"}'
            )
            from ronie_brain.models.base import LLMResult
            return LLMResult(fake_json, "gemini", "gemini-3.5-flash-lite")

    res = await gm.analyze_vision("fake_b64_image", router=VisionMockRouter())
    assert res["game"] == "chess"
    assert "fen" in res
    assert "best_move" in res
    assert "spoken" in res
    assert "Italian Game" in res["description"]


async def test_best_move_for_a_board_read_from_a_photo():
    from ronie_brain.games import GameManager

    gm = GameManager()
    fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"
    r = await gm.dispatch("chess_best", {"fen": fen})
    assert r["fen"].startswith("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2")  # the photo's board, not the starting one
    assert "error" in await gm.dispatch("chess_best", {"fen": "not a board"})


async def test_poker_fold_wording():
    from ronie_brain.games import GameManager

    r = await GameManager().dispatch("poker_equity", {"hole": "7c 2d", "board": "Ah Kh Qh", "pot": 10, "to_call": 100})
    assert r["recommendation"].startswith("Fold") and "fold" in r["spoken"] and "profitable" not in r["spoken"]


def test_spoken_starting_hands():
    from ronie_brain.games import parse_cards

    assert [str(c) for c in parse_cards("Ace King suited")] == ["As", "Ks"]
    assert [str(c) for c in parse_cards("ace king offsuit")] == ["As", "Kh"]
    assert [str(c) for c in parse_cards("AKs")] == ["As", "Ks"]
    assert [str(c) for c in parse_cards("Ah Kd")] == ["Ah", "Kd"]  # exact cards still work
    assert len(parse_cards("Ace of hearts, King of spades")) == 2
