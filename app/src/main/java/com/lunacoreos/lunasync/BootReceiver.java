package com.lunacoreos.lunasync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Listens for BOOT_COMPLETED to re-register WorkManager schedules.
 * This ensures sync continues even after the phone is restarted.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SyncLogger.log("Boot completed — restoring sync schedules...");
            SyncManager.restoreSchedules(context);
        }
    }
}
