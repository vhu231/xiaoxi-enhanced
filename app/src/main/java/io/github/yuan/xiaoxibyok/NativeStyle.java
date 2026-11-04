package io.github.yuan.xiaoxibyok;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class NativeStyle {
  static final Map<Boolean, Map<String, Integer>> themes = new ConcurrentHashMap<>();

  static void capture(Object composer) {
    try {
      Object colors =
          Host.call(
              composer,
              "k",
              Reflect.getStaticObjectField(Host.cls("androidx.compose.material3.O0"), "a"));
      Map<String, Integer> theme = new HashMap<>();
      for (String token : new String[] {"q", "s", "a", "n", "D"}) {
        long value = Reflect.getLongField(colors, token);
        theme.put(
            token,
            (Integer)
                Reflect.callStaticMethod(
                    Host.cls("androidx.compose.ui.graphics.C"), "toArgb-8_81llA", value));
      }
      themes.put(NativeUi.dark((Context) Host.app()), theme);
    } catch (Throwable error) {
      Xposed.log(error);
    }
  }

  static int color(Context context, String token) {
    Map<String, Integer> theme = themes.get(NativeUi.dark(context));
    Integer color = theme == null ? null : theme.get(token);
    return color == null ? 0 : color;
  }

  static View header(Context context, String title, Runnable back) {
    LinearLayout header = container(context, true, 16);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setMinimumHeight(dp(context, 64));
    TextView arrow = text(context, "‹", 34, NativeUi.primary(context));
    arrow.setGravity(Gravity.CENTER);
    arrow.setPadding(0, 0, 0, dp(context, 4));
    arrow.setContentDescription("返回");
    header.addView(arrow, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 56)));
    interaction(context, arrow, back);
    TextView label = text(context, title, 20, NativeUi.primary(context));
    label.setMaxLines(1);
    label.setEllipsize(TextUtils.TruncateAt.END);
    label.setPadding(dp(context, 4), 0, dp(context, 40), 0);
    header.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
    return header;
  }

  static int dp(Context context, float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  static int cardColor(Context context) {
    int color = color(context, "D");
    return color != 0 ? color : NativeUi.dark(context) ? 0xff1c1c20 : 0xffffffff;
  }

  static float radius() {
    try {
      return Reflect.getStaticFloatField(Host.cls("S6.a"), "g0");
    } catch (Throwable error) {
      return 10;
    }
  }

  static GradientDrawable background(Context context) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(cardColor(context));
    background.setCornerRadius(dp(context, radius()));
    return background;
  }

  static void interaction(Context context, View view, Runnable action) {
    view.setClickable(true);
    view.setFocusable(true);
    TypedValue value = new TypedValue();
    if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true))
      view.setBackground(context.getDrawable(value.resourceId));
    view.setOnClickListener(
        clicked -> {
          try {
            action.run();
          } catch (Throwable error) {
            NativeUi.error(error);
          }
        });
  }

  static void setEnabled(View view, boolean enabled) {
    view.setEnabled(enabled);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) setEnabled(group.getChildAt(i), enabled);
    }
  }

  static void cancelAnimations(View view) {
    view.animate().cancel();
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) cancelAnimations(group.getChildAt(i));
    }
  }

  static TextView text(Context context, String value, int size, int color) {
    TextView text = new TextView(context);
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(color);
    text.setLineSpacing(dp(context, 2), 1f);
    return text;
  }

  static LinearLayout container(Context context, boolean horizontal, int padding) {
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    layout.setPadding(dp(context, padding), dp(context, 12), dp(context, padding), dp(context, 12));
    return layout;
  }

  static FrameLayout cardView(Context context, View content) {
    FrameLayout card = new FrameLayout(context);
    card.setBackground(background(context));
    card.setClipToOutline(true);
    card.addView(content);
    return card;
  }

  static LinearLayout group(Context context) {
    LinearLayout group = new LinearLayout(context);
    group.setOrientation(LinearLayout.VERTICAL);
    group.setBackground(background(context));
    group.setClipToOutline(true);
    return group;
  }

  static void addToGroup(LinearLayout group, View child) {
    if (group.getChildCount() > 0) {
      View divider = new View(group.getContext());
      divider.setBackgroundColor(NativeUi.dark(group.getContext()) ? 0xff303036 : 0xffe8e8ec);
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(-1, Math.max(1, dp(group.getContext(), .5f)));
      params.leftMargin = dp(group.getContext(), 18);
      group.addView(divider, params);
    }
    group.addView(child, new LinearLayout.LayoutParams(-1, -2));
  }

  static View row(Context context, String title, String subtitle, Runnable action) {
    LinearLayout row = container(context, true, 18);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(context, 64));
    LinearLayout labels = new LinearLayout(context);
    labels.setOrientation(LinearLayout.VERTICAL);
    TextView heading = text(context, title, 17, NativeUi.primary(context));
    heading.setMaxLines(2);
    heading.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(heading);
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView secondary = text(context, subtitle, 13, NativeUi.secondary(context));
      secondary.setMaxLines(3);
      secondary.setEllipsize(TextUtils.TruncateAt.END);
      secondary.setPadding(0, dp(context, 4), 0, 0);
      labels.addView(secondary);
    }
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
    if (action != null) {
      TextView arrow = text(context, "›", 26, NativeUi.secondary(context));
      arrow.setGravity(Gravity.CENTER);
      arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      row.addView(arrow, new LinearLayout.LayoutParams(dp(context, 28), -2));
      interaction(context, row, action);
    }
    return row;
  }

  static View section(Context context, String title) {
    TextView section = text(context, title, 13, NativeUi.secondary(context));
    section.setPadding(dp(context, 8), dp(context, 16), dp(context, 8), dp(context, 4));
    return section;
  }

  static View toggle(Context context, String title, Switch value, Consumer<Boolean> changed) {
    LinearLayout row = container(context, true, 18);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(context, 60));
    TextView label = text(context, title, 16, NativeUi.primary(context));
    label.setMaxLines(2);
    label.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
    value.setOnCheckedChangeListener(
        (button, checked) -> {
          if (button.getTag() == null) changed.accept(checked);
        });
    row.addView(value);
    interaction(context, row, value::toggle);
    return row;
  }

  static View choice(Context context, String title, Spinner value) {
    LinearLayout row = container(context, true, 18);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(context, 60));
    TextView label = text(context, title, 16, NativeUi.primary(context));
    label.setMaxLines(2);
    label.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(label, new LinearLayout.LayoutParams(0, -2, .9f));
    TextView selected =
        text(context, String.valueOf(value.getSelectedItem()), 14, NativeUi.secondary(context));
    selected.setGravity(Gravity.END);
    selected.setMaxLines(2);
    selected.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(selected, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView arrow = text(context, "›", 24, NativeUi.secondary(context));
    arrow.setPadding(dp(context, 6), 0, 0, 0);
    row.addView(arrow);
    interaction(context, row, () -> choiceDialog(context, title, value, selected));
    return row;
  }

  static void choiceDialog(Context context, String title, Spinner value, TextView selected) {
    String[] items = new String[value.getAdapter().getCount()];
    for (int i = 0; i < items.length; i++) items[i] = String.valueOf(value.getAdapter().getItem(i));
    Dialog dialog = new Dialog(context);
    LinearLayout sheet = container(context, false, 8);
    sheet.setBackground(background(context));
    TextView heading = text(context, title, 18, NativeUi.primary(context));
    heading.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
    sheet.addView(heading);
    LinearLayout options = new LinearLayout(context);
    options.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < items.length; i++) {
      int index = i;
      LinearLayout option = container(context, true, 16);
      option.setGravity(Gravity.CENTER_VERTICAL);
      option.setMinimumHeight(dp(context, 52));
      TextView name = text(context, items[i], 16, NativeUi.primary(context));
      name.setMaxLines(3);
      option.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
      if (index == value.getSelectedItemPosition())
        option.addView(text(context, "✓", 18, NativeUi.accent(context)));
      interaction(
          context,
          option,
          () -> {
            value.setSelection(index);
            selected.setText(items[index]);
            dialog.dismiss();
          });
      options.addView(option);
    }
    ScrollView scroll = new ScrollView(context);
    scroll.addView(options);
    sheet.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    sheet.addView(button(context, "取消", dialog::dismiss));
    dialog.setContentView(sheet, new ViewGroup.LayoutParams(-1, -2));
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
      dialog.getWindow().setGravity(Gravity.BOTTOM);
      dialog.getWindow().setLayout(-1, -2);
      dialog.getWindow().setDimAmount(.4f);
      View decor = dialog.getWindow().getDecorView();
      decor.setOnApplyWindowInsetsListener(
          (view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(dp(context, 12), 0, dp(context, 12), bars.bottom + dp(context, 12));
            return insets;
          });
      decor.requestApplyInsets();
    }
  }

  static View button(Context context, String title, Runnable action) {
    LinearLayout row = container(context, true, 16);
    row.setGravity(Gravity.CENTER);
    row.setMinimumHeight(dp(context, 52));
    boolean danger = title.contains("删除") || title.contains("卸载") || title.contains("清除");
    TextView label = text(context, title, 16, danger ? 0xffd34f4f : NativeUi.accent(context));
    label.setGravity(Gravity.CENTER);
    label.setMaxLines(2);
    label.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(label, new LinearLayout.LayoutParams(-1, -2));
    interaction(context, row, action);
    return row;
  }
}
