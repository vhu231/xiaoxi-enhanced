package io.github.yuan.xiaoxibyok;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Transport for the local Vocu API contract; responses retain requestId and task metadata. */
final class VocuApi implements AutoCloseable {
  final String endpoint, key;
  volatile HttpURLConnection connection;
  volatile boolean cancelled;

  VocuApi(String endpoint, String key) {
    this.endpoint = Profile.normalize(endpoint);
    this.key = key;
  }

  static final class Failure extends IOException {
    final int status;
    final String code, requestId;

    Failure(int status, JSONObject response) {
      super(
          response.optString("message", "Vocu request failed")
              + " ["
              + response.optString("code")
              + "; requestId="
              + response.optString("requestId")
              + "]");
      this.status = status;
      code = response.optString("code");
      requestId = response.optString("requestId");
    }
  }

  JSONObject request(String method, String path, JSONObject body) throws Exception {
    if (cancelled) throw new java.util.concurrent.CancellationException();
    HttpURLConnection c = (HttpURLConnection) new URL(endpoint + path).openConnection();
    connection = c;
    c.setRequestMethod(method);
    c.setConnectTimeout(30000);
    c.setReadTimeout(600000);
    c.setRequestProperty("Accept", "application/json");
    c.setRequestProperty("X-Vocu-App-Lang", "zh-CN");
    if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
    try {
      if (body != null) {
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) {
          out.write(bytes);
        }
      }
      int http = c.getResponseCode();
      InputStream response = http >= 400 ? c.getErrorStream() : c.getInputStream();
      ByteArrayOutputStream data = new ByteArrayOutputStream();
      if (response != null)
        try (InputStream in = response) {
          byte[] buffer = new byte[8192];
          int length;
          while ((length = in.read(buffer)) != -1) {
            if (cancelled) throw new java.util.concurrent.CancellationException();
            data.write(buffer, 0, length);
          }
        }
      String text = data.toString("UTF-8");
      JSONObject result;
      try {
        result = new JSONObject(text);
      } catch (JSONException e) {
        throw new IOException("Vocu HTTP " + http + ": " + text, e);
      }
      int status = result.optInt("status", http);
      if (http >= 400 || status >= 400) throw new Failure(status, result);
      return result;
    } finally {
      c.disconnect();
      connection = null;
    }
  }

  JSONObject models() throws Exception {
    return request("GET", "/tts/models", null);
  }

  JSONObject voices() throws Exception {
    return request("GET", "/voice?show=full", null);
  }

  JSONObject voice(String id) throws Exception {
    return request("GET", "/voice/" + segment(id), null);
  }

  JSONObject market(int offset, int limit) throws Exception {
    return request("GET", "/market/voice?offset=" + offset + "&limit=" + limit, null);
  }

  JSONObject pull(String id) throws Exception {
    return request("POST", "/market/voice/" + segment(id) + "/pull", new JSONObject());
  }

  JSONObject simple(JSONObject content) throws Exception {
    JSONObject body = new JSONObject(content.toString());
    body.put("stream", true);
    body.remove("speechRate");
    return request("POST", "/tts/simple-generate", body);
  }

  JSONObject generate(JSONArray contents) throws Exception {
    JSONArray segments = new JSONArray(contents.toString());
    for (int i = 0; i < segments.length(); i++) segments.getJSONObject(i).remove("speechRate");
    return request("POST", "/tts/generate", new JSONObject().put("contents", segments));
  }

  JSONObject task(String id) throws Exception {
    return request("GET", "/tts/generate/" + segment(id) + "?stream=true", null);
  }

  static String segment(String value) throws UnsupportedEncodingException {
    return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
  }

  public void close() {
    cancelled = true;
    HttpURLConnection active = connection;
    if (active != null) active.disconnect();
  }
}
