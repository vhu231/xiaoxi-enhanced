package io.github.yuan.xiaoxibyok;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class HookRegistry {
  static final Set<String> installed = new HashSet<>();
  static final List<XposedInterface.HookHandle> handles = new ArrayList<>();

  static synchronized XposedInterface.HookHandle add(
      XposedInterface module, Executable executable, XC_MethodHook callback) {
    String key =
        System.identityHashCode(executable.getDeclaringClass().getClassLoader())
            + ":"
            + executable
            + ":"
            + System.identityHashCode(callback);
    if (!installed.add(key)) return null;
    XposedInterface.HookHandle handle =
        module
            .hook(executable)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept(chain -> Xposed.intercept(callback, chain));
    handles.add(handle);
    return handle;
  }
}
