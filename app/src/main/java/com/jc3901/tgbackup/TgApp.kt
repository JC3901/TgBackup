package com.jc3901.tgbackup

import android.app.Application
import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class TgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
    }
}

/** Shared helpers: settings, the Python engine, and where files go. */
object Tg {
    fun prefs(c: Context) = c.getSharedPreferences("tgbackup", Context.MODE_PRIVATE)
    fun py(): PyObject = Python.getInstance().getModule("tgsync")
    fun session(c: Context) = File(c.filesDir, "telegram").absolutePath

    /** /storage/emulated/0/Android/media/com.jc3901.tgbackup/TgBackup — visible in Files & Gallery. */
    fun backupRoot(c: Context): File {
        val base = c.externalMediaDirs.firstOrNull { it != null } ?: c.getExternalFilesDir(null) ?: c.filesDir
        return File(base, "TgBackup").apply { mkdirs() }
    }

    /** Connect with the saved API keys. Returns true if already logged in. Run off the main thread. */
    fun init(c: Context): Boolean {
        val p = prefs(c)
        val id = p.getString("api_id", "") ?: ""
        val hash = p.getString("api_hash", "") ?: ""
        require(id.isNotBlank() && hash.isNotBlank()) { "Enter your API ID and API hash first." }
        return py().callAttr("init", session(c), id, hash).toBoolean()
    }
}

/** Lets the service push status lines to the screen. */
object StatusBus {
    @Volatile var last: String = "Idle"
    @Volatile var running: Boolean = false
    var onChange: ((String) -> Unit)? = null
    fun post(s: String) { last = s; onChange?.invoke(s) }
}
