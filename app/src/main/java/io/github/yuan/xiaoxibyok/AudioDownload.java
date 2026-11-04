package io.github.yuan.xiaoxibyok;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class AudioDownload {
  static final class Result {
    final File file;
    final String extension, mime;

    Result(File file, String extension, String mime) {
      this.file = file;
      this.extension = extension;
      this.mime = mime;
    }
  }

  static Result fetch(String url, boolean subtitle) throws Exception {
    File directory = new File(Host.app().getFilesDir(), "xiaoxi_audio");
    directory.mkdirs();
    File file = new File(directory, UUID.randomUUID() + ".download");
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(30000);
    c.setReadTimeout(120000);
    try {
      int status = c.getResponseCode();
      if (status >= 400) throw new IOException("下载失败：HTTP " + status);
      try (InputStream in = c.getInputStream();
          OutputStream out = new FileOutputStream(file)) {
        byte[] bytes = new byte[32768];
        int count;
        while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
      }
    } finally {
      c.disconnect();
    }
    return inspect(file, subtitle);
  }

  static Result inspect(File file, boolean subtitle) throws IOException {
    if (subtitle) {
      byte[] prefix = new byte[4096];
      int size;
      try (InputStream in = new FileInputStream(file)) {
        size = in.read(prefix);
      }
      String text =
          new String(prefix, 0, Math.max(0, size), StandardCharsets.UTF_8)
              .replace("\uFEFF", "")
              .trim();
      if (text.startsWith("WEBVTT")) return new Result(file, "vtt", "text/vtt");
      if (text.startsWith("{") || text.startsWith("["))
        return new Result(file, "json", "application/json");
      if (text.matches("(?s).*\\d{2}:\\d{2}:\\d{2},\\d{3} --> .*"))
        return new Result(file, "srt", "application/x-subrip");
      return new Result(file, "txt", "text/plain");
    }
    byte[] header = new byte[16];
    int count;
    try (InputStream in = new FileInputStream(file)) {
      count = in.read(header);
    }
    String magic = new String(header, StandardCharsets.ISO_8859_1);
    if (magic.startsWith("RIFF") && magic.substring(8, 12).equals("WAVE"))
      return new Result(file, "wav", "audio/wav");
    if (magic.startsWith("OggS")) return new Result(file, "ogg", "audio/ogg");
    if (magic.startsWith("fLaC")) return new Result(file, "flac", "audio/flac");
    if (magic.substring(4, 8).equals("ftyp")) return new Result(file, "m4a", "audio/mp4");
    if (magic.startsWith("ID3")
        || count > 1 && (header[0] & 255) == 255 && (header[1] & 0xe0) == 0xe0) {
      boolean adts = !magic.startsWith("ID3") && (header[1] & 0xf6) == 0xf0;
      return new Result(file, adts ? "aac" : "mp3", adts ? "audio/aac" : "audio/mpeg");
    }
    return new Result(file, "bin", "application/octet-stream");
  }
}
