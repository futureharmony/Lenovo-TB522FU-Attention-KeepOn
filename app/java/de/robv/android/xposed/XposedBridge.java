package de.robv.android.xposed;

public final class XposedBridge {
    public static void log(String text) {
        android.util.Log.i("AonXposed", text);
    }

    public static void log(Throwable t) {
        android.util.Log.e("AonXposed", "Hook error", t);
    }
}
