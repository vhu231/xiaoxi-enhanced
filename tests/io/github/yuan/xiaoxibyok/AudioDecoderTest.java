package io.github.yuan.xiaoxibyok;

import java.nio.*;

public final class AudioDecoderTest {
  static ByteBuffer buffer(int bytes) {
    return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
  }

  static void expect(byte[] actual, short... expected) {
    ByteBuffer data = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
    if (data.remaining() != expected.length * 2) throw new AssertionError("PCM length");
    for (short value : expected)
      if (data.getShort() != value) throw new AssertionError("PCM sample");
  }

  public static void main(String[] args) {
    ByteBuffer stereo = buffer(8);
    stereo.putShort((short) 1000).putShort((short) -1000);
    stereo.putShort((short) 3000).putShort((short) 1000).flip();
    expect(AudioDecoder.mono(stereo, 2, 2), (short) 0, (short) 2000);
    ByteBuffer mono = buffer(6);
    mono.putShort(Short.MIN_VALUE).putShort((short) 0).putShort(Short.MAX_VALUE).flip();
    expect(AudioDecoder.mono(mono, 1, 2), Short.MIN_VALUE, (short) 0, Short.MAX_VALUE);
    ByteBuffer floating = buffer(16);
    floating.putFloat(2f).putFloat(-2f).putFloat(.5f).putFloat(-.5f).flip();
    expect(
        AudioDecoder.mono(floating, 1, 4),
        (short) 32767,
        (short) -32768,
        (short) 16384,
        (short) -16383);
    System.out.println("PCM conversion: stereo downmix, mono identity, float clipping passed");
  }
}
