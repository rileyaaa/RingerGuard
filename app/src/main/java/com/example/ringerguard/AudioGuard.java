package com.example.ringerguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.NotificationManager;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;

public final class AudioGuard {

    public static final String PREFS = "ringer_guard_prefs";
    public static final String KEY_ENABLED = "enabled";

    private AudioGuard() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    private static AudioManager audio(Context context) {
        return (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * 核心逻辑：
     *
     * 只处理这两种情况：
     * 1. 静音
     * 2. 震动
     *
     * 一旦发现静音或震动，就切回响铃。
     *
     * 注意：
     * 这里不调用 setStreamVolume()。
     * 所以不会修改铃声音量、媒体音量、通知音量、闹钟音量。
     */
    public static boolean enforce(Context context) {
        if (context == null) {
            return false;
        }

        AudioManager am = audio(context);

        if (am == null) {
            return false;
        }

        try {
            int mode = am.getRingerMode();

            if (mode == AudioManager.RINGER_MODE_NORMAL) {
                return false;
            }

            if (mode == AudioManager.RINGER_MODE_SILENT
                    || mode == AudioManager.RINGER_MODE_VIBRATE) {

                /*
                 * Android 7+ 某些系统（尤其 iQOO / vivo / OriginOS）
                 * 修改静音模式时要求勿扰访问权限。
                 */
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationManager nm =
                            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

                    if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
                        return false;
                    }
                }

                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);

                // 某些机型切回 NORMAL 后铃声音量会临时变 0
                if (am.getStreamVolume(AudioManager.STREAM_RING) == 0) {
                    am.adjustStreamVolume(
                            AudioManager.STREAM_RING,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                    );
                }

                return true;
            }

            return false;
        } catch (SecurityException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static int getCurrentRingerMode(Context context) {
        try {
            AudioManager am = audio(context);

            if (am == null) {
                return -1;
            }

            return am.getRingerMode();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 只读当前铃声音量。
     * 只用于界面显示，不会修改。
     */
    public static int getCurrentRingVolumeReadOnly(Context context) {
        try {
            AudioManager am = audio(context);

            if (am == null) {
                return 0;
            }

            return am.getStreamVolume(AudioManager.STREAM_RING);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 只读最大铃声音量。
     */
    public static int getMaxRingVolumeReadOnly(Context context) {
        try {
            AudioManager am = audio(context);

            if (am == null) {
                return 1;
            }

            return Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_RING));
        } catch (Exception e) {
            return 1;
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
}
