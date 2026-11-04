package io.github.yuan.xiaoxibyok;

import java.util.*;

final class VisionRouting {
  static final List<Association> configurations = new ArrayList<>();

  static final class Association {
    final java.lang.ref.WeakReference<Object> capabilities;
    final VisionConfig config;

    Association(Object capabilities, VisionConfig config) {
      this.capabilities = new java.lang.ref.WeakReference<>(capabilities);
      this.config = config;
    }
  }

  static synchronized void associate(Object capabilities, VisionConfig config) {
    configurations.removeIf(entry -> entry.capabilities.get() == null);
    configurations.add(new Association(capabilities, config));
  }

  static boolean imageBinding(Object binding) {
    return "IMAGE_ANALYSIS".equals(String.valueOf(Host.field(binding, "a")));
  }

  static synchronized VisionConfig configuration(Object clientConfig) {
    Object capabilities = Host.field(clientConfig, "d");
    for (Association entry : configurations)
      if (entry.capabilities.get() == capabilities) return entry.config;
    return null;
  }

  static void install() {
    Xposed.hookAllMethods(
        Host.cls("com.flyme.agentos.llm.impl.routing.k"),
        "a",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!imageBinding(Host.field(p.thisObject, "a")) || p.args[0] == null) return;
            Object request = p.args[0];
            if (Host.text(request, "e").isEmpty())
              Reflect.setObjectField(request, "e", UUID.randomUUID().toString());
            Trace.Parent toolParent = Trace.toolParent.get();
            Trace.Run parent = Trace.parsing.get();
            if (toolParent != null) {
              if (Host.text(request, "g").isEmpty())
                Reflect.setObjectField(request, "g", toolParent.session);
              Reflect.setAdditionalInstanceField(
                  request, "xiaoxi.parentRequestId", toolParent.requestId);
            } else if (parent != null && Host.text(request, "g").isEmpty()) {
              Reflect.setObjectField(request, "g", parent.session);
            }
          }
        });
    Xposed.hookAllMethods(
        Host.cls("com.flyme.agentos.llm.impl.routing.o"),
        "c",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!"IMAGE_ANALYSIS".equals(String.valueOf(p.args[0]))) return;
            VisionConfig config = new VisionConfig();
            if (!config.enabled) return;
            Object chatFunction =
                Reflect.getStaticObjectField(
                    Host.cls("com.flyme.agentos.core.ModelFunction"), "CHAT");
            Object chat = Host.call(p.thisObject, "c", chatFunction, null);
            Object snapshot = config.snapshot(chat);
            associate(Host.field(snapshot, "f"), config);
            p.setResult(snapshot);
          }
        });
    Xposed.hookAllMethods(
        Host.cls("com.flyme.agentos.llm.impl.routing.l"),
        "b",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            // Acquire a fresh image delegate on each tool invocation; in-flight leases remain owned
            // by the native slot. This also restores native routing after disabling the override.
            if (imageBinding(p.thisObject)) Host.call(p.thisObject, "invalidate");
          }
        });
    Xposed.hookAllMethods(
        Host.cls("com.flyme.agentos.app.phone.toolprovider.w0"),
        "b",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!new VisionConfig().enabled) return;
            Object client = p.args[0];
            if (Host.cls("com.flyme.agentos.llm.impl.routing.k").isInstance(client)
                && imageBinding(Host.field(client, "a"))) p.setResult(client);
          }
        });
  }
}
