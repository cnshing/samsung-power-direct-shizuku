// SPAKE2 for Android's wireless-debugging pairing handshake.
//
// Derived from Key Mapper's sysbridge/src/main/cpp/adb_pairing.cpp (GPL-3.0),
// which in turn follows AOSP's adb/pairing_auth.cpp. Same licence as this
// project. The Kotlin side owns the socket and the framing; this file owns only
// the cryptography, because SPAKE2 is the one piece the Android platform does
// not hand to an app.
//
// The protocol: both sides derive a shared secret from the six-digit pairing
// code plus the TLS exporter material, exchange SPAKE2 messages, then talk
// AES-128-GCM with a counter as the nonce. Getting the counter wrong is the
// classic way to break it, so the sequence numbers live in the context and are
// bumped exactly once per operation.

#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <android/log.h>
#include <openssl/curve25519.h>
#include <openssl/hkdf.h>
#include <openssl/evp.h>

#define LOG_TAG "powerdirect-pairing"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// We are always the client; adbd is the server. The names are part of the
// SPAKE2 transcript, so they must match AOSP's byte for byte.
constexpr spake2_role_t kClientRole = spake2_role_alice;
const uint8_t kClientName[] = "adb pair client";
const uint8_t kServerName[] = "adb pair server";

constexpr size_t kHkdfKeyLength = 16;

struct PairingContext {
    SPAKE2_CTX *spake2;
    uint8_t msg[SPAKE2_MAX_MSG_SIZE];
    size_t msg_size;

    EVP_AEAD_CTX *aead;
    uint64_t decrypt_sequence;
    uint64_t encrypt_sequence;
};

jlong Constructor(JNIEnv *env, jclass, jbyteArray jPassword) {
    auto *spake2 = SPAKE2_CTX_new(kClientRole,
                                  kClientName, sizeof(kClientName),
                                  kServerName, sizeof(kServerName));
    if (spake2 == nullptr) {
        LOGE("cannot create a SPAKE2 context");
        return 0;
    }

    auto password_size = env->GetArrayLength(jPassword);
    auto *password = env->GetByteArrayElements(jPassword, nullptr);

    size_t msg_size = 0;
    uint8_t msg[SPAKE2_MAX_MSG_SIZE];
    int ok = SPAKE2_generate_msg(spake2, msg, &msg_size, SPAKE2_MAX_MSG_SIZE,
                                 (uint8_t *) password, password_size);
    env->ReleaseByteArrayElements(jPassword, password, JNI_ABORT);

    if (ok != 1 || msg_size == 0) {
        LOGE("cannot generate the SPAKE2 public key");
        SPAKE2_CTX_free(spake2);
        return 0;
    }

    auto *ctx = (PairingContext *) calloc(1, sizeof(PairingContext));
    if (ctx == nullptr) {
        SPAKE2_CTX_free(spake2);
        return 0;
    }
    ctx->spake2 = spake2;
    memcpy(ctx->msg, msg, msg_size);
    ctx->msg_size = msg_size;
    return (jlong) ctx;
}

jbyteArray Msg(JNIEnv *env, jobject, jlong ptr) {
    auto *ctx = (PairingContext *) ptr;
    jbyteArray out = env->NewByteArray(ctx->msg_size);
    env->SetByteArrayRegion(out, 0, ctx->msg_size, (jbyte *) ctx->msg);
    return out;
}

