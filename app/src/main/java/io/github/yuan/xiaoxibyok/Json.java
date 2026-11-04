package io.github.yuan.xiaoxibyok;

import java.util.*;
import org.json.*;

final class Json {
  static JSONObject obj(String s) {
    try {
      return new JSONObject(s);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  static JSONArray arr(String s) {
    try {
      return new JSONArray(s);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  static JSONObject o(Object... pairs) {
    JSONObject o = new JSONObject();
    for (int i = 0; i < pairs.length; i += 2) put(o, String.valueOf(pairs[i]), pairs[i + 1]);
    return o;
  }

  static JSONObject put(JSONObject o, String k, Object v) {
    try {
      o.put(k, v == null ? JSONObject.NULL : v);
      return o;
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  static String pretty(Object o) {
    try {
      return o instanceof JSONObject
          ? ((JSONObject) o).toString(2)
          : o instanceof JSONArray ? ((JSONArray) o).toString(2) : String.valueOf(o);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  static List<String> strings(JSONArray a) {
    List<String> r = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) r.add(a.optString(i));
    return r;
  }

  static String str(Object o) {
    return o == null ? "" : o.toString();
  }
}
