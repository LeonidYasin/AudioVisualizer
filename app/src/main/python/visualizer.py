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
    FullHD качество, оптимизировано для экономии памяти.
    """
    try:
        print("🐍 >>> Python-модуль запущен успешно.")
        
        # 1. Загрузка аудио (WAV подготавливается на стороне Kotlin через FFmpeg)
        print(f"🐍 >>> Загрузка аудио: {os.path.basename(audio_wav_path)}")
        sample_rate, data = wavfile.read(audio_wav_path)
        if len(data.shape) > 1:
            data = data[:, 0]  # Преобразование в моно, если файл стерео

        duration = len(data) / sample_rate
        n_frames = int(duration * fps)
        print(f"🐍 >>> Длительность: {duration:.1f}с, Кадров: {n_frames}")

        # 2. Математический анализ спектра (STFT) с оптимизацией
        nperseg = sample_rate // fps
        noverlap = nperseg // 2  # 50% перекрытие для плавности
        print(f"🐍 >>> Расчет спектрограммы STFT...")
        f, t, Zxx = stft(data, fs=sample_rate, nperseg=nperseg, noverlap=noverlap)
        
        # Освобождаем память от оригинальных данных
        del data
        
        # Берем амплитуду и ограничиваем частоты (до 2000 Гц)
        Zxx = np.abs(Zxx)
        freq_limit = 2000  # Гц
        freq_idx = np.where(f <= freq_limit)[0]
        if len(freq_idx) > 0:
            Zxx = Zxx[:freq_idx[-1] + 1, :]
        
        # Группируем частоты по барам
        bars_chunks = np.array_split(Zxx, n_bars, axis=0)
        bars_data = np.array([np.mean(chunk, axis=0) for chunk in bars_chunks])
        
        # Освобождаем память от спектрограммы
        del Zxx
        
        # Логарифмическая нормализация
        bars_data = np.log1p(bars_data)
        if np.max(bars_data) > 0:
            bars_data = bars_data / np.max(bars_data)
        
        # 3. FULLHD графика (без потери качества)
        width, height = 1920, 1080  # FullHD
        
        # Безопасное чтение фонового изображения
        if background_path and os.path.exists(background_path):
            try:
                with open(background_path, "rb") as f:
                    file_bytes = np.frombuffer(f.read(), dtype=np.uint8)
                    bg_image = cv2.imdecode(file_bytes, cv2.IMREAD_COLOR)
                if bg_image is None:
                    raise ValueError("Не удалось декодировать фоновое изображение")
                bg_image = cv2.resize(bg_image, (width, height), interpolation=cv2.INTER_LANCZOS4)
            except Exception as e:
                print(f"🐍 >>> Ошибка загрузки фона: {e}, используется темный фон")
                bg_image = np.zeros((height, width, 3), dtype=np.uint8)
                bg_image[:] = (15, 15, 15)
        else:
            bg_image = np.zeros((height, width, 3), dtype=np.uint8)
            bg_image[:] = (15, 15, 15)

        # 4. Инициализация VideoWriter - пробуем разные кодеки
        out = None
        codecs_to_try = [
            ('avc1', '.mp4'),   # H.264 для MP4 (компактный)
            ('mp4v', '.mp4'),   # MPEG-4 для MP4
            ('X264', '.mp4'),   # Другой вариант H.264
        ]
        
        # Сначала пробуем MP4 кодеки
        for codec, ext in codecs_to_try:
            fourcc = cv2.VideoWriter_fourcc(*codec)
            test_path = output_path
            if not test_path.endswith(ext):
                test_path = test_path.rsplit('.', 1)[0] + ext
            
            try:
                out = cv2.VideoWriter(test_path, fourcc, fps, (width, height))
                if out.isOpened():
                    print(f"🐍 >>> Используется кодек: {codec}, файл: {os.path.basename(test_path)}")
                    output_path = test_path
                    break
                else:
                    out.release()
                    out = None
            except:
                if out is not None:
                    out.release()
                    out = None
        
        # Если MP4 не работает, пробуем AVI (но с MJPG для экономии места)
        if out is None:
            fourcc = cv2.VideoWriter_fourcc(*'MJPG')
            test_path = output_path.rsplit('.', 1)[0] + '.avi'
            out = cv2.VideoWriter(test_path, fourcc, fps, (width, height))
            if out.isOpened():
                print(f"🐍 >>> Используется кодек: MJPG (AVI), файл: {os.path.basename(test_path)}")
                output_path = test_path
            else:
                out.release()
                out = None
        
        if out is None:
            print("❌ Ошибка: Не удалось инициализировать OpenCV VideoWriter!")
            return False

        print(f"🎬 Начинается рендеринг {n_frames} кадров FullHD...")

        # 5. Основной цикл отрисовки кадров
        bar_w = width // n_bars
        max_bar_h = 450  # Фиксированная высота для FullHD
        
        # Предварительная компиляция цветов (оптимизация)
        color_cache = {}
        
        def get_neon_color(norm_val):
            # Кэширование цветов для ускорения
            key = int(norm_val * 100)
            if key in color_cache:
                return color_cache[key]
            
            r = int(130 + (255 - 130) * norm_val)
            g = int(0)
            b = int(180 + (255 - 180) * norm_val)
            color = (b, g, r)
            color_cache[key] = color
            return color

        # Предварительное выделение памяти для кадра
        frame_template = bg_image.copy()
        y_base = height - 30  # Отступ снизу
        
        for i in range(n_frames):
            # Копируем шаблон (быстрее чем создание нового)
            frame = frame_template.copy()
            
            if i < bars_data.shape[1]:
                current_frame_data = bars_data[:, i]
                
                for b in range(n_bars):
                    val = current_frame_data[b] if b < len(current_frame_data) else 0.0
                    h = int(val * max_bar_h)
                    
                    if h > 0:
                        color = get_neon_color(val)
                        x1 = b * bar_w + 3
                        y1 = y_base - h
                        x2 = (b + 1) * bar_w - 3
                        y2 = y_base
                        
                        cv2.rectangle(frame, (x1, y1), (x2, y2), color, -1)

            out.write(frame)

            # Вывод прогресса (реже для экономии CPU)
            if i % 60 == 0 or i == n_frames - 1:
                percent = int(((i + 1) / n_frames) * 100)
                print(f"🐍 >>> Прогресс: {percent}% ({i + 1}/{n_frames} кадров)")

        # 6. Завершение
        out.release()
        
        # Освобождаем память
        del frame_template
        del bg_image
        del bars_data
        color_cache.clear()
        
        file_size_mb = os.path.getsize(output_path) / (1024 * 1024)
        print(f"🐍 >>> Визуализация завершена! Размер: {file_size_mb:.1f} МБ")
        print(f"🐍 >>> Файл: {output_path}")
        
        # Возвращаем путь к файлу для Kotlin
        return output_path
        
    except Exception as e:
        print(f"🐍 >>> Ошибка: {str(e)}")
        import traceback
        traceback.print_exc()
        return False
