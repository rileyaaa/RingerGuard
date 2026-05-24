package com.example.ringerguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
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

    private Handler handler;
    private NotificationManager notificationManager;
    private boolean receiverRegistered = false;

    private ContentObserver settingsObserver;
    private boolean settingsObserverRegistered = false;

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

            if (isAudioRelatedAction(action)) {
                scheduleEnforceBurst();
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

        registerRingerModeReceiver();

        /*
         * 监听 Settings 变化。
         *
         * iQOO / OriginOS 有时不会发标准铃声模式广播，
         * 但会改系统设置项，例如音量、勿扰、厂商静音标记。
         *
         * 这是事件监听，不是定时轮询。
         */
        registerSettingsObserver();

        // 服务启动时做一组确认。
        scheduleEnforceBurst();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AudioGuard.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 服务被系统恢复时做一组确认。
        scheduleEnforceBurst();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(enforceRunnable);
        }

        if (receiverRegistered) {
            try {
                unregisterReceiver(ringerModeReceiver);
            } catch (Exception ignored) {
            }

            receiverRegistered = false;
        }

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

    private void scheduleEnforceBurst() {
        if (handler == null) {
            return;
        }

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
                scheduleEnforceBurst();
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                scheduleEnforceBurst();
            }
        };

        boolean registered = false;

        try {
            getContentResolver().registerContentObserver(
                    Settings.System.CONTENT_URI,
                    true,
                    settingsObserver
            );
            registered = true;
        } catch (Exception ignored) {
        }

        try {
            getContentResolver().registerContentObserver(
                    Settings.Global.CONTENT_URI,
                    true,
                    settingsObserver
            );
            registered = true;
        } catch (Exception ignored) {
        }

        try {
            getContentResolver().registerContentObserver(
                    Settings.Secure.CONTENT_URI,
                    true,
                    settingsObserver
            );
            registered = true;
        } catch (Exception ignored) {
        }

        settingsObserverRegistered = registered;
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
