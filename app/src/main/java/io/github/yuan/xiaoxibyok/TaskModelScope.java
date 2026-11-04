package io.github.yuan.xiaoxibyok;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

/** Task-local chat client and budget snapshots, isolated from the saved chat profile. */
final class TaskModelScope implements AutoCloseable {
  static final Map<String, Association> associations = new ConcurrentHashMap<>();
  static final Map<Object, Profile> configurations =
      Collections.synchronizedMap(new WeakHashMap<>());
  static final Map<Object, Profile> budgets = Collections.synchronizedMap(new WeakHashMap<>());
  static final Set<String> active = ConcurrentHashMap.newKeySet();

  final String session;
  final Object loop, originalClient, binding, budget;
  final String associationId;
  final Profile profile;

  static final class Association {
    final WeakReference<Object> owner;
    final Object snapshot;

    Association(Object owner, Object snapshot) {
      this.owner = new WeakReference<>(owner);
      this.snapshot = snapshot;
    }
  }

  static JSONObject snapshot(JSONObject configuration) throws JSONException {
    JSONObject copy = new JSONObject(configuration.toString());
    JSONArray steps = copy.getJSONArray("steps");
    for (int i = 0; i < steps.length(); i++) {
      JSONObject step = steps.getJSONObject(i);
      if (!step.optString("type").equals("agent")) continue;
      JSONObject model = step.optJSONObject("model");
      if (model == null) model = copy.optJSONObject("model");
      if (model != null) step.put("modelSnapshot", new JSONObject(model.toString()));
    }
    return copy;
  }

  static Profile configuration(Object clientConfig) {
    return configurations.get(Host.field(clientConfig, "d"));
  }

  static Profile budget(Object planner) {
    return budgets.get(planner);
  }

  TaskModelScope(String session, Object sessionObject, JSONObject snapshot) {
    this.session = session;
    profile = new Profile(snapshot, "");
    if (!profile.active() || profile.model.trim().isEmpty())
      throw new IllegalArgumentException("定时任务模型必须配置 endpoint 和 model");
    if (!active.add(session)) throw new IllegalStateException("该任务会话已有执行中的模型配置");
    String id = null;
    Object capabilities = null;
    try {
      Object taskLoop = Host.field(sessionObject, "d");
      Object original = Host.field(taskLoop, "a");
      Object originalBinding = Host.field(original, "a");
      Object owner = Host.field(originalBinding, "e");
      Object function = Host.field(originalBinding, "a");
      Object originalSnapshot = Host.call(originalBinding, "c");
      Object source = Host.field(originalSnapshot, "a");
      String originalEndpoint = Profile.normalize(Host.text(source, "d"));
      String key =
          snapshot.has("apiKey")
              ? snapshot.optString("apiKey")
              : originalEndpoint.equals(profile.endpoint) ? Host.text(source, "f") : "";
      Object originalCapabilities = Host.field(originalSnapshot, "f");
      Object window =
          profile.window > 0
              ? Integer.valueOf(profile.window)
              : originalCapabilities == null ? null : Host.field(originalCapabilities, "a");
      capabilities = Host.make("Y6.b0", window, Host.field(source, "h"), Host.field(source, "i"));
      Object route =
          Host.make(
              "C6.L",
              "xiaoxi-scheduled-" + UUID.randomUUID(),
              Host.field(source, "b"),
              snapshot.optString("provider", "openai"),
              profile.endpoint,
              profile.model,
              key,
              window,
              Host.field(source, "h"),
              Host.field(source, "i"));
      Object snapshotObject =
          Host.make(
              "W6.f",
              route,
              Host.field(originalSnapshot, "b"),
              Host.field(originalSnapshot, "c"),
              Host.field(originalSnapshot, "d"),
              Host.field(originalSnapshot, "e"),
              capabilities);
      id = UUID.randomUUID().toString();
      associations.put(id, new Association(owner, snapshotObject));
      Object taskBinding = Host.make("com.flyme.agentos.llm.impl.routing.l", owner, function, id);
      configurations.put(capabilities, profile);
      Object taskClient = Host.call(taskBinding, "b");
      Object taskBudget = Host.field(Host.field(taskLoop, "e"), "b");
      budgets.put(taskBudget, profile);
      Reflect.setObjectField(taskLoop, "a", taskClient);
      loop = taskLoop;
      originalClient = original;
      binding = taskBinding;
      budget = taskBudget;
      associationId = id;
    } catch (RuntimeException | Error error) {
      if (id != null) associations.remove(id);
      if (capabilities != null) configurations.remove(capabilities);
      active.remove(session);
      throw error;
    }
  }

  static void install() {
    Xposed.hookAllMethods(
        Host.cls("com.flyme.agentos.llm.impl.routing.o"),
        "c",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (p.args.length < 2 || !(p.args[1] instanceof String)) return;
            Association association = associations.get(p.args[1]);
            if (association == null || association.owner.get() != p.thisObject) return;
            p.setResult(association.snapshot);
          }
        });
  }

  public void close() {
    Reflect.setObjectField(loop, "a", originalClient);
    budgets.remove(budget);
    configurations.remove(Host.field(Host.call(binding, "c"), "f"));
    associations.remove(associationId);
    active.remove(session);
    try {
      Host.call(binding, "invalidate");
    } catch (Throwable error) {
      Xposed.log(error);
    }
  }
}
