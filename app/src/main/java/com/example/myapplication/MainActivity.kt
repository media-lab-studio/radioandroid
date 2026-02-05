package com.example.myapplication

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private companion object {
        const val RADIO_API_URL = "https://myradio24.org/users/25968/status.json"
        const val DEFAULT_VOLUME = 70
        const val UPDATE_INTERVAL = 10000L
    }

    private lateinit var recordButton: ImageView
    private lateinit var recordContainer: FrameLayout
    private lateinit var ivStatus: ImageView
    private lateinit var tvCurrentTrack: TextView
    private lateinit var tvNextTrack: TextView
    private lateinit var tvPlaylist: TextView
    private lateinit var sbVolume: SeekBar
    private lateinit var visualizer: LinearLayout
    private lateinit var btnTelegram: LinearLayout
    private lateinit var btnSponsor: LinearLayout

    private var isPlaying = false
    private var currentVolume = DEFAULT_VOLUME

    private var rotationAnimator: ObjectAnimator? = null
    private var visualizerJob: Job? = null
    private var trackUpdateJob: Job? = null

    private val mainScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
        setupVolumeControl()

        tvCurrentTrack.text = "EternalRock Radio"
        tvNextTrack.text = "Нажмите на пластинку"
        tvPlaylist.text = "Ожидание данных..."
    }

    private fun initViews() {
        recordButton = findViewById(R.id.recordButton)
        recordContainer = findViewById(R.id.recordContainer)
        ivStatus = findViewById(R.id.ivStatus)
        tvCurrentTrack = findViewById(R.id.tvCurrentTrack)
        tvNextTrack = findViewById(R.id.tvNextTrack)
        tvPlaylist = findViewById(R.id.tvPlaylist)
        sbVolume = findViewById(R.id.sbVolume)
        visualizer = findViewById(R.id.visualizer)
        btnTelegram = findViewById(R.id.btnTelegram)
        btnSponsor = findViewById(R.id.btnSponsor)
    }

    private fun setupClickListeners() {

        recordButton.setOnClickListener {
            togglePlayback()
        }

        btnTelegram.setOnClickListener {
            openTelegram()
        }

        btnSponsor.setOnClickListener {
            openSponsorLink()
        }
    }

    private fun togglePlayback() {
        if (isPlaying) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {

        isPlaying = true

        startRecordRotation()
        startVisualizer()

        ivStatus.setImageResource(R.drawable.ic_pause)

        tvCurrentTrack.text = "Подключение..."
        tvNextTrack.text = "Загрузка..."
        tvPlaylist.text = "Загрузка..."

        val intent = Intent(this, RadioService::class.java).apply {
            action = RadioService.ACTION_PLAY
        }
        startService(intent)

        startRealTrackUpdates()
    }

    private fun stopPlayback() {

        isPlaying = false

        stopRecordRotation()
        stopVisualizer()

        ivStatus.setImageResource(R.drawable.ic_play)

        val intent = Intent(this, RadioService::class.java).apply {
            action = RadioService.ACTION_STOP
        }
        startService(intent)

        tvCurrentTrack.text = "Радио выключено. Нажмите на пластинку"
        tvNextTrack.text = "Нажмите на пластинку"
        tvPlaylist.text = "Ожидание данных..."

        stopTrackUpdates()
    }

    private fun stopTrackUpdates() {
        trackUpdateJob?.cancel()
        trackUpdateJob = null
    }


    // -------------------------------
    // Radio status
    // -------------------------------

    private fun startRealTrackUpdates() {

        trackUpdateJob?.cancel()

        trackUpdateJob = mainScope.launch {
            while (isActive && isPlaying) {
                val status = fetchRadioStatus()
                status?.let { updateUI(it) }
                delay(UPDATE_INTERVAL)
            }
        }
    }

    private suspend fun fetchRadioStatus(): RadioStatus? {

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(RADIO_API_URL)
                val connection = url.openConnection() as HttpURLConnection

                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"

                if (connection.responseCode != 200) return@withContext null

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val sb = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }

                reader.close()
                connection.disconnect()

                parseRadioStatus(sb.toString())

            } catch (e: Exception) {
                Log.e("RadioPlayer", "status error", e)
                null
            }
        }
    }

    private fun parseRadioStatus(jsonString: String): RadioStatus? {

        return try {
            val json = JSONObject(jsonString)

            val artist = json.optString("artist", "")
            val song = json.optString("song", "")

            val currentTrack =
                if (artist.isNotEmpty() && song.isNotEmpty())
                    "$artist - $song"
                else
                    json.optString("title", "Неизвестный трек")

            val nextArray = json.optJSONArray("nextsongs")

            val nextTrack =
                if (nextArray != null && nextArray.length() > 0)
                    nextArray.getJSONObject(0).optString("song", "Нет данных")
                else
                    "Нет данных"

            val playlist = formatPlaylistName(
                json.optString("playlist", "Неизвестный плейлист")
            )

            val kbps = json.optInt("kbps", 128)

            RadioStatus(
                currentTrack,
                nextTrack,
                playlist,
                "$kbps kbps MP3"
            )

        } catch (e: Exception) {
            Log.e("RadioPlayer", "parse error", e)
            null
        }
    }

    private fun updateUI(status: RadioStatus) {

        runOnUiThread {

            tvCurrentTrack.text = status.currentTrack
            tvCurrentTrack.isSelected = true

            tvNextTrack.text = status.nextTrack
            tvPlaylist.text = status.playlist

            tvCurrentTrack.alpha = 0f
            tvCurrentTrack.animate().alpha(1f).setDuration(400).start()
        }
    }

    private fun formatPlaylistName(playlist: String): String {

        val lastUnderscoreIndex = playlist.lastIndexOf('_')

        return if (lastUnderscoreIndex != -1) {
            playlist.substring(0, lastUnderscoreIndex).replace('_', ' ')
        } else {
            playlist.replace('_', ' ')
        }
    }

    data class RadioStatus(
        val currentTrack: String,
        val nextTrack: String,
        val playlist: String,
        val bitrate: String
    )

    // -------------------------------
    // Visualizer
    // -------------------------------

    private fun startVisualizer() {

        visualizerJob?.cancel()

        visualizerJob = mainScope.launch {

            val bars = (0 until visualizer.childCount)
                .map { visualizer.getChildAt(it) }

            while (isActive && isPlaying) {

                bars.forEachIndexed { index, view ->

                    val time = System.currentTimeMillis() * 0.001
                    val height = (sin(time * 2 + index * 0.3) * 25 + 35).toFloat()

                    view.layoutParams.height = height.toInt()
                    view.requestLayout()

                    val alphaFloat = (height / 100f).coerceIn(0.3f, 0.8f)
                    view.setBackgroundColor(
                        Color.argb(
                            (alphaFloat * 255).toInt(),
                            255, 94, 0
                        )
                    )
                }

                delay(50)
            }
        }
    }

    private fun stopVisualizer() {

        visualizerJob?.cancel()
        visualizerJob = null

        runOnUiThread {
            for (i in 0 until visualizer.childCount) {
                val view = visualizer.getChildAt(i)
                view.layoutParams.height = 20
                view.setBackgroundColor(Color.parseColor("#FF5E00"))
                view.requestLayout()
            }
        }
    }

    // -------------------------------
    // Rotation
    // -------------------------------

    private fun startRecordRotation() {

        rotationAnimator =
            ObjectAnimator.ofFloat(recordContainer, "rotation", 0f, 360f).apply {
                duration = 3000
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
    }

    private fun stopRecordRotation() {
        rotationAnimator?.cancel()
        recordContainer.rotation = 0f
    }

    // -------------------------------
    // Volume (UI only)
    // -------------------------------

    private fun setupVolumeControl() {

        sbVolume.progress = currentVolume

        sbVolume.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                currentVolume = progress

                val intent = Intent(this@MainActivity, RadioService::class.java).apply {
                    action = RadioService.ACTION_SET_VOLUME
                    putExtra(RadioService.EXTRA_VOLUME, progress)
                }

                startService(intent)

                // Реальная громкость управляется уже внутри сервиса
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // -------------------------------
    // Links
    // -------------------------------

    private fun openTelegram() {
        val telegramUrl = "https://t.me/+IKyfzhp_0MQ3NjAy"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(telegramUrl)))
    }

    private fun openSponsorLink() {
        openTelegram()
    }

    // -------------------------------
    // Lifecycle
    // -------------------------------

    override fun onPause() {
        super.onPause()
        // НИЧЕГО не останавливаем!
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }
}
