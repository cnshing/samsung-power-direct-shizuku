package moe.qwreey.powerdirect.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ADB wire protocol, as much of it as connecting and running one command
 * needs.
 *
 * Ported from Key Mapper's `sysbridge/.../adb` (GPL-3.0, the same licence as
 * this project), which took it from Shizuku; both follow AOSP's `adb`. The
 * constants and framing are copied deliberately rather than rediscovered --
 * there is exactly one correct answer here and it is already theirs.
 */
internal object AdbProtocol {
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534C5453

    const val A_VERSION = 0x01000000
    const val A_MAXDATA = 4096
    const val A_STLS_VERSION = 0x01000000

    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3

    fun name(command: Int): String = when (command) {
        A_CNXN -> "A_CNXN"
        A_AUTH -> "A_AUTH"
        A_OPEN -> "A_OPEN"
        A_OKAY -> "A_OKAY"
        A_CLSE -> "A_CLSE"
        A_WRTE -> "A_WRTE"
        A_STLS -> "A_STLS"
        else -> "0x${command.toString(16)}"
    }
}

/** One ADB packet: a 24-byte little-endian header and an optional payload. */
internal class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val checksum: Int,
    val magic: Int,
    val data: ByteArray?,
) {
    constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) : this(
        command,
        arg0,
        arg1,
        data?.size ?: 0,
        checksum(data),
        command.inv(),
        data,
    )

    /** Strings on the wire are NUL-terminated, and the terminator is counted. */
    constructor(command: Int, arg0: Int, arg1: Int, text: String) :
        this(command, arg0, arg1, (text + "\u0000").toByteArray())

    fun toByteArray(): ByteArray =
        ByteBuffer.allocate(HEADER_LENGTH + (data?.size ?: 0)).order(ByteOrder.LITTLE_ENDIAN).run {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(dataLength)
            putInt(checksum)
            putInt(magic)
            data?.let { put(it) }
            array()
        }

    fun validate(): Boolean {
        if (command != magic.inv()) return false
        if (dataLength != 0 && checksum(data) != checksum) return false
        return true
    }

    override fun toString(): String =
        "${AdbProtocol.name(command)}(arg0=$arg0, arg1=$arg1, length=$dataLength)"

    companion object {
        const val HEADER_LENGTH = 24

        /** ADB's own checksum: the payload bytes summed as unsigned. */
        private fun checksum(data: ByteArray?): Int {
            if (data == null) return 0
            var sum = 0
            for (b in data) sum += b.toInt() and 0xff
            return sum
        }
    }
}

internal open class AdbException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** The pairing code was wrong -- the one failure a user can actually fix. */
internal class AdbPairingCodeException : AdbException("wrong pairing code")
