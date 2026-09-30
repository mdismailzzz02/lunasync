package com.lunacoreos.lunasync;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Thread-safe in-memory log buffer for displaying sync activity in the UI.
 */
public class SyncLogger {
    private static final String TAG = "LunaSync";
    private static final int MAX_LINES = 200;
    private static final List<String> logs = new ArrayList<>();
    private static LogListener listener;

    public interface LogListener {
        void onNewLog(String fullLog);
    }

    public static void setListener(LogListener l) {
        listener = l;
    }

    public static synchronized void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + timestamp + "] " + message;
        Log.d(TAG, message);

        logs.add(line);
        if (logs.size() > MAX_LINES) {
            logs.remove(0);
        }

        if (listener != null) {
            listener.onNewLog(getFullLog());
        }
    }

    public static synchronized String getFullLog() {
        StringBuilder sb = new StringBuilder();
        for (String line : logs) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        logs.clear();
        if (listener != null) {
            listener.onNewLog("");
        }
    }
}
