package moe.qwreey.powerdirect.adb

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A self-signed X.509 certificate, written by hand.
 *
 * ADB's TLS needs the client to present *a* certificate; neither side checks
 * who signed it, because the identity that matters is the ADB key carried
 * inside the pairing exchange. So this is a page of DER rather than a
 * dependency on a certificate library, which for one fixed certificate shape is
 * the cheaper of the two. [SelfSignedCertificateTest] parses the result back
 * and checks the signature, which is the whole of what it has to get right.
 */
internal object SelfSignedCertificate {
    fun create(publicKey: PublicKey, privateKey: PrivateKey, commonName: String): X509Certificate {
        val tbs = tbsCertificate(publicKey, commonName)

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(tbs)
            sign()
        }

        val certificate = sequence(tbs, signatureAlgorithm(), bitString(signature))

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
    }

    private fun tbsCertificate(publicKey: PublicKey, commonName: String): ByteArray = sequence(
        // [0] EXPLICIT version, 2 meaning v3.
        tagged(0xA0, integer(BigInteger.valueOf(2))),
        integer(BigInteger.ONE),
        signatureAlgorithm(),
        name(commonName),
        // 2000 to 2049, the whole range UTCTime expresses without ambiguity.
        sequence(utcTime("000101000000Z"), utcTime("491231235959Z")),
        name(commonName),
        // Already a DER SubjectPublicKeyInfo, which is what belongs here verbatim.
        publicKey.encoded,
    )

    /** sha256WithRSAEncryption, 1.2.840.113549.1.1.11, with NULL parameters. */
    private fun signatureAlgorithm(): ByteArray = sequence(
        byteArrayOf(
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
        ),
        byteArrayOf(0x05, 0x00),
    )

    /** A Name holding a single CN attribute. */
    private fun name(commonName: String): ByteArray = sequence(
        set(
            sequence(
                byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), // id-at-commonName, 2.5.4.3
                tlv(0x0C, commonName.toByteArray()), // UTF8String
            ),
        ),
    )

    private fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    /** A BIT STRING whose first byte counts the unused trailing bits: none. */
    private fun bitString(value: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + value)

    private fun utcTime(value: String): ByteArray = tlv(0x17, value.toByteArray())

    private fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))

    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(parts))

    private fun tagged(tag: Int, vararg parts: ByteArray): ByteArray = tlv(tag, concat(parts))

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + length(value.size) + value

    /** DER length: the short form under 128, otherwise a count of length bytes. */
    private fun length(size: Int): ByteArray {
        if (size < 0x80) return byteArrayOf(size.toByte())

        var remaining = size
        val bytes = ArrayList<Byte>(4)
        while (remaining > 0) {
            bytes.add(0, (remaining and 0xff).toByte())
            remaining = remaining ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }
}
