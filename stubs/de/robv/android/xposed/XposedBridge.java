package de.robv.android.xposed;
public final class XposedBridge {
    public static void log(String s) {} public static void log(Throwable t) {}
    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member m, XC_MethodHook cb) { return null; }
}
