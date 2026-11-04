package io.github.yuan.xiaoxibyok;

import de.robv.android.xposed.*;
import java.util.*;
import org.json.*;

/** Independent model route for the native summarizer; follows chat only when no override exists. */
final class SummaryModel {
  static final Map<Object, Profile> configurations =
      Collections.synchronizedMap(new WeakHashMap<>());
  static final List<Object> clients = new ArrayList<>();

  static JSONObject configuration() {
    return Store.get("compression", "default").optJSONObject("model");
  }

  static Profile configuration(Object clientConfig) {
    return configurations.get(Host.field(clientConfig, "d"));
  }

  static Object client(Object originalClient, JSONObject snapshot) {
    Profile profile = new Profile(snapshot, "");
    if (!profile.active() || profile.model.trim().isEmpty())
      throw new IllegalArgumentException("摘要模型必须配置 endpoint 和 model");
    Object originalBinding = Host.field(originalClient, "a");
    Object owner = Host.field(originalBinding, "e");
    Object function = Host.field(originalBinding, "a");
    Object original = Host.call(originalBinding, "c");
    Object source = Host.field(original, "a");
    String originalEndpoint = Profile.normalize(Host.text(source, "d"));
    String key =
        snapshot.has("apiKey")
            ? snapshot.optString("apiKey")
            : originalEndpoint.equals(profile.endpoint) ? Host.text(source, "f") : "";
    Object originalCapabilities = Host.field(original, "f");
    Object window =
        profile.window > 0
            ? Integer.valueOf(profile.window)
            : originalCapabilities == null ? null : Host.field(originalCapabilities, "a");
    Object capabilities =
        Host.make("Y6.b0", window, Host.field(source, "h"), Host.field(source, "i"));
    Object route =
        Host.make(
            "C6.L",
            "xiaoxi-summary-" + UUID.randomUUID(),
            Host.field(source, "b"),
            snapshot.optString("provider", "openai"),
            profile.endpoint,
            profile.model,
            key,
            window,
            Host.field(source, "h"),
            Host.field(source, "i"));
    Object snapshotObject =
        Host.make(
            "W6.f",
            route,
            Host.field(original, "b"),
            Host.field(original, "c"),
            Host.field(original, "d"),
            Host.field(original, "e"),
            capabilities);
    String id = UUID.randomUUID().toString();
    TaskModelScope.associations.put(id, new TaskModelScope.Association(owner, snapshotObject));
    Object binding = Host.make("com.flyme.agentos.llm.impl.routing.l", owner, function, id);
    configurations.put(capabilities, profile);
    Object client = Host.call(binding, "b");
    synchronized (clients) {
      clients.add(client);
      clients.add(binding);
    }
    return client;
  }

  static void install() {
    XposedBridge.hookAllConstructors(
        Host.cls(Compression.PREFIX + "k"),
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            JSONObject model = configuration();
            if (model != null && p.args.length > 0 && p.args[0] != null)
              p.args[0] = client(p.args[0], model);
          }
        });
  }
}
