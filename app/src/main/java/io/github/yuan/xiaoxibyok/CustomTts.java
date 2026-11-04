package io.github.yuan.xiaoxibyok;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Explicit template protocol: JSON values substitute ${text}, ${voice}, ${model}, ${language}. */
final class CustomTts implements AutoCloseable {
  volatile boolean cancelled;
  volatile HttpURLConnection connection;

  static Object template(Object value, Map<String, String> variables) throws JSONException {
    if (value instanceof JSONObject) {
      JSONObject result = new JSONObject(), source = (JSONObject) value;
      for (Iterator<String> keys = source.keys(); keys.hasNext(); ) {
        String key = keys.next();
        result.put(key, template(source.get(key), variables));
      }
      return result;
    }
    if (value instanceof JSONArray) {
      JSONArray result = new JSONArray(), source = (JSONArray) value;
      for (int i = 0; i < source.length(); i++) result.put(template(source.get(i), variables));
      return result;
    }
    if (value instanceof String) {
      String input = (String) value;
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile("\\$\\{(text|voice|model|language)\\}").matcher(input);
      StringBuffer output = new StringBuffer();
      while (matcher.find())
        matcher.appendReplacement(
            output, java.util.regex.Matcher.quoteReplacement(variables.get(matcher.group(1))));
      matcher.appendTail(output);
      return output.toString();
    }
    return value;
  }

  static Object lookup(JSONObject object, String path) {
    Object value = object;
    if (path.isEmpty()) return null;
    for (String part : path.split("\\.")) {
      if (value instanceof JSONArray) {
        if (!part.matches("[0-9]+")) return null;
        value = ((JSONArray) value).opt(Integer.parseInt(part));
      } else if (value instanceof JSONObject) {
        value = ((JSONObject) value).opt(part);
      } else return null;
      if (value == null || value == JSONObject.NULL) return null;
    }
    return value;
  }

  static String path(JSONObject object, String path) throws JSONException {
    Object value = lookup(object, path);
    if (value == null) throw new JSONException("TTS 响应缺少字段：" + path);
    return String.valueOf(value);
  }

  HttpURLConnection open(String url, String method, JSONObject headers, JSONObject body)
      throws Exception {
    if (cancelled) throw new java.util.concurrent.CancellationException();
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    connection = c;
    c.setRequestMethod(method);
    c.setConnectTimeout(30000);
    c.setReadTimeout(120000);
    for (Iterator<String> keys = headers.keys(); keys.hasNext(); ) {
      String key = keys.next();
      c.setRequestProperty(key, headers.getString(key));
    }
    if (body != null) {
      c.setDoOutput(true);
      if (!headers.has("Content-Type")) c.setRequestProperty("Content-Type", "application/json");
      byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
      c.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream out = c.getOutputStream()) {
        out.write(bytes);
      }
    }
    if (c.getResponseCode() >= 400)
      throw new IOException("TTS HTTP " + c.getResponseCode() + ": " + read(c.getErrorStream()));
    return c;
  }

  String read(InputStream stream) throws IOException {
    try (InputStream in = stream;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[8192];
      int n;
      while ((n = in.read(bytes)) != -1) {
        if (cancelled) throw new java.util.concurrent.CancellationException();
        out.write(bytes, 0, n);
      }
      return out.toString("UTF-8");
    }
  }

  JSONObject synthesize(String text, JSONObject config, AudioDecoder.Sink sink) throws Exception {
    Map<String, String> vars = new HashMap<>();
    vars.put("text", text);
    vars.put("voice", config.optString("voiceId"));
    vars.put("model", config.optString("model"));
    vars.put("language", config.optString("language", "auto"));
    JSONObject headers = config.optJSONObject("headers");
    if (headers == null) headers = new JSONObject();
    JSONObject body = (JSONObject) template(config.getJSONObject("body"), vars);
    String method = config.optString("method", "POST");
    HttpURLConnection c =
        open(config.getString("endpoint"), method, headers, method.equals("GET") ? null : body);
    String type = config.optString("responseType", "audio");
    if (type.equals("sse")) {
      StringBuilder event = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (cancelled) throw new java.util.concurrent.CancellationException();
          if (line.isEmpty()) {
            emit(event.toString(), config, sink);
            event.setLength(0);
          } else if (line.startsWith("data:")) {
            if (event.length() > 0) event.append('\n');
            event.append(line.substring(5).trim());
          }
        }
        emit(event.toString(), config, sink);
      }
      return Json.o("responseType", "sse");
    }
    if (type.equals("audio")) {
      File dir = new File(Host.app().getFilesDir(), "xiaoxi_audio");
      dir.mkdirs();
      File file = new File(dir, UUID.randomUUID() + ".original");
      try (InputStream in = c.getInputStream();
          OutputStream out = new FileOutputStream(file)) {
        byte[] bytes = new byte[32768];
        int n;
        while ((n = in.read(bytes)) != -1) {
          if (cancelled) throw new java.util.concurrent.CancellationException();
          out.write(bytes, 0, n);
        }
      }
      AudioDecoder.decode(file.getPath(), () -> cancelled, sink);
      return Json.o("originalFile", file.getPath(), "mime", c.getContentType());
    }
    JSONObject response = new JSONObject(read(c.getInputStream()));
    c.disconnect();
    if (type.equals("async")) {
      String task = path(response, config.getString("taskIdPath"));
      String pollUrl = config.getString("pollUrl").replace("${id}", VocuApi.segment(task));
      while (true) {
        if (cancelled) throw new java.util.concurrent.CancellationException();
        HttpURLConnection poll = open(pollUrl, "GET", headers, null);
        response = new JSONObject(read(poll.getInputStream()));
        poll.disconnect();
        String state = path(response, config.getString("statusPath"));
        if (state.equals(config.optString("successStatus", "completed"))) break;
        if (state.equals(config.optString("failureStatus", "failed")))
          throw new IOException(response.toString());
        Thread.sleep(config.optLong("pollMs", 2000));
      }
    }
    String url = path(response, config.getString("audioPath"));
    AudioDecoder.decode(url, () -> cancelled, sink);
    return Json.o("audio", url, "response", response);
  }

  void emit(String data, JSONObject config, AudioDecoder.Sink sink) throws Exception {
    if (data.isEmpty() || data.equals("[DONE]")) return;
    JSONObject event = new JSONObject(data);
    String errorPath = config.optString("errorPath");
    Object error = lookup(event, errorPath);
    if (error != null && !Boolean.FALSE.equals(error) && !"".equals(error))
      throw new IOException(event.toString());
    Object audio = lookup(event, config.getString("audioPath"));
    if (audio == null) return;
    String encoded = String.valueOf(audio);
    if (!encoded.isEmpty())
      sink.accept(
          android.util.Base64.decode(encoded, android.util.Base64.DEFAULT),
          config.optInt("sampleRate", 24000));
  }

  public void close() {
    cancelled = true;
    HttpURLConnection c = connection;
    if (c != null) c.disconnect();
  }
}
