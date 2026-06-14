package com.example.ringerguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 轻量内置诊断日志。
 *
 * 把关键事件（来电 onScreenCall、开机、低频兜底任务、打开界面时的 enforce 前后状态）
 * 记录到本地 SharedPreferences，并在主界面显示，用于在【没有电脑 / 没有 adb】的情况下
 * 排查“重启后来电不响”到底属于哪种情况：
 *
 *   A. 系统根本没在来电时拉起 CallScreeningService  -> 日志里【没有】“[来电] onScreenCall 被系统调用”记录；
 *   B. 拉起了但 enforce 没把静音 / 勿扰改过来          -> 有“[来电]”记录，但 enforce 改前 / 改后状态没变，
 *                                                       或“勿扰权限=未允许”。
 *
 * 设计原则：只在低频事件（来电 / 开机 / 打开界面 / 每 4 小时兜底）时写一条，
 * 不轮询、不常驻，几乎不耗电。
 */
public final class DiagLog {

    private static final String KEY_LINES = "diag_log_lines";

    /** 最多保留的日志条数，超出后丢弃最旧的。 */
    private static final int MAX_LINES = 40;

    private static final String SEP = "\n";

    private DiagLog() {
    }

    /**
     * 追加一条日志（自动加时间戳）。
     *
     * 注意：来电时本进程随时可能在 onScreenCall 返回后被系统回收，
     * 因此这里用 commit() 同步落盘，确保来电那一刻的日志不会丢失。
     */
    public static void log(Context context, String line) {
        if (context == null || line == null) {
            return;
        }

        try {
            SharedPreferences prefs = AudioGuard.prefs(context);

            String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date());

            // 单条日志内禁止出现换行，否则会破坏按行切分；统一替换为空格。
            String safeLine = line.replace("\r", " ").replace("\n", " ");
            String entry = time + "  " + safeLine;

            String old = prefs.getString(KEY_LINES, "");
            String combined = old.isEmpty() ? entry : entry + SEP + old;

            String[] arr = combined.split(SEP);
            if (arr.length > MAX_LINES) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < MAX_LINES; i++) {
                    if (i > 0) {
                        sb.append(SEP);
                    }
                    sb.append(arr[i]);
                }
                combined = sb.toString();
            }

            prefs.edit().putString(KEY_LINES, combined).commit();
        } catch (Exception ignored) {
        }
    }

    /** 读取全部日志（最新在最上）。无日志时返回空串。 */
    public static String read(Context context) {
        if (context == null) {
            return "";
        }

        try {
            return AudioGuard.prefs(context).getString(KEY_LINES, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 清空日志。 */
    public static void clear(Context context) {
        if (context == null) {
            return;
        }

        try {
            AudioGuard.prefs(context).edit().remove(KEY_LINES).commit();
        } catch (Exception ignored) {
        }
    }
}
