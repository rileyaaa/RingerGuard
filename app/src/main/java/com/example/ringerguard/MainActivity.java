package com.example.ringerguard;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_CALL_SCREENING_ROLE = 2001;
    private static final int REQ_READ_CONTACTS = 2002;

    /** 是否已向用户弹过一次通讯录系统授权框（用于区分“首次申请”与“永久拒绝”）。 */
    private static final String KEY_CONTACTS_ASKED = "contacts_permission_asked";

    private TextView statusView;

    private Button mainToggleButton;
    private Button fixButton;
    private Button roleButton;
    private Button contactsButton;
    private Button dndButton;

    /**
     * 是否正处于“首次开启”的权限引导链中。
     *
     * 引导链每次只申请“当前第一个缺失的权限”，并在各自的回调里继续推进；
     * 已授予的权限会被自动跳过，因此通讯录等权限只会在未授予时弹一次，
     * 授予后永不再问、也不再显示对应按钮。
     */
    private boolean guidingPermissions = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 打开界面时：顺手记住当前非 0 铃声音量；若守护已开启，立即再恢复一次。
        AudioGuard.rememberCurrentRingVolumeIfNonZero(this);

        if (AudioGuard.isEnabled(this)) {
            AudioGuard.enforce(this);
        }

        updateStatus();
        refreshButtons();
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
                        "1. 来电（含通讯录联系人与陌生号码）：系统在响铃前自动把铃声切回响铃。\n" +
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

        // 主开关：根据当前守护状态在“开启 / 停止”之间切换文案与行为。
        mainToggleButton = new Button(this);
        root.addView(mainToggleButton, matchWrap());
        mainToggleButton.setOnClickListener(v -> {
            if (AudioGuard.isEnabled(this)) {
                stopGuard();
            } else {
                startGuard();
            }
        });

        fixButton = new Button(this);
        fixButton.setText("立即退出静音/震动/勿扰");
        root.addView(fixButton, matchWrap());
        fixButton.setOnClickListener(v -> manualFix());

        roleButton = new Button(this);
        roleButton.setText("授予来电筛选权限（来电自动响铃的关键）");
        root.addView(roleButton, matchWrap());
        roleButton.setOnClickListener(v -> requestCallScreeningRole());

        contactsButton = new Button(this);
        contactsButton.setText("允许读取通讯录（通讯录来电也自动响）");
        root.addView(contactsButton, matchWrap());
        contactsButton.setOnClickListener(v -> requestContactsPermission());

        dndButton = new Button(this);
        dndButton.setText("打开勿扰权限设置（iQOO 静音建议开启）");
        root.addView(dndButton, matchWrap());
        dndButton.setOnClickListener(v -> openNotificationPolicyAccessSettings());

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
        refreshButtons();
    }

    private String buildNoteText() {
        String device = Build.MANUFACTURER + " / " + Build.BRAND + " / " + Build.MODEL;

        return "\n当前设备：\n" +
                device + "\n\n" +
                "为什么这版省电又不“假死”：\n" +
                "本版不常驻前台服务、不定时自检。来电由系统在响铃前主动唤起本应用处理，" +
                "平时没有任何后台进程，既省电、也不存在“服务还在但功能失效”的问题。\n\n" +
                "让来电自动恢复响铃的关键：\n" +
                "1. 授予“来电筛选”权限：否则系统通常不会在来电时唤起本应用。\n" +
                "2. 允许“读取通讯录”：系统默认只把陌生号码交给来电筛选，授予后通讯录联系人来电" +
                "才会同样恢复响铃。本应用仅凭此权限让系统放行通讯录来电，不读取任何联系人数据。\n\n" +
                "iQOO / OriginOS 建议设置：\n" +
                "1. 授予来电筛选 + 通讯录权限（最重要）。\n" +
                "2. 如为勿扰式静音，再允许勿扰权限。\n" +
                "3. 允许自启动、允许后台运行，并在最近任务中锁定本 App。\n" +
                "4. 不要手动“强行停止”本 App。\n\n" +
                "说明：\n" +
                "1. 微信等网络电话不走运营商电话栈，只靠每 4 小时兜底“尽量保持有声”，可能偶尔没声音。\n" +
                "2. 为修复 iQOO 静音，铃声音量为 0 时会恢复到上次记录的非 0 铃声音量。\n" +
                "3. 不修改媒体音量、闹钟音量。\n" +
                "4. 如果被“强行停止”或被系统深度睡眠强停，系统将无法在来电时唤起本应用，需重新打开一次。";
    }

    private void startGuard() {
        AudioGuard.rememberCurrentRingVolumeIfNonZero(this);
        AudioGuard.setEnabled(this, true);

        RingerJobService.schedule(this);
        AudioGuard.enforce(this);

        updateStatus();
        refreshButtons();

        if (keyPermissionsReady()) {
            Toast.makeText(this, "已开启", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已开启守护，请继续授予关键权限", Toast.LENGTH_LONG).show();
        }

        // 首次开启：依次引导授予缺失的关键权限（已授予的会自动跳过、不再打扰）。
        guidingPermissions = true;
        requestNextMissingPermission();
    }

    private void stopGuard() {
        AudioGuard.setEnabled(this, false);
        RingerJobService.cancel(this);
        guidingPermissions = false;

        updateStatus();
        refreshButtons();

        Toast.makeText(this, "已停止（如需彻底关闭，可在系统设置撤销“来电筛选”权限）", Toast.LENGTH_LONG).show();
    }

    /**
     * 首次开启时的权限引导链：每次只申请“当前第一个缺失的权限”，
     * 在对应回调里再次调用本方法推进到下一个。已授予的权限会被跳过，
     * 因此每个权限最多只弹一次。
     */
    private void requestNextMissingPermission() {
        // 来电筛选角色：仅当设备支持且尚未持有时才弹；设备不支持则跳过，避免引导链卡住。
        if (isCallScreeningRoleSupported() && !isCallScreeningRoleHeld()) {
            Toast.makeText(this, "请授予“来电筛选”权限，来电才能自动恢复响铃", Toast.LENGTH_LONG).show();
            requestCallScreeningRole();
            return;
        }

        // 通讯录权限：仅当还能弹系统授权框时才申请；已被永久拒绝则跳过，留给用户点按钮去设置开启。
        if (!hasContactsPermission() && !contactsPermanentlyDenied()) {
            Toast.makeText(this, "请允许“读取通讯录”，通讯录联系人来电才会自动恢复响铃", Toast.LENGTH_LONG).show();
            requestContactsPermission();
            return;
        }

        // 引导链结束。
        guidingPermissions = false;

        if (keyPermissionsReady()) {
            if (needsNotificationPolicyAccess()) {
                // 勿扰为可选项（仅 iQOO 勿扰式静音需要），不强行跳转，仅保留按钮供按需开启。
                Toast.makeText(this, "已就绪。如使用 iQOO 勿扰式静音，可再开启勿扰权限。", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已就绪：来电（含通讯录）会自动恢复响铃", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "仍有关键权限未授予，可点下方按钮或到系统设置补齐", Toast.LENGTH_LONG).show();
        }
    }

    private void manualFix() {
        boolean changed = AudioGuard.enforce(this);
        updateStatus();
        refreshButtons();

        int mode = AudioGuard.getCurrentRingerMode(this);
        int volume = AudioGuard.getCurrentRingVolumeReadOnly(this);
        boolean muted = AudioGuard.isRingStreamMutedReadOnly(this);
        int filter = AudioGuard.getCurrentInterruptionFilter(this);
        boolean dndActive = isDoNotDisturbActive(filter);

        if (mode == AudioManager.RINGER_MODE_NORMAL
                && volume > 0
                && !muted
                && !dndActive) {
            Toast.makeText(this,
                    changed ? "已恢复到可响铃状态" : "当前已是可响铃状态",
                    Toast.LENGTH_SHORT).show();
        } else if (dndActive && !AudioGuard.hasNotificationPolicyAccess(this)) {
            Toast.makeText(this, "恢复失败：iQOO 静音可能是勿扰，请先允许勿扰权限", Toast.LENGTH_LONG).show();
            openNotificationPolicyAccessSettings();
        } else if (volume == 0) {
            Toast.makeText(this, "恢复失败：铃声音量仍为 0，可能被系统策略限制", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "恢复失败，可能被系统策略限制；请检查勿扰权限和后台权限", Toast.LENGTH_LONG).show();
        }
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
                refreshButtons();
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

    private boolean isCallScreeningRoleSupported() {
        RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);

        if (roleManager == null) {
            return false;
        }

        try {
            return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING);
        } catch (Exception e) {
            return false;
        }
    }

    /** 关键权限（来电筛选角色 + 通讯录）是否都已就绪。设备不支持角色时只看通讯录。 */
    private boolean keyPermissionsReady() {
        boolean roleOk = !isCallScreeningRoleSupported() || isCallScreeningRoleHeld();
        return roleOk && hasContactsPermission();
    }

    /**
     * 通讯录权限是否被“永久拒绝”：已请求过一次，且系统不再允许展示申请理由
     * （用户勾过“不再询问”或多次拒绝）。此时再调 requestPermissions 不会弹框。
     */
    private boolean contactsPermanentlyDenied() {
        boolean asked = AudioGuard.prefs(this).getBoolean(KEY_CONTACTS_ASKED, false);
        return asked && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS);
    }

    private boolean hasContactsPermission() {
        try {
            return checkSelfPermission(Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private void requestContactsPermission() {
        if (hasContactsPermission()) {
            Toast.makeText(this, "已授予通讯录权限", Toast.LENGTH_SHORT).show();
            updateStatus();
            refreshButtons();
            return;
        }

        // 已被“永久拒绝”（请求过且系统不再展示申请理由）：系统授权框不会再弹，
        // 直接引导到本 App 系统设置里手动开启，避免用户“点了没反应”。
        if (contactsPermanentlyDenied()) {
            Toast.makeText(this, "通讯录权限已被拒绝，请在系统设置中手动开启“通讯录”", Toast.LENGTH_LONG).show();
            openAppDetails();
            return;
        }

        try {
            AudioGuard.prefs(this).edit().putBoolean(KEY_CONTACTS_ASKED, true).apply();
            requestPermissions(
                    new String[]{Manifest.permission.READ_CONTACTS},
                    REQ_READ_CONTACTS);
        } catch (Exception e) {
            Toast.makeText(this, "无法申请通讯录权限：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CALL_SCREENING_ROLE) {
            boolean held = isCallScreeningRoleHeld();

            if (held) {
                Toast.makeText(this, "来电筛选权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未授予来电筛选权限；来电可能无法自动响铃", Toast.LENGTH_LONG).show();
            }

            updateStatus();
            refreshButtons();

            // 引导链推进：授予则继续申请下一个缺失权限；用户拒绝则结束引导，不再反复弹窗。
            if (guidingPermissions) {
                if (held) {
                    requestNextMissingPermission();
                } else {
                    guidingPermissions = false;
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_READ_CONTACTS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            Toast.makeText(this,
                    granted
                            ? "通讯录权限已授予：通讯录来电将自动恢复响铃"
                            : "未授予通讯录权限：通讯录联系人来电可能不会自动响铃",
                    Toast.LENGTH_LONG).show();

            updateStatus();
            refreshButtons();

            // 引导链推进：授予则继续；用户拒绝则结束引导，不再反复弹窗。
            if (guidingPermissions) {
                if (granted) {
                    requestNextMissingPermission();
                } else {
                    guidingPermissions = false;
                }
            }
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

        String guardText;
        if (!AudioGuard.isEnabled(this)) {
            guardText = "未开启";
        } else if (keyPermissionsReady()) {
            guardText = "已开启";
        } else {
            guardText = "已开启，但关键权限缺失";
        }

        statusView.setText(
                "\n当前状态：\n" +
                        "守护：" + guardText + "\n" +
                        "来电筛选权限：" + (isCallScreeningRoleHeld() ? "已授予" : "未授予（来电响铃需要）") + "\n" +
                        "通讯录权限：" + (hasContactsPermission() ? "已授予" : "未授予（通讯录来电响铃需要）") + "\n" +
                        "兜底任务：" + (RingerJobService.isScheduled(this) ? "已登记（约每 4 小时）" : "未登记") + "\n" +
                        "声音模式：" + AudioGuard.ringerModeToText(mode) + "\n" +
                        "当前铃声音量：" + currentVolume + " / " + maxVolume + "\n" +
                        "铃声流静音：" + (muted ? "是" : "否") + "\n" +
                        "零音量恢复目标：" + recoverVolume + "\n" +
                        "勿扰权限：" + notificationPolicyAccessText() + "\n" +
                        "勿扰状态：" + AudioGuard.interruptionFilterToText(interruptionFilter) + "\n"
        );
    }

    /**
     * 按需显隐：守护未开启时界面只保留“开启”主开关与系统设置入口，尽量精简；
     * 守护开启后才显示“立即修复”与“仍缺失的权限”引导按钮，权限授予后对应按钮自动消失。
     */
    private void refreshButtons() {
        if (mainToggleButton == null) {
            return;
        }

        boolean enabled = AudioGuard.isEnabled(this);

        mainToggleButton.setText(enabled ? "停止防静音/震动" : "开启防静音/震动");

        fixButton.setVisibility(enabled ? View.VISIBLE : View.GONE);

        roleButton.setVisibility(enabled && !isCallScreeningRoleHeld() ? View.VISIBLE : View.GONE);
        contactsButton.setVisibility(enabled && !hasContactsPermission() ? View.VISIBLE : View.GONE);
        dndButton.setVisibility(enabled && needsNotificationPolicyAccess() ? View.VISIBLE : View.GONE);
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
