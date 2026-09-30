package com.lunacoreos.lunasync;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Background worker that syncs SMS, Call Logs, and Contacts to Supabase.
 * Runs via WorkManager — survives app kills and phone reboots.
 */
public class CommSyncWorker extends Worker {

    public CommSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("LunaSyncPrefs", Context.MODE_PRIVATE);
        String url = prefs.getString("supabaseUrl", null);
        String key = prefs.getString("supabaseKey", null);
        String deviceId = prefs.getString("deviceId", null);

        if (url == null || key == null || deviceId == null) {
            SyncLogger.log("CommSync skipped: not configured.");
            return Result.failure();
        }

        SupabaseClient client = new SupabaseClient(url, key);
        int smsCount = 0, callCount = 0, contactCount = 0;

        try {
            smsCount = syncSms(client, deviceId);
            SyncLogger.log("SMS synced: " + smsCount + " messages.");
        } catch (Exception e) {
            SyncLogger.log("SMS sync error: " + e.getMessage());
        }

        try {
            callCount = syncCalls(client, deviceId);
            SyncLogger.log("Calls synced: " + callCount + " records.");
        } catch (Exception e) {
            SyncLogger.log("Call sync error: " + e.getMessage());
        }

        try {
            contactCount = syncContacts(client, deviceId);
            SyncLogger.log("Contacts synced: " + contactCount + " entries.");
        } catch (Exception e) {
            SyncLogger.log("Contacts sync error: " + e.getMessage());
        }

        // Save stats
        prefs.edit()
            .putInt("lastSmsCount", smsCount)
            .putInt("lastCallCount", callCount)
            .putInt("lastContactCount", contactCount)
            .putLong("lastCommSync", System.currentTimeMillis())
            .apply();

