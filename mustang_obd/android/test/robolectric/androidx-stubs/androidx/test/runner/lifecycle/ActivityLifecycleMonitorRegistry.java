package androidx.test.runner.lifecycle;
public final class ActivityLifecycleMonitorRegistry { private static ActivityLifecycleMonitor m;
  public static ActivityLifecycleMonitor getInstance() { return m; } public static void registerInstance(ActivityLifecycleMonitor x) { m = x; } }
