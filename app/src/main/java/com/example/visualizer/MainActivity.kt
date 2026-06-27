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
	
	private fun copyUriToCache(uri: Uri, targetFileName: String): File? {
    return try {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val outputFile = File(cacheDir, targetFileName)
        inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        outputFile
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

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

    // ... верхние импорты и методы onCreate остаются без изменений ...

    private fun processVideo() {
        btnStart.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvGlobalStatus.text = "Шаг 1 из 3: Декодирование аудио (FFmpeg)..."

        val inputAudioPath = selectedAudioFile!!.absolutePath
        // Временный аудиофайл, который без проблем прочитает SciPy
        val wavAudioPath = File(cacheDir, "temp_mono.wav").absolutePath
        val silentVideoPath = File(cacheDir, "silent_temp.mp4").absolutePath
        val finalVideoPath = File(getExternalFilesDir(null), "Спектр_${System.currentTimeMillis()}.mp4").absolutePath
        val bgPath = selectedBgFile?.absolutePath ?: ""

        Thread {
            try {
                // ЭТАП 1: Быстрое нативное пережатие любого формата в чистый WAV Mono 22kHz
                val ffmpegPreCmd = "-y -i \"$inputAudioPath\" -ac 1 -ar 22050 \"$wavAudioPath\""
                val preSession = com.arthenica.ffmpegkit.FFmpegKit.execute(ffmpegPreCmd)
                
                if (!preSession.returnCode.isValueSuccess) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        btnStart.isEnabled = true
                        tvGlobalStatus.text = "Ошибка оптимизации аудио для Python."
                    }
                    return@Thread
                }

                // ЭТАП 2: Передача WAV-файла в Python для создания анимации
                runOnUiThread {
                    tvGlobalStatus.text = "Шаг 2 из 3: Математический анализ и рендеринг (SciPy)..."
                }
                val py = Python.getInstance()
                val pyModule = py.getModule("visualizer")
                
                val isPythonSuccess = pyModule.callAttr(
                    "generate_silent_spectrum",
                    wavAudioPath, bgPath, silentVideoPath
                ).toBoolean()

                if (isPythonSuccess) {
                    // ЭТАП 3: Финальный монтаж (Берем видеоряд из Python и накладываем ОРИГИНАЛЬНЫЙ трек)
                    runOnUiThread {
                        tvGlobalStatus.text = "Шаг 3 из 3: Финальное сведение потоков (FFmpeg)..."
                    }

                    val ffmpegMergeCmd = "-y -i \"$silentVideoPath\" -i \"$inputAudioPath\" -c:v libx264 -crf 23 -pix_fmt yuv420p -c:a aac -shortest \"$finalVideoPath\""
                    
                    com.arthenica.ffmpegkit.FFmpegKit.executeAsync(ffmpegMergeCmd) { session ->
                        val returnCode = session.returnCode
                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            btnStart.isEnabled = true
                            
                            // Заметаем следы (чистим тяжелый кэш)
                            File(wavAudioPath).delete()
                            File(silentVideoPath).delete()
                            
                            if (returnCode.isValueSuccess) {
                                tvGlobalStatus.text = "Готово! Видеоролик сохранен:\n$finalVideoPath"
                                Toast.makeText(this, "Видео успешно сгенерировано!", Toast.LENGTH_LONG).show()
                            } else {
                                tvGlobalStatus.text = "Сбой финального сведения звука и видео."
                            }
                        }
                    }
                } else {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        btnStart.isEnabled = true
                        tvGlobalStatus.text = "Сбой алгоритма SciPy при генерации кадров."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnStart.isEnabled = true
                    tvGlobalStatus.text = "Критическая ошибка: ${e.message}"
                }
            }
        }.start()
    }

// ... остальной код класса ниже (проверка разрешений) остается прежним ...

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
