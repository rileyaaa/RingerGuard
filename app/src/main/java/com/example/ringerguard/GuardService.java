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
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class GuardService extends Service {

    private static final String CHANNEL_ID = "ringer_guard_channel";
    private static final int NOTIFICATION_ID = 1001;

    private Handler handler;
    private NotificationManager notificationManager;
    private boolean receiverRegistered = false;

    private final Runnable enforceRunnable = new Runnable() {
        @Override
        public void run() {
            if (!AudioGuard.isEnabled(GuardService.this)) {
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

            if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)) {
                /*
                 * 不是轮询。
                 *
                 * 这里只是收到“声音模式变化”事件后，
                 * 稍微延迟 120ms，让系统状态稳定一下。
                 *
                 * 对 OriginOS / iQOO 这类系统更稳。
                 */
                scheduleEnforce(120);
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

        // 服务启动时检查一次，不循环。
        scheduleEnforce(0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AudioGuard.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 服务被系统恢复时检查一次，不循环。
        scheduleEnforce(0);

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

    private void scheduleEnforce(long delayMs) {
        if (handler == null) {
            return;
        }

        handler.removeCallbacks(enforceRunnable);
        handler.postDelayed(enforceRunnable, delayMs);
    }

    private void registerRingerModeReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                        ringerModeReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                registerReceiver(ringerModeReceiver, filter);
            }

            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "防静音防震动服务",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription("检测到静音或震动后自动切回响铃，不修改音量");
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
                .setContentText("只退出静音/震动，不修改音量")
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
