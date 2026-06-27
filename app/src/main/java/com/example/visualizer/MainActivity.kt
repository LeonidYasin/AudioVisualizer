package com.example.visualizer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep // ИСПРАВЛЕНО: Добавлен импорт для аннотации Keep
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

    private lateinit var mainScrollView: ScrollView
    private lateinit var tvAudioStatus: TextView
    private lateinit var tvBackgroundStatus: TextView
    private lateinit var tvGlobalStatus: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnStart: Button

    private var selectedAudioFile: File? = null
    private var selectedBgFile: File? = null

    // Класс перехвата стандартного вывода (print) из Python среды
    class PythonLogger(private val activity: MainActivity) {
        private val buffer = StringBuilder()

        @Keep // ИСПРАВЛЕНО: Аннотация теперь с большой буквы
        fun write(text: String) {
            buffer.append(text)
            val lines = buffer.toString().split("\n")
            if (lines.size > 1) {
                for (i in 0 until lines.size - 1) {
                    val line = lines[i]
                    if (line.trim().isNotEmpty()) {
                        activity.runOnUiThread {
                            activity.appendPythonLog(line)
                        }
                    }
                }
                buffer.setLength(0)
                buffer.append(lines.last())
            }
        }
        
        @Keep // ИСПРАВЛЕНО: Аннотация теперь с большой буквы
        fun flush() {}
    }

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val originalName = getFileNameFromUri(uri)
                logMessage("⏳ Импорт аудио: $originalName")
                
                val file = copyUriToCache(uri, "temp_input_audio.mp3")
                if (file != null) {
                    selectedAudioFile = file
                    val fileSizeMb = String.format(Locale.US, "%.2f", file.length().toDouble() / (1024 * 1024))
                    tvAudioStatus.text = "$originalName ($fileSizeMb МБ)"
                    logMessage("✅ Аудио подготовлено.")
                } else {
                    logMessage("❌ Ошибка: Не удалось прочитать аудиофайл.")
                }
            }
        }
    }

    private val pickBgLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val originalName = getFileNameFromUri(uri)
                logMessage("⏳ Импорт фона: $originalName")
                
                val file = copyUriToCache(uri, "temp_input_bg.jpg")
                if (file != null) {
                    selectedBgFile = file
                    val fileSizeMb = String.format(Locale.US, "%.2f", file.length().toDouble() / (1024 * 1024))
                    tvBackgroundStatus.text = "$originalName ($fileSizeMb МБ)"
                    logMessage("✅ Изображение привязано.")
                } else {
                    logMessage("❌ Ошибка: Не удалось прочитать изображение.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainScrollView = findViewById(R.id.mainScrollView)
        tvAudioStatus = findViewById(R.id.tvAudioStatus)
        tvBackgroundStatus = findViewById(R.id.tvBackgroundStatus)
        tvGlobalStatus = findViewById(R.id.tvGlobalStatus)
        progressBar = findViewById(R.id.progressBar)
        btnStart = findViewById(R.id.btnStart)

        val btnSelectAudio = findViewById<Button>(R.id.btnSelectAudio)
        val btnSelectBackground = findViewById<Button>(R.id.btnSelectBackground)

        tvGlobalStatus.setTextIsSelectable(true)
        logMessage("📱 Студия визуализации запущена.")

        // Запуск Python и перенаправление потоков вывода
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        try {
            val py = Python.getInstance()
            val sys = py.getModule("sys")
            val logger = PythonLogger(this)
            
            // ИСПРАВЛЕНО: Вызываем __setattr__ через callAttr для динамического проксирования Java-объекта в Python
            sys.callAttr("__setattr__", "stdout", logger)
            sys.callAttr("__setattr__", "stderr", logger)
            logMessage("⚙️ Системный лог Python успешно подключен.")
            
            py.getModule("visualizer")
        } catch (e: Exception) {
            logMessage("⚠️ Ошибка инициализации скриптов: ${e.message}")
        }

        btnSelectAudio.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/x-wav", "audio/wav", "audio/mp3", "audio/x-m4a", "audio/m4a"))
            }
            pickAudioLauncher.launch(intent)
        }

        btnSelectBackground.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickBgLauncher.launch(intent)
        }

        btnStart.setOnClickListener {
            if (selectedAudioFile == null) {
                logMessage("❌ Ошибка: Не выбран аудиофайл.")
                return@setOnClickListener
            }
            processVideo()
        }
    }

    private fun processVideo() {
        btnStart.isEnabled = false
        progressBar.visibility = View.VISIBLE
        
        logMessage("\n🚀 =========================================")
        logMessage("🚀 ЗАПУСК СГЛАЖИВАНИЯ И МЕДИАРЕНДЕРА")
        logMessage("🚀 =========================================")

        val inputAudioPath = selectedAudioFile!!.absolutePath
        val wavAudioPath = File(cacheDir, "temp_mono.wav").absolutePath
        val silentVideoPath = File(cacheDir, "silent_temp.mp4").absolutePath
        val finalVideoPath = File(getExternalFilesDir(null), "Спектр_${System.currentTimeMillis()}.mp4").absolutePath
        val bgPath = selectedBgFile?.absolutePath ?: ""

        Thread {
            try {
                runOnUiThread { logMessage("🎬 [1/3] Пережатие аудио в рабочий WAV Mono через FFmpeg...") }
                val ffmpegPreCmd = "-y -i \"$inputAudioPath\" -ac 1 -ar 22050 \"$wavAudioPath\""
                val preSession = com.arthenica.ffmpegkit.FFmpegKit.execute(ffmpegPreCmd)
                
                if (!preSession.returnCode.isValueSuccess) {
                    runOnUiThread {
                        logMessage("❌ Сбой FFmpeg декодера:\n${preSession.allLogsAsString}")
                        endProcess()
                    }
                    return@Thread
                }
                
                runOnUiThread { logMessage("✅ Аудио подготовлено. Передача управления ядру Python.") }

                runOnUiThread { logMessage("🐍 [2/3] Вызов Python-модуля (SciPy/OpenCV)...") }
                val py = Python.getInstance()
                val pyModule = py.getModule("visualizer")
                
                val isPythonSuccess = pyModule.callAttr(
                    "generate_silent_spectrum",
                    wavAudioPath, bgPath, silentVideoPath
                ).toBoolean()

                if (isPythonSuccess) {
                    runOnUiThread { logMessage("✅ Математический рендер кадров Python завершен.") }
                    runOnUiThread { logMessage("🎬 [3/3] Сборка финального контейнера (FFmpeg мультиплексор)...") }

                    val ffmpegMergeCmd = "-y -i \"$silentVideoPath\" -i \"$inputAudioPath\" -c:v libx264 -crf 23 -pix_fmt yuv420p -c:a aac -shortest \"$finalVideoPath\""
                    
                    com.arthenica.ffmpegkit.FFmpegKit.executeAsync(ffmpegMergeCmd) { session ->
                        runOnUiThread {
                            File(wavAudioPath).delete()
                            File(silentVideoPath).delete()
                            
                            if (session.returnCode.isValueSuccess) {
                                logMessage("\n🎉 ПРОЦЕСС ЗАВЕРШЕН УСПЕШНО!")
                                logMessage("💾 Сохранено в память устройства:\n$finalVideoPath")
                                Toast.makeText(this@MainActivity, "Видео готово!", Toast.LENGTH_LONG).show()
                            } else {
                                logMessage("❌ Ошибка сборки контейнера:\n${session.allLogsAsString}")
                            }
                            endProcess()
                        }
                    }
                } else {
                    runOnUiThread {
                        logMessage("❌ Скрипт Python вернул критический флаг False.")
                        endProcess()
                    }
                }
            } catch (e: PyException) {
                runOnUiThread {
                    logMessage("\n💥 ТРЕЙСБЕК ОШИБКИ ИЗ PYTHON СРЕДЫ:\n${e.message}")
                    endProcess()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    logMessage("\n💥 СИСТЕМНАЯ ОШИБКА АНДРОИД:\n${Log.getStackTraceString(e)}")
                    endProcess()
                }
            }
        }.start()
    }

    private fun logMessage(message: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvGlobalStatus.append("[$timeStamp] $message\n")
        scrollToBottom()
    }

    fun appendPythonLog(line: String) {
        tvGlobalStatus.append("🐍 >>> $line\n")
        scrollToBottom()
    }

    private fun scrollToBottom() {
        mainScrollView.post {
            mainScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun endProcess() {
        btnStart.isEnabled = true
        progressBar.visibility = View.GONE
        logMessage("🏁 Поток обработки остановлен.")
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = ""
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) name = cursor.getString(index)
                }
            }
        }
        if (name.isEmpty()) {
            name = uri.path ?: "file"
            val cut = name.lastIndexOf('/')
            if (cut != -1) name = name.substring(cut + 1)
        }
        return name
    }

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