"""Prints the YAMNet model's inputs and outputs, and the top labels for a WAV file.

Run from android/ with Python LiteRT installed (pip install ai-edge-litert numpy):

    python scripts/check_yamnet.py [clip.wav]

The app finds the outputs by shape, not position (GenreClassifier.kt); this
shows what the bundled model actually has. The WAV must be 16 kHz mono 16-bit.
"""
import csv
import sys
import wave

import numpy as np
from ai_edge_litert.interpreter import Interpreter

MODEL = "app/src/main/assets/yamnet.tflite"
CLASSES = "app/src/main/assets/yamnet_class_map.csv"

interpreter = Interpreter(model_path=MODEL)
print("Inputs:")
for d in interpreter.get_input_details():
    print(f"  {d['index']}: {d['name']} shape={d['shape']} signature={d['shape_signature']} dtype={d['dtype'].__name__}")
print("Outputs:")
for d in interpreter.get_output_details():
    print(f"  {d['index']}: {d['name']} shape={d['shape']} signature={d['shape_signature']} dtype={d['dtype'].__name__}")

if len(sys.argv) < 2:
    sys.exit(0)

with wave.open(sys.argv[1]) as w:
    if w.getframerate() != 16000 or w.getnchannels() != 1 or w.getsampwidth() != 2:
        sys.exit("Need a 16 kHz mono 16-bit WAV (ffmpeg -i in -ac 1 -ar 16000 -sample_fmt s16 out.wav)")
    samples = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0

names = [row["display_name"] for row in csv.DictReader(open(CLASSES, encoding="utf-8"))]
input_index = interpreter.get_input_details()[0]["index"]
interpreter.resize_tensor_input(input_index, [len(samples)], strict=False)
interpreter.allocate_tensors()
interpreter.set_tensor(input_index, samples)
interpreter.invoke()
scores = next(interpreter.get_tensor(d["index"]) for d in interpreter.get_output_details() if d["shape"][-1] == len(names))
mean = scores.mean(axis=0)
print(f"\n{scores.shape[0]} frames. Top 5 labels:")
for i in mean.argsort()[::-1][:5]:
    print(f"  {names[i]}: {mean[i]:.3f}")
