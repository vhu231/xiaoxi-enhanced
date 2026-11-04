package io.github.yuan.xiaoxibyok;

import android.media.*;
import java.nio.*;
import java.util.Collections;
import java.util.function.BooleanSupplier;

/** Decode service audio into the mono PCM16 chunks expected by the native playback queue. */
final class AudioDecoder {
  interface Sink {
    void accept(byte[] pcm, int sampleRate) throws Exception;
  }

  static void decode(String source, BooleanSupplier cancelled, Sink sink) throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec codec = null;
    try {
      if (source.startsWith("http://") || source.startsWith("https://"))
        extractor.setDataSource(source, Collections.emptyMap());
      else extractor.setDataSource(source);
      MediaFormat format = null;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat track = extractor.getTrackFormat(i);
        if (track.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
          extractor.selectTrack(i);
          format = track;
          break;
        }
      }
      if (format == null) throw new IllegalArgumentException("生成结果没有音频轨道");
      codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
      codec.configure(format, null, null, 0);
      codec.start();
      int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      int encoding = AudioFormat.ENCODING_PCM_16BIT;
      boolean inputEnded = false, outputEnded = false;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (!outputEnded) {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        if (!inputEnded) {
          int input = codec.dequeueInputBuffer(10000);
          if (input >= 0) {
            ByteBuffer buffer = codec.getInputBuffer(input);
            int length = extractor.readSampleData(buffer, 0);
            if (length < 0) {
              codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputEnded = true;
            } else {
              codec.queueInputBuffer(input, 0, length, extractor.getSampleTime(), 0);
              extractor.advance();
            }
          }
        }
        int output = codec.dequeueOutputBuffer(info, 10000);
        if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat decoded = codec.getOutputFormat();
          rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          encoding =
              decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)
                  ? decoded.getInteger(MediaFormat.KEY_PCM_ENCODING)
                  : AudioFormat.ENCODING_PCM_16BIT;
        } else if (output >= 0) {
          try {
            if (info.size > 0) {
              ByteBuffer buffer =
                  codec.getOutputBuffer(output).duplicate().order(ByteOrder.LITTLE_ENDIAN);
              buffer.position(info.offset);
              buffer.limit(info.offset + info.size);
              sink.accept(mono(buffer, channels, encoding), rate);
            }
          } finally {
            codec.releaseOutputBuffer(output, false);
          }
          outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        }
      }
    } finally {
      if (codec != null) codec.release();
      extractor.release();
    }
  }

  static byte[] mono(ByteBuffer source, int channels, int encoding) {
    boolean floating = encoding == AudioFormat.ENCODING_PCM_FLOAT;
    if (!floating && encoding != AudioFormat.ENCODING_PCM_16BIT)
      throw new IllegalArgumentException("不支持的解码 PCM 格式：" + encoding);
    int frames = source.remaining() / (channels * (floating ? 4 : 2));
    ByteBuffer result = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int frame = 0; frame < frames; frame++) {
      double sum = 0;
      for (int channel = 0; channel < channels; channel++)
        sum += floating ? source.getFloat() * 32767.0 : source.getShort();
      result.putShort((short) Math.max(-32768, Math.min(32767, Math.round(sum / channels))));
    }
    return result.array();
  }

  static void emit(Object collector, byte[] pcm, int rate) throws Exception {
    Host.await(collector, "emit", Host.make("E8.t", pcm, rate));
  }
}
