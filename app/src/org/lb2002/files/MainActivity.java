// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Yicons

package org.lb2002.files;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ProgressBar;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<FileIO.Entry> entries = new ArrayList<>();
    private ListView list;
    private ArrayAdapter<FileIO.Entry> adapter;
    private TextView location;
    private TextView status;
    private String current;
    private String rootPath;
    private boolean busy;
    private long generation;
    private final Map<String, Bundle> positions = new HashMap<>();
    private String homeMessage;
    private Button upButton;
    private Button homeButton;
    // The only file cursor. Views never own a second, independent row focus.
    private int cursor;
    private boolean ejectColumn;
    private static final int TEXT = 0xFFE8EDF4;
    private static final int MUTED = 0xFF97A4B5;
    private final BroadcastReceiver storageChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { loadHome(); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(25), dp(40), dp(20));
        root.setBackgroundColor(0xFF111821);
        getWindow().setNavigationBarColor(0xFF111821);
        getWindow().setStatusBarColor(0xFF111821);
        TextView title = text("简洁文件", 28, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        location = text("存储空间", 15, MUTED);
        location.setPadding(0, dp(5), 0, dp(14));
        location.setSingleLine();
        location.setEllipsize(android.text.TextUtils.TruncateAt.START);
        root.addView(location);
        LinearLayout actions = new LinearLayout(this);
        homeButton = addButton(actions, "存储空间", this::loadHome);
        upButton = addButton(actions, "返回上级", this::up);
        addButton(actions, "刷新", () -> { if (current == null) loadHome(); else loadDirectory(current); });
        Button permissionButton = addButton(actions, "存储权限", this::permissions);
        if (android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
            permissionButton.setVisibility(View.GONE);
        addButton(actions, "权限", () -> org.lb2002.shared.RootAccess.show(this,
            "android.permission.MOUNT_UNMOUNT_FILESYSTEMS",
            "普通目录使用存储权限。Android/data、Android/obb 使用 Root 只读访问；安全弹出 U 盘需要系统权限或 Root。"));
        root.addView(actions);
        status = text("读取存储空间…", 15, MUTED);
        status.setPadding(0, dp(15), 0, dp(8));
        root.addView(status);
        list = new ListView(this) {
            @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
                if (handleListKey(event)) return true;
                return super.dispatchKeyEvent(event);
            }
        };
        list.setItemsCanFocus(false);
        list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnFocusChangeListener((v, focused) -> paintCursor());
        list.setSaveEnabled(false);
        list.setSaveFromParentEnabled(false);
        homeButton.setOnKeyListener((v, key, event) -> enterList(key, event));
        upButton.setOnKeyListener((v, key, event) -> enterList(key, event));
        for (int i = 2; i < actions.getChildCount(); i++)
            actions.getChildAt(i).setOnKeyListener((v, key, event) -> enterList(key, event));
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(8));
        list.setPadding(dp(2), dp(2), dp(2), dp(2));
        list.setClipToPadding(false);
        adapter = new ArrayAdapter<FileIO.Entry>(this, android.R.layout.simple_list_item_1, entries) {
            @Override public View getView(int pos, View old, ViewGroup parent) {
                LinearLayout row;
                if (old == null) {
                    row = new LinearLayout(MainActivity.this) {
                        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(info);
                            info.setSelected(isActivated());
                        }
                    };
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(18), dp(10), dp(18), dp(10));
                    row.setFocusable(false);
                    row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    row.setMinimumHeight(dp(72));
                    row.setBackground(background(0xFF1B2531));
                    ImageView image = new ImageView(MainActivity.this);
                    image.setPadding(dp(9), dp(9), dp(9), dp(9));
                    row.addView(image, new LinearLayout.LayoutParams(dp(54), dp(54)));
                    LinearLayout labels = new LinearLayout(MainActivity.this);
                    labels.setOrientation(LinearLayout.VERTICAL);
                    labels.setPadding(dp(18), 0, 0, 0);
                    TextView name = text("", 19, TEXT);
                    name.setSingleLine();
                    name.setTypeface(null, Typeface.BOLD);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    TextView detail = text("", 13, MUTED);
                    labels.addView(name); labels.addView(detail);
                    TextView usage = text("", 12, MUTED);
                    usage.setPadding(0, dp(4), 0, dp(4));
                    ProgressBar progress = new ProgressBar(MainActivity.this, null,
                        android.R.attr.progressBarStyleHorizontal);
                    progress.setMax(100);
                    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF429F96));
                    progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF33404D));
                    labels.addView(usage);
                    labels.addView(progress, new LinearLayout.LayoutParams(dp(220), dp(4)));
                    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                    Button eject = new Button(MainActivity.this);
                    eject.setText("弹出");
                    eject.setTextSize(16);
                    eject.setAllCaps(false);
                    eject.setId(View.generateViewId());
                    styleButton(eject);
                    eject.setFocusable(false);
                    eject.setTextColor(TEXT);
                    eject.setBackground(background(0xFF293745));
                    row.addView(eject, new LinearLayout.LayoutParams(dp(104), dp(44)));
                    row.setTag(new View[]{image, name, detail, eject, usage, progress});
                } else row = (LinearLayout) old;
                View[] views = (View[]) row.getTag();
                FileIO.Entry entry = getItem(pos);
                ImageView image = (ImageView) views[0];
                boolean storage = current == null;
                String art = storage ? (entry.path.startsWith("/storage/emulated") ? "storage" : "usb")
                    : entry.directory ? "folder" : PreviewActivity.imageFile(entry.path) ? "picture" : "file";
                image.setImageResource(getResources().getIdentifier(art, "drawable", getPackageName()));
                image.setBackground(shape(storage ? 0xFF213C3C : entry.directory ? 0xFF3B3229 : 0xFF26354B, false));
                ((TextView) views[1]).setText(entry.name);
                ((TextView) views[2]).setText(current == null ? entry.path
                    : entry.directory ? "文件夹" : FileIO.size(entry.size));
                TextView usage = (TextView) views[4];
                ProgressBar progress = (ProgressBar) views[5];
                boolean showSpace = storage && entry.total > 0;
                usage.setVisibility(showSpace ? View.VISIBLE : View.GONE);
                progress.setVisibility(showSpace ? View.VISIBLE : View.GONE);
                if (showSpace) {
                    usage.setText("可用 " + FileIO.size(entry.free) + " / " + FileIO.size(entry.total));
                    progress.setProgress((int) ((entry.total - entry.free) * 100.0 / entry.total));
                }
                Button eject = (Button) views[3];
                boolean removable = current == null && !entry.path.startsWith("/storage/emulated");
                eject.setVisibility(removable ? View.VISIBLE : View.GONE);
                eject.setContentDescription("弹出 " + entry.name);
                eject.setOnClickListener(v -> { selectEntry(pos, true); activateCursor(); });
                row.setOnClickListener(v -> { selectEntry(pos, false); activateCursor(); });
                paintRow(row, pos);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, view, pos, id) -> {
            selectEntry(pos, false); activateCursor();
        });
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView view, int state) {}
            @Override public void onScroll(android.widget.AbsListView view, int first, int count, int total) {
                paintCursor();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addDataScheme("file");
        registerReceiver(storageChanged, filter);
        if (saved != null) {
            rootPath = saved.getString("root");
            current = saved.getString("path");
            Bundle savedPositions = saved.getBundle("positions");
            if (savedPositions != null) for (String key : savedPositions.keySet())
                positions.put(key, savedPositions.getBundle(key));
        }
    }
    @Override public void onResume() {
        super.onResume();
        if (busy) return; // A Root authorization dialog must not cancel the pending navigation.
        if (current == null) loadHome(); else loadDirectory(current);
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        savePosition();
        out.putString("root", rootPath); out.putString("path", current);
        Bundle savedPositions = new Bundle();
        for (Map.Entry<String, Bundle> entry : positions.entrySet())
            savedPositions.putBundle(entry.getKey(), entry.getValue());
        out.putBundle("positions", savedPositions);
    }
    @Override public void onPause() { savePosition(); super.onPause(); }
    private String positionKey(String path) { return path == null ? "HOME" : path; }
    private void savePosition() {
        if (list == null || busy || entries.isEmpty()) return;
        Bundle state = new Bundle();
        int first = list.getFirstVisiblePosition();
        View firstView = list.getChildAt(0);
        state.putInt("first", first);
        state.putInt("top", firstView == null ? list.getPaddingTop() : firstView.getTop());
        if (cursor >= 0 && cursor < entries.size()) state.putString("file", entries.get(cursor).path);
        state.putBoolean("eject", ejectColumn);
        positions.put(positionKey(current), state);
    }
    private void restorePosition(String path, long request) {
        Bundle state = positions.get(positionKey(path));
        int first = state == null ? 0 : Math.min(state.getInt("first"), Math.max(0, entries.size() - 1));
        int top = state == null ? list.getPaddingTop() : state.getInt("top");
        String file = state == null ? null : state.getString("file");
        int selected = first;
        for (int i = 0; file != null && i < entries.size(); i++)
            if (file.equals(entries.get(i).path)) { selected = i; break; }
        cursor = selected;
        ejectColumn = state != null && state.getBoolean("eject") && removable(cursor);
        list.setSelectionFromTop(first, top - list.getPaddingTop());
        list.post(() -> {
            if (isFinishing() || isDestroyed() || generation != request) return;
            list.requestFocus();
            paintCursor();
        });
    }
    private boolean removable(int index) {
        return current == null && index >= 0 && index < entries.size()
            && !entries.get(index).path.startsWith("/storage/emulated");
    }
    private boolean enterList(int key, android.view.KeyEvent event) {
        if (key != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return false;
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            list.requestFocus(); paintCursor();
        }
        return true;
    }
    private boolean handleListKey(android.view.KeyEvent event) {
        int key = event.getKeyCode();
        boolean direction = key == 19 || key == 20 || key == 21 || key == 22;
        boolean confirm = key == 23 || key == 66 || key == 160;
        if (!direction && !confirm) return false;
        if (busy) return true;
        if (confirm) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) activateCursor();
            return true;
        }
        if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return true;
        if (key == 19 && cursor == 0) { homeButton.requestFocus(); return true; }
        if (entries.isEmpty()) return true;
        if (key == 19 || key == 20) {
            cursor = Math.max(0, Math.min(entries.size() - 1, cursor + (key == 20 ? 1 : -1)));
            if (!removable(cursor)) ejectColumn = false;
            keepCursorVisible();
        } else ejectColumn = key == 22 && removable(cursor);
        paintCursor();
        return true;
    }
    private void selectEntry(int index, boolean eject) {
        cursor = index;
        ejectColumn = eject && removable(index);
        list.requestFocus(); paintCursor();
    }
    private void activateCursor() {
        if (busy || cursor < 0 || cursor >= entries.size()) return;
        FileIO.Entry entry = entries.get(cursor);
        if (ejectColumn && removable(cursor)) confirmEject(entry.path, entry.name);
        else openEntry(entry);
    }
    private void keepCursorVisible() {
        int first = list.getFirstVisiblePosition();
        View row = list.getChildAt(cursor - first);
        if (row == null || row.getTop() < list.getPaddingTop()
                || row.getBottom() > list.getHeight() - list.getPaddingBottom())
            list.setSelectionFromTop(cursor, list.getPaddingTop());
    }
    private void paintRow(View row, int index) {
        View[] children = (View[]) row.getTag();
        boolean active = list.hasFocus() && index == cursor;
        // AbsListView changes View.selected during layout. Activated belongs
        // exclusively to our cursor, so layout cannot paint a second selection.
        row.setActivated(active && !ejectColumn);
        children[3].setActivated(active && ejectColumn);
    }
    private void paintCursor() {
        if (list == null) return;
        int first = list.getFirstVisiblePosition();
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row.getTag() instanceof View[]) paintRow(row, first + i);
        }
    }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    private GradientDrawable shape(int color, boolean focus) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(10));
        if (focus) shape.setStroke(dp(2), 0xFF75C9BE);
        return shape;
    }
    private StateListDrawable background(int color) {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_focused}, shape(0xFF2B454A, true));
        state.addState(new int[]{android.R.attr.state_activated}, shape(0xFF2B454A, true));
        state.addState(new int[]{android.R.attr.state_pressed}, shape(0xFF2B454A, true));
        state.addState(new int[]{}, shape(color, false));
        return state;
    }
    private Button addButton(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(15); button.setTextColor(TEXT);
        styleButton(button);
        button.setBackground(background(0xFF24313F));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(126), dp(40));
        if (row.getChildCount() > 0) params.leftMargin = dp(10);
        row.addView(button, params);
        return button;
    }
    private void styleButton(Button button) {
        button.setAllCaps(false);
        button.setMinWidth(0); button.setMinHeight(0);
        button.setMinimumWidth(0); button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setElevation(0); button.setTranslationZ(0);
        button.setPadding(dp(12), 0, dp(12), 0);
    }
    private void loadHome() { load(null); }
    private void loadDirectory(String path) { load(path); }
    private void openEntry(FileIO.Entry entry) {
        if (busy) return;
        if (entry.directory) {
            if (current == null) rootPath = entry.path;
            loadDirectory(entry.path);
        } else open(entry);
    }
    private void load(String path) {
        if (!busy) savePosition();
        long request = ++generation;
        busy = true;
        status.setText("正在读取…");
        worker.execute(() -> {
            try {
                List<FileIO.Entry> result = path == null ? FileIO.roots(this) : FileIO.list(this, path);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != request) return;
                    current = path;
                    if (path == null) {
                        rootPath = null;
                    }
                    location.setText(path == null ? "存储空间" : path);
                    entries.clear(); entries.addAll(result); adapter.notifyDataSetChanged();
                    busy = false;
                    upButton.setEnabled(path != null);
                    upButton.setAlpha(path == null ? .4f : 1f);
                    if (path == null) {
                        status.setText(homeMessage != null ? homeMessage : result.size() + " 个存储空间");
                        homeMessage = null;
                    }
                    else status.setText(result.isEmpty() ? "这个文件夹是空的" : result.size() + " 个项目");
                    restorePosition(path, request);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != request) return;
                    busy = false;
                    boolean restricted = path != null && path.matches(".*/Android/(data|obb)(/.*)?");
                    status.setText(restricted ? "无法读取受保护目录：" + e.getMessage()
                        : "无法读取此目录：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                });
            }
        });
    }
    private void up() {
        if (current == null) return;
        if (current.equals(rootPath)) loadHome();
        else {
            String parent = new File(current).getParent();
            if (parent == null || rootPath == null || !parent.startsWith(rootPath)) loadHome();
            else loadDirectory(parent);
        }
    }
    private void confirmEject(String storage, String label) {
        if (busy || storage == null || storage.startsWith("/storage/emulated")) return;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(26), dp(24), dp(26), dp(24));
        card.setBackground(shape(0xFF1B2531, false));
        card.addView(text("弹出 " + label, 24, TEXT));
        TextView note = text("系统完成卸载后，即可安全拔出。\n" + storage, 16, MUTED);
        note.setPadding(0, dp(14), 0, dp(22));
        card.addView(note);
        LinearLayout buttons = new LinearLayout(this);
        addButton(buttons, "确认弹出", () -> { dialog.dismiss(); eject(storage); });
        addButton(buttons, "取消", dialog::dismiss);
        card.addView(buttons);
        dialog.setContentView(card);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        dialog.getWindow().setLayout(dp(520), -2);
        buttons.getChildAt(1).requestFocus();
    }
    private void eject(String storage) {
        busy = true;
        ++generation;
        status.setText("正在弹出 U 盘…");
        worker.execute(() -> {
            try {
                FileIO.eject(this, storage);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    homeMessage = "U 盘已弹出，可以安全拔出";
                    busy = false;
                    loadHome();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy = false;
                    status.setText("弹出失败 · " + e.getMessage());
                });
            }
        });
    }
    private void permissions() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } else requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"}, 1);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
        }
    }
    private void open(FileIO.Entry entry) {
        if (PreviewActivity.supports(entry.path)) {
            startActivity(new Intent(this, PreviewActivity.class).putExtra("path", entry.path));
            return;
        }
        Uri uri = new Uri.Builder().scheme("content").authority("org.lb2002.files.provider")
            .path("/file").appendQueryParameter("path", entry.path).build();
        String mime = FileIO.mime(entry.path);
        String action = "application/vnd.android.package-archive".equals(mime)
            ? Intent.ACTION_INSTALL_PACKAGE : Intent.ACTION_VIEW;
        Intent intent = new Intent(action).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(android.content.ClipData.newRawUri(entry.name, uri));
        try { startActivity(intent); }
        catch (RuntimeException e) { status.setText("没有可打开此文件的应用 · " + entry.name); }
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // Back navigates the Activity; a focused/reselecting ListView must not
        // consume the first press merely to change its own selection.
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if (current != null) up();
        else { ++generation; finish(); }
    }
    @Override public void onDestroy() {
        unregisterReceiver(storageChanged);
        worker.shutdown();
        super.onDestroy();
    }
}
