package dev.codex.xiaobu.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small self-contained configuration UI; enabling a switch never changes other accessibility services. */
public final class GestureSettingsActivity extends Activity {
    private static final int INK = Color.rgb(34, 34, 43);
    private static final int SECONDARY = Color.rgb(100, 100, 115);
    private static final int ACCENT = Color.rgb(102, 84, 217);
    private static final String BACKEND_BOOT = "xiaobu_single_finger_backend_boot";
    private final ExecutorService appLoader = Executors.newSingleThreadExecutor();
    private Switch enabledSwitch;
    private TextView statusText;
    private TextView timeoutSummary;
    private TextView exclusionSummary;
    private boolean updating;
    private boolean receiverRegistered;
    private volatile boolean destroyed;
    private AlertDialog activeDialog;
    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateState(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 245, 249));
        LinearLayout content = column();
        content.setPadding(dp(20), dp(22), dp(20), dp(30));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        TextView title = text("小布识屏增强", 28, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);
        TextView subtitle = text("保留原有识屏功能，按需开启全屏单指长按。", 14, SECONDARY);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        content.addView(subtitle);

        LinearLayout main = card(content, "全屏单指长按");
        enabledSwitch = new Switch(this);
        enabledSwitch.setText("启用单指长按识屏");
        enabledSwitch.setTextSize(17);
        enabledSwitch.setTextColor(INK);
        enabledSwitch.setPadding(0, dp(8), 0, dp(12));
        enabledSwitch.setMinHeight(dp(52));
        main.addView(enabledSwitch, new LinearLayout.LayoutParams(-1, -2));
        main.addView(text("任意位置长按；滑动、多指或提前抬手即取消。触发后轻震一次，同一次按住只启动一次。仅在亮屏且已解锁时工作。", 14, SECONDARY));
        enabledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (!updating) GestureConfig.setEnabled(GestureSettingsActivity.this, checked);
                updateState();
            }
        });

        LinearLayout status = card(content, "运行状态");
        statusText = text("", 14, SECONDARY);
        status.addView(statusText);
        addButton(status, "打开辅助功能设置", new View.OnClickListener() {
            @Override public void onClick(View view) { openAccessibilitySettings(); }
        });
        TextView serviceInfo = text("请开启“小布识屏增强 · 长按检测”。服务只接收操作弹窗等窗口事件，不读取页面文字，不接管触摸，也不模拟点击。", 13, SECONDARY);
        serviceInfo.setPadding(0, dp(6), 0, 0);
        status.addView(serviceInfo);

        LinearLayout timing = card(content, "长按时长");
        timeoutSummary = text("", 15, INK);
        timing.addView(timeoutSummary);
        TextView timingInfo = text("阈值到达后再观察约 100 毫秒，实际弹出操作菜单、选字工具栏或开始拖动时，优先保留原生操作。", 13, SECONDARY);
        timingInfo.setPadding(0, dp(7), 0, 0);
        timing.addView(timingInfo);
        addButton(timing, "调整时长", new View.OnClickListener() {
            @Override public void onClick(View view) { showTimeoutOptions(); }
        });

        LinearLayout excluded = card(content, "应用排除");
        exclusionSummary = text("", 15, INK);
        excluded.addView(exclusionSummary);
        TextView excludeInfo = text("有文字或收到选中事件本身不会拦截识屏；实际操作弹窗与桌面拖动优先。部分游戏或自绘菜单可能无法区分，可在这里排除应用。识屏界面与模块设置页始终不触发。", 13, SECONDARY);
        excludeInfo.setPadding(0, dp(7), 0, 0);
        excluded.addView(excludeInfo);
        addButton(excluded, "选择排除的应用", new View.OnClickListener() {
            @Override public void onClick(View view) { showAppChooser(); }
        });

        LinearLayout compatibility = card(content, "启用与兼容性");
        compatibility.addView(text("在 LSPosed 中启用模块，勾选推荐作用域：小布识屏、翻译、系统框架。首次安装、更新模块或调整系统框架作用域后，请重启手机。", 14, SECONDARY));
        TextView compatibilityInfo = text("功能需要系统提供小布识屏。不同系统版本可能存在接口差异；不匹配的增强会停用并恢复对应的原生流程，其他可用功能继续工作。", 13, SECONDARY);
        compatibilityInfo.setPadding(0, dp(10), 0, 0);
        compatibility.addView(compatibilityInfo);
        setContentView(scroll);
        updateState();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(GestureConfig.ACTION_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(settingsReceiver, filter);
            receiverRegistered = true;
        }
        updateState();
    }

    @Override protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(settingsReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (activeDialog != null) activeDialog.dismiss();
        appLoader.shutdownNow();
        super.onDestroy();
    }

    private void updateState() {
        if (destroyed || enabledSwitch == null) return;
        updating = true;
        boolean enabled = GestureConfig.enabled(this);
        enabledSwitch.setChecked(enabled);
        updating = false;
        String framework = "系统框架：加载状态尚未确认；首次安装或更新后需重启";
        try {
            int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -2);
            int loadedBoot = Settings.Global.getInt(getContentResolver(), BACKEND_BOOT, -1);
            if (boot >= 0 && loadedBoot == boot) framework = "系统框架：本次开机已加载手势检测";
        } catch (RuntimeException ignored) {}
        statusText.setText("单指长按：" + (enabled ? "配置已开启" : "未开启")
                + "\n辅助功能：" + (GestureObserverService.isConnected() ? "已连接" : "未连接，请在下方设置中开启")
                + "\n" + framework);
        int timeout = GestureConfig.timeoutMs(this);
        timeoutSummary.setText(timeout == 0 ? "跟随系统双指长按时长（推荐）" : "自定义阈值：" + timeout + " 毫秒");
        int count = GestureConfig.excluded(this).size();
        exclusionSummary.setText(count == 0 ? "未手动排除应用" : "已排除 " + count + " 个应用");
    }

    private void openAccessibilitySettings() {
        // AOSP exposes this detail action only as a system API; fall back on ROMs without it.
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        details.putExtra("android.intent.extra.COMPONENT_NAME", new ComponentName(this, GestureObserverService.class).flattenToString());
        try {
            startActivity(details);
        } catch (RuntimeException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (RuntimeException error) { Toast.makeText(this, "无法打开系统辅助功能设置", Toast.LENGTH_SHORT).show(); }
        }
    }

    private void showTimeoutOptions() {
        activeDialog = new AlertDialog.Builder(this).setTitle("长按时长")
                .setItems(new String[] { "跟随系统双指长按时长（推荐）", "自定义时长" }, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            GestureConfig.setTimeoutMs(GestureSettingsActivity.this, 0);
                            updateState();
                        } else showCustomTimeout();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void showCustomTimeout() {
        LinearLayout body = column();
        body.setPadding(dp(24), dp(6), dp(24), 0);
        body.addView(text("输入 200–2000 毫秒。另有约 100 毫秒的观察窗口；较短时长更容易与原生长按冲突。", 14, SECONDARY));
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        int configured = GestureConfig.timeoutMs(this);
        field.setText(Integer.toString(configured == 0 ? 400 : configured));
        field.setSelectAllOnFocus(true);
        body.addView(field, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("自定义长按阈值")
                .setView(body).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        activeDialog = dialog;
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        int value;
                        try { value = Integer.parseInt(field.getText().toString().trim()); }
                        catch (NumberFormatException error) { field.setError("请输入有效的毫秒数"); return; }
                        if (value < 200 || value > 2000) { field.setError("请输入 200–2000"); return; }
                        GestureConfig.setTimeoutMs(GestureSettingsActivity.this, value);
                        updateState();
                        dialog.dismiss();
                    }
                });
            }
        });
        dialog.show();
    }

    private void showAppChooser() {
        final Set<String> selected = new HashSet<String>(GestureConfig.excluded(this));
        final LinearLayout body = column();
        body.setPadding(dp(20), 0, dp(20), 0);
        body.addView(text("勾选后，该应用内不会触发单指长按识屏。", 13, SECONDARY));
        final EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索应用名称或包名");
        body.addView(search, new LinearLayout.LayoutParams(-1, -2));
        final ProgressBar loading = new ProgressBar(this);
        LinearLayout.LayoutParams loadingLayout = new LinearLayout.LayoutParams(dp(36), dp(36));
        loadingLayout.gravity = Gravity.CENTER_HORIZONTAL;
        loadingLayout.topMargin = dp(30);
        loadingLayout.bottomMargin = dp(30);
        body.addView(loading, loadingLayout);
        final ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setVisibility(View.GONE);
        body.addView(list, new LinearLayout.LayoutParams(-1, dp(370)));
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("排除应用").setView(body)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface ignored, int which) {
                        GestureConfig.setExcluded(GestureSettingsActivity.this, selected);
                        updateState();
                    }
                }).setNegativeButton("取消", null).create();
        activeDialog = dialog;
        dialog.show();
        appLoader.execute(new Runnable() {
            @Override public void run() {
                final List<AppEntry> apps = new ArrayList<AppEntry>();
                try {
                    PackageManager manager = getApplicationContext().getPackageManager();
                    for (ApplicationInfo info : manager.getInstalledApplications(0)) {
                        if (Thread.currentThread().isInterrupted()) return;
                        if (getPackageName().equals(info.packageName) || "com.coloros.colordirectservice".equals(info.packageName)) continue;
                        CharSequence label;
                        try { label = manager.getApplicationLabel(info); }
                        catch (RuntimeException error) { label = info.packageName; }
                        apps.add(new AppEntry(info.packageName, label == null ? info.packageName : label.toString()));
                    }
                    final Collator collator = Collator.getInstance(Locale.getDefault());
                    Collections.sort(apps, new Comparator<AppEntry>() {
                        @Override public int compare(AppEntry first, AppEntry second) {
                            int compared = collator.compare(first.label, second.label);
                            return compared != 0 ? compared : first.packageName.compareTo(second.packageName);
                        }
                    });
                } catch (RuntimeException ignored) {}
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (destroyed || !dialog.isShowing()) return;
                        loading.setVisibility(View.GONE);
                        list.setVisibility(View.VISIBLE);
                        final AppAdapter adapter = new AppAdapter(apps, selected);
                        list.setAdapter(adapter);
                        adapter.filter(search.getText().toString());
                        search.addTextChangedListener(new TextWatcher() {
                            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { adapter.filter(text.toString()); }
                            @Override public void afterTextChanged(Editable text) {}
                        });
                        if (apps.isEmpty()) Toast.makeText(GestureSettingsActivity.this, "未能读取已安装应用列表，请稍后重试", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> all;
        private final List<AppEntry> visible = new ArrayList<AppEntry>();
        private final Set<String> selected;
        AppAdapter(List<AppEntry> apps, Set<String> checked) { all = apps; selected = checked; }
        void filter(String input) {
            String query = input.trim().toLowerCase(Locale.ROOT);
            visible.clear();
            for (AppEntry app : all) {
                if (query.length() == 0 || app.label.toLowerCase(Locale.ROOT).contains(query)
                        || app.packageName.toLowerCase(Locale.ROOT).contains(query)) visible.add(app);
            }
            notifyDataSetChanged();
        }
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            final AppEntry app = visible.get(position);
            CheckBox row;
            if (recycled instanceof CheckBox) row = (CheckBox) recycled;
            else {
                row = new CheckBox(GestureSettingsActivity.this);
                row.setTextColor(INK);
                row.setTextSize(14);
                row.setPadding(0, dp(9), 0, dp(9));
                row.setMinHeight(dp(62));
            }
            row.setOnCheckedChangeListener(null);
            row.setText(app.label + "\n" + app.packageName);
            row.setChecked(selected.contains(app.packageName));
            row.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                    if (checked) selected.add(app.packageName); else selected.remove(app.packageName);
                }
            });
            return row;
        }
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        AppEntry(String name, String title) { packageName = name; label = title; }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card(LinearLayout parent, String label) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        parent.addView(card, params);
        TextView heading = text(label, 16, INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, 0, 0, dp(10));
        card.addView(heading);
        return card;
    }

    private TextView text(String value, int size, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setLineSpacing(dp(3), 1f);
        return label;
    }

    private void addButton(LinearLayout card, String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(ACCENT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(6);
        card.addView(button, params);
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
