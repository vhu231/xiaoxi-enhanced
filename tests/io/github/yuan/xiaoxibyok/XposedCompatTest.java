package io.github.yuan.xiaoxibyok;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Objects;

public final class XposedCompatTest {
  static int originalCalls;

  public static class Target {
    private int value = 7;
    private String text;
    private static String staticText = "static";

    private Target(String text) {
      this.text = text;
    }

    private String call(String prefix, int amount) {
      originalCalls++;
      return prefix + amount;
    }

    private String fail() {
      throw new IllegalStateException("original");
    }

    private static String staticCall(int value) {
      return "s" + value;
    }
  }

  static final class EqualTarget {
    public boolean equals(Object other) {
      return other instanceof EqualTarget;
    }

    public int hashCode() {
      return 1;
    }
  }

  static XposedInterface.Chain chain(Executable executable, Object receiver, Object[] args) {
    return (XposedInterface.Chain)
        Proxy.newProxyInstance(
            XposedCompatTest.class.getClassLoader(),
            new Class<?>[] {XposedInterface.Chain.class},
            (proxy, method, arguments) -> {
              switch (method.getName()) {
                case "getExecutable":
                  return executable;
                case "getThisObject":
                  return receiver;
                case "getArgs":
                  return Arrays.asList(args);
                case "getArg":
                  return args[(Integer) arguments[0]];
                case "proceed":
                  Object[] callArgs = arguments == null ? args : (Object[]) arguments[0];
                  try {
                    ((Method) executable).setAccessible(true);
                    return ((Method) executable).invoke(receiver, callArgs);
                  } catch (InvocationTargetException error) {
                    throw error.getTargetException();
                  }
                case "proceedWith":
                  Object actualReceiver = arguments[0];
                  Object[] actualArgs = arguments.length > 1 ? (Object[]) arguments[1] : args;
                  try {
                    ((Method) executable).setAccessible(true);
                    return ((Method) executable).invoke(actualReceiver, actualArgs);
                  } catch (InvocationTargetException error) {
                    throw error.getTargetException();
                  }
                default:
                  throw new UnsupportedOperationException(method.toString());
              }
            });
  }

  static XposedInterface.HookBuilder builder() {
    return (XposedInterface.HookBuilder)
        Proxy.newProxyInstance(
            XposedCompatTest.class.getClassLoader(),
            new Class<?>[] {XposedInterface.HookBuilder.class},
            (proxy, method, args) -> {
              if (method.getName().equals("intercept")) return null;
              if (method.getName().startsWith("set")) return proxy;
              throw new UnsupportedOperationException(method.toString());
            });
  }

  static XposedInterface.Invoker invoker(Method method) {
    return (XposedInterface.Invoker)
        Proxy.newProxyInstance(
            XposedCompatTest.class.getClassLoader(),
            new Class<?>[] {XposedInterface.Invoker.class},
            (proxy, name, args) -> {
              if (name.getName().equals("setType")) return proxy;
              if (name.getName().equals("invoke")) {
                method.setAccessible(true);
                return method.invoke(args[0], (Object[]) args[1]);
              }
              throw new UnsupportedOperationException(name.toString());
            });
  }

  static XposedInterface.CtorInvoker invoker(Constructor<?> constructor) {
    return (XposedInterface.CtorInvoker)
        Proxy.newProxyInstance(
            XposedCompatTest.class.getClassLoader(),
            new Class<?>[] {XposedInterface.CtorInvoker.class},
            (proxy, name, args) -> {
              if (name.getName().equals("setType")) return proxy;
              if (name.getName().equals("newInstance")) {
                constructor.setAccessible(true);
                return constructor.newInstance((Object[]) args[0]);
              }
              throw new UnsupportedOperationException(name.toString());
            });
  }

  static XposedModule module() {
    XposedInterface api =
        (XposedInterface)
            Proxy.newProxyInstance(
                XposedCompatTest.class.getClassLoader(),
                new Class<?>[] {XposedInterface.class},
                (proxy, method, args) -> {
                  switch (method.getName()) {
                    case "hook":
                      return builder();
                    case "getInvoker":
                      return args[0] instanceof Method
                          ? invoker((Method) args[0])
                          : invoker((Constructor<?>) args[0]);
                    case "getApiVersion":
                      return 102;
                    case "getFrameworkName":
                      return "test";
                    case "getFrameworkVersion":
                      return "1";
                    case "getFrameworkVersionCode":
                    case "getFrameworkProperties":
                      return 0L;
                    case "log":
                      return null;
                    default:
                      if (method.getReturnType() == boolean.class) return false;
                      if (method.getReturnType() == int.class) return 0;
                      if (method.getReturnType() == long.class) return 0L;
                      return null;
                  }
                });
    XposedModule module = new XposedModule() {};
    module.attachFramework(api, () -> {});
    return module;
  }

