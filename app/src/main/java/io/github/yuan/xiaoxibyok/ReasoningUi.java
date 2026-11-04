package io.github.yuan.xiaoxibyok;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class ReasoningUi {
  static final ThreadLocal<Boolean> panel = new ThreadLocal<>();

  static boolean active() {
    Profile profile = MainHook.profile();
    return profile.active() && Reasoning.supports(profile);
  }

  static void install() {
    Xposed.hookAllMethods(
        Host.cls("com.flyme.agentos.app.common.ui.components.input.p0"),
        "a",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!active() || p.args.length != 6) return;
            Profile profile = MainHook.profile();
            String selected =
                Reasoning.selected(Host.currentSession(), profile.endpoint, profile.model);
            JSONObject level = Reasoning.find(Reasoning.configuration(profile), selected);
            String label = level == null ? selected : level.optString("name", selected);
            p.args[0] = Host.make("com.flyme.agentos.app.common.ui.components.input.t0", label);
          }
        });
    Xposed.hookAllMethods(
        Host.cls("com.flyme.agentos.app.common.ui.components.input.p0"),
        "d",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!active() || p.args.length != 6) return;
            Profile profile = MainHook.profile();
            String session = Host.currentSession();
            String selected = Reasoning.selected(session, profile.endpoint, profile.model);
            Object dismiss = p.args[3];
            JSONArray levels = Reasoning.options(profile.endpoint, profile.model);
            List<Object> options = new ArrayList<>();
            for (int i = 0; i < levels.length(); i++) {
              JSONObject level = levels.optJSONObject(i);
              options.add(option(level, level.optString("id").equals(selected)));
            }
            p.args[0] = options;
            p.args[2] =
                Host.function(
                    "kotlin.jvm.functions.Function1",
                    args -> {
                      try {
                        String id =
                            args[0] instanceof String ? (String) args[0] : Host.text(args[0], "a");
                        Reasoning.select(session, profile.endpoint, profile.model, id);
                        Host.call(dismiss, "invoke");
                      } catch (Throwable error) {
                        Xposed.log(error);
                        NativeUi.error(error);
                      }
                      return Host.unit();
                    });
          }
        });
    Xposed.hookAllMethods(
        Host.cls("com.flyme.agentos.app.common.ui.components.input.p0"),
        "c",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            panel.set(active());
          }

          protected void afterHookedMethod(MethodHookParam p) {
            panel.remove();
          }
        });
    Xposed.hookAllMethods(
        Host.cls("androidx.compose.material3.w6"),
        "b",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (Boolean.TRUE.equals(panel.get()) && p.args.length > 0 && "选择模型".equals(p.args[0]))
              p.args[0] = "思考等级";
          }
        });
  }

  static Object option(JSONObject level, boolean selected) {
    return Host.make(
        "com.flyme.agentos.app.common.viewmodel.L3",
        level.optString("id"),
        level.optString("name", level.optString("id")),
        "",
        level.optString("description"),
        false,
        selected,
        true);
  }
}
