# TG Channel Backup (Android)

Backs up every photo, video, audio file and document from one or more Telegram
channels to your phone, then (optionally) stays connected to Telegram and saves
new posts the moment they appear.

## Install
Open **Releases** on this repo from your phone → newest build → tap `TgBackup.apk`.
Allow "install unknown apps" for your browser when asked. Every push to `main`
builds a new APK automatically (Actions tab), and it installs over the old one.

## First run
1. Go to <https://my.telegram.org/apps>, log in, create an app (any name/short name),
   and copy **api_id** and **api_hash** into the app.
2. Enter your phone number → **Send login code** → type the code Telegram sends you
   (plus your two-step password if you use one).
3. Add channels, one per line: `@channelname`, `https://t.me/channelname`,
   `https://t.me/c/123456789/1` (private channels you're a member of) or a numeric ID.
4. Tap **Start backup**. Tap "Let it run in the background" once so Android doesn't kill it.

## How syncing works
- Logs in as **you** (via Telethon / MTProto), so it can read any channel you can see —
  no bot or admin rights needed.
- Downloads oldest → newest, 3 files at a time; remembers the last post per channel
  (`.tgbackup_state.json`), so stopping/restarting or rebooting just resumes.
- **Keep syncing** on: stays connected and grabs new posts in real time, with a full
  catch-up check every 30 minutes and auto-restart after reboot.
- Files that were already downloaded are never re-downloaded. Posts deleted on
  Telegram are **kept** in your backup.
- Captions are saved to `captions.jsonl` in each channel folder.

## Where files go
`Internal storage/Android/media/com.jc3901.tgbackup/TgBackup/<Channel name>/{Photos,Videos,Audio,Files}`
— visible in the Files app, and photos/videos appear in your Gallery.

## Building yourself
Android Studio (Hedgehog or newer) → open this folder → Run. Uses Kotlin + Chaquopy
(embedded Python 3.11) + Telethon 1.45.
