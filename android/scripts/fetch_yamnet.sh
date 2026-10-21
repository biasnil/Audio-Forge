#!/usr/bin/env bash
# Downloads the YAMNet genre-detection model into app/src/main/assets and
# checks it against pinned SHA-256 sums. The files are committed, so this is
# only needed to verify them or to replace them on purpose. Run from android/:
#
#   scripts/fetch_yamnet.sh
#
# Source: YAMNet, the full TFLite variant (variable-length 16 kHz mono
# waveform in; scores [N,521], embeddings [N,1024], log-mel spectrogram out),
# Apache 2.0, published by Google on Kaggle:
#   https://www.kaggle.com/models/google/yamnet/tfLite/tflite/1
# Class names: the TensorFlow models repository.
#   https://github.com/tensorflow/models/tree/master/research/audioset/yamnet
#
# If you change the model or the class map, update the sums below and bump
# MODEL_ID in data/Genre.kt so every song is analyzed again.
set -euo pipefail

MODEL_URL="https://www.kaggle.com/api/v1/models/google/yamnet/tfLite/tflite/1/download"
MODEL_SHA256="141fba1cdaae842c816f28edc4937e8b4f0af4c8df21862ccc6b52dc567993c3"
CLASSES_URL="https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv"
CLASSES_SHA256="cdf24d193e196d9e95912a2667051ae203e92a2ba09449218ccb40ef787c6df2"
ASSETS="app/src/main/assets"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

check() { # file expected-sha256
    local actual
    actual="$(sha256sum "$1" | cut -d' ' -f1)"
    if [ "$actual" != "$2" ]; then
        echo "SHA-256 mismatch for $1: got $actual, expected $2" >&2
        exit 1
    fi
}

curl -fsSL -o "$work/yamnet.tar.gz" "$MODEL_URL"
tar -xzf "$work/yamnet.tar.gz" -C "$work"
check "$work/1.tflite" "$MODEL_SHA256"
curl -fsSL -o "$work/yamnet_class_map.csv" "$CLASSES_URL"
check "$work/yamnet_class_map.csv" "$CLASSES_SHA256"

mkdir -p "$ASSETS"
cp "$work/1.tflite" "$ASSETS/yamnet.tflite"
cp "$work/yamnet_class_map.csv" "$ASSETS/yamnet_class_map.csv"
echo "YAMNet model and class map are in $ASSETS (checksums match)."
