package com.example.myapplication

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder

class RadioService : Service() {

    companion object {
        const val ACTION_PLAY = "PLAY"
        const val ACTION_STOP = "STOP"
        const val STREAM_URL = "https://myradio24.org/25968"
        const val CHANNEL_ID = "radio_channel"
        const val ACTION_SET_VOLUME = "SET_VOLUME"
        const val EXTRA_VOLUME = "EXTRA_VOLUME"

    }

    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_PLAY -> startRadio()
            ACTION_STOP -> stopRadio()
            ACTION_SET_VOLUME -> {
                val v = intent.getIntExtra(EXTRA_VOLUME, 70)
                setVolume(v)
            }
        }

        return START_STICKY
    }

    private fun setVolume(volume: Int) {

        val v = volume.coerceIn(0, 100) / 100f
        player?.setVolume(v, v)
    }

    private fun startRadio() {

        if (player != null) return

        val notification = buildNotification()
        startForeground(1, notification)

        player = MediaPlayer().apply {

            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            setDataSource(STREAM_URL)

            setOnPreparedListener {
                it.start()
            }

            prepareAsync()
        }
    }

    private fun stopRadio() {

        player?.stop()
        player?.release()
        player = null

        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopRadio()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("EternalRock Radio")
            .setContentText("Воспроизведение")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Radio playback",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
