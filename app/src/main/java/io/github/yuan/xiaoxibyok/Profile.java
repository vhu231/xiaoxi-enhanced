package io.github.yuan.xiaoxibyok;

import android.content.SharedPreferences;
import android.net.Uri;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONObject;

final class Profile {
  final String endpoint, prefix, model, format, effort, extra, outputField;
  final int window, output, budget;
  final boolean enabled;

  static String normalize(String value) {
    return value.trim().replaceAll("/+$", "");
  }

  Profile(String url, String nativeModel) {
    this(url, nativeModel, true);
  }

  Profile(String url, String nativeModel, boolean useConfiguredModel) {
    endpoint = normalize(url);
    prefix = "profile:" + endpoint + ":";
    SharedPreferences p = MainHook.prefs();
    boolean deepseek = "api.deepseek.com".equalsIgnoreCase(Uri.parse(endpoint).getHost());
    enabled = p.getBoolean(prefix + "enabled", true);
    String defaultModel =
        deepseek
            ? (nativeModel.toLowerCase(Locale.ROOT).contains("pro")
                ? "deepseek-v4-pro"
                : "deepseek-flash")
            : nativeModel;
    model = useConfiguredModel ? p.getString(prefix + "model", defaultModel) : nativeModel;
    format = p.getString(prefix + "format", "auto");
    effort = p.getString(prefix + "effort", deepseek ? "high" : "default");
    window = p.getInt(prefix + "window", deepseek ? 1000000 : 0);
    output = p.getInt(prefix + "output", deepseek ? 16384 : 0);
    budget = p.getInt(prefix + "budget", 0);
    extra = p.getString(prefix + "extra", "");
    outputField = p.getString(prefix + "outputField", "max_tokens");
  }

  Profile(JSONObject snapshot, String nativeModel) {
    endpoint = normalize(snapshot.optString("endpoint"));
    prefix = "";
    boolean deepseek = "api.deepseek.com".equalsIgnoreCase(Uri.parse(endpoint).getHost());
    enabled = snapshot.optBoolean("enabled", true);
    model = snapshot.optString("model", nativeModel);
    format = snapshot.optString("format", "auto");
    effort = snapshot.optString("effort", deepseek ? "high" : "default");
    window = snapshot.optInt("window", deepseek ? 1000000 : 0);
    output = snapshot.optInt("output", deepseek ? 16384 : 0);
    budget = snapshot.optInt("budget", 0);
    extra = snapshot.optString("extra", "");
    outputField = snapshot.optString("outputField", "max_tokens");
  }

  boolean active() {
    return enabled && !endpoint.isEmpty();
  }

  boolean matches(String url) {
    return active() && endpoint.equals(normalize(url));
  }

  String protocol() {
    if (!format.equals("auto")) return format;
    String host = Uri.parse(endpoint).getHost();
    String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
    String m = model.toLowerCase(Locale.ROOT);
    if (h.equals("api.deepseek.com") || m.startsWith("deepseek")) return "deepseek";
    if (h.contains("dashscope") || m.contains("qwen") || m.contains("qwq"))
      return "enable_thinking";
    if (h.contains("moonshot")
        || h.contains("volces.com")
        || m.startsWith("kimi")
        || m.startsWith("doubao")) return "thinking_type";
    if (h.equals("api.openai.com")
        || h.contains("openrouter.ai")
        || m.startsWith("gpt-")
        || m.matches("o[134].*")) return "reasoning_effort";
    return "none";
  }

  void rewrite(JSONObject body) throws Exception {
    rewrite(body, null);
  }

  void rewrite(JSONObject body, Reasoning.Selection reasoning) throws Exception {
    if (!model.trim().isEmpty()) body.put("model", model.trim());
    if (output > 0) {
      body.remove("max_tokens");
      body.remove("max_completion_tokens");
      body.put(outputField, output);
    }
    for (String key :
        new String[] {
          "tier_id",
          "caller",
          "session_id",
          "chat_turn",
          "loop_turn",
          "catalog_version",
          "catalog_md5",
          "request_id",
          "rollout_id"
        }) body.remove(key);
    if (!extra.trim().isEmpty()) {
      JSONObject overrides = new JSONObject(extra);
      for (Iterator<String> it = overrides.keys(); it.hasNext(); ) {
        String key = it.next();
        if (overrides.isNull(key)) body.remove(key);
        else body.put(key, overrides.get(key));
      }
    }
    Reasoning.apply(body, this, reasoning);
  }
}
