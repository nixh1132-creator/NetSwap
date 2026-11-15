#include <jni.h>
#include <cstdint>
#include <vector>
#include <mutex>
#include <new>
#include <cstring>

extern "C" {
void* netswap_tunnel_new(const uint8_t*, const uint8_t*, uint16_t, uint32_t);
void netswap_tunnel_free(void*);
int32_t netswap_tunnel_encapsulate(void*, const uint8_t*, size_t, uint8_t*, size_t, size_t*, uint32_t*);
int32_t netswap_tunnel_decapsulate(void*, const uint8_t*, size_t, uint8_t*, size_t, size_t*, uint32_t*);
}

struct EngineHandle { void* tunnel; std::mutex mutex; };
static void throwState(JNIEnv* env, const char* message) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, message);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_netswap_app_NativeWireGuardEngine_nativeCreate(JNIEnv* env, jobject, jbyteArray privateKey, jbyteArray peerKey, jint keepalive, jint index) {
    if (!privateKey || !peerKey || env->GetArrayLength(privateKey) != 32 || env->GetArrayLength(peerKey) != 32 || keepalive < 0 || keepalive > 65535) {
        throwState(env, "Invalid WireGuard key or keepalive length"); return 0;
    }
    jbyte a[32], b[32];
    env->GetByteArrayRegion(privateKey, 0, 32, a);
    env->GetByteArrayRegion(peerKey, 0, 32, b);
    if (env->ExceptionCheck()) return 0;
    void* tunnel = netswap_tunnel_new(reinterpret_cast<uint8_t*>(a), reinterpret_cast<uint8_t*>(b), static_cast<uint16_t>(keepalive), static_cast<uint32_t>(index));
    std::memset(a, 0, sizeof(a));
    std::memset(b, 0, sizeof(b));
    if (!tunnel) { throwState(env, "Native WireGuard tunnel creation failed"); return 0; }
    auto* handle = new (std::nothrow) EngineHandle{tunnel, {}};
    if (!handle) { netswap_tunnel_free(tunnel); throwState(env, "Native memory allocation failed"); return 0; }
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_netswap_app_NativeWireGuardEngine_nativeFree(JNIEnv* env, jobject, jlong value) {
    auto* h = reinterpret_cast<EngineHandle*>(value);
    if (!h) return;
    { std::lock_guard<std::mutex> lock(h->mutex); if (h->tunnel) { netswap_tunnel_free(h->tunnel); h->tunnel = nullptr; } }
    delete h;
}

static jbyteArray process(JNIEnv* env, jlong value, jbyteArray input, bool decrypt) {
    auto* h = reinterpret_cast<EngineHandle*>(value);
    if (!h || !input) { throwState(env, "Tunnel is closed or input is null"); return nullptr; }
    jsize length = env->GetArrayLength(input);
    if (length > 65535 || (!decrypt && length == 0)) { throwState(env, "Packet length is invalid"); return nullptr; }
    std::vector<uint8_t> in(static_cast<size_t>(length));
    if (length) env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(in.data()));
    if (env->ExceptionCheck()) return nullptr;
    std::vector<uint8_t> out(65536);
    size_t outLen = 0; uint32_t kind = 0; int32_t status;
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        if (!h->tunnel) { throwState(env, "Tunnel is closed"); return nullptr; }
        status = decrypt
            ? netswap_tunnel_decapsulate(h->tunnel, in.empty() ? nullptr : in.data(), in.size(), out.data(), out.size(), &outLen, &kind)
            : netswap_tunnel_encapsulate(h->tunnel, in.data(), in.size(), out.data(), out.size(), &outLen, &kind);
    }
    if (status != 0 || outLen > out.size() || kind > 3) { throwState(env, "Native WireGuard packet processing failed"); return nullptr; }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(outLen + 1));
    if (!result) return nullptr;
    jbyte header = static_cast<jbyte>(kind);
    env->SetByteArrayRegion(result, 0, 1, &header);
    if (outLen) env->SetByteArrayRegion(result, 1, static_cast<jsize>(outLen), reinterpret_cast<const jbyte*>(out.data()));
    return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_netswap_app_NativeWireGuardEngine_nativeEncapsulate(JNIEnv* e, jobject, jlong h, jbyteArray p) { return process(e,h,p,false); }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_netswap_app_NativeWireGuardEngine_nativeDecapsulate(JNIEnv* e, jobject, jlong h, jbyteArray p) { return process(e,h,p,true); }
