package io.github.yuan.xiaoxibyok;

import android.app.*;
import android.database.Cursor;
import android.os.*;
import dalvik.system.DexClassLoader;
import de.robv.android.xposed.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.json.*;

final class Host {
  static ClassLoader cl, bridge;
  static final Map<String, Object> services = new ConcurrentHashMap<>(),
      databases = new ConcurrentHashMap<>(),
      contexts = new ConcurrentHashMap<>();
  static final ExecutorService io = Executors.newSingleThreadExecutor();
  static final Handler main = new Handler(Looper.getMainLooper());
  static volatile String session = "";

  static String currentSession() {
    Object vm = services.get("chatViewModel");
    if (vm == null) return session;
    return text(call(field(vm, "E1"), "getValue"), "G");
  }

  static Application app() {
    return AndroidAppHelper.currentApplication();
  }

  static Class<?> cls(String name) {
    return XposedHelpers.findClass(name, cl);
  }

  static Object field(Object o, String n) {
    return XposedHelpers.getObjectField(o, n);
  }

  static String text(Object o, String n) {
    return Json.str(field(o, n));
  }

  static Object call(Object o, String n, Object... args) {
    return XposedHelpers.callMethod(o, n, args);
  }

  static Object make(String n, Object... args) {
    return XposedHelpers.newInstance(cls(n), args);
  }

  static Object unit() {
    return XposedHelpers.getStaticObjectField(cls("kotlin.Unit"), "INSTANCE");
  }

  static Object function(String name, Function<Object[], Object> fn) {
    return Proxy.newProxyInstance(
        cl,
        new Class[] {cls(name)},
        (p, m, a) -> {
          switch (m.getName()) {
            case "invoke":
              return fn.apply(a == null ? new Object[0] : a);
            case "toString":
              return "XiaoxiEnhanceCallback";
            case "hashCode":
              return System.identityHashCode(p);
            case "equals":
              return p == a[0];
            default:
              throw new UnsupportedOperationException(m.toString());
          }
        });
  }

  static void capture(String name, String key) {
    XposedBridge.hookAllConstructors(
        cls(name),
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            services.put(key, p.thisObject);
          }
        });
  }

  static void install(ClassLoader loader) {
    cl = loader;
    McpBridge.install();
    TaskModelScope.install();
    TaskToolScope.install();
    ScheduledSteps.install();
    capture("Z4.i", "fusion");
    capture("com.flyme.agentos.app.common.viewmodel.x0", "chatViewModel");
    capture("com.flyme.agentos.agent.domain.skill.install.M0", "uninstaller");
    capture("com.flyme.agentos.agent.domain.profile.persistence.d", "profiles");
    capture("com.flyme.agentos.agent.domain.memory.l", "memory");
    capture("com.flyme.agentos.agent.domain.skill.install.a0", "importer");
    capture("com.flyme.agentos.agent.domain.skill.install.y0", "skills");
    capture("com.flyme.agentos.agent.domain.skill.install.b0", "activation");
    capture("x5.e", "enabled");
    capture("com.flyme.agentos.agent.domain.memory.persistence.c", "sessions");
    for (String databaseClass :
        new String[] {
          "net.zetetic.database.sqlcipher.SQLiteDatabase", "android.database.sqlite.SQLiteDatabase"
        })
      XposedBridge.hookAllMethods(
          cls(databaseClass),
          "openDatabase",
          new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
              if (p.hasThrowable() || p.getResult() == null) return;
              Object db = p.getResult();
              String path = (String) call(db, "getPath");
              databases.put(new java.io.File(path).getName(), db);
            }
          });
  }

  static JSONArray query(String name, String sql, String... args) {
    Object db = databases.get(name);
    if (db == null) throw new IllegalStateException("小溪尚未打开 " + name + "，请先打开对应原生页面");
    try (Cursor c = (Cursor) call(db, "rawQuery", sql, args)) {
      JSONArray rows = new JSONArray();
      while (c.moveToNext()) {
        JSONObject row = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
          Object v =
              c.isNull(i)
                  ? JSONObject.NULL
                  : c.getType(i) == Cursor.FIELD_TYPE_INTEGER
                      ? c.getLong(i)
                      : c.getType(i) == Cursor.FIELD_TYPE_FLOAT
                          ? c.getDouble(i)
                          : c.getType(i) == Cursor.FIELD_TYPE_BLOB ? "[binary]" : c.getString(i);
          Json.put(row, c.getColumnName(i), v);
        }
        rows.put(row);
      }
      return rows;
    }
  }

  static void sql(String name, String sql, Object... args) {
    call(databases.get(name), "execSQL", sql, args);
  }

  static Object await(Object target, String method, Object... args) throws Exception {
    return awaitIn(
        XposedHelpers.getStaticObjectField(
            cls("kotlin.coroutines.EmptyCoroutineContext"), "INSTANCE"),
        target,
        method,
        args);
  }

  static Object awaitIn(Object context, Object target, String method, Object... args)
      throws Exception {
    if (bridge == null) {
      String apk =
          app().getPackageManager().getApplicationInfo("io.github.yuan.xiaoxibyok", 0).sourceDir;
      bridge = new DexClassLoader(apk, app().getCodeCacheDir().getPath(), null, cl);
    }
    CompletableFuture<Object> future = new CompletableFuture<>();
    Object completion =
        Proxy.newProxyInstance(
            cl,
            new Class[] {cls("kotlin.coroutines.Continuation")},
            (p, m, a) -> {
              if (m.getName().equals("getContext")) return context;
              if (m.getName().equals("resumeWith")) {
                future.complete(a[0]);
                return null;
              }
              return null;
            });
    Object cont =
        bridge
            .loadClass("io.github.yuan.xiaoxibyok.host.HostContinuation")
            .getConstructor(cls("kotlin.coroutines.Continuation"))
            .newInstance(completion);
    Object[] all = Arrays.copyOf(args, args.length + 1);
    all[args.length] = cont;
    Object result =
        target instanceof Class<?>
            ? XposedHelpers.callStaticMethod((Class<?>) target, method, all)
            : call(target, method, all);
    if (result
        != XposedHelpers.callStaticMethod(
            cls("kotlin.coroutines.intrinsics.IntrinsicsKt"), "getCOROUTINE_SUSPENDED"))
      future.complete(result);
    result = future.get();
    XposedHelpers.callStaticMethod(cls("kotlin.ResultKt"), "throwOnFailure", result);
    return result;
  }

  static void work(Callable<?> task, Consumer<Object> done) {
    work(task, done, NativeUi::error);
  }

  static void work(Callable<?> task, Consumer<Object> done, Consumer<Throwable> failed) {
    io.execute(
        () -> {
          Object value;
          try {
            value = task.call();
          } catch (Throwable error) {
            XposedBridge.log(error);
            main.post(() -> failed.accept(error));
            return;
          }
          main.post(
              () -> {
                try {
                  done.accept(value);
                } catch (Throwable error) {
                  XposedBridge.log(error);
                  failed.accept(error);
                }
              });
        });
  }

  static JSONArray sessions() {
    return query(
        "agentos_memory.db",
        "SELECT session_id,session_type,last_active_at,message_count,metadata FROM sessions ORDER"
            + " BY last_active_at DESC");
  }

  static JSONArray messages(String id) {
    return query(
        "agentos_memory.db", "SELECT * FROM session_messages WHERE session_id=? ORDER BY id", id);
  }
}
