// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Yicons

package org.lb2002.shared;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Process;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Shared by the standalone APKs; no ADB service or device-side setup required. */
public final class RootAccess {
    private RootAccess() {}
    public static int userId() { return Process.myUid() / 100000; }
    public static boolean system(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    public static String run(Context context, String command) throws Exception {
        final java.lang.Process process;
        try { process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start(); }
        catch (IOException e) {
            throw new IOException("未找到可用的 Root。请确认设备已 Root，并在超级用户管理器中授权本应用");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] bytes = new byte[1024];
                int count;
                while ((count = in.read(bytes)) != -1) {
                    synchronized (output) {
                        if (output.size() < 16384) output.write(bytes, 0, Math.min(count, 16384 - output.size()));
                    }
                }
            } catch (IOException ignored) {}
        }, "RootOutput");
        drain.setDaemon(true); drain.start();
        if (!process.waitFor(40, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Root 请求超时，请在设备的超级用户管理器中允许本应用后重试");
        }
        drain.join(1000);
        String result;
        synchronized (output) { result = output.toString("UTF-8").trim(); }
        if (process.exitValue() != 0) {
            context.getSharedPreferences("root_access", 0).edit().putBoolean("verified", false).apply();
            throw new IOException("Root 操作被拒绝或执行失败，请检查超级用户授权。" +
                (result.isEmpty() ? "" : "\n" + result));
        }
        return result;
    }
    public static void show(Activity activity, String systemPermission, String purpose) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (24 * density);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL); card.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1B2531); background.setCornerRadius(12 * density);
        card.setBackground(background);
        TextView title = new TextView(activity);
        title.setText("权限管理"); title.setTextSize(24); title.setTextColor(0xFFE8EDF4);
        card.addView(title);
        TextView text = new TextView(activity);
        text.setTextSize(16); text.setTextColor(0xFFB6C4D3);
        text.setPadding(0, pad / 2, 0, pad);
        String state = system(activity, systemPermission) ? "系统权限已可用，当前无需 Root。"
            : activity.getSharedPreferences("root_access", 0).getBoolean("verified", false)
                ? "Root 上次验证成功；实际操作时会再次检查授权。" : "Root 尚未验证。";
        text.setText(state + "\n\n" + purpose + "\n\n点击下方按钮后，请在设备的超级用户管理器中允许本应用。无需电脑或 ADB。");
        card.addView(text);
        Button grant = button(activity, "申请 / 重新验证 Root");
        card.addView(grant, new LinearLayout.LayoutParams(-1, (int) (48 * density)));
        Button close = button(activity, "关闭");
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, (int) (48 * density));
        gap.topMargin = (int) (12 * density); card.addView(close, gap);
        close.setOnClickListener(v -> dialog.dismiss());
        grant.setOnClickListener(v -> {
            grant.setEnabled(false); text.setText("正在请求 Root，请查看超级用户授权提示…");
            new Thread(() -> {
                String message;
                try {
                    if (!"0".equals(run(activity, "id -u"))) throw new IOException("当前会话没有获得 Root");
                    activity.getSharedPreferences("root_access", 0).edit().putBoolean("verified", true).apply();
                    message = "Root 验证成功。\n\n" + purpose;
                } catch (Exception e) { message = e.getMessage(); }
                String finalMessage = message;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) return;
                    text.setText(finalMessage); grant.setEnabled(true); grant.requestFocus();
                });
            }, "RootAuthorization").start();
        });
        dialog.setContentView(card); dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout((int) (560 * density), -2);
        grant.requestFocus();
    }
    private static Button button(Activity activity, String label) {
        Button button = new Button(activity);
        button.setText(label); button.setTextSize(16); button.setTextColor(0xFFE8EDF4);
        button.setAllCaps(false); button.setStateListAnimator(null); button.setElevation(0);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        for (boolean focus : new boolean[]{true, false}) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(focus ? 0xFF2B454A : 0xFF293745); bg.setCornerRadius(10);
            if (focus) bg.setStroke(3, 0xFF75C9BE);
            states.addState(focus ? new int[]{android.R.attr.state_focused} : new int[]{}, bg);
        }
        button.setBackground(states); return button;
    }
}
