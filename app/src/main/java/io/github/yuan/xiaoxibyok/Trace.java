package io.github.yuan.xiaoxibyok;

import de.robv.android.xposed.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

final class Trace {
  static final Map<Object, Run> runs = Collections.synchronizedMap(new WeakHashMap<>());
  static final Map<String, Run> active = new HashMap<>();
  static final Map<String, Run> latest = new java.util.concurrent.ConcurrentHashMap<>();
  static final ThreadLocal<Run> parsing = new ThreadLocal<>();
  static final ThreadLocal<Parent> toolParent = new ThreadLocal<>();

  static final class Parent {
    final String session, requestId;

    Parent(Object context) {
      session = Host.text(context, "e");
      requestId = Host.text(context, "f");
    }
  }

  static final class Run {
    final String id = UUID.randomUUID().toString(), session, requestId, caller, parentRequestId;
    final int turn, step;
    int attempt = 1;
    String logicalRequestId = id;
    final long start = System.currentTimeMillis();
    long first, end, lastSave;
    String model = "", status = "running";
    String endpoint = "", task = "", configSource = "";
    JSONObject failure = new JSONObject();
    JSONObject request = new JSONObject(), usage = new JSONObject(), reasoning = new JSONObject();
    StringBuilder thinking = new StringBuilder(), text = new StringBuilder();
    LinkedHashMap<String, JSONObject> tools = new LinkedHashMap<>();

    Run(Object req) {
      session = Host.text(req, "g");
      requestId = Host.text(req, "e");
      caller = Host.text(req, "f");
      Object parent = XposedHelpers.getAdditionalInstanceField(req, "xiaoxi.parentRequestId");
      parentRequestId = parent == null ? "" : parent.toString();
      Object t = Host.field(req, "h"), s = Host.field(req, "i");
      turn = t == null ? 0 : ((Number) t).intValue();
      step = s == null ? 0 : ((Number) s).intValue();
    }

    JSONObject json() {
      return Json.o(
          "requestId",
          requestId,
          "parentRequestId",
          parentRequestId,
          "logicalRequestId",
          logicalRequestId,
          "attempt",
          attempt,
          "caller",
          caller,
          "turn",
          turn,
          "step",
          step,
          "model",
          model,
          "endpoint",
          endpoint,
          "task",
          task,
          "configSource",
          configSource,
          "reasoning",
          reasoning,
          "start",
          start,
          "first",
          first,
          "end",
          end,
          "ttftMs",
          first > 0 ? first - start : JSONObject.NULL,
          "status",
          status,
          "error",
          failure,
          "request",
          request,
          "usage",
          usage,
          "thinking",
          thinking.toString(),
          "content",
          text.toString(),
          "tools",
          new JSONArray(tools.values()));
    }

    void save() {
      lastSave = System.currentTimeMillis();
      Store.event(id, session, "llm.request", start, json());
    }
  }

  static String key(Object req) {
    String requestId = Host.text(req, "e");
    if (requestId.isEmpty()) {
      synchronized (req) {
        requestId =
            (String) XposedHelpers.getAdditionalInstanceField(req, "xiaoxi.requestIdentity");
        if (requestId == null) {
          requestId = UUID.randomUUID().toString();
          XposedHelpers.setAdditionalInstanceField(req, "xiaoxi.requestIdentity", requestId);
        }
      }
    }
    return Host.text(req, "g")
        + "|"
        + requestId
        + "|"
        + Host.text(req, "f")
        + "|"
        + Host.text(req, "h")
        + "|"
        + Host.text(req, "i");
  }

  static synchronized Run begin(Object req) {
    Run r = new Run(req);
    Run previous = active.get(key(req));
    if (previous != null) {
      r.attempt = previous.attempt + 1;
      r.logicalRequestId = previous.logicalRequestId;
    }
    active.put(key(req), r);
    runs.put(req, r);
    if (!r.session.isEmpty()) latest.put(r.session, r);
    return r;
  }

  static synchronized Run run(Object req) {
    Run r = runs.get(req);
    if (r == null || r != active.get(key(req))) {
      String key = key(req);
      r = active.get(key);
      if (r == null || !r.status.equals("running")) {
        r = new Run(req);
        active.put(key, r);
        if (!r.session.isEmpty()) latest.put(r.session, r);
      }
      runs.put(req, r);
    }
    return r;
  }

  static void request(Object req, JSONObject body) {
    Run r = run(req);
    synchronized (r) {
      r.request = Json.obj(body.toString());
      r.model = body.optString("model");
      r.save();
    }
  }

