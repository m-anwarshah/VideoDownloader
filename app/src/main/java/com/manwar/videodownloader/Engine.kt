package com.manwar.videodownloader

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

object Engine {
    @Volatile
    var ready = false
        private set

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        Aria2c.getInstance().init(app)
        ready = true
    }
}
