package com.lunacoreos.lunasync;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    private EditText etUrl, etKey, etVaultPrefix;
    private Button btnSave, btnRequestPerms;
    private TextView tvDeviceId, tvConnectionStatus;
    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);
        prefs = requireContext().getSharedPreferences("LunaSyncPrefs", 0);

        etUrl = v.findViewById(R.id.etSupabaseUrl);
        etKey = v.findViewById(R.id.etSupabaseKey);
        etVaultPrefix = v.findViewById(R.id.etVaultPrefix);
        btnSave = v.findViewById(R.id.btnSaveConfig);
        btnRequestPerms = v.findViewById(R.id.btnRequestPerms);
        tvDeviceId = v.findViewById(R.id.tvSettingsDeviceId);
        tvConnectionStatus = v.findViewById(R.id.tvConnectionStatus);

        // Restore saved config (pre-filled with LunaCoreOS defaults)
        String defaultUrl = "https://llseujnjhjrwmzhwfmoq.supabase.co";
        String defaultKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imxsc2V1am5qaGpyd216aHdmbW9xIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzc2MTM1NzQsImV4cCI6MjA5MzE4OTU3NH0.sL45a5IZcMZWZSZS8FVNWbNZa7NiHMoVCcNeohV5ndc";
        String defaultVaultPrefix = "vault/67539ee2-a1b0-405d-bbc1-c33dcbd198e6/gallery-phone-backup/";
        
        String savedUrl = prefs.getString("supabaseUrl", "");
        String savedKey = prefs.getString("supabaseKey", "");
        String savedVaultPrefix = prefs.getString("vaultPrefix", "");
        
        etUrl.setText(savedUrl.isEmpty() ? defaultUrl : savedUrl);
        etKey.setText(savedKey.isEmpty() ? defaultKey : savedKey);
        etVaultPrefix.setText(savedVaultPrefix.isEmpty() ? defaultVaultPrefix : savedVaultPrefix);
        tvDeviceId.setText(prefs.getString("deviceId", "Not generated"));
        updateConnectionStatus();

        // Auto-save defaults on first launch
        if (savedUrl.isEmpty() && savedKey.isEmpty()) {
            prefs.edit()
                .putString("supabaseUrl", defaultUrl)
                .putString("supabaseKey", defaultKey)
                .putString("vaultPrefix", defaultVaultPrefix)
                .apply();
            updateConnectionStatus();
            SyncLogger.log("Default Supabase config loaded ✓");
        }

        btnSave.setOnClickListener(view -> saveConfig());

        btnRequestPerms.setOnClickListener(view -> {
            ((MainActivity) requireActivity()).requestAllPermissions();
            Toast.makeText(requireContext(), "Requesting all permissions...", Toast.LENGTH_SHORT).show();
        });

        Button btnResetSyncCache = v.findViewById(R.id.btnResetSyncCache);
        btnResetSyncCache.setOnClickListener(view -> {
            prefs.edit().remove("syncedFiles").apply();
            Toast.makeText(requireContext(), "File Sync Memory Wiped! Ready to re-upload.", Toast.LENGTH_LONG).show();
            SyncLogger.log("User manually wiped file sync memory cache.");
        });

        return v;
    }

    private void saveConfig() {
        String url = etUrl.getText().toString().trim();
        String key = etKey.getText().toString().trim();
        String vaultPrefix = etVaultPrefix.getText().toString().trim();

        if (url.isEmpty() || key.isEmpty()) {
            Toast.makeText(requireContext(), "Please fill in both fields.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        prefs.edit()
            .putString("supabaseUrl", url)
            .putString("supabaseKey", key)
            .putString("vaultPrefix", vaultPrefix)
            .apply();

        SyncLogger.log("Configuration saved ✓");
        updateConnectionStatus();
        Toast.makeText(requireContext(), "Saved! Go to Sync tab to enable background sync.", Toast.LENGTH_LONG).show();
    }

    private void updateConnectionStatus() {
        boolean configured = prefs.getString("supabaseUrl", "").length() > 0
            && prefs.getString("supabaseKey", "").length() > 0;

        if (configured) {
            tvConnectionStatus.setText("Connected ✓");
            tvConnectionStatus.setTextColor(requireContext().getColor(R.color.accent_green));
        } else {
            tvConnectionStatus.setText("Not Configured");
            tvConnectionStatus.setTextColor(requireContext().getColor(R.color.accent_yellow));
        }
    }
}
