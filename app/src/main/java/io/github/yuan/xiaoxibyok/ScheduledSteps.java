package io.github.yuan.xiaoxibyok;

import de.robv.android.xposed.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;

/**
 * Runs extension steps inside the native task attempt, leaving alarms and lifecycle to Scheduler.
 */
final class ScheduledSteps {
  static final ExecutorService workers = Executors.newCachedThreadPool();
  static final String EXECUTOR = "com.flyme.agentos.app.common.scheduler.executor.m";

  static void install() {
    XposedBridge.hookAllMethods(
        Host.cls(
            "com.flyme.agentos.app.common.scheduler.executor.TaskExecutor$executeWithRetry$result$1"),
        "invokeSuspend",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            Object coroutine = p.thisObject;
            if (Boolean.TRUE.equals(
                XposedHelpers.getAdditionalInstanceField(coroutine, "xiaoxi.steps"))) {
              try {
                XposedHelpers.callStaticMethod(
                    Host.cls("kotlin.ResultKt"), "throwOnFailure", p.args[0]);
                p.setResult(p.args[0]);
              } catch (Throwable error) {
                p.setThrowable(error.getCause() == null ? error : error.getCause());
              }
              return;
            }
            if (XposedHelpers.getIntField(coroutine, "label") != 0) return;
            Object task = Host.field(coroutine, "$task");
            JSONObject config = Store.get("schedule", Host.text(task, "a"));
            if (!config.optBoolean("enabled") || config.optJSONArray("steps") == null) return;
            XposedHelpers.setAdditionalInstanceField(coroutine, "xiaoxi.steps", true);
            workers.execute(
                () -> {
                  Object result;
                  try {
                    result = run(coroutine, config);
                  } catch (Throwable error) {
                    result =
                        XposedHelpers.callStaticMethod(
                            Host.cls("kotlin.ResultKt"), "createFailure", error);
                  }
                  Host.call(coroutine, "resumeWith", result);
                });
            p.setResult(
                XposedHelpers.callStaticMethod(
                    Host.cls("kotlin.coroutines.intrinsics.IntrinsicsKt"),
                    "getCOROUTINE_SUSPENDED"));
          }
        });
    Host.capture(EXECUTOR, "taskExecutor");
    Host.capture("com.flyme.agentos.app.common.scheduler.executor.f", "taskQueue");
    XposedBridge.hookAllMethods(
        Host.cls(EXECUTOR),
        "w",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            String taskId = Host.text(p.args[0], "a"), reason = String.valueOf(p.args[2]);
            JSONObject config = Store.get("schedule", taskId);
            if (!config.optBoolean("enabled")) return;
            if (reason.contains("超出执行宽限期")
                && config.optString("missedPolicy", "native").equals("run")) {
              p.setResult(Host.unit());
              Store.event(
                  taskId, "scheduler.missed.run", Json.o("triggerType", String.valueOf(p.args[1])));
              triggerLater(
                  taskId,
                  Json.o(
                      "triggerType",
                      String.valueOf(p.args[1]),
                      "timestamp",
                      System.currentTimeMillis()),
                  0);
              return;
            }
            if (reason.contains("同一任务上次执行未完成")
                && config.optString("overlapPolicy", "skip").equals("queue")) {
              JSONObject pending =
                  Json.o(
                      "triggerType",
                      String.valueOf(p.args[1]),
                      "timestamp",
                      System.currentTimeMillis());
              Store.save("schedulePending", taskId, pending);
              Store.event(taskId, "scheduler.overlap.queued", pending);
              p.setResult(Host.unit());
            }
          }
        });
    XposedBridge.hookAllMethods(
        Host.cls("com.flyme.agentos.app.common.scheduler.executor.f"),
        "f",
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            String taskId = (String) p.args[0];
            JSONObject pending = Store.get("schedulePending", taskId);
            if (pending.length() == 0) return;
            Store.delete("schedulePending", taskId);
            triggerLater(taskId, pending, 250);
          }
        });
  }

  static void triggerLater(String taskId, JSONObject pending, long delayMs) {
    Host.main.postDelayed(
        () ->
            workers.execute(
                () -> {
                  try {
                    Object triggerType =
                        XposedHelpers.callStaticMethod(
                            Host.cls("com.flyme.agentos.scheduler.model.TriggerType"),
                            "valueOf",
                            pending.optString("triggerType"));
                    Host.await(
                        Host.services.get("taskExecutor"),
                        "v",
                        taskId,
                        triggerType,
                        pending.optLong("timestamp"));
                  } catch (Throwable error) {
                    XposedBridge.log(error);
                    Store.event(
                        taskId, "scheduler.deferred.error", Json.o("error", error.toString()));
                  }
                }),
        delayMs);
  }

  static Object substitute(Object value, String previous) throws JSONException {
    if (value instanceof String) return ((String) value).replace("${previous}", previous);
    if (value instanceof JSONObject) {
      JSONObject source = (JSONObject) value, result = new JSONObject();
      for (java.util.Iterator<String> keys = source.keys(); keys.hasNext(); ) {
        String key = keys.next();
        result.put(key, substitute(source.get(key), previous));
      }
      return result;
    }
    if (value instanceof JSONArray) {
      JSONArray source = (JSONArray) value, result = new JSONArray();
      for (int i = 0; i < source.length(); i++) result.put(substitute(source.get(i), previous));
      return result;
    }
    return value;
  }

  static String run(Object coroutine, JSONObject config) throws Exception {
    Object task = Host.field(coroutine, "$task"), session = Host.field(coroutine, "$session");
    Object context = Host.call(coroutine, "getContext");
    String taskId = Host.text(task, "a"), sid = Host.text(session, "a");
    long timestamp = XposedHelpers.getLongField(coroutine, "$messageTimestamp");
    String runId = taskId + ":" + sid + ":" + timestamp;
    JSONObject state = Store.get("scheduleRun", runId);
    if (!state.has("config")) {
      Json.put(state, "config", TaskPromptScope.snapshot(config));
      Json.put(state, "outputs", new JSONArray());
      Json.put(state, "taskId", taskId);
      Store.save("scheduleRun", runId, state);
    }
    JSONArray steps = state.getJSONObject("config").getJSONArray("steps");
    JSONArray outputs = state.getJSONArray("outputs");
    for (int i = outputs.length(); i < steps.length(); i++) {
      Object job =
          Host.call(
              context,
              "get",
              XposedHelpers.getStaticObjectField(Host.cls("kotlinx.coroutines.o0"), "a"));
      if (job != null && (Boolean) Host.call(job, "isCancelled")) throw new CancellationException();
      JSONObject step = steps.getJSONObject(i);
      String previous = outputs.length() == 0 ? "" : outputs.getString(outputs.length() - 1);
      String text = step.optString("text").replace("${previous}", previous);
      String eventId = UUID.randomUUID().toString();
      long start = System.currentTimeMillis();
      JSONObject event =
          Json.o(
              "taskId",
              taskId,
              "runId",
              runId,
              "step",
              i,
              "type",
              step.getString("type"),
              "status",
              "running");
      Store.event(eventId, sid, "scheduler.step", start, event);
      try {
        String output;
        switch (step.getString("type")) {
          case "agent":
            {
              JSONObject snapshot = step.optJSONObject("promptSnapshot");
              JSONObject modelSnapshot = step.optJSONObject("modelSnapshot");
              JSONArray toolSnapshot = step.optJSONArray("toolSnapshot");
              try (TaskPromptScope scope =
                      snapshot == null ? null : new TaskPromptScope(sid, snapshot);
                  TaskModelScope modelScope =
                      modelSnapshot == null
                          ? null
                          : new TaskModelScope(sid, session, modelSnapshot);
                  TaskToolScope toolScope =
                      toolSnapshot == null ? null : new TaskToolScope(session, toolSnapshot)) {
                Object request =
                    Host.awaitIn(
                        context,
                        Host.cls(EXECUTOR),
                        "a",
                        Host.field(coroutine, "this$0"),
                        session,
                        task,
                        text);
                output =
                    String.valueOf(
                        Host.awaitIn(
                            context,
                            session,
                            "t",
                            request,
                            XposedHelpers.getIntField(
                                Host.field(coroutine, "$retryCount"), "element"),
                            timestamp,
                            null));
              }
              Json.put(event, "promptPreset", step.optString("promptPreset"));
              Json.put(event, "promptSnapshot", snapshot);
              if (modelSnapshot != null) {
                Json.put(event, "modelEndpoint", modelSnapshot.optString("endpoint"));
                Json.put(event, "model", modelSnapshot.optString("model"));
              }
              break;
            }
          case "tool":
            {
              JSONArray toolSnapshot = step.optJSONArray("toolSnapshot");
              try (TaskToolScope toolScope =
                  toolSnapshot == null ? null : new TaskToolScope(session, toolSnapshot)) {
                output =
                    TaskToolScope.callIn(
                        context,
                        session,
                        taskId,
                        step.getString("tool"),
                        (JSONObject) substitute(step.getJSONObject("arguments"), previous));
              }
              break;
            }
          case "mcp":
            output =
                McpBridge.callIn(
                    context,
                    step.getString("serverId"),
                    step.getString("tool"),
                    (JSONObject) substitute(step.getJSONObject("arguments"), previous),
                    sid);
            break;
          case "tts":
            Host.awaitIn(
                context,
                Host.services.get("ttsPlayback"),
                "g",
                text,
                TtsPresets.nativeConfig(sid, step.optString("ttsPreset")),
                "xiaoxi-scheduler:" + taskId);
            output = text;
            break;
          case "notification":
            Host.call(
                Host.field(Host.field(coroutine, "this$0"), "f"),
                "c",
                task,
                true,
                text,
                sid,
                timestamp);
            output = text;
            break;
          default:
            throw new IllegalArgumentException("未知任务步骤：" + step.getString("type"));
        }
        outputs.put(output);
        Store.save("scheduleRun", runId, state);
        Json.put(event, "status", "completed");
        Json.put(event, "output", output);
      } catch (Exception error) {
        Json.put(event, "status", "error");
        Json.put(event, "error", error.toString());
        throw error;
      } finally {
        Json.put(event, "end", System.currentTimeMillis());
        Store.event(eventId, sid, "scheduler.step", start, event);
      }
    }
    return outputs.length() == 0 ? "" : outputs.getString(outputs.length() - 1);
  }
}
