package com.example.visualizer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var selectedAudioUri: Uri? = null
    private var selectedImageUri: Uri? = null

    private lateinit var tvGlobalStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollView: ScrollView

    private var autoScrollEnabled = true
    private val logHandler = Handler(Looper.getMainLooper())
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация UI
        tvGlobalStatus = findViewById(R.id.tvGlobalStatus)
        btnStart = findViewById(R.id.btnStart)
        progressBar = findViewById(R.id.progressBar)
        scrollView = findViewById(R.id.scrollView)

        // Инициализация Python
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // Настройка выбора файлов через SAF
        val pickAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                selectedAudioUri = result.data?.data
                logMessage("🎵 Выбрано аудио: ${getFileName(selectedAudioUri)}")
            }
        }

        val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                selectedImageUri = result.data?.data
                logMessage("🖼️ Выбрано изображение: ${getFileName(selectedImageUri)}")
            }
        }

        findViewById<Button>(R.id.btnPickAudio).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            pickAudio.launch(intent)
        }

        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickImage.launch(intent)
        }

        btnStart.setOnClickListener { startProcessing() }

        // Умный скролл: если пользователь отмотал вверх, отключаем автоскролл
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val childHeight = scrollView.getChildAt(0).height
            val isAtBottom = (childHeight - (scrollY + scrollView.height)) < 50
            autoScrollEnabled = isAtBottom
        }
    }

    private fun startProcessing() {
        if (isProcessing) return
        if (selectedAudioUri == null) {
            logMessage("❌ Ошибка: Выберите аудиофайл!")
            return
        }

        isProcessing = true
        btnStart.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvGlobalStatus.text = "" // Очистка консоли

        Thread {
            try {
                processVideo()
            } catch (e: Exception) {
                logMessage("‼️ Критическая ошибка: ${e.message}")
            } finally {
                runOnUiThread {
                    isProcessing = false
                    btnStart.isEnabled = true
                    progressBar.visibility = View.GONE
                    logMessage("🏁 Поток обработки остановлен.")
                }
            }
        }.start()
    }

    private fun processVideo() {
        logMessage("🚀 Запуск конвейера обработки...")

        // 1. Подготовка файлов в кэше
        val audioFile = copyUriToCache(selectedAudioUri!!, "temp_input_audio.mp3") ?: return
        val imageFile = if (selectedImageUri != null) {
            copyUriToCache(selectedImageUri!!, "temp_input_bg.jpg")
        } else null

        val cacheDir = cacheDir.absolutePath
        val wavPath = "$cacheDir/temp_audio.wav"
        val silentVideoPath = "$cacheDir/silent_temp.mp4" // Используем mp4 во избежание лимита 2ГБ
        val finalOutputPath = "/storage/emulated/0/Download/Visualizer_${System.currentTimeMillis()}.mp4"

        // ШАГ 1: Декодирование в WAV для SciPy
        logMessage("🎬 [1/3] Подготовка аудио (FFmpeg)...")
        FFmpegKit.execute("-y -i \"${audioFile.absolutePath}\" -ac 1 -ar 22050 \"$wavPath\"")

        // ШАГ 2: Python Рендеринг (SciPy + OpenCV)
        logMessage("🐍 [2/3] Вызов Python-модуля...")
        val py = Python.getInstance()
        val logger = PythonLogger()
        
        // Перехват stdout/stderr Python
        val sys = py.getModule("sys")
        sys.callAttr("__setattr__", "stdout", logger)
        sys.callAttr("__setattr__", "stderr", logger)

        val visualizer = py.getModule("visualizer")
        try {
            visualizer.callAttr(
                "create_spectrum_video",
                wavPath,
                silentVideoPath,
                imageFile?.absolutePath
            )
            logMessage("✅ Математический рендер кадров Python завершен.")
        } catch (e: Exception) {
            logMessage("🐍 >>> Ошибка логики Python:\n${e.message}")
            return
        }

        // ШАГ 3: Финальная сборка с исправлением нечетных размеров
        logMessage("🎬 [3/3] Сборка финального контейнера (FFmpeg)...")
        val ffmpegMergeCmd = "-y -i \"$silentVideoPath\" -i \"${audioFile.absolutePath}\" " +
                "-vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" " + // Исправление ошибки libx264
                "-c:v libx264 -preset ultrafast -crf 28 -c:a aac -shortest \"$finalOutputPath\""

        val session = FFmpegKit.execute(ffmpegMergeCmd)
        if (ReturnCode.isSuccess(session.returnCode)) {
            logMessage("🎉 УСПЕХ! Видео сохранено в Downloads.")
        } else {
            logMessage("❌ Ошибка сборки контейнера: ${session.allLogsAsString}")
        }
    }

    // Вспомогательный класс для перехвата вывода Python
    @Keep
    inner class PythonLogger : PyObject {
        constructor() : super()

        @Keep
        fun write(data: String) {
            logMessage("🐍 >>> $data", isPython = true)
        }

        @Keep
        fun flush() {}
    }

    private fun logMessage(message: String, isPython: Boolean = false) {
        logHandler.post {
            val cleanMessage = if (isPython) message.replace("\r", "") else "\n[$message]"
            if (cleanMessage.isNotBlank()) {
                tvGlobalStatus.append(cleanMessage)
                if (autoScrollEnabled) {
                    scrollView.postDelayed({ scrollView.fullScroll(View.FOCUS_DOWN) }, 100)
                }
            }
        }
    }

    private fun copyUriToCache(uri: Uri, targetFileName: String): File? {
        return try {
            val destFile = File(cacheDir, targetFileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            logMessage("❌ Ошибка копирования в кэш: ${e.message}")
            null
        }
    }

    private fun getFileName(uri: Uri?): String {
        if (uri == null) return "Не выбрано"
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) name = cursor.getString(nameIndex)
        }
        return name
    }
}
