package com.lunacoreos.lunasync;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SyncFragment extends Fragment {

    private SwitchMaterial switchFile, switchComm, switchWhatsapp;
    private Button btnSyncFiles, btnSyncComm, btnClearLogs, btnCopyLogs;
    private TextView tvLogs;
    private ScrollView logScrollView;
    private SharedPreferences prefs;
    private Handler uiHandler;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_sync, container, false);
        prefs = requireContext().getSharedPreferences("LunaSyncPrefs", 0);
        uiHandler = new Handler(Looper.getMainLooper());

        switchFile = v.findViewById(R.id.switchFileSync);
        switchComm = v.findViewById(R.id.switchCommSync);
        switchWhatsapp = v.findViewById(R.id.switchWhatsappSync);
        btnSyncFiles = v.findViewById(R.id.btnSyncFiles);
        btnSyncComm = v.findViewById(R.id.btnSyncComm);
        btnClearLogs = v.findViewById(R.id.btnClearLogs);
        btnCopyLogs = v.findViewById(R.id.btnCopyLogs);
        tvLogs = v.findViewById(R.id.tvLogs);
        logScrollView = v.findViewById(R.id.logScrollView);

        // Fix nested scrolling - let inner ScrollView handle its own touch events
        logScrollView.setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        // Restore switch states
        switchFile.setChecked(prefs.getBoolean("fileSyncEnabled", false));
        switchComm.setChecked(prefs.getBoolean("commSyncEnabled", false));
        switchWhatsapp.setChecked(prefs.getBoolean("whatsappSyncEnabled", true));

        // Setup log listener
        SyncLogger.setListener(fullLog -> uiHandler.post(() -> {
            tvLogs.setText(fullLog);
            logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }));

        // Show existing logs
        String existing = SyncLogger.getFullLog();
        if (!existing.isEmpty()) {
            tvLogs.setText(existing);
        }

        // --- Listeners ---

        switchFile.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isConfigured()) {
                switchFile.setChecked(false);
                Toast.makeText(requireContext(), "Go to Settings and configure Supabase first!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isChecked) {
                ((MainActivity) requireActivity()).requestAllPermissions();
                SyncManager.enableFileSync(requireContext());
            } else {
                SyncManager.disableFileSync(requireContext());
            }
        });

        switchComm.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isConfigured()) {
                switchComm.setChecked(false);
                Toast.makeText(requireContext(), "Go to Settings and configure Supabase first!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isChecked) {
                ((MainActivity) requireActivity()).requestAllPermissions();
                SyncManager.enableCommSync(requireContext());
            } else {
                SyncManager.disableCommSync(requireContext());
            }
        });

        switchWhatsapp.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("whatsappSyncEnabled", isChecked).apply();
            if (isChecked) {
                SyncLogger.log("WhatsApp sync enabled (will run with File Sync).");
            } else {
                SyncLogger.log("WhatsApp sync disabled.");
            }
        });

        btnSyncFiles.setOnClickListener(view -> {
            if (!isConfigured()) {
                Toast.makeText(requireContext(), "Configure Supabase first!", Toast.LENGTH_SHORT).show();
                return;
            }
            ((MainActivity) requireActivity()).requestAllPermissions();
            SyncManager.triggerFileSyncNow(requireContext());
        });

        btnSyncComm.setOnClickListener(view -> {
            if (!isConfigured()) {
                Toast.makeText(requireContext(), "Configure Supabase first!", Toast.LENGTH_SHORT).show();
                return;
            }
            ((MainActivity) requireActivity()).requestAllPermissions();
            SyncManager.triggerCommSyncNow(requireContext());
        });

        btnClearLogs.setOnClickListener(view -> {
            SyncLogger.clear();
            tvLogs.setText("Logs cleared.");
        });

        btnCopyLogs.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("LunaSync Logs", tvLogs.getText().toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "Logs copied to clipboard!", Toast.LENGTH_SHORT).show();
        });

        return v;
    }

    private boolean isConfigured() {
        return prefs.getString("supabaseUrl", "").length() > 0
            && prefs.getString("supabaseKey", "").length() > 0;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        SyncLogger.setListener(null);
    }
}
