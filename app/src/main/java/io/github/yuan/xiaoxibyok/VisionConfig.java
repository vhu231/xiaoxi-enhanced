package io.github.yuan.xiaoxibyok;

import android.content.SharedPreferences;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.*;

/** Image task configuration; creates independent host snapshots instead of editing chat objects. */
final class VisionConfig {
  final boolean enabled, followChat;
  final String endpoint, model, key, detail, prompt;
  final int output, maxDimension, quality;

  VisionConfig() {
    SharedPreferences p = MainHook.prefs();
    enabled = p.getBoolean("vision.enabled", false);
    followChat = p.getBoolean("vision.followChat", true);
    endpoint = Profile.normalize(p.getString("vision.endpoint", ""));
    model = p.getString("vision.model", "");
    key = p.getString("vision.key", "");
    detail = p.getString("vision.detail", "");
    prompt = p.getString("vision.prompt", "");
    output = p.getInt("vision.output", 0);
    maxDimension = p.getInt("vision.maxDimension", 0);
    quality = p.getInt("vision.quality", 90);
  }

  Object snapshot(Object chat) {
    Object source = Host.field(chat, "a");
    Profile profile = MainHook.profile();
    String url = followChat ? Host.text(source, "d") : endpoint;
    String selected =
        followChat ? (profile.active() ? profile.model : Host.text(source, "e")) : model;
    String apiKey = followChat ? Host.text(source, "f") : key;
    Set<Object> modalities = new LinkedHashSet<>();
    Class<?> input = Host.cls("com.flyme.agentos.core.InputModality");
    modalities.add(Reflect.getStaticObjectField(input, "TEXT"));
    modalities.add(Reflect.getStaticObjectField(input, "IMAGE"));
    Object originalCapabilities = Host.field(chat, "f");
    Object limits = Host.field(originalCapabilities, "c");
    Object window = Host.field(originalCapabilities, "a");
    Object capabilities = Host.make("Y6.b0", window, modalities, limits);
    Object route =
        Host.make(
            "C6.L",
            "xiaoxi-vision-byok",
            Host.field(source, "b"),
            "openai",
            url,
            selected,
            apiKey,
            window,
            modalities,
            limits);
    return Host.make(
        "W6.f",
        route,
        Host.field(chat, "b"),
        Host.field(chat, "c"),
        Host.field(chat, "d"),
        Host.field(chat, "e"),
        capabilities);
  }

  void rewrite(JSONObject body, Object config) throws JSONException {
    body.put("model", Host.text(config, "b"));
    if (output > 0) {
      body.remove("max_completion_tokens");
      body.put("max_tokens", output);
    }
    JSONArray messages = body.getJSONArray("messages");
    if (!prompt.isEmpty()) {
      JSONArray updated = new JSONArray();
      updated.put(Json.o("role", "system", "content", prompt));
      for (int i = 0; i < messages.length(); i++) updated.put(messages.get(i));
      body.put("messages", updated);
    }
    for (int i = 0; i < messages.length(); i++) {
      JSONArray content = messages.getJSONObject(i).optJSONArray("content");
      if (content == null) continue;
      for (int j = 0; j < content.length(); j++) {
        JSONObject image = content.getJSONObject(j).optJSONObject("image_url");
        if (image == null) continue;
        if (!detail.isEmpty()) image.put("detail", detail);
        if (maxDimension > 0) image.put("url", resize(image.getString("url")));
      }
    }
  }

  String resize(String url) {
    if (!url.startsWith("data:")) return url;
    int comma = url.indexOf(',');
    byte[] bytes =
        android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT);
    android.graphics.Bitmap original =
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    if (original == null) throw new IllegalArgumentException("无法解码图片输入");
    float scale =
        Math.min(1f, (float) maxDimension / Math.max(original.getWidth(), original.getHeight()));
    android.graphics.Bitmap resized =
        android.graphics.Bitmap.createScaledBitmap(
            original,
            Math.max(1, Math.round(original.getWidth() * scale)),
            Math.max(1, Math.round(original.getHeight() * scale)),
            true);
    java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
    resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, data);
    if (resized != original) resized.recycle();
    original.recycle();
    return "data:image/jpeg;base64,"
        + android.util.Base64.encodeToString(data.toByteArray(), android.util.Base64.NO_WRAP);
  }
}
