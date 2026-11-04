package io.github.yuan.xiaoxibyok;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Custom HTTP connectors use the host store coordinator, installation schema and tool registry. */
final class McpBridge {
  static final String CLOUD = "com.flyme.agentos.agent.domain.cloudskill.";

  static void install() {
    Host.capture("com.flyme.agentos.agent.domain.cloudskill.x2", "mcpCoordinator");
    Host.capture("com.flyme.agentos.agent.domain.mcp.t", "mcpConnection");
    Host.capture("n5.p", "mcpStore");
  }

  static Object copyInstallation(Object installed, boolean enabled) {
    return Host.make(
        "n5.c",
        Host.field(installed, "a"),
        Host.field(installed, "b"),
        Host.field(installed, "c"),
        Host.field(installed, "d"),
        constant(CLOUD + "McpStoreStatus", enabled ? "ACTIVE" : "ARCHIVED"),
        Host.field(installed, "g"),
        Host.field(installed, "h"),
        Host.field(installed, "i"),
        Host.field(installed, "j"),
        Host.field(installed, "k"),
        Host.field(installed, "l"),
        System.currentTimeMillis());
  }

  static Object setEnabled(String account, String serverId, boolean enabled) throws Exception {
    Object store = Host.services.get("mcpStore");
    Object installed = Host.call(store, "g", account, serverId);
    if (installed == null) throw new IllegalArgumentException("连接器尚未安装：" + serverId);
    JSONObject extensions = new JSONObject(Host.text(installed, "d"));
    if (!extensions.optBoolean("xiaoxiCustom"))
      throw new IllegalArgumentException("市场连接器请使用原生安装与授权管理");
    Object write = copyInstallation(installed, enabled);
    if (!enabled) {
      Host.call(store, "u", write);
      return disconnect(account, serverId, false);
    }
    Object prepared =
        Reflect.callStaticMethod(
            Host.cls("com.google.android.gms.internal.mlkit_vision_common.u4"), "m", write);
    return Host.await(
        Host.services.get("mcpCoordinator"),
        "f",
        account,
        prepared,
        Collections.emptyMap(),
        Host.field(installed, "c"),
        Host.text(installed, "d"));
  }

  static Object constant(String type, String name) {
    return Reflect.getStaticObjectField(Host.cls(type), name);
  }

