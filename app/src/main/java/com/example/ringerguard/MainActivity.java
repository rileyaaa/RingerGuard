package com.example.ringerguard;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
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

    private static final int REQ_POST_NOTIFICATIONS = 1001;

    private TextView statusView;
    private boolean pendingStartAfterNotificationPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();

        /*
         * 如果当前是正常非 0 铃声音量，顺手记住。
         * 如果用户刚从勿扰权限页面回来，并且守护已开启，立即再恢复一次。
         */
        AudioGuard.rememberCurrentRingVolumeIfNonZero(this);

        if (AudioGuard.isEnabled(this)) {
            AudioGuard.enforce(this);
        }

        updateStatus();
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
                "\n功能：\n" +
                        "手机一旦进入静音或震动，就自动切回响铃。\n\n" +
                        "iQOO / OriginOS 兼容处理：\n" +
                        "1. 标准静音/震动：切回响铃模式。\n" +
                        "2. 勿扰式静音：有勿扰权限时关闭勿扰。\n" +
                        "3. 零音量式静音：只在铃声音量为 0 时，恢复到上次非 0 铃声音量。\n\n" +
                        "不会修改媒体音量、闹钟音量。\n" +
                        "为了解决 iQOO 静音，铃声音量为 0 时会被恢复。"
        );
        desc.setTextSize(15);
        root.addView(desc, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(15);
        root.addView(statusView, matchWrap());

        Button startButton = new Button(this);
        startButton.setText("开启防静音/震动");
        root.addView(startButton, matchWrap());

        startButton.setOnClickListener(v -> startGuardWithPermissionCheck());

        Button rememberVolumeButton = new Button(this);
        rememberVolumeButton.setText("记住当前铃声音量作为恢复音量");
        root.addView(rememberVolumeButton, matchWrap());

        rememberVolumeButton.setOnClickListener(v -> {
            boolean saved = AudioGuard.rememberCurrentRingVolumeIfNonZero(this);
            updateStatus();

            if (saved) {
                Toast.makeText(
                        this,
                        "已记住当前铃声音量",
                        Toast.LENGTH_SHORT
                ).show();
            } else {
                Toast.makeText(
                        this,
                        "当前铃声音量为 0，不能作为恢复音量",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        Button fixButton = new Button(this);
        fixButton.setText("立即退出静音/震动/勿扰");
        root.addView(fixButton, matchWrap());

        fixButton.setOnClickListener(v -> {
            boolean changed = AudioGuard.enforce(this);
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
                    Toast.makeText(
                            this,
                            "已恢复到可响铃状态",
                            Toast.LENGTH_SHORT
                    ).show();
                } else {
                    Toast.makeText(
                            this,
                            "当前已是可响铃状态",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            } else if (dndActive && !AudioGuard.hasNotificationPolicyAccess(this)) {
                Toast.makeText(
                        this,
                        "恢复失败：iQOO 静音可能是勿扰，请先允许勿扰权限",
                        Toast.LENGTH_LONG
                ).show();

                openNotificationPolicyAccessSettings();
            } else if (volume == 0) {
                Toast.makeText(
                        this,
                        "恢复失败：铃声音量仍为 0，可能被系统策略限制",
                        Toast.LENGTH_LONG
                ).show();
            } else {
                Toast.makeText(
                        this,
                        "恢复失败，可能被系统策略限制；请检查勿扰权限和后台权限",
                        Toast.LENGTH_LONG
                ).show();
            }
        });

        Button stopButton = new Button(this);
        stopButton.setText("停止防静音/震动");
        root.addView(stopButton, matchWrap());

        stopButton.setOnClickListener(v -> {
            AudioGuard.setEnabled(this, false);
            stopService(new Intent(this, GuardService.class));
            updateStatus();

            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
        });

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

        setContentView(scrollView);

        updateStatus();
    }

    private String buildNoteText() {
        String device = Build.MANUFACTURER + " / " + Build.BRAND + " / " + Build.MODEL;

        return "\n当前设备：\n" +
                device + "\n\n" +
                "iQOO Z9 Turbo / OriginOS 重点：\n" +
                "如果“震动能恢复、静音恢复不了”，通常不是震动逻辑的问题，" +
                "而是 OriginOS 的静音可能被做成了：\n" +
                "1. 勿扰模式\n" +
                "2. 铃声流静音\n" +
                "3. 铃声音量 0\n\n" +
                "所以本版本增加了 iQOO 兼容处理：\n" +
                "1. 标准静音/震动会切回响铃。\n" +
                "2. 勿扰式静音需要你允许“勿扰权限”。\n" +
                "3. 铃声音量为 0 时，会恢复到上次非 0 铃声音量。\n\n" +
                "OriginOS / vivo / iQOO 建议设置：\n" +
                "1. 允许本 App 通知权限。\n" +
                "2. 点“打开勿扰权限设置”，允许本 App 的勿扰权限。\n" +
                "3. 打开本 App 自启动。\n" +
                "4. 电池管理里允许后台运行或允许后台高耗电。\n" +
                "5. 最近任务界面里锁定本 App。\n\n" +
                "说明：\n" +
                "1. 本版不会修改媒体音量、闹钟音量。\n" +
                "2. 为了修复 iQOO 静音，铃声音量为 0 时会被恢复。\n" +
                "3. 恢复目标是上次记录到的非 0 铃声音量。\n" +
                "4. 如果从未记录过非 0 铃声音量，会默认恢复到最大铃声音量的一半。\n" +
                "5. 如果手机打开了勿扰，并且你没有给勿扰权限，静音可能仍然恢复不了。\n" +
                "6. 如果手动强行停止 App，系统会阻止它后台恢复，需要重新打开一次。";
    }

    private void startGuardWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingStartAfterNotificationPermission = true;

                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIFICATIONS
                );

                return;
            }
        }

        startGuardNow();
    }

    private void startGuardNow() {
        /*
         * 开启前先记住当前非 0 铃声音量。
         */
        AudioGuard.rememberCurrentRingVolumeIfNonZero(this);

        AudioGuard.setEnabled(this, true);

        Intent intent = new Intent(this, GuardService.class);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            AudioGuard.enforce(this);
            updateStatus();

            if (needsNotificationPolicyAccess()) {
                Toast.makeText(
                        this,
                        "已开启。iQOO 静音建议允许勿扰权限，接下来请在列表里允许本 App。",
                        Toast.LENGTH_LONG
                ).show();

                openNotificationPolicyAccessSettings();
            } else {
                Toast.makeText(
                        this,
                        "已开启 iQOO 兼容守护",
                        Toast.LENGTH_SHORT
                ).show();
            }
        } catch (Exception e) {
            AudioGuard.setEnabled(this, false);
            updateStatus();

            Toast.makeText(
                    this,
                    "启动失败：" + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_POST_NOTIFICATIONS
                && pendingStartAfterNotificationPermission) {
            pendingStartAfterNotificationPermission = false;

            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (!granted) {
                Toast.makeText(
                        this,
                        "通知未允许，OriginOS 后台稳定性可能变差",
                        Toast.LENGTH_LONG
                ).show();
            }

            startGuardNow();
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
                        "声音模式：" + AudioGuard.ringerModeToText(mode) + "\n" +
                        "当前铃声音量：" + currentVolume + " / " + maxVolume + "\n" +
                        "铃声流静音：" + (muted ? "是" : "否") + "\n" +
                        "iQOO 零音量恢复目标：" + recoverVolume + "\n" +
                        "通知权限：" + notificationPermissionText() + "\n" +
                        "勿扰权限：" + notificationPolicyAccessText() + "\n" +
                        "勿扰状态：" + AudioGuard.interruptionFilterToText(interruptionFilter) + "\n"
        );
    }

    private String notificationPermissionText() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "无需单独授权";
        }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return "已允许";
        }

        return "未允许";
    }

    private String notificationPolicyAccessText() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "无需单独授权";
        }

        if (AudioGuard.hasNotificationPolicyAccess(this)) {
            return "已允许";
        }

        return "未允许（iQOO 静音建议开启）";
    }

    private boolean needsNotificationPolicyAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !AudioGuard.hasNotificationPolicyAccess(this);
    }

    private boolean isDoNotDisturbActive(int filter) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }

        return filter != NotificationManager.INTERRUPTION_FILTER_ALL
                && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                && filter != -1;
    }

    private void openNotificationPolicyAccessSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "无法打开勿扰权限设置，已尝试打开应用设置",
                    Toast.LENGTH_LONG
            ).show();

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
