package android.util;

/**
 * Test-only stand-in for android.util.Log.
 *
 * The android.jar on the unit-test classpath is a stub: every Log method
 * throws RuntimeException("Stub!"). Libraries that log during class
 * initialisation - PdfBox-Android's font stack (PDType1Font, the
 * LiberationSans TTF parser) calls Log.d / Log.w while its static
 * initialisers run - therefore fail every JVM unit test that touches
 * them with ExceptionInInitializerError caused by "Stub!".
 *
 * This class lives in src/test only, so it never ships in the APK.
 * Gradle puts the compiled test classes before android.jar on the
 * unit-test runtime classpath, so this no-op Log wins over the stub.
 * It deliberately swallows everything: unit tests must not depend on
 * log output, and the production app uses the real framework Log.
 */
public final class Log {

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private Log() {
    }

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int v(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, Throwable tr) {
        return 0;
    }

    public static int e(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int wtf(String tag, String msg) {
        return 0;
    }

    public static int wtf(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int wtf(String tag, String msg, Throwable tr, boolean local) {
        return 0;
    }

    public static int println(int priority, String tag, String msg) {
        return 0;
    }

    public static boolean isLoggable(String tag, int level) {
        return false;
    }

    public static String getStackTraceString(Throwable tr) {
        return "";
    }
}
