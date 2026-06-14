package com.example.ringerguard;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_CALL_SCREENING_ROLE = 2001;

    private TextView statusView;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();

        /*
         * 打开界面时：顺手记住当前非 0 铃声音量；若守护已开启，立即再恢复一次。
         */
        AudioGuard.rememberCurrentRingVolumeIfNonZero(this);

        if (AudioGuard.isEnabled(this)) {
            AudioGuard.enforce(this, "打开界面");
        }

        updateStatus();
        updateLog();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("防静音防震动");
        title.setTextSize(28);
        root.addView(title, matchWrap());

        TextView desc = new TextView(this);
        desc.setText(
                "\n工作方式（省电版）：\n" +
                        "1. 运营商来电：系统在响铃前自动把铃声切回响铃（必响）。\n" +
                        "2. 平时不常驻后台、不轮询，待机几乎不耗电。\n" +
                        "3. 每 4 小时低频兜底一次，覆盖微信等网络电话的“尽量有声”。\n\n" +
                        "iQOO / OriginOS 兼容：标准静音/震动、勿扰式静音、铃声音量被压到 0、" +
                        "铃声流被 mute，都会尝试恢复。\n" +
                        "不会修改媒体音量、闹钟音量。"
        );
        desc.setTextSize(15);
        root.addView(desc, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(15);
        root.addView(statusView, matchWrap());

        Button startButton = new Button(this);
        startButton.setText("开启防静音/震动");
        root.addView(startButton, matchWrap());

        startButton.setOnClickListener(v -> startGuard());

        Button roleButton = new Button(this);
        roleButton.setText("授予来电筛选权限（来电必响的关键）");
        root.addView(roleButton, matchWrap());

        roleButton.setOnClickListener(v -> requestCallScreeningRole());

        Button rememberVolumeButton = new Button(this);
        rememberVolumeButton.setText("记住当前铃声音量作为恢复音量");
        root.addView(rememberVolumeButton, matchWrap());

        rememberVolumeButton.setOnClickListener(v -> {
            boolean saved = AudioGuard.rememberCurrentRingVolumeIfNonZero(this);
            updateStatus();

            if (saved) {
                Toast.makeText(this, "已记住当前铃声音量", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "当前铃声音量为 0，不能作为恢复音量", Toast.LENGTH_SHORT).show();
            }
        });

        Button fixButton = new Button(this);
        fixButton.setText("立即退出静音/震动/勿扰");
        root.addView(fixButton, matchWrap());

        fixButton.setOnClickListener(v -> {
            boolean changed = AudioGuard.enforce(this, "手动修复");
            updateStatus();

            int mode = AudioGuard.getCurrentRingerMode(this);
            int volume = AudioGuard.getCurrentRingVolumeReadOnly(this);
            boolean muted = AudioGuard.isRingStreamMutedReadOnly(this);
            int filter = AudioGuard.getCurrentInterruptionFilter(this);
            boolean dndActive = isDoNotDisturbActive(filter);

            if (mode == AudioManager.RINGER_MODE_NORMAL
                    && volume > 0
                    && !muted
                    && !dndActive) {
                if (changed) {
                    Toast.makeText(this, "已恢复到可响铃状态", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "当前已是可响铃状态", Toast.LENGTH_SHORT).show();
                }
            } else if (dndActive && !AudioGuard.hasNotificationPolicyAccess(this)) {
                Toast.makeText(this, "恢复失败：iQOO 静音可能是勿扰，请先允许勿扰权限", Toast.LENGTH_LONG).show();
                openNotificationPolicyAccessSettings();
            } else if (volume == 0) {
                Toast.makeText(this, "恢复失败：铃声音量仍为 0，可能被系统策略限制", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "恢复失败，可能被系统策略限制；请检查勿扰权限和后台权限", Toast.LENGTH_LONG).show();
            }
        });

        Button stopButton = new Button(this);
        stopButton.setText("停止防静音/震动");
        root.addView(stopButton, matchWrap());

        stopButton.setOnClickListener(v -> stopGuard());

        Button dndSettingsButton = new Button(this);
        dndSettingsButton.setText("打开勿扰权限设置（iQOO 静音建议开启）");
        root.addView(dndSettingsButton, matchWrap());

        dndSettingsButton.setOnClickListener(v -> openNotificationPolicyAccessSettings());

        Button appSettingsButton = new Button(this);
        appSettingsButton.setText("打开本 App 系统设置");
        root.addView(appSettingsButton, matchWrap());

        appSettingsButton.setOnClickListener(v -> openAppDetails());

        TextView note = new TextView(this);
        note.setText(buildNoteText());
        note.setTextSize(14);
        root.addView(note, matchWrap());

        TextView logTitle = new TextView(this);
        logTitle.setText("\n诊断日志（最近事件，自上而下：新 → 旧）：");
        logTitle.setTextSize(16);
        root.addView(logTitle, matchWrap());

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        root.addView(logView, matchWrap());

        Button copyLogButton = new Button(this);
        copyLogButton.setText("复制诊断日志");
        root.addView(copyLogButton, matchWrap());

        copyLogButton.setOnClickListener(v -> {
            String text = DiagLog.read(this);

            if (text == null || text.isEmpty()) {
                Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("RingerGuard 诊断日志", text));
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        Button clearLogButton = new Button(this);
        clearLogButton.setText("清空诊断日志");
        root.addView(clearLogButton, matchWrap());

        clearLogButton.setOnClickListener(v -> {
            DiagLog.clear(this);
            updateLog();
            Toast.makeText(this, "已清空日志", Toast.LENGTH_SHORT).show();
        });

        setContentView(scrollView);

        updateStatus();
        updateLog();
    }

    private String buildNoteText() {
        String device = Build.MANUFACTURER + " / " + Build.BRAND + " / " + Build.MODEL;

        return "\n当前设备：\n" +
                device + "\n\n" +
                "为什么这版省电又不“假死”：\n" +
                "本版不再常驻前台服务、不再每隔几分钟自检。运营商来电由系统在响铃前" +
                "主动唤起本应用处理，平时没有任何后台进程，所以既省电、也不存在" +
                "“服务还在但功能失效”的问题。\n\n" +
                "运营商来电必响的关键：\n" +
                "请点“授予来电筛选权限”，把本应用设为来电筛选应用。否则系统不会在来电时唤起本应用。\n\n" +
                "iQOO / OriginOS 建议设置：\n" +
                "1. 授予来电筛选权限（最重要）。\n" +
                "2. 允许勿扰权限（处理勿扰式静音）。\n" +
                "3. 允许自启动、允许后台运行。\n" +
                "4. 在最近任务中锁定本 App。\n" +
                "5. 不要手动“强行停止”本 App。\n\n" +
                "说明：\n" +
                "1. 微信等网络电话无法精确拦截，只靠每 4 小时兜底“尽量保持有声”，可能偶尔没声音。\n" +
                "2. 为修复 iQOO 静音，铃声音量为 0 时会恢复到上次记录的非 0 铃声音量。\n" +
                "3. 不修改媒体音量、闹钟音量。\n" +
                "4. 如果被“强行停止”或被系统深度睡眠强停，系统将无法在来电时唤起本应用，需重新打开一次。";
    }

    private void startGuard() {
        AudioGuard.rememberCurrentRingVolumeIfNonZero(this);
        AudioGuard.setEnabled(this, true);

        RingerJobService.schedule(this);
        AudioGuard.enforce(this, "开启守护");

        updateStatus();

        if (!isCallScreeningRoleHeld()) {
            Toast.makeText(this, "已开启。请授予“来电筛选”权限，运营商来电才能必响。", Toast.LENGTH_LONG).show();
            requestCallScreeningRole();
        } else if (needsNotificationPolicyAccess()) {
            Toast.makeText(this, "已开启。iQOO 静音建议允许勿扰权限。", Toast.LENGTH_LONG).show();
            openNotificationPolicyAccessSettings();
        } else {
            Toast.makeText(this, "已开启", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopGuard() {
        AudioGuard.setEnabled(this, false);
        RingerJobService.cancel(this);
        updateStatus();

        Toast.makeText(this, "已停止（如需彻底关闭，可在系统设置撤销“来电筛选”权限）", Toast.LENGTH_LONG).show();
    }

    private void requestCallScreeningRole() {
        RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);

        if (roleManager == null) {
            Toast.makeText(this, "本机不支持来电筛选角色", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                Toast.makeText(this, "本机不支持来电筛选角色", Toast.LENGTH_LONG).show();
                return;
            }

            if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                Toast.makeText(this, "已持有来电筛选权限", Toast.LENGTH_SHORT).show();
                updateStatus();
                return;
            }

            Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING);
            startActivityForResult(intent, REQ_CALL_SCREENING_ROLE);
        } catch (Exception e) {
            Toast.makeText(this, "无法申请来电筛选权限：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean isCallScreeningRoleHeld() {
        RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);

        if (roleManager == null) {
            return false;
        }

        try {
            return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
                    && roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CALL_SCREENING_ROLE) {
            if (isCallScreeningRoleHeld()) {
                Toast.makeText(this, "来电筛选权限已授予，运营商来电将自动恢复响铃", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "未授予来电筛选权限；运营商来电可能无法自动响铃", Toast.LENGTH_LONG).show();
            }

            updateStatus();
        }
    }

    private void updateStatus() {
        if (statusView == null) {
            return;
        }

        int mode = AudioGuard.getCurrentRingerMode(this);
        int currentVolume = AudioGuard.getCurrentRingVolumeReadOnly(this);
        int maxVolume = AudioGuard.getMaxRingVolumeReadOnly(this);
        int recoverVolume = AudioGuard.getSavedRecoverRingVolumeReadOnly(this);
        boolean muted = AudioGuard.isRingStreamMutedReadOnly(this);
        int interruptionFilter = AudioGuard.getCurrentInterruptionFilter(this);

        statusView.setText(
                "\n当前状态：\n" +
                        "守护：" + (AudioGuard.isEnabled(this) ? "已开启" : "未开启") + "\n" +
                        "来电筛选权限：" + (isCallScreeningRoleHeld() ? "已授予" : "未授予（来电必响需要）") + "\n" +
                        "兜底任务：" + (RingerJobService.isScheduled(this) ? "已登记（约每 4 小时）" : "未登记") + "\n" +
                        "声音模式：" + AudioGuard.ringerModeToText(mode) + "\n" +
                        "当前铃声音量：" + currentVolume + " / " + maxVolume + "\n" +
                        "铃声流静音：" + (muted ? "是" : "否") + "\n" +
                        "iQOO 零音量恢复目标：" + recoverVolume + "\n" +
                        "勿扰权限：" + notificationPolicyAccessText() + "\n" +
                        "勿扰状态：" + AudioGuard.interruptionFilterToText(interruptionFilter) + "\n"
        );
    }

    private void updateLog() {
        if (logView == null) {
            return;
        }

        String text = DiagLog.read(this);

        if (text == null || text.isEmpty()) {
            logView.setText("（暂无日志。建议：清空日志 → 重启手机 → 手动静音 → 打来电测试 → 回到本页查看）");
        } else {
            logView.setText(text);
        }
    }

    private String notificationPolicyAccessText() {
        if (AudioGuard.hasNotificationPolicyAccess(this)) {
            return "已允许";
        }

        return "未允许（iQOO 静音建议开启）";
    }

    private boolean needsNotificationPolicyAccess() {
        return !AudioGuard.hasNotificationPolicyAccess(this);
    }

    private boolean isDoNotDisturbActive(int filter) {
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL
                && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                && filter != -1;
    }

    private void openNotificationPolicyAccessSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开勿扰权限设置，已尝试打开应用设置", Toast.LENGTH_LONG).show();
            openAppDetails();
        }
    }

    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开应用设置", Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );

        params.setMargins(0, dp(6), 0, dp(6));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
