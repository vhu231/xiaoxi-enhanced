package io.github.yuan.xiaoxibyok;

import android.app.Activity;
import android.content.SharedPreferences;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class MainHook extends XposedModule {
  static volatile Object configManager;
  static String processName = "";

  static SharedPreferences prefs() {
    return Host.app().getSharedPreferences("xiaoxi_byok", 0);
  }

  static String nativeValue(String key) {
    if (configManager == null) return "";
    String value = (String) Reflect.callMethod(configManager, "b", key);
    return value == null ? "" : value;
  }

  static Profile profile() {
    return new Profile(nativeValue("model.apiUrl"), nativeValue("model.selected"));
  }

  static WeakReference<Activity> resumedActivity = new WeakReference<>(null);

  @Override
  public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
    Xposed.attach(this);
    if (param.isSystemServer()) return;
    processName = param.getProcessName();
    Xposed.log(
        "API 102: framework="
            + getFrameworkName()
            + " "
            + getFrameworkVersion()
            + " ("
            + getFrameworkVersionCode()
            + "), api="
            + getApiVersion()
            + ", properties="
            + getFrameworkProperties()
            + ", process="
            + processName);
  }

  @Override
  public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
    if (!processName.equals("com.meizu.voiceassistant")
        || !param.getPackageName().equals(processName)
        || !param.isFirstPackage()) return;
    installHooks(param.getClassLoader());
  }

  void installHooks(ClassLoader cl) {
    Xposed.hookAllConstructors(
        Reflect.findClass("com.flyme.agentos.core.config.c", cl),
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            configManager = p.thisObject;
          }
        });
    Xposed.hookAllMethods(
        Reflect.findClass("com.flyme.agentos.app.phone.PhoneApp$runtimeInitialization$1", cl),
        "invoke",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Reflect.setStaticBooleanField(Reflect.findClass("b4.b", cl), "a", true);
            Xposed.log("XiaoxiByok 5.0 API102: developer enabled");
          }
        });
    Xposed.findAndHookMethod(
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
    Xposed.findAndHookMethod(
        "D9.c",
        cl,
        "Q",
        OutputStream.class,
        Reflect.findClass("Y6.i", cl),
        boolean.class,
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
            Object config = Reflect.getObjectField(p.thisObject, "b");
            String endpoint = (String) Reflect.getObjectField(config, "c");
            Profile settings = profile();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Object[] args = p.args.clone();
            args[0] = buffer;
            Xposed.invokeOriginalMethod(p.method, p.thisObject, args);
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
            Xposed.log(
                "XiaoxiByok 5.0: model="
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
    Xposed.findAndHookMethod(
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
    Xposed.findAndHookMethod(
        "X4.a",
        cl,
        "a",
        Reflect.findClass("X4.r", cl),
        Integer.class,
        Reflect.findClass("X4.s", cl),
        Reflect.findClass("X4.i", cl),
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            Profile scheduled = TaskModelScope.budget(p.thisObject);
            Profile s = scheduled == null ? profile() : scheduled;
            if (!s.active() || s.window == 0) return;
            Object resolved = p.args[2];
            p.args[2] =
                Reflect.newInstance(
                    resolved.getClass(),
                    s.window,
                    Reflect.getObjectField(resolved, "b"),
                    96000,
                    64000,
                    20000);
            p.args[1] =
                Integer.valueOf(s.window - Math.max(4000, s.output) - (int) (s.window * 0.02));
            ((AtomicInteger) Reflect.getObjectField(p.thisObject, "a")).set(s.window);
            p.setObjectExtra("byokBudget", true);
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (p.getObjectExtra("byokBudget") == null) return;
            Object plan = p.getResult();
            Xposed.log(
                "XiaoxiByok 5.0: context window="
                    + Reflect.getIntField(plan, "a")
                    + ", inputCap="
                    + Reflect.getIntField(plan, "d"));
          }
        });
    Xposed.log("XiaoxiByok 5.0 API102: all hooks installed");
  }
}
