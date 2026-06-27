// ... (фрагмент в processVideo)

// ИСПОЛЬЗУЕМ .mp4 ДЛЯ ВСЕХ ПРОМЕЖУТОЧНЫХ ФАЙЛОВ
val silentVideoPath = File(cacheDir, "temp_render.mp4").absolutePath 

// Внутри Thread { ... }
val isPythonSuccess = pyModule.callAttr(
    "generate_silent_spectrum",
    wavAudioPath, bgPath, silentVideoPath
).toBoolean()

if (isPythonSuccess) {
    logMessage("✅ Рендеринг завершен. Сборка...")
    
    // ФИЛЬТР scale=trunc(iw/2)*2:trunc(ih/2)*2 - критически важен для libx264
    val ffmpegMergeCmd = "-y -i \"$silentVideoPath\" -i \"$inputAudioPath\" " +
                         "-vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" " +
                         "-c:v libx264 -preset ultrafast -crf 28 " + // ultrafast экономит CPU, crf 28 экономит место
                         "-c:a aac -b:a 128k -shortest \"$finalVideoPath\""

    com.arthenica.ffmpegkit.FFmpegKit.executeAsync(ffmpegMergeCmd) { session ->
        // ОЧИСТКА ВРЕМЕННЫХ ФАЙЛОВ
        File(silentVideoPath).delete()
        File(wavAudioPath).delete()
        
        if (session.returnCode.isValueSuccess) {
            logMessage("🎉 Видео готово!")
        }
    }
}
