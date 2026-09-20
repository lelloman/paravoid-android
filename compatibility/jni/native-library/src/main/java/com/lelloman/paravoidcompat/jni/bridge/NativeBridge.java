package com.lelloman.paravoidcompat.jni.bridge;

import java.nio.ByteBuffer;

public final class NativeBridge {
    static { System.loadLibrary("probe_jni"); }
    public static void loadAgain() { System.loadLibrary("probe_jni"); }
    public static native int registered(int value);
    public static native int onLoadCount();
    public static native int callback();
    public static native int nativeThread();
    public static native int mutate(ByteBuffer data);
    public static native int dynamicLibrary();
    public static native String abi();
    public static native void throwFromNative();
}
