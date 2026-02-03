package com.example.myapplication

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlin.math.sin
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // Конфигурация
    private companion object {
        const val STREAM_URL = "https://myradio24.org/25968"
        const val API_URL = "https://myradio24.com/users/25968/status.json"
        const val DEFAULT_VOLUME = 70
        const val UPDATE_INTERVAL = 30000L // 30 секунд
    }

    // UI элементы
    private lateinit var recordButton: ImageView
    private lateinit var recordContainer: FrameLayout
    private lateinit var ivStatus: ImageView
    private lateinit var tvCurrentTrack: TextView
    private lateinit var tvNextTrack: TextView
    private lateinit var tvPlaylistName: TextView // для отображения плейлиста
    private lateinit var nextTrackContainer: LinearLayout
    private lateinit var sbVolume: SeekBar
    private lateinit var visualizer: LinearLayout
    private lateinit var btnTelegram: LinearLayout
    private lateinit var btnSponsor: LinearLayout

    // Состояние
    private var isPlaying = false
    private var currentVolume = DEFAULT_VOLUME
    private var rotationAnimator: ObjectAnimator? = null
    private var visualizerJob: Job? = null
    private var trackUpdateJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    // Данные треков
    private var currentTrack = ""
    private var nextTrack = ""
    private var currentPlaylist = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
        setupVolumeControl()

        // Загружаем начальные данные
        loadInitialData()
    }

    private fun initViews() {
        recordButton = findViewById(R.id.recordButton)
        recordContainer = findViewById(R.id.recordContainer)
        ivStatus = findViewById(R.id.ivStatus)
        tvCurrentTrack = findViewById(R.id.tvCurrentTrack)
        tvNextTrack = findViewById(R.id.tvNextTrack)
        nextTrackContainer = findViewById(R.id.nextTrackContainer)
        sbVolume = findViewById(R.id.sbVolume)
        visualizer = findViewById(R.id.visualizer)
        btnTelegram = findViewById(R.id.btnTelegram)
        btnSponsor = findViewById(R.id.btnSponsor)
        tvPlaylistName = findViewById(R.id.tvPlaylistName)

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

    private fun loadInitialData() {
        // Загружаем начальные данные о треке и плейлисте
        CoroutineScope(IO).launch {
            fetchTrackInfo()
        }
    }

    private fun togglePlayback() {
        if (isPlaying) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        isPlaying = true

        // Анимация вращения пластинки
        startRecordRotation()

        // Изменение иконки статуса
        ivStatus.setImageResource(R.drawable.ic_pause)

        // Показ следующего трека
        nextTrackContainer.visibility = View.VISIBLE

        // Запуск визуализатора
        startVisualizer()

        // Запуск аудиопотока
        startAudioStream()

        // Запуск обновления информации о треках
        startTrackUpdates()
    }

    private fun stopPlayback() {
        isPlaying = false

        // Остановка анимации вращения
        stopRecordRotation()

        // Изменение иконки статуса
        ivStatus.setImageResource(R.drawable.ic_play)

        // Скрытие следующего трека
        nextTrackContainer.visibility = View.GONE

        // Остановка визуализатора
        stopVisualizer()

        // Обновление текста статуса
        updateStatusText()

        // Остановка аудио
        stopAudioStream()

        // Остановка обновления
        stopTrackUpdates()
    }

    private fun startAudioStream() {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(STREAM_URL)
                prepareAsync()
                setOnPreparedListener {
                    start()
                    setVolume(currentVolume / 100f, currentVolume / 100f)
                    Log.d("RadioPlayer", "Аудио поток запущен")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("RadioPlayer", "Ошибка аудио: what=$what, extra=$extra")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Ошибка подключения к радио", Toast.LENGTH_SHORT).show()
                        stopPlayback()
                    }
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("RadioPlayer", "Ошибка запуска аудио: ${e.message}")
            Toast.makeText(this, "Ошибка загрузки потока", Toast.LENGTH_SHORT).show()
            stopPlayback()
        }
    }

    private fun stopAudioStream() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
        }
        mediaPlayer = null
        Log.d("RadioPlayer", "Аудио поток остановлен")
    }

    private fun startRecordRotation() {
        rotationAnimator = ObjectAnimator.ofFloat(recordContainer, "rotation", 0f, 360f).apply {
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

    private fun setupVolumeControl() {
        sbVolume.progress = currentVolume
        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentVolume = progress
                mediaPlayer?.setVolume(progress / 100f, progress / 100f)
                updateVolumeIcon(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateVolumeIcon(volume: Int) {
        // Можно добавить логику изменения иконки в зависимости от громкости
        // Например, разные иконки для mute/low/medium/high volume
    }

    private fun startTrackUpdates() {
        trackUpdateJob?.cancel()

        trackUpdateJob = CoroutineScope(Main).launch {
            while (isActive && isPlaying) {
                fetchTrackInfo()
                delay(UPDATE_INTERVAL)
            }
        }
    }

    private fun stopTrackUpdates() {
        trackUpdateJob?.cancel()
        trackUpdateJob = null
    }

    private suspend fun fetchTrackInfo() {
        withContext(IO) {
            try {
                val url = java.net.URL(API_URL)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val inputStream = connection.inputStream
                    val response = inputStream.bufferedReader().use { it.readText() }

                    val json = JSONObject(response)

                    // Получаем текущий трек
                    val song = json.optString("song", "")
                    val artist = json.optString("artist", "")

                    val currentTrackText = if (artist.isNotEmpty() && song.isNotEmpty()) {
                        "$artist - $song"
                    } else if (song.isNotEmpty()) {
                        song
                    } else {
                        "Информация о треке недоступна"
                    }

                    // Получаем следующий трек
                    val nextTrackText = if (json.has("nextsongs")) {
                        val nextSongs = json.getJSONArray("nextsongs")
                        if (nextSongs.length() > 0) {
                            val nextSong = nextSongs.getJSONObject(0)
                            val nextArtist = nextSong.optString("artist", "")
                            val nextSongTitle = nextSong.optString("song", "")

                            if (nextArtist.isNotEmpty() && nextSongTitle.isNotEmpty()) {
                                "$nextArtist - $nextSongTitle"
                            } else if (nextSongTitle.isNotEmpty()) {
                                nextSongTitle
                            } else {
                                ""
                            }
                        } else {
                            ""
                        }
                    } else {
                        ""
                    }

                    // Получаем название плейлиста
                    val playlist = json.optString("playlist", "")
                    val playlistName = if (playlist.isNotEmpty()) {
                        playlist.replace("_", " ").replace("\\s*\\d+\$".toRegex(), "").trim()
                    } else {
                        "Rock / Metal / Alternative"
                    }

                    // Обновляем UI в главном потоке
                    withContext(Main) {
                        updateTrackInfo(currentTrackText, nextTrackText, playlistName)
                    }

                    Log.d("TrackInfo", "Текущий трек: $currentTrackText")
                    Log.d("TrackInfo", "Следующий трек: $nextTrackText")
                    Log.d("TrackInfo", "Плейлист: $playlistName")

                } else {
                    Log.e("TrackInfo", "Ошибка HTTP: $responseCode")
                    withContext(Main) {
                        updateStatusText()
                    }
                }

                connection.disconnect()

            } catch (e: Exception) {
                Log.e("TrackInfo", "Ошибка получения данных: ${e.message}")
                withContext(Main) {
                    updateStatusText()
                }
            }
        }
    }

    private fun updateTrackInfo(current: String, next: String, playlist: String) {
        // Сохраняем данные
        currentTrack = current
        nextTrack = next
        currentPlaylist = playlist

        // Обновляем текущий трек
        tvCurrentTrack.text = current

        // Обновляем следующий трек
        if (next.isNotEmpty()) {
            tvNextTrack.text = next
            if (isPlaying) {
                nextTrackContainer.visibility = View.VISIBLE
            }
        } else {
            nextTrackContainer.visibility = View.GONE
        }

        // Обновляем плейлист (если есть TextView для него)
        // tvPlaylistName?.text = playlist

        // Анимация обновления
        tvCurrentTrack.alpha = 0f
        tvCurrentTrack.animate()
            .alpha(1f)
            .setDuration(500)
            .start()
    }

    private fun updateStatusText() {
        if (isPlaying) {
            if (currentTrack.isNotEmpty()) {
                tvCurrentTrack.text = currentTrack
            } else {
                tvCurrentTrack.text = "Загрузка информации о треке..."
            }
        } else {
            tvCurrentTrack.text = "Радио выключено. Нажмите на пластинку"
            nextTrackContainer.visibility = View.GONE
        }
    }

    private fun startVisualizer() {
        visualizerJob = CoroutineScope(Main).launch {
            val bars = mutableListOf<View>()
            for (i in 0 until visualizer.childCount) {
                bars.add(visualizer.getChildAt(i) as View)
            }

            while (isActive && isPlaying) {
                bars.forEachIndexed { index, view ->
                    // Синусоидальная анимация для эффекта визуализатора
                    val amplitude = 50.0
                    val frequency = 0.1
                    val phase = index * 0.2
                    val time = System.currentTimeMillis() * 0.001

                    val height = (amplitude * sin(2 * Math.PI * frequency * time + phase)).toFloat() + 20

                    view.layoutParams.height = height.toInt()
                    view.requestLayout()

                    val alphaFloat = (height / 100).coerceIn(0.3f, 1f)
                    val alphaInt = (alphaFloat * 255).toInt()
                    view.setBackgroundColor(Color.argb(alphaInt, 255, 94, 0))
                }
                delay(50)
            }
        }
    }

    private fun stopVisualizer() {
        visualizerJob?.cancel()
        // Сброс высоты всех баров
        for (i in 0 until visualizer.childCount) {
            val view = visualizer.getChildAt(i)
            view.layoutParams.height = 20
            view.setBackgroundColor(Color.parseColor("#FF5E00"))
        }
    }

    private fun openTelegram() {
        val telegramUrl = "https://t.me/+IKyfzhp_0MQ3NjAy"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(telegramUrl)))
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть Telegram", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSponsorLink() {
        openTelegram()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioStream()
        visualizerJob?.cancel()
        trackUpdateJob?.cancel()
        rotationAnimator?.cancel()
    }

    override fun onPause() {
        super.onPause()
        // Можно добавить паузу при сворачивании приложения
        // if (isPlaying) {
        //     // Останавливаем воспроизведение при сворачивании
        //     stopPlayback()
        // }
    }

    override fun onResume() {
        super.onResume()
        // При возвращении в приложение обновляем данные
        if (!isPlaying) {
            CoroutineScope(IO).launch {
                fetchTrackInfo()
            }
        }
    }
}