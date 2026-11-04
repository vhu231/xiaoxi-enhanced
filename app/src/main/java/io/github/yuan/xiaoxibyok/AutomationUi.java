package io.github.yuan.xiaoxibyok;

import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

final class AutomationUi {
  final NativeUi ui;

  AutomationUi(NativeUi ui) {
    this.ui = ui;
  }

  void show() {
    ui.start("定时任务", this::show);
    ui.note("任务 ID、重复规则、系统唤醒和重启恢复由小溪原生 Scheduler 管理。增强步骤在原生任务执行时运行。");
    ui.row("新建原生定时任务", "标题、提示词、执行时间与重复规则", () -> nativeEdit(null));
    ui.row("增强执行历史", "按任务查看步骤输出与错误", this::history);
    LinearLayout target = ui.content;
    ui.work(
        () -> Host.await(Host.services.get("taskStore"), "f"),
        result -> {
          List<?> tasks = (List<?>) result;
          JSONArray items = new JSONArray();
          for (Object task : tasks) {
            String id = Host.text(task, "a");
            JSONObject config = Store.get("schedule", id);
            int count = steps(config).length();
            items.put(
                Json.o(
                    "title",
                    Host.text(task, "b"),
                    "subtitle",
                    repeatSummary(task)
                        + "\n"
                        + (config.optBoolean("enabled") ? "增强已启用" : "使用原生提示词")
                        + " · "
                        + count
                        + " 个步骤",
                    "task",
                    task));
          }
          ui.pagedList(
              target,
              "scheduledTasks",
              "暂无原生定时任务。可在这里新建，或通过小溪语音创建。",
              items,
              0,
              row -> new String[] {row.optString("title"), row.optString("subtitle")},
              row -> edit(row.opt("task")));
        });
  }

