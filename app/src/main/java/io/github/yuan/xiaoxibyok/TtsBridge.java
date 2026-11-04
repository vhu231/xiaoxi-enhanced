package io.github.yuan.xiaoxibyok;

import de.robv.android.xposed.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;

/** External synthesis replaces CloudTts's Flow while playback remains in the host queue. */
final class TtsBridge {
  static final ExecutorService workers = Executors.newCachedThreadPool();

  static Object flow(String text, JSONObject preset, String session) {
    return Proxy.newProxyInstance(
        Host.cl,
        new Class<?>[] {Host.cls("kotlinx.coroutines.flow.h")},
        (proxy, method, args) -> {
          if (method.getName().equals("toString")) return "XiaoxiExternalTtsFlow";
          if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
          if (method.getName().equals("equals")) return proxy == args[0];
          if (!method.getName().equals("b"))
            throw new UnsupportedOperationException(method.toString());
          Object collector = args[0], completion = args[1];
          workers.execute(() -> synthesize(text, preset, session, collector, completion));
          return XposedHelpers.callStaticMethod(
              Host.cls("kotlin.coroutines.intrinsics.IntrinsicsKt"), "getCOROUTINE_SUSPENDED");
        });
  }

  static void synthesize(
      String text, JSONObject preset, String session, Object collector, Object completion) {
    String id = UUID.randomUUID().toString();
    long started = System.currentTimeMillis();
    JSONObject record =
        Json.o(
            "id",
            id,
            "text",
            text,
            "provider",
            preset.optString("provider"),
            "status",
            "generating",
            "start",
            started,
            "voiceId",
            preset.optString("voiceId"),
            "model",
            preset.optString("model", "v4.0"));
    Object outcome = Host.unit();
    Object cancellationHandle = null;
    java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean();
    try (VocuApi api =
            new VocuApi(
                preset.optString("endpoint", "https://v1.vocu.studio/api"),
                preset.optString("key"));
        CustomTts custom = new CustomTts()) {
      Object context = Host.call(completion, "getContext");
      Object job =
          Host.call(
              context,
              "get",
              XposedHelpers.getStaticObjectField(Host.cls("kotlinx.coroutines.o0"), "a"));
      if (job != null)
        cancellationHandle =
            Host.call(
                job,
                "u",
                true,
                true,
                Host.function(
                    "kotlin.jvm.functions.Function1",
                    args -> {
                      if (args[0] != null) {
                        cancelled.set(true);
                        api.close();
                        custom.close();
                      }
                      return Host.unit();
                    }));
      if (preset.optString("provider").equals("custom")) {
        JSONObject config = preset.getJSONObject("custom");
        Json.put(record, "model", config.optString("model"));
        Json.put(record, "voiceId", config.optString("voiceId"));
      }
      save(id, session, record);
      long[] samples = {0};
      java.io.File audioDirectory = new java.io.File(Host.app().getFilesDir(), "xiaoxi_audio");
      audioDirectory.mkdirs();
      java.io.File wav = new java.io.File(audioDirectory, id + ".wav");
      float speed = (float) preset.optDouble("speed", 1);
      float pitch = (float) preset.optDouble("pitch", 1);
      boolean transformed = speed != 1f || pitch != 1f;
      java.io.File processed = new java.io.File(audioDirectory, id + "-processed.wav");
      long[] playedSamples = {0};
      Json.put(record, "speed", speed);
      Json.put(record, "pitch", pitch);
      try (WavFile audio = new WavFile(wav);
          WavFile playback = transformed ? new WavFile(processed) : null) {
        AudioTransform transform =
            new AudioTransform(
                speed,
                pitch,
                (pcm, rate) -> {
                  if (cancelled.get()) throw new CancellationException();
                  playedSamples[0] += pcm.length / 2;
                  if (playback != null) playback.write(pcm, rate);
                  Host.awaitIn(context, collector, "emit", Host.make("E8.t", pcm, rate));
                });
        AudioDecoder.Sink sink =
            (pcm, rate) -> {
              if (samples[0] == 0) {
                Json.put(record, "firstAudioMs", System.currentTimeMillis() - started);
                Json.put(record, "status", "streaming");
              }
              samples[0] += pcm.length / 2;
              Json.put(record, "sampleRate", rate);
              Json.put(record, "decodedSamples", samples[0]);
              audio.write(pcm, rate);
              transform.accept(pcm, rate);
            };
        if (preset.optString("provider").equals("custom")) {
          JSONObject result = custom.synthesize(text, preset.getJSONObject("custom"), sink);
          Json.put(record, "result", result);
        } else {
          JSONObject content =
              Json.o(
                  "type",
                  "text",
                  "text",
                  text,
                  "voiceId",
                  preset.optString("voiceId"),
                  "model",
                  preset.optString("model", "v4.0"));
          if (!preset.optString("promptId").isEmpty())
            Json.put(content, "promptId", preset.optString("promptId"));
          if (preset.optString("model", "v4.0").startsWith("v4"))
            Json.put(content, "reference_mode", preset.optString("reference_mode", "balanced"));
          JSONObject response = api.simple(content);
          JSONObject data = response.getJSONObject("data");
          Json.put(record, "requestId", response.optString("requestId"));
          Json.put(record, "taskId", data.optString("id"));
          Json.put(record, "result", data);
          Json.put(record, "status", "decoding");
          save(id, session, record);
          String url = data.optString("streamUrl");
          if (url.isEmpty()) url = data.optString("audio");
          if (url.isEmpty())
            throw new IllegalStateException(
                "Vocu 未返回 audio 或 streamUrl，任务：" + data.optString("id"));
          AudioDecoder.decode(url, () -> api.cancelled, sink);
        }
        transform.finish();
        if (samples[0] == 0) throw new IllegalStateException("TTS 未返回音频数据");
      }
      Json.put(record, "decodedWav", wav.getAbsolutePath());
      Json.put(
          record,
          "processedWav",
          transformed ? processed.getAbsolutePath() : wav.getAbsolutePath());
      Json.put(
          record,
          "playbackDurationMs",
          playedSamples[0] * 1000L / record.optInt("sampleRate", 24000));
      Json.put(record, "durationMs", samples[0] * 1000L / record.optInt("sampleRate", 24000));
      Json.put(record, "status", "synthesized");
    } catch (Throwable error) {
      if (cancelled.get() && !(error instanceof CancellationException)) {
        CancellationException stopped = new CancellationException("原生播放已停止");
        stopped.initCause(error);
        error = stopped;
      }
      Json.put(record, "status", error instanceof CancellationException ? "cancelled" : "error");
      Json.put(record, "error", error.toString());
      outcome = XposedHelpers.callStaticMethod(Host.cls("kotlin.ResultKt"), "createFailure", error);
    } finally {
      if (cancellationHandle != null) Host.call(cancellationHandle, "dispose");
      Json.put(record, "end", System.currentTimeMillis());
      save(id, session, record);
    }
    Host.call(completion, "resumeWith", outcome);
  }

  static void save(String id, String session, JSONObject record) {
    Store.save("audio", id, record);
    Store.event(id, session, "tts.synthesis", record.optLong("start"), record);
  }

  static void install() {
    Host.capture("com.flyme.agentos.voice.impl.tts.t", "ttsPlayback");
    XposedBridge.hookAllMethods(
        Host.cls("com.flyme.agentos.voice.impl.tts.b"),
        "a",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            Host.services.put("ttsConfig", p.args[0]);
            JSONObject preset =
                (JSONObject)
                    XposedHelpers.getAdditionalInstanceField(p.args[0], "xiaoxi.ttsPreset");
            if (preset == null) preset = TtsPresets.resolve("", "");
            String provider = preset.optString("provider", "native");
            if (!provider.equals("vocu") && !provider.equals("custom")) return;
            Object session =
                XposedHelpers.getAdditionalInstanceField(p.args[0], "xiaoxi.ttsSession");
            p.setResult(
                flow((String) p.args[1], preset, session == null ? "" : session.toString()));
          }
        });
  }
}
