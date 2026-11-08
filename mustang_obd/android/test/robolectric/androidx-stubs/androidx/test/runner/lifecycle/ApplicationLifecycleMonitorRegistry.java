package androidx.test.runner.lifecycle;
public final class ApplicationLifecycleMonitorRegistry { private static ApplicationLifecycleMonitor m;
  public static ApplicationLifecycleMonitor getInstance() { return m; } public static void registerInstance(ApplicationLifecycleMonitor x) { m = x; } }