  static String repeatSummary(Object task) {
    Object rule = Host.field(task, "d");
    String type = Host.text(rule, "a");
    String time = String.format("%02d:%02d", Host.field(rule, "b"), Host.field(rule, "c"));
    String result;
    switch (type) {
      case "DAILY":
        result = "每天";
        break;
      case "WORKDAY":
        result = "法定工作日";
        break;
      case "HOLIDAY":
        result = "法定休假日";
        break;
      case "WEEKLY":
        int mask = (Integer) Host.field(rule, "e");
        List<String> days = new ArrayList<>();
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < 7; i++) if ((mask & (1 << i)) != 0) days.add(names[i]);
        result = "每周" + String.join("、", days);
        break;
      case "MONTHLY":
        result = "每月 " + Host.field(rule, "f") + " 日";
        break;
      default:
        Object date = Host.field(rule, "d");
        result = "一次性 " + (date == null ? "" : date);
    }
    return result + " " + time;
  }

  Object taskTools() {
    Object tools = Host.services.get("taskTools");
    if (tools == null) throw new IllegalStateException("原生定时任务服务尚未就绪，请先打开一次小溪对话");
    return tools;
  }

  void nativeEdit(Object task) {
    boolean creating = task == null;
    String id = creating ? "" : Host.text(task, "a");
    Object rule = creating ? null : Host.field(task, "d");
    ui.start(creating ? "新建原生任务" : "编辑原生任务", () -> nativeEdit(task));
    EditText title = ui.field("任务标题（不超过 10 个字）", creating ? "" : Host.text(task, "b"), false);
    EditText prompt = ui.field("原生提示词", creating ? "" : Host.text(task, "e"), true);
    EditText description = ui.field("任务简介（不超过 50 个字）", creating ? "" : Host.text(task, "f"), false);
    Spinner repeat =
        ui.choice(
            "重复规则",
            new String[] {"once", "daily", "workday", "holiday", "weekly", "monthly"},
            creating ? "once" : Host.text(rule, "a").toLowerCase(java.util.Locale.ROOT));
    EditText hour =
        ui.field("小时（0–23）", creating ? "9" : String.valueOf(Host.field(rule, "b")), false);
    EditText minute =
        ui.field("分钟（0–59）", creating ? "0" : String.valueOf(Host.field(rule, "c")), false);
    Object date = rule == null ? null : Host.field(rule, "d");
    EditText onceDate =
        ui.field("执行日期（YYYY-MM-DD）", date == null ? "" : String.valueOf(date), false);
    EditText delay = ui.field("延后分钟数（创建后由原生服务计算时间）", "60", false);
    int mask = rule == null ? 0 : (Integer) Host.field(rule, "e");
    List<String> weekdays = new ArrayList<>();
    for (int i = 0; i < 7; i++) if ((mask & (1 << i)) != 0) weekdays.add(String.valueOf(i + 1));
    EditText weekdayField = ui.field("每周日期（1=周一，…，7=周日；逗号分隔）", String.join(",", weekdays), false);
    Object monthDays = rule == null ? null : Host.field(rule, "f");
    EditText monthField =
        ui.field("每月日期（1–31；逗号分隔）", monthDays == null ? "" : join(monthDays), false);
    Switch enabled = ui.toggle("启用原生任务", creating || (Boolean) Host.field(task, "c"), value -> {});
    Switch notify = ui.toggle("完成后提醒", creating || (Boolean) Host.field(task, "g"), value -> {});
    Switch confirm = ui.toggle("执行前确认", !creating && (Boolean) Host.field(task, "h"), value -> {});
    Runnable update =
        () -> {
          String kind = repeat.getSelectedItem().toString();
          ui.container(onceDate)
              .setVisibility(!creating && kind.equals("once") ? View.VISIBLE : View.GONE);
          ui.container(delay)
              .setVisibility(creating && kind.equals("once") ? View.VISIBLE : View.GONE);
          ui.container(weekdayField)
              .setVisibility(kind.equals("weekly") ? View.VISIBLE : View.GONE);
          ui.container(monthField).setVisibility(kind.equals("monthly") ? View.VISIBLE : View.GONE);
          ui.syncChoice(repeat);
        };
    repeat.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long rowId) {
            update.run();
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    update.run();
    ui.note("工作日与节假日使用小溪原生日历。保存通过原生 ScheduledTaskTool 完成，由原生 Scheduler 重新安排系统唤醒。");
    ui.button(
        "保存原生任务",
        () -> {
          Map<String, Object> args = new HashMap<>();
          String kind = repeat.getSelectedItem().toString();
          args.put("title", ui.value(title).trim());
          args.put("prompt", ui.value(prompt).trim());
          args.put("description", ui.value(description).trim());
          args.put("repeat_type", kind);
          args.put("hour", ui.value(hour).trim());
          args.put("minute", ui.value(minute).trim());
          args.put("notify_on_complete", String.valueOf(notify.isChecked()));
          args.put("confirm_before_run", String.valueOf(confirm.isChecked()));
          if (creating && kind.equals("once")) {
            args.remove("repeat_type");
            args.remove("hour");
            args.remove("minute");
            args.put("delay_minutes", ui.value(delay).trim());
          } else if (kind.equals("once")) args.put("once_date", ui.value(onceDate).trim());
          if (kind.equals("weekly")) args.put("weekdays", csv(ui.value(weekdayField)));
          if (kind.equals("monthly")) args.put("month_days", csv(ui.value(monthField)));
          if (!creating) args.put("task_id", id);
          ui.work(
              () -> {
                Object result;
                if (creating) {
                  String session = ui.sid.isEmpty() ? Host.currentSession() : ui.sid;
                  result = Host.await(taskTools(), "C", session, args);
                } else {
                  result = Host.await(taskTools(), "I", args);
                  if (!enabled.isChecked()) Host.await(taskTools(), "H", args);
                }
                return TaskToolScope.resultText(result);
              },
              result -> {
                NativeUi.toast(result.toString());
                ui.back();
              });
        });
  }

  static String join(Object values) {
    if (!(values instanceof Iterable<?>)) return "";
    List<String> parts = new ArrayList<>();
    for (Object value : (Iterable<?>) values) parts.add(String.valueOf(value));
    return String.join(",", parts);
  }

  static List<String> csv(String text) {
    List<String> result = new ArrayList<>();
    for (String value : text.split("[,，\\n]"))
      if (!value.trim().isEmpty()) result.add(value.trim());
    return result;
  }

  void edit(Object task) {
    String id = Host.text(task, "a");
    JSONObject config = Store.get("schedule", id);
    ui.start(Host.text(task, "b"), () -> edit(task));
    ui.note("原生任务：" + id + "\n" + repeatSummary(task) + "\n原生提示词：" + Host.text(task, "e"));
    ui.row("编辑原生任务", "标题、提示词、周期、提醒与启停", () -> nativeEdit(task));
    ui.section("增强执行");
    ui.toggle(
        "启用增强步骤",
        config.optBoolean("enabled"),
        enabled ->
            Store.save("schedule", id, Json.put(Store.get("schedule", id), "enabled", enabled)));
    Spinner overlap =
        ui.choice(
            "同一任务重叠执行", new String[] {"skip", "queue"}, config.optString("overlapPolicy", "skip"));
    Spinner missed =
        ui.choice(
            "错过执行后的策略", new String[] {"native", "run"}, config.optString("missedPolicy", "native"));
    ui.button(
        "保存执行策略",
        () -> {
          Json.put(config, "overlapPolicy", overlap.getSelectedItem().toString());
          Json.put(config, "missedPolicy", missed.getSelectedItem().toString());
          Store.save("schedule", id, config);
          ui.saved();
        });
    ui.row("任务模型", modelName(config.optJSONObject("model")), () -> model(task, -1));
    ui.row("任务工具白名单", toolsName(config.optJSONArray("tools")), () -> tools(task, -1));
    ui.section("增强步骤");
    JSONArray steps = steps(config);
    for (int i = 0; i < steps.length(); i++) {
      int index = i;
      JSONObject step = steps.optJSONObject(i);
      ui.row(
          (i + 1) + " · " + stepType(step.optString("type")),
          step.optString("text", step.optString("tool")),
          () -> editStep(task, index));
    }
    ui.button("添加 Agent 步骤", () -> editStep(task, steps.length(), "agent"));
    ui.button("添加固定工具步骤", () -> editStep(task, steps.length(), "tool"));
    ui.button("添加 MCP 步骤", () -> editStep(task, steps.length(), "mcp"));
    ui.button("添加 TTS 步骤", () -> editStep(task, steps.length(), "tts"));
    ui.button("添加通知步骤", () -> editStep(task, steps.length(), "notification"));
    ui.row("此任务执行历史", "保留的运行快照与逐步输出", () -> history(id));
    ui.row(
        "清除增强配置",
        "恢复原任务提示词、模型与工具，不删除原生定时任务",
        () -> {
          Store.delete("schedule", id);
          NativeUi.toast("已恢复原生执行内容");
          ui.back();
        });
  }

  static JSONArray steps(JSONObject config) {
    JSONArray steps = config.optJSONArray("steps");
    return steps == null ? new JSONArray() : steps;
  }

  static String stepType(String type) {
    switch (type) {
      case "agent":
        return "Agent 提示词";
      case "tool":
        return "固定工具";
      case "mcp":
        return "MCP 工具";
      case "tts":
        return "TTS";
      case "notification":
        return "通知";
      default:
        return type;
    }
  }

  void editStep(Object task, int index) {
    editStep(task, index, "agent");
  }

  void editStep(Object task, int index, String initialType) {
    String id = Host.text(task, "a");
    JSONObject config = Store.get("schedule", id);
    JSONArray steps = steps(config);
    if (index < 0 || index > steps.length()) {
      ui.back();
      return;
    }
    boolean adding = index == steps.length();
    JSONObject step = adding ? Json.o("type", initialType) : steps.optJSONObject(index);
    ui.start("编辑" + stepType(step.optString("type")), () -> editStep(task, index, initialType));
    Spinner type =
        ui.choice(
            "步骤类型",
            new String[] {"agent", "tool", "mcp", "tts", "notification"},
            step.optString("type", "agent"));
    EditText text = ui.field("文本（可用 ${previous} 引用上一步）", step.optString("text"), true);
    EditText tool = ui.field("工具名称", step.optString("tool"), false);
    EditText server = ui.field("MCP serverId", step.optString("serverId"), false);
    EditText arguments =
        ui.field(
            "工具参数 JSON（支持 ${previous}）",
            step.optJSONObject("arguments") == null
                ? "{}"
                : Json.pretty(step.optJSONObject("arguments")),
            true);
    EditText prompt = ui.field("Agent Prompt 预设 ID（可留空）", step.optString("promptPreset"), false);
    EditText tts = ui.field("TTS 预设 ID（可留空）", step.optString("ttsPreset"), false);
    JSONObject ownModel = step.optJSONObject("model");
    View model =
        ui.actionRow(ownModel == null ? "此步骤模型（跟随任务）" : "此步骤模型（已覆盖）", () -> model(task, index));
    JSONArray ownTools = step.optJSONArray("tools");
    View tools =
        ui.actionRow(
            ownTools == null ? "此步骤工具白名单（跟随任务）" : "此步骤工具白名单（已覆盖）", () -> tools(task, index));
    Runnable update =
        () -> {
          String kind = type.getSelectedItem().toString();
          boolean fixedTool = kind.equals("tool"), mcp = kind.equals("mcp");
          ui.container(text).setVisibility(fixedTool || mcp ? View.GONE : View.VISIBLE);
          ui.container(tool).setVisibility(fixedTool || mcp ? View.VISIBLE : View.GONE);
          ui.container(server).setVisibility(mcp ? View.VISIBLE : View.GONE);
          ui.container(arguments).setVisibility(fixedTool || mcp ? View.VISIBLE : View.GONE);
          ui.container(prompt).setVisibility(kind.equals("agent") ? View.VISIBLE : View.GONE);
          ui.container(tts).setVisibility(kind.equals("tts") ? View.VISIBLE : View.GONE);
          model.setVisibility(kind.equals("agent") ? View.VISIBLE : View.GONE);
          tools.setVisibility(kind.equals("agent") || fixedTool ? View.VISIBLE : View.GONE);
          ui.syncChoice(type);
        };
    type.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long rowId) {
            update.run();
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    update.run();
    ui.button(
        "保存步骤",
        () -> {
          JSONObject next = Json.obj(step.toString());
          String selected = type.getSelectedItem().toString();
          Json.put(next, "type", selected);
          Json.put(next, "text", ui.value(text));
          Json.put(next, "tool", ui.value(tool).trim());
          Json.put(next, "serverId", ui.value(server).trim());
          Json.put(next, "arguments", Json.obj(ui.value(arguments)));
          Json.put(next, "promptPreset", ui.value(prompt).trim());
          Json.put(next, "ttsPreset", ui.value(tts).trim());
          if ((selected.equals("tool") || selected.equals("mcp"))
              && next.optString("tool").isEmpty()) throw new IllegalArgumentException("请填写工具名称");
          if (selected.equals("mcp") && next.optString("serverId").isEmpty())
            throw new IllegalArgumentException("请填写 MCP serverId");
          if (adding) {
            steps.put(next);
            Json.put(config, "steps", steps);
          } else {
            JSONArray updated = new JSONArray();
            for (int i = 0; i < steps.length(); i++) updated.put(i == index ? next : steps.opt(i));
            Json.put(config, "steps", updated);
          }
          Store.save("schedule", id, config);
          ui.saved();
        });
    if (!adding) {
      if (index > 0) ui.button("上移", () -> moveStep(task, index, index - 1));
      if (index + 1 < steps.length()) ui.button("下移", () -> moveStep(task, index, index + 1));
      ui.button(
          "删除步骤",
          () -> {
            JSONArray next = new JSONArray();
            for (int i = 0; i < steps.length(); i++) if (i != index) next.put(steps.opt(i));
            Store.save("schedule", id, Json.put(config, "steps", next));
            ui.back();
          });
    }
  }

  static String modelName(JSONObject model) {
    return model == null
        ? "跟随聊天模型"
        : model.optString("model") + " · " + model.optString("endpoint");
  }

  void model(Object task, int stepIndex) {
    String id = Host.text(task, "a");
    JSONObject config = Store.get("schedule", id);
    JSONObject step = stepIndex < 0 ? null : steps(config).optJSONObject(stepIndex);
    JSONObject initial = step == null ? config.optJSONObject("model") : step.optJSONObject("model");
    if (initial == null) initial = config.optJSONObject("model");
    Profile current = initial == null ? MainHook.profile() : new Profile(initial, "");
    ui.start("任务模型", () -> model(task, stepIndex));
    EditText endpoint = ui.field("API 地址", current.endpoint, false);
    EditText model = ui.field("模型 ID", current.model, false);
    EditText key =
        ui.field("API Key（同聊天服务可留空继承）", initial == null ? "" : initial.optString("apiKey"), false);
    key.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
    Spinner format =
        ui.choice(
            "思考参数格式",
            new String[] {
              "auto",
              "deepseek",
              "reasoning_effort",
              "thinking_type",
              "enable_thinking",
              "none",
              "native"
            },
            current.format);
    Spinner effort = ui.choice("思考等级", Reasoning.optionIds(current), current.effort);
    EditText budget = ui.field("思考预算（0 沿用）", String.valueOf(current.budget), false);
    EditText window = ui.field("上下文窗口（0 沿用）", String.valueOf(current.window), false);
    EditText output = ui.field("最大输出（0 沿用）", String.valueOf(current.output), false);
    Spinner outputField =
        ui.choice(
            "输出字段", new String[] {"max_tokens", "max_completion_tokens"}, current.outputField);
    EditText extra = ui.field("扩展参数 JSON", current.extra, true);
    ui.note("不同服务必须填写 API Key。思考等级使用固定预设；任务执行时固定本轮模型快照，不修改聊天配置。");
    ui.button(
        "保存模型覆盖",
        () -> {
          JSONObject next =
              Json.o(
                  "provider",
                  "openai",
                  "enabled",
                  true,
                  "endpoint",
                  ui.value(endpoint).trim(),
                  "model",
                  ui.value(model).trim(),
                  "format",
                  format.getSelectedItem().toString(),
                  "effort",
                  effort.getSelectedItem().toString(),
                  "budget",
                  Integer.parseInt(ui.value(budget)),
                  "window",
                  Integer.parseInt(ui.value(window)),
                  "output",
                  Integer.parseInt(ui.value(output)),
                  "outputField",
                  outputField.getSelectedItem().toString(),
                  "extra",
                  ui.value(extra).trim());
          if (!ui.value(key).trim().isEmpty()) Json.put(next, "apiKey", ui.value(key).trim());
          if (!next.optString("extra").isEmpty()) Json.obj(next.optString("extra"));
          if (next.optString("endpoint").isEmpty() || next.optString("model").isEmpty())
            throw new IllegalArgumentException("请填写 API 地址和模型 ID");
          if (next.optInt("budget") < 0 || next.optInt("window") < 0 || next.optInt("output") < 0)
            throw new IllegalArgumentException("预算和窗口不能为负数");
          if (step == null) Json.put(config, "model", next);
          else Json.put(step, "model", next);
          Store.save("schedule", id, config);
          ui.saved();
        });
    ui.button(
        "清除此页模型覆盖",
        () -> {
          if (step == null) config.remove("model");
          else step.remove("model");
          Store.save("schedule", id, config);
          ui.back();
        });
  }

  static String toolsName(JSONArray tools) {
    return tools == null ? "不限制原生工具" : String.join("、", Json.strings(tools));
  }

  void tools(Object task, int stepIndex) {
    String id = Host.text(task, "a");
    JSONObject config = Store.get("schedule", id);
    JSONObject step = stepIndex < 0 ? null : steps(config).optJSONObject(stepIndex);
    JSONArray initial = step == null ? config.optJSONArray("tools") : step.optJSONArray("tools");
    if (initial == null) initial = config.optJSONArray("tools");
    ui.start("任务工具白名单", () -> tools(task, stepIndex));
    EditText tools =
        ui.field(
            "允许的原生工具名称（每行一个，留空不限制）",
            initial == null ? "" : String.join("\n", Json.strings(initial)),
            true);
    ui.note("白名单同时限制 Agent 可见工具和实际调用。固定工具步骤也必须列入允许范围；使用 invoke_tool 时代理目标同样检查。");
    ui.button(
        "保存工具白名单",
        () -> {
          JSONArray allowed = new JSONArray();
          for (String name : csv(ui.value(tools))) allowed.put(name);
          if (allowed.length() == 0) {
            if (step == null) config.remove("tools");
            else step.remove("tools");
          } else if (step == null) Json.put(config, "tools", allowed);
          else Json.put(step, "tools", allowed);
          Store.save("schedule", id, config);
          ui.saved();
        });
  }

  void moveStep(Object task, int from, int to) {
    String id = Host.text(task, "a");
    JSONObject config = Store.get("schedule", id);
    JSONArray steps = steps(config), next = new JSONArray();
    for (int i = 0; i < steps.length(); i++)
      next.put(i == from ? steps.opt(to) : i == to ? steps.opt(from) : steps.opt(i));
    Store.save("schedule", id, Json.put(config, "steps", next));
    ui.refresh(() -> edit(task));
  }

  void history() {
    history("");
  }

  void history(String taskId) {
    ui.start(taskId.isEmpty() ? "增强执行历史" : "任务执行历史", () -> history(taskId));
    JSONArray records = Store.list("scheduleRun"), ordered = new JSONArray();
    for (int i = records.length() - 1; i >= 0; i--) {
      JSONObject record = records.optJSONObject(i);
      if (!taskId.isEmpty() && !record.optString("taskId").equals(taskId)) continue;
      ordered.put(record);
    }
    ui.pagedList(
        ui.content,
        "scheduleRuns",
        "暂无增强步骤执行记录。原生任务的其他执行历史仍由小溪保存。",
        ordered,
        0,
        row -> {
          JSONArray outputs = row.optJSONArray("outputs");
          return new String[] {
            row.optString("taskId"),
            row.optString("id") + "\n" + (outputs == null ? 0 : outputs.length()) + " 个已完成步骤"
          };
        },
        row -> ui.textPage("执行快照", Json.pretty(row)));
  }
}
