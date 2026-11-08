package androidx.test.platform.app;
public final class InstrumentationRegistry { private static android.app.Instrumentation i;
  public static void registerInstance(android.app.Instrumentation x, android.os.Bundle b) { i = x; } public static android.app.Instrumentation getInstrumentation() { return i; } }
