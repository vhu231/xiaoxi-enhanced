package io.github.yuan.xiaoxibyok;

import android.view.View;
import android.widget.*;
import org.json.*;

final class TtsUi {
  final NativeUi ui;

  TtsUi(NativeUi ui) {
    this.ui = ui;
  }

  void show() {
    ui.start("语音与 TTS", this::show);
    JSONObject saved = Store.get("tts", "default");
    Spinner provider =
        ui.choice(
            "语音来源",
            new String[] {"native", "vocu", "custom"},
            saved.optString("provider", "native"));
    EditText endpoint =
        ui.field("Vocu API 地址", saved.optString("endpoint", "https://v1.vocu.studio/api"), false);
    EditText key = ui.field("Vocu API Key", saved.optString("key"), false);
    key.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
    EditText model = ui.field("模型", saved.optString("model", "v4.0"), false);
    EditText voice = ui.field("角色 ID", saved.optString("voiceId"), false);
    EditText style = ui.field("风格 promptId", saved.optString("promptId"), false);
    EditText language = ui.field("语言", saved.optString("language", "auto"), false);
    EditText speed = ui.field("播放语速倍率", String.valueOf(saved.optDouble("speed", 1)), false);
    EditText pitch = ui.field("播放音调倍率", String.valueOf(saved.optDouble("pitch", 1)), false);
    Spinner reference =
        ui.choice(
            "V4 reference_mode",
            new String[] {"balanced", "expressive", "similarity"},
            saved.optString("reference_mode", "balanced"));
    java.util.function.Supplier<JSONObject> draft =
        () ->
            Json.o(
                "provider",
                provider.getSelectedItem().toString(),
                "endpoint",
                ui.value(endpoint).trim(),
                "key",
                ui.value(key).trim(),
                "model",
                ui.value(model).trim(),
                "voiceId",
                ui.value(voice).trim(),
                "promptId",
                ui.value(style).trim(),
                "language",
                ui.value(language).trim(),
                "speed",
                Double.parseDouble(ui.value(speed)),
                "pitch",
                Double.parseDouble(ui.value(pitch)),
                "reference_mode",
                reference.getSelectedItem().toString());
    ui.note("Vocu 显式使用流式生成，自定义 API 使用独立模板配置，均复用小溪播放队列。原生音色仍在小溪原有语音页选择；语速和音调标为本地播放效果。");
    ui.row("自定义 TTS API", "请求模板、原始音频、JSON、SSE 与异步任务", this::custom);
    View models =
        ui.actionRow(
            "查询模型目录",
            () -> {
              JSONObject config = draft.get();
              ui.work(
                  () -> {
                    try (VocuApi api = api(config)) {
                      return api.models();
                    }
                  },
                  result -> ui.textPage("Vocu 模型与能力", Json.pretty(result)));
            });
    View voices =
        ui.actionRow(
            "我的角色与风格",
            () -> {
              JSONObject config = draft.get();
              ui.work(
                  () -> {
                    try (VocuApi api = api(config)) {
                      return api.voices();
                    }
                  },
                  result -> {
                    JSONObject response = (JSONObject) result;
                    roles(response.optJSONArray("data"), config);
                  });
            });
    View market = ui.actionRow("Vocu 角色市场", () -> market(draft.get(), 0));
    Runnable updateProvider =
        () -> {
          boolean vocu = provider.getSelectedItem().equals("vocu");
          boolean custom = provider.getSelectedItem().equals("custom");
          for (EditText item : new EditText[] {endpoint, key, model, voice, style})
            ui.container(item).setVisibility(vocu ? View.VISIBLE : View.GONE);
          ui.container(language).setVisibility(vocu || custom ? View.VISIBLE : View.GONE);
          ui.container(reference).setVisibility(vocu ? View.VISIBLE : View.GONE);
          models.setVisibility(vocu ? View.VISIBLE : View.GONE);
          voices.setVisibility(vocu ? View.VISIBLE : View.GONE);
          market.setVisibility(vocu ? View.VISIBLE : View.GONE);
          ui.syncChoice(provider);
        };
    provider.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            updateProvider.run();
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    updateProvider.run();
    ui.section("播放与生成");
    ui.button(
        "保存语音配置",
        () -> {
          JSONObject config = draft.get();
          if (config.optString("provider").equals("vocu") && config.optString("voiceId").isEmpty())
            throw new IllegalArgumentException("请选择或填写 Vocu 角色 ID");
          if (config.optDouble("speed", 1) <= 0 || config.optDouble("pitch", 1) <= 0)
            throw new IllegalArgumentException("语速和音调倍率必须大于 0");
          Store.save("tts", "default", config);
          NativeUi.toast("语音配置已保存，下次合成生效");
        });
    EditText sample = ui.field("试听文本", "你好，我是小溪。", true);
    ui.button(
        "使用已保存配置试听",
        () -> {
          String sampleText = ui.value(sample);
          ui.work(
              () -> {
                Object config = TtsPresets.nativeConfig(ui.sid, "");
                return Host.await(
                    Host.services.get("ttsPlayback"), "g", sampleText, config, "xiaoxi-enhance");
              },
              result -> NativeUi.toast("已交给原生播放队列"));
        });
    ui.button("停止播放", () -> Host.call(Host.services.get("ttsPlayback"), "i", "xiaoxi-enhance"));
    ui.row("生成记录", "任务、请求 ID、音频地址与错误", this::records);
    ui.row("长文与多角色生成", "按段指定角色、风格和模型", this::longForm);
  }

