package com.jc3901.tgbackup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val bg = Executors.newSingleThreadExecutor()

    private lateinit var apiId: EditText
    private lateinit var apiHash: EditText
    private lateinit var phone: EditText
    private lateinit var code: EditText
    private lateinit var password: EditText
    private lateinit var channels: EditText
    private lateinit var live: MaterialSwitch
    private lateinit var loginState: TextView
    private lateinit var status: TextView
    private lateinit var btnSync: Button
    private var loggedIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiId = findViewById(R.id.apiId)
        apiHash = findViewById(R.id.apiHash)
        phone = findViewById(R.id.phone)
        code = findViewById(R.id.code)
        password = findViewById(R.id.password)
        channels = findViewById(R.id.channels)
        live = findViewById(R.id.live)
        loginState = findViewById(R.id.loginState)
        status = findViewById(R.id.status)
        btnSync = findViewById(R.id.btnSync)

        val p = Tg.prefs(this)
        apiId.setText(p.getString("api_id", ""))
        apiHash.setText(p.getString("api_hash", ""))
        phone.setText(p.getString("phone", ""))
        channels.setText(p.getString("channels", ""))
        live.isChecked = p.getBoolean("live", true)
        findViewById<TextView>(R.id.folder).text =
            "Saved to: ${Tg.backupRoot(this).absolutePath}\n(Photos & videos also show up in your Gallery.)"

        findViewById<Button>(R.id.btnOpenKeysSite).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps")))
        }
        findViewById<Button>(R.id.btnSendCode).setOnClickListener { sendCode() }
        findViewById<Button>(R.id.btnLogin).setOnClickListener { login() }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { logout() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askBattery() }
        btnSync.setOnClickListener { toggleSync() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        if (!p.getString("api_id", "").isNullOrBlank()) checkLogin()
    }

    override fun onResume() {
        super.onResume()
        StatusBus.onChange = { s -> runOnUiThread { status.text = s; refreshSyncButton() } }
        status.text = StatusBus.last
        refreshSyncButton()
    }

    override fun onPause() {
        super.onPause()
        StatusBus.onChange = null
        saveFields()
    }

    private fun saveFields() {
        Tg.prefs(this).edit()
            .putString("api_id", apiId.text.toString().trim())
            .putString("api_hash", apiHash.text.toString().trim())
            .putString("phone", phone.text.toString().trim())
            .putString("channels", channels.text.toString())
            .putBoolean("live", live.isChecked)
            .apply()
    }

    /** Run Python work off the main thread, then show the result. */
    private fun <T> work(busy: String, job: () -> T, done: (T) -> Unit) {
        saveFields()
        loginState.text = busy
        bg.execute {
            val r = runCatching(job)
            runOnUiThread {
                r.onSuccess(done).onFailure {
                    loginState.text = "Problem: ${it.message?.substringAfterLast(": ") ?: it}"
                    toast(it.message ?: "Error")
                }
            }
        }
    }

    private fun checkLogin() = work("Connecting…", { if (Tg.init(this)) Tg.py().callAttr("me").toString() else null }) {
        setLoggedIn(it)
    }

    @SuppressLint("SetTextI18n")
    private fun setLoggedIn(name: String?) {
        loggedIn = name != null
        loginState.text = if (loggedIn) "✓ Logged in as $name" else "Not logged in"
        findViewById<View>(R.id.loginForm).visibility = if (loggedIn) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnLogout).visibility = if (loggedIn) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardKeys).visibility = if (loggedIn) View.GONE else View.VISIBLE
        refreshSyncButton()
    }

    private fun sendCode() {
        val ph = phone.text.toString().trim()
        if (ph.isEmpty()) return toast("Enter your phone number")
        work("Sending code…", {
            if (Tg.init(this)) "already" else Tg.py().callAttr("send_code", ph).toString()
        }) { r ->
            when {
                r == "already" -> checkLogin()
                r == "ok" -> {
                    loginState.text = "Code sent — check your Telegram app (or SMS)."
                    findViewById<View>(R.id.codeBox).visibility = View.VISIBLE
                    findViewById<View>(R.id.btnLogin).visibility = View.VISIBLE
                    code.requestFocus()
                }
                else -> loginState.text = r.removePrefix("error: ")
            }
        }
    }

    private fun login() {
        val pwBox = findViewById<View>(R.id.passwordBox)
        val usePw = pwBox.visibility == View.VISIBLE
        val ph = phone.text.toString().trim()
        val c = code.text.toString().trim()
        val pw = password.text.toString()
        work("Logging in…", {
            if (usePw) Tg.py().callAttr("sign_in_password", pw).toString()
            else Tg.py().callAttr("sign_in", ph, c).toString()
        }) { r ->
            when (r) {
                "ok" -> checkLogin()
                "password" -> {
                    pwBox.visibility = View.VISIBLE
                    loginState.text = "Your account has two-step verification — enter that password."
                    password.requestFocus()
                }
                else -> loginState.text = r.removePrefix("error: ")
            }
        }
    }

    private fun logout() {
        if (StatusBus.running) SyncService.stop(this)
        work("Logging out…", { Tg.py().callAttr("logout") }) { setLoggedIn(null) }
    }

    private fun toggleSync() {
        saveFields()
        if (StatusBus.running) {
            SyncService.stop(this)
            status.text = "Stopping…"
            return
        }
        if (!loggedIn) return toast("Log in first")
        if (channels.text.isNullOrBlank()) return toast("Add a channel first")
        SyncService.start(this)
        btnSync.text = "Stop backup"
    }

    private fun refreshSyncButton() {
        btnSync.text = if (StatusBus.running) "Stop backup" else "Start backup"
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return toast("Already allowed ✓")
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
