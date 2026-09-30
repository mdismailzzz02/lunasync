package com.lunacoreos.lunasync;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HomeFragment extends Fragment {

    private TextView tvDeviceId, tvSyncStatus, tvFileCount, tvSmsCount, tvCallCount, tvWhatsappCount;
    private TextView tvLastFileSync, tvLastCommSync, tvContactCount;
    private SharedPreferences prefs;
    private Handler refreshHandler;
    private Runnable refreshRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        prefs = requireContext().getSharedPreferences("LunaSyncPrefs", 0);

        tvDeviceId = v.findViewById(R.id.tvDeviceId);
        tvSyncStatus = v.findViewById(R.id.tvSyncStatus);
        tvFileCount = v.findViewById(R.id.tvFileCount);
        tvSmsCount = v.findViewById(R.id.tvSmsCount);
        tvCallCount = v.findViewById(R.id.tvCallCount);
        tvLastFileSync = v.findViewById(R.id.tvLastFileSync);
        tvLastCommSync = v.findViewById(R.id.tvLastCommSync);
        tvContactCount = v.findViewById(R.id.tvContactCount);
        tvWhatsappCount = v.findViewById(R.id.tvWhatsappCount);

        refreshUI();

        // Auto-refresh stats every 5 seconds
        refreshHandler = new Handler(Looper.getMainLooper());
        refreshRunnable = () -> {
            refreshUI();
            refreshHandler.postDelayed(refreshRunnable, 5000);
        };
        refreshHandler.postDelayed(refreshRunnable, 5000);

        return v;
    }

    private void refreshUI() {
        String deviceId = prefs.getString("deviceId", "Not generated");
        tvDeviceId.setText("Device ID: " + deviceId);

        boolean configured = prefs.getString("supabaseUrl", "").length() > 0;
        boolean fileOn = prefs.getBoolean("fileSyncEnabled", false);
        boolean commOn = prefs.getBoolean("commSyncEnabled", false);

        if (!configured) {
            tvSyncStatus.setText("● Not Configured");
            tvSyncStatus.setTextColor(requireContext().getColor(R.color.accent_yellow));
        } else if (fileOn || commOn) {
            tvSyncStatus.setText("● Active — Syncing Every 15 Min");
            tvSyncStatus.setTextColor(requireContext().getColor(R.color.accent_green));
        } else {
            tvSyncStatus.setText("● Configured — Sync Disabled");
            tvSyncStatus.setTextColor(requireContext().getColor(R.color.accent_blue));
        }

        String url = prefs.getString("supabaseUrl", "");
        String key = prefs.getString("supabaseKey", "");

        if (!configured || url.isEmpty() || key.isEmpty()) {
            tvFileCount.setText("0");
            tvSmsCount.setText("0");
            tvCallCount.setText("0");
            tvContactCount.setText("0");
            if (tvWhatsappCount != null) tvWhatsappCount.setText("0");
        } else {
            new Thread(() -> {
                SupabaseClient client = new SupabaseClient(url, key);
                int files = client.getTableCount("phone_sync_logs");
                int whatsapp = client.getTableCount("whatsapp_sync_logs");
                int sms = client.getTableCount("phone_sms");
                int calls = client.getTableCount("phone_call_logs");
                int contacts = client.getTableCount("phone_contacts");

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    
                    if (files >= 0) tvFileCount.setText(String.valueOf(files));
                    if (whatsapp >= 0 && tvWhatsappCount != null) tvWhatsappCount.setText(String.valueOf(whatsapp));
                    if (sms >= 0) tvSmsCount.setText(String.valueOf(sms));
                    if (calls >= 0) tvCallCount.setText(String.valueOf(calls));
                    if (contacts >= 0) tvContactCount.setText(String.valueOf(contacts));
                });
            }).start();
        }

        long lastFile = prefs.getLong("lastFileSync", 0);
        tvLastFileSync.setText(lastFile > 0 ?
            new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(new Date(lastFile)) : "Never");

        long lastComm = prefs.getLong("lastCommSync", 0);
        tvLastCommSync.setText(lastComm > 0 ?
            new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(new Date(lastComm)) : "Never");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (refreshHandler != null) refreshHandler.removeCallbacks(refreshRunnable);
    }
}
