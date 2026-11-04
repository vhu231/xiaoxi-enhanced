package io.github.yuan.xiaoxibyok;

import java.io.*;
import java.nio.*;

/** Streaming mono PCM16 RIFF writer. close() finalizes the actual recorded length. */
final class WavFile implements AutoCloseable {
  final RandomAccessFile file;
  int rate;
  long bytes;

  WavFile(File destination) throws IOException {
    file = new RandomAccessFile(destination, "rw");
    file.setLength(0);
    file.write(new byte[44]);
  }

  void write(byte[] pcm, int sampleRate) throws IOException {
    if (sampleRate <= 0 || (pcm.length & 1) != 0) throw new IOException("无效的 PCM16 音频块");
    if (rate != 0 && rate != sampleRate) throw new IOException("同一 WAV 中采样率发生变化");
    if (bytes + pcm.length > 0xffffffffL - 36) throw new IOException("音频超过 RIFF WAV 大小限制");
    rate = sampleRate;
    file.write(pcm);
    bytes += pcm.length;
  }

  public void close() throws IOException {
    try {
      ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
      header.put(new byte[] {'R', 'I', 'F', 'F'}).putInt((int) (36 + bytes));
      header.put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
      header.putInt(16).putShort((short) 1).putShort((short) 1);
      int sampleRate = rate == 0 ? 24000 : rate;
      header.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
      header.put(new byte[] {'d', 'a', 't', 'a'}).putInt((int) bytes);
      file.seek(0);
      file.write(header.array());
    } finally {
      file.close();
    }
  }
}
