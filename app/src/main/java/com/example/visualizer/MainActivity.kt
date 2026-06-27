package com.example.visualizer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.PyException
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var tvAudioStatus: TextView
    private lateinit var tvBackgroundStatus: TextView
    private lateinit var tvGlobalStatus: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnStart: Button

    private var selectedAudioFile: File? = null
    private var selectedBgFile: File? = null

    // Лаунчер для безопасного выбора аудио через системный проводник (доступен Downloads)
    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            logMessage("⏳ Обработка выбранного аудио-трека...")
            val file = copyUriToCache(it, "temp_input_audio.mp3")
            if (file != null) {
                selectedAudioFile = file
                val fileSizeMb = String.format(Locale.US, "%.2f", file.length().toDouble() / (1024 * 1024))
                tvAudioStatus.text = "Аудио: ${file.name} ($fileSizeMb МБ)"
                logMessage("✅ Аудио успешно импортировано во внутренний кэш.\nПуть: ${file.absolutePath}")
            } else {
                logMessage("❌ Ошибка: Не удалось скопировать аудиофайл в кэш приложения.")
                Toast.makeText(this, "Ошибка чтения аудио", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Лаунчер для безопасного выбора картинок через системный проводник
    private val pickBgLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            logMessage("⏳ Обработка выбранного изображения...")
            val file = copyUriToCache(it, "temp_input_bg.jpg")
            if (file != null) {
                selectedBgFile = file
                val fileSizeMb = String.format(Locale.US, "%.2f", file.length().toDouble() / (1024 * 1024))
                tvBackgroundStatus.text = "Фон: ${file.name} ($fileSizeMb МБ)"
                logMessage("✅ Фоновое изображение импортировано во внутренний кэш.\nПуть: ${file.absolutePath}")
            } else {
                logMessage("❌ Ошибка: Не удалось скопировать изображение в кэш приложения.")
                Toast.makeText(this, "Ошибка чтения изображения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация UI
        tvAudioStatus = findViewById(R.id.tvAudioStatus)
        tvBackgroundStatus = findViewById(R.id.tvBackgroundStatus)
        tvGlobalStatus = findViewById(R.id.tvGlobalStatus)
        progressBar = findViewById(R.id.progressBar)
        btnStart = findViewById(R.id.btnStart)

        val btnSelectAudio = findViewById<Button>(R.id.btnSelectAudio)
        val btnSelectBackground = findViewById<Button>(R.id.btnSelectBackground)

        // Делаем текстовое поле логов выделяемым — теперь его можно копировать!
        tvGlobalStatus.setTextIsSelectable(true)
        
        logMessage("📱 Приложение запущено. Ожидание выбора файлов...")

        // Инициализация Python
        logMessage("⏳ Инициализация окружения Python (Chaquopy)...")
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        logMessage("✅ Python успешно инициализирован.")

        // Принудительный пре-импорт модулей для защиты от конфликтов C++ линкера
        try {
            logMessage("⏳ Прогрев C++ библиотек (OpenCV/SciPy) в пространстве имен...")
            val py = Python.getInstance()
            py.getModule("visualizer")
            logMessage("System: Нативный рантайм Chaquopy синхронизирован.")
        } catch (e: Exception) {
            logMessage("⚠️ Предупреждение при прогреве: ${e.message}")
        }

        btnSelectAudio.setOnClickListener { pickAudioLauncher.launch("audio/*") }
        btnSelectBackground.setOnClickListener { pickBgLauncher.launch("image/*") }

        btnStart.setOnClickListener {
            if (selectedAudioFile == null) {
                logMessage("❌ Ошибка: Невозможно начать рендеринг. Не выбран аудиофайл.")
                Toast.makeText(this, "Сначала выберите аудиофайл!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            processVideo()
        }
    }

    private fun processVideo() {
        btnStart.isEnabled = false
        progressBar.visibility = View.VISIBLE
        
        logMessage("\n🚀 =========================================")
        logMessage("🚀 ЗАПУСК КОНВЕРТАЦИИ")
        logMessage("🚀 =========================================")

        val inputAudioPath = selectedAudioFile!!.absolutePath
        val wavAudioPath = File(cacheDir, "temp_mono.wav").absolutePath
        val silentVideoPath = File(cacheDir, "silent_temp.mp4").absolutePath
        val finalVideoPath = File(getExternalFilesDir(null), "Спектр_${System.currentTimeMillis()}.mp4").absolutePath
        val bgPath = selectedBgFile?.absolutePath ?: ""

        logMessage("📋 Конфигурация путей:")
        logMessage(" - Исходный звук: $inputAudioPath")
        logMessage(" - Исходный фон: ${if(bgPath.isEmpty()) "Используется стандартный (черный экран)" else bgPath}")
        logMessage(" - Промежуточный аудио-кэш: $wavAudioPath")
        logMessage(" - Промежуточный видео-кэш: $silentVideoPath")
        logMessage(" - Выходной медиафайл: $finalVideoPath")

        Thread {
            try {
                // ЭТАП 1: Нативное декодирование аудио через FFmpeg
                runOnUiThread { logMessage("\n🎬 [Шаг 1 из 3] Запуск FFmpeg для декодирования аудио в WAV...") }
                
                val ffmpegPreCmd = "-y -i \"$inputAudioPath\" -ac 1 -ar 22050 \"$wavAudioPath\""
                logVerbose("FFmpeg Command: $ffmpegPreCmd")
                
                val preSession = com.arthenica.ffmpegkit.FFmpegKit.execute(ffmpegPreCmd)
                
                if (!preSession.returnCode.isValueSuccess) {
                    val ffLogs = preSession.allLogsAsString
                    runOnUiThread {
                        logMessage("❌ СБОЙ на Шаге 1: FFmpeg не смог обработать аудио.")
                        logMessage("📋 Лог ошибки FFmpeg:\n$ffLogs")
                        endProcess()
                    }
                    return@Thread
                }
                
                val wavFile = File(wavAudioPath)
                runOnUiThread { 
                    logMessage("✅ Шаг 1 завершен. Создан оптимизированный WAV файл (${wavFile.length() / 1024} КБ).") 
                }

                // ЭТАП 2: Передача данных в Python алгоритм SciPy + OpenCV
                runOnUiThread { logMessage("\n🐍 [Шаг 2 из 3] Передача управления в Python (SciPy/OpenCV)...") }
                
                val py = Python.getInstance()
                val pyModule = py.getModule("visualizer")
                
                runOnUiThread { logMessage("⏳ Вызов функции 'generate_silent_spectrum'. Идет рендеринг кадров...") }
                
                val isPythonSuccess = pyModule.callAttr(
                    "generate_silent_spectrum",
                    wavAudioPath, bgPath, silentVideoPath
                ).toBoolean()

                if (isPythonSuccess) {
                    runOnUiThread { logMessage("✅ Шаг 2 завершен. Видеопоток без звука успешно сгенерирован.") }

                    // ЭТАП 3: Финальный монтаж звука и видео
                    runOnUiThread { logMessage("\n🎬 [Шаг 3 из 3] Финальное сведение потоков (Мультиплексирование)...") }

                    val ffmpegMergeCmd = "-y -i \"$silentVideoPath\" -i \"$inputAudioPath\" -c:v libx264 -crf 23 -pix_fmt yuv420p -c:a aac -shortest \"$finalVideoPath\""
                    logVerbose("FFmpeg Command: $ffmpegMergeCmd")
                    
                    com.arthenica.ffmpegkit.FFmpegKit.executeAsync(ffmpegMergeCmd) { session ->
                        val returnCode = session.returnCode
                        runOnUiThread {
                            // Очищаем тяжелые временные файлы из кэша
                            File(wavAudioPath).delete()
                            File(silentVideoPath).delete()
                            logMessage("🧹 Временные файлы кэша удалены.")
                            
                            if (returnCode.isValueSuccess) {
                                logMessage("\n🎉🎉🎉 УСПЕХ! СБОРКА ЗАВЕРШЕНА 🎉🎉🎉")
                                logMessage("💾 Итоговый видеоролик сохранен по пути:\n$finalVideoPath")
                                Toast.makeText(this, "Видео успешно сгенерировано!", Toast.LENGTH_LONG).show()
                            } else {
                                logMessage("❌ СБОЙ на Шаге 3: Не удалось объединить аудио и видео дорожки.")
                                logMessage("📋 Лог ошибки FFmpeg:\n${session.allLogsAsString}")
                            }
                            endProcess()
                        }
                    }
                } else {
                    runOnUiThread {
                        logMessage("❌ СБОЙ на Шаге 2: Python-скрипт вернул False. Отрендерить видео не удалось.")
                        endProcess()
                    }
                }
            } catch (e: PyException) {
                // Перехват детальной ошибки из Python (Traceback)
                runOnUiThread {
                    logMessage("\n💥 КРИТИЧЕСКАЯ ОШИБКА В PYTHON КОДЕ (Traceback):")
                    logMessage(e.message ?: "Неизвестная ошибка Python.")
                    endProcess()
                }
            } catch (e: Exception) {
                // Перехват общих системных ошибок Android/Kotlin
                runOnUiThread {
                    logMessage("\n💥 СИСТЕМНАЯ ОШИБКА ПРИЛОЖЕНИЯ:")
                    logMessage(Log.getStackTraceString(e))
                    endProcess()
                }
            }
        }.start()
    }

    // Вспомогательная функция вывода логов на экран устройства
    private fun logMessage(message: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMessage = "[$timeStamp] $message\n"
        tvGlobalStatus.append(formattedMessage)
        Log.d("VisualizerLog", message)
    }

    private fun logVerbose(message: String) {
        Log.d("VisualizerLogVerbose", message)
    }

    private fun endProcess() {
        btnStart.isEnabled = true
        progressBar.visibility = View.GONE
        logMessage("🏁 Процесс остановлен.")
    }

    // Единственная, очищенная функция копирования URI в кэш
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