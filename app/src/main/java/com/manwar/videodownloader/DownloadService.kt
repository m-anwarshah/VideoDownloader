package com.manwar.videodownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIF_ID = 1
        const val DONE_NOTIF_ID = 2

        /** Progress codes handed to the listener alongside a message. */
        const val PROGRESS_FAILED = -1
        const val PROGRESS_DUPLICATE = -2

        @Volatile var isRunning = false
        // MainActivity sets this to receive live progress while it is open
        @Volatile var progressListener: ((Int, String) -> Unit)? = null
        // Last file this service finished, so the UI can offer to open it
        @Volatile var completedFile: File? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPct = -1
    private var allowData = false
    private var currentProcessId: String? = null
    private var stoppedByNetwork = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // Output path, scraped from yt-dlp's own log lines
    private var destPath: String? = null
    private var finalPath: String? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Downloads",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url")
        if (url == null) {
            stopSelf(); return START_NOT_STICKY
        }
        val quality = intent.getStringExtra("quality") ?: "Best available"
        val fmt = intent.getStringExtra("format") ?: "MP4"
        val useAria2 = intent.getBooleanExtra("aria2", true)
        allowData = intent.getBooleanExtra("allowData", false)
        // Set when the user answered "Download again" to the duplicate prompt
        val force = intent.getBooleanExtra("force", false)

        if (!Net.downloadAllowed(this, allowData)) {
            notifyDone("Download not started", "No WiFi (mobile data not allowed)")
            stopSelf(); return START_NOT_STICKY
        }

        // startForegroundService was used, so the notification must go up promptly
        // even on the paths that bail out below.
        startInForeground("Checking link...", 0)
        acquireWakeLock()
        isRunning = true

        currentProcessId = url.hashCode().toString()
        stoppedByNetwork = false
        destPath = null
        finalPath = null
        lastPct = -1
        startedAt = System.currentTimeMillis()
        watchNetwork()

        scope.launch {
            try {
                Engine.init(this@DownloadService)

                val outDir = outputDir()

                // Already on the phone? Say so instead of fetching it twice.
                if (!force) {
                    val already = findExisting(url, quality, outDir)
                    if (already != null) {
                        History.record(this@DownloadService, url, quality, already)
                        completedFile = already
                        notifyDone("Already downloaded", already.name, already)
                        progressListener?.invoke(PROGRESS_DUPLICATE, already.absolutePath)
                        return@launch
                    }
                }

                updateNotification("Downloading...", 0)

                val request = buildRequest(url, quality, fmt, useAria2, outDir)
                YoutubeDL.getInstance()
                    .execute(request, currentProcessId) { progress, _, line ->
                        scanForOutputPath(line)
                        val pct = if (progress >= 0) progress.toInt() else lastPct
                        if (pct != lastPct) {
                            lastPct = pct
                            updateNotification("Downloading  $pct%", pct)
                        }
                        progressListener?.invoke(pct, line)
                    }

                val saved = resolveSavedFile(outDir)
                if (saved != null) {
                    History.record(this@DownloadService, url, quality, saved)
                    completedFile = saved
                }
                notifyDone(
                    "Download complete",
                    saved?.name ?: "Saved in Download/Video Downloads",
                    saved
                )
                progressListener?.invoke(100, "Done. Saved in Download/Video Downloads")
            } catch (e: Exception) {
                if (stoppedByNetwork) {
                    notifyDone(
                        "Download stopped",
                        "WiFi disconnected. Start again on WiFi, or allow mobile data — it resumes where it left off."
                    )
                    progressListener?.invoke(PROGRESS_FAILED, "Stopped: WiFi disconnected")
                } else {
                    val msg = e.message?.take(120) ?: "Unknown error"
                    notifyDone("Download failed", msg)
                    progressListener?.invoke(PROGRESS_FAILED, "Failed: $msg")
                }
            } finally {
                isRunning = false
                unwatchNetwork()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ---------- duplicate detection ----------

    private fun outputDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Video Downloads"
        )
        dir.mkdirs()
        return dir
    }

    /**
     * MainActivity already checked local history before starting us. This catches
     * the rest: the same video after a reinstall, or shared through a URL our
     * history key did not match.
     */
    private fun findExisting(url: String, quality: String, outDir: File): File? {
        History.existing(this, url, quality)?.let { return it }

        val title = try {
            YoutubeDL.getInstance().getInfo(url).title
        } catch (e: Exception) {
            null
        } ?: return null

        return History.fileWithTitle(outDir, title, wantAudio = quality == "Audio only (MP3)")
    }

    // ---------- finding what was written ----------

    private val reDestination = Regex("""\[download]\s+Destination:\s+(.+)""")
    private val reMerger = Regex("\\[Merger]\\s+Merging formats into\\s+\"(.+)\"")
    private val reExtractAudio = Regex("""\[ExtractAudio]\s+Destination:\s+(.+)""")
    private val reAlready = Regex("""\[download]\s+(.+?)\s+has already been downloaded""")

    private fun scanForOutputPath(raw: String) {
        for (line in raw.split('\n')) {
            val l = line.trim()
            // Merge and audio-extract run last, so their output is the keeper
            val merged = reMerger.find(l) ?: reExtractAudio.find(l)
            if (merged != null) {
                finalPath = merged.groupValues[1].trim()
                continue
            }
            val dest = reDestination.find(l) ?: reAlready.find(l)
            if (dest != null) destPath = dest.groupValues[1].trim()
        }
    }

    private fun resolveSavedFile(outDir: File): File? {
        (finalPath ?: destPath)?.let {
            val f = File(it)
            if (f.exists() && f.length() > 0) return f
        }
        // Fallback: newest file written since this download began
        return outDir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.lastModified() >= startedAt - 2000 }
            ?.maxByOrNull { it.lastModified() }
    }

    // Stop the download if we lose WiFi and mobile data is not allowed
    private fun watchNetwork() {
        if (allowData) return
        val cm = getSystemService(ConnectivityManager::class.java)
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network, caps: NetworkCapabilities
            ) {
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isWifi) stopDownloadForNetwork()
            }

            override fun onLost(network: Network) {
                if (!Net.downloadAllowed(this@DownloadService, false)) {
                    stopDownloadForNetwork()
                }
            }
        }
        cm.registerDefaultNetworkCallback(netCallback!!)
    }

    private fun unwatchNetwork() {
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        netCallback = null
    }

    private fun stopDownloadForNetwork() {
        if (stoppedByNetwork) return
        stoppedByNetwork = true
        currentProcessId?.let {
            try {
                YoutubeDL.getInstance().destroyProcessById(it)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        scope.cancel()
        unwatchNetwork()
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    // ---------- request building ----------

    private fun buildRequest(
        url: String, quality: String, fmt: String, useAria2: Boolean, outDir: File
    ): YoutubeDLRequest {
        val isYouTube = url.contains("youtube.com") || url.contains("youtu.be")

        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("-o", "${outDir.absolutePath}/%(title).150s.%(ext)s")
        request.addOption("--socket-timeout", "15")
        request.addOption("--retries", "10")
        request.addOption("--fragment-retries", "10")
        request.addOption("--concurrent-fragments", "8")
        request.addOption("--http-chunk-size", "10M")

        if (useAria2) {
            request.addOption("--downloader", "libaria2c.so")
            request.addOption("--external-downloader-args", "aria2c:-x 16 -s 16 -k 1M")
        }

        when {
            quality == "Audio only (MP3)" -> {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "192K")
            }
            quality == "Best available" -> {
                if (isYouTube) {
                    request.addOption("-f", "bestvideo+bestaudio/best")
                } else {
                    request.addOption("-f", "best[ext=mp4]/best/bestvideo+bestaudio")
                }
                request.addOption("--merge-output-format", fmt.lowercase())
            }
            else -> {
                val h = quality.removeSuffix("p")
                if (isYouTube) {
                    request.addOption(
                        "-f", "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best"
                    )
                } else {
                    request.addOption(
                        "-f",
                        "best[height<=$h][ext=mp4]/best[height<=$h]/bestvideo[height<=$h]+bestaudio/best"
                    )
                }
                request.addOption("--merge-output-format", fmt.lowercase())
            }
        }
        return request
    }

    // ---------- notifications ----------

    private fun baseNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Video Downloader")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )

    private fun startInForeground(text: String, pct: Int) {
        val notif = baseNotification(text)
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String, pct: Int) {
        val notif = baseNotification(text)
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun notifyDone(title: String, text: String, file: File? = null) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)

        if (file != null && file.exists()) {
            val play = PendingIntent.getActivity(
                this, 3, Opener.viewIntent(this, file), PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(play)
            builder.addAction(android.R.drawable.ic_media_play, "Play", play)
        } else {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        getSystemService(NotificationManager::class.java).notify(DONE_NOTIF_ID, builder.build())
    }

    // ---------- wake lock ----------

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "VideoDownloader:download"
        ).apply { acquire(3 * 60 * 60 * 1000L) } // up to 3 hours
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