  static void route(Object req, String endpoint, String task, String source) {
    Run r = run(req);
    synchronized (r) {
      r.endpoint = endpoint;
      r.task = task;
      r.configSource = source;
      r.save();
    }
  }

  static void reasoning(Object req, Reasoning.Selection selection) {
    Run r = run(req);
    synchronized (r) {
      r.reasoning = selection.json();
      r.save();
    }
  }

  static void tool(Run r, Object tool) {
    String id = Host.text(tool, "a");
    r.tools.put(
        id, Json.o("id", id, "name", Host.text(tool, "b"), "arguments", Host.text(tool, "c")));
  }

  static void chunk(Run r, Object chunk) {
    synchronized (r) {
      String content = Host.text(chunk, "a"), reason = Host.text(chunk, "b");
      List<?> tools = (List<?>) Host.field(chunk, "c");
      if (r.first == 0 && (!content.isEmpty() || !reason.isEmpty() || !tools.isEmpty()))
        r.first = System.currentTimeMillis();
      r.text.append(content);
      r.thinking.append(reason);
      for (Object tool : tools) tool(r, tool);
      if (XposedHelpers.getBooleanField(chunk, "d")) {
        r.end = System.currentTimeMillis();
        r.status = "complete";
      }
      if (r.end > 0 || System.currentTimeMillis() - r.lastSave >= 250) r.save();
    }
  }

  static void finish(Run r, Throwable error) {
    synchronized (r) {
      r.end = System.currentTimeMillis();
      r.status =
          error == null
              ? "complete"
              : error instanceof java.util.concurrent.CancellationException ? "cancelled" : "error";
      if (error != null) {
        JSONArray causes = new JSONArray();
        Throwable cause = error;
        while (cause != null) {
          causes.put(Json.o("type", cause.getClass().getName(), "message", cause.getMessage()));
          cause = cause.getCause();
        }
        r.failure = Json.o("message", error.toString(), "causes", causes);
      }
      r.save();
    }
  }

  static void rawUsage(Run r, JSONObject raw) {
    JSONObject result = new JSONObject();
    if (!raw.isNull("prompt_tokens")) Json.put(result, "input", raw.optLong("prompt_tokens"));
    if (!raw.isNull("completion_tokens"))
      Json.put(result, "output", raw.optLong("completion_tokens"));
    if (!raw.isNull("total_tokens")) Json.put(result, "total", raw.optLong("total_tokens"));
    JSONObject details = raw.optJSONObject("prompt_tokens_details");
    if (!raw.isNull("prompt_cache_hit_tokens"))
      Json.put(result, "cached", raw.optLong("prompt_cache_hit_tokens"));
    else if (details != null && !details.isNull("cached_tokens"))
      Json.put(result, "cached", details.optLong("cached_tokens"));
    if (!raw.isNull("prompt_cache_miss_tokens"))
      Json.put(result, "uncached", raw.optLong("prompt_cache_miss_tokens"));
    Json.put(result, "raw", raw);
    synchronized (r) {
      // Usage frames may be partial; replace reported fields without summing cumulative frames.
      for (Iterator<String> keys = result.keys(); keys.hasNext(); ) {
        String key = keys.next();
        Json.put(r.usage, key, result.opt(key));
      }
      r.save();
    }
  }

