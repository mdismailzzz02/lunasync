package com.lunacoreos.lunasync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Manages all WorkManager scheduling for both File and Comm sync.
 */
public class SyncManager {

    private static final String FILE_SYNC_WORK = "LunaFileSync";
    private static final String COMM_SYNC_WORK = "LunaCommSync";

    /**
     * Schedule recurring file sync (every 15 minutes).
     */
    public static void enableFileSync(Context context) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                FileSyncWorker.class, 15, TimeUnit.MINUTES)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                FILE_SYNC_WORK,
                ExistingPeriodicWorkPolicy.REPLACE,
                work
        );

        SharedPreferences prefs = context.getSharedPreferences("LunaSyncPrefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("fileSyncEnabled", true).apply();

        SyncLogger.log("File sync scheduled (every 15 min).");
    }

    /**
     * Cancel recurring file sync.
     */
    public static void disableFileSync(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(FILE_SYNC_WORK);
        SharedPreferences prefs = context.getSharedPreferences("LunaSyncPrefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("fileSyncEnabled", false).apply();
        SyncLogger.log("File sync disabled.");
    }

    /**
     * Schedule recurring comm sync (every 15 minutes).
     */
    public static void enableCommSync(Context context) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                CommSyncWorker.class, 15, TimeUnit.MINUTES)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                COMM_SYNC_WORK,
                ExistingPeriodicWorkPolicy.REPLACE,
                work
        );

        SharedPreferences prefs = context.getSharedPreferences("LunaSyncPrefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("commSyncEnabled", true).apply();

        SyncLogger.log("Comm sync scheduled (every 15 min).");
    }

    /**
     * Cancel recurring comm sync.
     */
    public static void disableCommSync(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(COMM_SYNC_WORK);
        SharedPreferences prefs = context.getSharedPreferences("LunaSyncPrefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("commSyncEnabled", false).apply();
        SyncLogger.log("Comm sync disabled.");
    }

    /**
     * Fire an immediate one-time file sync.
     */
    public static void triggerFileSyncNow(Context context) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(FileSyncWorker.class).build();
        WorkManager.getInstance(context).enqueue(work);
        SyncLogger.log("Immediate file sync triggered.");
    }

    /**
     * Fire an immediate one-time comm sync.
     */
    public static void triggerCommSyncNow(Context context) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(CommSyncWorker.class).build();
        WorkManager.getInstance(context).enqueue(work);
        SyncLogger.log("Immediate comm sync triggered.");
    }

    /**
     * Re-register all enabled syncs (called after boot).
     */
    public static void restoreSchedules(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("LunaSyncPrefs", Context.MODE_PRIVATE);

        if (prefs.getBoolean("fileSyncEnabled", false)) {
            enableFileSync(context);
            SyncLogger.log("File sync restored after boot.");
        }
        if (prefs.getBoolean("commSyncEnabled", false)) {
            enableCommSync(context);
            SyncLogger.log("Comm sync restored after boot.");
        }
    }
}
