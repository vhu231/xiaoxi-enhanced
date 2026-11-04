package io.github.yuan.xiaoxibyok;

import java.io.File;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** File preparation for the native export picker; performs no UI or playback operations. */
final class AudioExport {
  static AudioDownload.Result original(JSONObject record) throws Exception {
    JSONObject result = record.optJSONObject("result");
    if (result != null) {
      String local = result.optString("originalFile");
      if (!local.isEmpty()) return AudioDownload.inspect(new File(local), false);
      JSONObject metadata = result.optJSONObject("metadata");
      JSONObject files = metadata == null ? result : metadata;
      String url = files.optString("audio");
      if (!url.isEmpty()) return AudioDownload.fetch(url, false);
    }
    String decoded = record.optString("decodedWav");
    if (decoded.isEmpty()) throw new IllegalStateException("生成记录尚无可导出的音频");
    return AudioDownload.inspect(new File(decoded), false);
  }

  static AudioDownload.Result processed(
      JSONObject record, float speed, float pitch, BooleanSupplier cancelled) throws Exception {
    String cached = record.optString("processedWav");
    if (!cached.isEmpty()
        && speed == (float) record.optDouble("speed", 1)
        && pitch == (float) record.optDouble("pitch", 1))
      return AudioDownload.inspect(new File(cached), false);
    String decoded = record.optString("decodedWav");
    File source = decoded.isEmpty() ? original(record).file : new File(decoded);
    File target = new File(source.getParentFile(), UUID.randomUUID() + "-processed.wav");
    try (WavFile wav = new WavFile(target)) {
      AudioTransform transform =
          new AudioTransform(
              speed,
              pitch,
              (pcm, rate) -> {
                if (cancelled.getAsBoolean())
                  throw new java.util.concurrent.CancellationException();
                wav.write(pcm, rate);
              });
      AudioDecoder.decode(source.getPath(), cancelled, transform::accept);
      transform.finish();
    }
    return new AudioDownload.Result(target, "wav", "audio/wav");
  }
}