  void custom() {
    ui.start("自定义 TTS API", this::custom);
    JSONObject saved = Store.get("ttsCustom", "default");
    EditText endpoint = ui.field("API 地址", saved.optString("endpoint"), false);
    Spinner method =
        ui.choice("请求方法", new String[] {"POST", "GET"}, saved.optString("method", "POST"));
    EditText headers =
        ui.field(
            "请求头 JSON",
            saved.optJSONObject("headers") == null
                ? "{}"
                : Json.pretty(saved.optJSONObject("headers")),
            true);
    EditText body =
        ui.field(
            "请求体 JSON 模板",
            saved.optJSONObject("body") == null
                ? Json.pretty(Json.o("text", "${text}", "voice", "${voice}", "model", "${model}"))
                : Json.pretty(saved.optJSONObject("body")),
            true);
    Spinner response =
        ui.choice(
            "响应类型",
            new String[] {"audio", "json", "sse", "async"},
            saved.optString("responseType", "audio"));
    EditText audioPath = ui.field("音频 URL / Base64 字段路径", saved.optString("audioPath"), false);
    EditText sampleRate =
        ui.field("SSE PCM 采样率", String.valueOf(saved.optInt("sampleRate", 24000)), false);
    EditText errorPath = ui.field("SSE 错误字段路径（可留空）", saved.optString("errorPath"), false);
    EditText taskIdPath = ui.field("异步任务 ID 字段路径", saved.optString("taskIdPath"), false);
    EditText pollUrl = ui.field("异步查询地址（${id} 占位）", saved.optString("pollUrl"), false);
    EditText statusPath = ui.field("异步状态字段路径", saved.optString("statusPath"), false);
    EditText success = ui.field("成功状态值", saved.optString("successStatus", "completed"), false);
    EditText failure = ui.field("失败状态值", saved.optString("failureStatus", "failed"), false);
    EditText pollMs = ui.field("查询间隔 ms", String.valueOf(saved.optLong("pollMs", 2000)), false);
    Runnable updateResponse =
        () -> {
          String kind = response.getSelectedItem().toString();
          ui.container(audioPath).setVisibility(kind.equals("audio") ? View.GONE : View.VISIBLE);
          ui.container(sampleRate).setVisibility(kind.equals("sse") ? View.VISIBLE : View.GONE);
          ui.container(errorPath).setVisibility(kind.equals("sse") ? View.VISIBLE : View.GONE);
          for (EditText item :
              new EditText[] {taskIdPath, pollUrl, statusPath, success, failure, pollMs})
            ui.container(item).setVisibility(kind.equals("async") ? View.VISIBLE : View.GONE);
          ui.syncChoice(response);
        };
    response.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            updateResponse.run();
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    updateResponse.run();
    ui.note(
        "模板变量仅支持 ${text}、${voice}、${model}、${language}。audio 为原始音频；json 从 audioPath 读取 URL；sse 从"
            + " audioPath 读取 Base64 PCM；async 轮询后读取音频 URL。复杂私有协议需要独立适配器。");
    ui.button(
        "保存自定义 API",
        () -> {
          JSONObject config =
              Json.o(
                  "endpoint",
                  ui.value(endpoint).trim(),
                  "method",
                  method.getSelectedItem().toString(),
                  "headers",
                  Json.obj(ui.value(headers)),
                  "body",
                  Json.obj(ui.value(body)),
                  "responseType",
                  response.getSelectedItem().toString(),
                  "audioPath",
                  ui.value(audioPath).trim(),
                  "sampleRate",
                  Integer.parseInt(ui.value(sampleRate)),
                  "errorPath",
                  ui.value(errorPath).trim(),
                  "taskIdPath",
                  ui.value(taskIdPath).trim(),
                  "pollUrl",
                  ui.value(pollUrl).trim(),
                  "statusPath",
                  ui.value(statusPath).trim(),
                  "successStatus",
                  ui.value(success),
                  "failureStatus",
                  ui.value(failure),
                  "pollMs",
                  Long.parseLong(ui.value(pollMs)));
          if (config.optString("endpoint").isEmpty())
            throw new IllegalArgumentException("请填写 API 地址");
          String type = config.optString("responseType");
          if (!type.equals("audio") && config.optString("audioPath").isEmpty())
            throw new IllegalArgumentException("此响应类型需要 audioPath");
          if (type.equals("async")
              && (config.optString("taskIdPath").isEmpty()
                  || config.optString("pollUrl").isEmpty()
                  || config.optString("statusPath").isEmpty()))
            throw new IllegalArgumentException("异步任务需要 taskIdPath、pollUrl 和 statusPath");
          Store.save("ttsCustom", "default", config);
          ui.saved();
        });
    ui.note("保存后返回“语音与 TTS”，将语音来源选为 custom 并保存，即可用于下一次合成。");
  }

