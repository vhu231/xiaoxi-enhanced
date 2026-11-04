package io.github.yuan.xiaoxibyok;

import java.util.*;
import org.json.*;

/** Harness-style reasoning presets, per-session selection and per-turn snapshots. */
final class Reasoning {
  static final Map<String, Selection> turns =
      Collections.synchronizedMap(new LinkedHashMap<String, Selection>());
  static final Set<String> RESERVED = new HashSet<>(Arrays.asList("model", "messages", "tools"));

  static final class Selection {
    final String key, levelId, protocol, source;
    final JSONObject level;
    final long capturedAt = System.currentTimeMillis();

    Selection(Profile profile, JSONObject configuration, String levelId, String source) {
      key = key(profile.endpoint, profile.model);
      protocol = profile.protocol();
      JSONObject selected = find(configuration, levelId);
      if (selected == null) {
        levelId = configuration.optString("defaultLevel", "default");
        selected = find(configuration, levelId);
        source = "model-default";
      }
      if (selected == null) {
        levelId = "default";
        selected = defaultLevel();
        source = "service-default";
      }
      this.levelId = levelId;
      this.source = source;
      level = selected;
    }

    JSONObject json() {
      return Json.o(
          "profile",
          key,
          "levelId",
          levelId,
          "label",
          level.optString("name", levelId),
          "protocol",
          protocol,
          "source",
          source,
          "parameters",
          level.optJSONObject("parameters") == null
              ? JSONObject.NULL
              : level.optJSONObject("parameters"),
          "capturedAt",
          capturedAt);
    }
  }

  static JSONObject defaultLevel() {
    return Json.o("id", "default", "name", "Default", "enabled", true);
  }

  static JSONObject preset(String id, int budget) {
    String name = id.equals("on") ? "开启思考" : id.equals("off") ? "关闭思考" : id;
    return Json.o("id", id, "name", name, "enabled", true, "value", id, "budget", budget);
  }

  static String[] levels(Profile profile) {
    switch (profile.protocol()) {
      case "deepseek":
        return new String[] {"low", "high", "max"};
      case "reasoning_effort":
        return new String[] {"low", "medium", "high"};
      case "thinking_type":
      case "enable_thinking":
        return new String[] {"on", "off"};
      default:
        return new String[0];
    }
  }

  static boolean supports(Profile profile) {
    return levels(profile).length > 0;
  }

  static String[] optionIds(Profile profile) {
    List<String> ids = new ArrayList<>();
    ids.add("default");
    Collections.addAll(ids, levels(profile));
    return ids.toArray(new String[0]);
  }

  static String key(String endpoint, String model) {
    return Profile.normalize(endpoint) + "\n" + model.trim();
  }

  static JSONObject find(JSONObject configuration, String id) {
    if (id.equals("default")) return defaultLevel();
    JSONArray levels = configuration.optJSONArray("levels");
    for (int i = 0; levels != null && i < levels.length(); i++) {
      JSONObject level = levels.optJSONObject(i);
      if (level.optString("id").equals(id) && level.optBoolean("enabled", true)) return level;
    }
    return null;
  }

  static JSONObject configuration(Profile profile) {
    JSONArray levels = new JSONArray();
    int budget = profile.protocol().equals("enable_thinking") ? profile.budget : 0;
    for (String id : levels(profile)) levels.put(preset(id, budget));
    return Json.o("levels", levels, "defaultLevel", "default");
  }

  static JSONObject configuration(String endpoint, String model) {
    return configuration(new Profile(endpoint, model, false));
  }

  static JSONArray options(String endpoint, String model) {
    JSONObject configuration = configuration(endpoint, model);
    JSONArray result = new JSONArray().put(defaultLevel());
    JSONArray levels = configuration.optJSONArray("levels");
    for (int i = 0; levels != null && i < levels.length(); i++) result.put(levels.opt(i));
    return result;
  }

  static String selected(String session, String endpoint, String model) {
    Profile profile = new Profile(endpoint, model, false);
    JSONObject configuration = configuration(profile);
    String id =
        Store.get("reasoningSession", session)
            .optString(key(endpoint, model), configuration.optString("defaultLevel", "default"));
    if (find(configuration, id) != null) return id;
    return "default";
  }