jboolean InitCipher(JNIEnv *env, jobject, jlong ptr, jbyteArray jTheirMsg) {
    auto *ctx = (PairingContext *) ptr;
    auto their_size = env->GetArrayLength(jTheirMsg);

    if (their_size > SPAKE2_MAX_MSG_SIZE) {
        LOGE("their message is %d bytes, over the %d maximum", their_size, SPAKE2_MAX_MSG_SIZE);
        return JNI_FALSE;
    }

    auto *their_msg = env->GetByteArrayElements(jTheirMsg, nullptr);

    size_t material_size = 0;
    uint8_t material[SPAKE2_MAX_KEY_SIZE];
    int ok = SPAKE2_process_msg(ctx->spake2, material, &material_size, sizeof(material),
                                (uint8_t *) their_msg, their_size);
    env->ReleaseByteArrayElements(jTheirMsg, their_msg, JNI_ABORT);

    if (ok != 1) {
        LOGE("cannot process their public key");
        return JNI_FALSE;
    }

    uint8_t key[kHkdfKeyLength];
    uint8_t info[] = "adb pairing_auth aes-128-gcm key";
    if (HKDF(key, sizeof(key), EVP_sha256(), material, material_size,
             nullptr, 0, info, sizeof(info) - 1) != 1) {
        LOGE("HKDF failed");
        return JNI_FALSE;
    }

    ctx->aead = EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(), key, sizeof(key),
                                 EVP_AEAD_DEFAULT_TAG_LENGTH);
    if (ctx->aead == nullptr) {
        LOGE("cannot create the AEAD context");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// The nonce is the little-endian sequence number, zero-padded. Each direction
// counts separately.
void nonce_from(uint64_t sequence, uint8_t *nonce, size_t nonce_size) {
    memset(nonce, 0, nonce_size);
    memcpy(nonce, &sequence, sizeof(sequence) < nonce_size ? sizeof(sequence) : nonce_size);
}

jbyteArray Encrypt(JNIEnv *env, jobject, jlong ptr, jbyteArray jIn) {
    auto *ctx = (PairingContext *) ptr;
    if (ctx->aead == nullptr) return nullptr;

    auto *in = env->GetByteArrayElements(jIn, nullptr);
    auto in_size = env->GetArrayLength(jIn);

    size_t out_size = (size_t) in_size + EVP_AEAD_max_overhead(EVP_AEAD_CTX_aead(ctx->aead));
    auto *out = (uint8_t *) malloc(out_size);
    if (out == nullptr) {
        env->ReleaseByteArrayElements(jIn, in, JNI_ABORT);
        return nullptr;
    }

    auto nonce_size = EVP_AEAD_nonce_length(EVP_AEAD_CTX_aead(ctx->aead));
    uint8_t nonce[EVP_AEAD_MAX_NONCE_LENGTH];
    nonce_from(ctx->encrypt_sequence, nonce, nonce_size);

    size_t written = 0;
    int ok = EVP_AEAD_CTX_seal(ctx->aead, out, &written, out_size, nonce, nonce_size,
                               (uint8_t *) in, in_size, nullptr, 0);
    env->ReleaseByteArrayElements(jIn, in, JNI_ABORT);

    if (!ok) {
        LOGE("cannot encrypt %d bytes", in_size);
        free(out);
        return nullptr;
    }
    ctx->encrypt_sequence++;

    jbyteArray result = env->NewByteArray(written);
    env->SetByteArrayRegion(result, 0, written, (jbyte *) out);
    free(out);
    return result;
}

jbyteArray Decrypt(JNIEnv *env, jobject, jlong ptr, jbyteArray jIn) {
    auto *ctx = (PairingContext *) ptr;
    if (ctx->aead == nullptr) return nullptr;

    auto *in = env->GetByteArrayElements(jIn, nullptr);
    auto in_size = env->GetArrayLength(jIn);

    auto out_size = (size_t) in_size;
    auto *out = (uint8_t *) malloc(out_size);
    if (out == nullptr) {
        env->ReleaseByteArrayElements(jIn, in, JNI_ABORT);
        return nullptr;
    }

    auto nonce_size = EVP_AEAD_nonce_length(EVP_AEAD_CTX_aead(ctx->aead));
    uint8_t nonce[EVP_AEAD_MAX_NONCE_LENGTH];
    nonce_from(ctx->decrypt_sequence, nonce, nonce_size);

    size_t written = 0;
    int ok = EVP_AEAD_CTX_open(ctx->aead, out, &written, out_size, nonce, nonce_size,
                               (uint8_t *) in, in_size, nullptr, 0);
    env->ReleaseByteArrayElements(jIn, in, JNI_ABORT);

    if (!ok) {
        // Overwhelmingly the wrong pairing code, which is why the Kotlin side
        // turns a null here into "that code was wrong" rather than an error.
        free(out);
        return nullptr;
    }
    ctx->decrypt_sequence++;

    jbyteArray result = env->NewByteArray(written);
    env->SetByteArrayRegion(result, 0, written, (jbyte *) out);
    free(out);
    return result;
}

void Destroy(JNIEnv *, jobject, jlong ptr) {
    auto *ctx = (PairingContext *) ptr;
    if (ctx == nullptr) return;
    SPAKE2_CTX_free(ctx->spake2);
    if (ctx->aead != nullptr) EVP_AEAD_CTX_free(ctx->aead);
    free(ctx);
}

const JNINativeMethod kMethods[] = {
        {"nativeConstructor", "([B)J",   (void *) Constructor},
        {"nativeMsg",         "(J)[B",   (void *) Msg},
        {"nativeInitCipher",  "(J[B)Z",  (void *) InitCipher},
        {"nativeEncrypt",     "(J[B)[B", (void *) Encrypt},
        {"nativeDecrypt",     "(J[B)[B", (void *) Decrypt},
        {"nativeDestroy",     "(J)V",    (void *) Destroy},
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;

    jclass clazz = env->FindClass("moe/qwreey/powerdirect/adb/PairingContext");
    if (clazz == nullptr) return -1;

    if (env->RegisterNatives(clazz, kMethods, sizeof(kMethods) / sizeof(JNINativeMethod)) != 0) {
        return -1;
    }
    return JNI_VERSION_1_6;
}
