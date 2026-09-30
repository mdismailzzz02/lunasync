# LunaSync

> **Enterprise-grade, self-hosted cloud backup engine for Android — built entirely from scratch.**

LunaSync is a native Android application that automatically backs up your device's files, media, SMS messages, call logs, and contacts to a self-hosted cloud backend. Designed for privacy-first users who want full ownership of their data without relying on big tech cloud services.

---

## ✨ Features

### 📂 File & Media Backup
- Automatically syncs **DCIM, Pictures, Documents, and Downloads** folders
- Dedicated **WhatsApp media backup** with a separate database table to prevent log bloating
- Recursive folder scanning with smart deduplication — files are **never uploaded twice**
- Filters out hidden files and zero-byte thumbnails automatically

### ⚡ High-Performance Upload Engine
- **10-thread concurrent streaming** — uploads 10 files simultaneously at full bandwidth
- **Zero RAM usage** regardless of file size — streams directly in 64KB chunks
- Real-time per-file upload logging with **live percentage tracking** for large files (50MB+)
- Aggressive progress checkpointing every 10 files — survives Android OS kills and network drops

### 🧠 Smart Sync Memory
- Maintains a local `syncedFiles` cache — **no re-scanning or re-uploading** on repeated runs
- **Fresh install recovery:** On a new or factory-reset phone, the app automatically downloads its full upload history from the cloud database and rebuilds its memory instantly
- Dual-table history seeding (media files + WhatsApp files)

### 🔒 Concurrency Lock
- Static `isRunning` guard prevents duplicate sync workers from spawning simultaneously
- Safe `finally` block ensures the lock is **always released**, even on crashes or OS kills

### 📱 Communications Backup
- **SMS messages** — full conversation history with timestamps and addresses
- **Call logs** — incoming, outgoing, and missed calls with durations
- **Contacts** — complete phonebook with full contact details
- Native Android Content Provider integration for accurate data reads

### 🔄 Restore Engine
- Granular, folder-by-folder media restore: **DCIM, Pictures, Documents, Downloads, WhatsApp**
- 10-thread concurrent download engine mirrors the upload speed
- Communications restore: re-injects SMS and call logs directly into Android OS databases via Content Providers
- Live restore log panel with scrolling status output

### 🏠 Home Dashboard
- At-a-glance stats: Files synced, WhatsApp files synced, SMS count, Call log count, Contact count
- Live sync status display

### ⚙️ Background Automation
- **WorkManager-powered** 15-minute automatic background sync — survives device reboots
- Boot receiver re-registers all sync schedules after phone restart
- Immediate one-shot manual trigger via "SYNC NOW" buttons

---

## 🏗️ Architecture

```
LunaSync
├── FileSyncWorker.java      — 10-thread media backup engine with Smart Sync memory
├── CommSyncWorker.java      — SMS, Call Log & Contacts backup engine
├── RestoreFragment.java     — Multi-threaded restore with folder selection
├── RestoreManager.java      — Content Provider injection for SMS/Call restore
├── SupabaseClient.java      — Zero-dependency HTTP client (raw HttpURLConnection)
├── SyncManager.java         — WorkManager scheduling (15-min periodic + one-shot)
├── SyncLogger.java          — Thread-safe, real-time in-app log panel
├── HomeFragment.java        — Live stats dashboard
├── SettingsFragment.java    — Supabase credentials configuration
├── BootReceiver.java        — Re-registers schedules after device reboot
└── MainActivity.java        — Permission management and navigation
```

**Key Design Decisions:**
- **Zero external HTTP libraries** — raw `HttpURLConnection` only, keeping the APK lean
- **Presigned URL streaming** — files bypass the API server entirely and stream directly to edge storage
- **Dual database tables** — media logs and WhatsApp logs are isolated to prevent query slowdowns
- **Row-Level Security** — database enforces per-policy access control at the server level

---

## 🛠️ Setup

### Prerequisites
- Android Studio (latest stable)
- A self-hosted backend with:
  - A PostgreSQL database (e.g., Supabase)
  - An S3-compatible object storage (e.g., Cloudflare R2)
  - A presign Edge Function for generating secure upload/download URLs

### Database Schema

Run the following SQL to create the required tables:

```sql
-- Main file sync log
CREATE TABLE public.phone_sync_logs (
    id BIGSERIAL PRIMARY KEY,
    file_path TEXT NOT NULL UNIQUE,
    filename TEXT,
    size_bytes BIGINT,
    mime_type TEXT,
    synced_at TIMESTAMPTZ DEFAULT NOW()
);

-- WhatsApp-specific file log (isolated to keep queries fast)
CREATE TABLE public.whatsapp_sync_logs (
    id BIGSERIAL PRIMARY KEY,
    file_path TEXT NOT NULL UNIQUE,
    filename TEXT,
    size_bytes BIGINT,
    mime_type TEXT,
    synced_at TIMESTAMPTZ DEFAULT NOW()
);

-- Communications tables
CREATE TABLE public.sms_logs ( ... );
CREATE TABLE public.call_logs ( ... );
CREATE TABLE public.contacts ( ... );
```

### RLS (Row Level Security)

```sql
-- Allow the app's anonymous API key to read/write both log tables
CREATE POLICY "Enable all for anon" ON public.phone_sync_logs
    FOR ALL TO anon USING (true) WITH CHECK (true);

CREATE POLICY "Enable all for anon wa" ON public.whatsapp_sync_logs
    FOR ALL TO anon USING (true) WITH CHECK (true);
```

### Configuration

1. Clone this repository and open the `lunasync/` folder in Android Studio.
2. Build and install the app on your Android device.
3. Open LunaSync → go to **Settings**.
4. Enter your **Database URL** and **API Key**.
5. Toggle on **File Sync** and **Comm Sync** from the Sync tab.
6. Tap **SYNC NOW** to trigger the first backup immediately.

---

## 📋 Permissions Required

| Permission | Purpose |
|---|---|
| `READ_EXTERNAL_STORAGE` / `READ_MEDIA_*` | Scan and read media files |
| `READ_CONTACTS` | Backup phonebook |
| `READ_CALL_LOG` | Backup call history |
| `READ_SMS` | Backup SMS messages |
| `WRITE_CALL_LOG` | Restore call history |
| `RECEIVE_BOOT_COMPLETED` | Re-register background syncs after reboot |
| `INTERNET` | Upload to cloud backend |

---

## 📊 Performance

| Metric | Value |
|---|---|
| Upload concurrency | 10 threads |
| Download concurrency | 10 threads |
| Upload chunk size | 64KB streaming |
| Background sync interval | 15 minutes |
| Progress checkpoint frequency | Every 10 files |
| Large file threshold (% logging) | 50MB+ |

---

## 🔐 Privacy & Security

- **You own 100% of your data.** No third-party cloud service has access to your files.
- No analytics, no telemetry, no tracking of any kind.
- All credentials are stored locally on-device in Android `SharedPreferences`.
- Database access is protected by Row-Level Security policies.
- Presigned URLs expire automatically and cannot be reused.

---

## 📄 License

MIT License — free to use, modify, and distribute.

---

*Built with raw Java, Android SDK, WorkManager, and a deep hatred for paying for cloud storage.*
