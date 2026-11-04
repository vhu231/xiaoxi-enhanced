package io.github.yuan.xiaoxibyok;
import java.io.*;
import java.nio.*;
import java.nio.file.*;

public final class WavFileTest {
  public static void main(String[] args) throws Exception {
    File file = new File(args[0]);
    byte[] pcm = {0, 0, -1, 127, 0, -128};
    try (WavFile wav = new WavFile(file)) { wav.write(pcm, 24000); }
    byte[] bytes = Files.readAllBytes(file.toPath());
    ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (bytes.length != 50 || header.getInt(4) != 42 || header.getInt(40) != 6
        || header.getInt(24) != 24000 || header.getInt(28) != 48000
        || header.getShort(22) != 1 || header.getShort(34) != 16)
      throw new AssertionError("Invalid WAV header");
    for (int i = 0; i < pcm.length; i++) if (bytes[44+i] != pcm[i]) throw new AssertionError("PCM changed");
    System.out.println("WAV header, actual lengths and PCM preservation passed");
  }
}
