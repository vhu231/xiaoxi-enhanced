package io.github.yuan.xiaoxibyok;

import java.util.*;
import java.util.regex.*;
import org.json.*;

final class PromptEngine {
  static final ThreadLocal<String> scope = new ThreadLocal<>();
  static final Map<Object, String> envelopeSessions =
      Collections.synchronizedMap(new WeakHashMap<>());

  static JSONObject preset(String sid) {
    JSONObject taskPreset = TaskPromptScope.current(sid);
    if (taskPreset != null) return taskPreset;
    String id =
        Store.settings(sid).optString("preset", MainHook.prefs().getString("defaultPreset", ""));
    return Store.get("preset", id);
  }

  static String asset(String name) {
    try (java.io.InputStream in = Host.app().getAssets().open("prompts/" + name);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      byte[] bytes = new byte[8192];
      int n;
      while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
      return out.toString("UTF-8").trim();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static String base(String sid, String text) {
    String voice = asset("quickvoice-screen-addon.md");
    boolean quick = text.contains(voice);
    String value = text;
    JSONObject main = Store.get("base", "main"), addon = Store.get("base", "voice");
    if (main.optBoolean("enabled"))
      value =
          main.optString("text")
              + (quick
                  ? "\n\n" + (addon.optBoolean("enabled") ? addon.optString("text") : voice)
                  : "");
    else if (quick && addon.optBoolean("enabled"))
      value = value.replace(voice, addon.optString("text"));
    JSONObject pre = preset(sid);
    if (pre.optString("target", "base").equals("base")
        && pre.optJSONArray("rules") != null
        && pre.optJSONArray("rules").length() > 0)
      value = render(applyPrompt(sections(value), value, pre.optJSONArray("rules")));
    return value;
  }

  static JSONArray sections(String text) {
    JSONArray a = new JSONArray();
    String title = "前言";
    StringBuilder b = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      if (line.startsWith("# ")) {
        if (b.length() > 0) a.put(Json.o("id", title, "text", b.toString()));
        title = line.substring(2).trim();
        b.setLength(0);
      }
      b.append(line).append('\n');
    }
    if (b.length() > 0) a.put(Json.o("id", title, "text", b.toString()));
    return a;
  }

  // Prefix/suffix rules operate on the rendered prompt so host formatting stays intact.
  static JSONArray applyPrompt(JSONArray source, String rendered, JSONArray rules) {
    boolean whole = true;
    for (int i = 0; rules != null && i < rules.length(); i++) {
      JSONObject rule = rules.optJSONObject(i);
      if (!rule.optBoolean("enabled", true)) continue;
      String kind = rule.optString("kind");
      if (!kind.equals("prepend") && !kind.equals("append") && !kind.equals("replace"))
        whole = false;
    }
    return apply(
        whole ? new JSONArray().put(Json.o("id", "original", "text", rendered)) : source, rules);
  }

  static JSONArray apply(JSONArray source, JSONArray rules) {
    JSONArray copy = Json.arr(source.toString());
    List<JSONObject> items = new ArrayList<>();
    for (int i = 0; i < copy.length(); i++) items.add(copy.optJSONObject(i));
    if (rules == null) return copy;
    for (int i = 0; i < rules.length(); i++) {
      JSONObject r = rules.optJSONObject(i);
      if (!r.optBoolean("enabled", true)) continue;
      String kind = r.optString("kind"), text = r.optString("text");
      JSONObject row = Json.o("id", "custom:" + i, "text", text);
      switch (kind) {
        case "prepend":
          items.add(0, row);
          break;
        case "append":
          items.add(row);
          break;
        case "replace":
          items.clear();
          items.add(row);
          break;
        case "insert_before":
        case "insert":
          {
            int n = -1;
            for (int j = 0; j < items.size(); j++)
              if (items.get(j).optString("id").equals(r.optString("after"))) {
                n = j;
                break;
              }
            items.add(n < 0 ? items.size() : n + (kind.equals("insert_before") ? 0 : 1), row);
            break;
          }
        case "remove":
          {
            List<String> ids =
                Json.strings(
                    r.optJSONArray("sections") == null
                        ? new JSONArray()
                        : r.optJSONArray("sections"));
            items.removeIf(x -> ids.contains(x.optString("id")));
            break;
          }
        case "substitute":
          {
            String flags = r.optString("flags", "g");
            int f =
                (flags.contains("i") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0)
                    | (flags.contains("m") ? Pattern.MULTILINE : 0)
                    | (flags.contains("s") ? Pattern.DOTALL : 0);
            Pattern pattern = Pattern.compile(r.optString("pattern"), f);
            List<String> ids =
                Json.strings(
                    r.optJSONArray("sections") == null
                        ? new JSONArray()
                        : r.optJSONArray("sections"));
            for (JSONObject item : items)
              if (ids.isEmpty() || ids.contains(item.optString("id"))) {
                Matcher m = pattern.matcher(item.optString("text"));
                Json.put(
                    item,
                    "text",
                    flags.contains("g")
                        ? m.replaceAll(r.optString("replacement"))
                        : m.replaceFirst(r.optString("replacement")));
              }
            break;
          }
        case "literal":
          for (JSONObject item : items)
            Json.put(
                item,
                "text",
                item.optString("text").replace(r.optString("pattern"), r.optString("replacement")));
          break;
        default:
          throw new IllegalArgumentException("未知规则：" + kind);
      }
    }
    JSONArray a = new JSONArray();
    for (JSONObject item : items) a.put(item);
    return a;
  }

  static String render(JSONArray parts) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < parts.length(); i++) {
      if (i > 0) b.append("\n\n");
      b.append(parts.optJSONObject(i).optString("text"));
    }
    return b.toString();
  }

  static void install() {
    XC_MethodHook bind =
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("previous", scope.get());
            Object input = p.args[0];
            if (input == null && p.args.length == 2) input = Host.field(p.args[1], "L$0");
            if (input != null) {
              String sid = Host.text(input, "a");
              scope.set(sid);
              Host.session = sid;
            }
          }

          protected void afterHookedMethod(MethodHookParam p) {
            scope.set((String) p.getObjectExtra("previous"));
          }
        };
    Xposed.hookAllMethods(Host.cls("com.flyme.agentos.agent.core.context.pipeline.e"), "a", bind);
    Xposed.hookAllMethods(Host.cls("com.flyme.agentos.agent.core.context.pipeline.e"), "b", bind);
    Xposed.hookAllConstructors(
        Host.cls("com.flyme.agentos.agent.core.context.y"),
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            if (scope.get() != null) envelopeSessions.put(p.thisObject, scope.get());
          }
        });
    Xposed.hookAllConstructors(
        Host.cls("Z4.n"),
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            Object env = p.args[0];
            String sid = envelopeSessions.get(env);
            if (sid == null) sid = scope.get();
            if (sid == null) return;
            JSONArray original = new JSONArray();
            List<Object> originals = new ArrayList<>();
            for (String f : new String[] {"a", "b", "c", "d"})
              for (Object seg : (List<?>) Host.field(env, f)) {
                originals.add(seg);
                original.put(
                    Json.o(
                        "id",
                        Host.text(seg, "a"),
                        "text",
                        Host.text(seg, "e"),
                        "layer",
                        Host.text(seg, "b"),
                        "source",
                        Host.text(seg, "d")));
              }
            Store.save("promptSource", sid, Json.o("segments", original, "rendered", p.args[1]));
            JSONObject preset = preset(sid);
            if (!preset.optString("target", "base").equals("assembled")
                || preset.optJSONArray("rules") == null
                || preset.optJSONArray("rules").length() == 0) return;
            JSONArray changed =
                applyPrompt(original, (String) p.args[1], preset.optJSONArray("rules"));
            List<Object> segments = new ArrayList<>();
            Object layer =
                Reflect.getStaticObjectField(
                    Host.cls("com.flyme.agentos.agent.core.context.PromptLayer"), "STABLE_PREFIX");
            for (int i = 0; i < changed.length(); i++) {
              JSONObject part = changed.optJSONObject(i);
              Object template = originals.isEmpty() ? null : originals.get(0);
              if (template == null) continue;
              Object seg =
                  Host.make(
                      "com.flyme.agentos.agent.core.context.A",
                      part.optString("id"),
                      layer,
                      Host.field(template, "c"),
                      Host.field(template, "d"),
                      part.optString("text"),
                      estimate(part.optString("text")),
                      Reflect.getIntField(template, "g"),
                      Host.field(template, "h"),
                      Host.field(template, "i"),
                      Host.field(template, "j"),
                      Host.field(template, "k"));
              segments.add(seg);
            }
            p.args[0] =
                Host.make(
                    "com.flyme.agentos.agent.core.context.y",
                    segments,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList());
            p.args[1] = render(changed);
            Object report = p.args[2];
            Object estimator = Host.field(Host.services.get("fusion"), "a");
            int total = ((Number) Host.call(estimator, "c", p.args[1])).intValue();
            Reflect.setIntField(report, "i", total);
            for (String f : new String[] {"j", "k", "l", "m", "n", "o", "p"})
              Reflect.setIntField(report, f, 0);
            Reflect.setIntField(report, "q", total);
            Store.save(
                "promptEffective",
                sid,
                Json.o("segments", changed, "rendered", p.args[1], "tokens", total));
          }
        });
  }

  static int estimate(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) n += s.charAt(i) > 127 ? 4 : 1;
    return (n + 3) / 4;
  }
}
