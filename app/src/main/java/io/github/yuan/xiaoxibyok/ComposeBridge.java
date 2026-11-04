package io.github.yuan.xiaoxibyok;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import de.robv.android.xposed.*;
import java.util.*;

final class ComposeBridge {
  static final Map<Activity, Map<String, Object>> routes =
      Collections.synchronizedMap(new WeakHashMap<>());

  static void activate(Activity activity) {
    routes.computeIfAbsent(activity, ignored -> new HashMap<>());
  }

  static Object route(Activity activity, String title) {
    Map<String, Object> callbacks = routes.get(activity);
    return callbacks == null ? null : callbacks.get(title);
  }

  static final Set<Object> inputComposers = Collections.newSetFromMap(new WeakHashMap<>());

  static Object modifier() {
    return XposedHelpers.getStaticObjectField(Host.cls("androidx.compose.ui.n"), "a");
  }

  static int composer(Object[] args) {
    for (int i = 0; i < args.length; i++)
      if (args[i] != null && Host.cls("androidx.compose.runtime.m").isInstance(args[i])) return i;
    throw new IllegalArgumentException("Missing Composer");
  }

  static void view(Object composer, Object factory, Object update) {
    view(composer, modifier(), factory, update);
  }

  static void view(Object composer, Object modifier, Object factory, Object update) {
    XposedHelpers.callStaticMethod(
        Host.cls("androidx.compose.ui.viewinterop.g"),
        "a",
        factory,
        modifier,
        update,
        composer,
        0,
        0);
  }

