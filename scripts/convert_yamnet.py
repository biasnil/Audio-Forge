#!/usr/bin/env python3
"""Rebuilds resources/models/yamnet.onnx (genre detection's model) from the
Android app's yamnet.tflite -- the same Google YAMNet model, so both apps
detect the same genres.

Needs (in a throwaway virtual environment; ~600 MB, not needed afterwards):

    python -m venv yamnet_venv
    yamnet_venv\\Scripts\\pip install tensorflow tf2onnx onnx onnxruntime

Then, from the project root:

    yamnet_venv\\Scripts\\python scripts/convert_yamnet.py path/to/yamnet.tflite

It converts the model, repairs the two slices tf2onnx gets wrong, and checks
that the ONNX model gives the same scores as the TFLite one. The class map
(resources/models/yamnet_class_map.csv) is copied from the Android app's
assets as-is.
"""
import pathlib
import subprocess
import sys

import numpy as np
import onnx
import onnxruntime as ort
import tensorflow as tf
from onnx import numpy_helper

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resources" / "models" / "yamnet.onnx"


def fix_slices(model):
    """tf2onnx turns TFLite's StridedSlice into ONNX Slice with an empty
    `axes` constant. ONNX Runtime reads that as zero axes and rejects it;
    TFLite meant "every axis", which in ONNX is `axes` left out."""
    inits = {i.name: numpy_helper.to_array(i) for i in model.graph.initializer}
    fixed = 0
    for node in model.graph.node:
        if node.op_type == "Slice" and len(node.input) >= 4 and node.input[3] in inits and inits[node.input[3]].size == 0:
            node.input[3] = ""
            fixed += 1
    return fixed


def compare(tflite_path, onnx_path):
    t = np.arange(16000 * 10) / 16000.0
    wave = (0.3 * np.sin(2 * np.pi * 440 * t) + 0.2 * np.sin(2 * np.pi * 110 * t)).astype(np.float32)
    interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    interpreter.resize_tensor_input(0, [wave.size])
    interpreter.allocate_tensors()
    interpreter.set_tensor(interpreter.get_input_details()[0]["index"], wave)
    interpreter.invoke()
    expected = next(o for o in (interpreter.get_tensor(d["index"]) for d in interpreter.get_output_details())
                    if o.ndim == 2 and o.shape[1] == 521)
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    actual = next(o for o in session.run(None, {session.get_inputs()[0].name: wave}) if o.ndim == 2 and o.shape[1] == 521)
    return expected.shape == actual.shape and float(np.max(np.abs(expected - actual))) < 1e-4


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    tflite = pathlib.Path(sys.argv[1])
    subprocess.run([sys.executable, "-m", "tf2onnx.convert", "--tflite", str(tflite), "--output", str(OUT),
                    "--opset", "17"], check=True)
    model = onnx.load(str(OUT))
    print("repaired", fix_slices(model), "Slice node(s)")
    onnx.checker.check_model(model)
    onnx.save(model, str(OUT))
    if not compare(tflite, OUT):
        print("error: the ONNX model's scores differ from the TFLite model's")
        return 1
    print("ok:", OUT, "matches", tflite)
    return 0


if __name__ == "__main__":
    sys.exit(main())
