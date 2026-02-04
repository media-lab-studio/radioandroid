package com.example.myapplication

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    // Конфигурация
    private companion object {
        // Альтернативные радиостанции на случай проблем
        const val STREAM_URL = "https://myradio24.org/25968"
        const val ALT_STREAM_URL1 = "http://stream.radioparadise.com/rock-128"
        const val ALT_STREAM_URL2 = "https://stream.live.vc.bbcmedia.co.uk/bbc_radio_one"

        // Простые тестовые потоки
        const val TEST_STREAM_1 = "https://icecast.rtl.it/radiofreccia" // Итальянское радио
        const val TEST_STREAM_2 = "http://live.leanstream.co/CIOQFMAAC" // Канадское радио
        const val TEST_STREAM_3 = "http://icecast.rtl.it/radiofreccia.aac" // AAC поток

        const val DEFAULT_VOLUME = 70
        const val UPDATE_INTERVAL = 10000L
        const val CONNECTION_TIMEOUT = 15000 // 15 секунд таймаут
    }

    // UI элементы
    private lateinit var recordButton: ImageView
    private lateinit var recordContainer: FrameLayout
    private lateinit var ivStatus: ImageView
    private lateinit var tvCurrentTrack: TextView
    private lateinit var tvNextTrack: TextView
    private lateinit var nextTrackContainer: LinearLayout
    private lateinit var sbVolume: SeekBar
    private lateinit var visualizer: LinearLayout
    private lateinit var btnTelegram: LinearLayout
    private lateinit var btnSponsor: LinearLayout

    // Состояние
    private var isPlaying = false
    private var currentVolume = DEFAULT_VOLUME // ВОССТАНАВЛИВАЕМ эту переменную
    private var rotationAnimator: ObjectAnimator? = null
    private var visualizerJob: Job? = null
    private var trackUpdateJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    // Coroutine scope для управления жизненным циклом
    private val mainScope = MainScope()

    // Тестовые данные треков
    private val testTracks = listOf(
        "Metallica - Enter Sandman",
        "AC/DC - Back in Black",
        "Nirvana - Smells Like Teen Spirit",
        "Led Zeppelin - Stairway to Heaven",
        "Queen - Bohemian Rhapsody"
    )

    private val testNextTracks = listOf(
        "Deep Purple - Smoke on the Water",
        "Iron Maiden - The Trooper",
        "Ozzy Osbourne - Crazy Train",
        "Aerosmith - Dream On",
        "Scorpions - Wind of Change"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d("MainActivity", "Приложение запущено")

        initViews()
        setupClickListeners()
        setupVolumeControl()

        // Устанавливаем начальный текст
        tvCurrentTrack.text = "EternalRock Radio"
        tvNextTrack.text = "Нажмите на пластинку"

        // Проверяем разрешение на интернет в манифесте
        checkInternetPermission()
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
    }

    private fun checkInternetPermission() {
        // Убедитесь, что в AndroidManifest.xml есть:
        // <uses-permission android:name="android.permission.INTERNET" />
        Log.d("MainActivity", "Проверка разрешений: INTERNET должно быть в манифесте")
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
            // Даем пользователю выбор: онлайн радио или локальный файл
            showPlaybackOptions()
        }
    }

    private fun showPlaybackOptions() {
        val options = arrayOf("Онлайн радио", "Локальный файл (тест)", "Отмена")

        AlertDialog.Builder(this)
            .setTitle("Выберите источник аудио")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> startOnlinePlayback() // Онлайн радио
                    1 -> startLocalPlayback()  // Локальный файл
                    // 2 - отмена, ничего не делаем
                }
            }
            .setCancelable(true)
            .show()
    }

    private fun startOnlinePlayback() {
        Log.d("RadioPlayer", "Запуск онлайн воспроизведения")
        isPlaying = true

        // Анимация вращения пластинки
        startRecordRotation()

        // Изменение иконки статуса
        ivStatus.setImageResource(R.drawable.ic_pause)

        // Показ следующего трека
        nextTrackContainer.visibility = View.VISIBLE

        // Запуск визуализатора
        startVisualizer()

        // Обновляем текст
        tvCurrentTrack.text = "Подключение к радио..."
        tvNextTrack.text = "Идет подключение..."

        // Запуск аудиопотока в отдельной корутине
        mainScope.launch {
            startAudioStreamWithRetry()
        }

        // Запуск тестовых обновлений треков
        startTestTrackUpdates()
    }

    private fun startLocalPlayback() {
        Log.d("RadioPlayer", "Запуск локального воспроизведения")
        isPlaying = true

        // Анимация вращения пластинки
        startRecordRotation()

        // Изменение иконки статуса
        ivStatus.setImageResource(R.drawable.ic_pause)

        // Показ следующего трека
        nextTrackContainer.visibility = View.VISIBLE

        // Запуск визуализатора
        startVisualizer()

        // Обновляем текст
        tvCurrentTrack.text = "Подготовка локального файла..."
        tvNextTrack.text = "Тестовый трек"

        // Запуск локального аудиофайла
        testLocalAudio()

        // Запуск тестовых обновлений треков
        startTestTrackUpdates()
    }

    private suspend fun startAudioStreamWithRetry() {
        val urlsToTry = listOf(
            STREAM_URL,
            ALT_STREAM_URL1,
            ALT_STREAM_URL2,
            TEST_STREAM_1,
            TEST_STREAM_2,
            TEST_STREAM_3
        )

        for ((index, url) in urlsToTry.withIndex()) {
            Log.d("RadioPlayer", "Попытка подключения к потоку $index: $url")

            runOnUiThread {
                tvCurrentTrack.text = "Подключение... Попытка ${index + 1}/6"
            }

            try {
                // Пытаемся подключиться напрямую
                startAudioStream(url)
                return // Успешно подключились, выходим
            } catch (e: Exception) {
                Log.e("RadioPlayer", "Ошибка при попытке $index: ${e.message}")
                if (index == urlsToTry.lastIndex) {
                    // Последняя попытка не удалась
                    runOnUiThread {
                        showConnectionError()
                    }
                }
                delay(1000) // Задержка перед следующей попыткой
            }
        }
    }

    private fun startAudioStream(url: String) {
        runOnUiThread {
            try {
                Log.d("RadioPlayer", "Создание MediaPlayer для URL: $url")

                // Освобождаем предыдущий MediaPlayer
                mediaPlayer?.release()

                mediaPlayer = MediaPlayer().apply {
                    // Настройка аудио атрибутов
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )

                    // Устанавливаем источник данных
                    setDataSource(url)

                    // Обработчик ошибок
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

                        // Возвращаем true, чтобы указать что ошибка обработана
                        true
                    }

                    // Обработчик подготовки
                    setOnPreparedListener { mp ->
                        Log.d("RadioPlayer", "Аудио подготовлено, запускаем воспроизведение...")
                        mp.start()
                        mp.setVolume(currentVolume / 100f, currentVolume / 100f)
                        runOnUiThread {
                            tvCurrentTrack.text = "Радио запущено!"
                            Toast.makeText(
                                this@MainActivity,
                                "Подключено к радио",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    // Готовим асинхронно
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
                // Пробрасываем исключение дальше для retry
                throw e
            }
        }
    }

    private fun showConnectionError() {
        runOnUiThread {
            tvCurrentTrack.text = "Не удалось подключиться к радио"
            Toast.makeText(
                this@MainActivity,
                "Проверьте подключение к интернету и попробуйте снова",
                Toast.LENGTH_LONG
            ).show()
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        Log.d("RadioPlayer", "Остановка воспроизведения")
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
        tvCurrentTrack.text = "Радио выключено. Нажмите на пластинку"

        // Остановка аудио
        stopAudioStream()

        // Остановка обновления треков
        stopTrackUpdates()
    }

    private fun stopAudioStream() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
                Log.d("RadioPlayer", "MediaPlayer остановлен и освобожден")
            } catch (e: Exception) {
                Log.e("RadioPlayer", "Ошибка при остановке MediaPlayer", e)
            }
        }
        mediaPlayer = null
    }

    private fun startTestTrackUpdates() {
        trackUpdateJob?.cancel()

        trackUpdateJob = mainScope.launch {
            var trackIndex = 0

            while (isActive && isPlaying) {
                // Обновляем текущий трек
                val currentTrack = testTracks[trackIndex % testTracks.size]
                val nextTrack = testNextTracks[trackIndex % testNextTracks.size]

                runOnUiThread {
                    tvCurrentTrack.text = currentTrack
                    tvNextTrack.text = "Далее: $nextTrack"

                    // Анимация обновления
                    tvCurrentTrack.alpha = 0f
                    tvCurrentTrack.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .start()
                }

                trackIndex++
                delay(UPDATE_INTERVAL)
            }
        }
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

        // Сброс визуализатора
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
        // Останавливаем воспроизведение при сворачивании приложения
        if (isPlaying) {
            stopPlayback()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel() // Отменяем все корутины
        stopAudioStream()
        rotationAnimator?.cancel()
        Log.d("MainActivity", "Приложение закрыто")
    }

    private fun testLocalAudio() {
        runOnUiThread {
            try {
                Log.d("RadioPlayer", "Тестирование локального аудио файла")

                // Освобождаем предыдущий MediaPlayer
                mediaPlayer?.release()

                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )

                    // Загружаем файл из папки assets
                    val assetManager = this@MainActivity.assets
                    val assetFileDescriptor = assetManager.openFd("test.mp3")

                    setDataSource(
                        assetFileDescriptor.fileDescriptor,
                        assetFileDescriptor.startOffset,
                        assetFileDescriptor.length
                    )

                    assetFileDescriptor.close()

                    setOnPreparedListener { mp ->
                        Log.d("RadioPlayer", "Локальный файл подготовлен, запуск...")
                        mp.start()
                        mp.setVolume(currentVolume / 100f, currentVolume / 100f)
                        runOnUiThread {
                            tvCurrentTrack.text = "Тестовое аудио запущено!"
                            Toast.makeText(
                                this@MainActivity,
                                "Воспроизводится локальный MP3 файл",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    setOnErrorListener { mp, what, extra ->
                        Log.e("RadioPlayer", "Ошибка воспроизведения локального файла: $what, $extra")
                        runOnUiThread {
                            tvCurrentTrack.text = "Ошибка: $what, $extra"
                            Toast.makeText(
                                this@MainActivity,
                                "Не удалось воспроизвести тестовый файл",
                                Toast.LENGTH_LONG
                            ).show()
                            stopPlayback()
                        }
                        true
                    }

                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e("RadioPlayer", "Ошибка загрузки локального файла", e)
                runOnUiThread {
                    tvCurrentTrack.text = "Ошибка: ${e.localizedMessage}"
                    Toast.makeText(
                        this@MainActivity,
                        "Ошибка: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                    stopPlayback()
                }
            }
        }
    }
}