  static void install() {
    Host.capture("com.flyme.agentos.scheduler.store.e", "taskStore");
    Host.capture("com.flyme.agentos.app.common.scheduler.skill.e", "taskTools");
    ReasoningUi.install();
    Object click =
        Host.function(
            "kotlin.jvm.functions.Function0",
            args -> {
              NativeUi.show(MainHook.resumedActivity.get());
              return Host.unit();
            });
    XposedBridge.hookAllMethods(
        Host.cls("com.flyme.agentos.app.common.ui.components.list.i"),
        "a",
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) throws Throwable {
            if (p.args.length != 14) return;
            String title = String.valueOf(p.args[0]);
            Activity activity = MainHook.resumedActivity.get();
            if (p.args[9] != null
                && Host.cls("com.flyme.agentos.app.phone.ui.SettingsActivity").isInstance(activity))
              routes.computeIfAbsent(activity, ignored -> new HashMap<>()).put(title, p.args[9]);
            if (!title.equals("开发者工具")) return;
            NativeStyle.capture(p.args[10]);
            Object[] row = p.args.clone();
            row[0] = "增强设置";
            row[2] = "模型、提示词、上下文、技能与会话轨迹";
            row[9] = click;
            row[11] = 3078;
            row[12] = 0;
            XposedBridge.invokeOriginalMethod(p.method, p.thisObject, row);
          }
        });
    XposedBridge.hookAllMethods(
        Activity.class,
        "onResume",
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            NativeUi.onResume((Activity) p.thisObject);
          }
        });
    XposedBridge.hookAllMethods(
        Activity.class,
        "onDestroy",
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            if (Host.cls("com.flyme.agentos.app.phone.ui.SettingsActivity")
                .isInstance(p.thisObject)) NativeUi.settingsDestroyed((Activity) p.thisObject);
          }
        });
    XposedBridge.hookAllMethods(
        Host.cls("com.flyme.agentos.app.common.ui.components.input.z"),
        "c",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!MainHook.prefs().getBoolean("stats", true)) return;
            int ci = composer(p.args);
            Object c = p.args[ci];
            if (inputComposers.contains(c)) return;
            Object parent =
                XposedHelpers.callStaticMethod(Host.cls("androidx.compose.runtime.b"), "z", c);
            Object[] captured = p.args.clone();
            java.lang.reflect.Member method = p.method;
            Object owner = p.thisObject;
            Object factory =
                Host.function(
                    "kotlin.jvm.functions.Function1",
                    args -> {
                      Context context = (Context) args[0];
                      LinearLayout l = new LinearLayout(context);
                      l.setOrientation(1);
                      StatsBar bar = new StatsBar(context);
                      l.addView(bar, new LinearLayout.LayoutParams(-1, -2));
                      View nativeInput =
                          (View)
                              Host.make(
                                  "androidx.compose.ui.platform.ComposeView", context, null, 0);
                      Host.call(nativeInput, "setParentCompositionContext", parent);
                      l.addView(nativeInput, new LinearLayout.LayoutParams(-1, -2));
                      return l;
                    });
            Object update =
                Host.function(
                    "kotlin.jvm.functions.Function1",
                    args -> {
                      LinearLayout l = (LinearLayout) args[0];
                      Object child = l.getChildAt(1);
                      Object content =
                          Host.function(
                              "kotlin.jvm.functions.Function2",
                              a -> {
                                Object[] forwarded = captured.clone();
                                forwarded[ci] = a[0];
                                inputComposers.add(a[0]);
                                for (int i = ci + 1; i < forwarded.length - 2; i++)
                                  forwarded[i] = 0;
                                try {
                                  XposedBridge.invokeOriginalMethod(method, owner, forwarded);
                                } catch (Throwable e) {
                                  throw new RuntimeException(e);
                                }
                                return Host.unit();
                              });
                      Host.call(child, "setContent", content);
                      return Host.unit();
                    });
            view(c, factory, update);
            p.setResult(null);
          }
        });
    XposedBridge.hookAllMethods(
        Host.cls("com.flyme.agentos.app.common.ui.chat.a0"),
        "f",
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            if (!MainHook.prefs().getBoolean("details", true)) return;
            Object message = p.args[0];
            String request = Host.text(message, "n"), trace = Host.text(message, "m");
            List<?> items = (List<?>) Host.field(message, "l");
            if (items.isEmpty() && request.isEmpty()) return;
            Object factory =
                Host.function(
                    "kotlin.jvm.functions.Function1", a -> new DetailView((Context) a[0]));
            Object update =
                Host.function(
                    "kotlin.jvm.functions.Function1",
                    a -> {
                      ((DetailView) a[0]).bind(message, request, trace);
                      return Host.unit();
                    });
            view(p.args[composer(p.args)], factory, update);
          }
        });
  }

  static final class StatsBar extends LinearLayout {
    final TextView text;
    String session = "";
    long version, nextReload;
    final Runnable tick =
        new Runnable() {
          public void run() {
            String current = Host.currentSession();
            long now = android.os.SystemClock.uptimeMillis();
            if (!current.equals(session) || now >= nextReload) {
              session = current;
              nextReload = now + 4000;
              long request = ++version;
              Host.work(
                  () -> Trace.compactShort(session),
                  result -> {
                    if (request == version && isAttachedToWindow())
                      text.setText(String.valueOf(result));
                  },
                  error -> {
                    if (request == version && isAttachedToWindow()) text.setText("统计暂不可用");
                  });
            }
            postDelayed(this, 1000);
          }
        };

    StatsBar(Context c) {
      super(c);
      setOrientation(HORIZONTAL);
      setGravity(Gravity.CENTER_VERTICAL);
      setPadding(NativeUi.dp(c, 12), 0, NativeUi.dp(c, 12), 0);
      text = new TextView(c);
      text.setTextSize(10);
      text.setTextColor(NativeUi.secondary(c));
      text.setMaxLines(2);
      text.setEllipsize(android.text.TextUtils.TruncateAt.END);
      text.setIncludeFontPadding(false);
      text.setGravity(Gravity.CENTER_VERTICAL);
      text.setPadding(0, 0, NativeUi.dp(c, 4), 0);
      addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
      LinearLayout actions = new LinearLayout(c);
      actions.setOrientation(HORIZONTAL);
      actions.setGravity(Gravity.CENTER_VERTICAL);
      for (String name : new String[] {"统计详情", "轨迹", "上下文"}) {
        TextView action = new TextView(c);
        action.setText(name);
        action.setTextSize(11);
        action.setSingleLine(true);
        action.setTextColor(NativeUi.secondary(c));
        action.setMinHeight(NativeUi.dp(c, 36));
        action.setGravity(Gravity.CENTER);
        action.setPadding(NativeUi.dp(c, 4), 0, NativeUi.dp(c, 4), 0);
        actions.addView(action);
        NativeStyle.interaction(
            c,
            action,
            () ->
                NativeUi.showSession(
                    MainHook.resumedActivity.get(),
                    session,
                    name.equals("轨迹") ? "trajectory" : name.equals("上下文") ? "context" : "stats"));
      }
      addView(actions, new LinearLayout.LayoutParams(-2, -2));
      NativeStyle.interaction(
          c, text, () -> NativeUi.showSession(MainHook.resumedActivity.get(), session, "stats"));
    }

    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      nextReload = 0;
      tick.run();
    }

    protected void onDetachedFromWindow() {
      version++;
      removeCallbacks(tick);
      super.onDetachedFromWindow();
    }
  }

  static final class DetailView extends LinearLayout {
    final TextView label, body;
    boolean expanded;
    Object message;
    String request, trace;
    long version;

    DetailView(Context c) {
      super(c);
      setOrientation(VERTICAL);
      setPadding(NativeUi.dp(c, 16), 0, NativeUi.dp(c, 16), NativeUi.dp(c, 8));
      label = new TextView(c);
      label.setTextSize(12);
      label.setTextColor(NativeUi.accent(c));
      label.setPadding(0, NativeUi.dp(c, 8), 0, NativeUi.dp(c, 8));
      body = new TextView(c);
      body.setTextSize(13);
      body.setTextColor(NativeUi.primary(c));
      body.setTextIsSelectable(true);
      addView(label);
      addView(body);
      label.setOnClickListener(
          v -> {
            expanded = !expanded;
            render();
          });
    }

    void bind(Object m, String r, String t) {
      version++;
      message = m;
      request = r;
      trace = t;
      render();
    }

    void render() {
      long binding = ++version;
      label.setText(expanded ? "收起详细思考与工具调用 ▴" : "展开详细思考与工具调用 ▾");
      body.setVisibility(expanded ? VISIBLE : GONE);
      if (!expanded) return;
      StringBuilder b = new StringBuilder();
      for (Object item : (List<?>) Host.field(message, "l")) {
        String type = item.getClass().getSimpleName();
        if (type.equals("n0")) b.append("思考\n").append(Host.text(item, "b"));
        else if (type.equals("m0")) b.append(Host.text(item, "b"));
        else if (type.equals("o0")) {
          Object tool = Host.field(item, "b");
          b.append("工具：")
              .append(Host.text(tool, "c"))
              .append(" · ")
              .append(Host.text(tool, "d"))
              .append("\n参数\n")
              .append(Host.text(tool, "e"))
              .append("\n结果\n")
              .append(Host.text(tool, "f"))
              .append(Host.text(tool, "g"));
        } else b.append(item);
        b.append("\n\n");
      }
      String nativeDetails = b.toString();
      body.setText(nativeDetails + "正在读取关联请求…");
      Host.work(
          () -> {
            StringBuilder details = new StringBuilder(nativeDetails);
            JSONArrayHelper.appendRequests(details, request);
            return details.toString();
          },
          result -> {
            if (binding == version && expanded) body.setText(String.valueOf(result));
          },
          error -> {
            if (binding == version && expanded) body.setText(nativeDetails);
          });
    }
  }

  static final class JSONArrayHelper {
    static void appendRequests(StringBuilder b, String request) {
      org.json.JSONArray events = Store.requests(request);
      for (int i = 0; i < events.length(); i++) {
        org.json.JSONObject d = events.optJSONObject(i);
        b.append("思考\n")
            .append(d.optString("thinking"))
            .append("\n\n工具调用\n")
            .append(Json.pretty(d.optJSONArray("tools")));
      }
    }
  }
}