  static JSONArray parseImport(String json) throws Exception {
    JSONObject servers = new JSONObject(json).getJSONObject("mcpServers");
    JSONArray result = new JSONArray();
    for (Iterator<String> names = servers.keys(); names.hasNext(); ) {
      String name = names.next();
      JSONObject source = servers.getJSONObject(name);
      if (source.has("command")) throw new IllegalArgumentException(name + "：暂不支持 STDIO");
      String url = source.getString("url");
      URI uri = new URI(url);
      if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
        throw new IllegalArgumentException(name + "：MCP 地址必须为 HTTP 或 HTTPS");
      String transport =
          source
              .optString("type", source.optString("transport", "streamable"))
              .toLowerCase(Locale.ROOT);
      if (transport.equals("http") || transport.equals("streamable-http")) transport = "streamable";
      if (!(transport.equals("streamable") || transport.equals("sse")))
        throw new IllegalArgumentException(name + "：未知传输类型 " + transport);
      JSONObject headers = source.optJSONObject("headers");
      result.put(
          Json.o(
              "id",
              "xiaoxi-custom-" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),
              "name",
              name,
              "url",
              url,
              "transport",
              transport,
              "headers",
              headers == null ? new JSONObject() : headers,
              "description",
              source.optString("description"),
              "enabled",
              !source.optBoolean("disabled")));
    }
    return result;
  }

  static JSONArray importServers(String account, String json) throws Exception {
    JSONArray configurations = parseImport(json);
    JSONArray outcomes = new JSONArray();
    for (int i = 0; i < configurations.length(); i++) {
      JSONObject config = configurations.getJSONObject(i);
      JSONObject outcome = Json.o("id", config.getString("id"), "name", config.getString("name"));
      try {
        if (config.getBoolean("enabled")) {
          Object result = connect(account, config);
          Json.put(
              outcome,
              "status",
              Host.cls(CLOUD + "n2").isInstance(result) ? "connected" : "failed");
          Json.put(outcome, "result", String.valueOf(result));
        } else {
          Object prepared = prepare(account, config);
          Object write = Host.field(prepared, "c");
          Object archived =
              Host.make(
                  "n5.c",
                  account,
                  config.getString("id"),
                  Host.field(write, "c"),
                  Host.field(write, "d"),
                  constant(CLOUD + "McpStoreStatus", "ARCHIVED"),
                  Host.field(write, "f"),
                  Host.field(write, "g"),
                  Host.field(write, "h"),
                  Host.field(write, "i"),
                  Host.field(write, "j"),
                  Host.field(write, "k"),
                  Host.field(write, "l"));
          Host.call(Host.services.get("mcpStore"), "u", archived);
          disconnect(account, config.getString("id"), false);
          Json.put(outcome, "status", "disabled");
        }
      } catch (Exception error) {
        Json.put(outcome, "status", "error");
        Json.put(outcome, "error", error.toString());
      }
      outcomes.put(outcome);
    }
    return outcomes;
  }

  static Object prepare(String account, JSONObject config) throws Exception {
    ArrayList<Object> headers = new ArrayList<>();
    JSONObject values = config.getJSONObject("headers");
    for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
      String key = keys.next();
      headers.add(
          Host.make("com.flyme.agentos.agent.domain.cloudskill.F0", 3, key, values.getString(key)));
    }
    Object params =
        Host.make(
            "com.flyme.agentos.agent.domain.cloudskill.I0",
            33,
            constant(CLOUD + "TransportKind", "HTTP"),
            null,
            null,
            null,
            null,
            headers);
    Object definition =
        Host.make(
            "com.flyme.agentos.agent.domain.cloudskill.Z1",
            config.getString("id"),
            config.getString("name"),
            null,
            config.optString("description"),
            "自定义",
            constant(
                CLOUD + "McpTransportType", config.getString("transport").toUpperCase(Locale.ROOT)),
            config.getString("url"),
            params,
            "请求头",
            constant("com.flyme.agentos.agent.domain.mcp.McpStoreAuthMethod", "NONE"),
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList());
    Object write =
        Reflect.callStaticMethod(
            Host.cls(CLOUD + "V1"),
            "f",
            definition,
            account,
            System.currentTimeMillis(),
            constant(CLOUD + "db.McpInstallOrigin", "USER"),
            Json.o("xiaoxiCustom", true).toString());
    return Reflect.callStaticMethod(
        Host.cls("com.google.android.gms.internal.mlkit_vision_common.u4"), "m", write);
  }

  static Object connect(String account, JSONObject config) throws Exception {
    Object prepared = prepare(account, config);
    Object outcome =
        Host.await(
            Host.services.get("mcpCoordinator"),
            "f",
            account,
            prepared,
            Collections.emptyMap(),
            constant(CLOUD + "db.McpInstallOrigin", "USER"),
            Json.o("xiaoxiCustom", true).toString());
    Store.event(
        UUID.randomUUID().toString(),
        "",
        "mcp.connect",
        System.currentTimeMillis(),
        Json.o("serverId", config.getString("id"), "result", String.valueOf(outcome)));
    return outcome;
  }

  static Object disconnect(String account, String serverId, boolean uninstall) throws Exception {
    return Host.await(
        Host.services.get("mcpCoordinator"),
        "j",
        account,
        serverId,
        serverId,
        uninstall,
        uninstall);
  }

  static JSONArray tools(String serverId) throws Exception {
    return tools("", serverId);
  }

  static JSONArray tools(String account, String serverId) throws Exception {
    List<?> tools = (List<?>) Host.await(Host.services.get("mcpConnection"), "j", serverId);
    JSONObject overrides = new JSONObject();
    if (!account.isEmpty()) {
      JSONArray rows =
          Host.query(
              "mcp_store.db",
              "SELECT overrides_json FROM mcp_tool_override WHERE account_id=? AND mcp_slug=?",
              account,
              serverId);
      if (rows.length() > 0)
        overrides = Json.obj(rows.optJSONObject(0).optString("overrides_json"));
    }
    JSONArray result = new JSONArray();
    for (Object tool : tools) {
      Object schema = Host.field(tool, "c");
      String name = Host.text(tool, "a");
      result.put(
          Json.o(
              "serverId",
              serverId,
              "name",
              name,
              "description",
              Host.text(tool, "b"),
              "title",
              Host.text(tool, "d"),
              "enabled",
              overrides.optBoolean(name, true),
              "inputSchema",
              schema == null ? JSONObject.NULL : new JSONObject(schema.toString())));
    }
    return result;
  }

  static void setToolEnabled(String account, String serverId, String toolName, boolean enabled)
      throws Exception {
    List<?> available = (List<?>) Host.await(Host.services.get("mcpConnection"), "j", serverId);
    for (Object tool : available) {
      if (!Host.text(tool, "a").equals(toolName)) continue;
      Host.await(
          Host.services.get("mcpCoordinator"), "t", account, serverId, serverId, tool, enabled);
      return;
    }
    throw new IllegalArgumentException("连接器未提供工具：" + toolName);
  }

  static String call(String serverId, String tool, JSONObject arguments, String session)
      throws Exception {
    return callIn(
        constant("kotlin.coroutines.EmptyCoroutineContext", "INSTANCE"),
        serverId,
        tool,
        arguments,
        session);
  }

  static String callIn(
      Object context, String serverId, String tool, JSONObject arguments, String session)
      throws Exception {
    Object payload = Host.call(constant("Th.b", "d"), "e", arguments.toString());
    String id = UUID.randomUUID().toString();
    long start = System.currentTimeMillis();
    JSONObject event =
        Json.o("serverId", serverId, "tool", tool, "arguments", arguments, "status", "running");
    Store.event(id, session, "mcp.manual", start, event);
    try {
      Object result =
          Host.awaitIn(context, Host.services.get("mcpConnection"), "c", serverId, tool, payload);
      Json.put(event, "status", "completed");
      Json.put(event, "result", String.valueOf(result));
      return String.valueOf(result);
    } catch (Exception error) {
      Json.put(event, "status", "error");
      Json.put(event, "error", error.toString());
      throw error;
    } finally {
      Json.put(event, "end", System.currentTimeMillis());
      Store.event(id, session, "mcp.manual", start, event);
    }
  }
}
