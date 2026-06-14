package com.example.ringerguard;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

public final class AudioGuard {

    public static final String PREFS = "ringer_guard_prefs";
    public static final String KEY_ENABLED = "enabled";

    /**
     * iQOO / OriginOS 兼容：
     *
     * 有些 iQOO 的“静音”不是标准 RINGER_MODE_SILENT，
     * 而是把铃声音量压到 0，或者把铃声流 mute。
     *
     * 为了能恢复来电响铃，需要记住上一次非 0 铃声音量。
     */
    public static final String KEY_LAST_NON_ZERO_RING_VOLUME = "last_non_zero_ring_volume";

    private AudioGuard() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }

        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }

        prefs(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    private static AudioManager audio(Context context) {
        return (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
    }

    private static NotificationManager notification(Context context) {
        return (NotificationManager) context.getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * 核心逻辑：把设备恢复到“能响铃”的状态。
     *
     * 1. 标准 Android 静音/震动：
     *    RINGER_MODE_SILENT / RINGER_MODE_VIBRATE -> RINGER_MODE_NORMAL
     *
     * 2. iQOO / OriginOS 勿扰式静音：
     *    如果用户授予了勿扰权限，则关闭勿扰。
     *
     * 3. iQOO / OriginOS 零音量式静音：
     *    如果铃声音量被压到 0，则恢复到上一次非 0 铃声音量。
     *
     * 4. iQOO / OriginOS mute 流式静音：
     *    如果铃声流被 mute，则尝试 unmute。
     *
     * 注意：为兼容 iQOO 静音，本方法会在铃声音量为 0 时恢复铃声音量，
     * 但不修改媒体音量、闹钟音量。
     *
     * @return 是否对系统状态做出了改变。
     */
    public static boolean enforce(Context context) {
        if (context == null) {
            return false;
        }

        Context appContext = context.getApplicationContext();
        AudioManager am = audio(appContext);

        if (am == null) {
            return false;
        }

        boolean changed = false;

        /*
         * 先记住当前非 0 铃声音量。
         *
         * 如果用户正常把铃声音量调到 5，后面 iQOO 静音把它压成 0，
         * 就可以恢复到 5。
         */
        rememberCurrentRingVolumeIfNonZero(appContext, am);

        /*
         * iQOO / OriginOS 的“静音”有时实际是勿扰。
         * 如果没有勿扰权限，这一步不会成功，界面里会提示用户去打开勿扰权限。
         */
        changed |= exitDoNotDisturbIfAllowed(appContext);

        // 标准静音/震动模式恢复。
        changed |= ensureNormalMode(am);

        /*
         * iQOO 上有些情况不是改 ringer mode，而是把铃声流 / 通知流 mute。
         * 这里只做 unmute，不主动改通知音量大小。
         */
        changed |= unmuteStreamIfMuted(am, AudioManager.STREAM_RING);
        changed |= unmuteStreamIfMuted(am, AudioManager.STREAM_NOTIFICATION);

        // 部分系统 unmute 后会重新计算 ringer mode，所以再确认一次。
        changed |= ensureNormalMode(am);

        /*
         * iQOO / OriginOS 常见情况：getRingerMode() 已经是 NORMAL，
         * 但铃声音量实际是 0，来电仍然不响。所以这里专门处理 ring volume == 0。
         */
        int ringVolume = getStreamVolumeSafe(am, AudioManager.STREAM_RING, -1);

        if (ringVolume == 0) {
            int targetVolume = getRecoveryRingVolume(appContext, am);

            if (targetVolume > 0) {
                changed |= setStreamVolumeSafe(
                        am,
                        AudioManager.STREAM_RING,
                        targetVolume
                );
            }
        }

        // 设置音量后，部分系统可能又把 ringer mode 带到震动/静音，最后再确认一次。
        changed |= ensureNormalMode(am);

        // 最后如果已经有非 0 铃声音量，再保存一次。
        rememberCurrentRingVolumeIfNonZero(appContext, am);

        return changed;
    }

    /**
     * 若当前响铃模式不是“响铃”，则切回响铃模式。
     *
     * @return 是否实际修改了响铃模式。
     */
    private static boolean ensureNormalMode(AudioManager am) {
        int mode = getRingerModeSafe(am);

        if (mode != -1 && mode != AudioManager.RINGER_MODE_NORMAL) {
            return setRingerModeNormalSafe(am);
        }

        return false;
    }

    public static int getCurrentRingerMode(Context context) {
        if (context == null) {
            return -1;
        }

        try {
            AudioManager am = audio(context);

            if (am == null) {
                return -1;
            }

            return getRingerModeSafe(am);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 只读当前铃声音量。
     */
    public static int getCurrentRingVolumeReadOnly(Context context) {
        if (context == null) {
            return 0;
        }

        try {
            AudioManager am = audio(context);

            if (am == null) {
                return 0;
            }

            return getStreamVolumeSafe(am, AudioManager.STREAM_RING, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 只读最大铃声音量。
     */
    public static int getMaxRingVolumeReadOnly(Context context) {
        if (context == null) {
            return 1;
        }

        try {
            AudioManager am = audio(context);

            if (am == null) {
                return 1;
            }

            return getMaxStreamVolumeSafe(am, AudioManager.STREAM_RING, 1);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 只读铃声流是否被 mute。
     */
    public static boolean isRingStreamMutedReadOnly(Context context) {
        if (context == null) {
            return false;
        }

        try {
            AudioManager am = audio(context);

            if (am == null) {
                return false;
            }

            return isStreamMutedSafe(am, AudioManager.STREAM_RING);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 手动记住当前铃声音量。
     *
     * 当前铃声音量为 0 时不保存，防止把 0 当成恢复目标。
     */
    public static boolean rememberCurrentRingVolumeIfNonZero(Context context) {
        if (context == null) {
            return false;
        }

        try {
            AudioManager am = audio(context);

            if (am == null) {
                return false;
            }

            return rememberCurrentRingVolumeIfNonZero(
                    context.getApplicationContext(),
                    am
            );
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 当前用于 iQOO 零音量恢复的目标音量。
     */
    public static int getSavedRecoverRingVolumeReadOnly(Context context) {
        if (context == null) {
            return 1;
        }

        try {
            AudioManager am = audio(context);
            return getRecoveryRingVolume(context.getApplicationContext(), am);
        } catch (Exception e) {
            return 1;
        }
    }

    public static boolean hasNotificationPolicyAccess(Context context) {
        if (context == null) {
            return false;
        }

        try {
            NotificationManager nm = notification(context);

            if (nm == null) {
                return false;
            }

            return nm.isNotificationPolicyAccessGranted();
        } catch (Exception e) {
            return false;
        }
    }

    public static int getCurrentInterruptionFilter(Context context) {
        if (context == null) {
            return NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }

        try {
            NotificationManager nm = notification(context);

            if (nm == null) {
                return NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
            }

            return nm.getCurrentInterruptionFilter();
        } catch (Exception e) {
            return NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }
    }

    public static String interruptionFilterToText(int filter) {
        if (filter == NotificationManager.INTERRUPTION_FILTER_ALL) {
            return "关闭/允许全部";
        } else if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            return "勿扰：仅优先事项";
        } else if (filter == NotificationManager.INTERRUPTION_FILTER_ALARMS) {
            return "勿扰：仅闹钟";
        } else if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) {
            return "勿扰：全部拦截";
        } else {
            return "未知";
        }
    }

    public static String ringerModeToText(int mode) {
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            return "响铃";
        } else if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            return "震动";
        } else if (mode == AudioManager.RINGER_MODE_SILENT) {
            return "静音";
        } else {
            return "未知";
        }
    }

    private static boolean exitDoNotDisturbIfAllowed(Context context) {
        if (context == null) {
            return false;
        }

        try {
            NotificationManager nm = notification(context);

            if (nm == null) {
                return false;
            }

            int filter = nm.getCurrentInterruptionFilter();

            if (filter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                return false;
            }

            if (!nm.isNotificationPolicyAccessGranted()) {
                return false;
            }

            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean rememberCurrentRingVolumeIfNonZero(
            Context context,
            AudioManager am
    ) {
        if (context == null || am == null) {
            return false;
        }

        int currentVolume = getStreamVolumeSafe(
                am,
                AudioManager.STREAM_RING,
                -1
        );

        if (currentVolume <= 0) {
            return false;
        }

        SharedPreferences preferences = prefs(context);
        int savedVolume = preferences.getInt(
                KEY_LAST_NON_ZERO_RING_VOLUME,
                -1
        );

        if (savedVolume == currentVolume) {
            return true;
        }

        preferences.edit()
                .putInt(KEY_LAST_NON_ZERO_RING_VOLUME, currentVolume)
                .apply();

        return true;
    }

    private static int getRecoveryRingVolume(Context context, AudioManager am) {
        int max = getMaxStreamVolumeSafe(am, AudioManager.STREAM_RING, 1);

        int saved = prefs(context).getInt(
                KEY_LAST_NON_ZERO_RING_VOLUME,
                -1
        );

        if (saved > 0) {
            return clamp(saved, 1, max);
        }

        return defaultRecoveryRingVolume(max);
    }

    private static int defaultRecoveryRingVolume(int max) {
        int safeMax = Math.max(1, max);

        /*
         * 如果从未记录过非 0 铃声音量，
         * 默认恢复到最大音量的一半，避免恢复到 1 太小听不见。
         */
        return Math.max(1, (safeMax + 1) / 2);
    }

    private static int getRingerModeSafe(AudioManager am) {
        if (am == null) {
            return -1;
        }

        try {
            return am.getRingerMode();
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean setRingerModeNormalSafe(AudioManager am) {
        if (am == null) {
            return false;
        }

        try {
            am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean unmuteStreamIfMuted(AudioManager am, int stream) {
        if (am == null) {
            return false;
        }

        boolean muted = false;

        try {
            muted = am.isStreamMute(stream);
        } catch (Exception ignored) {
        }

        if (!muted) {
            return false;
        }

        try {
            am.adjustStreamVolume(
                    stream,
                    AudioManager.ADJUST_UNMUTE,
                    0
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isStreamMutedSafe(AudioManager am, int stream) {
        if (am == null) {
            return false;
        }

        try {
            return am.isStreamMute(stream);
        } catch (Exception e) {
            return false;
        }
    }

    private static int getStreamVolumeSafe(
            AudioManager am,
            int stream,
            int fallback
    ) {
        if (am == null) {
            return fallback;
        }

        try {
            return am.getStreamVolume(stream);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int getMaxStreamVolumeSafe(
            AudioManager am,
            int stream,
            int fallback
    ) {
        if (am == null) {
            return Math.max(1, fallback);
        }

        try {
            return Math.max(1, am.getStreamMaxVolume(stream));
        } catch (Exception e) {
            return Math.max(1, fallback);
        }
    }

    private static boolean setStreamVolumeSafe(
            AudioManager am,
            int stream,
            int volume
    ) {
        if (am == null) {
            return false;
        }

        int max = getMaxStreamVolumeSafe(am, stream, volume);
        int safeVolume = clamp(volume, 1, max);

        int currentVolume = getStreamVolumeSafe(am, stream, -1);

        if (currentVolume == safeVolume) {
            return false;
        }

        try {
            am.setStreamVolume(stream, safeVolume, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int clamp(int value, int min, int max) {
        int safeMax = Math.max(min, max);
        return Math.max(min, Math.min(value, safeMax));
    }
}
