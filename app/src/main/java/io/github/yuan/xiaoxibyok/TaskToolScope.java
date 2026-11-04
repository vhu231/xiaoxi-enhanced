package io.github.yuan.xiaoxibyok;

import java.util.*;
import org.json.*;

/** Restricts tools exposed and executed by one scheduled session without changing the registry. */
final class TaskToolScope implements AutoCloseable {
  static final Map<Object, TaskToolScope> active = Collections.synchronizedMap(new WeakHashMap<>());

  final Object loop;
  final Set<String> allowed;

  static JSONObject snapshot(JSONObject configuration) throws JSONException {
    JSONObject copy = new JSONObject(configuration.toString());
    JSONArray steps = copy.getJSONArray("steps");
    for (int i = 0; i < steps.length(); i++) {
      JSONObject step = steps.getJSONObject(i);
      String type = step.optString("type");
      if (!type.equals("agent") && !type.equals("tool")) continue;
      JSONArray tools = step.optJSONArray("tools");
      if (tools == null) tools = copy.optJSONArray("tools");
      if (tools != null) step.put("toolSnapshot", new JSONArray(tools.toString()));
    }
    return copy;
  }

  TaskToolScope(Object session, JSONArray tools) {
    loop = Host.field(session, "d");
    allowed = new LinkedHashSet<>(Json.strings(tools));
    if (active.putIfAbsent(loop, this) != null) throw new IllegalStateException("该任务会话已有执行中的工具范围");
  }

  static TaskToolScope current(Object loop) {
    return active.get(loop);
  }

  static void check(Object loop, String name, String arguments) {
    TaskToolScope scope = current(loop);
    if (scope == null) return;
    if (!scope.allowed.contains(name)) throw new IllegalArgumentException("工具不在定时任务允许范围：" + name);
    if (name.equals("invoke_tool")) {
      String target = Json.obj(arguments).optString("tool_name");
      if (!scope.allowed.contains(target))
        throw new IllegalArgumentException("代理目标不在定时任务允许范围：" + target);
    }
  }

  static void install() {
    Xposed.hookAllMethods(
        Host.cls("com.flyme.agentos.agent.core.loop.O"),
        "v0",
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            TaskToolScope scope = current(p.thisObject);
            Object result = p.getResult();
            if (scope == null || !(result instanceof List<?>)) return;
            List<Object> filtered = new ArrayList<>();
            for (Object definition : (List<?>) result)
              if (scope.allowed.contains(Host.text(definition, "a"))) filtered.add(definition);
            p.setResult(filtered);
          }
        });
    Xposed.hookAllMethods(
        Host.cls("com.flyme.agentos.agent.core.loop.O"),
        "O",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (current(p.thisObject) == null || p.args[0] == null) return;
            for (Object call : (List<?>) p.args[0])
              check(p.thisObject, Host.text(call, "b"), Host.text(call, "c"));
          }
        });
  }

  static String callIn(
      Object context, Object session, String taskId, String tool, JSONObject arguments)
      throws Exception {
    Object loop = Host.field(session, "d");
    String sid = Host.text(session, "a");
    check(loop, tool, arguments.toString());
    Object registry = Host.field(loop, "b");
    Object definition = Host.call(registry, "b", tool);
    if (definition == null) throw new IllegalArgumentException("原生工具尚未注册：" + tool);
    if (!((Boolean) Host.call(registry, "c", tool)))
      throw new IllegalArgumentException("原生工具已停用：" + tool);
    String runId = "xiaoxi-scheduler:" + taskId + ":" + UUID.randomUUID();
    String requestId = UUID.randomUUID().toString();
    Object executionContext =
        Host.make(
            "com.flyme.agentos.agent.core.tool.U",
            runId,
            "BACKGROUND",
            "scheduler",
            "background",
            sid,
            requestId,
            UUID.randomUUID().toString(),
            Integer.valueOf(0),
            Integer.valueOf(0),
            "定时任务固定工具调用：" + tool,
            System.currentTimeMillis(),
            Host.make("com.flyme.agentos.agent.core.loop.AgentLoop$prepareRun$5", loop, null),
            Host.make("com.flyme.agentos.agent.core.loop.AgentLoop$prepareRun$6", loop, null),
            Host.make("com.flyme.agentos.agent.core.loop.M", loop),
            Reflect.getStaticObjectField(
                Host.cls("com.flyme.agentos.agent.core.tool.ToolInvocationSource"), "DEFAULT"));
    Object previous = Host.field(loop, "P");
    String eventId = UUID.randomUUID().toString();
    long start = System.currentTimeMillis();
    JSONObject event =
        Json.o(
            "taskId", taskId,
            "runId", runId,
            "requestId", requestId,
            "tool", tool,
            "arguments", arguments,
            "status", "running");
    Store.event(eventId, sid, "scheduler.tool", start, event);
    try {
      Reflect.setObjectField(loop, "P", executionContext);
      Object call = Host.make("Y6.l0", requestId, tool, arguments.toString());
      Object result =
          Host.awaitIn(
              context, loop, "O", Collections.singletonList(call), Integer.valueOf(0), false);
      String text = resultText(result);
      Json.put(event, "status", "completed");
      Json.put(event, "result", text);
      return text;
    } catch (Exception error) {
      Json.put(event, "status", "error");
      Json.put(event, "error", error.toString());
      throw error;
    } finally {
      Reflect.setObjectField(loop, "P", previous);
      Json.put(event, "end", System.currentTimeMillis());
      Store.event(eventId, sid, "scheduler.tool", start, event);
    }
  }

  static String resultText(Object result) {
    if (result instanceof List<?>) {
      List<?> results = (List<?>) result;
      if (results.size() == 1) return resultText(results.get(0));
      JSONArray values = new JSONArray();
      for (Object item : results) values.put(resultText(item));
      return values.toString();
    }
    if (result == null) return "";
    if (Host.cls("com.flyme.agentos.agent.core.tool.Y").isInstance(result)) {
      String content = Host.text(result, "b");
      if (!Reflect.getBooleanField(result, "a"))
        throw new IllegalStateException(content.isEmpty() ? "原生工具执行失败" : content);
      return content;
    }
    if (Host.cls("Y6.a0").isInstance(result)) return Host.text(result, "b");
    return String.valueOf(result);
  }

  public void close() {
    active.remove(loop, this);
  }
}