        SyncLogger.log("CommSync complete ✓");
        return Result.success();
    }

    private int syncSms(SupabaseClient client, String deviceId) throws Exception {
        JSONArray messages = new JSONArray();
        Uri smsURI = Uri.parse("content://sms");
        String[] reqCols = new String[]{"_id", "address", "body", "date", "type"};

        Cursor c = getApplicationContext().getContentResolver().query(smsURI, reqCols, null, null, "date DESC");
        if (c != null) {
            int count = 0;
            java.util.Set<String> seenIds = new java.util.HashSet<>();
            while (c.moveToNext() && count < 5000) {
                JSONObject msg = new JSONObject();
                String address = safeString(c, 1);
                String body = safeString(c, 2);
                String timestamp = safeString(c, 3);
                
                // Use a deterministic ID so restoring to a new phone doesn't create new IDs
                // We normalize the phone number so Android formatting changes don't break the hash!
                String uniqueId = normalizePhone(address) + "_" + timestamp;
                
                if (seenIds.contains(uniqueId)) continue;
                seenIds.add(uniqueId);
                
                msg.put("message_id", uniqueId);
                msg.put("address", address);
                msg.put("body", body);
                msg.put("timestamp", timestamp);
                msg.put("type", safeString(c, 4));
                messages.put(msg);
                count++;
            }
            c.close();
        }

        if (messages.length() > 0) {
            // Batch in groups of 500 to avoid payload size limits
            for (int i = 0; i < messages.length(); i += 500) {
                JSONArray batch = new JSONArray();
                for (int j = i; j < Math.min(i + 500, messages.length()); j++) {
                    batch.put(messages.get(j));
                }
                client.postToTable("phone_sms", batch, "message_id");
            }
        }
        return messages.length();
    }

    private int syncCalls(SupabaseClient client, String deviceId) throws Exception {
        JSONArray calls = new JSONArray();
        String[] reqCols = new String[]{
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.CACHED_NAME
        };

        Cursor c = getApplicationContext().getContentResolver().query(
            CallLog.Calls.CONTENT_URI, reqCols, null, null, CallLog.Calls.DATE + " DESC");
        if (c != null) {
            int numIdx = c.getColumnIndex(CallLog.Calls.NUMBER);
            int dateIdx = c.getColumnIndex(CallLog.Calls.DATE);
            int durIdx = c.getColumnIndex(CallLog.Calls.DURATION);
            int typeIdx = c.getColumnIndex(CallLog.Calls.TYPE);
            int nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME);

            int count = 0;
            java.util.Set<String> seenIds = new java.util.HashSet<>();
            while (c.moveToNext() && count < 5000) {
                JSONObject log = new JSONObject();
                String number = safeStringIdx(c, numIdx);
                String timestamp = safeStringIdx(c, dateIdx);
                
                // Deterministic ID to prevent duplication
                String uniqueId = normalizePhone(number) + "_" + timestamp;
                
                if (seenIds.contains(uniqueId)) continue;
                seenIds.add(uniqueId);
                
                log.put("call_id", uniqueId);
                log.put("number", number);
                log.put("timestamp", timestamp);
                log.put("duration_seconds", safeStringIdx(c, durIdx));
                log.put("type", safeStringIdx(c, typeIdx));
                log.put("cached_name", safeStringIdx(c, nameIdx));
                calls.put(log);
                count++;
            }
            c.close();
        }

        if (calls.length() > 0) {
            for (int i = 0; i < calls.length(); i += 500) {
                JSONArray batch = new JSONArray();
                for (int j = i; j < Math.min(i + 500, calls.length()); j++) {
                    batch.put(calls.get(j));
                }
                client.postToTable("phone_call_logs", batch, "call_id");
            }
        }
        return calls.length();
    }

    private int syncContacts(SupabaseClient client, String deviceId) throws Exception {
        JSONArray contacts = new JSONArray();
        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String[] projection = new String[]{
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        };

        Cursor c = getApplicationContext().getContentResolver().query(uri, projection, null, null, null);
        if (c != null) {
            java.util.Map<String, JSONObject> contactMap = new java.util.LinkedHashMap<>();
            while (c.moveToNext()) {
                String contactId = safeString(c, 0);
                if (contactId.isEmpty()) continue;
                
                String name = safeString(c, 1);
                String phone = safeString(c, 2);
                
                JSONObject contact = contactMap.get(contactId);
                if (contact == null) {
                    contact = new JSONObject();
                    contact.put("contact_id", contactId);
                    contact.put("display_name", name.isEmpty() ? "Unknown" : name);
                    contact.put("phones", new JSONArray());
                    contact.put("emails", new JSONArray());
                    contactMap.put(contactId, contact);
                }
                
                if (!phone.isEmpty()) {
                    contact.getJSONArray("phones").put(phone);
                }
            }
            c.close();
            
            // Convert map to JSONArray
            int count = 0;
            java.util.Set<String> seenIds = new java.util.HashSet<>();
            for (JSONObject contact : contactMap.values()) {
                if (count >= 5000) break;
                
                try {
                    // Generate a deterministic ID based on name and first phone
                    String name = contact.getString("display_name");
                    String firstPhone = contact.getJSONArray("phones").length() > 0 ? 
                                        contact.getJSONArray("phones").getString(0) : "no-phone";
                    String uniqueId = name + "_" + normalizePhone(firstPhone);
                    
                    if (seenIds.contains(uniqueId)) continue;
                    seenIds.add(uniqueId);
                    
                    contact.put("contact_id", uniqueId);
                } catch (Exception ignore) {}
                
                contacts.put(contact);
                count++;
            }
        }

        if (contacts.length() > 0) {
            for (int i = 0; i < contacts.length(); i += 500) {
                JSONArray batch = new JSONArray();
                for (int j = i; j < Math.min(i + 500, contacts.length()); j++) {
                    batch.put(contacts.get(j));
                }
                client.postToTable("phone_contacts", batch, "contact_id");
            }
        }
        return contacts.length();
    }

    private String safeString(Cursor c, int colIndex) {
        try {
            String val = c.getString(colIndex);
            return val != null ? val : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String safeStringIdx(Cursor c, int colIndex) {
        try {
            if (colIndex >= 0 && !c.isNull(colIndex)) {
                return c.getString(colIndex);
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Strips spaces, dashes, and parentheses from phone numbers.
     * This prevents Android's auto-formatting from changing the unique hashes
     * and creating phantom duplicates!
     */
    private String normalizePhone(String phone) {
        if (phone == null) return "";
        return phone.replaceAll("[^0-9+]", "");
    }
}
