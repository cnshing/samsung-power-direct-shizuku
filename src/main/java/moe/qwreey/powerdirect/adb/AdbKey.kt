package moe.qwreey.powerdirect.adb

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import org.conscrypt.Conscrypt

/**
 * The RSA key that identifies this app to adbd, and the TLS context built on
 * it.
 *
 * Ported from Key Mapper's `AdbKey` (GPL-3.0), which took it from Shizuku.
 *
 * The key is generated in software rather than in the Android keystore, because
 * ADB's challenge-response wants a raw RSA operation with its own padding that
 * keystore keys will not perform. It is instead stored encrypted, under an
 * AES-GCM key that *is* keystore-backed and never leaves it. This key is
 * effectively a shell credential for the device, so it is worth the care.
 */
internal class AdbKey(private val store: KeyStorage, private val name: String) {
    private val encryptionKey: Key = getOrCreateEncryptionKey()
    private val privateKey: RSAPrivateKey = getOrCreatePrivateKey()

    private val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA").generatePublic(
        RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4),
    ) as RSAPublicKey

    private val certificate: X509Certificate =
        SelfSignedCertificate.create(publicKey, privateKey, "lan-mouse-android")

    /** The public key in the format adbd stores in its authorized key list. */
    val adbPublicKey: ByteArray by lazy { publicKey.adbEncoded(name) }

    /**
     * Sign an ADB auth token: raw RSA over a fixed PKCS#1 v1.5 SHA-1 padding.
     * Only the pre-TLS (USB) handshake uses this; wireless debugging
     * authenticates through the TLS certificate instead.
     */
    fun sign(data: ByteArray?): ByteArray = Cipher.getInstance("RSA/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, privateKey)
        update(SIGNATURE_PADDING)
        doFinal(data)
    }

    /**
     * TLS 1.3 through Conscrypt rather than the platform provider, because the
     * pairing handshake needs RFC 5705 exported keying material and only
     * Conscrypt's own API exposes it.
     */
    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(arrayOf(keyManager()), arrayOf(trustManager()), SecureRandom())
        }
    }

    private fun getOrCreateEncryptionKey(): Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(ENCRYPTION_KEY_ALIAS, null)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            ENCRYPTION_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        val aad = ByteArray(16).also { "adbkey".toByteArray().copyInto(it) }

        store.read()?.let { ciphertext ->
            runCatching {
                val plaintext = decrypt(ciphertext, aad)
                return KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            }
        }

        val generated = KeyPairGenerator.getInstance("RSA").run {
            initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            generateKeyPair().private as RSAPrivateKey
        }
        store.write(encrypt(generated.encoded, aad))
        return generated
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad)

        val ciphertext = ByteArray(IV_SIZE + plaintext.size + TAG_SIZE)
        cipher.doFinal(plaintext, 0, plaintext.size, ciphertext, IV_SIZE)
        cipher.iv.copyInto(ciphertext, 0)
        return ciphertext
    }

    private fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertext.size >= IV_SIZE + TAG_SIZE) { "stored key is truncated" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey,
            GCMParameterSpec(8 * TAG_SIZE, ciphertext, 0, IV_SIZE),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IV_SIZE, ciphertext.size - IV_SIZE)
    }

    private fun keyManager() = object : X509ExtendedKeyManager() {
        private val alias = "adb"

        override fun chooseClientAlias(
            keyTypes: Array<out String>,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = alias.takeIf { keyTypes.contains("RSA") }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == this.alias) arrayOf(certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? =
            if (alias == this.alias) privateKey else null

        override fun getClientAliases(k: String?, i: Array<out Principal>?): Array<String>? = null

        override fun getServerAliases(k: String, i: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(k: String, i: Array<out Principal>?, s: Socket?): String? =
            null
    }

    /**
     * Trusts everything, as adb's own client does. There is nothing to verify:
     * adbd's certificate is self-signed too, and the connection is to localhost
     * on this very device. Authentication runs the other way -- adbd decides
     * whether to trust *us*, from the key exchanged during pairing.
     */
    private fun trustManager() = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) = Unit

        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) =
            Unit

        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = Unit

        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) = Unit

        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) =
            Unit

        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Where the encrypted private key lives between runs. */
    interface KeyStorage {
        fun read(): ByteArray?

        fun write(bytes: ByteArray)
    }

    class PreferenceStorage(private val preferences: SharedPreferences) : KeyStorage {
        override fun read(): ByteArray? = preferences.getString(KEY, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }

        override fun write(bytes: ByteArray) {
            preferences.edit { putString(KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)) }
        }

        private companion object {
            const val KEY = "adbkey"
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ENCRYPTION_KEY_ALIAS = "adb_key_encryption"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_SIZE = 16

        /**
         * PKCS#1 v1.5 padding for a SHA-1 digest, precomputed: adbd signs a
         * fixed-size token, so the padding never varies.
         */
        val SIGNATURE_PADDING: ByteArray = byteArrayOf(0x00, 0x01) +
            ByteArray(218) { 0xff.toByte() } +
            byteArrayOf(
                0x00, 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b,
                0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
            )
    }
}

private const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
private const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
private const val RSA_PUBLIC_KEY_SIZE = 524

/**
 * The layout `libcrypto_utils`'s android_pubkey.c defines: a Montgomery
 * pre-computation alongside the modulus, little-endian, base64, then the key
 * name. adbd will not recognise anything else.
 */
private fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val rr = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
        .modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSA_PUBLIC_KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbWords().forEach(buffer::putInt)
    rr.toAdbWords().forEach(buffer::putInt)
    buffer.putInt(publicExponent.toInt())

    val encoded = Base64.encode(buffer.array(), Base64.NO_WRAP)
    return encoded + " $name\u0000".toByteArray()
}

/** Little-endian 32-bit words, zero-padded to the modulus size. */
private fun BigInteger.toAdbWords(): IntArray {
    val words = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)

    var remaining = this
    for (i in words.indices) {
        val (quotient, remainder) = remaining.divideAndRemainder(r32)
        remaining = quotient
        words[i] = remainder.toInt()
    }
    return words
}

private operator fun Array<BigInteger>.component1(): BigInteger = this[0]

private operator fun Array<BigInteger>.component2(): BigInteger = this[1]
