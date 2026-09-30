package com.lunacoreos.lunasync;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST_CODE = 100;
    private static final int MANAGE_STORAGE_CODE = 101;

    private BottomNavigationView bottomNav;
    private SharedPreferences prefs;

    private final HomeFragment homeFragment = new HomeFragment();
    private final SyncFragment syncFragment = new SyncFragment();
    private final SettingsFragment settingsFragment = new SettingsFragment();
    private final RestoreFragment restoreFragment = new RestoreFragment();
    private Fragment activeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("LunaSyncPrefs", MODE_PRIVATE);

        // Generate Device ID on first launch
        if (prefs.getString("deviceId", null) == null) {
            String androidId = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.isEmpty()) androidId = java.util.UUID.randomUUID().toString();
            String hash = Integer.toHexString(androidId.hashCode()).toUpperCase();
            String deviceId = "LUNA-" + (hash + "00000000").substring(0, 8);
            prefs.edit().putString("deviceId", deviceId).apply();
        }

        // Add all fragments, hide non-default ones
        getSupportFragmentManager().beginTransaction()
            .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
            .add(R.id.fragmentContainer, restoreFragment, "restore").hide(restoreFragment)
            .add(R.id.fragmentContainer, syncFragment, "sync").hide(syncFragment)
            .add(R.id.fragmentContainer, homeFragment, "home")
            .commit();
        activeFragment = homeFragment;

        // Bottom navigation
        bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                switchFragment(homeFragment);
            } else if (id == R.id.nav_sync) {
                switchFragment(syncFragment);
            } else if (id == R.id.nav_restore) {
                switchFragment(restoreFragment);
            } else if (id == R.id.nav_settings) {
                switchFragment(settingsFragment);
            }
            return true;
        });

        // Request permissions on launch
        requestAllPermissions();

        SyncLogger.log("LunaSync started.");
    }

    private void switchFragment(Fragment target) {
        if (target == activeFragment) return;
        getSupportFragmentManager().beginTransaction()
            .hide(activeFragment)
            .show(target)
            .commit();
        activeFragment = target;
    }

    public void requestAllPermissions() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_MEDIA_AUDIO);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_CONTACTS);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.WRITE_CONTACTS);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_SMS);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_CALL_LOG);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.WRITE_CALL_LOG);

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM_REQUEST_CODE);
        }

        // Special: All Files Access (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                SyncLogger.log("Requesting 'All Files Access'...");
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, MANAGE_STORAGE_CODE);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, MANAGE_STORAGE_CODE);
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                String perm = permissions[i].substring(permissions[i].lastIndexOf('.') + 1);
                String status = (grantResults[i] == PackageManager.PERMISSION_GRANTED) ? "✓" : "✗";
                SyncLogger.log("Permission " + perm + ": " + status);
            }
        }
    }
}
