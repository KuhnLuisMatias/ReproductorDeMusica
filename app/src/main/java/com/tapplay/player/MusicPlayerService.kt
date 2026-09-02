package com.tapplay.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class MusicPlayerService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var wasPlayingBeforeHeadphoneDisconnect = false

    private val headsetReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val player = mediaSession?.player ?: return
                when (intent.action) {
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        wasPlayingBeforeHeadphoneDisconnect = player.isPlaying
                        player.pause()
                    }

                    AudioManager.ACTION_HEADSET_PLUG -> {
                        val plugged = intent.getIntExtra("state", 0) == 1
                        if (plugged && wasPlayingBeforeHeadphoneDisconnect) {
                            player.play()
                        }
                        if (plugged) {
                            wasPlayingBeforeHeadphoneDisconnect = false
                        }
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        val player =
            ExoPlayer.Builder(this)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    // handleAudioFocus =
                    true,
                )
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        player.repeatMode = Player.REPEAT_MODE_OFF
        mediaSession = MediaSession.Builder(this, player).build()
        registerHeadsetReceiver()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        unregisterReceiver(headsetReceiver)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun registerHeadsetReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
            }
        ContextCompat.registerReceiver(
            this,
            headsetReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