  static void check(Object expected, Object actual, String message) {
    if (!Objects.equals(expected, actual))
      throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
  }

  public static void main(String[] args) throws Throwable {
    Xposed.attach(module());
    Method call = Target.class.getDeclaredMethod("call", String.class, int.class);
    Target target = new Target("field");
    originalCalls = 0;
    XposedInterface.Hooker beforeAfter =
        chain ->
            Xposed.intercept(
                new XC_MethodHook() {
                  protected void beforeHookedMethod(MethodHookParam p) {
                    p.args[0] = p.args[0] + "-before";
                    p.setObjectExtra("seen", true);
                  }

                  protected void afterHookedMethod(MethodHookParam p) {
                    check(true, p.getObjectExtra("seen"), "chain extras");
                    p.setResult(p.getResult() + "-after");
                  }
                },
                chain);
    check(
        "x-before3-after",
        beforeAfter.intercept(chain(call, target, new Object[] {"x", 3})),
        "before/after");
    check(1, originalCalls, "original called once");

    final boolean[] after = {false};
    XposedInterface.Hooker shortCircuit =
        chain ->
            Xposed.intercept(
                new XC_MethodHook() {
                  protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult("stop");
                  }

                  protected void afterHookedMethod(MethodHookParam p) {
                    after[0] = true;
                  }
                },
                chain);
    check(
        "stop",
        shortCircuit.intercept(chain(call, target, new Object[] {"x", 3})),
        "short circuit");
    check(false, after[0], "short circuit after");
    check(1, originalCalls, "short circuit skips original");

    try {
      Xposed.intercept(
          new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
              p.setThrowable(new Exception("stop"));
            }
          },
          chain(call, target, new Object[] {"x", 3}));
      throw new AssertionError("before throwable not propagated");
    } catch (Exception error) {
      check("stop", error.getMessage(), "before throwable");
    }

    Method fail = Target.class.getDeclaredMethod("fail");
    final Throwable[] seen = new Throwable[1];
    try {
      Xposed.intercept(
          new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
              seen[0] = p.getThrowable();
            }
          },
          chain(fail, target, new Object[0]));
      throw new AssertionError("original throwable not propagated");
    } catch (IllegalStateException error) {
      check("original", error.getMessage(), "original throwable");
      check(seen[0], error, "after sees original throwable");
    }

    check(7, Reflect.getObjectField(target, "value"), "private field");
    check("x3", Reflect.callMethod(target, "call", "x", 3), "private method");
    check("s4", Reflect.callStaticMethod(Target.class, "staticCall", 4), "static method");
    check(
        "ctor",
        Reflect.getObjectField(Reflect.newInstance(Target.class, "ctor"), "text"),
        "constructor");
    check("static", Reflect.getStaticObjectField(Target.class, "staticText"), "static field");

    EqualTarget first = new EqualTarget(), second = new EqualTarget();
    WeakIdentityMap.put(first, "request", "first");
    WeakIdentityMap.put(second, "request", "second");
    check("first", WeakIdentityMap.get(first, "request"), "identity first");
    check("second", WeakIdentityMap.get(second, "request"), "identity second");
    check(
        false,
        new WeakIdentityMap.Key(first, WeakIdentityMap.queue)
            .equals(new WeakIdentityMap.Key(second, WeakIdentityMap.queue)),
        "identity keys");
    WeakIdentityMap.put(first, "request", null);
    check(null, WeakIdentityMap.get(first, "request"), "metadata removal");

    HookRegistry.installed.clear();
    XC_MethodHook sameCallback = new XC_MethodHook() {};
    Xposed.hookAllMethods(Target.class, "call", sameCallback);
    Xposed.hookAllMethods(Target.class, "call", sameCallback);
    check(1, HookRegistry.installed.size(), "hook dedup");
    Xposed.hookAllMethods(Target.class, "call", new XC_MethodHook() {});
    check(2, HookRegistry.installed.size(), "distinct callbacks on one method");
    System.out.println("XposedCompatTest: all checks passed");
  }
}
