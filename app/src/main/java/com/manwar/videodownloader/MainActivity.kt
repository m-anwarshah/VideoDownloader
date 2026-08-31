package com.manwar.videodownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        // Contact address shown in the support section
        const val SUPPORT_EMAIL = "video.downloader.github@gmail.com"
        // Optional: a donation page. Leave blank to hide the Donate button.
        const val DONATE_URL = ""
    }

    private lateinit var urlInput: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var formatSpinner: Spinner
    private lateinit var aria2Check: CheckBox
    private lateinit var dataSwitch: SwitchCompat
    private lateinit var downloadBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private lateinit var resultCard: View
    private lateinit var resultName: TextView
    private lateinit var playBtn: Button
    private lateinit var shareBtn: Button

    private lateinit var supportText: TextView
    private lateinit var emailBtn: Button
    private lateinit var donateBtn: Button

    private val qualities =
        arrayOf("Best available", "1080p", "720p", "480p", "360p", "Audio only (MP3)")
    private val formats = arrayOf("MP4", "MKV")

    private val urlRegex = Regex("""https?://\S+""")

    private var shownFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        formatSpinner = findViewById(R.id.formatSpinner)
        aria2Check = findViewById(R.id.aria2Check)
        dataSwitch = findViewById(R.id.dataSwitch)

        resultCard = findViewById(R.id.resultCard)
        resultName = findViewById(R.id.resultName)
        playBtn = findViewById(R.id.playBtn)
        shareBtn = findViewById(R.id.shareBtn)

        supportText = findViewById(R.id.supportText)
        emailBtn = findViewById(R.id.emailBtn)
        donateBtn = findViewById(R.id.donateBtn)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        dataSwitch.isChecked = prefs.getBoolean("allow_data", false)
        dataSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("allow_data", checked).apply()
        }
        downloadBtn = findViewById(R.id.downloadBtn)
        updateBtn = findViewById(R.id.updateBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        qualitySpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualities)
        formatSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats)

        downloadBtn.setOnClickListener { startDownload() }
        updateBtn.setOnClickListener { updateEngine() }
        playBtn.setOnClickListener { shownFile?.let { Opener.open(this, it) } }
        shareBtn.setOnClickListener { shownFile?.let { Opener.share(this, it) } }

        setUpSupportSection()

        requestPermissionsIfNeeded()
        initEngine()
        showFinishedFile(DownloadService.completedFile ?: History.lastFile(this))
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Receive live progress from the background service while visible
        DownloadService.progressListener = { pct, line ->
            runOnUiThread { onProgress(pct, line) }
        }
        if (DownloadService.isRunning) {
            status("Download running in background...")
        }
        showFinishedFile(DownloadService.completedFile ?: History.lastFile(this))
    }

    override fun onPause() {
        DownloadService.progressListener = null
        super.onPause()
    }

    private fun onProgress(pct: Int, line: String) {
        when (pct) {
            DownloadService.PROGRESS_DUPLICATE -> {
                val file = File(line)
                status("Already downloaded: ${file.name}")
                showFinishedFile(file)
            }
            DownloadService.PROGRESS_FAILED -> {
                status(line.take(140))
            }
            100 -> {
                progressBar.progress = 100
                status(line.take(140))
                showFinishedFile(DownloadService.completedFile)
            }
            else -> {
                if (pct in 0..100) progressBar.progress = pct
                statusText.text = line.take(140)
            }
        }
    }

    // ---------- finished file ----------

    private fun showFinishedFile(file: File?) {
        if (file == null || !file.exists()) {
            resultCard.visibility = View.GONE
            shownFile = null
            return
        }
        shownFile = file
        resultName.text = file.name
        playBtn.text = if (History.isAudio(file)) "Play audio" else "Play video"
        resultCard.visibility = View.VISIBLE
    }

    // ---------- support ----------

    private fun setUpSupportSection() {
        supportText.text =
            "Made by Anwar Shah. If the app is useful to you, a small donation keeps it " +
            "free and updated. Bug reports and feature requests are welcome too.\n\n" +
            SUPPORT_EMAIL

        emailBtn.setOnClickListener {
            val mail = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$SUPPORT_EMAIL")
                putExtra(Intent.EXTRA_SUBJECT, "Video Downloader")
            }
            try {
                startActivity(Intent.createChooser(mail, "Send email"))
            } catch (e: Exception) {
                copyToClipboard(SUPPORT_EMAIL)
                toast("No email app found — address copied")
            }
        }

        if (DONATE_URL.isBlank()) {
            donateBtn.visibility = View.GONE
        } else {
            donateBtn.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)))
                } catch (e: Exception) {
                    toast("No browser found")
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("email", text))
    }

    // ---------- existing behaviour ----------

    private fun handleIncomingIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val url = urlRegex.find(text)?.value ?: return
        urlInput.setText(url)
        status("Link received. Choose quality and press Download.")
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < 30 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    private fun initEngine() {
        if (Engine.ready) {
            status("Ready. Share a link here or paste it above.")
            return
        }
        status("Preparing download engine (first time takes a minute)...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Engine.init(this@MainActivity)
                withContext(Dispatchers.Main) {
                    status("Ready. Share a link here or paste it above.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status("Engine init failed: ${e.message}")
                }
            }
        }
    }

    private fun updateEngine() {
        status("Updating yt-dlp engine...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance()
                    .updateYoutubeDL(this@MainActivity, YoutubeDL.UpdateChannel.STABLE)
                withContext(Dispatchers.Main) { status("Engine updated.") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { status("Update failed: ${e.message}") }
            }
        }
    }

    private fun startDownload() {
        if (DownloadService.isRunning) {
            toast("A download is already running — check the notification")
            return
        }
        if (!Engine.ready) {
            toast("Engine is still preparing, wait a moment")
            return
        }
        val url = urlRegex.find(urlInput.text.toString())?.value
        if (url == null) {
            toast("Paste a valid video link first")
            return
        }
        if (!Net.downloadAllowed(this, dataSwitch.isChecked)) {
            status("No WiFi. Turn ON the mobile data switch above to download on data.")
            toast("WiFi not connected")
            return
        }

        val quality = qualities[qualitySpinner.selectedItemPosition]

        // Instant local check — no network needed for the common case
        val existing = History.existing(this, url, quality)
        if (existing != null) {
            showFinishedFile(existing)
            AlertDialog.Builder(this)
                .setTitle("Already downloaded")
                .setMessage("${existing.name}\n\nIt is already in Download/Video Downloads.")
                .setPositiveButton("Play") { _, _ ->
                    History.setLastFile(this, existing)
                    Opener.open(this, existing)
                }
                .setNegativeButton("Download again") { _, _ -> launchService(url, quality, true) }
                .setNeutralButton("Cancel", null)
                .show()
            return
        }

        launchService(url, quality, false)
    }

    private fun launchService(url: String, quality: String, force: Boolean) {
        val serviceIntent = Intent(this, DownloadService::class.java).apply {
            putExtra("url", url)
            putExtra("quality", quality)
            putExtra("format", formats[formatSpinner.selectedItemPosition])
            putExtra("aria2", aria2Check.isChecked)
            putExtra("allowData", dataSwitch.isChecked)
            putExtra("force", force)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        progressBar.progress = 0
        resultCard.visibility = View.GONE
        status("Download started — it keeps running even if you close this app. Progress shows in the notification.")
    }

    private fun status(msg: String) {
        statusText.text = msg
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
