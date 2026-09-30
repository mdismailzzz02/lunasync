package com.lunacoreos.lunasync;

import org.json.JSONArray;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lightweight HTTP client for Supabase REST API.
 * Uses raw HttpURLConnection — zero external dependencies.
 */
public class SupabaseClient {

    private final String baseUrl;
    private final String apiKey;

    public SupabaseClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * POST a JSON array to a Supabase table via REST API.
     * Uses "Prefer: resolution=merge-duplicates" for upsert behavior.
     */
    public int postToTable(String tableName, JSONArray data, String onConflictCols) throws Exception {
        String urlStr = baseUrl + "/rest/v1/" + tableName;
        if (onConflictCols != null && !onConflictCols.isEmpty()) {
            urlStr += "?on_conflict=" + onConflictCols;
        }
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("apikey", apiKey);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Prefer", "resolution=merge-duplicates");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = data.toString().getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        conn.disconnect();

        if (responseCode >= 400) {
            String errorMsg = "";
            try (java.io.InputStream es = conn.getErrorStream()) {
                if (es != null) {
                    java.util.Scanner s = new java.util.Scanner(es).useDelimiter("\\A");
                    errorMsg = s.hasNext() ? s.next() : "";
                }
            } catch (Exception ignore) {}
            throw new Exception("Supabase HTTP " + responseCode + " on table " + tableName + " - " + errorMsg);
        }
        return responseCode;
    }

    /**
     * Get the exact row count of a Supabase table.
     * Uses HEAD request with "Prefer: count=exact" for high performance.
     */
    public int getTableCount(String tableName) {
        return getTableCountWhere(tableName, null);
    }

