package io.github.yuan.xiaoxibyok;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import de.robv.android.xposed.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.*;
import org.json.*;

final class NativeUi {
  static NativeUi current;
  static Pending pending;
  final Activity a;
  final FrameLayout root;
  final Object backCallback;
  final ArrayDeque<Page> history = new ArrayDeque<>();
  final Map<String, BaseDraft> baseDrafts = new HashMap<>();
  final int nightMode;
  LinearLayout page, content, group;
  ScrollView scroll;
  Page currentPage;
  String sid = "", title = "";
  long pageToken;
  boolean popping, active = true, closing, returnToCaller;

  static final class Pending {
    final String sid, screen;
    boolean returnToCaller;

    Pending(String sid, String screen) {
      this.sid = sid == null ? "" : sid;
      this.screen = screen == null ? "home" : screen;
    }
  }

  static final class Page {
    final Runnable rebuild;
    final String title;
    final Map<String, Object> draft = new HashMap<>();
    int scrollY;
    String focus = "";
    boolean ready, failed;

    Page(Runnable rebuild, String title) {
      this.rebuild = rebuild;
      this.title = title;
    }
  }

  NativeUi(Activity activity) {
    a = activity;
    nightMode = a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    root = new FrameLayout(a);
    root.setClickable(true);
    root.setFocusableInTouchMode(true);
    root.setBackgroundColor(background(a));
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets bars =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
          v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    ((FrameLayout) a.findViewById(android.R.id.content))
        .addView(root, new FrameLayout.LayoutParams(-1, -1));
    root.requestApplyInsets();
    root.requestFocus();
    backCallback =
        Host.make(
            "androidx.activity.D",
            Host.function(
                "kotlin.jvm.functions.Function1",
                args -> {
                  navigateBack();
                  return Host.unit();
                }),
            0);
    Host.call(Host.call(a, "getOnBackPressedDispatcher"), "addCallback", a, backCallback);
  }

  static boolean active() {
    return current != null && current.active;
  }

  static void show(Activity activity) {
    pending = new Pending("", "home");
    requestOpen(activity);
  }

  static void showSession(Activity activity, String session, String screen) {
    pending = new Pending(session, screen);
    requestOpen(activity);
  }

  static void requestOpen(Activity activity) {
    if (pending == null) return;
    if (activity == null) {
      toast("请等待小溪页面就绪后再试");
      return;
    }
    if (!Host.cls("com.flyme.agentos.app.phone.ui.SettingsActivity").isInstance(activity)) {
      pending.returnToCaller = true;
      if (current != null) current.dispose();
      activity.startActivity(
          new Intent(activity, Host.cls("com.flyme.agentos.app.phone.ui.SettingsActivity")));
      return;
    }
    Pending requested = pending;
    pending = null;
    open(activity, requested);
  }

  static void onResume(Activity activity) {
    if (!Host.cls("com.flyme.agentos.app.phone.ui.SettingsActivity").isInstance(activity)) return;
    ComposeBridge.activate(activity);
    if (current != null && current.a == activity) {
      current.refreshConfiguration();
      return;
    }
    if (pending != null) requestOpen(activity);
  }

  static void open(Activity activity, Pending requested) {
    if (activity == null) return;
    if (current != null) current.dispose();
    current = new NativeUi(activity);
    current.sid = requested.sid;
    current.returnToCaller = requested.returnToCaller;
    try {
      current.openScreen(requested.screen);
    } catch (Throwable error) {
      error(error);
    }
  }

  void refreshConfiguration() {
    int mode = a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    if (mode == nightMode || currentPage == null || !currentPage.ready) {
      root.requestApplyInsets();
      return;
    }
    capture(currentPage);
    Page shown = currentPage;
    currentPage = null;
    popping = true;
    try {
      shown.rebuild.run();
    } finally {
      popping = false;
    }
  }

  void openScreen(String screen) {
    if (screen.equals("trajectory")) trajectory(0, "");
    else if (screen.equals("context")) context();
    else if (screen.equals("stats")) statistics();
    else home();
  }

  static void settingsDestroyed(Activity activity) {
    ComposeBridge.routes.remove(activity);
    if (current != null && current.a == activity) current.dispose();
  }

  void dispose() {
    if (closing) return;
    closing = true;
    active = false;
    pageToken++;
    Host.call(backCallback, "remove");
    FilesUi.cancel(a);
    root.clearFocus();
    NativeStyle.cancelAnimations(root);
    ViewGroup parent = (ViewGroup) root.getParent();
    if (parent != null) parent.removeView(root);
    root.removeAllViews();
    history.clear();
    baseDrafts.clear();
    choiceControls.clear();
    page = null;
    content = null;
    group = null;
    scroll = null;
    currentPage = null;
    if (current == this) current = null;
  }

  static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  static boolean dark(Context c) {
    return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
  }

  static int primary(Context c) {
    int color = NativeStyle.color(c, "q");
    return color != 0 ? color : dark(c) ? 0xfff3f3f3 : 0xff1b1b1b;
  }

  static int secondary(Context c) {
    int color = NativeStyle.color(c, "s");
    return color != 0 ? color : dark(c) ? 0xffaaaaaf : 0xff77777c;
  }

  static int accent(Context c) {
    int color = NativeStyle.color(c, "a");
    if (color != 0) return color;
    android.util.TypedValue v = new android.util.TypedValue();
    return c.getTheme().resolveAttribute(android.R.attr.colorAccent, v, true) ? v.data : 0xff3878f6;
  }

  static int background(Context c) {
    int color = NativeStyle.color(c, "n");
    return color != 0 ? color : dark(c) ? 0xff101012 : 0xfff5f5f7;
  }

  static void toast(String s) {
    Toast.makeText(Host.app(), s, Toast.LENGTH_SHORT).show();
  }

