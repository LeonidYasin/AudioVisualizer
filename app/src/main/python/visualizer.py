import os
import numpy as np
import cv2
from scipy.io import wavfile
from scipy.signal import stft
import sys
import builtins

# Исправление буферизации для мгновенного проталкивания логов в Android UI
def print(*args, **kwargs):
    kwargs['flush'] = True
    builtins.print(*args, **kwargs)

def create_spectrum_video(audio_wav_path, output_path, background_path=None, fps=30, n_bars=50):
    """
    Создает видео визуализации спектра на основе WAV-файла.
    Использует SciPy для анализа и OpenCV для отрисовки.
    """
    try:
        print("🐍 >>> Python-модуль запущен успешно.")
        
        # 1. Загрузка аудио (WAV подготавливается на стороне Kotlin через FFmpeg)
        sample_rate, data = wavfile.read(audio_wav_path)
        if len(data.shape) > 1:
            data = data[:, 0]  # Преобразование в моно, если файл стерео

        duration = len(data) / sample_rate
        n_frames = int(duration * fps)

        # 2. Математический анализ спектра (STFT)
        # nperseg подбирается так, чтобы окно анализа соответствовало частоте кадров
        nperseg = sample_rate // fps
        f, t, Zxx = stft(data, fs=sample_rate, nperseg=nperseg)
        
        # Берем амплитуду и ограничиваем частоты (до 2000 Гц для наглядности басов)
        Zxx = np.abs(Zxx)
        # ИСПРАВЛЕНО: корректное ограничение частот
        freq_limit = 2000  # Гц
        freq_idx = np.where(f <= freq_limit)[0]
        if len(freq_idx) > 0:
            Zxx = Zxx[:freq_idx[-1] + 1, :]
        
        # Группируем частоты по барам (столбикам)
        bars_chunks = np.array_split(Zxx, n_bars, axis=0)
        bars_data = np.array([np.mean(chunk, axis=0) for chunk in bars_chunks])
        
        # Логарифмическая нормализация для "прыгучести"
        bars_data = np.log1p(bars_data)
        if np.max(bars_data) > 0:
            bars_data = bars_data / np.max(bars_data)

        # 3. Подготовка графики
        width, height = 1280, 720
        
        # Безопасное чтение фонового изображения для Android
        if background_path and os.path.exists(background_path):
            try:
                # Безопасное побайтовое чтение изображения для Android хранилища
                with open(background_path, "rb") as f:
                    file_bytes = np.frombuffer(f.read(), dtype=np.uint8)
                    bg_image = cv2.imdecode(file_bytes, cv2.IMREAD_COLOR)
                if bg_image is None:
                    raise ValueError("Не удалось декодировать фоновое изображение через cv2.imdecode")
                bg_image = cv2.resize(bg_image, (width, height))
            except Exception as e:
                print(f"🐍 >>> Ошибка загрузки фона: {e}, используется темный фон")
                bg_image = np.zeros((height, width, 3), dtype=np.uint8)
                bg_image[:] = (15, 15, 15)  # Темно-серый фон
        else:
            bg_image = np.zeros((height, width, 3), dtype=np.uint8)
            bg_image[:] = (15, 15, 15)  # Темно-серый фон

        # 4. Инициализация VideoWriter (MP4 контейнер для обхода лимита 2ГБ)
        # Используем 'mp4v', который стабильно работает в Chaquopy
        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        out = cv2.VideoWriter(output_path, fourcc, fps, (width, height))

        if not out.isOpened():
            print("❌ Ошибка: Не удалось инициализировать OpenCV VideoWriter!")
            return False

        print(f"🎬 Начинается рендеринг {n_frames} кадров...")

        # 5. Основной цикл отрисовки кадров
        bar_w = width // n_bars
        max_bar_h = 400

        def get_neon_color(norm_val):
            r = int(130 + (255 - 130) * norm_val)
            g = int(0)
            b = int(180 + (255 - 180) * norm_val)
            return (b, g, r)

        for i in range(n_frames):
            frame = bg_image.copy()
            
            if i < bars_data.shape[1]:
                current_frame_data = bars_data[:, i]
                
                for b in range(n_bars):
                    # Вычисляем высоту столбика
                    val = current_frame_data[b] if b < len(current_frame_data) else 0.0
                    h = int(val * max_bar_h) + 10
                    
                    # Рисуем столбик (неоновый цвет)
                    color = get_neon_color(val)
                    cv2.rectangle(
                        frame,
                        (b * bar_w + 2, height - 20),
                        ((b + 1) * bar_w - 2, height - 20 - h),
                        color,
                        -1
                    )

            out.write(frame)

            # Вывод прогресса для перехвата в Android UI
            if i % 30 == 0 or i == n_frames - 1:
                percent = int(((i + 1) / n_frames) * 100)
                print(f"🐍 >>> Обработка: кадр {i + 1}/{n_frames} ({percent}%)")

        # 6. Завершение
        out.release()
        print("🐍 >>> Визуализация SciPy/OpenCV успешно завершена!")
        return True
        
    except Exception as e:
        print(f"🐍 >>> Ошибка логики SciPy/OpenCV: {str(e)}")
        import traceback
        traceback.print_exc()
        return False
