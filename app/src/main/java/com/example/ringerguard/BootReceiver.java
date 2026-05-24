package com.example.ringerguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

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

        Intent serviceIntent = new Intent(context, GuardService.class);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception ignored) {
        }
    }
}
