#include <jni.h>
#include <pthread.h>
#include <dlfcn.h>
#include <string>

extern "C" int dependent_add(int);
static JavaVM* vm;
static jclass target;
static jobject loader;
static jmethodID answer;
static jmethodID loadClass;
static int loads;

static jint registered(JNIEnv*, jclass, jint value) { return dependent_add(value); }

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* machine, void*) {
    vm = machine;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass bridge = env->FindClass("com/lelloman/paravoidcompat/jni/bridge/NativeBridge");
    if (!bridge) return JNI_ERR;
    JNINativeMethod method = {const_cast<char*>("registered"), const_cast<char*>("(I)I"), reinterpret_cast<void*>(registered)};
    if (env->RegisterNatives(bridge, &method, 1) != JNI_OK) return JNI_ERR;
    jclass localTarget = env->FindClass("com/lelloman/paravoidcompat/jni/NativeTarget");
    if (!localTarget) return JNI_ERR;
    target = static_cast<jclass>(env->NewGlobalRef(localTarget));
    answer = env->GetStaticMethodID(target, "answer", "()I");
    jclass classType = env->FindClass("java/lang/Class");
    jmethodID getLoader = env->GetMethodID(classType, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject localLoader = env->CallObjectMethod(target, getLoader);
    if (env->ExceptionCheck() || !localLoader || !answer) return JNI_ERR;
    loader = env->NewGlobalRef(localLoader);
    jclass loaderType = env->FindClass("java/lang/ClassLoader");
    loadClass = env->GetMethodID(loaderType, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (env->ExceptionCheck() || !target || !loader || !loadClass) return JNI_ERR;
    ++loads;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* machine, void*) {
    JNIEnv* env = nullptr;
    if (machine->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        env->DeleteGlobalRef(target);
        env->DeleteGlobalRef(loader);
    }
}

#define JNI_METHOD(name) Java_com_lelloman_paravoidcompat_jni_bridge_NativeBridge_##name

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(onLoadCount)(JNIEnv*, jclass) { return loads; }
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(callback)(JNIEnv* env, jclass) {
    jclass type = env->FindClass("com/lelloman/paravoidcompat/jni/NativeTarget");
    if (!type) return -1;
    jmethodID method = env->GetStaticMethodID(type, "answer", "()I");
    return method ? env->CallStaticIntMethod(type, method) : -1;
}

static void* threadProbe(void* output) {
    JNIEnv* env = nullptr;
    int mask = 0;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    jclass bare = env->FindClass("com/lelloman/paravoidcompat/jni/NativeTarget");
    if (!bare && env->ExceptionCheck()) { env->ExceptionClear(); mask |= 1; }
    if (!env->ExceptionCheck() && env->CallStaticIntMethod(target, answer) == 42) mask |= 2;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jstring name = env->NewStringUTF("com.lelloman.paravoidcompat.jni.NativeTarget");
    auto loaded = static_cast<jclass>(env->CallObjectMethod(loader, loadClass, name));
    if (!env->ExceptionCheck() && loaded && env->IsSameObject(loaded, target)) mask |= 4;
    if (env->ExceptionCheck()) env->ExceptionClear();
    *static_cast<int*>(output) = mask;
    vm->DetachCurrentThread();
    return nullptr;
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(nativeThread)(JNIEnv*, jclass) {
    pthread_t thread;
    int result = 0;
    if (pthread_create(&thread, nullptr, threadProbe, &result) != 0) return -1;
    if (pthread_join(thread, nullptr) != 0) return -2;
    return result;
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(mutate)(JNIEnv* env, jclass, jobject buffer) {
    auto* data = static_cast<unsigned char*>(env->GetDirectBufferAddress(buffer));
    jlong size = env->GetDirectBufferCapacity(buffer);
    if (!data || size != 4) return -1;
    int sum = 0;
    for (jlong i = 0; i < size; ++i) { sum += data[i]; ++data[i]; }
    return sum;
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(dynamicLibrary)(JNIEnv*, jclass) {
    void* handle = dlopen("libprobe_plugin.so", RTLD_NOW);
    if (!handle) return -1;
    auto function = reinterpret_cast<int(*)()>(dlsym(handle, "plugin_answer"));
    int result = function ? function() : -2;
    dlclose(handle);
    return result;
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(abi)(JNIEnv* env, jclass) {
#if defined(__aarch64__)
    std::string value = "arm64-v8a";
#elif defined(__x86_64__)
    std::string value = "x86_64";
#else
    std::string value = "unsupported";
#endif
    return env->NewStringUTF(value.c_str());
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(throwFromNative)(JNIEnv* env, jclass) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type) env->ThrowNew(type, "native failure");
}
