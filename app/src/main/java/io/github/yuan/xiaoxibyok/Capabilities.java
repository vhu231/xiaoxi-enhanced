package io.github.yuan.xiaoxibyok;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class Capabilities {
  static File skillRoot() {
    return (File) Host.field(Host.services.get("skills"), "a");
  }

  static Object importSkill(String name, byte[] bytes) throws Exception {
    Object request =
        Host.make(
            "com.flyme.agentos.agent.domain.skill.install.V",
            name,
            bytes,
            true,
            true,
            null,
            null,
            null,
            512);
    Object result = Host.await(Host.services.get("importer"), "c", request);
    Store.event(
        Host.session, "skill.import", Json.o("name", name, "result", String.valueOf(result)));
    return result;
  }

  static File skillDir(String id) {
    return (File) Host.call(Host.services.get("skills"), "v", id);
  }

  static JSONArray skills() throws Exception {
    JSONArray a = new JSONArray();
    for (File f : skillRoot().listFiles()) {
      if (!new File(f, "SKILL.md").isFile()) continue;
      File meta = new File(f, ".agentos/install.json");
      JSONObject row =
          meta.isFile()
              ? Json.obj(
                  new String(
                      java.nio.file.Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8))
              : Json.o("skillId", f.getName(), "origin", "USER");
      Json.put(
          row, "enabled", Host.call(Host.services.get("enabled"), "c", row.optString("skillId")));
      a.put(row);
    }
    return a;
  }

  static void enable(String id, boolean enabled) throws Exception {
    if (enabled) {
      Host.call(Host.services.get("enabled"), "b", id);
      Host.await(Host.services.get("activation"), "e", id);
    } else {
      Host.call(Host.services.get("enabled"), "a", id);
      Host.await(Host.services.get("activation"), "d", id);
    }
    Store.event(Host.session, "skill.enable", Json.o("id", id, "enabled", enabled));
  }

  static Object memorySave(String id, String title, String content, String tags) throws Exception {
    List<String> list = new ArrayList<>();
    for (String tag :
        (tags.trim().startsWith("[")
            ? Json.strings(Json.arr(tags))
            : Arrays.asList(tags.split("[,，\n]")))) if (!tag.trim().isEmpty()) list.add(tag.trim());
    Object service = Host.services.get("memory");
    Object result =
        id.isEmpty()
            ? Host.await(
                service,
                "b",
                Host.make(
                    "com.flyme.agentos.agent.domain.memory.h",
                    0,
                    title,
                    content,
                    "user",
                    list,
                    Collections.emptyMap()))
            : Host.await(
                service,
                "c",
                id,
                Host.make(
                    "com.flyme.agentos.agent.domain.memory.H1", title, content, "user", list));
    Store.event(Host.session, "memory.edit", Json.o("id", id, "title", title));
    return result;
  }

  static Object memoryDelete(String id) throws Exception {
    return Host.await(Host.services.get("memory"), "m", id);
  }

  static Object enumeration(String type, String value) {
    return de.robv.android.xposed.XposedHelpers.callStaticMethod(
        Host.cls("com.flyme.agentos.agent.domain.profile." + type), "valueOf", value);
  }

  static Object decodeProfile(String type, String text) {
    Object service = Host.services.get("profiles");
    Object companion =
        de.robv.android.xposed.XposedHelpers.getStaticObjectField(
            Host.cls("com.flyme.agentos.agent.domain.profile." + type), "Companion");
    return Host.call(Host.field(service, "b"), "b", Host.call(companion, "serializer"), text);
  }

  static Object profileSave(String table, JSONObject data) throws Exception {
    Object service = Host.services.get("profiles"), record;
    String method;
    if (table.equals("speech_styles")) {
      List<Object> constraints = new ArrayList<>();
      JSONArray list = Json.arr(data.optString("constraints_json", "[]"));
      for (int i = 0; i < list.length(); i++)
        constraints.add(decodeProfile("L1", list.optJSONObject(i).toString()));
      record =
          Host.make(
              "com.flyme.agentos.agent.domain.profile.G1",
              data.optString("style_id"),
              data.optString("profile_id"),
              data.optString("name"),
              enumeration("SpeechTone", data.optString("tone")),
              enumeration("SpeechVerbosity", data.optString("verbosity")),
              enumeration("SpeechFormality", data.optString("formality")),
              enumeration("SpeechInitiative", data.optString("initiative")),
              enumeration("AddressMode", data.optString("address_mode")),
              enumeration("VoiceResponseMode", data.optString("voice_mode")),
              constraints,
              System.currentTimeMillis());
      method = "a";
    } else {
      record =
          Host.make(
              "com.flyme.agentos.agent.domain.profile.x",
              data.optString("attribute_id"),
              data.optString("profile_id"),
              enumeration("ProfileAttributeCategory", data.optString("category")),
              data.optString("key"),
              decodeProfile("y1", data.optString("value_json")),
              (float) data.optDouble("confidence", 1),
              enumeration("ProfileEvidenceSource", data.optString("source")),
              data.isNull("source_session_id") ? null : data.optString("source_session_id"),
              enumeration("ProfileAttributeStatus", data.optString("status")),
              data.optLong("created_at"),
              System.currentTimeMillis());
      method = "h";
    }
    Object result = Host.await(service, method, record);
    Store.event(
        Host.session, "profile.edit", Json.o("table", table, "result", String.valueOf(result)));
    return result;
  }
}
