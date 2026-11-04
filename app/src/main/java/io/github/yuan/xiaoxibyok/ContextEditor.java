package io.github.yuan.xiaoxibyok;

import java.util.*;
import org.json.*;

final class ContextEditor {
  static String key(Object msg) {
    return Host.text(msg, "a") + "\u001f" + Host.text(msg, "b") + "\u001f" + Host.text(msg, "d");
  }

  static Object message(String role, String content) {
    Object r =
        Reflect.callStaticMethod(
            Host.cls("com.flyme.agentos.llm.api.model.Message$Role"),
            "valueOf",
            role.toUpperCase(Locale.ROOT));
    return Host.make(
        "Y6.a0", r, content, null, null, Collections.emptyList(), Collections.emptyList());
  }

  static JSONArray pack(List<?> history) {
    JSONArray a = new JSONArray();
    for (Object m : history)
      a.put(
          Json.o(
              "role",
              Host.text(m, "a"),
              "content",
              Host.text(m, "b"),
              "tool_call_id",
              Host.text(m, "d"),
              "reasoning",
              Host.text(m, "f"),
              "tools",
              Json.str(Host.field(m, "c"))));
    return a;
  }

  // Edits and turn retention can remove one side of an assistant/tool exchange. Keep only
  // complete pairs while preserving assistant prose, images, reasoning and tool-result order.
  static List<Object> paired(List<Object> messages) {
    List<Object> result = new ArrayList<>();
    Set<String> pending = new HashSet<>();
    for (int i = 0; i < messages.size(); i++) {
      Object message = messages.get(i);
      String role = Host.text(message, "a");
      if (role.equals("TOOL")) {
        if (pending.remove(Host.text(message, "d"))) result.add(message);
        continue;
      }
      pending.clear();
      List<?> calls = (List<?>) Host.field(message, "c");
      if (role.equals("ASSISTANT") && !calls.isEmpty()) {
        Set<String> available = new HashSet<>();
        for (int j = i + 1; j < messages.size(); j++) {
          Object next = messages.get(j);
          if (!Host.text(next, "a").equals("TOOL")) break;
          available.add(Host.text(next, "d"));
        }
        List<Object> complete = new ArrayList<>();
        for (Object call : calls) {
          String id = Host.text(call, "a");
          if (available.contains(id) && pending.add(id)) complete.add(call);
        }
        if (complete.size() != calls.size())
          message =
              Host.make(
                  "Y6.a0",
                  Host.field(message, "a"),
                  Host.field(message, "b"),
                  Host.field(message, "d"),
                  Host.field(message, "f"),
                  complete,
                  Host.field(message, "e"));
      }
      result.add(message);
    }
    return result;
  }

