package moe.qwreey.powerdirect.adb

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket
import org.conscrypt.Conscrypt

/**
 * The wireless-debugging pairing handshake.
 *
 * Ported from Key Mapper's `AdbPairingClient` (GPL-3.0), which took it from
 * Shizuku; all of them follow AOSP's `adb/pairing_connection.cpp`.
 *
 * Pairing is what gets our key into adbd's authorized list, and it happens
 * exactly once per device. The secret is the six digits Android shows plus the
 * TLS connection's own exported keying material, run through SPAKE2 -- so
 * neither the digits nor a recorded conversation is enough on its own. The
 * SPAKE2 itself is in the native library; this file is the framing around it.
 */
internal class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val key: AdbKey,
) : Closeable {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var pairing: PairingContext? = null

    /** @throws AdbPairingCodeException if the code was wrong. */
    fun pair() {
        connect()
        exchangeSpake2Messages()
        exchangePeerInfo()
    }

    private fun connect() {
        val plain = Socket(host, port).also { socket = it }
        plain.tcpNoDelay = true

        val tls = key.sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        tls.startHandshake()

        input = DataInputStream(tls.inputStream)
        output = DataOutputStream(tls.outputStream)

        // The password is the pairing code with the TLS exporter output
        // appended, which is what binds the handshake to this connection.
        val material = Conscrypt.exportKeyingMaterial(tls, EXPORT_LABEL, null, EXPORT_SIZE)
        val password = pairingCode.toByteArray() + material

        pairing = PairingContext.create(password)
            ?: throw AdbException("cannot start SPAKE2")
    }

    private fun exchangeSpake2Messages() {
        val pairing = pairing ?: throw AdbException("not connected")

        writePacket(PacketType.Spake2, pairing.message)

        val header = readHeader()
        if (header.type != PacketType.Spake2) {
            throw AdbException("expected a SPAKE2 message, got ${header.type}")
        }

        val theirs = ByteArray(header.payloadSize).also { readFully(it) }
        if (!pairing.initCipher(theirs)) throw AdbException("SPAKE2 exchange failed")
    }

    private fun exchangePeerInfo() {
        val pairing = pairing ?: throw AdbException("not connected")

        // Our half: the public key adbd will remember us by.
        val info = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        info.put(PEER_INFO_ADB_RSA_PUB_KEY)
        info.put(key.adbPublicKey.copyOf(MAX_PEER_INFO_SIZE - 1))

        val encrypted = pairing.encrypt(info.array()) ?: throw AdbException("cannot encrypt")
        writePacket(PacketType.PeerInfo, encrypted)

        val header = readHeader()
        if (header.type != PacketType.PeerInfo) {
            throw AdbException("expected peer info, got ${header.type}")
        }

        val theirs = ByteArray(header.payloadSize).also { readFully(it) }

        // Failing to decrypt means the shared secret differs, which in practice
        // means the six digits were mistyped. It is the only error here a user
        // can do anything about, so it gets its own type.
        pairing.decrypt(theirs) ?: throw AdbPairingCodeException()
    }

    private enum class PacketType(val value: Byte) {
        Spake2(0),
        PeerInfo(1),
        ;

        companion object {
            fun of(value: Byte): PacketType? = entries.firstOrNull { it.value == value }
        }
    }

    private class Header(val type: PacketType, val payloadSize: Int)

    private fun writePacket(type: PacketType, payload: ByteArray) {
        val stream = output ?: throw AdbException("not connected")

        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(HEADER_VERSION)
        header.put(type.value)
        header.putInt(payload.size)

        stream.write(header.array())
        stream.write(payload)
        stream.flush()
    }

    private fun readHeader(): Header {
        val bytes = ByteArray(HEADER_SIZE).also { readFully(it) }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val version = buffer.get()
        val type = PacketType.of(buffer.get())
        val payloadSize = buffer.int

        if (version != HEADER_VERSION) {
            throw AdbException("pairing header version $version, expected $HEADER_VERSION")
        }
        if (type == null) throw AdbException("unknown pairing packet type")
        if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD_SIZE) {
            throw AdbException("pairing payload of $payloadSize bytes is out of range")
        }
        return Header(type, payloadSize)
    }

    private fun readFully(into: ByteArray) {
        (input ?: throw AdbException("not connected")).readFully(into)
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        pairing?.destroy()
    }

    private companion object {
        const val HEADER_VERSION: Byte = 1
        const val HEADER_SIZE = 6

        const val MAX_PEER_INFO_SIZE = 8192
        const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2
        const val PEER_INFO_ADB_RSA_PUB_KEY: Byte = 0

        // Both constants are part of AOSP's protocol, not ours to choose --
        // including the NUL inside the label, which AOSP counts as part of it.
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_SIZE = 64
    }
}

/**
 * The native SPAKE2 state. One instance per pairing attempt; [destroy] frees
 * the C side, and nothing may be called afterwards.
 */
internal class PairingContext private constructor(private val pointer: Long) {
    val message: ByteArray = nativeMsg(pointer)

    fun initCipher(theirMessage: ByteArray): Boolean = nativeInitCipher(pointer, theirMessage)

    fun encrypt(plaintext: ByteArray): ByteArray? = nativeEncrypt(pointer, plaintext)

    fun decrypt(ciphertext: ByteArray): ByteArray? = nativeDecrypt(pointer, ciphertext)

    fun destroy() = nativeDestroy(pointer)

    private external fun nativeMsg(pointer: Long): ByteArray

    private external fun nativeInitCipher(pointer: Long, theirMessage: ByteArray): Boolean

    private external fun nativeEncrypt(pointer: Long, plaintext: ByteArray): ByteArray?

    private external fun nativeDecrypt(pointer: Long, ciphertext: ByteArray): ByteArray?

    private external fun nativeDestroy(pointer: Long)

    companion object {
        init {
            System.loadLibrary("adbpairing")
        }

        fun create(password: ByteArray): PairingContext? =
            nativeConstructor(password).takeIf { it != 0L }?.let { PairingContext(it) }

        @JvmStatic
        private external fun nativeConstructor(password: ByteArray): Long
    }
}
