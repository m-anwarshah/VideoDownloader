package com.manwar.videodownloader

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        // Contact address shown in the support section
        const val SUPPORT_EMAIL = "video.downloader.github@gmail.com"
        // Optional donation page. Leave blank to hide the Donate button.
        const val DONATE_URL = ""
    }

    private lateinit var urlInput: TextInputEditText
    private lateinit var pasteBtn: TextView
    private lateinit var qualityChips: ChipGroup
    private lateinit var formatChips: ChipGroup
    private lateinit var formatLabel: TextView
    private lateinit var aria2Switch: MaterialSwitch
    private lateinit var dataSwitch: MaterialSwitch
    private lateinit var downloadBtn: MaterialButton
    private lateinit var updateBtn: MaterialButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var statusText: TextView

    private lateinit var resultCard: MaterialCardView
    private lateinit var resultName: TextView
    private lateinit var playBtn: MaterialButton
    private lateinit var shareBtn: MaterialButton

    private lateinit var supportText: TextView
    private lateinit var emailBtn: MaterialButton
    private lateinit var donateBtn: MaterialButton

    private val urlRegex = Regex("""https?://\S+""")

    private var shownFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        pasteBtn = findViewById(R.id.pasteBtn)
        qualityChips = findViewById(R.id.qualityChips)
        formatChips = findViewById(R.id.formatChips)
        formatLabel = findViewById(R.id.formatLabel)
        aria2Switch = findViewById(R.id.aria2Check)
        dataSwitch = findViewById(R.id.dataSwitch)
        downloadBtn = findViewById(R.id.downloadBtn)
        updateBtn = findViewById(R.id.updateBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

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

        // MP3 has no container choice, so hide the file type row for it
        qualityChips.setOnCheckedStateChangeListener { _, _ -> syncFormatVisibility() }
        syncFormatVisibility()

        downloadBtn.setOnClickListener { startDownload() }
        updateBtn.setOnClickListener { updateEngine() }
        pasteBtn.setOnClickListener { pasteFromClipboard() }
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
        DownloadService.progressListener = { pct, line ->
            runOnUiThread { onProgress(pct, line) }
        }
        if (DownloadService.isRunning) status("Download running in background...")
        showFinishedFile(DownloadService.completedFile ?: History.lastFile(this))
    }

    override fun onPause() {
        DownloadService.progressListener = null
        super.onPause()
    }

    // ---------- selection ----------

    private fun selectedQuality(): String = when (qualityChips.checkedChipId) {
        R.id.chip1080 -> "1080p"
        R.id.chip720 -> "720p"
        R.id.chip480 -> "480p"
        R.id.chip360 -> "360p"
        R.id.chipMp3 -> "Audio only (MP3)"
        else -> "Best available"
    }

    private fun selectedFormat(): String =
        if (formatChips.checkedChipId == R.id.chipMkv) "MKV" else "MP4"

    private fun syncFormatVisibility() {
        val audio = qualityChips.checkedChipId == R.id.chipMp3
        formatLabel.visibility = if (audio) View.GONE else View.VISIBLE
        formatChips.visibility = if (audio) View.GONE else View.VISIBLE
    }

    // ---------- progress ----------

    private fun onProgress(pct: Int, line: String) {
        when (pct) {
            DownloadService.PROGRESS_DUPLICATE -> {
                val file = File(line)
                status("Already downloaded: ${file.name}")
                showFinishedFile(file)
            }
            DownloadService.PROGRESS_FAILED -> status(line.take(140))
            100 -> {
                progressBar.setProgressCompat(100, true)
                status(line.take(140))
                showFinishedFile(DownloadService.completedFile)
            }
            else -> {
                if (pct in 0..100) progressBar.setProgressCompat(pct, true)
                statusText.text = line.take(140)
            }
        }
    }

    private fun showFinishedFile(file: File?) {
        if (file == null || !file.exists()) {
            resultCard.visibility = View.GONE
            shownFile = null
            return
        }
        shownFile = file
        resultName.text = file.name
        playBtn.text = if (History.isAudio(file)) "Play" else "Play"
        resultCard.visibility = View.VISIBLE
    }

    // ---------- support ----------

    private fun setUpSupportSection() {
        supportText.text =
            "Made by Anwar Shah. This app is free and has no ads. If it saves you " +
            "time, a small donation keeps it updated.\n\n$SUPPORT_EMAIL"

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

    private fun clipboard() = getSystemService(ClipboardManager::class.java)

    private fun copyToClipboard(text: String) {
        clipboard().setPrimaryClip(ClipData.newPlainText("email", text))
    }

    private fun pasteFromClipboard() {
        val clip = clipboard().primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else ""
        val url = urlRegex.find(text)?.value
        if (url == null) {
            toast("No link found in clipboard")
            return
        }
        urlInput.setText(url)
        status("Link pasted. Pick a quality and hit Download.")
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
        status("Link received. Pick a quality and hit Download.")
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
            status("Ready. Paste a link or share one to this app.")
            return
        }
        status("Getting ready (first time takes a minute)...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Engine.init(this@MainActivity)
                withContext(Dispatchers.Main) {
                    status("Ready. Paste a link or share one to this app.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { status("Setup failed: ${e.message}") }
            }
        }
    }

    private fun updateEngine() {
        status("Updating engine...")
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
            toast("A download is already running")
            return
        }
        if (!Engine.ready) {
            toast("Still getting ready, wait a moment")
            return
        }
        val url = urlRegex.find(urlInput.text.toString())?.value
        if (url == null) {
            toast("Paste a valid video link first")
            return
        }
        if (!Net.downloadAllowed(this, dataSwitch.isChecked)) {
            status("No WiFi. Turn on 'Allow mobile data' to download without it.")
            toast("WiFi not connected")
            return
        }

        val quality = selectedQuality()

        val existing = History.existing(this, url, quality)
        if (existing != null) {
            showFinishedFile(existing)
            AlertDialog.Builder(this)
                .setTitle("Already downloaded")
                .setMessage("${existing.name}\n\nIt is already saved on your phone.")
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
            putExtra("format", selectedFormat())
            putExtra("aria2", aria2Switch.isChecked)
            putExtra("allowData", dataSwitch.isChecked)
            putExtra("force", force)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        progressBar.setProgressCompat(0, false)
        resultCard.visibility = View.GONE
        status("Started. It keeps going even if you close the app.")
    }

    private fun status(msg: String) {
        statusText.text = msg
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
