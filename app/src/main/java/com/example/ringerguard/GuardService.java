package com.example.ringerguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

public class GuardService extends Service {

    private static final String CHANNEL_ID = "ringer_guard_channel";
    private static final int NOTIFICATION_ID = 1001;

    /**
     * 部分系统会发送这个隐藏广播。
     *
     * iQOO / OriginOS 有时候静音不走标准 RINGER_MODE_CHANGED_ACTION，
     * 但会走音量变化或内部铃声模式变化。
     */
    private static final String ACTION_VOLUME_CHANGED =
            "android.media.VOLUME_CHANGED_ACTION";

    private static final String EXTRA_VOLUME_STREAM_TYPE =
            "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private static final String ACTION_INTERNAL_RINGER_MODE_CHANGED =
            "android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION";

    /**
     * OriginOS 有时会后写入静音状态。
     *
     * 如果只收到事件后立即恢复一次，
     * 系统后面又把静音写回去，就会表现为“震动能恢复，静音恢复不了”。
     *
     * 所以收到变化后做一组短延迟确认。
     * 这不是长期轮询，只是事件后的短时间补偿。
     */
    private static final long[] ENFORCE_DELAYS_MS =
            new long[]{0L, 120L, 450L, 1200L, 3000L};

    /**
     * 同一次音频变化经常会同时触发多个广播和多个 Settings 回调。
     * 这里做短节流，避免同一轮变化重复排队；不会形成轮询。
     */
    private static final long ENFORCE_BURST_DEBOUNCE_MS = 650L;

    private Handler handler;
    private NotificationManager notificationManager;
    private boolean receiverRegistered = false;

    private ContentObserver settingsObserver;
    private boolean settingsObserverRegistered = false;
    private long lastEnforceBurstAtMs = 0L;

    private final Runnable enforceRunnable = new Runnable() {
        @Override
        public void run() {
            if (!AudioGuard.isEnabled(GuardService.this)) {
                if (handler != null) {
                    handler.removeCallbacks(this);
                }

                stopSelf();
                return;
            }

            AudioGuard.enforce(GuardService.this);
        }
    };

