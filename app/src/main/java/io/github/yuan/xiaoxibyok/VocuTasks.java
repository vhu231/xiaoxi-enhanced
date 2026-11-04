package io.github.yuan.xiaoxibyok;

import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;

final class VocuTasks {
  static final ScheduledExecutorService poller = Executors.newScheduledThreadPool(2);
  static final java.util.Set<String> polling = ConcurrentHashMap.newKeySet();
  static final java.util.concurrent.atomic.AtomicBoolean restored =
      new java.util.concurrent.atomic.AtomicBoolean();

  static void resumeOnce() {
    if (restored.compareAndSet(false, true)) poller.execute(VocuTasks::resume);
  }

  static JSONObject submit(JSONObject config, JSONArray contents, String session) throws Exception {
    String id = UUID.randomUUID().toString();
    JSONObject response;
    try (VocuApi api = TtsUi.api(config)) {
      response = api.generate(contents);
    }
    JSONObject task = response.getJSONObject("data");
    JSONObject record =
        Json.o(
            "id",
            id,
            "provider",
            "vocu",
            "mode",
            "async",
            "session",
            session,
            "start",
            System.currentTimeMillis(),
            "taskId",
            task.getString("id"),
            "requestId",
            response.optString("requestId"),
            "status",
            task.optString("status", "pending"),
            "contents",
            contents,
            "result",
            task,
            "text",
            "长文 / 多角色任务（" + contents.length() + " 段）");
    Store.save("ttsTaskConfig", id, config);
    TtsBridge.save(id, session, record);
    start(id);
    return record;
  }

  static void start(String id) {
    if (polling.add(id)) poller.schedule(() -> poll(id, 1500), 1500, TimeUnit.MILLISECONDS);
  }

  static void poll(String id, long delay) {
    JSONObject record = Store.get("audio", id);
    if (record.optBoolean("trackingStopped")) {
      polling.remove(id);
      return;
    }
    boolean again = false;
    try (VocuApi api = TtsUi.api(Store.get("ttsTaskConfig", id))) {
      JSONObject response = api.task(record.getString("taskId"));
      JSONObject task = response.getJSONObject("data");
      String status = task.getString("status");
      Json.put(record, "result", task);
      Json.put(record, "status", status);
      Json.put(record, "requestId", response.optString("requestId"));
      record.remove("pollError");
      again = status.equals("pending") || status.equals("processing");
      if (!again) Json.put(record, "end", System.currentTimeMillis());
    } catch (Exception error) {
      Json.put(record, "pollError", error.toString());
      // A failed status query is not evidence that the remote generation failed.
      Json.put(record, "trackingStopped", true);
    }
    if (Store.get("audio", id).optBoolean("trackingStopped")) {
      Json.put(record, "trackingStopped", true);
      again = false;
    }
    TtsBridge.save(id, record.optString("session"), record);
    if (again)
      poller.schedule(() -> poll(id, Math.min(6000, delay * 3 / 2)), delay, TimeUnit.MILLISECONDS);
    else polling.remove(id);
  }

  static void resume() {
    JSONArray records = Store.list("audio");
    for (int i = 0; i < records.length(); i++) {
      JSONObject record = records.optJSONObject(i);
      if (record.optString("mode").equals("async")
          && !record.optBoolean("trackingStopped")
          && (record.optString("status").equals("pending")
              || record.optString("status").equals("processing"))) start(record.optString("id"));
    }
  }
}
