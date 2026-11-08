"""
Telegram channel media backup engine.

Runs inside the Android app via Chaquopy. Telethon is asyncio-based, so we
keep one event loop alive on a background thread and submit coroutines to it
from Kotlin (which calls the plain functions at the bottom of this file).
"""
import asyncio
import json
import os
import re
import threading
import time
import traceback

from telethon import TelegramClient, events, errors, utils
from telethon.tl.types import (
    MessageMediaWebPage, MessageMediaPoll, MessageMediaGeo, MessageMediaGeoLive,
    MessageMediaVenue, MessageMediaContact, MessageMediaDice, MessageMediaGame,
)

SKIP_MEDIA = (MessageMediaWebPage, MessageMediaPoll, MessageMediaGeo, MessageMediaGeoLive,
              MessageMediaVenue, MessageMediaContact, MessageMediaDice, MessageMediaGame)
PARALLEL = 3            # simultaneous downloads
RESCAN_SECONDS = 1800   # in live mode, full catch-up scan every 30 min


class _Engine:
    def __init__(self):
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self._run, name="tg-loop", daemon=True).start()
        self.client = None
        self.creds = None
        self.phone_hash = None
        self.stop_flag = False
        self.sync_task = None
        self.status = "Idle"
        self.listener = None
        self.handlers = []

    def _run(self):
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def call(self, coro, timeout=None):
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)

    # ---------- status reporting ----------
    def report(self, text):
        self.status = text
        if self.listener is not None:
            try:
                self.listener.onStatus(text)
            except Exception:
                pass

    def file_saved(self, path):
        if self.listener is not None:
            try:
                self.listener.onFile(path)
            except Exception:
                pass

    # ---------- client ----------
    async def ensure_client(self, session_path, api_id, api_hash):
        creds = (session_path, int(api_id), api_hash.strip())
        if self.client is not None and self.creds == creds:
            if not self.client.is_connected():
                await self.client.connect()
            return self.client
        if self.client is not None:
            await self.client.disconnect()
        self.client = TelegramClient(
            creds[0], creds[1], creds[2],
            device_model="Android", app_version="TgBackup 1.0", system_version="Android",
        )
        self.creds = creds
        await self.client.connect()
        return self.client


E = _Engine()


def _safe(name, maxlen=80):
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", str(name)).strip(" .")
    return (name or "untitled")[:maxlen]


def _kind(msg):
    if msg.photo:
        return "Photos"
    if msg.video or msg.video_note or msg.gif:
        return "Videos"
    if msg.voice or msg.audio:
        return "Audio"
    return "Files"


def _filename(msg):
    f = msg.file
    base = f"{msg.date:%Y-%m-%d_%H%M%S}_{msg.id}"
    if f is not None and f.name:
        stem, ext = os.path.splitext(f.name)
        return f"{base}_{_safe(stem, 60)}{ext}"
    ext = (f.ext if f is not None and f.ext else "") or ".bin"
    if ext == ".jpe":
        ext = ".jpg"
    return base + ext


class _Channel:
    """Backup state for one channel (stored next to its files)."""

    def __init__(self, entity, root):
        title = getattr(entity, "title", None) or getattr(entity, "username", None) or str(entity.id)
        self.entity = entity
        self.title = title
        self.dir = os.path.join(root, _safe(title))
        os.makedirs(self.dir, exist_ok=True)
        self.state_path = os.path.join(self.dir, ".tgbackup_state.json")
        self.state = {"last_id": 0, "files": 0, "bytes": 0}
        try:
            with open(self.state_path) as fh:
                self.state.update(json.load(fh))
        except Exception:
            pass
        self.lock = asyncio.Lock()

    def save(self):
        tmp = self.state_path + ".tmp"
        with open(tmp, "w") as fh:
            json.dump(self.state, fh)
        os.replace(tmp, self.state_path)

    def log_caption(self, msg, path):
        if not msg.message:
            return
        with open(os.path.join(self.dir, "captions.jsonl"), "a", encoding="utf-8") as fh:
            fh.write(json.dumps({"id": msg.id, "date": msg.date.isoformat(),
                                 "file": os.path.relpath(path, self.dir),
                                 "text": msg.message}, ensure_ascii=False) + "\n")


async def _download(ch, msg, sem):
    if not msg.media or isinstance(msg.media, SKIP_MEDIA) or msg.file is None:
        return
    folder = os.path.join(ch.dir, _kind(msg))
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, _filename(msg))
    if os.path.exists(path):
        return
    part = path + ".part"
    async with sem:
        for attempt in range(5):
            if E.stop_flag:
                return
            try:
                await E.client.download_media(msg, file=part)
                break
            except errors.FloodWaitError as fw:
                E.report(f"Telegram asked us to wait {fw.seconds}s…")
                await asyncio.sleep(fw.seconds + 1)
            except (errors.FileReferenceExpiredError, errors.FileReferenceInvalidError):
                msg = await E.client.get_messages(ch.entity, ids=msg.id)
                if msg is None or not msg.media:
                    return
            except (ConnectionError, OSError, asyncio.TimeoutError):
                await asyncio.sleep(5 * (attempt + 1))
        else:
            return
    if not os.path.exists(part):
        return
    os.replace(part, path)
    ch.state["files"] += 1
    ch.state["bytes"] += os.path.getsize(path)
    ch.log_caption(msg, path)
    E.file_saved(path)


