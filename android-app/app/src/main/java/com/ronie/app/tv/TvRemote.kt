package com.ronie.app.tv

import android.content.Context
import com.ronie.app.voice.Diag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The phone as a TV remote, through Android TV's own remote service (the protocol the Google TV remote app uses):
 * pairing once on port 6467 with a code shown on the TV, then keys and links on port 6466. Works with the laptop off.
 */
class TvRemote(context: Context) {
    private val dir = File(context.filesDir, "tv").apply { mkdirs() }
    private val keyFile = File(dir, "identity.p12")
    private val pairedFile = File(dir, "paired")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var session: Session? = null

    /** The TV's address it was paired with, or null. */
    val pairedHost: String? get() = pairedFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }

    // ---------- pairing ----------

    /** Starts pairing: the TV shows a 6-character code; pass it to [Pairing.finish]. */
    suspend fun startPairing(host: String): Pairing = withContext(Dispatchers.IO) {
        val socket = connect(host, PAIRING_PORT)
        val out = socket.outputStream
        val input = socket.inputStream
        write(out, pairing(field(10, field(1, "atvremote") + field(2, "Ronie"))))
        read(input) // pairing request ack
        val encoding = field(1, 3) + field(2, 6) // hexadecimal, 6 symbols
        write(out, pairing(field(20, field(1, encoding) + field(3, 1)))) // options: we enter the code
        read(input)
        write(out, pairing(field(30, field(1, encoding) + field(2, 1)))) // configuration
        read(input) // configuration ack: the TV now shows the code
        Pairing(host, socket)
    }

    inner class Pairing(private val host: String, private val socket: SSLSocket) {
        suspend fun finish(code: String) = withContext(Dispatchers.IO) {
            val clean = code.trim().uppercase().replace(" ", "")
            require(clean.length == 6) { "The code has 6 characters" }
            val client = identity().second.publicKey as RSAPublicKey
            val server = socket.session.peerCertificates[0].publicKey as RSAPublicKey
            val sha = MessageDigest.getInstance("SHA-256")
            sha.update(hex(client.modulus.toString(16).uppercase()))
            sha.update(hex("0" + client.publicExponent.toString(16).uppercase()))
            sha.update(hex(server.modulus.toString(16).uppercase()))
            sha.update(hex("0" + server.publicExponent.toString(16).uppercase()))
            sha.update(hex(clean.substring(2)))
            val secret = sha.digest()
            if ((secret[0].toInt() and 0xFF) != clean.substring(0, 2).toInt(16)) {
                socket.close()
                throw IllegalArgumentException("That code doesn't match the one on the TV")
            }
            write(socket.outputStream, pairing(field(40, field(1, secret))))
            val ack = parse(read(socket.inputStream))
            socket.close()
            if (ack.firstOrNull { it.first == 2 }?.second != 200L) throw IllegalStateException("The TV refused the code")
            pairedFile.writeText(host)
            reset()
            Diag.log("tv: paired with $host")
        }

        fun cancel() = runCatching { socket.close() }
    }

    private fun pairing(body: ByteArray) = field(1, 2) + field(2, 200) + body

    // ---------- commands ----------

    /** Presses keys / opens links on the TV. Returns the app on screen (package name), if the TV said. */
    suspend fun run(host: String, ops: List<Op>): String = lock.withLock {
        if (pairedHost == null) throw IllegalStateException("The phone isn't paired with the TV yet")
        for (attempt in 1..2) {
            val s = session(host)
            try {
                for (op in ops) s.send(op)
                return@withLock s.foreground
            } catch (e: Exception) {
                Diag.log("tv: sending failed (attempt $attempt): ${e.message}")
                s.close()
                session = null
                if (attempt == 2) throw e
            }
        }
        ""
    }

    /**
     * The open connection, or a new one. The TV pings every few seconds, so a connection it hasn't spoken on lately
     * is dead (the TV slept or dropped Wi-Fi) even though writing to it still "works": keys sent there were lost while
     * Ronie said "done" (31 Oct).
     */
    private suspend fun session(host: String): Session {
        session?.let { s ->
            if (s.alive && s.host == host && System.currentTimeMillis() - s.lastHeard < STALE_MS) return s
            Diag.log("tv: connection ${if (s.alive) "silent for ${(System.currentTimeMillis() - s.lastHeard) / 1000}s" else "closed"}; reconnecting")
            s.close()
        }
        session = null
        val s = Session(host)
        try {
            s.open()
        } catch (e: Exception) {
            s.close()
            Diag.log("tv: couldn't connect to $host: ${e.message}")
            throw IllegalStateException("Couldn't reach the TV at $host. Is it on the same Wi-Fi?")
        }
        Diag.log("tv: connected to $host (screen ${when (s.screenOn) { true -> "on"; false -> "off"; null -> "unknown" }})")
        session = s
        return s
    }

    /** The TV's volume as (level, maximum), if the open connection has heard it. */
    val volume: Pair<Int, Int>? get() = session?.volume

    /** Whether the TV's screen is on, if the open connection has heard. */
    val screenOn: Boolean? get() = session?.screenOn

    /** Drops the connection, e.g. after pairing again, so the next command starts fresh. */
    fun reset() {
        session?.close()
        session = null
    }

    sealed class Op {
        data class Key(val code: Int) : Op()
        data class Link(val url: String) : Op()
        data class Sleep(val seconds: Double) : Op()
    }

    private inner class Session(val host: String) {
        private lateinit var socket: SSLSocket
        @Volatile var alive = false
        @Volatile var foreground = ""
        /** Whether the TV's screen is on, from its remote_start message; null until it says. */
        @Volatile var screenOn: Boolean? = null
        @Volatile var lastHeard = System.currentTimeMillis()
        /** (level, maximum), from the TV's volume messages; null until it says. */
        @Volatile var volume: Pair<Int, Int>? = null
        private val ready = CompletableDeferred<Unit>()

        suspend fun open() {
            socket = withContext(Dispatchers.IO) { connect(host, REMOTE_PORT) }
            alive = true
            scope.launch { listen() }
            withTimeout(8_000) { ready.await() }
            delay(800) // keys sent the instant it's ready are ignored
        }

        private fun listen() {
            try {
                while (true) {
                    val msg = parse(read(socket.inputStream)).firstOrNull() ?: continue
                    lastHeard = System.currentTimeMillis()
                    val body = msg.second as? ByteArray ?: ByteArray(0)
                    when (msg.first) {
                        1 -> { // remote_configure: answer with who we are
                            val info = field(1, "Ronie") + field(2, "Ronie") + field(3, 1) + field(4, "1") +
                                field(5, "atvremote") + field(6, "1.0.0")
                            send(field(1, field(1, 622) + field(2, info)))
                        }
                        2 -> { // remote_set_active
                            send(field(2, field(1, 622)))
                            ready.complete(Unit)
                        }
                        8 -> send(field(9, field(1, (parse(body).firstOrNull { it.first == 1 }?.second as? Long ?: 0L).toInt())))
                        20 -> appOnScreen(body)?.let { foreground = it }
                        40 -> screenOn = (parse(body).firstOrNull { it.first == 1 }?.second as? Long ?: 0L) != 0L
                        50 -> { // remote_set_volume_level: field 6 the maximum, 7 the level
                            val f = parse(body)
                            val max = (f.firstOrNull { it.first == 6 }?.second as? Long)?.toInt() ?: 0
                            val level = (f.firstOrNull { it.first == 7 }?.second as? Long)?.toInt() ?: 0
                            if (max > 0) volume = level to max
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                alive = false
                ready.completeExceptionally(IllegalStateException("The TV closed the remote connection"))
            }
        }

        private suspend fun pressKey(code: Int) = send(field(10, field(1, code) + field(2, 3))) // SHORT press

        /** remote_ime_key_inject carries the app on screen: field 1 (app info) -> field 12 (package). */
        private fun appOnScreen(body: ByteArray): String? {
            val appInfo = parse(body).firstOrNull { it.first == 1 }?.second as? ByteArray ?: return null
            return (parse(appInfo).firstOrNull { it.first == 12 }?.second as? ByteArray)?.toString(Charsets.UTF_8)
        }

        suspend fun send(op: Op) {
            when (op) {
                // Some TVs ignore SLEEP (seen on the user's TV): when the screen is on, Power turns it off.
                is Op.Key -> if (op.code == KEY_SLEEP && screenOn == true) pressKey(KEY_POWER)
                    else if (op.code == KEY_SLEEP && screenOn == false) Unit
                    else pressKey(op.code)
                is Op.Link -> send(field(90, field(1, op.url)))
                is Op.Sleep -> delay((op.seconds * 1000).toLong())
            }
        }

        @Synchronized
        private fun send(msg: ByteArray) = write(socket.outputStream, msg)

        fun close() {
            alive = false
            runCatching { socket.close() }
        }
    }

    // ---------- TLS with our own certificate (the TV remembers it after pairing) ----------

    private fun identity(): Pair<java.security.PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("PKCS12")
        if (keyFile.exists()) {
            keyFile.inputStream().use { ks.load(it, PASS) }
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=ronie")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - DAY), Date(now + 3650 * DAY), name, kp.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            val cert = JcaX509CertificateConverter().getCertificate(holder)
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, kp.private, PASS, arrayOf(cert))
            keyFile.outputStream().use { ks.store(it, PASS) }
        }
        return ks.getKey(ALIAS, PASS) as java.security.PrivateKey to ks.getCertificate(ALIAS) as X509Certificate
    }

    private fun connect(host: String, port: Int): SSLSocket {
        val (key, cert) = identity()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry(ALIAS, key, PASS, arrayOf(cert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PASS) }
        val trustAll = object : X509TrustManager { // the TV's certificate is its own self-signed one
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        socket.connect(java.net.InetSocketAddress(host, port), 5_000)
        socket.soTimeout = 0
        socket.startHandshake()
        return socket
    }

    // ---------- protobuf, just what this protocol needs ----------

    private companion object {
        const val PAIRING_PORT = 6467
        const val REMOTE_PORT = 6466
        const val KEY_POWER = 26
        const val KEY_SLEEP = 223
        const val STALE_MS = 15_000L // the TV pings about every 5 s
        const val ALIAS = "ronie"
        val PASS = "ronie".toCharArray()
        const val DAY = 86_400_000L

        fun varint(value: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var v = value
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) out.write(b or 0x80) else { out.write(b); return out.toByteArray() }
            }
        }

        fun field(num: Int, value: Int) = varint((num shl 3).toLong()) + varint(value.toLong())
        fun field(num: Int, value: String) = field(num, value.toByteArray())
        fun field(num: Int, value: ByteArray) = varint((num shl 3 or 2).toLong()) + varint(value.size.toLong()) + value

        /** -> (field number, Long or ByteArray) */
        fun parse(data: ByteArray): List<Pair<Int, Any>> {
            val out = mutableListOf<Pair<Int, Any>>()
            var i = 0
            fun readVarint(): Long {
                var n = 0L
                var shift = 0
                while (true) {
                    val b = data[i++].toInt() and 0xFF
                    n = n or ((b and 0x7F).toLong() shl shift)
                    shift += 7
                    if (b and 0x80 == 0) return n
                }
            }
            while (i < data.size) {
                val key = readVarint()
                val num = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> out += num to readVarint()
                    2 -> {
                        val len = readVarint().toInt()
                        out += num to data.copyOfRange(i, i + len)
                        i += len
                    }
                    5 -> i += 4
                    1 -> i += 8
                    else -> return out
                }
            }
            return out
        }

        fun write(out: OutputStream, msg: ByteArray) {
            out.write(varint(msg.size.toLong()) + msg)
            out.flush()
        }

        fun read(input: InputStream): ByteArray {
            var n = 0
            var shift = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("closed")
                n = n or ((b and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = input.read(buf, got, n - got)
                if (r < 0) throw EOFException("closed")
                got += r
            }
            return buf
        }

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
