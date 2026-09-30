package com.lunacoreos.lunasync;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class RestoreFragment extends Fragment {

    private CheckBox cbContacts, cbCallLogs, cbSms, cbWhatsApp, cbDcim, cbPictures, cbDocuments, cbDownload;
    private Button btnRestore;
    private TextView tvRestoreLogs;
    private ScrollView restoreLogScrollView;
    private Handler uiHandler;

    private static final int ROLE_REQUEST_CODE = 200;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_restore, container, false);
        uiHandler = new Handler(Looper.getMainLooper());

        cbContacts = v.findViewById(R.id.cbContacts);
        cbCallLogs = v.findViewById(R.id.cbCallLogs);
        cbSms = v.findViewById(R.id.cbSms);
        cbWhatsApp = v.findViewById(R.id.cbWhatsApp);
        cbDcim = v.findViewById(R.id.cbDcim);
        cbPictures = v.findViewById(R.id.cbPictures);
        cbDocuments = v.findViewById(R.id.cbDocuments);
        cbDownload = v.findViewById(R.id.cbDownload);
        btnRestore = v.findViewById(R.id.btnRestore);
        tvRestoreLogs = v.findViewById(R.id.tvRestoreLogs);
        restoreLogScrollView = v.findViewById(R.id.restoreLogScrollView);

        restoreLogScrollView.setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        btnRestore.setOnClickListener(view -> {
            if (cbSms.isChecked()) {
                requestDefaultSmsRole();
            } else {
                startRestoreProcess("ALL");
            }
        });

        return v;
    }

    private void requestDefaultSmsRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = requireContext().getSystemService(RoleManager.class);
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS) && !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS);
                startActivityForResult(intent, ROLE_REQUEST_CODE);
            } else {
                startRestoreProcess("ALL");
            }
        } else {
            // For older Android, fallback to standard intent
            Intent intent = new Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
            intent.putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, requireContext().getPackageName());
            startActivityForResult(intent, ROLE_REQUEST_CODE);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ROLE_REQUEST_CODE) {
            log("SMS Role requested. Proceeding with restore...");
            startRestoreProcess("ALL");
        }
    }

    private void startRestoreProcess(String oldDeviceId) {
        log("Starting global restore from unified vault...");
        
        SharedPreferences prefs = requireContext().getSharedPreferences("LunaSyncPrefs", Context.MODE_PRIVATE);
        String url = prefs.getString("supabaseUrl", null);
        String key = prefs.getString("supabaseKey", null);
        if (url == null || key == null) {
            log("Error: Supabase credentials not found.");
            return;
        }
        SupabaseClient client = new SupabaseClient(url, key);

        new Thread(() -> {
            try {
                if (cbContacts.isChecked()) {
                    log("Fetching Contacts from Supabase...");
                    org.json.JSONArray contactsData = client.fetchTableData("phone_contacts", oldDeviceId);
                    log("Downloaded " + contactsData.length() + " contacts. Inserting into phone...");
                    String result = RestoreManager.restoreContacts(requireContext(), contactsData);
                    if (result.startsWith("Restored") || result.startsWith("Outer")) {
                        log("ERROR: " + result);
                    } else {
                        log("Successfully restored " + result + " contacts!");
                    }
                }
                
                if (cbCallLogs.isChecked()) {
                    log("Fetching Call Logs from Supabase...");
                    org.json.JSONArray logsData = client.fetchTableData("phone_call_logs", oldDeviceId);
                    log("Downloaded " + logsData.length() + " call logs. Inserting into phone...");
                    int restored = RestoreManager.restoreCallLogs(requireContext(), logsData);
                    log("Successfully restored " + restored + " call logs!");
                }

                if (cbSms.isChecked()) {
                    log("Fetching SMS Messages from Supabase...");
                    org.json.JSONArray smsData = client.fetchTableData("phone_sms", oldDeviceId);
                    log("Downloaded " + smsData.length() + " SMS messages. Inserting into phone...");
                    int restored = RestoreManager.restoreSms(requireContext(), smsData);
                    log("Successfully restored " + restored + " SMS messages!");
                    
                    // VERIFICATION: Check how many messages exist in the Android OS
                    try (android.database.Cursor c = requireContext().getContentResolver().query(android.provider.Telephony.Sms.CONTENT_URI, new String[]{"_id"}, null, null, null)) {
                        if (c != null) {
                            log("VERIFICATION: The Android OS currently holds " + c.getCount() + " total SMS messages.");
                        }
                    } catch (Exception ignore) {}
                    
                    log("\n[ACTION REQUIRED] Prompting you to switch back to Google Messages...");
                    
                    // Automatically pop up the dialog to switch back to Google Messages!
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            Intent intent = new Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
                            intent.putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, "com.google.android.apps.messaging");
                            startActivity(intent);
                        } catch (Exception e) {
                            try {
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
                                startActivity(intent);
                            } catch (Exception ignore) {}
                        }
                    }, 1500);
                }

                if (cbWhatsApp != null && cbWhatsApp.isChecked()) restoreMediaFolder("WhatsApp", client);
                if (cbDcim != null && cbDcim.isChecked()) restoreMediaFolder("DCIM", client);
                if (cbPictures != null && cbPictures.isChecked()) restoreMediaFolder("Pictures", client);
                if (cbDocuments != null && cbDocuments.isChecked()) restoreMediaFolder("Documents", client);
                if (cbDownload != null && cbDownload.isChecked()) restoreMediaFolder("Download", client);

                log("--- RESTORE COMPLETE ---");
            } catch (Exception e) {
                log("Error: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private void restoreMediaFolder(String folderName, SupabaseClient client) {
        log("Fetching " + folderName + " file list from Supabase...");
        org.json.JSONArray files = client.fetchTableDataWithFilter("vault_files", "r2_key=ilike.*" + folderName + "*/*");
        int totalFiles = files.length();
        
        if (totalFiles > 0) {
            log("Found " + totalFiles + " " + folderName + " files. Starting 10-thread download...");
            
            org.json.JSONObject backupInfo = client.getPhoneBackupInfo();
            String basePrefix = null;
            if (backupInfo != null) {
                basePrefix = backupInfo.optString("key_prefix", null);
            }
            if (basePrefix == null) {
                basePrefix = requireContext().getSharedPreferences("LunaSyncPrefs", 0).getString("vaultPrefix", "vault/67539ee2-a1b0-405d-bbc1-c33dcbd198e6/gallery-phone-backup/");
            }
            if (basePrefix != null && !basePrefix.isEmpty()) {
                if (!basePrefix.endsWith("/")) basePrefix += "/";
                final String finalBasePrefix = basePrefix;
                
                ExecutorService executor = Executors.newFixedThreadPool(10);
                java.util.List<Future<?>> futures = new java.util.ArrayList<>();
                AtomicInteger downloaded = new AtomicInteger(0);
                AtomicInteger failed = new AtomicInteger(0);
                String externalRoot = android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
                
                for (int i = 0; i < totalFiles; i++) {
                    final org.json.JSONObject fileLog = files.optJSONObject(i);
                    if (fileLog == null) continue;
                    
                    futures.add(executor.submit(() -> {
                        try {
                            String r2Key = fileLog.getString("r2_key");
                            String mimeType = fileLog.optString("mime_type", "");
                            
                            String relativePath = r2Key.replace(finalBasePrefix, "");
                            String filePath = externalRoot + relativePath;
                            String storagePath = r2Key;
                            String encodedPath = Uri.encode(storagePath, "/");
                            
                            String presignedGetUrl = client.getR2PresignedUrl("get", encodedPath, mimeType);
                            File destFile = new File(filePath);
                            
                            // Skip if the file already exists and the size matches
                            if (destFile.exists() && destFile.length() == fileLog.optLong("size_bytes", -1)) {
                                downloaded.incrementAndGet();
                            } else {
                                if (destFile.getParentFile() != null) {
                                    destFile.getParentFile().mkdirs();
                                }
                                client.downloadFromPresignedUrl(presignedGetUrl, destFile);
                                int current = downloaded.incrementAndGet();
                                if (current % 100 == 0) {
                                    log(folderName + " Sync: " + current + " / " + totalFiles + " downloaded...");
                                }
                            }
                        } catch (Exception e) {
                            failed.incrementAndGet();
                        }
                    }));
                }
                
                for (Future<?> f : futures) {
                    try { f.get(); } catch (Exception ignore) {}
                }
                executor.shutdown();
                
                log(folderName + " Restore Complete ✓ Downloaded: " + downloaded.get() + ", Failed: " + failed.get());
            } else {
                log("ERROR: Could not get R2 prefix for " + folderName + " restore.");
            }
        } else {
            log("No " + folderName + " files found in the cloud vault!");
        }
    }

    private void log(String msg) {
        uiHandler.post(() -> {
            String current = tvRestoreLogs.getText().toString();
            if (current.equals("Ready...")) current = "";
            tvRestoreLogs.setText(current + "\n> " + msg);
            restoreLogScrollView.post(() -> restoreLogScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
