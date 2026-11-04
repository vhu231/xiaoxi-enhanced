package io.github.yuan.xiaoxibyok;

import java.util.*;
import org.json.*;

final class Compression {
  static final String PREFIX = "com.flyme.agentos.agent.core.context.compression.";

  static Object unchanged(Object engine, List<?> messages, Object summary, Object breakdown) {
    int count = ((Number) Host.call(engine, "b", messages)).intValue();
    if (breakdown == null)
      breakdown =
          Host.make(
              "com.flyme.agentos.agent.core.context.s",
              0,
              count,
              summary == null ? 0 : Reflect.getIntField(summary, "f"),
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0);
    Object route =
        Reflect.getStaticObjectField(Host.cls(PREFIX + "CompressionRoute"), "CHECK_ONLY");
    Object decision =
        Host.make(
            PREFIX + "l",
            route,
            "user_disabled_compression",
            count,
            count,
            Collections.emptyList());
    return Host.make(
        PREFIX + "q",
        messages,
        Collections.emptyList(),
        summary,
        false,
        null,
        false,
        false,
        false,
        null,
        0,
        0,
        null,
        null,
        decision,
        null,
        breakdown,
        0,
        0,
        0);
  }

  static void configure(Object engine, String sid) {
    JSONObject settings = Store.settings(sid);
    double ratio = settings.optDouble("compressionThreshold", 0);
    int minimum = settings.optInt("compressionMinTokens", 0);
    if (ratio > 0 && ratio <= 1) Reflect.setFloatField(engine, "k", (float) ratio);
    if (minimum > 0) Reflect.setIntField(engine, "l", minimum);
  }

  static void install() {
    SummaryModel.install();
    Xposed.hookAllConstructors(
        Host.cls(PREFIX + "k"),
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            String prompt = Store.get("compression", "default").optString("prompt");
            if (!prompt.isEmpty()) {
              Reflect.setObjectField(p.thisObject, "b", prompt);
              Reflect.setObjectField(p.thisObject, "g", prompt);
            }
          }
        });
    for (String method : new String[] {"i", "j"})
      Xposed.hookAllMethods(
          Host.cls(PREFIX + "k"),
          method,
          new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
              int output = Store.get("compression", "default").optInt("output", 0);
              if (output > 0) p.args[2] = output;
            }
          });
    XC_MethodHook pass =
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (p.args[0] == null) return;
            String sid = (String) p.args[5];
            configure(p.thisObject, sid);
            if (!Store.settings(sid).optBoolean("noCompression")) return;
            Object result = unchanged(p.thisObject, (List<?>) p.args[0], p.args[2], null);
            p.setResult(result);
            Store.event(
                sid,
                "context.compression.bypassed",
                Json.o("messages", ((List<?>) p.args[0]).size(), "reason", "用户关闭自动压缩"));
          }

          protected void afterHookedMethod(MethodHookParam p) {
            String sid = (String) p.args[5];
            Object result = p.getResult();
            if (Store.settings(sid).optBoolean("noCompression") || result == null) return;
            Store.event(
                sid,
                "context.compression",
                Json.o(
                    "inputMessages",
                    ((List<?>) p.args[0]).size(),
                    "activeMessages",
                    ((List<?>) Host.field(result, "a")).size(),
                    "decision",
                    Json.str(Host.field(result, "o")),
                    "summaryAvailable",
                    Host.field(result, "c") != null,
                    "failure",
                    Json.str(Host.field(result, "e")),
                    "tokenBreakdown",
                    Json.str(Host.field(result, "q"))));
          }
        };
    Xposed.hookAllMethods(Host.cls(PREFIX + "p"), "a", pass);
    Xposed.hookAllMethods(Host.cls(PREFIX + "p"), "c", pass);
    Xposed.hookAllMethods(
        Host.cls(PREFIX + "p"),
        "d",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            String sid = PromptEngine.scope.get();
            if (sid != null) configure(p.thisObject, sid);
            if (sid != null && Store.settings(sid).optBoolean("noCompression"))
              p.setResult(unchanged(p.thisObject, (List<?>) p.args[0], p.args[1], p.args[2]));
          }
        });
  }
}
