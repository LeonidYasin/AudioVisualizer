package com.example.visualizer

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
// ИСПРАВЛЕНО: Официальный пакет импорта для FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKit
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvAudioStatus: TextView
    private lateinit var tvBackgroundStatus: TextView
    private lateinit var tvGlobalStatus: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnStart: Button

    private var selectedAudioFile: File? = null
    private var selectedBgFile: File? = null

    // Регистрируем лаунчеры для безопасного выбора медиафайлов через SAF
    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val file = copyUriToCache(it, "temp_input_audio.mp3")
            if (file != null) {
                selectedAudioFile = file
                tvAudioStatus.text = "Аудио успешно загружено"
            } else {
                Toast.makeText(this, "Ошибка чтения аудио", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickBgLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val file = copyUriToCache(it, "temp_input_bg.jpg")
            if (file != null) {
                selectedBgFile = file
                tvBackgroundStatus.text = "Фоновое изображение загружено"
            } else {
                Toast.makeText(this, "Ошибка чтения изображения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация UI компонентов
        tvAudioStatus = findViewById(R.id.tvAudioStatus)
        tvBackgroundStatus = findViewById(R.id.tvBackgroundStatus)
        tvGlobalStatus = findViewById(R.id.tvGlobalStatus)
        progressBar = findViewById(R.id.progressBar)
        btnStart = findViewById(R.id.btnStart)

        val btnSelectAudio = findViewById<Button>(R.id.btnSelectAudio)
        val btnSelectBackground = findViewById<Button>(R.id.btnSelectBackground)

        // Инициализация Python среды
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        btnSelectAudio.setOnClickListener { pickAudioLauncher.launch("audio/*") }
        btnSelectBackground.setOnClickListener { pickBgLauncher.launch("image/*") }

        btnStart.setOnClickListener {
            if (selectedAudioFile == null) {
                Toast.makeText(this, "Сначала выберите аудиофайл!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            processVideo()
        }
    }

    private fun processVideo() {
        // Переключаем элементы интерфейса в режим загрузки
        btnStart.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvGlobalStatus.text = "Шаг 1 из 2: Генерация видеоряда (Python)..."

        val silentVideoPath = File(cacheDir, "silent_temp.mp4").absolutePath
        val finalVideoPath = File(getExternalFilesDir(null), "Спектр_${System.currentTimeMillis()}.mp4").absolutePath
        val bgPath = selectedBgFile?.absolutePath ?: ""

        Thread {
            try {
                val py = Python.getInstance()
                val pyModule = py.getModule("visualizer")
                
                // Вызов метода питона для отрисовки фреймов
                val isPythonSuccess = pyModule.callAttr(
                    "generate_silent_spectrum",
                    selectedAudioFile!!.absolutePath, bgPath, silentVideoPath
                ).toBoolean()

                if (isPythonSuccess) {
                    runOnUiThread {
                        tvGlobalStatus.text = "Шаг 2 из 2: Сведение потоков мультимедиа (FFmpeg)..."
                    }

                    // Наложение звука на видеоряд высокого качества yuv420p
                    val ffmpegCmd = "-y -i \"$silentVideoPath\" -i \"${selectedAudioFile!!.absolutePath}\" -c:v libx264 -crf 23 -pix_fmt yuv420p -c:a aac -shortest \"$finalVideoPath\""
                    
                    FFmpegKit.executeAsync(ffmpegCmd) { session ->
                        val returnCode = session.returnCode
                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            btnStart.isEnabled = true
                            if (returnCode.isValueSuccess) {
                                tvGlobalStatus.text = "Успех! Файл сохранен в:\n$finalVideoPath"
                                Toast.makeText(this, "Видео создано!", Toast.LENGTH_LONG).show()
                            } else {
                                tvGlobalStatus.text = "Сбой компиляции FFmpeg."
                            }
                        }
                    }
                } else {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        btnStart.isEnabled = true
                        tvGlobalStatus.text = "Ошибка синтаксического анализа аудио в Python."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnStart.isEnabled = true
                    tvGlobalStatus.text = "Критический сбой: ${e.message}"
                }
            }
        }.start()
    }

    // Вспомогательная функция для копирования контента URI во внутренний кэш
    private fun copyUriToCache(uri: Uri, targetFileName: String): File? {
        return try {
            val file = File(cacheDir, targetFileName)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
