package io.github.yuan.xiaoxibyok;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class AudioTransformTest {
  static final int RATE = 24000;

  static byte[] tone() {
    ByteBuffer b = ByteBuffer.allocate(RATE * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < RATE * 2; i++)
      b.putShort((short) (12000 * Math.sin(2 * Math.PI * 220 * i / RATE)));
    return b.array();
  }

  static byte[] process(byte[] input, float speed, float pitch, int chunk) throws Exception {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    AudioTransform transform =
        new AudioTransform(
            speed,
            pitch,
            (pcm, rate) -> {
              if (rate != RATE) throw new AssertionError("sample rate changed");
              result.write(pcm);
            });
    for (int i = 0; i < input.length; i += chunk)
      transform.accept(Arrays.copyOfRange(input, i, Math.min(input.length, i + chunk)), RATE);
    transform.finish();
    return result.toByteArray();
  }

  static void check(byte[] input, float speed, float pitch) throws Exception {
    byte[] output = process(input, speed, pitch, 514);
    double duration = output.length / 2.0 / RATE;
    if (Math.abs(duration - 2.0 / speed) > 0.035)
      throw new AssertionError("duration " + duration + " at speed " + speed);
    ByteBuffer b = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
    int start = RATE / 10, end = output.length / 2 - RATE / 10;
    int crossings = 0;
    short previous = b.getShort(start * 2);
    for (int i = start + 1; i < end; i++) {
      short sample = b.getShort(i * 2);
      if (previous <= 0 && sample > 0) crossings++;
      previous = sample;
    }
    double frequency = crossings * (double) RATE / (end - start);
    if (Math.abs(frequency - 220 * pitch) > 5)
      throw new AssertionError("frequency " + frequency + " at pitch " + pitch);
    byte[] largeChunks = process(input, speed, pitch, 8192);
    if (Math.abs(largeChunks.length - output.length) > RATE * 0.01 * 2)
      throw new AssertionError("chunking changed duration");
  }

  public static void main(String[] args) throws Exception {
    byte[] input = tone();
    if (!Arrays.equals(input, process(input, 1, 1, 514)))
      throw new AssertionError("default playback must preserve PCM");
    check(input, 2, 1);
    check(input, 0.75f, 1);
    check(input, 1, 1.5f);
    check(input, 1.5f, 0.8f);
    System.out.println(
        "AudioTransform: identity, tempo, independent pitch and chunk continuity passed");
  }
}
