package moe.qwreey.powerdirect.adb

import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket
import moe.qwreey.powerdirect.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import moe.qwreey.powerdirect.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import moe.qwreey.powerdirect.adb.AdbProtocol.A_AUTH
import moe.qwreey.powerdirect.adb.AdbProtocol.A_CLSE
import moe.qwreey.powerdirect.adb.AdbProtocol.A_CNXN
import moe.qwreey.powerdirect.adb.AdbProtocol.A_MAXDATA
import moe.qwreey.powerdirect.adb.AdbProtocol.A_OKAY
import moe.qwreey.powerdirect.adb.AdbProtocol.A_OPEN
import moe.qwreey.powerdirect.adb.AdbProtocol.A_STLS
import moe.qwreey.powerdirect.adb.AdbProtocol.A_STLS_VERSION
import moe.qwreey.powerdirect.adb.AdbProtocol.A_VERSION
import moe.qwreey.powerdirect.adb.AdbProtocol.A_WRTE

/**
 * An ADB client, talking to this device's own adbd over the loopback interface.
 *
 * Ported from Key Mapper's `AdbClient` (GPL-3.0), which took it from Shizuku.
 *
 * The point of it is one shell command. The app cannot open `/dev/uhid` and
 * never will be able to, so it borrows the shell user the only way an app can:
 * by being an ADB client to the device it is running on.
 */
internal class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) :
    Closeable {

    private var socket: Socket? = null
    private var tlsSocket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    fun connect() {
        val socket = Socket(host, port).also { this.socket = it }
        socket.tcpNoDelay = true
        // Without this a torn-down connection is indistinguishable from a slow
        // one and a read blocks for ever. That is not hypothetical: at boot,
        // something else turning wireless debugging off underneath us killed
        // the session mid-command, and the caller waited on it until the
        // process died -- so no retry ever ran and wireless debugging was
        // never put back.
        socket.soTimeout = READ_TIMEOUT_MILLIS
        input = DataInputStream(socket.getInputStream())
        output = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        when (message.command) {
            // Android 11 and up: adbd asks to switch to TLS, and the client
            // certificate is the whole of the authentication.
            A_STLS -> {
                write(A_STLS, A_STLS_VERSION, 0)
                startTls()
                message = read()
            }

            // The older challenge-response, still used over USB.
            A_AUTH -> {
                write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))
                message = read()
                if (message.command != A_CNXN) {
                    // Our key is unknown to adbd. Offering the public key is
                    // what makes the "Allow debugging?" dialog appear.
                    write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                    message = read()
                }
            }
        }

        if (message.command != A_CNXN) {
            throw AdbException("adbd did not accept the connection: $message")
        }
    }

    /**
     * Run one command and return everything it wrote.
     *
     * Both streams come back interleaved, exactly as `adb shell` shows them,
     * because adbd's shell service merges them and this asks for no better.
     */
    fun shell(command: String): String {
        val localId = 1
        write(A_OPEN, localId, 0, "shell:$command")

        val out = StringBuilder()
        var message = read()

        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    when (message.command) {
                        A_WRTE -> {
                            message.data?.let { out.append(String(it)) }
                            write(A_OKAY, localId, remoteId)
                        }

                        A_CLSE -> {
                            write(A_CLSE, localId, remoteId)
                            return out.toString()
                        }

                        else -> throw AdbException("unexpected $message while reading output")
                    }
                }
            }

            // adbd refused to open the stream at all.
            A_CLSE -> write(A_CLSE, localId, message.arg0)

            else -> throw AdbException("unexpected $message when opening a shell")
        }

        return out.toString()
    }

    private fun startTls() {
        val plain = socket ?: throw AdbException("not connected")
        val tls = key.sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        tls.soTimeout = READ_TIMEOUT_MILLIS
        tls.startHandshake()
        Log.d(TAG, "TLS handshake with adbd succeeded")

        tlsSocket = tls
        input = DataInputStream(tls.inputStream)
        output = DataOutputStream(tls.outputStream)
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) =
        write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, text: String) =
        write(AdbMessage(command, arg0, arg1, text))

    private fun write(message: AdbMessage) {
        val stream = output ?: throw AdbException("not connected")
        stream.write(message.toByteArray())
        stream.flush()
    }

    private fun read(): AdbMessage {
        val stream = input ?: throw AdbException("not connected")

        val header = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        stream.readFully(header.array())

        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val length = header.int
        val checksum = header.int
        val magic = header.int

        val data = if (length > 0) ByteArray(length).also { stream.readFully(it) } else null

        val message = AdbMessage(command, arg0, arg1, length, checksum, magic, data)
        if (!message.validate()) throw AdbException("malformed message from adbd: $message")
        return message
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { tlsSocket?.close() }
        runCatching { socket?.close() }
    }

    private companion object {
        const val TAG = "AdbClient"

        /**
         * Long enough for any command this app runs -- the bootstrap script
         * finishes in well under a second -- and short enough that a dead
         * connection is reported rather than waited on.
         */
        const val READ_TIMEOUT_MILLIS = 20_000
    }
}
