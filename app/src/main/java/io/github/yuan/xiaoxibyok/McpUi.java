package io.github.yuan.xiaoxibyok;

import android.widget.EditText;
import android.widget.LinearLayout;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

final class McpUi {
  final NativeUi ui;

  McpUi(NativeUi ui) {
    this.ui = ui;
  }

  void show() {
    ui.start("MCP 连接器", this::show);
    ui.row("原生安装与授权管理", "市场连接器、账号授权和原生工具包", () -> ui.nativeRoute("工具箱"));
    ui.button("新建 HTTP 连接器", () -> edit(""));
    ui.button(
        "导入 mcpServers JSON",
        () -> FilesUi.read(ui.a, bytes -> edit(new String(bytes, StandardCharsets.UTF_8))));
    ui.note("首阶段支持 STREAMABLE 与 SSE。市场连接器继续由原生页面管理；自定义连接器通过原生注册与工具检索链运行。");
    LinearLayout target = ui.content;
    ui.work(
        () ->
            Host.query(
                "mcp_store.db",
                "SELECT"
                    + " account_id,mcp_slug,install_origin,connector_status,definition_json,tools_json,extensions_json"
                    + " FROM mcp_connector_installation ORDER BY mcp_slug"),
        result -> {
          if (NativeUi.current != ui || ui.content != target) return;
          JSONArray rows = (JSONArray) result;
          if (rows.length() == 0) ui.note("暂无已安装连接器");
          for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            ui.row(
                row.optString("mcp_slug"),
                row.optString("connector_status") + " · " + row.optString("install_origin"),
                () -> server(row));
          }
        });
  }

  void edit(String imported) {
    ui.start(imported.isEmpty() ? "新建 HTTP 连接器" : "导入 MCP", () -> edit(imported));
    EditText account =
        ui.field("账号作用域（留空使用原生默认）", MainHook.prefs().getString("mcp.account", ""), false);
    EditText json =
        ui.field(
            "mcpServers JSON",
            imported.isEmpty()
                ? Json.pretty(
                    Json.o(
                        "mcpServers",
                        Json.o(
                            "my-server",
                            Json.o(
                                "type",
                                "streamable",
                                "url",
                                "https://example.com/mcp",
                                "headers",
                                new JSONObject()))))
                : imported,
            true);
    ui.note("每个服务器可分别设置 type、url、headers、description 和 disabled。command/STDIO 配置会明确拒绝，不会静默忽略。");
    ui.button(
        "校验、安装并连接",
        () -> {
          String scope = ui.value(account).trim();
          String body = ui.value(json);
          MainHook.prefs().edit().putString("mcp.account", scope).apply();
          ui.work(
              () -> McpBridge.importServers(scope, body),
              result -> ui.textPage("MCP 安装结果", Json.pretty(result)));
        });
  }

  void server(JSONObject installation) {
    String account = installation.optString("account_id"), id = installation.optString("mcp_slug");
    ui.start(id, () -> server(installation));
    ui.row("状态", installation.optString("connector_status"), null);
    ui.row("来源与账号", installation.optString("install_origin") + " · " + account, null);
    JSONObject extensions = Json.obj(installation.optString("extensions_json", "{}"));
    boolean custom = extensions.optBoolean("xiaoxiCustom");
    if (custom) {
      ui.toggle(
          "启用连接器",
          installation.optString("connector_status").equals("ACTIVE"),
          enabled ->
              ui.work(
                  () -> McpBridge.setEnabled(account, id, enabled),
                  result -> {
                    NativeUi.toast("连接器状态已更新");
                    reload(installation);
                  }));
      ui.button(
          "卸载自定义连接器",
          () ->
              ui.work(
                  () -> McpBridge.disconnect(account, id, true),
                  result -> {
                    NativeUi.toast("已卸载");
                    ui.back();
                  }));
    } else {
      ui.note("这是市场或原生连接器。安装、授权和卸载请使用原生管理页。");
    }
    ui.row("工具与调用", "查看 schema、暴露状态和手动调用", () -> tools(installation));
    ui.row(
        "安装定义",
        "原生 definition、tools 与 extensions",
        () -> ui.textPage("MCP 定义", Json.pretty(installation)));
  }

  void reload(JSONObject installation) {
    String account = installation.optString("account_id"), id = installation.optString("mcp_slug");
    ui.work(
        () ->
            Host.query(
                "mcp_store.db",
                "SELECT"
                    + " account_id,mcp_slug,install_origin,connector_status,definition_json,tools_json,extensions_json"
                    + " FROM mcp_connector_installation WHERE account_id=? AND mcp_slug=? LIMIT 1",
                account,
                id),
        result -> {
          JSONArray rows = (JSONArray) result;
          if (rows.length() == 0) {
            ui.back();
            return;
          }
          ui.refresh(() -> server(rows.optJSONObject(0)));
        });
  }

  void tools(JSONObject installation) {
    String account = installation.optString("account_id"), id = installation.optString("mcp_slug");
    ui.start("MCP 工具", () -> tools(installation));
    LinearLayout target = ui.content;
    ui.work(
        () -> McpBridge.tools(account, id),
        result -> {
          if (!ui.showing(target)) return;
          JSONArray tools = (JSONArray) result;
          if (tools.length() == 0) ui.note("连接器尚未返回工具，请先检查连接与授权状态。");
          for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            ui.row(
                tool.optString("name"),
                tool.optString("description", tool.optString("title")),
                () -> tool(installation, tool));
          }
        });
  }

  void tool(JSONObject installation, JSONObject tool) {
    String account = installation.optString("account_id"),
        server = installation.optString("mcp_slug");
    String name = tool.optString("name");
    ui.start(name, () -> tool(installation, tool));
    ui.note(tool.optString("description", tool.optString("title")));
    ui.toggle(
        "允许 Agent 使用此工具",
        tool.optBoolean("enabled", true),
        enabled ->
            ui.work(
                () -> {
                  McpBridge.setToolEnabled(account, server, name, enabled);
                  return Host.unit();
                },
                result -> {
                  Json.put(tool, "enabled", enabled);
                  ui.refresh(() -> tool(installation, tool));
                  NativeUi.toast("工具暴露状态已更新");
                }));
    ui.row("输入 schema", "", () -> ui.textPage("工具 schema", Json.pretty(tool.opt("inputSchema"))));
    EditText arguments = ui.field("手动调用参数 JSON", "{}", true);
    ui.button(
        "手动调用",
        () -> {
          JSONObject args = Json.obj(ui.value(arguments));
          ui.work(
              () -> McpBridge.call(server, name, args, ui.sid),
              result -> ui.textPage("MCP 调用结果", String.valueOf(result)));
        });
  }
}
