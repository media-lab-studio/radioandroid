package com.example.myapplication

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private companion object {
        const val STREAM_URL = "https://myradio24.org/25968"
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
    private lateinit var nextTrackContainer: LinearLayout
    private lateinit var playlistContainer: LinearLayout
    private lateinit var sbVolume: SeekBar
    private lateinit var visualizer: LinearLayout
    private lateinit var btnTelegram: LinearLayout
    private lateinit var btnSponsor: LinearLayout

    private var isPlaying = false
    private var currentVolume = DEFAULT_VOLUME
    private var rotationAnimator: ObjectAnimator? = null
    private var visualizerJob: Job? = null
    private var trackUpdateJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    private val mainScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d("MainActivity", "Приложение запущено")

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
//        nextTrackContainer = findViewById(R.id.nextTrackContainer)
//        playlistContainer = findViewById(R.id.playlistContainer)
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
        if (isPlaying) {
            stopPlayback()
        } else {
            startOnlinePlayback()
        }
    }

    private fun startOnlinePlayback() {
        Log.d("RadioPlayer", "Запуск онлайн воспроизведения")
        isPlaying = true

        startRecordRotation()
        ivStatus.setImageResource(R.drawable.ic_pause)
        startVisualizer()  // УДАЛИТЬ: nextTrackContainer.visibility = View.VISIBLE
        // УДАЛИТЬ: playlistContainer.visibility = View.VISIBLE

        tvCurrentTrack.text = "Подключение к радио..."
        tvNextTrack.text = "Загрузка данных..."
        tvPlaylist.text = "Загрузка..."

        mainScope.launch {
            startAudioStreamWithRetry()
        }

        startRealTrackUpdates()
    }

    private suspend fun fetchRadioStatus(): RadioStatus? {
        return try {
            withContext(IO) {
                val url = URL(RADIO_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "EternalRockRadio/1.0")

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val inputStream = connection.inputStream
                    val bufferedReader = BufferedReader(InputStreamReader(inputStream))
                    val stringBuilder = StringBuilder()
                    var line: String?

                    while (bufferedReader.readLine().also { line = it } != null) {
                        stringBuilder.append(line)
                    }

                    bufferedReader.close()
                    inputStream.close()
                    connection.disconnect()

                    val jsonString = stringBuilder.toString()
                    Log.d("RadioPlayer", "Получен JSON: $jsonString")

                    parseRadioStatus(jsonString)
                } else {
                    Log.e("RadioPlayer", "Ошибка HTTP: $responseCode")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("RadioPlayer", "Ошибка получения статуса: ${e.message}")
            null
        }
    }

    private fun parseRadioStatus(jsonString: String): RadioStatus? {
        return try {
            val json = JSONObject(jsonString)

            // Получаем текущий трек
            val artist = json.optString("artist", "")
            val song = json.optString("song", "")
            val currentTrack = if (artist.isNotEmpty() && song.isNotEmpty()) {
                "$artist - $song"
            } else {
                json.optString("title", "Неизвестный трек")
            }

            // Получаем следующий трек
            val nextSongsArray = json.optJSONArray("nextsongs")
            val nextTrack = if (nextSongsArray != null && nextSongsArray.length() > 0) {
                val firstNextSong = nextSongsArray.getJSONObject(0)
                firstNextSong.optString("song", "Нет данных")
            } else {
                "Нет данных"
            }

            // Получаем плейлист и форматируем его название
            val playlist = json.optString("playlist", "Неизвестный плейлист")
            val formattedPlaylist = formatPlaylistName(playlist)

            // Получаем битрейт
            val kbps = json.optInt("kbps", 128)
            val bitrate = "$kbps kbps MP3"

            RadioStatus(currentTrack, nextTrack, formattedPlaylist, bitrate)
        } catch (e: Exception) {
            Log.e("RadioPlayer", "Ошибка парсинга JSON: ${e.message}")
            null
        }
    }

    private fun formatPlaylistName(playlist: String): String {
        return try {
            // Удаляем все после последнего подчеркивания (включая само подчеркивание)
            val lastUnderscoreIndex = playlist.lastIndexOf('_')
            if (lastUnderscoreIndex != -1) {
                // Берем часть строки до последнего подчеркивания
                val baseName = playlist.substring(0, lastUnderscoreIndex)
                // Заменяем оставшиеся подчеркивания на пробелы
                baseName.replace('_', ' ')
            } else {
                // Если нет подчеркиваний, просто заменяем их на пробелы
                playlist.replace('_', ' ')
            }
        } catch (e: Exception) {
            Log.e("RadioPlayer", "Ошибка форматирования плейлиста: ${e.message}")
            playlist // Возвращаем оригинал в случае ошибки
        }
    }

    data class RadioStatus(
        val currentTrack: String,
        val nextTrack: String,
        val playlist: String,
        val bitrate: String
    )

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

    private fun updateUI(status: RadioStatus) {
        runOnUiThread {
            tvCurrentTrack.text = status.currentTrack
            tvNextTrack.text = status.nextTrack
            tvPlaylist.text = status.playlist

            tvCurrentTrack.alpha = 0f
            tvCurrentTrack.animate()
                .alpha(1f)
                .setDuration(500)
                .start()
        }
    }

    private suspend fun startAudioStreamWithRetry() {
        runOnUiThread {
            tvCurrentTrack.text = "Подключение к потоку..."
        }

        try {
            startAudioStream(STREAM_URL)
        } catch (e: Exception) {
            Log.e("RadioPlayer", "Ошибка подключения: ${e.message}")
            runOnUiThread {
                showConnectionError()
            }
        }
    }

    private fun startAudioStream(url: String) {
        runOnUiThread {
            try {
                Log.d("RadioPlayer", "Создание MediaPlayer для URL: $url")

                mediaPlayer?.release()

                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )

                    setDataSource(url)

                    setOnErrorListener { mp, what, extra ->
                        Log.e("RadioPlayer", "Ошибка MediaPlayer: what=$what, extra=$extra")
                        runOnUiThread {
                            tvCurrentTrack.text = "Ошибка подключения"
                            Toast.makeText(
                                this@MainActivity,
                                "Не удалось подключиться к радио",
                                Toast.LENGTH_SHORT
                            ).show()
                            stopPlayback()
                        }
                        true
                    }

                    setOnPreparedListener { mp ->
                        Log.d("RadioPlayer", "Аудио подготовлено, запускаем воспроизведение...")
                        mp.start()
                        mp.setVolume(currentVolume / 100f, currentVolume / 100f)
                        runOnUiThread {
                            tvCurrentTrack.text = "Радио запущено!"
                            Toast.makeText(
                                this@MainActivity,
                                "Подключено к EternalRock Radio",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    prepareAsync()
                    Log.d("RadioPlayer", "Подготовка MediaPlayer запущена")
                }
            } catch (e: Exception) {
                Log.e("RadioPlayer", "Критическая ошибка при создании MediaPlayer", e)
                runOnUiThread {
                    tvCurrentTrack.text = "Ошибка: ${e.localizedMessage}"
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось создать плеер: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                    stopPlayback()
                }
                throw e
            }
        }
    }

    private fun showConnectionError() {
        runOnUiThread {
            tvCurrentTrack.text = "Не удалось подключиться к радио"
            Toast.makeText(
                this@MainActivity,
                "Проверьте подключение к интернету",
                Toast.LENGTH_LONG
            ).show()
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        Log.d("RadioPlayer", "Остановка воспроизведения")
        isPlaying = false

        stopRecordRotation()
        ivStatus.setImageResource(R.drawable.ic_play)
        stopVisualizer()   // УДАЛИТЬ: nextTrackContainer.visibility = View.GONE
        // УДАЛИТЬ: playlistContainer.visibility = View.GONE

        tvCurrentTrack.text = "Радио выключено. Нажмите на пластинку"
        tvNextTrack.text = "Нажмите на пластинку"
        tvPlaylist.text = "Ожидание данных..."

        stopAudioStream()
        stopTrackUpdates()
    }

    private fun stopAudioStream() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
                Log.d("RadioPlayer", "MediaPlayer остановлен")
            } catch (e: Exception) {
                Log.e("RadioPlayer", "Ошибка при остановке MediaPlayer", e)
            }
        }
        mediaPlayer = null
    }

    private fun stopTrackUpdates() {
        trackUpdateJob?.cancel()
        trackUpdateJob = null
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
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun startVisualizer() {
        visualizerJob?.cancel()
        visualizerJob = mainScope.launch {
            val bars = mutableListOf<View>()
            for (i in 0 until visualizer.childCount) {
                bars.add(visualizer.getChildAt(i) as View)
            }

            while (isActive && isPlaying) {
                bars.forEachIndexed { index, view ->
                    val time = System.currentTimeMillis() * 0.001
                    val height = (sin(time * 2 + index * 0.3) * 25 + 35).toFloat()

                    view.layoutParams.height = height.toInt()
                    view.requestLayout()

                    val alphaFloat = (height / 100).coerceIn(0.3f, 0.8f)
                    val alphaInt = (alphaFloat * 255).toInt()
                    view.setBackgroundColor(Color.argb(alphaInt, 255, 94, 0))
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

    override fun onPause() {
        super.onPause()
        if (isPlaying) {
            stopPlayback()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        stopAudioStream()
        rotationAnimator?.cancel()
        Log.d("MainActivity", "Приложение закрыто")
    }
}