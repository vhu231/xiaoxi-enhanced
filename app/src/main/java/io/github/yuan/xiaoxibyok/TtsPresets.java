package io.github.yuan.xiaoxibyok;

import de.robv.android.xposed.XposedHelpers;
import org.json.JSONObject;

/** Resolve and freeze scoped TTS settings before a queued item is synthesized. */
final class TtsPresets {
  static JSONObject resolve(String session, String presetId) {
    String selected = presetId;
    if (selected.isEmpty() && !session.isEmpty())
      selected = Store.get("ttsSession", session).optString("presetId");
    JSONObject preset =
        selected.isEmpty() ? Store.get("tts", "default") : Store.get("ttsPreset", selected);
    if (!selected.isEmpty() && preset.length() == 0)
      throw new IllegalArgumentException("语音预设不存在：" + selected);
    if (preset.optString("provider").equals("custom") && !preset.has("custom"))
      Json.put(preset, "custom", Store.get("ttsCustom", "default"));
    return preset;
  }

  static Object nativeConfig(String session, String presetId) {
    JSONObject preset = resolve(session, presetId);
    boolean nativeProvider = preset.optString("provider", "native").equals("native");
    Object current =
        nativeProvider && preset.optString("voiceId").isEmpty()
            ? Host.services.get("ttsConfig")
            : null;
    Object config;
    if (current != null) {
      config =
          Host.make(
              "E8.a0",
              Host.text(current, "a"),
              preset.optString("language", Host.text(current, "d")),
              nativeProvider
                  ? (float) preset.optDouble("speed", XposedHelpers.getFloatField(current, "b"))
                  : 1f,
              nativeProvider
                  ? (float) preset.optDouble("pitch", XposedHelpers.getFloatField(current, "c"))
                  : 1f);
    } else {
      config =
          Host.make(
              "E8.a0",
              preset.optString("voiceId", "default"),
              preset.optString("language", "auto"),
              nativeProvider ? (float) preset.optDouble("speed", 1) : 1f,
              nativeProvider ? (float) preset.optDouble("pitch", 1) : 1f);
    }
    XposedHelpers.setAdditionalInstanceField(config, "xiaoxi.ttsPreset", preset);
    XposedHelpers.setAdditionalInstanceField(config, "xiaoxi.ttsSession", session);
    return config;
  }
}
