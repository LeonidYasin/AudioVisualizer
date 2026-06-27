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

    // Буферы для троттлинга высокоинтенсивных логов из Python среды
    private val pendingPythonLogs = StringBuilder()
    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var isLogUpdateScheduled = false

    // Интеллектуальный флаг автоскролла
    private var autoScrollEnabled = true

    // Класс перехвата стандартного вывода (print) из Python среды
    class PythonLogger(private val activity: MainActivity) {
        @Keep
        fun write(text: String) {
            activity.appendPythonLogThrottled(text)
        }
        
        @Keep
        fun flush() {
            // Необходим для интерфейса sys.stdout/sys.stderr в Python
        }
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

        // Отслеживание скролла: строго определяет положение каретки пользователя
        mainScrollView.viewTreeObserver.addOnScrollChangedListener {
            val child = mainScrollView.getChildAt(0)
            if (child != null) {
                val maxScrollY = child.height - mainScrollView.height
                // Если пользователь находится у самого низа (с допуском 150px), автоскролл активен. 
                // Если отмотал вверх — автоскролл засыпает.
                autoScrollEnabled = (maxScrollY - mainScrollView.scrollY) <= 150 || maxScrollY <= 0
            }
        }

        // Инициализация Python платформы
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        try {
            val py = Python.getInstance()
            val sys = py.getModule("sys")
            val logger = PythonLogger(this)
            
            // Перенаправление стандартных потоков вывода Python в логер
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
        logMessage("🚀 ЗАПУСК СГЛАЖИВАНИЯ И МЕДИАРЕНДЕРА")
        logMessage("🚀 =========================================")

        val inputAudioPath = selectedAudioFile!!.absolutePath
        val wavAudioPath = File(cacheDir, "temp_mono.wav").absolutePath
        val silentVideoPath = File(cacheDir, "silent_temp.avi").absolutePath
        val finalVideoPath = File(getExternalFilesDir(null), "Спектр_${System.currentTimeMillis()}.mp4").absolutePath
        val bgPath = selectedBgFile?.absolutePath ?: ""

        Thread {
            try {
                logMessage("🎬 [1/3] Пережатие аудио в рабочий WAV Mono через FFmpeg...")
                val ffmpegPreCmd = "-y -i \"$inputAudioPath\" -ac 1 -ar 22050 \"$wavAudioPath\""
                val preSession = com.arthenica.ffmpegkit.FFmpegKit.execute(ffmpegPreCmd)
                
                if (!preSession.returnCode.isValueSuccess) {
                    logMessage("❌ Сбой FFmpeg декодера:\n${preSession.allLogsAsString}")
                    endProcess()
                    return@Thread
                }
                
                logMessage("✅ Аудио подготовлено. Передача управления ядру Python.")
                logMessage("🐍 [2/3] Вызов Python-модуля (SciPy/OpenCV)...")
                
                val py = Python.getInstance()
                val pyModule = py.getModule("visualizer")
                
                val isPythonSuccess = pyModule.callAttr(
                    "generate_silent_spectrum",
                    wavAudioPath, bgPath, silentVideoPath
                ).toBoolean()

                if (isPythonSuccess) {
                    logMessage("✅ Математический рендер кадров Python завершен.")
                    logMessage("🎬 [3/3] Сборка финального контейнера (FFmpeg мультиплексор)...")

                    // ИСПРАВЛЕНО: Добавлен фильтр масштабирования -vf для скругления нечетных разрешений (например, 1077 -> 1076)
                    val ffmpegMergeCmd = "-y -i \"$silentVideoPath\" -i \"$inputAudioPath\" -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" -c:v libx264 -crf 23 -pix_fmt yuv420p -c:a aac -shortest \"$finalVideoPath\""
                    
                    com.arthenica.ffmpegkit.FFmpegKit.executeAsync(ffmpegMergeCmd) { session ->
                        File(wavAudioPath).delete()
                        File(silentVideoPath).delete()
                        
                        if (session.returnCode.isValueSuccess) {
                            logMessage("\n🎉 ПРОЦЕСС ЗАВЕРШЕН УСПЕШНО!")
                            logMessage("💾 Сохранено в память устройства:\n$finalVideoPath")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Видео готово!", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            logMessage("❌ Ошибка сборки контейнера:\n${session.allLogsAsString}")
                        }
                        endProcess()
                    }
                } else {
                    logMessage("❌ Скрипт Python вернул критический флаг False.")
                    endProcess()
                }
            } catch (e: PyException) {
                logMessage("\n💥 ТРЕЙСБЕК ОШИБКИ ИЗ PYTHON СРЕДЫ:\n${e.message}")
                endProcess()
            } catch (e: Exception) {
                logMessage("\n💥 СИСТЕМНАЯ ОШИБКА АНДРОИД:\n${Log.getStackTraceString(e)}")
                endProcess()
            }
        }.start()
    }

    private fun logMessage(message: String) {
        runOnUiThread {
            val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvGlobalStatus.append("[$timeStamp] $message\n")
            trimLogBufferIfNeeded()
            
            // ИСПРАВЛЕНО: Задержка предохраняет от нежелательного выкидывания скролла вверх при смене этапов обработки
            mainScrollView.postDelayed({
                scrollToBottom()
            }, 50)
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
        // ИСПРАВЛЕНО: Если флаг снят пользователем (просмотр старых ошибок) — автоскролл игнорируется намертво
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
            logMessage("🏁 Поток обработки остановлен.")
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