    private final BroadcastReceiver ringerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }

            String action = intent.getAction();

            if (isAudioRelatedAction(action) && isRelevantAudioEvent(intent)) {
                scheduleEnforceBurst(false);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        handler = new Handler(Looper.getMainLooper());
        notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        createNotificationChannel();

        /*
         * 前台服务不是把 App 界面放在最前面。
         * 它只是在通知栏显示一个安静通知，提高后台存活率。
         */
        startForeground(NOTIFICATION_ID, buildNotification());

        ensureListenersRegistered();

        // 服务启动时做一组确认。
        scheduleEnforceBurst(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AudioGuard.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 服务被系统恢复或被再次 startService 时，确认监听链路仍然挂着。
        ensureListenersRegistered();
        scheduleEnforceBurst(true);

        return START_STICKY;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);

        if (!AudioGuard.isEnabled(this)) {
            return;
        }

        /*
         * 只在真正内存压力或后台清理阶段重挂监听。
         *
         * TRIM_MEMORY_UI_HIDDEN 只是界面退到后台，不代表监听失效，
         * 不在这里处理，避免用户每次退出界面都触发额外操作。
         */
        boolean shouldRebind = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;

        if (shouldRebind) {
            rebindListeners();
            scheduleEnforceBurst(true);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();

        if (!AudioGuard.isEnabled(this)) {
            return;
        }

        rebindListeners();
        scheduleEnforceBurst(true);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);

        if (!AudioGuard.isEnabled(this)) {
            return;
        }

        /*
         * 用户/系统把最近任务移除时，前台服务理论上仍应继续运行。
         * 但部分 OriginOS 版本可能只留下通知或让动态监听链路异常。
         * 这里不做定时保活，只做一次被动恢复尝试。
         */
        ensureListenersRegistered();
        scheduleEnforceBurst(true);
        requestServiceStart();
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(enforceRunnable);
        }

        unregisterRingerModeReceiver();
        unregisterSettingsObserver();

        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureListenersRegistered() {
        registerRingerModeReceiver();
        registerSettingsObserver();
    }

    private void rebindListeners() {
        unregisterRingerModeReceiver();
        unregisterSettingsObserver();
        ensureListenersRegistered();
    }

    private void unregisterRingerModeReceiver() {
        if (!receiverRegistered) {
            return;
        }

        try {
            unregisterReceiver(ringerModeReceiver);
        } catch (Exception ignored) {
        }

        receiverRegistered = false;
    }

    private void requestServiceStart() {
        Intent serviceIntent = new Intent(this, GuardService.class);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isAudioRelatedAction(String action) {
        if (action == null) {
            return false;
        }

        return AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)
                || ACTION_INTERNAL_RINGER_MODE_CHANGED.equals(action)
                || ACTION_VOLUME_CHANGED.equals(action)
                || NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(action)
                || NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED.equals(action)
                || NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED.equals(action);
    }

    private boolean isRelevantAudioEvent(Intent intent) {
        if (intent == null) {
            return false;
        }

        String action = intent.getAction();

        if (!ACTION_VOLUME_CHANGED.equals(action)) {
            return true;
        }

        int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, Integer.MIN_VALUE);

        /*
         * 有些厂商 ROM 可能不带 stream extra。此时保守处理，避免漏掉铃声变化。
         */
        if (stream == Integer.MIN_VALUE) {
            return true;
        }

        return stream == AudioManager.STREAM_RING
                || stream == AudioManager.STREAM_NOTIFICATION;
    }

    private void scheduleEnforceBurst(boolean force) {
        if (handler == null) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (!force && now - lastEnforceBurstAtMs < ENFORCE_BURST_DEBOUNCE_MS) {
            return;
        }

        lastEnforceBurstAtMs = now;
        handler.removeCallbacks(enforceRunnable);

        for (long delay : ENFORCE_DELAYS_MS) {
            handler.postDelayed(enforceRunnable, delay);
        }
    }

    private void registerRingerModeReceiver() {
        if (receiverRegistered) {
            return;
        }

        try {
            IntentFilter filter = new IntentFilter();

            filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            filter.addAction(ACTION_INTERNAL_RINGER_MODE_CHANGED);
            filter.addAction(ACTION_VOLUME_CHANGED);
            filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
            filter.addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED);
            filter.addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED);

            /*
             * Android 13+ 动态广播需要指定 exported 标记。
             *
             * 这里用 RECEIVER_EXPORTED 是为了兼容部分厂商系统：
             * 有些音频/静音广播不是 system UID 直接发出，
             * 用 NOT_EXPORTED 可能收不到。
             *
             * 这个 Receiver 只会触发 enforce，不读取外部数据，
             * 被其它 App 伪造广播的风险很低。
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                        ringerModeReceiver,
                        filter,
                        Context.RECEIVER_EXPORTED
                );
            } else {
                registerReceiver(ringerModeReceiver, filter);
            }

            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void registerSettingsObserver() {
        if (handler == null) {
            return;
        }

        if (settingsObserverRegistered) {
            return;
        }

        settingsObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                scheduleEnforceBurst(false);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                scheduleEnforceBurst(false);
            }
        };

        boolean registered = false;

        /*
         * 不再监听整棵 Settings.System / Global / Secure。
         * 只监听和铃声、通知音量、响铃模式、勿扰直接相关的 URI，
         * 避免亮度、输入法、系统杂项变化也唤醒本服务逻辑。
         */
        registered |= registerSettingUri(Settings.System.getUriFor("mode_ringer"));
        registered |= registerSettingUri(Settings.System.getUriFor("volume_ring"));
        registered |= registerSettingUri(Settings.System.getUriFor("volume_notification"));
        registered |= registerSettingUri(Settings.System.getUriFor("mute_streams_affected"));

        // 少数 vivo/iQOO ROM 可能使用带 speaker 后缀的音量项。
        registered |= registerSettingUri(Settings.System.getUriFor("volume_ring_speaker"));
        registered |= registerSettingUri(Settings.System.getUriFor("volume_notification_speaker"));

        // 勿扰相关。
        registered |= registerSettingUri(Settings.Global.getUriFor("zen_mode"));
        registered |= registerSettingUri(Settings.Global.getUriFor("zen_mode_config_etag"));

        settingsObserverRegistered = registered;
    }

    private boolean registerSettingUri(Uri uri) {
        if (settingsObserver == null || uri == null) {
            return false;
        }

        try {
            getContentResolver().registerContentObserver(
                    uri,
                    false,
                    settingsObserver
            );
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void unregisterSettingsObserver() {
        if (settingsObserverRegistered && settingsObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(settingsObserver);
            } catch (Exception ignored) {
            }
        }

        settingsObserverRegistered = false;
        settingsObserver = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "防静音防震动服务",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription("检测静音、震动、勿扰或 iQOO 零铃声音量后自动恢复响铃");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("防静音防震动运行中")
                .setContentText("兼容 iQOO 静音：必要时恢复铃声音量")
                .setSmallIcon(R.drawable.ic_stat_guard)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }
}