  static void install() {
    Xposed.hookAllConstructors(
        Host.cls("com.flyme.agentos.agent.core.context.m"),
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            Host.contexts.put((String) p.args[8], p.thisObject);
            String sid = (String) p.args[8];
            JSONArray edits = Store.settings(sid).optJSONArray("edits");
            if (edits != null && edits.length() > 0) Store.setting(sid, "rebuild", true);
          }
        });
    Xposed.hookAllConstructors(
        Host.cls("com.flyme.agentos.agent.core.context.j"),
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (p.args.length != 27) return;
            String sid = (String) p.args[0];
            Host.session = sid;
            String original = (String) p.args[2];
            JSONObject previous = Store.get("contextEffective", sid);
            if (original.equals(previous.optString("base")))
              original = Store.get("baseSnapshot", sid).optString("text", original);
            Store.save("baseSnapshot", sid, Json.o("text", original));
            p.args[2] = PromptEngine.base(sid, original);
            JSONObject settings = Store.settings(sid);
            List<?> history = (List<?>) p.args[3];
            List<Object> withoutAdditions = new ArrayList<>();
            for (Object m : history)
              if (!Boolean.TRUE.equals(Reflect.getAdditionalInstanceField(m, "xiaoxi.addition")))
                withoutAdditions.add(m);
            history = withoutAdditions;
            Store.save("contextSource", sid, Json.o("messages", pack(history), "input", p.args[1]));
            JSONArray edits = settings.optJSONArray("edits");
            JSONArray additions = settings.optJSONArray("additions");
            if (settings.optBoolean("rebuild")) {
              Object snapshot = Host.call(Host.services.get("sessions"), "x", sid);
              List<?> records = (List<?>) Host.field(snapshot, "b");
              List<Object> all = new ArrayList<>(), old = new ArrayList<>();
              for (Object record : records) {
                List<?> msgs =
                    (List<?>)
                        Reflect.callStaticMethod(
                            Host.cls("com.flyme.agentos.session.persistence.b"),
                            "c",
                            record,
                            Host.field(record, "k"));
                old.addAll(msgs);
                JSONObject edit = null;
                String id = Host.text(record, "c");
                if (id.isEmpty()) id = Host.text(record, "g");
                for (int i = 0; edits != null && i < edits.length(); i++) {
                  JSONObject e = edits.optJSONObject(i);
                  if (e.optString("messageId").equals(id)) {
                    edit = e;
                    break;
                  }
                }
                if (edit != null && edit.optBoolean("exclude")) continue;
                for (Object m : msgs) {
                  if (edit != null
                      && edit.has("content")
                      && Host.text(m, "a").equalsIgnoreCase(Host.text(record, "a"))) {
                    m =
                        Host.make(
                            "Y6.a0",
                            Host.field(m, "a"),
                            edit.optString("content"),
                            Host.field(m, "d"),
                            Host.field(m, "f"),
                            Host.field(m, "c"),
                            Host.field(m, "e"));
                  }
                  all.add(m);
                }
              }
              Set<String> persisted = new HashSet<>();
              for (Object m : old) persisted.add(key(m));
              int tail = 0;
              boolean matched = false;
              for (int i = history.size() - 1; i >= 0; i--)
                if (persisted.contains(key(history.get(i)))) {
                  tail = i + 1;
                  matched = true;
                  break;
                }
              if (!matched && !old.isEmpty()) {
                tail = history.size();
                for (int i = history.size() - 1; i >= 0; i--)
                  if (Host.text(history.get(i), "a").equals("USER")
                      && Host.text(history.get(i), "b").equals(p.args[1])) {
                    tail = i;
                    break;
                  }
              }

              all.addAll(history.subList(tail, history.size()));
              history = all;
              p.args[8] = null;
              p.args[9] = 0;
            }
            List<Object> effective = new ArrayList<>(history);
            int keep = settings.optInt("keepTurns", 0);
            if (keep > 0) {
              int turns = 0, start = 0;
              for (int i = effective.size() - 1; i >= 0; i--)
                if (Host.text(effective.get(i), "a").equals("USER") && ++turns == keep) {
                  start = i;
                  break;
                }
              if (start > 0) {
                effective = new ArrayList<>(effective.subList(start, effective.size()));
                p.args[8] = null;
                p.args[9] = 0;
              }
            }
            StringBuilder pinned = new StringBuilder();
            if (edits != null) {
              for (int i = 0; i < edits.length(); i++) {
                JSONObject edit = edits.optJSONObject(i);
                if (edit.optBoolean("pinned"))
                  pinned
                      .append("\n\n")
                      .append(edit.optString("role", "message"))
                      .append(": ")
                      .append(edit.optString("content"));
              }
            }
            if (pinned.length() > 0) p.args[2] = p.args[2] + "\n\n# 固定上下文" + pinned;
            if (additions != null) {
              List<Object> extra = new ArrayList<>();
              for (int i = 0; i < additions.length(); i++) {
                JSONObject a = additions.optJSONObject(i);
                Object added = message(a.optString("role", "user"), a.optString("content"));
                Reflect.setAdditionalInstanceField(added, "xiaoxi.addition", true);
                extra.add(added);
              }
              effective.addAll(0, extra);
            }
            if (settings.optBoolean("rebuild") || keep > 0 || additions != null)
              effective = paired(effective);
            p.args[3] = effective;
            if (settings.has("summary")) {
              String summary = settings.optString("summary");
              p.args[8] =
                  summary.isEmpty()
                      ? null
                      : Host.make(
                          "g5.d",
                          summary,
                          -1,
                          1,
                          System.currentTimeMillis(),
                          0,
                          PromptEngine.estimate(summary),
                          Collections.emptyList(),
                          0,
                          0);
            }
            Store.save(
                "contextEffective",
                sid,
                Json.o("messages", pack(effective), "input", p.args[1], "base", p.args[2]));
          }
        });
    Compression.install();
  }

  static void edit(
      String sid, String messageId, String content, boolean excluded, boolean pinned, String role) {
    JSONObject settings = Store.settings(sid);
    JSONArray edits = settings.optJSONArray("edits");
    if (edits == null) edits = new JSONArray();
    JSONArray next = new JSONArray();
    for (int i = 0; i < edits.length(); i++)
      if (!edits.optJSONObject(i).optString("messageId").equals(messageId))
        next.put(edits.optJSONObject(i));
    next.put(
        Json.o(
            "messageId",
            messageId,
            "content",
            content,
            "exclude",
            excluded,
            "pinned",
            pinned,
            "role",
            role));
    Json.put(settings, "edits", next);
    Json.put(settings, "rebuild", true);
    settings.remove("summary");
    Store.save("session", sid, settings);
    Store.event(
        sid,
        "context.edit",
        Json.o("messageId", messageId, "excluded", excluded, "content", content));
  }
}
