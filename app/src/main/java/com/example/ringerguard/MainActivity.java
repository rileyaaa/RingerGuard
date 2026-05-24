package com.example.ringerguard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.app.NotificationManager;
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
                        "这个版本不会修改任何音量：\n" +
                        "不修改铃声音量\n" +
                        "不修改媒体音量\n" +
                        "不修改通知音量\n" +
                        "不修改闹钟音量\n\n" +
                        "它只改声音模式，不改音量大小。"
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

        Button fixButton = new Button(this);
        fixButton.setText("立即退出静音/震动");
        root.addView(fixButton, matchWrap());

        fixButton.setOnClickListener(v -> {
            boolean changed = AudioGuard.enforce(this);
            updateStatus();

            int mode = AudioGuard.getCurrentRingerMode(this);

            if (changed && mode == AudioManager.RINGER_MODE_NORMAL) {
                Toast.makeText(this, "已切回响铃模式", Toast.LENGTH_SHORT).show();
            } else if (mode == AudioManager.RINGER_MODE_NORMAL) {
                Toast.makeText(this, "当前已是响铃模式", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(
                        this,
                        "恢复失败，可能被勿扰模式或系统策略限制",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });


        Button policyButton = new Button(this);
        policyButton.setText("开启勿扰访问权限（iQOO 必开）");
        root.addView(policyButton, matchWrap());

        policyButton.setOnClickListener(v -> openNotificationPolicySettings());

        Button stopButton = new Button(this);
        stopButton.setText("停止防静音/震动");
        root.addView(stopButton, matchWrap());

        stopButton.setOnClickListener(v -> {
            AudioGuard.setEnabled(this, false);
            stopService(new Intent(this, GuardService.class));
            updateStatus();

            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
        });

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
                "OriginOS / vivo / iQOO 建议设置：\n" +
                "1. 允许本 App 通知权限\n" +
                "2. 打开本 App 自启动\n" +
                "3. 电池管理里允许后台运行或允许后台高耗电\n" +
                "4. 最近任务界面里锁定本 App\n\n" +
                "说明：\n" +
                "1. 当前铃声音量只读取显示，不会被 App 修改。\n" +
                "2. 你已经设置好的铃声音量会保持不动。\n" +
                "3. 妈妈误点静音或震动后，App 只负责切回响铃。\n" +
                "4. iQOO / vivo / OriginOS 必须打开‘勿扰访问权限’，否则只能处理震动，无法退出静音。\n" +
                "5. 如果手动强行停止 App，系统会阻止它后台恢复，需要重新打开一次。";
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

            Toast.makeText(
                    this,
                    "已开启，不会修改音量",
                    Toast.LENGTH_SHORT
            ).show();
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

        statusView.setText(
                "\n当前状态：\n" +
                        "守护：" + (AudioGuard.isEnabled(this) ? "已开启" : "未开启") + "\n" +
                        "声音模式：" + AudioGuard.ringerModeToText(mode) + "\n" +
                        "当前铃声音量：" + currentVolume + " / " + maxVolume + "，只读，不修改\n" +
                        "通知权限：" + notificationPermissionText() + "\n" +
                        "勿扰访问：" + notificationPolicyText() + "\n"
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


    private String notificationPolicyText() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return "系统无需授权";
        }

        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (nm != null && nm.isNotificationPolicyAccessGranted()) {
            return "已允许";
        }

        return "未允许（iQOO 必开）";
    }

    private void openNotificationPolicySettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                startActivity(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开勿扰权限设置", Toast.LENGTH_SHORT).show();
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
