package io.github.yuan.xiaoxibyok;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** One streaming transform per synthesis; output is shared by playback and processed export. */
final class AudioTransform {
  interface Sink {
    void accept(byte[] pcm, int rate) throws Exception;
  }

  final float speed, pitch;
  final Sink output;
  Sonic sonic;
  int rate;
  boolean ended;

  AudioTransform(float speed, float pitch, Sink output) {
    if (!Float.isFinite(speed) || !Float.isFinite(pitch) || speed <= 0 || pitch <= 0)
      throw new IllegalArgumentException("语速和音调必须为正数");
    this.speed = speed;
    this.pitch = pitch;
    this.output = output;
  }

  void accept(byte[] pcm, int sampleRate) throws Exception {
    if (ended) throw new IllegalStateException("音频处理已结束");
    if ((pcm.length & 1) != 0 || sampleRate <= 0)
      throw new IllegalArgumentException("无效的 PCM16 音频块");
    if (rate != 0 && rate != sampleRate) throw new IllegalArgumentException("同一音频流采样率发生变化");
    rate = sampleRate;
    if (speed == 1f && pitch == 1f) {
      output.accept(pcm, rate);
      return;
    }
    if (sonic == null) sonic = new Sonic(rate, 1, speed, pitch, rate, false);
    sonic.queueInput(ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN));
    drain();
  }

  void finish() throws Exception {
    if (ended) return;
    ended = true;
    if (sonic != null) {
      sonic.queueEndOfStream();
      drain();
    }
  }

  void drain() throws Exception {
    while (sonic.getOutputSize() > 0) {
      ByteBuffer data =
          ByteBuffer.allocate(Math.min(32768, sonic.getOutputSize()))
              .order(ByteOrder.LITTLE_ENDIAN);
      sonic.getOutput(data);
      output.accept(data.array(), rate);
    }
  }
}
