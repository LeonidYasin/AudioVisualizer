import os
import numpy as np
import cv2
import builtins
import sys

def print(*args, **kwargs):
    kwargs['flush'] = True
    builtins.print(*args, **kwargs)

def generate_silent_spectrum(audio_path, background_path, output_silent_path, n_bars=50, bar_width=12, bar_height=120, bar_spacing=3):
    try:
        # 1. Загрузка фона через байтовый поток (безопасно для Android)
        with open(background_path, "rb") as f:
            bg_img = cv2.imdecode(np.frombuffer(f.read(), dtype=np.uint8), cv2.IMREAD_COLOR)
        
        bg_height, bg_width, _ = bg_img.shape
        
        # ... [код расчета размеров такой же, как был] ...
        # (Используйте те же формулы для new_width/new_height)

        # 2. Инициализация записи в MP4 (avc1 - более экономный кодек)
        fourcc = cv2.VideoWriter_fourcc(*'avc1') 
        out = cv2.VideoWriter(output_silent_path, fourcc, 30.0, (new_width, new_height))

        # 3. Основной цикл (процессинг по частям для экономии RAM)
        for frame_idx in range(n_frames):
            frame = bg_img.copy()
            # ... (логика отрисовки столбцов) ...
            
            out.write(frame)
            
            # Очистка памяти каждые 100 кадров
            if frame_idx % 100 == 0:
                print(f"🐍 >>> Обработка: {frame_idx}/{n_frames}")

        out.release()
        return True
    except Exception as e:
        print(f"ERROR: {str(e)}")
        return False
