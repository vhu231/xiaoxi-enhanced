package io.github.yuan.xiaoxibyok;

import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

/**
 * Task-local preset snapshots are visible across coroutine threads without changing saved chat
 * settings.
 */
final class TaskPromptScope implements AutoCloseable {
  static final ConcurrentHashMap<String, JSONObject> active = new ConcurrentHashMap<>();
  final String session;
  final JSONObject preset;

  TaskPromptScope(String session, JSONObject preset) {
    this.session = session;
    this.preset = preset;
    if (active.putIfAbsent(session, preset) != null)
      throw new IllegalStateException("该任务会话已有执行中的提示词配置");
  }

  static JSONObject current(String session) {
    return session == null ? null : active.get(session);
  }

  static JSONObject snapshot(JSONObject configuration) throws JSONException {
    JSONObject copy = TaskToolScope.snapshot(TaskModelScope.snapshot(configuration));
    JSONArray steps = copy.getJSONArray("steps");
    for (int i = 0; i < steps.length(); i++) {
      JSONObject step = steps.getJSONObject(i);
      if (!step.optString("type").equals("agent")) continue;
      String id = step.optString("promptPreset", copy.optString("promptPreset"));
      if (id.isEmpty()) continue;
      JSONObject preset = Store.get("preset", id);
      if (preset.length() == 0) throw new IllegalArgumentException("提示词预设不存在：" + id);
      step.put("promptSnapshot", preset);
      step.put("promptPreset", id);
    }
    return copy;
  }

  public void close() {
    active.remove(session, preset);
  }
}