  static void install() {
    XposedBridge.hookAllMethods(
        Host.cls("com.flyme.agentos.agent.core.loop.O"),
        "O",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("parent", toolParent.get());
            Object context = Host.field(p.thisObject, "P");
            if (context != null && !Host.text(context, "e").isEmpty())
              toolParent.set(new Parent(context));
          }

          protected void afterHookedMethod(MethodHookParam p) {
            toolParent.set((Parent) p.getObjectExtra("parent"));
          }
        });
    XposedBridge.hookAllMethods(
        Host.cls("com.flyme.agentos.app.phone.toolprovider.A"),
        "d",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            Parent parent = toolParent.get();
            if (parent != null && p.args.length > 4 && p.args[4] != null)
              XposedHelpers.setAdditionalInstanceField(p.args[4], "xiaoxi.parent", parent);
          }
        });
    XposedBridge.hookAllMethods(
        Host.cls(
            "com.flyme.agentos.app.phone.toolprovider.FlymeImageAnalyzeProvider$analyzeByCloudVlm$1"),
        "invokeSuspend",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("parent", toolParent.get());
            Parent parent =
                (Parent) XposedHelpers.getAdditionalInstanceField(p.thisObject, "xiaoxi.parent");
            if (parent != null) toolParent.set(parent);
          }

          protected void afterHookedMethod(MethodHookParam p) {
            toolParent.set((Parent) p.getObjectExtra("parent"));
          }
        });
    XposedBridge.hookAllConstructors(
        Host.cls("com.flyme.agentos.llm.impl.openai.OpenAiCompatibleClient$executeChatStream$1"),
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            XposedHelpers.setAdditionalInstanceField(p.thisObject, "xiaoxi.run", parsing.get());
          }
        });
    XposedBridge.hookAllMethods(
        Host.cls("com.flyme.agentos.llm.impl.openai.k"),
        "d",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("previous", parsing.get());
            Object req = p.args[2], collector = p.args[1];
            Run r;
            if (req == null)
              r = (Run) XposedHelpers.getAdditionalInstanceField(p.args[4], "xiaoxi.run");
            else {
              r = begin(req);
              Class<?> it = ((Method) p.method).getParameterTypes()[1];
              p.args[1] =
                  Proxy.newProxyInstance(Host.cl, new Class[] {it}, new Collector(collector, r));
            }
            p.setObjectExtra("run", r);
            parsing.set(r);
          }

          protected void afterHookedMethod(MethodHookParam p) {
            Run r = (Run) p.getObjectExtra("run");
            if (r != null && p.hasThrowable()) finish(r, p.getThrowable());
            parsing.set((Run) p.getObjectExtra("previous"));
          }
        });
    for (String name :
        new String[] {
          "OpenAiCompatibleClient$executeChatStream$3", "OpenAiCompatibleClient$chat$2"
        })
      XposedBridge.hookAllMethods(
          Host.cls("com.flyme.agentos.llm.impl.openai." + name),
          "invokeSuspend",
          new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
              p.setObjectExtra("previous", parsing.get());
              Object req = Host.field(p.thisObject, "$request");
              Run r =
                  (Run) XposedHelpers.getAdditionalInstanceField(p.thisObject, "xiaoxi.attempt");
              if (r == null) {
                r =
                    p.thisObject.getClass().getName().endsWith("$chat$2")
                            && XposedHelpers.getIntField(p.thisObject, "label") == 0
                        ? begin(req)
                        : run(req);
                XposedHelpers.setAdditionalInstanceField(p.thisObject, "xiaoxi.attempt", r);
              }
              parsing.set(r);
              p.setObjectExtra("run", r);
            }

            protected void afterHookedMethod(MethodHookParam p) {
              Run r = (Run) p.getObjectExtra("run");
              Object result = p.getResult();
              if (p.hasThrowable()) finish(r, p.getThrowable());
              else if (result
                  != XposedHelpers.callStaticMethod(
                      Host.cls("kotlin.coroutines.intrinsics.IntrinsicsKt"),
                      "getCOROUTINE_SUSPENDED")) {
                if (result != null
                    && result.getClass().getName().equals("com.flyme.agentos.llm.impl.openai.A")) {
                  if (!XposedHelpers.getBooleanField(result, "a")) {
                    finish(
                        r,
                        new IllegalStateException(
                            Host.text(result, "c") + ": " + Host.text(result, "d")));
                    parsing.set((Run) p.getObjectExtra("previous"));
                    return;
                  }
                  result = Host.field(result, "b");
                }
                if (result != null && result.getClass().getName().equals("Y6.l")) {
                  synchronized (r) {
                    r.text = new StringBuilder(Host.text(result, "a"));
                    r.thinking = new StringBuilder(Host.text(result, "e"));
                    for (Object tool : (List<?>) Host.field(result, "b")) tool(r, tool);
                  }
                }
                finish(r, null);
              }
              parsing.set((Run) p.getObjectExtra("previous"));
            }
          });
    XposedBridge.hookAllMethods(
        Host.cls("Th.b"),
        "e",
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            Run r = parsing.get();
            if (r == null || p.hasThrowable()) return;
            String raw = (String) p.args[0];
            if (!raw.contains("\"usage\"")) return;
            JSONObject body = Json.obj(raw), usage = body.optJSONObject("usage");
            if (usage != null) rawUsage(r, usage);
          }
        });
    XposedBridge.hookAllMethods(
        Host.cls("L6.O"),
        "b",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            Object e = p.args[0];
            Map<?, ?> attributes = (Map<?, ?>) Host.field(e, "m");
            JSONObject attrs = new JSONObject();
            for (Map.Entry<?, ?> a : attributes.entrySet()) {
              Object v = a.getValue();
              String str = String.valueOf(v);
              try {
                v = new JSONTokener(str).nextValue();
              } catch (JSONException ex) {
                v = str;
              }
              Json.put(attrs, String.valueOf(a.getKey()), v);
            }
            String sid = Host.text(e, "e"), id = Host.text(e, "a");
            if (id.isEmpty()) id = Host.text(e, "c") + ":" + Host.text(e, "g");
            Store.event(
                "native:" + id,
                sid,
                Host.text(e, "g"),
                XposedHelpers.getLongField(e, "h"),
                Json.o(
                    "trace",
                    Host.text(e, "b"),
                    "span",
                    Host.text(e, "c"),
                    "parent",
                    Host.text(e, "d"),
                    "start",
                    Host.field(e, "h"),
                    "end",
                    Host.field(e, "i"),
                    "duration",
                    Host.field(e, "j"),
                    "status",
                    Host.text(e, "k"),
                    "attributes",
                    attrs));
          }
        });
    XposedBridge.hookAllConstructors(
        Host.cls("com.flyme.agentos.agent.core.context.t"),
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            String sid = PromptEngine.scope.get();
            if (sid == null) return;
            if (Store.settings(sid).optBoolean("rebuild")) Store.setting(sid, "rebuild", false);
            Store.save(
                "window",
                sid,
                Json.o(
                    "tokens",
                    p.args[3],
                    "limit",
                    p.args[4] == null ? 0 : XposedHelpers.getIntField(p.args[4], "a"),
                    "report",
                    Json.str(p.args[6]),
                    "budget",
                    Json.str(p.args[4]),
                    "prompt",
                    p.args[0]));
          }
        });
  }

  static final class Collector implements InvocationHandler {
    final Object original;
    final Run attempt;

    Collector(Object o, Run r) {
      original = o;
      attempt = r;
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (args != null
          && args.length > 0
          && args[0] != null
          && args[0].getClass().getName().equals("Y6.f")) chunk(attempt, args[0]);
      try {
        return method.invoke(original, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }

  static JSONObject stats(String sid) {
    JSONArray events = Store.events(sid, Integer.MAX_VALUE, 0, "llm.request");
    long input = 0, output = 0, cached = 0;
    int requests = 0, turn = 0, usageReports = 0, outputReports = 0, cacheReports = 0;
    JSONObject recent = null;
    JSONArray turns = new JSONArray();
    TreeMap<Integer, JSONObject> groups = new TreeMap<>();
    Set<String> logicalRequests = new HashSet<>();
    for (int i = 0; i < events.length(); i++) {
      JSONObject event = events.optJSONObject(i);
      if (!event.optString("kind").equals("llm.request")) continue;
      JSONObject d = event.optJSONObject("data");
      if (recent == null) recent = d;
      requests++;
      logicalRequests.add(d.optString("logicalRequestId", event.optString("id")));
      int number = d.optInt("turn");
      turn = Math.max(turn, number);
      JSONObject u = d.optJSONObject("usage");
      input += u.optLong("input");
      output += u.optLong("output");
      cached += u.optLong("cached");
      if (u.has("input")) usageReports++;
      if (u.has("output")) outputReports++;
      if (u.has("cached")) cacheReports++;
      JSONObject g = groups.get(number);
      if (g == null) {
        g = Json.o("turn", number, "input", 0, "output", 0, "requests", 0, "steps", 0);
        groups.put(number, g);
      }
      Json.put(g, "input", g.optLong("input") + u.optLong("input"));
      Json.put(g, "output", g.optLong("output") + u.optLong("output"));
      Json.put(g, "inputReports", g.optInt("inputReports") + (u.has("input") ? 1 : 0));
      Json.put(g, "outputReports", g.optInt("outputReports") + (u.has("output") ? 1 : 0));
      Json.put(g, "requests", g.optInt("requests") + 1);
      Json.put(g, "steps", Math.max(g.optInt("steps"), d.optInt("step")));
    }
    for (JSONObject g : groups.values()) {
      if (g.optInt("inputReports") == 0) Json.put(g, "input", JSONObject.NULL);
      if (g.optInt("outputReports") == 0) Json.put(g, "output", JSONObject.NULL);
      turns.put(g);
    }
    return Json.o(
        "input",
        usageReports == 0 ? JSONObject.NULL : input,
        "output",
        outputReports == 0 ? JSONObject.NULL : output,
        "cached",
        cacheReports == 0 ? JSONObject.NULL : cached,
        "usageReports",
        usageReports,
        "outputReports",
        outputReports,
        "missingInputReports",
        requests - usageReports,
        "missingOutputReports",
        requests - outputReports,
        "cacheReports",
        cacheReports,
        "requests",
        requests,
        "logicalRequests",
        logicalRequests.size(),
        "retries",
        requests - logicalRequests.size(),
        "turns",
        turn,
        "byTurn",
        turns,
        "latest",
        recent == null ? new JSONObject() : recent);
  }

  static JSONObject recent(String sid) {
    Run r = latest.get(sid);
    if (r != null)
      synchronized (r) {
        return r.json();
      }
    JSONObject saved = Store.get("lastRequest", sid);
    if (saved.length() > 0) return saved;
    JSONArray rows = Store.events(sid, 1, 0, "llm.request");
    return rows.length() > 0 ? rows.optJSONObject(0).optJSONObject("data") : new JSONObject();
  }

  static String count(JSONObject u, String key) {
    return !u.isNull(key) ? String.valueOf(u.optLong(key)) : "—";
  }

  static String compactNumber(long value) {
    if (value < 1000) return String.valueOf(value);
    boolean millions = value >= 1_000_000;
    String formatted =
        java.math.BigDecimal.valueOf(value)
            .divide(java.math.BigDecimal.valueOf(millions ? 1_000_000 : 1000))
            .setScale(1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
    if (!millions && formatted.equals("1000")) return "1M";
    return formatted + (millions ? "M" : "K");
  }

  static String compactShort(String sid) {
    JSONObject recent = recent(sid), u = recent.optJSONObject("usage");
    if (u == null) u = new JSONObject();
    JSONObject win = Store.get("window", sid);
    String occupancy = win.has("tokens") ? compactNumber(win.optInt("tokens")) : "—";
    int cap = win.optInt("limit");
    JSONObject totals = stats(sid);
    String total =
        totals.isNull("input") && totals.isNull("output")
            ? "—"
            : compactNumber(totals.optLong("input") + totals.optLong("output"));
    String cache = "—";
    if (u.has("cached") && u.optLong("input") > 0)
      cache =
          String.format(Locale.CHINA, "%.0f%%", 100.0 * u.optLong("cached") / u.optLong("input"));
    long first = recent.optLong("first"), end = recent.optLong("end");
    String speed = "—";
    if (first > 0 && end > first && u.has("output"))
      speed = String.format(Locale.CHINA, "%.1f", u.optLong("output") * 1000.0 / (end - first));
    else if (first > 0) {
      int estimated =
          PromptEngine.estimate(recent.optString("content") + recent.optString("thinking"));
      speed =
          "≈"
              + String.format(
                  Locale.CHINA,
                  "%.1f",
                  estimated * 1000.0 / Math.max(1, System.currentTimeMillis() - first));
    }
    return "≈"
        + occupancy
        + "/"
        + (cap > 0 ? compactNumber(cap) : "—")
        + " · 总"
        + total
        + " · 缓存"
        + cache
        + " · "
        + speed
        + "t/s";
  }

  static String compact(String sid) {
    JSONObject recent = recent(sid), u = recent.optJSONObject("usage");
    if (u == null) u = new JSONObject();
    JSONObject win = Store.get("window", sid);
    int cap = win.optInt("limit");
    String occupancy = win.has("tokens") ? String.valueOf(win.optInt("tokens")) : "—";
    String cache =
        u.has("cached")
            ? count(u, "cached")
                + (u.optLong("input") > 0
                    ? String.format(
                        Locale.CHINA, " (%.1f%%)", 100.0 * u.optLong("cached") / u.optLong("input"))
                    : "")
            : "—";
    long first = recent.optLong("first"), end = recent.optLong("end");
    String speed =
        first > 0 && end > first && u.has("output")
            ? String.format(Locale.CHINA, "%.1f", u.optLong("output") * 1000.0 / (end - first))
            : "—";
    if (end == 0 && first > 0) {
      int estimated =
          PromptEngine.estimate(recent.optString("content") + recent.optString("thinking"));
      speed =
          "≈"
              + String.format(
                  Locale.CHINA,
                  "%.1f",
                  estimated * 1000.0 / Math.max(1, System.currentTimeMillis() - first));
    }
    return "上下文 ≈"
        + occupancy
        + " / "
        + (cap > 0 ? cap : "—")
        + " · 第 "
        + recent.optInt("turn")
        + " 轮 · 第 "
        + recent.optInt("step")
        + " 步\n输入 "
        + count(u, "input")
        + " · 输出 "
        + count(u, "output")
        + " · 缓存 "
        + cache
        + " · "
        + speed
        + " tok/s";
  }
}
