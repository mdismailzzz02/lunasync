package com.lunacoreos.lunasync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background worker that scans DCIM, Pictures, Documents, Download
 * and uploads new files to Supabase Storage using 8KB streaming.
 * Zero RAM usage regardless of file size.
 */
public class FileSyncWorker extends Worker {

    private static final String[] BASE_WATCH_FOLDERS = {"DCIM", "Pictures", "Documents", "Download"};
    private static final String BUCKET_NAME = "phone-backup";
    private static boolean isRunning = false;

    public FileSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (isRunning) {
            SyncLogger.log("Sync is already running! Skipping duplicate trigger.");
            return Result.success();
        }
        isRunning = true;
        
        try {
            SharedPreferences prefs = getApplicationContext().getSharedPreferences("LunaSyncPrefs", Context.MODE_PRIVATE);
            String url = prefs.getString("supabaseUrl", null);
            String key = prefs.getString("supabaseKey", null);
            String deviceId = prefs.getString("deviceId", null);

            if (url == null || key == null || deviceId == null) {
                SyncLogger.log("FileSync skipped: not configured.");
                return Result.failure();
            }
        
        java.util.List<String> watchFolders = new java.util.ArrayList<>(java.util.Arrays.asList(BASE_WATCH_FOLDERS));
        if (prefs.getBoolean("whatsappSyncEnabled", true)) {
            watchFolders.add("Android/media/com.whatsapp/WhatsApp");
        }

        SupabaseClient client = new SupabaseClient(url, key);

        // Get the actual Vault folder prefix for "Phone backup"
        org.json.JSONObject backupInfo = client.getPhoneBackupInfo();
        String basePrefix = prefs.getString("vaultPrefix", "");
        String collectionId = "";
        
        if (basePrefix.isEmpty() && backupInfo != null) {
            basePrefix = backupInfo.optString("key_prefix", "");
            collectionId = backupInfo.optString("id", "");
        }
        
        if (basePrefix == null || basePrefix.isEmpty()) {
            // Hardcode default as last resort
            basePrefix = "vault/67539ee2-a1b0-405d-bbc1-c33dcbd198e6/gallery-phone-backup/";
        }
        
        if (!basePrefix.endsWith("/")) basePrefix += "/";
        
        if (collectionId.isEmpty()) {
            collectionId = client.getCollectionIdForPrefix(basePrefix);
            
            // If manual lookup failed (maybe typo in Settings or URL encoding issue), fallback to auto-detect!
            if (collectionId == null && backupInfo != null) {
                SyncLogger.log("Manual prefix lookup failed, falling back to auto-detect.");
                collectionId = backupInfo.optString("id", "");
                basePrefix = backupInfo.optString("key_prefix", "");
            }
            
            if (collectionId == null || collectionId.isEmpty()) {
                SyncLogger.log("Collection not found. Auto-creating 'Phone Backup' folder...");
                collectionId = client.createVaultCollection("Phone Backup", basePrefix);
                
                if (collectionId == null) {
                    SyncLogger.log("FileSync failed: Could not create collection_id for prefix " + basePrefix);
                    return Result.failure();
                }
            }
        }
        
        final String finalBasePrefix = basePrefix;
        final String finalCollectionId = collectionId;

        // Load synced file cache from SharedPreferences (Thread-safe)
        Set<String> syncedFiles = Collections.synchronizedSet(new HashSet<>(prefs.getStringSet("syncedFiles", new HashSet<>())));
        
        // SMART SYNC: If this is a brand new phone (or memory was wiped), download the global history from Supabase!
        if (syncedFiles.isEmpty()) {
            SyncLogger.log("Memory is blank! Downloading global file sync history from Supabase...");
            try {
                org.json.JSONArray history = client.fetchTableDataWithFilter("vault_files", "r2_key=ilike." + finalBasePrefix + "*");
                String sdcardRoot = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
                for (int i = 0; i < history.length(); i++) {
                    String r2Key = history.getJSONObject(i).optString("r2_key");
                    if (r2Key.startsWith(finalBasePrefix)) {
                        String relativePath = r2Key.substring(finalBasePrefix.length());
                        syncedFiles.add(sdcardRoot + relativePath);
                    }
                }
                SyncLogger.log("Smart Sync: Injected " + syncedFiles.size() + " known files into memory!");
                prefs.edit().putStringSet("syncedFiles", syncedFiles).apply();
            } catch (Exception e) {
                SyncLogger.log("Failed to fetch global history: " + e.getMessage());
            }
        }

        AtomicInteger uploaded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();

        for (String folderName : watchFolders) {
            File folder = new File(Environment.getExternalStorageDirectory(), folderName);
            if (!folder.exists() || !folder.isDirectory()) {
                SyncLogger.log("Folder not found: " + folderName);
                continue;
            }

            SyncLogger.log("Scanning " + folderName + "...");
            java.util.List<File> files = new java.util.ArrayList<>();
            scanRecursive(folder, files);
            SyncLogger.log("Found " + files.size() + " files in " + folderName);

            for (File file : files) {
                String filePath = file.getAbsolutePath();

                // Skip already synced files
                if (syncedFiles.contains(filePath)) {
                    continue;
                }

                futures.add(executor.submit(() -> {
                    try {
                        // Build the storage path using the Vault prefix
                        String relativePath = filePath.replace(
                            Environment.getExternalStorageDirectory().getAbsolutePath() + "/", "");
                        String storagePath = finalBasePrefix + relativePath;
                        
                        // Must URL-encode the path (keeping slashes) to handle spaces and special characters
                        String encodedPath = Uri.encode(storagePath, "/");

                        // Determine MIME type
                        String mimeType = getMimeType(file.getName());

                        // 1. Get presigned URL from Edge Function
                        String presignedUrl = client.getR2PresignedUrl("put", encodedPath, mimeType);
                        
                        SyncLogger.log("Uploading: " + file.getName() + " (" + (file.length() / 1024 / 1024) + " MB)");

                        // 2. Stream upload directly to Cloudflare R2
                        FileInputStream fis = new FileInputStream(file);
                        client.uploadToPresignedUrl(presignedUrl, fis, file.length(), mimeType, file.getName());
                        fis.close();
                        
                        // 3. Log to Supabase Database (vault_files)
                        org.json.JSONObject logObj = new org.json.JSONObject();
                        logObj.put("collection_id", finalCollectionId);
                        logObj.put("r2_key", storagePath);
                        logObj.put("filename", file.getName());
                        logObj.put("size_bytes", file.length());
                        logObj.put("mime_type", mimeType);
                        
                        String source = filePath.contains("WhatsApp") ? "lunasync_whatsapp" : "lunasync_mobile";
                        logObj.put("upload_source", source);
                        
                        // Generate thumbnail
                        String thumbBase64 = generateThumbnail(file, mimeType);
                        if (thumbBase64 != null) {
                            logObj.put("thumbnail_key", thumbBase64);
                        }
                        
                        org.json.JSONArray logArray = new org.json.JSONArray();
                        logArray.put(logObj);
                        
                        // Upsert based ONLY on r2_key so it is perfectly deduplicated!
                        client.postToTable("vault_files", logArray, "r2_key");

                        // Mark as synced
                        syncedFiles.add(filePath);
                        int currentUploaded = uploaded.incrementAndGet();

                        if (currentUploaded % 10 == 0) {
                            // Save progress more frequently so we don't lose it if killed
                            prefs.edit().putStringSet("syncedFiles", syncedFiles).apply();
                        }
                    } catch (Exception e) {
                        SyncLogger.log("Failed: " + file.getName() + " — " + e.getMessage());
                        failed.incrementAndGet();
                    }
                }));
            }
        }

        // Wait for all 10 threads to finish uploading
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception ignore) {}
        }
        executor.shutdown();

        // Save final synced set
        prefs.edit()
            .putStringSet("syncedFiles", syncedFiles)
            .putInt("lastFileCount", syncedFiles.size())
            .putLong("lastFileSync", System.currentTimeMillis())
            .apply();

        SyncLogger.log("FileSync complete ✓ Uploaded: " + uploaded.get() + ", Failed: " + failed.get());
        return Result.success();
        
        } finally {
            isRunning = false;
        }
    }

    private String generateThumbnail(File file, String mimeType) {
        try {
            if (mimeType != null && mimeType.startsWith("image/")) {
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                int outWidth = options.outWidth;
                int outHeight = options.outHeight;
                if (outWidth == 0 || outHeight == 0) return null;
                
                int inSampleSize = 1;
                while (outWidth / inSampleSize > 400 || outHeight / inSampleSize > 400) {
                    inSampleSize *= 2;
                }
                
                options.inJustDecodeBounds = false;
                options.inSampleSize = inSampleSize;
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                if (bmp == null) return null;
                
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] bytes = baos.toByteArray();
                bmp.recycle();
                return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            } else if (mimeType != null && mimeType.startsWith("video/")) {
                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                retriever.setDataSource(file.getAbsolutePath());
                android.graphics.Bitmap bmp = retriever.getFrameAtTime(1000000); // 1 second
                if (bmp == null) {
                    bmp = retriever.getFrameAtTime(0);
                }
                retriever.release();
                if (bmp == null) return null;
                
                int width = bmp.getWidth();
                int height = bmp.getHeight();
                float scale = 1.0f;
                if (width > 400 || height > 400) {
                    scale = 400.0f / Math.max(width, height);
                }
                if (scale < 1.0f) {
                    android.graphics.Bitmap scaledBmp = android.graphics.Bitmap.createScaledBitmap(bmp, (int)(width * scale), (int)(height * scale), true);
                    bmp.recycle();
                    bmp = scaledBmp;
                }
                
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] bytes = baos.toByteArray();
                bmp.recycle();
                return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void scanRecursive(File dir, java.util.List<File> results) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                // Skip hidden directories (e.g., .thumbnails)
                if (!f.getName().startsWith(".")) {
                    scanRecursive(f, results);
                }
            } else {
                // Skip tiny files and hidden files
                if (f.length() > 0 && !f.getName().startsWith(".")) {
                    results.add(f);
                }
            }
        }
    }

    private String getMimeType(String filename) {
        String extension = "";
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = filename.substring(dotIndex + 1).toLowerCase();
        }

        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        if (mime != null) return mime;

        // Fallback for common types
        switch (extension) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "mp4": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            case "pdf": return "application/pdf";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default: return "application/octet-stream";
        }
    }
}
