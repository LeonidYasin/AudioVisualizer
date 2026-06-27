package com.example.visualizer

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
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

    private val pendingPythonLogs = StringBuilder()
    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var isLogUpdateScheduled = false
    private var autoScrollEnabled = true

    class PythonLogger(private val activity: MainActivity) {
        @Keep
        fun write(text: String) {
            activity.appendPythonLogThrottled(text)
        }
        
        @Keep
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

        mainScrollView.viewTreeObserver.addOnScrollChangedListener {
            val child = mainScrollView.getChildAt(0)
            if (child != null) {
                val maxScrollY = child.height - mainScrollView.height
                autoScrollEnabled = (maxScrollY - mainScrollView.scrollY) <= 150 || maxScrollY <= 0
            }
        }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        try {
            val py = Python.getInstance()
            val sys = py.getModule("sys")
            val logger = PythonLogger(this)
            
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (autoScrollEnabled) {
            mainScrollView.postDelayed({
                val child = mainScrollView.getChildAt(0)
                if (child != null) {
                    val lastPixelSpace = child.height - mainScrollView.height
                    if (lastPixelSpace > 0) mainScrollView.scrollTo(0, lastPixelSpace)
                }
            }, 200)
        }
    }

    private fun processVideo() {
        btnStart.isEnabled = false
        progressBar.visibility = View.VISIBLE
        
        logMessage("\n🚀 =========================================")
        logMessage("🚀 ЗАПУСК ВИЗУАЛИЗАЦИИ FULLHD")
        logMessage("🚀 =========================================")

        val inputAudioPath = selectedAudioFile!!.absolutePath
        val wavAudioPath = File(cacheDir, "temp_mono.wav").absolutePath
        
        // Временные файлы
        val tempVideoPath = File(cacheDir, "temp_video.avi").absolutePath
        val finalVideoPath = File(
            getExternalFilesDir(null), 
            "Визуализация_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.mp4"
        ).absolutePath
        val bgPath = selectedBgFile?.absolutePath ?: ""

        Thread {
            try {
                // ШАГ 1: Подготовка аудио
                logMessage("🎬 [1/3] Подготовка аудио (FFmpeg)...")
                val ffmpegPreCmd = "-y -i \"$inputAudioPath\" -ac 1 -ar 22050 \"$wavAudioPath\""
                val preSession = com.arthenica.ffmpegkit.FFmpegKit.execute(ffmpegPreCmd)
                
                if (!preSession.returnCode.isValueSuccess) {
                    logMessage("❌ Сбой FFmpeg:\n${preSession.allLogsAsString}")
                    endProcess()
                    return@Thread
                }
                
                val wavSize = File(wavAudioPath).length() / 1024
                logMessage("✅ Аудио подготовлено (${wavSize} КБ)")

                // ШАГ 2: Python рендеринг
                logMessage("🐍 [2/3] Рендеринг визуализации (Python)...")
                
                val py = Python.getInstance()
                val pyModule = py.getModule("visualizer")
                
                val startTime = System.currentTimeMillis()
                val resultPath = pyModule.callAttr(
                    "create_spectrum_video",
                    wavAudioPath, tempVideoPath, bgPath
                ).asString()
                
                val renderTime = (System.currentTimeMillis() - startTime) / 1000

                if (resultPath != "False" && File(resultPath).exists()) {
                    val tempSize = File(resultPath).length() / (1024 * 1024)
                    logMessage("✅ Рендеринг завершен за ${renderTime}с")
                    logMessage("📊 Промежуточный файл: ${tempSize} МБ")

                    // ШАГ 3: Финальная сборка MP4 с оптимизацией
                    logMessage("🎬 [3/3] Сборка MP4...")
                    
                    // Оптимальные настройки для FullHD с балансом качество/размер
                    val ffmpegMergeCmd = "-y -i \"$resultPath\" -i \"$inputAudioPath\" " +
                            "-vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" " +
                            "-c:v libx264 -preset medium -crf 22 -pix_fmt yuv420p " +
                            "-c:a aac -b:a 192k -shortest -movflags +faststart " +
                            "-profile:v high -level 4.0 \"$finalVideoPath\""
                    
                    com.arthenica.ffmpegkit.FFmpegKit.executeAsync(ffmpegMergeCmd) { session ->
                        // Очистка временных файлов
                        File(wavAudioPath).delete()
                        File(resultPath).delete()
                        
                        if (session.returnCode.isValueSuccess) {
                            val finalSize = File(finalVideoPath).length() / (1024 * 1024)
                            logMessage("\n🎉 ВИДЕО FULLHD ГОТОВО!")
                            logMessage("📊 Размер: ${finalSize} МБ")
                            logMessage("📁 Сохранено: ${File(finalVideoPath).name}")
                            logMessage("📂 Путь: $finalVideoPath")
                            
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity, 
                                    "FullHD видео готово! ${finalSize} МБ", 
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            logMessage("❌ Ошибка сборки:\n${session.allLogsAsString}")
                        }
                        endProcess()
                    }
                } else {
                    logMessage("❌ Python вернул ошибку или файл не создан")
                    endProcess()
                }
            } catch (e: PyException) {
                logMessage("\n💥 ОШИБКА PYTHON:\n${e.message}")
                endProcess()
            } catch (e: Exception) {
                logMessage("\n💥 СИСТЕМНАЯ ОШИБКА:\n${Log.getStackTraceString(e)}")
                endProcess()
            }
        }.start()
    }

    private fun logMessage(message: String) {
        runOnUiThread {
            val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvGlobalStatus.append("[$timeStamp] $message\n")
            trimLogBufferIfNeeded()
            mainScrollView.postDelayed({ scrollToBottom() }, 50)
        }
    }

    fun appendPythonLogThrottled(text: String) {
        if (text.isEmpty()) return
        
        synchronized(pendingPythonLogs) {
            pendingPythonLogs.append(text.replace("\r", "\n"))
        }

        if (!isLogUpdateScheduled) {
            isLogUpdateScheduled = true
            uiHandler.postDelayed({
                flushPythonLogsToUI()
            }, 250)
        }
    }

    private fun flushPythonLogsToUI() {
        val chunk: String
        synchronized(pendingPythonLogs) {
            chunk = pendingPythonLogs.toString()
            pendingPythonLogs.setLength(0)
            isLogUpdateScheduled = false
        }

        if (chunk.isEmpty()) return

        val lines = chunk.split("\n")
        val formattedChunk = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                if (trimmed.startsWith("🐍 >>>")) {
                    formattedChunk.append("$trimmed\n")
                } else {
                    formattedChunk.append("🐍 >>> $trimmed\n")
                }
            }
        }

        if (formattedChunk.isNotEmpty()) {
            tvGlobalStatus.append(formattedChunk)
            trimLogBufferIfNeeded()
            scrollToBottom()
        }
    }

    private fun trimLogBufferIfNeeded() {
        val currentText = tvGlobalStatus.text
        if (currentText.length > 40000) {
            tvGlobalStatus.text = currentText.substring(currentText.length - 20000)
        }
    }

    private fun scrollToBottom() {
        if (!autoScrollEnabled) return

        mainScrollView.post {
            val child = mainScrollView.getChildAt(0)
            if (child != null) {
                val lastPixelSpace = child.height - mainScrollView.height
                if (lastPixelSpace > 0) {
                    mainScrollView.scrollTo(0, lastPixelSpace)
                }
            }
        }
    }

    private fun endProcess() {
        runOnUiThread {
            btnStart.isEnabled = true
            progressBar.visibility = View.GONE
            logMessage("🏁 Обработка завершена.")
        }
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
