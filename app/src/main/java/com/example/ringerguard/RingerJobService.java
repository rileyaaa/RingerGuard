package com.example.ringerguard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * 低频兜底任务（微信等 VoIP / 一般静音的“最佳努力”覆盖）。
 *
 * 通过系统 {@link JobScheduler} 周期触发：
 * - 两次运行之间不保留任何进程，系统到点拉起一个新进程跑一次 {@link AudioGuard#enforce} 即结束；
 * - 因此不会出现“服务还在但功能假死”，也几乎不耗电（每天数次、每次几毫秒）。
 *
 * 它只是“粗粒度地把手机大体保持在可响铃状态”，不保证某一通 VoIP 来电一定有声音；
 * 运营商来电由 {@link CallGuardScreeningService} 精确保证“必响”。
 */
public class RingerJobService extends JobService {

    private static final int JOB_ID = 2001;

    /**
     * 4 小时一次。
     *
     * 与 6 小时相比耗电差异可忽略（均 < 0.1%/天）；间隔短一点，在 OriginOS 等
     * 会限流后台任务的系统上，系统有更多机会把它真正执行，兜底覆盖更好。
     */
    private static final long INTERVAL_MS = 4L * 60L * 60L * 1000L;

    public static void schedule(Context context) {
        if (context == null) {
            return;
        }

        JobScheduler scheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }

        ComponentName component = new ComponentName(context, RingerJobService.class);

        // setPersisted(true) 让任务在重启后自动恢复（依赖 RECEIVE_BOOT_COMPLETED 权限）。
        JobInfo job = new JobInfo.Builder(JOB_ID, component)
                .setPersisted(true)
                .setPeriodic(INTERVAL_MS)
                .build();

        try {
            scheduler.schedule(job);
        } catch (Exception ignored) {
        }
    }

    public static void cancel(Context context) {
        if (context == null) {
            return;
        }

        JobScheduler scheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }

        try {
            scheduler.cancel(JOB_ID);
        } catch (Exception ignored) {
        }
    }

    public static boolean isScheduled(Context context) {
        if (context == null) {
            return false;
        }

        JobScheduler scheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return false;
        }

        try {
            return scheduler.getPendingJob(JOB_ID) != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        DiagLog.log(this, "[兜底任务] onStartJob 执行 守护="
                + (AudioGuard.isEnabled(this) ? "开" : "关"));

        // enforce 为同步快速操作，直接完成即可。
        if (AudioGuard.isEnabled(this)) {
            try {
                AudioGuard.enforce(this, "兜底任务");
            } catch (Exception ignored) {
            }
        } else {
            // 守护已关闭，顺手取消周期任务。
            cancel(this);
        }

        // 没有后续异步工作。
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 周期任务由系统按周期自动重排，无需在此立即重排。
        return false;
    }
}
