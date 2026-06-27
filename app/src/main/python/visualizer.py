import os
import numpy as np
import cv2
import librosa

def generate_silent_spectrum(audio_path, background_path, output_silent_path, n_bars=50, bar_width=12, bar_height=120, bar_spacing=3):
    try:
        # 1. Настройка сцены и загрузка изображения
        if background_path and os.path.exists(background_path):
            bg_img = cv2.imread(background_path)
            if bg_img is None:
                raise ValueError("Не удалось прочитать изображение")
            bg_height, bg_width, _ = bg_img.shape
        else:
            bg_width, bg_height = 1280, 720
            bg_img = np.zeros((bg_height, bg_width, 3), dtype=np.uint8)
            bg_img[:] = (15, 10, 25)

        # Расчет геометрии нижней плашки спектрометра
        total_bars_width = n_bars * bar_width + (n_bars - 1) * bar_spacing
        new_width = max(bg_width, total_bars_width + 40)
        padding_bottom, padding_between = 25, 20
        new_height = bg_height + bar_height + padding_bottom + padding_between
        
        x_start = (new_width - total_bars_width) // 2
        y_start = bg_height + padding_between

        final_background = np.zeros((new_height, new_width, 3), dtype=np.uint8)
        final_background[0:bg_height, 0:bg_width] = bg_img

        # 2. Преобразование аудио через Librosa (STFT)
        fps = 30
        y, sr = librosa.load(audio_path, sr=None)
        hop_length = int(sr / fps)
        n_fft = hop_length * 2
        D = librosa.stft(y, hop_length=hop_length, n_fft=n_fft)
        S_db = librosa.amplitude_to_db(np.abs(D), ref=np.max)

        freq_bins = S_db.shape[0]
        n_frames = S_db.shape[1]

        # Логарифмический шаг шкалы частот
        bar_edges = np.logspace(np.log10(1), np.log10(max(freq_bins, 2)), n_bars + 1, dtype=int)
        bar_edges = np.clip(bar_edges, 0, freq_bins - 1)
        bar_edges = np.unique(bar_edges)
        actual_n_bars = len(bar_edges) - 1

        # 3. Генерация видеопотока
        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        out = cv2.VideoWriter(output_silent_path, fourcc, fps, (new_width, new_height))

        # Насыщенная неоновая палитра цвета (Фиолетово-розовый градиент)
        def get_neon_color(norm_val):
            # Переход от темно-фиолетового к ярко-розовому в формате BGR
            r = int(130 + (255 - 130) * norm_val)
            g = int(0)
            b = int(180 + (255 - 180) * norm_val)
            return (b, g, r)

        for frame_idx in range(n_frames):
            frame = final_background.copy()
            for bar_idx in range(actual_n_bars):
                low, high = bar_edges[bar_idx], bar_edges[bar_idx + 1]
                if low >= freq_bins:
                    norm = 0.0
                else:
                    magnitudes = S_db[low:high, frame_idx]
                    norm = np.clip((np.mean(magnitudes) + 75) / 75.0, 0.0, 1.0) if magnitudes.size > 0 else 0.0

                current_height = max(int(norm * bar_height), 4)
                x = x_start + bar_idx * (bar_width + bar_spacing)
                y = y_start + bar_height - current_height
                
                # Рендерим неоновые столбики
                cv2.rectangle(frame, (x, y), (x + bar_width, y + current_height), get_neon_color(norm), -1)

            out.write(frame)

        out.release()
        return True
    except Exception as e:
        print(f"Критический сбой Python: {str(e)}")
        return False
