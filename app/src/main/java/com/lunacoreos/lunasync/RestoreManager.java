package com.lunacoreos.lunasync;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Telephony;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class RestoreManager {

    public static String restoreContacts(Context context, JSONArray contactsData) {
        int restored = 0;
        try {
            for (int i = 0; i < contactsData.length(); i++) {
                JSONObject c = contactsData.getJSONObject(i);
                String name = c.optString("display_name", "Unknown");
                JSONArray phones = c.optJSONArray("phones");

                ArrayList<ContentProviderOperation> ops = new ArrayList<>();
                int rawContactInsertIndex = ops.size();

                ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                        .build());

                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build());

                if (phones != null) {
                    for (int j = 0; j < phones.length(); j++) {
                        String phone = phones.getString(j);
                        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                                .build());
                    }
                }

                try {
                    context.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
                    restored++;
                } catch (Exception innerE) {
                    return "Restored " + restored + " before CRASH: " + innerE.toString();
                }
            }
        } catch (Exception e) {
            return "Outer CRASH: " + e.toString();
        }
        return String.valueOf(restored);
    }

    public static int restoreCallLogs(Context context, JSONArray logsData) {
        int restored = 0;
        try {
            for (int i = 0; i < logsData.length(); i++) {
                JSONObject log = logsData.getJSONObject(i);
                ContentValues values = new ContentValues();
                values.put(CallLog.Calls.NUMBER, log.optString("number"));
                
                // Supabase might have stored timestamp as String or Long depending on formatting
                long timestamp = System.currentTimeMillis();
                try { timestamp = Long.parseLong(log.optString("timestamp")); } catch (Exception ignore) {}
                values.put(CallLog.Calls.DATE, timestamp);
                
                long duration = 0;
                try { duration = Long.parseLong(log.optString("duration_seconds", "0")); } catch (Exception ignore) {}
                values.put(CallLog.Calls.DURATION, duration);
                
                int type = CallLog.Calls.INCOMING_TYPE;
                try { type = Integer.parseInt(log.optString("type", "1")); } catch (Exception ignore) {}
                values.put(CallLog.Calls.TYPE, type);
                
                values.put(CallLog.Calls.NEW, 1);
                
                String name = log.optString("cached_name", "");
                if (!name.isEmpty() && !name.equals("null")) {
                    values.put(CallLog.Calls.CACHED_NAME, name);
                }

                context.getContentResolver().insert(CallLog.Calls.CONTENT_URI, values);
                restored++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return restored;
    }

    public static int restoreSms(Context context, JSONArray smsData) {
        int restored = 0;
        try {
            for (int i = 0; i < smsData.length(); i++) {
                JSONObject msg = smsData.getJSONObject(i);
                ContentValues values = new ContentValues();
                values.put(Telephony.Sms.ADDRESS, msg.optString("address"));
                values.put(Telephony.Sms.BODY, msg.optString("body"));
                
                long timestamp = System.currentTimeMillis();
                try { timestamp = Long.parseLong(msg.optString("timestamp")); } catch (Exception ignore) {}
                values.put(Telephony.Sms.DATE, timestamp);
                values.put(Telephony.Sms.DATE_SENT, timestamp);
                
                values.put(Telephony.Sms.READ, 1);
                values.put(Telephony.Sms.SEEN, 1);
                values.put(Telephony.Sms.STATUS, -1);
                
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                        values.put(Telephony.Sms.SUBSCRIPTION_ID, android.telephony.SmsManager.getDefaultSmsSubscriptionId());
                    }
                } catch (Exception ignore) {}
                
                int type = Telephony.Sms.MESSAGE_TYPE_INBOX;
                try { type = Integer.parseInt(msg.optString("type", "1")); } catch (Exception ignore) {}
                values.put(Telephony.Sms.TYPE, type);

                try {
                    long threadId = Telephony.Threads.getOrCreateThreadId(context, msg.optString("address"));
                    values.put(Telephony.Sms.THREAD_ID, threadId);
                } catch (Exception ignore) {}

                android.net.Uri resultUri = context.getContentResolver().insert(Telephony.Sms.CONTENT_URI, values);
                if (resultUri != null) {
                    restored++;
                }
            }
            
            // VERIFICATION: Check how many messages are actually in the OS database now
            try (android.database.Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, new String[]{"_id"}, null, null, null)) {
                if (c != null) {
                    SyncLogger.log("VERIFICATION: The Android OS currently holds " + c.getCount() + " total SMS messages.");
                }
            } catch (Exception ignore) {}
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        return restored;
    }
}
