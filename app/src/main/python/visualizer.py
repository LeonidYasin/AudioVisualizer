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
    Возвращает путь к созданному файлу или False в случае ошибки.
    """
    try:
        print("🐍 >>> Python-модуль запущен успешно.")
        
        # 1. Загрузка аудио
        print(f"🐍 >>> Загрузка аудио: {os.path.basename(audio_wav_path)}")
        sample_rate, data = wavfile.read(audio_wav_path)
        if len(data.shape) > 1:
            data = data[:, 0]

        duration = len(data) / sample_rate
        n_frames = int(duration * fps)
        print(f"🐍 >>> Длительность: {duration:.1f}с, Кадров: {n_frames}")

        # 2. Расчет спектрограммы
        nperseg = sample_rate // fps
        noverlap = nperseg // 2
        print(f"🐍 >>> Расчет спектрограммы STFT...")
        f, t, Zxx = stft(data, fs=sample_rate, nperseg=nperseg, noverlap=noverlap)
        
        del data
        
        Zxx = np.abs(Zxx)
        freq_limit = 2000
        freq_idx = np.where(f <= freq_limit)[0]
        if len(freq_idx) > 0:
            Zxx = Zxx[:freq_idx[-1] + 1, :]
        
        bars_chunks = np.array_split(Zxx, n_bars, axis=0)
        bars_data = np.array([np.mean(chunk, axis=0) for chunk in bars_chunks])
        
        del Zxx
        
        bars_data = np.log1p(bars_data)
        if np.max(bars_data) > 0:
            bars_data = bars_data / np.max(bars_data)
        
        # 3. FULLHD графика
        width, height = 1920, 1080
        
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

        # 4. Инициализация VideoWriter - используем MJPG для AVI
        fourcc = cv2.VideoWriter_fourcc(*'MJPG')
        out = cv2.VideoWriter(output_path, fourcc, fps, (width, height))
        
        if not out.isOpened():
            print("❌ Ошибка: Не удалось инициализировать OpenCV VideoWriter!")
            return "False"

        print(f"🎬 Начинается рендеринг {n_frames} кадров FullHD...")

        # 5. Основной цикл
        bar_w = width // n_bars
        max_bar_h = 450
        y_base = height - 30
        
        color_cache = {}
        
        def get_neon_color(norm_val):
            key = int(norm_val * 100)
            if key in color_cache:
                return color_cache[key]
            
            r = int(130 + (255 - 130) * norm_val)
            g = int(0)
            b = int(180 + (255 - 180) * norm_val)
            color = (b, g, r)
            color_cache[key] = color
            return color

        frame_template = bg_image.copy()
        
        # Переменная для отслеживания последнего выведенного процента
        last_percent = -1
        
        for i in range(n_frames):
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

            # Компактный вывод прогресса: только проценты
            percent = int(((i + 1) / n_frames) * 100)
            if percent != last_percent and percent % 5 == 0:  # Каждые 5%
                print(f"🐍 >>> {percent}%")
                last_percent = percent

        # 6. Завершение
        out.release()
        
        del frame_template
        del bg_image
        del bars_data
        color_cache.clear()
        
        file_size_mb = os.path.getsize(output_path) / (1024 * 1024)
        print(f"🐍 >>> Готово! Размер: {file_size_mb:.1f} МБ")
        print(f"🐍 >>> Файл: {output_path}")
        
        return output_path
        
    except Exception as e:
        print(f"🐍 >>> Ошибка: {str(e)}")
        import traceback
        traceback.print_exc()
        return "False"