  void market(JSONObject config, int offset) {
    ui.start("Vocu 角色市场", () -> market(config, offset));
    android.widget.LinearLayout target = ui.content;
    ui.work(
        () -> {
          try (VocuApi api = api(config)) {
            return api.market(offset, 30);
          }
        },
        result -> {
          if (ui.content != target) return;
          JSONArray list = ((JSONObject) result).optJSONArray("data");
          for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject role = list.optJSONObject(i);
            ui.row(
                role.optString("name"),
                role.optString("description"),
                () -> marketRole(role, config));
          }
          if (list == null || list.length() == 0) ui.note("暂无角色");
          if (list != null && list.length() == 30)
            ui.button("下一页", () -> market(config, offset + 30));
        });
  }

  void marketRole(JSONObject role, JSONObject config) {
    ui.start("市场角色", () -> marketRole(role, config));
    ui.note(role.optString("description"));
    ui.button(
        "添加到我的角色",
        () ->
            ui.work(
                () -> {
                  try (VocuApi api = api(config)) {
                    api.pull(role.optString("id"));
                    return api.voice(role.optString("id"));
                  }
                },
                added -> styles(((JSONObject) added).optJSONObject("data"), config)));
  }

  void longForm() {
    ui.start("长文与多角色生成", this::longForm);
    JSONObject config = Store.get("tts", "default");
    EditText content =
        ui.field(
            "内容段 JSON 数组",
            Json.pretty(
                new JSONArray()
                    .put(
                        Json.o(
                            "type",
                            "text",
                            "voiceId",
                            config.optString("voiceId"),
                            "model",
                            config.optString("model", "v4.0"),
                            "text",
                            "在此填写第一段内容"))),
            true);
    ui.note("每段可分别设置 voiceId、promptId、model、text。提交后由服务端生成，退出页面不取消任务。");
    ui.button(
        "提交生成任务",
        () -> {
          JSONArray segments = Json.arr(ui.value(content));
          ui.work(
              () -> VocuTasks.submit(config, segments, ui.sid),
              result -> audioDetail((JSONObject) result));
        });
  }

  static VocuApi api(JSONObject config) {
    return new VocuApi(config.optString("endpoint"), config.optString("key"));
  }

  void roles(JSONArray roles, JSONObject draft) {
    ui.start("我的 Vocu 角色", () -> roles(roles, draft));
    if (roles == null) {
      ui.note("响应没有角色数组");
      return;
    }
    for (int i = 0; i < roles.length(); i++) {
      JSONObject role = roles.optJSONObject(i);
      ui.row(
          role.optString("name", role.optString("id")),
          role.optString("status"),
          () -> styles(role, draft));
    }
    ui.note("选中角色或风格后会保存到语音配置；原生与 Vocu 来源选择保持原值。");
  }

  void styles(JSONObject role, JSONObject draft) {
    ui.start(role.optString("name", "角色风格"), () -> styles(role, draft));
    ui.button("使用此角色默认风格", () -> select(role.optString("id"), "", draft));
    JSONObject metadata = role.optJSONObject("metadata");
    JSONArray prompts = metadata == null ? null : metadata.optJSONArray("prompts");
    for (int i = 0; prompts != null && i < prompts.length(); i++) {
      JSONObject prompt = prompts.optJSONObject(i);
      ui.row(
          prompt.optString("name", prompt.optString("id")),
          prompt.optString("description"),
          () -> select(role.optString("id"), prompt.optString("id"), draft));
    }
    ui.button("查看角色原始数据", () -> ui.textPage("角色详情", Json.pretty(role)));
  }

  void select(String voice, String style, JSONObject draft) {
    Json.put(draft, "voiceId", voice);
    Json.put(draft, "promptId", style);
    Store.save("tts", "default", draft);
    ui.saved();
  }

  void records() {
    VocuTasks.resume();
    ui.start("语音生成记录", this::records);
    JSONArray records = Store.list("audio"), recent = new JSONArray();
    for (int i = records.length() - 1; i >= 0; i--) recent.put(records.opt(i));
    ui.pagedList(
        ui.content,
        "audioRecords",
        "暂无语音生成记录",
        recent,
        0,
        row ->
            new String[] {
              row.optString("text"), row.optString("status") + " · " + row.optString("model")
            },
        this::audioDetail);
  }

  void audioDetail(JSONObject record) {
    ui.start("语音生成详情", () -> audioDetail(record));
    ui.note(record.optString("text"));
    ui.row("状态", record.optString("status"), null);
    JSONObject result = record.optJSONObject("result");
    if (result != null) {
      JSONObject metadata = result.optJSONObject("metadata");
      JSONObject files = metadata == null ? result : metadata;
      String audio = files.optString("audio"),
          subtitle = files.optString("srtUrl", files.optString("srt"));
      if (!audio.isEmpty())
        ui.button("下载并导出原始音频", () -> exportUrl(record.optString("id"), audio, false));
      if (!subtitle.isEmpty())
        ui.button("下载并导出字幕", () -> exportUrl(record.optString("id"), subtitle, true));
    }
    ui.button("查看任务与原始响应", () -> ui.textPage("任务详情", Json.pretty(record)));
    if (record.optString("mode").equals("async")) {
      ui.button(
          "继续查询任务状态",
          () -> {
            JSONObject latest = Store.get("audio", record.optString("id"));
            Json.put(latest, "trackingStopped", false);
            Store.save("audio", record.optString("id"), latest);
            VocuTasks.start(record.optString("id"));
            NativeUi.toast("已恢复查询");
          });
      ui.button(
          "停止自动查询",
          () -> {
            JSONObject latest = Store.get("audio", record.optString("id"));
            Json.put(latest, "trackingStopped", true);
            Store.save("audio", record.optString("id"), latest);
            NativeUi.toast("已停止查询，服务端任务继续运行");
          });
    }
    ui.button(
        "刷新记录", () -> ui.refresh(() -> audioDetail(Store.get("audio", record.optString("id")))));
    exportWav(record, "decodedWav", "导出解码后的 WAV", "-decoded.wav");
    exportWav(record, "processedWav", "导出处理后的 WAV", "-processed.wav");
  }

  void exportWav(JSONObject record, String key, String title, String suffix) {
    if (record.optString(key).isEmpty()) return;
    ui.button(
        title,
        () ->
            FilesUi.saveFile(
                ui.a,
                record.optString("id") + suffix,
                "audio/wav",
                new java.io.File(record.optString(key))));
  }

  void exportUrl(String id, String url, boolean subtitle) {
    NativeUi.toast("正在下载");
    ui.work(
        () -> AudioDownload.fetch(url, subtitle),
        value -> {
          AudioDownload.Result result = (AudioDownload.Result) value;
          FilesUi.saveFile(ui.a, id + "-original." + result.extension, result.mime, result.file);
        });
  }
}
