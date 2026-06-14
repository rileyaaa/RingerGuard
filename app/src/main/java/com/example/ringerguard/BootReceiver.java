package com.example.ringerguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        String action = intent.getAction();

        boolean allowed =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (!allowed) {
            return;
        }

        if (!AudioGuard.isEnabled(context)) {
            return;
        }

        /*
         * 开机 / 更新后：
         * 1. 重新登记低频兜底任务（持久化任务一般会自动恢复，这里再确保一次）；
         * 2. 顺手恢复一次响铃状态。
         *
         * 运营商来电的“必响”由系统来电筛选机制负责，不需要在这里启动任何常驻服务。
         */
        RingerJobService.schedule(context);

        try {
            AudioGuard.enforce(context);
        } catch (Exception ignored) {
        }
    }
}
