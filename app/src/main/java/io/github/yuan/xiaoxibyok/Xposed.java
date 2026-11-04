package io.github.yuan.xiaoxibyok;

import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class Xposed {
  static XposedModule module;

  static void attach(XposedModule implementation) {
    module = implementation;
  }

  static XposedModule module() {
    if (module == null) throw new IllegalStateException("API 102 尚未初始化");
    return module;
  }

  static List<XposedInterface.HookHandle> hookAllMethods(
      Class<?> type, String name, XC_MethodHook callback) {
    List<XposedInterface.HookHandle> result = new ArrayList<>();
    for (Method method : type.getDeclaredMethods())
      if (method.getName().equals(name)) result.add(HookRegistry.add(module(), method, callback));
    return result;
  }

  static List<XposedInterface.HookHandle> hookAllConstructors(
      Class<?> type, XC_MethodHook callback) {
    List<XposedInterface.HookHandle> result = new ArrayList<>();
    for (Constructor<?> constructor : type.getDeclaredConstructors())
      result.add(HookRegistry.add(module(), constructor, callback));
    return result;
  }

  static XposedInterface.HookHandle findAndHookMethod(
      Class<?> type, String name, Object... parametersAndCallback) {
    return findMethod(type, name, parametersAndCallback);
  }

  static XposedInterface.HookHandle findAndHookMethod(
      String className, ClassLoader loader, String name, Object... parametersAndCallback) {
    return findMethod(Reflect.findClass(className, loader), name, parametersAndCallback);
  }

  static XposedInterface.HookHandle findMethod(
      Class<?> type, String name, Object[] parametersAndCallback) {
    if (parametersAndCallback.length == 0
        || !(parametersAndCallback[parametersAndCallback.length - 1] instanceof XC_MethodHook))
      throw new IllegalArgumentException("Missing hook callback");
    int count = parametersAndCallback.length - 1;
    Class<?>[] parameters = new Class<?>[count];
    for (int i = 0; i < count; i++) parameters[i] = (Class<?>) parametersAndCallback[i];
    XC_MethodHook callback = (XC_MethodHook) parametersAndCallback[count];
    Method method;
    try {
      method = type.getDeclaredMethod(name, parameters);
    } catch (NoSuchMethodException error) {
      throw new NoSuchMethodError(type.getName() + "." + name);
    }
    return HookRegistry.add(module(), method, callback);
  }

  static Object intercept(XC_MethodHook callback, XposedInterface.Chain chain) throws Throwable {
    XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
    param.thisObject = chain.getThisObject();
    param.args = chain.getArgs().toArray();
    param.method = chain.getExecutable();
    try {
      callback.beforeHookedMethod(param);
    } catch (Throwable error) {
      param.throwable = error;
      callback.afterHookedMethod(param);
      throw param.throwable;
    }
    if (param.early) {
      if (param.throwable != null) throw param.throwable;
      return param.result;
    }
    try {
      param.result = chain.proceed(param.args);
    } catch (Throwable error) {
      param.throwable = error;
      callback.afterHookedMethod(param);
      throw param.throwable;
    }
    callback.afterHookedMethod(param);
    if (param.throwable != null) throw param.throwable;
    return param.result;
  }

  static Object invokeOriginalMethod(Member method, Object receiver, Object... args)
      throws Throwable {
    if (!(method instanceof Method)) throw new IllegalArgumentException("Not a method: " + method);
    return module()
        .getInvoker((Method) method)
        .setType(XposedInterface.Invoker.Type.ORIGIN)
        .invoke(receiver, args);
  }

  static Object invoke(Method method, Object receiver, Object... args) throws Throwable {
    return module().getInvoker(method).invoke(receiver, args);
  }

  static <T> T newInstance(Constructor<T> constructor, Object... args) throws Throwable {
    return module().getInvoker(constructor).newInstance(args);
  }

  static void log(String message) {
    module().log(Log.INFO, "XiaoxiByok", message);
  }

  static void log(Throwable error) {
    module().log(Log.ERROR, "XiaoxiByok", error.toString(), error);
  }

  @SuppressWarnings("unchecked")
  static <T extends Throwable> void throwUnchecked(Throwable error) throws T {
    throw (T) error;
  }
}