  static void error(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null) cause = cause.getCause();
    XposedBridge.log(e);
    if (current != null && current.currentPage != null && !current.currentPage.ready)
      current.currentPage.failed = true;
    String message = cause.getMessage();
    toast("操作未完成：" + (message == null || message.isEmpty() ? cause.toString() : message));
  }

  void work(Callable<?> task, Consumer<Object> done) {
    long token = pageToken;
    Host.work(
        task,
        value -> {
          if (active && token == pageToken) done.accept(value);
        },
        error -> {
          if (active && token == pageToken) error(error);
        });
  }

  boolean showing(LinearLayout target) {
    return active && current == this && content == target;
  }

  void close() {
    if (!active) return;
    boolean finish = returnToCaller;
    dispose();
    if (finish && !a.isFinishing()) a.finish();
  }

  boolean hideKeyboard() {
    WindowInsets insets = root.getRootWindowInsets();
    if (insets == null || !insets.isVisible(WindowInsets.Type.ime())) return false;
    root.requestFocus();
    WindowInsetsController controller = root.getWindowInsetsController();
    if (controller != null) controller.hide(WindowInsets.Type.ime());
    else {
      InputMethodManager keyboard =
          (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
      keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }
    return true;
  }

  void navigateBack() {
    if (active && !hideKeyboard()) back();
  }

  void back() {
    if (!active || currentPage == null || !currentPage.ready) return;
    if (history.size() > 1 && history.peekLast() == currentPage) {
      capture(currentPage);
      history.removeLast();
      Page previous = history.peekLast();
      currentPage = null;
      popping = true;
      try {
        previous.rebuild.run();
      } finally {
        popping = false;
      }
    } else close();
  }

  void transition(LinearLayout previous, boolean forward) {
    if (previous == null) return;
    float width = Math.max(1, root.getWidth());
    NativeStyle.setEnabled(previous, false);
    page.setTranslationX(forward ? width : -width * .5f);
    previous.setTranslationX(0f);
    page.animate().translationX(0f).setDuration(220).start();
    previous
        .animate()
        .translationX(forward ? -width * .5f : width)
        .setDuration(220)
        .withEndAction(
            () -> {
              if (previous.getParent() == root) root.removeView(previous);
            })
        .start();
  }

  final List<Spinner> choiceControls = new ArrayList<>();

  void capture(Page target) {
    if (target == null || page == null || !target.ready) return;
    target.draft.clear();
    target.focus = "";
    saveState(page, "", target);
    for (int i = 0; i < choiceControls.size(); i++)
      target.draft.put("choice#" + i, choiceControls.get(i).getSelectedItemPosition());
    if (scroll != null) target.scrollY = scroll.getScrollY();
  }

  void saveState(View view, String path, Page target) {
    if (view instanceof EditText) {
      EditText text = (EditText) view;
      target.draft.put("text:" + path, text.getText().toString());
      target.draft.put(
          "selection:" + path, new int[] {text.getSelectionStart(), text.getSelectionEnd()});
    } else if (view instanceof Switch) {
      target.draft.put("checked:" + path, ((Switch) view).isChecked());
    }
    if (view.hasFocus()) target.focus = path;
    if (view instanceof ViewGroup) {
      ViewGroup children = (ViewGroup) view;
      for (int i = 0; i < children.getChildCount(); i++)
        saveState(children.getChildAt(i), path + "/" + i, target);
    }
  }

  void restoreState(View view, String path, Page target) {
    if (view instanceof EditText) {
      EditText text = (EditText) view;
      String value = (String) target.draft.get("text:" + path);
      if (value != null) text.setText(value);
      int[] selection = (int[]) target.draft.get("selection:" + path);
      if (selection != null && selection[0] >= 0 && selection[1] >= 0)
        text.setSelection(
            Math.min(selection[0], text.length()), Math.min(selection[1], text.length()));
    } else if (view instanceof Switch) {
      Boolean checked = (Boolean) target.draft.get("checked:" + path);
      if (checked != null) {
        view.setTag("restoring");
        ((Switch) view).setChecked(checked);
        view.setTag(null);
      }
    }
    if (path.equals(target.focus)) view.requestFocus();
    if (view instanceof ViewGroup) {
      ViewGroup children = (ViewGroup) view;
      for (int i = 0; i < children.getChildCount(); i++)
        restoreState(children.getChildAt(i), path + "/" + i, target);
    }
  }

  void restoreChoices(Page target) {
    for (int i = 0; i < choiceControls.size(); i++) {
      Spinner choice = choiceControls.get(i);
      Integer selected = (Integer) target.draft.get("choice#" + i);
      if (selected != null && selected >= 0 && selected < choice.getCount())
        choice.setSelection(selected);
      syncChoice(choice);
    }
  }

  void syncChoice(Spinner choice) {
    if (!(choice.getTag() instanceof ViewGroup)) return;
    ViewGroup row = (ViewGroup) choice.getTag();
    if (row.getChildCount() > 1 && row.getChildAt(1) instanceof TextView)
      ((TextView) row.getChildAt(1)).setText(String.valueOf(choice.getSelectedItem()));
  }

  void start(String name, Runnable rebuild) {
    boolean forward = !popping;
    capture(currentPage);
    LinearLayout previous = page;
    Page next = popping ? history.peekLast() : new Page(rebuild, name);
    if (next == null) next = new Page(rebuild, name);
    long token = ++pageToken;
    title = name;
    next.ready = false;
    currentPage = next;
    choiceControls.clear();
    page = new LinearLayout(a);
    page.setOrientation(1);
    page.setBackgroundColor(background(a));
    page.addView(
        NativeStyle.header(a, name, this::navigateBack), new LinearLayout.LayoutParams(-1, -2));
    scroll = new ScrollView(a);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    content = new LinearLayout(a);
    group = null;
    content.setOrientation(1);
    content.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 24));
    scroll.addView(content);
    page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout createdPage = page;
    ScrollView createdScroll = scroll;
    root.addView(page, new FrameLayout.LayoutParams(-1, -1));
    Page finalNext = next;
    createdScroll.post(
        () -> {
          if (!active || token != pageToken || currentPage != finalNext) return;
          if (finalNext.failed) {
            pageToken++;
            if (history.peekLast() == finalNext) history.removeLast();
            currentPage = null;
            if (history.isEmpty()) {
              close();
              return;
            }
            popping = true;
            try {
              history.peekLast().rebuild.run();
            } finally {
              popping = false;
            }
            return;
          }
          if (forward && history.peekLast() != finalNext) history.addLast(finalNext);
          finalNext.ready = true;
          createdPage.clearFocus();
          restoreState(createdPage, "", finalNext);
          restoreChoices(finalNext);
          createdScroll.scrollTo(0, finalNext.scrollY);
          if (finalNext.focus.isEmpty()) root.requestFocus();
        });
    transition(previous, forward);
  }

  LinearLayout card() {
    group = null;
    LinearLayout l = new LinearLayout(a);
    l.setOrientation(1);
    l.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
    component(NativeStyle.cardView(a, l));
    return l;
  }

  void component(View view) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(a, 10);
    content.addView(view, params);
  }

  void groupComponent(View view) {
    if (group == null) {
      group = NativeStyle.group(a);
      component(group);
    }
    NativeStyle.addToGroup(group, view);
  }

  void section(String text) {
    group = null;
    component(NativeStyle.section(a, text));
  }

  TextView label(LinearLayout l, String text, int size, int color) {
    TextView t = new TextView(a);
    t.setText(text);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setLineSpacing(dp(a, 3), 1);
    l.addView(t);
    return t;
  }

  void note(String text) {
    group = null;
    TextView t = label(content, text, 13, secondary(a));
    t.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 14));
  }

  void row(String name, String subtitle, Runnable action) {
    groupComponent(NativeStyle.row(a, name, subtitle, action));
  }

  void button(String text, Runnable action) {
    actionRow(text, action);
  }

  View actionRow(String text, Runnable action) {
    View row = NativeStyle.button(a, text, action);
    groupComponent(row);
    return row;
  }

  EditText field(String name, String value, boolean multiline) {
    group = null;
    LinearLayout l = new LinearLayout(a);
    l.setOrientation(1);
    l.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
    label(l, name, 13, secondary(a));
    EditText e = new EditText(a);
    e.setText(value);
    e.setTextSize(15);
    e.setTextColor(primary(a));
    e.setHintTextColor(secondary(a));
    e.setSingleLine(!multiline);
    e.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    e.setGravity(multiline ? Gravity.TOP : Gravity.CENTER_VERTICAL);
    e.setPadding(0, dp(a, 8), 0, dp(a, 8));
    e.setBackground(null);
    if (multiline) e.setMinLines(4);
    l.addView(e, new LinearLayout.LayoutParams(-1, -2));
    View wrapper = NativeStyle.cardView(a, l);
    e.setTag(wrapper);
    component(wrapper);
    return e;
  }

  View container(View field) {
    return field.getTag() instanceof View ? (View) field.getTag() : (View) field.getParent();
  }

  Switch toggle(String name, boolean checked, Consumer<Boolean> changed) {
    Switch s = new Switch(a);
    s.setChecked(checked);
    groupComponent(NativeStyle.toggle(a, name, s, changed));
    return s;
  }

  Spinner choice(String name, String[] values, String selected) {
    Spinner s = new Spinner(a);
    s.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, values));
    s.setSelection(Math.max(0, Arrays.asList(values).indexOf(selected)));
    View row = NativeStyle.choice(a, name, s);
    s.setTag(row);
    s.setVisibility(View.INVISIBLE);
    s.setSaveEnabled(false);
    ((LinearLayout) row).addView(s, new LinearLayout.LayoutParams(1, 1));
    choiceControls.add(s);
    groupComponent(row);
    return s;
  }

  String value(EditText e) {
    return e.getText().toString();
  }

  void refresh(Runnable r) {
    capture(currentPage);
    popping = true;
    try {
      r.run();
    } finally {
      popping = false;
    }
  }

  void saved() {
    toast("已保存，下次请求生效");
    back();
  }

  void textPage(String name, String text) {
    start(name, () -> textPage(name, text));
    TextView t = label(card(), text, 14, primary(a));
    t.setTextIsSelectable(true);
    button(
        "复制全文",
        () -> {
          ((android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE))
              .setPrimaryClip(ClipData.newPlainText(name, text));
          toast("已复制");
        });
    button(
        "导出",
        () -> FilesUi.save(a, name + ".txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)));
  }

  void home() {
    start("增强设置", this::home);
    note("小溪增强 4.0");
    section("模型与生成");
    row("模型与生成", MainHook.profile().model, this::model);
    section("Prompt、上下文与记忆");
    row("Prompt 与预设", "基础提示词、规则与生效预览", this::prompts);
    row("上下文管理", "消息、摘要与保留策略", () -> sessions(this::context));
    row("长期记忆与画像", "记忆、标签、表达风格", this::memories);
    section("语音、工具与自动化");
    row("语音与 TTS", "原生语音、自定义 API、Vocu 角色与生成记录", () -> new TtsUi(this).show());
    row("技能与工具", "Skill、MCP 与原生工具包", this::skills);
    row("定时任务", "原生任务、增强步骤与执行历史", () -> new AutomationUi(this).show());
    section("对话与观测");
    row("会话显示与统计", "思考、工具详情与输入框统计", this::display);
    row("轨迹与导出", "请求、工具、压缩与执行时间", () -> sessions(() -> trajectory(0, "")));
  }

  void model() {
    start("模型与生成", this::model);
    Profile p = MainHook.profile();
    note("当前 API：" + p.endpoint);
    Switch enabled = toggle("启用此 API 的增强", p.enabled, b -> {});
    EditText model = field("模型 ID", p.model, false);
    Spinner format =
        choice(
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
            p.format);
    note("聊天输入框按当前模型协议提供可用的思考等级或开关，选择后下一轮请求生效。预算仅用于 enable_thinking 协议。");
    EditText budget = field("思考预算（0 沿用）", String.valueOf(p.budget), false),
        window = field("上下文窗口（0 沿用）", String.valueOf(p.window), false),
        output = field("最大输出（0 沿用）", String.valueOf(p.output), false);
    Spinner outfield =
        choice("输出字段", new String[] {"max_tokens", "max_completion_tokens"}, p.outputField);
    EditText extra = field("扩展参数 JSON", p.extra, true);
    VisionConfig vision = new VisionConfig();
    Runnable[] updateVision = {() -> {}};
    Switch visionEnabled = toggle("覆盖原生读屏接口", vision.enabled, b -> updateVision[0].run());
    note("使用 BYOK 理解截图和图片；原生截图、资源采集和本地 OCR 沿用原流程。模型需要支持图片输入。");
    Switch followChat = toggle("图片理解跟随聊天 BYOK", vision.followChat, b -> updateVision[0].run());
    EditText visionEndpoint = field("独立视觉 API 地址（跟随聊天时不使用）", vision.endpoint, false);
    EditText visionModel = field("独立视觉模型 ID", vision.model, false);
    EditText visionKey = field("独立视觉 API Key", vision.key, false);
    visionKey.setTransformationMethod(
        android.text.method.PasswordTransformationMethod.getInstance());
    Spinner detail =
        choice("图片 detail（留空不发送）", new String[] {"", "auto", "low", "high"}, vision.detail);
    EditText visionOutput = field("视觉输出预算（0 沿用）", String.valueOf(vision.output), false);
    EditText dimension = field("图片最长边（0 保留原图）", String.valueOf(vision.maxDimension), false);
    EditText quality = field("缩放后 JPEG 质量（1–100）", String.valueOf(vision.quality), false);
    EditText visionPrompt = field("视觉任务附加提示词", vision.prompt, true);
    updateVision[0] =
        () -> {
          boolean on = visionEnabled.isChecked(), follow = followChat.isChecked();
          container(followChat).setVisibility(on ? View.VISIBLE : View.GONE);
          for (EditText field : new EditText[] {visionEndpoint, visionModel, visionKey})
            container(field).setVisibility(on && !follow ? View.VISIBLE : View.GONE);
          container(detail).setVisibility(on ? View.VISIBLE : View.GONE);
          for (EditText field : new EditText[] {visionOutput, dimension, quality, visionPrompt})
            container(field).setVisibility(on ? View.VISIBLE : View.GONE);
        };
    updateVision[0].run();
    note(
        "当前已保存路由："
            + (vision.enabled
                ? (vision.followChat
                    ? "聊天 BYOK · " + p.endpoint + " · " + p.model
                    : "独立 BYOK · " + vision.endpoint + " · " + vision.model)
                : "原生 IMAGE_ANALYSIS"));
    button(
        "保存",
        () -> {
          String json = value(extra).trim();
          if (!json.isEmpty()) Json.obj(json);
          int imageSize = Integer.parseInt(value(dimension));
          int imageQuality = Integer.parseInt(value(quality));
          int imageOutput = Integer.parseInt(value(visionOutput));
          if (imageSize < 0 || imageOutput < 0 || imageQuality < 1 || imageQuality > 100)
            throw new IllegalArgumentException("图片尺寸和输出预算不能为负数，JPEG 质量范围为 1–100");
          if (visionEnabled.isChecked()
              && !followChat.isChecked()
              && (value(visionEndpoint).trim().isEmpty() || value(visionModel).trim().isEmpty()))
            throw new IllegalArgumentException("请填写独立视觉 API 地址和模型 ID");
          MainHook.prefs()
              .edit()
              .putBoolean("vision.enabled", visionEnabled.isChecked())
              .putBoolean("vision.followChat", followChat.isChecked())
              .putString("vision.endpoint", value(visionEndpoint).trim())
              .putString("vision.model", value(visionModel).trim())
              .putString("vision.key", value(visionKey).trim())
              .putString("vision.detail", detail.getSelectedItem().toString())
              .putString("vision.prompt", value(visionPrompt))
              .putInt("vision.output", imageOutput)
              .putInt("vision.maxDimension", imageSize)
              .putInt("vision.quality", imageQuality)
              .putBoolean(p.prefix + "enabled", enabled.isChecked())
              .putString(p.prefix + "model", value(model))
              .putString(p.prefix + "format", format.getSelectedItem().toString())
              .putInt(p.prefix + "budget", Integer.parseInt(value(budget)))
              .putInt(p.prefix + "window", Integer.parseInt(value(window)))
              .putInt(p.prefix + "output", Integer.parseInt(value(output)))
              .putString(p.prefix + "outputField", outfield.getSelectedItem().toString())
              .putString(p.prefix + "extra", json)
              .apply();
          saved();
        });
  }

  void prompts() {
    start("Prompt 与预设", this::prompts);
    row("基础提示词", "主聊天设定", () -> base("main"));
    row("快捷语音追加提示词", "屏幕与语音场景", () -> base("voice"));
    row("会话预设", "选择会话单独配置", () -> sessions(this::selectPreset));
    row("预设应用前后预览", "查看片段与有效内容", () -> sessions(this::preview));
    button("新建预设", () -> presetEdit(UUID.randomUUID().toString()));
    button(
        "导入预设",
        () ->
            FilesUi.read(
                a,
                bytes -> {
                  JSONObject p = Json.obj(new String(bytes, StandardCharsets.UTF_8));
                  JSONArray list = p.optJSONArray("presets");
                  if (list == null) list = new JSONArray().put(p);
                  for (int i = 0; i < list.length(); i++) {
                    JSONObject x = list.optJSONObject(i);
                    PromptEngine.apply(new JSONArray(), x.optJSONArray("rules"));
                    Store.save("preset", x.optString("id", UUID.randomUUID().toString()), x);
                  }
                  refresh(this::prompts);
                }));
    button(
        "导出全部预设",
        () ->
            FilesUi.save(
                a,
                "xiaoxi-presets.json",
                "application/json",
                Json.pretty(Json.o("presets", Store.list("preset")))
                    .getBytes(StandardCharsets.UTF_8)));
    JSONArray presets = Store.list("preset");
    for (int i = 0; i < presets.length(); i++) {
      JSONObject item = presets.optJSONObject(i);
      row(
          item.optString("name", "未命名"),
          item.optString("description"),
          () -> presetEdit(item.optString("id")));
    }
  }

  String asset(String name) {
    try (InputStream in = a.getAssets().open("prompts/" + name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toString("UTF-8");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static final class BaseDraft {
    boolean enabled;
    String text, addition = "";

    BaseDraft(JSONObject data, String original) {
      enabled = data.optBoolean("enabled");
      text = data.optString("text", original);
    }
  }

  void watch(EditText text, Consumer<String> changed) {
    text.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {}

          public void afterTextChanged(Editable s) {
            changed.accept(s.toString());
          }
        });
  }

  void base(String target) {
    start(target.equals("main") ? "基础提示词" : "快捷语音提示词", () -> base(target));
    String original =
        asset(target.equals("main") ? "phone-system.md" : "quickvoice-screen-addon.md");
    BaseDraft draft =
        baseDrafts.computeIfAbsent(target, key -> new BaseDraft(Store.get("base", key), original));
    Switch enabled = toggle("使用自定义内容", draft.enabled, b -> draft.enabled = b);
    EditText text = field("提示词", draft.text, true);
    watch(text, value -> draft.text = value);
    note("追加会直接合并到上方提示词中，可继续编辑。点击保存后，下次请求生效。");
    EditText addition = field("追加内容", draft.addition, true);
    watch(addition, value -> draft.addition = value);
    for (boolean prepend : new boolean[] {true, false}) {
      button(
          prepend ? "前置追加到基础提示词" : "后置追加到基础提示词",
          () -> {
            String extra = value(addition);
            if (extra.isEmpty()) return;
            String existing = value(text);
            text.setText(
                existing.isEmpty()
                    ? extra
                    : prepend ? extra + "\n\n" + existing : existing + "\n\n" + extra);
            enabled.setChecked(true);
            addition.setText("");
            toast("已合并到提示词，保存后生效");
          });
    }
    button("查看原文", () -> textPage("原始提示词", original));
    button(
        "从文件导入",
        () ->
            FilesUi.read(
                a,
                b -> {
                  text.setText(new String(b, StandardCharsets.UTF_8));
                  enabled.setChecked(true);
                }));
    button("恢复原文", () -> text.setText(original));
    button(
        "保存",
        () -> {
          Store.save("base", target, Json.o("enabled", enabled.isChecked(), "text", value(text)));
          baseDrafts.remove(target);
          saved();
        });
  }

  void editText(String name, String text, Consumer<String> save) {
    start(name, () -> editText(name, text, save));
    EditText e = field(name, text, true);
    button(
        "保存",
        () -> {
          save.accept(value(e));
          back();
        });
  }

  void presetEdit(String id) {
    JSONObject data = Store.get("preset", id);
    start("编辑预设", () -> presetEdit(id));
    EditText name = field("名称", data.optString("name", "新预设"), false),
        desc = field("说明", data.optString("description"), false);
    Spinner target =
        choice(
            "应用位置",
            new String[] {"完整系统提示词", "仅基础提示词"},
            data.optString("target", "assembled").equals("base") ? "仅基础提示词" : "完整系统提示词");
    note(
        "完整系统提示词：前置追加在所有内容之前，后置追加在记忆、摘要与技能之后。仅基础提示词：只修改基础设定。旧预设保留原来的应用位置。\n"
            + "规则自上而下执行，每条处理上一条的结果；后执行的前置追加会放在更前面。");
    JSONArray rules =
        data.optJSONArray("rules") == null ? new JSONArray() : data.optJSONArray("rules");
    for (int i = 0; i < rules.length(); i++) {
      final int index = i;
      JSONObject rule = rules.optJSONObject(i);
      row(
          (i + 1)
              + ". "
              + ruleLabel(rule.optString("kind"))
              + (rule.optBoolean("enabled", true) ? "" : "（已停用）"),
          rule.optString("text", rule.optString("pattern")),
          () -> {
            savePreset(id, name, desc, target, rules);
            ruleEdit(id, index);
          });
    }
    for (String operation : new String[] {"prepend", "append"}) {
      button(
          ruleLabel(operation),
          () -> {
            savePreset(id, name, desc, target, rules);
            ruleEdit(id, rules.length(), operation);
          });
    }
    button(
        "添加其他规则",
        () -> {
          savePreset(id, name, desc, target, rules);
          ruleEdit(id, rules.length());
        });
    button(
        "保存预设",
        () -> {
          savePreset(id, name, desc, target, rules);
          saved();
        });
    button(
        "设为全局默认",
        () -> {
          savePreset(id, name, desc, target, rules);
          MainHook.prefs().edit().putString("defaultPreset", id).apply();
          toast("已设为默认");
        });
    button(
        "复制预设",
        () -> {
          savePreset(id, name, desc, target, rules);
          String copy = UUID.randomUUID().toString();
          Store.save(
              "preset", copy, Json.put(Store.get("preset", id), "name", value(name) + " 副本"));
          presetEdit(copy);
        });
    button(
        "导出预设",
        () -> {
          savePreset(id, name, desc, target, rules);
          FilesUi.save(
              a,
              "prompt-preset.json",
              "application/json",
              Json.pretty(Json.put(Store.get("preset", id), "id", id))
                  .getBytes(StandardCharsets.UTF_8));
        });
    button(
        "删除预设",
        () -> {
          Store.delete("preset", id);
          back();
        });
  }

  void savePreset(String id, EditText name, EditText desc, Spinner target, JSONArray rules) {
    Store.save(
        "preset",
        id,
        Json.o(
            "name",
            value(name),
            "description",
            value(desc),
            "target",
            target.getSelectedItemPosition() == 0 ? "assembled" : "base",
            "rules",
            rules));
  }

  static final String[] RULE_CODES = {
    "prepend", "append", "insert_before", "insert", "replace", "remove", "substitute", "literal"
  };
  static final String[] RULE_LABELS = {
    "前置追加", "后置追加", "指定片段前插入", "指定片段后插入", "整体替换", "删除指定片段", "正则替换", "普通文本替换"
  };

  static String ruleLabel(String kind) {
    int index = Arrays.asList(RULE_CODES).indexOf(kind);
    return index < 0 ? kind : RULE_LABELS[index];
  }

  void ruleEdit(String id, int index) {
    ruleEdit(id, index, "append");
  }

  void ruleEdit(String id, int index, String initialKind) {
    JSONObject preset = Store.get("preset", id);
    JSONArray rules = preset.optJSONArray("rules");
    JSONObject rule =
        index < rules.length()
            ? rules.optJSONObject(index)
            : Json.o("kind", initialKind, "enabled", true);
    start("预设规则", () -> ruleEdit(id, index, initialKind));
    Switch enabled = toggle("启用此规则", rule.optBoolean("enabled", true), b -> {});
    Spinner kind = choice("追加 / 修改方式", RULE_LABELS, ruleLabel(rule.optString("kind")));
    note("前置追加：你的内容 → 原提示词；后置追加：原提示词 → 你的内容。\n插入按片段 ID 或章节标题匹配；未找到位置时放到末尾。文本按原文保存，不解析花括号变量。");
    EditText text = field("文本", rule.optString("text"), true),
        after = field("目标片段 ID / 章节标题", rule.optString("after"), false),
        sections =
            field(
                "限定片段（逗号分隔）",
                rule.optJSONArray("sections") == null
                    ? ""
                    : String.join(",", Json.strings(rule.optJSONArray("sections"))),
                false),
        pattern = field("匹配文本 / 正则", rule.optString("pattern"), false),
        replacement = field("替换为", rule.optString("replacement"), true),
        flags = field("正则标志", rule.optString("flags", "g"), false);
    Runnable updateFields =
        () -> {
          String op = RULE_CODES[kind.getSelectedItemPosition()];
          container(text)
              .setVisibility(
                  Arrays.asList("prepend", "append", "insert", "insert_before", "replace")
                          .contains(op)
                      ? View.VISIBLE
                      : View.GONE);
          container(after).setVisibility(op.startsWith("insert") ? View.VISIBLE : View.GONE);
          container(sections)
              .setVisibility(
                  op.equals("remove") || op.equals("substitute") ? View.VISIBLE : View.GONE);
          boolean substitute = op.equals("substitute") || op.equals("literal");
          container(pattern).setVisibility(substitute ? View.VISIBLE : View.GONE);
          container(replacement).setVisibility(substitute ? View.VISIBLE : View.GONE);
          container(flags).setVisibility(op.equals("substitute") ? View.VISIBLE : View.GONE);
        };
    kind.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long rowId) {
            updateFields.run();
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    updateFields.run();
    button(
        "保存规则",
        () -> {
          JSONObject next =
              Json.o(
                  "kind",
                  RULE_CODES[kind.getSelectedItemPosition()],
                  "enabled",
                  enabled.isChecked(),
                  "text",
                  value(text),
                  "after",
                  value(after),
                  "sections",
                  new JSONArray(
                      value(sections).isEmpty()
                          ? Collections.emptyList()
                          : Arrays.asList(value(sections).split(","))),
                  "pattern",
                  value(pattern),
                  "replacement",
                  value(replacement),
                  "flags",
                  value(flags));
          JSONArray updated = new JSONArray();
          for (int i = 0; i < rules.length(); i++) updated.put(i == index ? next : rules.opt(i));
          if (index == rules.length()) updated.put(next);
          PromptEngine.apply(PromptEngine.sections("预览"), updated);
          Store.save("preset", id, Json.put(preset, "rules", updated));
          saved();
        });
    if (index < rules.length()) {
      button(
          "下移",
          () -> {
            JSONArray updated = new JSONArray();
            for (int i = 0; i < rules.length(); i++)
              updated.put(
                  i == index && index + 1 < rules.length()
                      ? rules.opt(index + 1)
                      : i == index + 1 ? rules.opt(index) : rules.opt(i));
            Store.save("preset", id, Json.put(preset, "rules", updated));
            back();
          });
      button(
          "上移",
          () -> {
            JSONArray updated = new JSONArray();
            for (int i = 0; i < rules.length(); i++)
              updated.put(
                  i == index - 1
                      ? rules.opt(index)
                      : i == index && index > 0 ? rules.opt(index - 1) : rules.opt(i));
            Store.save("preset", id, Json.put(preset, "rules", updated));
            back();
          });
      button(
          "删除规则",
          () -> {
            JSONArray updated = new JSONArray();
            for (int i = 0; i < rules.length(); i++) if (i != index) updated.put(rules.opt(i));
            Store.save("preset", id, Json.put(preset, "rules", updated));
            back();
          });
    }
  }

  void sessions(Runnable selected) {
    start("选择会话", () -> sessions(selected));
    note("正在读取小溪会话…");
    LinearLayout target = content;
    work(
        Host::sessions,
        result ->
            pagedList(
                target,
                "sessions",
                "",
                (JSONArray) result,
                0,
                row ->
                    new String[] {
                      row.optString("session_type") + " · " + row.optInt("message_count") + " 条消息",
                      row.optString("session_id")
                    },
                row -> {
                  sid = row.optString("session_id");
                  selected.run();
                }));
  }

  void pagedList(
      LinearLayout target,
      String tag,
      String empty,
      JSONArray rows,
      int offset,
      Function<JSONObject, String[]> labels,
      Consumer<JSONObject> selected) {
    if (!showing(target)) return;
    View previous = target.findViewWithTag(tag);
    if (previous != null) target.removeView(previous);
    LinearLayout savedContent = content, savedGroup = group;
    LinearLayout results = new LinearLayout(a);
    results.setOrientation(1);
    results.setTag(tag);
    target.addView(results);
    content = results;
    group = null;
    if (rows.length() == 0) note(empty);
    int end = Math.min(rows.length(), offset + 50);
    for (int i = Math.max(0, offset); i < end; i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row == null) continue;
      String[] text = labels.apply(row);
      row(text[0], text[1], () -> selected.accept(row));
    }
    if (end < rows.length())
      button(
          "显示更多（" + end + " / " + rows.length() + "）",
          () -> pagedList(target, tag, empty, rows, end, labels, selected));
    content = savedContent;
    group = savedGroup;
  }

  void selectPreset() {
    start("会话预设", this::selectPreset);
    note(sid);
    row(
        "跟随全局默认",
        "",
        () -> {
          JSONObject settings = Store.settings(sid);
          settings.remove("preset");
          Store.save("session", sid, settings);
          saved();
        });
    row(
        "原始提示词",
        "不使用预设规则",
        () -> {
          Store.setting(sid, "preset", "");
          saved();
        });
    JSONArray list = Store.list("preset");
    for (int i = 0; i < list.length(); i++) {
      JSONObject p = list.optJSONObject(i);
      row(
          p.optString("name"),
          p.optString("description"),
          () -> {
            Store.setting(sid, "preset", p.optString("id"));
            Store.event(sid, "prompt.selected", p);
            saved();
          });
    }
  }

  void preview() {
    start("提示词生效预览", this::preview);
    JSONObject p = PromptEngine.preset(sid);
    String original = Store.get("baseSnapshot", sid).optString("text", asset("phone-system.md"));
    JSONArray parts =
        p.optString("target", "base").equals("assembled")
            ? Store.get("promptSource", sid).optJSONArray("segments")
            : PromptEngine.sections(original);
    if (parts == null) {
      note("这个会话尚未采集到合并后的 Prompt，请发送一条消息后再查看。");
      return;
    }
    String
        before =
            p.optString("target", "base").equals("assembled")
                ? Store.get("promptSource", sid).optString("rendered", PromptEngine.render(parts))
                : original,
        after =
            p.optString("target", "base").equals("base")
                ? PromptEngine.base(sid, original)
                : PromptEngine.render(
                    PromptEngine.applyPrompt(parts, before, p.optJSONArray("rules")));
    note(
        "修改前约 "
            + PromptEngine.estimate(before)
            + " tokens · 修改后约 "
            + PromptEngine.estimate(after)
            + " tokens");
    row("修改前", "原始片段", () -> textPage("修改前", before));
    row("修改后", "下一次组装使用的规则结果", () -> textPage("修改后", after));
    row("逐行差异", "+ 新增，− 删除", () -> textPage("提示词差异", diff(before, after)));
    row("片段与来源", "", () -> textPage("Prompt 片段", Json.pretty(parts)));
    row(
        "最后一次实际请求",
        "",
        () ->
            textPage(
                "实际请求",
                Json.pretty(Trace.stats(sid).optJSONObject("latest").optJSONObject("request"))));
  }

  void context() {
    start("上下文管理", this::context);
    note(sid);
    row("会话预设", "为这个会话选择 Prompt", this::selectPreset);
    row(
        "有效上下文预览",
        "最近一次组装的消息与基础提示词",
        () -> textPage("有效上下文", Json.pretty(Store.get("contextEffective", sid))));
    row("摘要与保留策略", "", this::summary);
    button("追加上下文", this::addContext);
    row("追加消息管理", "修改、删除或调整顺序", this::additions);
    row("原始上下文快照", "", () -> textPage("原始上下文", Json.pretty(Store.get("contextSource", sid))));
    button(
        "导出会话修改",
        () ->
            FilesUi.save(
                a,
                "context-overrides.json",
                "application/json",
                Json.pretty(Store.settings(sid)).getBytes(StandardCharsets.UTF_8)));
    button(
        "导入会话修改",
        () ->
            FilesUi.read(
                a,
                bytes -> {
                  Store.save(
                      "session",
                      sid,
                      Json.put(
                          Json.obj(new String(bytes, StandardCharsets.UTF_8)), "rebuild", true));
                  refresh(this::context);
                }));
    button(
        "恢复全部上下文修改",
        () -> {
          JSONObject s = Store.settings(sid);
          s.remove("edits");
          s.remove("additions");
          s.remove("summary");
          Json.put(s, "rebuild", true);
          Store.save("session", sid, s);
          toast("已恢复");
        });
    LinearLayout target = content;
    work(
        () -> Host.messages(sid),
        result ->
            pagedList(
                target,
                "sessionMessages",
                "暂无消息",
                (JSONArray) result,
                0,
                row ->
                    new String[] {
                      row.optString("role") + " · " + row.optLong("id"), row.optString("content")
                    },
                this::messageEdit));
  }

  void messageEdit(JSONObject r) {
    String id = r.optString("message_id");
    if (id.isEmpty() || id.equals("null")) id = String.valueOf(r.optLong("timestamp"));
    final String messageId = id;
    JSONObject s = Store.settings(sid), edit = new JSONObject();
    JSONArray edits = s.optJSONArray("edits");
    for (int i = 0; edits != null && i < edits.length(); i++)
      if (edits.optJSONObject(i).optString("messageId").equals(id)) edit = edits.optJSONObject(i);
    final JSONObject initial = edit;
    start("编辑上下文消息", () -> messageEdit(r));
    Switch excluded = toggle("从模型上下文排除此条及其工具组", initial.optBoolean("exclude"), b -> {});
    Switch pinned = toggle("固定到系统上下文", initial.optBoolean("pinned"), b -> {});
    EditText text = field("内容", initial.optString("content", r.optString("content")), true);
    row("原始消息与工具记录", "", () -> textPage("原始消息", Json.pretty(r)));
    button(
        "保存上下文修改",
        () -> {
          ContextEditor.edit(
              sid,
              messageId,
              value(text),
              excluded.isChecked(),
              pinned.isChecked(),
              r.optString("role"));
          saved();
        });
    button(
        "恢复此条",
        () -> {
          JSONArray next = new JSONArray();
          JSONArray old = Store.settings(sid).optJSONArray("edits");
          for (int i = 0; old != null && i < old.length(); i++)
            if (!old.optJSONObject(i).optString("messageId").equals(messageId))
              next.put(old.opt(i));
          Store.setting(sid, "edits", next);
          Store.setting(sid, "rebuild", true);
          saved();
        });
  }

  void addContext() {
    start("追加上下文", this::addContext);
    Spinner role = choice("消息角色", new String[] {"user", "assistant", "system"}, "user");
    EditText text = field("内容", "", true);
    button(
        "保存",
        () -> {
          JSONObject s = Store.settings(sid);
          JSONArray list = s.optJSONArray("additions");
          if (list == null) list = new JSONArray();
          list.put(Json.o("role", role.getSelectedItem(), "content", value(text)));
          Store.setting(sid, "additions", list);
          Store.event(
              sid,
              "context.append",
              Json.o("role", role.getSelectedItem(), "content", value(text)));
          saved();
        });
  }

  void summary() {
    start("摘要与保留策略", this::summary);
    JSONObject s = Store.settings(sid);
    EditText keep = field("保留最近多少轮（0 全部）", String.valueOf(s.optInt("keepTurns")), false);
    toggle("禁用自动压缩", s.optBoolean("noCompression"), b -> Store.setting(sid, "noCompression", b));
    EditText summary = field("自定义摘要", s.optString("summary"), true);
    row(
        "查看原生摘要快照",
        "",
        () ->
            work(
                () ->
                    Host.query(
                        "agentos_memory.db",
                        "SELECT * FROM session_context_summaries WHERE session_id=?",
                        sid),
                x -> textPage("摘要快照", Json.pretty(x))));
    button(
        "保存策略与摘要",
        () -> {
          Store.setting(sid, "keepTurns", Integer.parseInt(value(keep)));
          Store.setting(sid, "summary", value(summary));
          saved();
        });
    button(
        "清除旧摘要并从消息重建",
        () -> {
          JSONObject settings = Store.settings(sid);
          settings.remove("summary");
          Json.put(settings, "rebuild", true);
          Store.save("session", sid, settings);
          Store.event(sid, "context.rebuild", new JSONObject());
          saved();
        });
  }

  void memories() {
    start("长期记忆与画像", this::memories);
    row("用户画像与表达风格", "", this::profiles);
    button("新增记忆", () -> memory(new JSONObject()));
    button(
        "导入记忆 JSON",
        () ->
            FilesUi.read(
                a,
                b ->
                    work(
                        () -> {
                          JSONArray rows = Json.arr(new String(b, StandardCharsets.UTF_8));
                          for (int i = 0; i < rows.length(); i++) {
                            JSONObject r = rows.optJSONObject(i);
                            Capabilities.memorySave(
                                "",
                                r.optString("title"),
                                r.optString("content"),
                                r.optString("tags"));
                          }
                          return "导入完成";
                        },
                        x -> {
                          toast(x.toString());
                          refresh(this::memories);
                        })));
    button(
        "导出全部记忆",
        () ->
            work(
                () ->
                    Host.query(
                        "agentos_memory.db",
                        "SELECT id,title,content,tags,source,timestamp,metadata FROM"
                            + " memory_fragments ORDER BY timestamp DESC"),
                x ->
                    FilesUi.save(
                        a,
                        "xiaoxi-memories.json",
                        "application/json",
                        Json.pretty(x).getBytes(StandardCharsets.UTF_8))));
    EditText search = field("搜索记忆", "", false);
    button("搜索", () -> memoryList(value(search)));
    memoryList("");
  }

  void memoryList(String term) {
    LinearLayout target = content;
    work(
        () ->
            Host.query(
                "agentos_memory.db",
                "SELECT id,title,content,tags,source,timestamp,metadata FROM memory_fragments WHERE"
                    + " content LIKE ? OR title LIKE ? ORDER BY timestamp DESC LIMIT 200",
                "%" + term + "%",
                "%" + term + "%"),
        result ->
            pagedList(
                target,
                "memoryResults",
                "没有找到匹配的记忆",
                (JSONArray) result,
                0,
                row -> new String[] {row.optString("title"), row.optString("content")},
                this::memory));
  }

  void memory(JSONObject data) {
    start("编辑记忆", () -> memory(data));
    EditText title = field("标题", data.optString("title"), false),
        text = field("内容", data.optString("content"), true),
        tags = field("标签（逗号分隔）", data.optString("tags"), false);
    note("来源：" + data.optString("source", "user"));
    button(
        "保存记忆",
        () ->
            work(
                () ->
                    Capabilities.memorySave(
                        data.optString("id"), value(title), value(text), value(tags)),
                x -> {
                  toast("记忆与索引已更新");
                  back();
                }));
    if (data.has("id"))
      button(
          "删除记忆",
          () ->
              work(
                  () -> Capabilities.memoryDelete(data.optString("id")),
                  x -> {
                    toast("已删除");
                    back();
                  }));
  }

  void profiles() {
    start("画像与表达风格", this::profiles);
    LinearLayout target = content;
    work(
        () ->
            Host.query(
                "agentos_profile.db", "SELECT * FROM profile_attributes ORDER BY updated_at DESC"),
        result ->
            pagedList(
                target,
                "profiles",
                "暂无用户画像",
                (JSONArray) result,
                0,
                row ->
                    new String[] {
                      row.optString("category") + " · " + row.optString("key"),
                      row.optString("value_json")
                    },
                row -> profileEdit("profile_attributes", "attribute_id", row)));
    row("表达风格", "语气、详细程度与称呼方式", this::styles);
  }

  void styles() {
    start("表达风格", this::styles);
    LinearLayout target = content;
    work(
        () -> Host.query("agentos_profile.db", "SELECT * FROM speech_styles"),
        result ->
            pagedList(
                target,
                "speechStyles",
                "暂无表达风格",
                (JSONArray) result,
                0,
                row -> new String[] {row.optString("name"), row.optString("tone")},
                row -> profileEdit("speech_styles", "style_id", row)));
  }

  void profileEdit(String table, String idKey, JSONObject data) {
    start("编辑画像", () -> profileEdit(table, idKey, data));
    Map<String, EditText> fields = new LinkedHashMap<>();
    String[] names =
        table.equals("speech_styles")
            ? new String[] {
              "name",
              "tone",
              "verbosity",
              "formality",
              "initiative",
              "address_mode",
              "voice_mode",
              "constraints_json"
            }
            : new String[] {"category", "key", "value_json", "confidence", "status"};
    for (String name : names)
      fields.put(name, field(name, data.optString(name), name.endsWith("json")));
    button(
        "保存",
        () ->
            work(
                () -> {
                  JSONObject changed = Json.obj(data.toString());
                  for (String name : names) Json.put(changed, name, value(fields.get(name)));
                  Object result = Capabilities.profileSave(table, changed);
                  return "已保存";
                },
                x -> {
                  toast(x.toString());
                  back();
                }));
  }

  void skills() {
    start("技能与工具", this::skills);
    row("原生工具包市场", "进入小溪已有的工具管理", () -> nativeRoute("工具箱"));
    row("MCP 连接器", "原生安装与工具配置", this::mcp);
    button(
        "导入 ZIP（可多选）",
        () ->
            FilesUi.pick(
                a,
                "*/*",
                true,
                uris ->
                    work(
                        () -> {
                          StringBuilder result = new StringBuilder();
                          for (android.net.Uri uri : uris)
                            result
                                .append(
                                    Capabilities.importSkill(FilesUi.name(uri), FilesUi.bytes(uri)))
                                .append('\n');
                          return result.toString();
                        },
                        x -> textPage("导入结果", x.toString()))));
    button(
        "从文件夹导入",
        () ->
            FilesUi.tree(
                a,
                bytes ->
                    work(
                        () -> Capabilities.importSkill("folder.zip", bytes),
                        x -> textPage("导入结果", x.toString()))));
    button(
        "导入 SKILL.md",
        () ->
            FilesUi.read(
                a,
                bytes ->
                    work(
                        () ->
                            Capabilities.importSkill(
                                "skill.zip",
                                FilesUi.zip(null, Collections.singletonMap("SKILL.md", bytes))),
                        x -> textPage("导入结果", x.toString()))));
    button("新建 Skill", this::skillCreate);
    LinearLayout target = content;
    work(
        Capabilities::skills,
        result ->
            pagedList(
                target,
                "skills",
                "暂无已安装 Skill",
                (JSONArray) result,
                0,
                row ->
                    new String[] {
                      row.optString("skillId"),
                      row.optString("origin") + " · " + (row.optBoolean("enabled") ? "已启用" : "已停用")
                    },
                this::skill));
  }

  void nativeRoute(String title) {
    Object callback = ComposeBridge.route(a, title);
    if (callback == null) {
      toast("请先进入小溪原生设置，让该入口完成加载");
      return;
    }
    returnToCaller = false;
    close();
    Host.call(callback, "invoke");
  }

  void skillCreate() {
    start("新建 Skill", this::skillCreate);
    EditText name = field("Skill ID", "my-skill", false),
        doc =
            field("SKILL.md", "---\nname: my-skill\ndescription: 描述用途与触发场景\n---\n\n# 使用说明\n", true);
    button(
        "安装",
        () ->
            work(
                () ->
                    Capabilities.importSkill(
                        value(name) + ".zip",
                        FilesUi.zip(
                            null,
                            Collections.singletonMap(
                                "SKILL.md", value(doc).getBytes(StandardCharsets.UTF_8)))),
                x -> textPage("安装结果", x.toString())));
  }

  void skill(JSONObject info) {
    String id = info.optString("skillId");
    File dir = Capabilities.skillDir(id);
    start(id, () -> skill(info));
    note(info.optString("origin") + " · " + info.optString("version"));
    toggle(
        "启用",
        (Boolean) Host.call(Host.services.get("enabled"), "c", id),
        on ->
            work(
                () -> {
                  Capabilities.enable(id, on);
                  return "已更新";
                },
                x -> toast(x.toString())));
    row(
        "编辑 SKILL.md",
        "说明、触发示例、元数据与依赖",
        () ->
            work(
                () ->
                    new String(
                        java.nio.file.Files.readAllBytes(new File(dir, "SKILL.md").toPath()),
                        StandardCharsets.UTF_8),
                x ->
                    editText(
                        "SKILL.md",
                        x.toString(),
                        text ->
                            work(
                                () ->
                                    Capabilities.importSkill(
                                        id + ".zip",
                                        FilesUi.zip(
                                            dir,
                                            Collections.singletonMap(
                                                "SKILL.md",
                                                text.getBytes(StandardCharsets.UTF_8)))),
                                result -> textPage("更新结果", result.toString())))));
    row("文件与脚本", "", () -> skillFiles(dir, dir));
    row("安装与依赖状态", "", () -> dependency(id, info));
    button("复制为自定义 Skill", () -> skillCopy(id, dir));
    button(
        "导出 ZIP",
        () ->
            work(
                () -> FilesUi.zip(dir, Collections.emptyMap()),
                x -> FilesUi.save(a, id + ".zip", "application/zip", (byte[]) x)));
    button(
        "卸载",
        () ->
            work(
                () -> {
                  return Host.await(Host.services.get("uninstaller"), "a", id, "user_requested");
                },
                x -> {
                  toast(x.toString());
                  back();
                }));
  }

  void skillFiles(File rootDir, File dir) {
    start(dir.getName(), () -> skillFiles(rootDir, dir));
    button("新建文件或脚本", () -> newSkillFile(rootDir, dir));
    button(
        "导入文件",
        () ->
            FilesUi.pick(
                a,
                "*/*",
                true,
                uris ->
                    work(
                        () -> {
                          Map<String, byte[]> changes = new LinkedHashMap<>();
                          for (android.net.Uri uri : uris) {
                            String path =
                                rootDir
                                    .toPath()
                                    .relativize(new File(dir, FilesUi.name(uri)).toPath())
                                    .toString()
                                    .replace('\\', '/');
                            changes.put(path, FilesUi.bytes(uri));
                          }
                          return Capabilities.importSkill(
                              rootDir.getName() + ".zip", FilesUi.zip(rootDir, changes));
                        },
                        x -> textPage("更新结果", x.toString()))));
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File file : files) {
      row(
          file.getName(),
          file.isDirectory() ? "文件夹" : file.length() + " bytes",
          () -> {
            if (file.isDirectory()) skillFiles(rootDir, file);
            else
              work(
                  () ->
                      new String(
                          java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
                  x ->
                      editText(
                          file.getName(),
                          x.toString(),
                          text ->
                              work(
                                  () -> {
                                    String path =
                                        rootDir
                                            .toPath()
                                            .relativize(file.toPath())
                                            .toString()
                                            .replace('\\', '/');
                                    return Capabilities.importSkill(
                                        rootDir.getName() + ".zip",
                                        FilesUi.zip(
                                            rootDir,
                                            Collections.singletonMap(
                                                path, text.getBytes(StandardCharsets.UTF_8))));
                                  },
                                  result -> textPage("更新结果", result.toString()))));
          });
    }
  }

  void mcp() {
    new McpUi(this).show();
  }

  void display() {
    start("会话显示与统计", this::display);
    toggle(
        "输入框上方显示统计",
        MainHook.prefs().getBoolean("stats", true),
        b -> MainHook.prefs().edit().putBoolean("stats", b).apply());
    toggle(
        "展开详细思考与工具调用",
        MainHook.prefs().getBoolean("details", true),
        b -> MainHook.prefs().edit().putBoolean("details", b).apply());
    note("统计条分别提供统计详情、轨迹和上下文入口。≈ 表示估算，— 表示服务端未返回。布局开关在重新进入对话时生效。");
    row("会话统计", "", () -> sessions(this::statistics));
  }

  void statistics() {
    start("会话统计", this::statistics);
    JSONObject stats = Trace.stats(sid);
    row("当前请求", Trace.compact(sid), null);
    row(
        "会话累计",
        "输入 "
            + Trace.count(stats, "input")
            + " · 输出 "
            + Trace.count(stats, "output")
            + " · 请求 "
            + stats.optInt("requests"),
        null);
    JSONObject last = stats.optJSONObject("latest");
    row(
        "首 Token 延迟",
        last.optLong("first") > 0
            ? (last.optLong("first") - last.optLong("start")) + " ms"
            : "未取得流式首 Token",
        null);
    row("分轮统计", "", () -> textPage("各轮用量", Json.pretty(stats.optJSONArray("byTurn"))));
    note(
        "输入用量已返回 "
            + stats.optInt("usageReports")
            + " / "
            + stats.optInt("requests")
            + " 次；输出用量已返回 "
            + stats.optInt("outputReports")
            + " 次；缓存用量已返回 "
            + stats.optInt("cacheReports")
            + " 次。累计数值只包含已上报请求。");
    row("统计明细", "", () -> textPage("用量明细", Json.pretty(stats)));
    row("上下文组成", "", () -> textPage("上下文组成", Json.pretty(Store.get("window", sid))));
    row("轨迹", "", () -> trajectory(0, ""));
    row("上下文管理", "", this::context);
  }

  void trajectory(int offset, String filter) {
    start("会话轨迹", () -> trajectory(offset, filter));
    EditText search = field("搜索类型、工具或内容", filter, false);
    button("筛选", () -> refresh(() -> trajectory(0, value(search))));
    JSONArray events = Store.events(sid, 50, offset, filter);
    content.addView(
        new TimelineView(a, events),
        new LinearLayout.LayoutParams(-1, dp(a, Math.max(70, Math.min(10, events.length()) * 30))));
    for (int i = 0; i < events.length(); i++) {
      JSONObject e = events.optJSONObject(i), d = e.optJSONObject("data");
      String hint =
          android.text.format.DateFormat.format("HH:mm:ss", e.optLong("time")).toString()
              + " · "
              + d.optString("status");
      if (d.has("turn")) hint += " · 第 " + d.optInt("turn") + " 轮 / 第 " + d.optInt("step") + " 步";
      row(e.optString("kind"), hint, () -> eventDetail(e));
    }
    if (events.length() == 50) button("更早记录", () -> trajectory(offset + 50, filter));
    button(
        "导出完整轨迹",
        () ->
            FilesUi.save(
                a,
                "xiaoxi-trajectory.json",
                "application/json",
                Json.pretty(Store.events(sid, Integer.MAX_VALUE, 0, ""))
                    .getBytes(StandardCharsets.UTF_8)));
    button(
        "查看原生历史轨迹",
        () ->
            work(
                () ->
                    Host.query(
                        "agentos_telemetry.db",
                        "SELECT * FROM telemetry_events WHERE session_id=? ORDER BY start_unix_ms"
                            + " DESC LIMIT 200",
                        sid),
                x -> textPage("原生轨迹", Json.pretty(x))));
  }

  void eventDetail(JSONObject event) {
    start(event.optString("kind"), () -> eventDetail(event));
    JSONObject d = event.optJSONObject("data");
    if (d.has("thinking"))
      row(
          "思考内容",
          d.optString("thinking").isEmpty() ? "未返回思考内容" : "点击展开全文",
          () -> textPage("思考内容", d.optString("thinking")));
    if (d.has("content")) row("模型回复", "", () -> textPage("模型回复", d.optString("content")));
    if (d.has("request"))
      row("实际请求与 Prompt", "", () -> textPage("请求", Json.pretty(d.optJSONObject("request"))));
    if (d.has("tools"))
      row("工具调用", "", () -> textPage("工具调用", Json.pretty(d.optJSONArray("tools"))));
    row("完整事件", "时间、状态、参数与用量", () -> textPage("事件详情", Json.pretty(event)));
  }

  void additions() {
    start("追加消息管理", this::additions);
    JSONArray list = Store.settings(sid).optJSONArray("additions");
    if (list == null) return;
    for (int i = 0; i < list.length(); i++) {
      int index = i;
      JSONObject item = list.optJSONObject(i);
      row(
          (i + 1) + " · " + item.optString("role"),
          item.optString("content"),
          () -> addition(index));
    }
  }

  void addition(int index) {
    start("编辑追加消息", () -> addition(index));
    JSONArray list = Store.settings(sid).optJSONArray("additions");
    if (list == null || index < 0 || index >= list.length()) {
      note("这条追加消息已不存在");
      button("返回", this::back);
      return;
    }
    JSONObject item = list.optJSONObject(index);
    Spinner role =
        choice("角色", new String[] {"user", "assistant", "system"}, item.optString("role"));
    EditText text = field("内容", item.optString("content"), true);
    button(
        "保存",
        () -> {
          JSONArray next = new JSONArray();
          for (int i = 0; i < list.length(); i++)
            next.put(
                i == index
                    ? Json.o("role", role.getSelectedItem(), "content", value(text))
                    : list.opt(i));
          Store.setting(sid, "additions", next);
          saved();
        });
    if (index > 0)
      button(
          "上移",
          () -> {
            JSONArray next = new JSONArray();
            for (int i = 0; i < list.length(); i++)
              next.put(
                  i == index - 1
                      ? list.opt(index)
                      : i == index ? list.opt(index - 1) : list.opt(i));
            Store.setting(sid, "additions", next);
            back();
          });
    if (index + 1 < list.length())
      button(
          "下移",
          () -> {
            JSONArray next = new JSONArray();
            for (int i = 0; i < list.length(); i++)
              next.put(
                  i == index
                      ? list.opt(index + 1)
                      : i == index + 1 ? list.opt(index) : list.opt(i));
            Store.setting(sid, "additions", next);
            back();
          });
    button(
        "删除",
        () -> {
          JSONArray next = new JSONArray();
          for (int i = 0; i < list.length(); i++) if (i != index) next.put(list.opt(i));
          Store.setting(sid, "additions", next);
          back();
        });
  }

  void dependency(String id, JSONObject info) {
    start("安装与依赖", () -> dependency(id, info));
    row("安装元数据", info.optString("origin"), () -> textPage("安装元数据", Json.pretty(info)));
    Object deps = Host.field(Host.services.get("activation"), "c");
    if (deps != null) {
      row("依赖状态", "", () -> textPage("依赖状态", String.valueOf(Host.call(deps, "d", id))));
      button(
          "安装 / 重新准备依赖",
          () ->
              work(
                  () -> {
                    Object priority =
                        XposedHelpers.getStaticObjectField(
                            Host.cls("com.flyme.agentos.agent.domain.skill.deps.ProvisionPriority"),
                            "BACKGROUND");
                    return Host.await(deps, "r", id, Capabilities.skillDir(id), priority);
                  },
                  x -> textPage("依赖处理结果", x.toString())));
    }
  }

  void newSkillFile(File rootDir, File dir) {
    start("新建文件", () -> newSkillFile(rootDir, dir));
    EditText name = field("相对路径", "scripts/main.py", false), text = field("内容", "", true);
    button(
        "保存到 Skill",
        () ->
            work(
                () -> {
                  String path =
                      rootDir
                          .toPath()
                          .relativize(new File(dir, value(name)).toPath())
                          .toString()
                          .replace('\\', '/');
                  return Capabilities.importSkill(
                      rootDir.getName() + ".zip",
                      FilesUi.zip(
                          rootDir,
                          Collections.singletonMap(
                              path, value(text).getBytes(StandardCharsets.UTF_8))));
                },
                x -> textPage("更新结果", x.toString())));
  }

  void skillCopy(String id, File dir) {
    start("复制为自定义 Skill", () -> skillCopy(id, dir));
    EditText name = field("新 Skill 名称", id.replace(':', '-') + "-custom", false);
    button(
        "创建并安装副本",
        () ->
            work(
                () -> {
                  String doc =
                      new String(
                          java.nio.file.Files.readAllBytes(new File(dir, "SKILL.md").toPath()),
                          StandardCharsets.UTF_8);
                  doc =
                      doc.replaceFirst(
                          "(?m)^name:.*$",
                          "name: " + java.util.regex.Matcher.quoteReplacement(value(name)));
                  Map<String, byte[]> changes = new LinkedHashMap<>();
                  changes.put("SKILL.md", doc.getBytes(StandardCharsets.UTF_8));
                  changes.put("skill.sig", null);
                  return Capabilities.importSkill(value(name) + ".zip", FilesUi.zip(dir, changes));
                },
                x -> textPage("安装结果", x.toString())));
  }

  static String diff(String before, String after) {
    if (before.equals(after)) return "内容未改变";
    String[] a = before.split("\n", -1), b = after.split("\n", -1);
    StringBuilder out = new StringBuilder();
    appendDiff(out, a, 0, a.length, b, 0, b.length);
    return out.toString();
  }

  static void appendDiff(
      StringBuilder out, String[] a, int aStart, int aEnd, String[] b, int bStart, int bEnd) {
    while (aStart < aEnd && bStart < bEnd && a[aStart].equals(b[bStart])) {
      out.append("  ").append(a[aStart++]).append('\n');
      bStart++;
    }
    int suffix = 0;
    while (aEnd - suffix > aStart
        && bEnd - suffix > bStart
        && a[aEnd - suffix - 1].equals(b[bEnd - suffix - 1])) suffix++;
    int aMiddle = aEnd - suffix, bMiddle = bEnd - suffix;
    if (aStart < aMiddle && bStart < bMiddle) {
      if ((long) (aMiddle - aStart) * (bMiddle - bStart) <= 4_000_000L) {
        int[][] lengths = new int[aMiddle - aStart + 1][bMiddle - bStart + 1];
        for (int i = aMiddle - 1; i >= aStart; i--)
          for (int j = bMiddle - 1; j >= bStart; j--)
            lengths[i - aStart][j - bStart] =
                a[i].equals(b[j])
                    ? lengths[i - aStart + 1][j - bStart + 1] + 1
                    : Math.max(
                        lengths[i - aStart + 1][j - bStart], lengths[i - aStart][j - bStart + 1]);
        int i = aStart, j = bStart;
        while (i < aMiddle && j < bMiddle) {
          if (a[i].equals(b[j])) {
            out.append("  ").append(a[i]).append('\n');
            i++;
            j++;
          } else if (lengths[i - aStart + 1][j - bStart] >= lengths[i - aStart][j - bStart + 1])
            out.append("− ").append(a[i++]).append('\n');
          else out.append("+ ").append(b[j++]).append('\n');
        }
        while (i < aMiddle) out.append("− ").append(a[i++]).append('\n');
        while (j < bMiddle) out.append("+ ").append(b[j++]).append('\n');
      } else {
        Map<String, Integer> counts = new HashMap<>(), candidates = new HashMap<>();
        for (int i = aStart; i < aMiddle; i++) counts.put(a[i], counts.getOrDefault(a[i], 0) + 1);
        for (int j = bStart; j < bMiddle; j++)
          candidates.put(b[j], candidates.containsKey(b[j]) ? -1 : j);
        int anchorA = -1, anchorB = -1, middle = (aStart + aMiddle) / 2;
        for (int distance = 0; distance <= aMiddle - aStart && anchorA < 0; distance++) {
          for (int sign : new int[] {-1, 1}) {
            int i = middle + distance * sign;
            if (i < aStart || i >= aMiddle) continue;
            Integer j = candidates.get(a[i]);
            if (counts.get(a[i]) == 1 && j != null && j >= 0) {
              anchorA = i;
              anchorB = j;
              break;
            }
          }
        }
        if (anchorA >= 0) {
          appendDiff(out, a, aStart, anchorA, b, bStart, anchorB);
          out.append("  ").append(a[anchorA]).append('\n');
          appendDiff(out, a, anchorA + 1, aMiddle, b, anchorB + 1, bMiddle);
        } else {
          for (int i = aStart; i < aMiddle; i++) out.append("− ").append(a[i]).append('\n');
          for (int j = bStart; j < bMiddle; j++) out.append("+ ").append(b[j]).append('\n');
        }
      }
    } else {
      for (int i = aStart; i < aMiddle; i++) out.append("− ").append(a[i]).append('\n');
      for (int j = bStart; j < bMiddle; j++) out.append("+ ").append(b[j]).append('\n');
    }
    for (int i = aMiddle; i < aEnd; i++) out.append("  ").append(a[i]).append('\n');
  }

  static final class TimelineView extends View {
    final JSONArray events;
    final Paint paint = new Paint(3);

    TimelineView(Context c, JSONArray e) {
      super(c);
      events = e;
    }

    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      int n = Math.min(10, events.length());
      long min = Long.MAX_VALUE, max = 0;
      for (int i = 0; i < n; i++) {
        JSONObject e = events.optJSONObject(i), d = e.optJSONObject("data");
        long start = e.optLong("time"), end = d.optLong("end", start);
        min = Math.min(min, start);
        max = Math.max(max, Math.max(start, end));
      }
      float left = dp(getContext(), 118), width = getWidth() - left - dp(getContext(), 8);
      for (int i = 0; i < n; i++) {
        JSONObject e = events.optJSONObject(i), d = e.optJSONObject("data");
        float y = dp(getContext(), i * 30 + 19);
        paint.setColor(secondary(getContext()));
        paint.setTextSize(dp(getContext(), 10));
        String name = e.optString("kind");
        canvas.drawText(name.length() > 18 ? name.substring(0, 18) : name, 0, y, paint);
        long start = e.optLong("time"), end = d.optLong("end", start);
        float x = left + (start - min) * width / Math.max(1, max - min);
        paint.setColor(accent(getContext()));
        canvas.drawRoundRect(
            x,
            y - dp(getContext(), 10),
            x + Math.max(dp(getContext(), 3), (end - start) * width / Math.max(1, max - min)),
            y + dp(getContext(), 2),
            dp(getContext(), 3),
            dp(getContext(), 3),
            paint);
      }
    }
  }
}