async def _catch_up(ch):
    """Download everything newer than the last message we finished."""
    async with ch.lock:
        sem = asyncio.Semaphore(PARALLEL)
        batch = []

        async def flush():
            if not batch:
                return
            await asyncio.gather(*(_download(ch, m, sem) for m in batch))
            if not E.stop_flag:
                ch.state["last_id"] = max(ch.state["last_id"], batch[-1].id)
                ch.save()
            batch.clear()
            E.report(f"{ch.title}: {ch.state['files']} files "
                     f"({ch.state['bytes'] / 1048576:.0f} MB), up to post #{ch.state['last_id']}")

        E.report(f"{ch.title}: checking for new media…")
        async for m in E.client.iter_messages(ch.entity, min_id=ch.state["last_id"], reverse=True):
            if E.stop_flag:
                break
            batch.append(m)
            if len(batch) >= 20:
                await flush()
        await flush()


async def _sync(channels, root, live):
    E.stop_flag = False
    chans = []
    try:
        for ref in channels:
            try:
                ent = await E.client.get_entity(ref)
            except Exception as ex:
                E.report(f"Can't open channel '{ref}': {ex}")
                continue
            chans.append(_Channel(ent, root))
        if not chans:
            E.report("No channels could be opened. Check the names/links.")
            return

        for ch in chans:
            if E.stop_flag:
                return
            await _catch_up(ch)

        if not live:
            E.report("Backup complete ✓ " + "; ".join(
                f"{c.title}: {c.state['files']} files" for c in chans))
            return

        by_id = {utils.get_peer_id(c.entity): c for c in chans}

        async def on_new(event):
            ch = by_id.get(event.chat_id)
            if ch is not None:
                await _catch_up(ch)

        E.client.add_event_handler(on_new, events.NewMessage(chats=[c.entity for c in chans]))
        E.handlers.append(on_new)
        E.report("Live sync on — waiting for new posts. " + "; ".join(
            f"{c.title}: {c.state['files']} files" for c in chans))

        last = time.time()
        while not E.stop_flag:
            await asyncio.sleep(2)
            if not E.client.is_connected():
                try:
                    await E.client.connect()
                except Exception:
                    await asyncio.sleep(20)
                    continue
            if time.time() - last > RESCAN_SECONDS:
                last = time.time()
                for ch in chans:
                    await _catch_up(ch)
                E.report("Live sync on — waiting for new posts.")
    except asyncio.CancelledError:
        pass
    except Exception as ex:
        E.report(f"Error: {ex}")
        traceback.print_exc()
    finally:
        for h in E.handlers:
            E.client.remove_event_handler(h)
        E.handlers.clear()
        if E.stop_flag:
            E.report("Stopped.")


# ===================== functions called from Kotlin =====================

def init(session_path, api_id, api_hash):
    """Connect; returns True if this phone is already logged in."""
    async def go():
        c = await E.ensure_client(session_path, api_id, api_hash)
        return await c.is_user_authorized()
    return E.call(go(), 60)


def send_code(phone):
    async def go():
        r = await E.client.send_code_request(phone.strip())
        E.phone_hash = r.phone_code_hash
    try:
        E.call(go(), 60)
        return "ok"
    except Exception as ex:
        return f"error: {ex}"


def sign_in(phone, code):
    async def go():
        await E.client.sign_in(phone.strip(), code.strip(), phone_code_hash=E.phone_hash)
    try:
        E.call(go(), 60)
        return "ok"
    except errors.SessionPasswordNeededError:
        return "password"
    except Exception as ex:
        return f"error: {ex}"


def sign_in_password(password):
    try:
        E.call(E.client.sign_in(password=password), 60)
        return "ok"
    except Exception as ex:
        return f"error: {ex}"


def me():
    try:
        u = E.call(E.client.get_me(), 30)
        return (u.first_name or "") + (f" (@{u.username})" if u.username else "")
    except Exception:
        return ""


def start_sync(channels_text, root, live, listener):
    """Start backing up. channels_text: one @name / t.me link / id per line."""
    E.listener = listener
    if is_running():
        return "already running"
    refs = []
    for line in re.split(r"[\n,]+", channels_text or ""):
        line = line.strip()
        if not line:
            continue
        m = re.match(r"(?:https?://)?(?:t|telegram)\.me/(?:s/)?(c/\d+|[^/?#]+)", line)
        if m:
            g = m.group(1)
            if g.startswith("c/"):              # private channel link t.me/c/123/45
                line = "-100" + g[2:]
            elif g != "joinchat" and not g.startswith("+"):
                line = g                         # public @username
        if re.fullmatch(r"-?\d+", line):
            line = int(line)
        refs.append(line)
    if not refs:
        return "no channels"
    os.makedirs(root, exist_ok=True)

    def create():
        E.sync_task = E.loop.create_task(_sync(refs, root, bool(live)))
    E.loop.call_soon_threadsafe(create)
    return "started"


def is_running():
    return E.sync_task is not None and not E.sync_task.done()


def wait():
    """Block until the current sync finishes (used by the service thread)."""
    while is_running():
        time.sleep(1)


def stop():
    E.stop_flag = True


def status():
    return E.status


def logout():
    try:
        stop()
        E.call(E.client.log_out(), 30)
    except Exception:
        pass