    public int getTableCountWhere(String tableName, String filter) {
        try {
            String urlStr = baseUrl + "/rest/v1/" + tableName + "?select=*&limit=1";
            if (filter != null && !filter.isEmpty()) {
                urlStr += "&" + filter;
            }
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("apikey", apiKey);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Prefer", "count=exact");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                String range = conn.getHeaderField("Content-Range");
                if (range != null && range.contains("/")) {
                    String[] parts = range.split("/");
                    return Integer.parseInt(parts[1]);
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    public org.json.JSONObject getPhoneBackupInfo() {
        try {
            // Use ilike to make it case-insensitive and allow it to be inside subfolders (ignore parent_id constraint)
            URL url = new URL(baseUrl + "/rest/v1/vault_collections?name=ilike.*phone%20backup*&select=id,key_prefix&limit=1");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", apiKey);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                java.io.InputStream is = conn.getInputStream();
                java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                String result = s.hasNext() ? s.next() : "";
                is.close();
                
                org.json.JSONArray array = new org.json.JSONArray(result);
                if (array.length() > 0) {
                    return array.getJSONObject(0);
                }
            }
        } catch (Exception e) {}
        return null;
    }
    
    public String getCollectionIdForPrefix(String prefix) {
        try {
            URL url = new URL(baseUrl + "/rest/v1/vault_collections?key_prefix=eq." + java.net.URLEncoder.encode(prefix, "UTF-8") + "&select=id&limit=1");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", apiKey);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                java.io.InputStream is = conn.getInputStream();
                java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                String result = s.hasNext() ? s.next() : "";
                is.close();
                org.json.JSONArray array = new org.json.JSONArray(result);
                if (array.length() > 0) {
                    return array.getJSONObject(0).getString("id");
                }
            }
        } catch (Exception e) {}
        return null;
    }

    /**
     * Get a presigned upload URL from the Cloudflare R2 edge function.
     */
    public String getR2PresignedUrl(String op, String key, String mimeType) throws Exception {
        String urlStr = baseUrl + "/functions/v1/r2-presign?op=" + op + "&key=" + key;
        if (mimeType != null && !mimeType.isEmpty()) {
            urlStr += "&content_type=" + mimeType;
        }
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("apikey", apiKey);
        
        int responseCode = conn.getResponseCode();
        if (responseCode >= 400) {
            String errorMsg = "";
            try (java.io.InputStream es = conn.getErrorStream()) {
                if (es != null) {
                    java.util.Scanner s = new java.util.Scanner(es).useDelimiter("\\A");
                    errorMsg = s.hasNext() ? s.next() : "";
                }
            } catch (Exception ignore) {}
            throw new Exception("Edge Function HTTP " + responseCode + " - " + errorMsg);
        }

        StringBuilder response = new StringBuilder();
        try (java.io.InputStream is = conn.getInputStream()) {
            java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
            response.append(s.hasNext() ? s.next() : "");
        }
        
        org.json.JSONObject json = new org.json.JSONObject(response.toString());
        return json.getString("url");
    }

    /**
     * Upload a file directly to Cloudflare R2 using a presigned URL (8KB chunks).
     */
    public int uploadToPresignedUrl(String presignedUrlStr, java.io.InputStream fileStream, long fileSize, String mimeType, String fileName) throws Exception {
        URL url = new URL(presignedUrlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", mimeType);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(300000); // 5 min timeout for large files

        // Enable streaming mode — critical for large files
        conn.setFixedLengthStreamingMode(fileSize);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] buffer = new byte[65536]; // 64KB buffer for faster I/O
            int bytesRead;
            long totalBytesRead = 0;
            int lastPercent = 0;
            
            while ((bytesRead = fileStream.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                
                // Only spam the log with percentages for files larger than 50MB
                if (fileSize > 50 * 1024 * 1024) {
                    int percent = (int) ((totalBytesRead * 100) / fileSize);
                    if (percent >= lastPercent + 10) {
                        SyncLogger.log("-> " + fileName + ": " + percent + "%");
                        lastPercent = percent;
                    }
                }
            }
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        
        if (responseCode >= 400) {
            String errorMsg = "";
            try (java.io.InputStream es = conn.getErrorStream()) {
                if (es != null) {
                    java.util.Scanner s = new java.util.Scanner(es).useDelimiter("\\A");
                    errorMsg = s.hasNext() ? s.next() : "";
                }
            } catch (Exception ignore) {}
            throw new Exception("R2 HTTP " + responseCode + " - " + errorMsg);
        }
        
        conn.disconnect();
        return responseCode;
    }

    /**
     * Download a file directly from Cloudflare R2 using a presigned URL.
     */
    public int downloadFromPresignedUrl(String presignedUrlStr, java.io.File destFile) throws Exception {
        URL url = new URL(presignedUrlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(300000); // 5 min timeout for large files

        int responseCode = conn.getResponseCode();
        
        if (responseCode >= 400) {
            String errorMsg = "";
            try (java.io.InputStream es = conn.getErrorStream()) {
                if (es != null) {
                    java.util.Scanner s = new java.util.Scanner(es).useDelimiter("\\A");
                    errorMsg = s.hasNext() ? s.next() : "";
                }
            } catch (Exception ignore) {}
            throw new Exception("R2 GET HTTP " + responseCode + " - " + errorMsg);
        }
        
        // Ensure parent directories exist
        if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
            destFile.getParentFile().mkdirs();
        }

        try (java.io.InputStream is = conn.getInputStream();
             java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
            byte[] buffer = new byte[65536]; // 64KB buffer
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.flush();
        }
        
        conn.disconnect();
        return responseCode;
    }

    public org.json.JSONArray fetchTableDataWithFilter(String tableName, String filterStr) {
        try {
            String urlStr = baseUrl + "/rest/v1/" + tableName + "?select=*";
            if (filterStr != null && !filterStr.isEmpty()) {
                urlStr += "&" + filterStr;
            }
            urlStr += "&limit=10000"; // Fetch up to 10k records
            
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", apiKey);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                java.io.InputStream is = conn.getInputStream();
                java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                String result = s.hasNext() ? s.next() : "";
                is.close();
                conn.disconnect();
                
                if (!result.isEmpty()) {
                    return new org.json.JSONArray(result);
                }
            } else {
                conn.disconnect();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new org.json.JSONArray();
    }

    public org.json.JSONArray fetchTableData(String tableName, String deviceId) {
        String filter = null;
        if (deviceId != null && !deviceId.equals("ALL")) {
            filter = "device_id=eq." + deviceId;
        }
        return fetchTableDataWithFilter(tableName, filter);
    }
}
