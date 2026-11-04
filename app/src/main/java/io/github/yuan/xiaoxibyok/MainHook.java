package io.github.yuan.xiaoxibyok;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.SharedPreferences;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class MainHook implements IXposedHookLoadPackage {
  static volatile Object configManager;

  static SharedPreferences prefs() {
    return AndroidAppHelper.currentApplication().getSharedPreferences("xiaoxi_byok", 0);
  }

  static String nativeValue(String key) {
    if (configManager == null) return "";
    String value = (String) XposedHelpers.callMethod(configManager, "b", key);
    return value == null ? "" : value;
  }

  static Profile profile() {
    return new Profile(nativeValue("model.apiUrl"), nativeValue("model.selected"));
  }

  static WeakReference<Activity> resumedActivity = new WeakReference<>(null);

  @Override
  public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
    if (!app.packageName.equals("com.meizu.voiceassistant")
        || !app.processName.equals(app.packageName)) return;
    ClassLoader cl = app.classLoader;
    XposedBridge.hookAllConstructors(
        XposedHelpers.findClass("com.flyme.agentos.core.config.c", cl),
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            configManager = p.thisObject;
          }
        });
    XposedBridge.hookAllMethods(
        XposedHelpers.findClass("com.flyme.agentos.app.phone.PhoneApp$runtimeInitialization$1", cl),
        "invoke",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            XposedHelpers.setStaticBooleanField(XposedHelpers.findClass("b4.b", cl), "a", true);
            XposedBridge.log("XiaoxiByok 4.0: developer enabled");
          }
        });
    XposedHelpers.findAndHookMethod(
        Activity.class,
        "onResume",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            resumedActivity = new WeakReference<>((Activity) p.thisObject);
            VocuTasks.resumeOnce();
          }
        });
    Host.install(cl);
    VisionRouting.install();
    TtsBridge.install();
    FilesUi.install();
    PromptEngine.install();
    ContextEditor.install();
    Trace.install();
    ComposeBridge.install();
    XposedHelpers.findAndHookMethod(
        "D9.c",
        cl,
        "Q",
        OutputStream.class,
        XposedHelpers.findClass("Y6.i", cl),
        boolean.class,
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
            Object config = XposedHelpers.getObjectField(p.thisObject, "b");
            String endpoint = (String) XposedHelpers.getObjectField(config, "c");
            Profile settings = profile();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Object[] args = p.args.clone();
            args[0] = buffer;
            XposedBridge.invokeOriginalMethod(p.method, p.thisObject, args);
            JSONObject body = new JSONObject(buffer.toString("UTF-8"));
            String oldModel = body.optString("model");
            VisionConfig vision = VisionRouting.configuration(config);
            Profile scheduled = TaskModelScope.configuration(config);
            Profile summary = SummaryModel.configuration(config);
            if (scheduled != null) {
              settings = scheduled;
              Reasoning.Selection reasoning = Reasoning.implicit(scheduled);
              scheduled.rewrite(body, reasoning);
              Trace.reasoning(p.args[1], reasoning);
            } else if (summary != null) {
              settings = summary;
              Reasoning.Selection reasoning = Reasoning.implicit(summary);
              summary.rewrite(body, reasoning);
              Trace.reasoning(p.args[1], reasoning);
            } else if (vision != null) {
              settings = new Profile(endpoint, Host.text(config, "b"), false);
              if (settings.active()) {
                Reasoning.Selection reasoning = Reasoning.implicit(settings);
                settings.rewrite(body, reasoning);
                Trace.reasoning(p.args[1], reasoning);
              }
              vision.rewrite(body, config);
            } else if (settings.matches(endpoint)) {
              Reasoning.Selection reasoning = Reasoning.capture(p.args[1], settings);
              settings.rewrite(body, reasoning);
              Trace.reasoning(p.args[1], reasoning);
            }
            Trace.request(p.args[1], body);
            Trace.route(
                p.args[1],
                endpoint,
                scheduled != null
                    ? "SCHEDULED_CHAT"
                    : summary != null
                        ? "SUMMARY"
                        : vision == null ? "chat-or-native" : "IMAGE_ANALYSIS",
                scheduled != null
                    ? "scheduled-task"
                    : summary != null
                        ? "summary-model"
                        : vision == null
                            ? "native/profile"
                            : vision.followChat ? "follow-chat" : "independent");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            ((OutputStream) p.args[0]).write(bytes);
            XposedBridge.log(
                "XiaoxiByok 4.0: model="
                    + oldModel
                    + " -> "
                    + body.optString("model")
                    + ", protocol="
                    + settings.protocol()
                    + ", effort="
                    + settings.effort
                    + ", stream="
                    + body.optBoolean("stream")
                    + ", bytes="
                    + bytes.length);
            p.setResult(null);
          }
        });
    XposedHelpers.findAndHookMethod(
        "Y6.d0",
        cl,
        "b",
        String.class,
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Profile s = profile();
            if (s.active() && s.window > 0) p.setResult(s.window);
          }
        });
    XposedHelpers.findAndHookMethod(
        "X4.a",
        cl,
        "a",
        XposedHelpers.findClass("X4.r", cl),
        Integer.class,
        XposedHelpers.findClass("X4.s", cl),
        XposedHelpers.findClass("X4.i", cl),
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            Profile scheduled = TaskModelScope.budget(p.thisObject);
            Profile s = scheduled == null ? profile() : scheduled;
            if (!s.active() || s.window == 0) return;
            Object resolved = p.args[2];
            p.args[2] =
                XposedHelpers.newInstance(
                    resolved.getClass(),
                    s.window,
                    XposedHelpers.getObjectField(resolved, "b"),
                    96000,
                    64000,
                    20000);
            p.args[1] =
                Integer.valueOf(s.window - Math.max(4000, s.output) - (int) (s.window * 0.02));
            ((AtomicInteger) XposedHelpers.getObjectField(p.thisObject, "a")).set(s.window);
            p.setObjectExtra("byokBudget", true);
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (p.getObjectExtra("byokBudget") == null) return;
            Object plan = p.getResult();
            XposedBridge.log(
                "XiaoxiByok 4.0: context window="
                    + XposedHelpers.getIntField(plan, "a")
                    + ", inputCap="
                    + XposedHelpers.getIntField(plan, "d"));
          }
        });
    XposedBridge.log("XiaoxiByok 4.0: all hooks installed");
  }
}