  static Selection implicit(Profile profile) {
    JSONObject configuration = configuration(profile);
    String id = find(configuration, profile.effort) == null ? "default" : profile.effort;
    return new Selection(
        profile, configuration, id, id.equals("default") ? "model-default" : "task-profile");
  }

  static void select(String session, String endpoint, String model, String levelId) {
    Profile profile = new Profile(endpoint, model, false);
    JSONObject configuration = configuration(profile);
    if (find(configuration, levelId) == null)
      throw new IllegalArgumentException("思考等级不存在：" + levelId);
    String key = key(endpoint, model);
    JSONObject selections = Store.get("reasoningSession", session);
    Store.save("reasoningSession", session, Json.put(selections, key, levelId));
  }

  static Selection capture(Object request, Profile profile) {
    String session = Host.text(request, "g");
    Object nativeTurn = Host.field(request, "h");
    int turn = nativeTurn == null ? 0 : ((Number) nativeTurn).intValue();
    String turnKey = session + "\n" + turn;
    synchronized (turns) {
      Selection existing = turns.get(turnKey);
      if (existing != null) return existing;
      JSONObject configuration = configuration(profile);
      String selected =
          Store.get("reasoningSession", session)
              .optString(
                  key(profile.endpoint, profile.model),
                  configuration.optString("defaultLevel", "default"));
      String source =
          Store.get("reasoningSession", session).has(key(profile.endpoint, profile.model))
              ? "session"
              : "model-default";
      Selection selection = new Selection(profile, configuration, selected, source);
      turns.put(turnKey, selection);
      if (turns.size() > 128) turns.remove(turns.keySet().iterator().next());
      return selection;
    }
  }

  static void remove(JSONObject body, String path) {
    String[] parts = path.split("\\.");
    if (parts.length == 0 || RESERVED.contains(parts[0])) return;
    JSONObject current = body;
    for (int i = 0; i < parts.length - 1; i++) {
      Object next = current.opt(parts[i]);
      if (!(next instanceof JSONObject)) return;
      current = (JSONObject) next;
    }
    current.remove(parts[parts.length - 1]);
  }

  static void clear(JSONObject body) {
    for (String key :
        new String[] {
          "thinking", "enable_thinking", "thinking_budget", "reasoning_effort", "reasoning"
        }) body.remove(key);
  }

  static void merge(JSONObject body, JSONObject parameters) {
    for (Iterator<String> keys = parameters.keys(); keys.hasNext(); ) {
      String key = keys.next();
      if (RESERVED.contains(key)) continue;
      Object value = parameters.opt(key);
      Object existing = body.opt(key);
      if (value instanceof JSONObject && existing instanceof JSONObject)
        merge((JSONObject) existing, (JSONObject) value);
      else Json.put(body, key, value);
    }
  }

  static void apply(JSONObject body, Profile profile, Selection selection) throws JSONException {
    String protocol = profile.protocol();
    if (protocol.equals("native")) return;
    clear(body);
    if (selection == null) {
      JSONObject configuration = configuration(profile);
      selection =
          new Selection(
              profile,
              configuration,
              configuration.optString("defaultLevel", "default"),
              "model-default");
    }
    if (selection.levelId.equals("default")) return;
    JSONObject level = selection.level;
    String value = level.optString("value");
    boolean off =
        selection.levelId.equals("off")
            || selection.levelId.equals("none")
            || value.equals("off")
            || value.equals("none");
    int budget = level.optInt("budget", 0);
    if (protocol.equals("deepseek")) {
      body.put("thinking", new JSONObject().put("type", off ? "disabled" : "enabled"));
      if (!off && !value.isEmpty() && !value.equals("default")) body.put("reasoning_effort", value);
    } else if (protocol.equals("thinking_type")) {
      body.put("thinking", new JSONObject().put("type", off ? "disabled" : "enabled"));
    } else if (protocol.equals("enable_thinking")) {
      body.put("enable_thinking", !off);
      if (!off && budget > 0) body.put("thinking_budget", budget);
    } else if (protocol.equals("reasoning_effort")) {
      if (!value.isEmpty()) body.put("reasoning_effort", off ? "none" : value);
    }
    JSONArray remove = level.optJSONArray("remove");
    for (int i = 0; remove != null && i < remove.length(); i++) remove(body, remove.optString(i));
    JSONObject parameters = level.optJSONObject("parameters");
    if (parameters != null) merge(body, parameters);
  }
}
