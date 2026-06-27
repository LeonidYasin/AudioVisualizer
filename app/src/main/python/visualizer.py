import os
import numpy as np
import cv2
from scipy.io import wavfile
from scipy.signal import stft
import sys
import builtins

# Исправление буферизации: переопределяем print, чтобы он мгновенно проталкивал логи в Android
def print(*args, **kwargs):
    kwargs['flush'] = True
    builtins.print(*args, **kwargs)

def generate_silent_spectrum(audio_path, background_path, output_silent_path, n_bars=50, bar_width=12, bar_height=120, bar_spacing=3):
    try:
        print("🐍 >>> Python-модуль запущен успешно.")
        
        # 1. Настройка графической сцены
        if background_path and os.path.exists(background_path):
            # ВМЕСТО ЭТОГО КОДА В visualizer.py:
            # bg_img = cv2.imread(background_path)

            # ИСПОЛЬЗУЙТЕ ЭТОТ БЕЗОПАСНЫЙ ВАРИАНТ:
            with open(background_path, "rb") as f:
                file_bytes = np.frombuffer(f.read(), dtype=np.uint8)
                bg_img = cv2.imdecode(file_bytes, cv2.IMREAD_COLOR)
            if bg_img is None:
                raise ValueError("Не удалось прочитать изображение")
            bg_height, bg_width, _ = bg_img.shape
        else:
            bg_width, bg_height = 1280, 720
            bg_img = np.zeros((bg_height, bg_width, 3), dtype=np.uint8)
            bg_img[:] = (15, 10, 25)

        total_bars_width = n_bars * bar_width + (n_bars - 1) * bar_spacing
        new_width = max(bg_width, total_bars_width + 40)
        padding_bottom, padding_between = 25, 20
        new_height = bg_height + bar_height + padding_bottom + padding_between
        
        x_start = (new_width - total_bars_width) // 2
        y_start = bg_height + padding_between

        print(f"🐍 >>> Чтение аудиофайла: {os.path.basename(audio_path)}")
        sample_rate, audio_data = wavfile.read(audio_path)
        
        if len(audio_data.shape) > 1:
            audio_data = np.mean(audio_data, axis=1)

        print("🐍 >>> Расчет спектрограммы (алгоритм STFT)...")
        nperseg = int(sample_rate * 0.04)
        noverlap = int(sample_rate * 0.02)
        f, t, Zxx = stft(audio_data, fs=sample_rate, nperseg=nperseg, noverlap=noverlap)
        S_db = 20 * np.log10(np.abs(Zxx) + 1e-6)

        fps = 30
        duration = len(audio_data) / sample_rate
        n_frames = int(duration * fps)
        
        t_video = np.linspace(0, duration, n_frames)
        freq_bins = S_db.shape[0]
        
        max_freq_idx = int(freq_bins * 0.4)
        bar_edges = np.logspace(0, np.log10(max_freq_idx), n_bars + 1, dtype=int)
        bar_edges = np.clip(bar_edges, 0, freq_bins - 1)
        bar_edges = np.unique(bar_edges)
        actual_n_bars = len(bar_edges) - 1

        final_background = np.zeros((new_height, new_width, 3), dtype=np.uint8)
        final_background[:] = (15, 10, 25)
        final_background[0:bg_height, 0:bg_width] = bg_img

        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        out = cv2.VideoWriter(output_silent_path, fourcc, fps, (new_width, new_height))

        def get_neon_color(norm_val):
            r = int(130 + (255 - 130) * norm_val)
            g = int(0)
            b = int(180 + (255 - 180) * norm_val)
            return (b, g, r)

        print(f"🐍 >>> Начинается рендеринг видео. Всего кадров: {n_frames}")
        
        for frame_idx in range(n_frames):
            # Периодически отправляем шаг прогресса в консоль (каждые 20 кадров, чтобы не спамить UI поток)
            if frame_idx % 20 == 0 or frame_idx == n_frames - 1:
                print(f"🐍 >>> Обработка: кадр {frame_idx + 1}/{n_frames} ({int((frame_idx + 1) / n_frames * 100)}%)")

            frame = final_background.copy()
            t_idx = np.searchsorted(t, t_video[frame_idx])
            t_idx = min(t_idx, S_db.shape[1] - 1)

            for bar_idx in range(actual_n_bars):
                low, high = bar_edges[bar_idx], bar_edges[bar_idx + 1]
                if low >= freq_bins:
                    norm = 0.0
                else:
                    magnitudes = S_db[low:high, t_idx]
                    norm = np.clip((np.mean(magnitudes) + 75) / 75.0, 0.0, 1.0) if magnitudes.size > 0 else 0.0

                current_bar_height = int(bar_height * norm)
                
                x1 = x_start + bar_idx * (bar_width + bar_spacing)
                y1 = y_start + (bar_height - current_bar_height)
                x2 = x1 + bar_width
                y2 = y_start + bar_height

                color = get_neon_color(norm)
                cv2.rectangle(frame, (x1, y1), (x2, y2), color, -1)

            out.write(frame)

        out.release()
        print("🐍 >>> Визуализация SciPy/OpenCV успешно завершена!")
        return True
        
    except Exception as e:
        print(f"🐍 >>> Ошибка логики SciPy/OpenCV: {str(e)}")
        return False