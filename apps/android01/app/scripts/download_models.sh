#!/usr/bin/env bash
set -e

mkdir -p app/src/main/assets/models/stt
mkdir -p app/src/main/assets/models/tts

# Download Whisper tiny.en STT model
if [ ! -f app/src/main/assets/models/stt/whisper_tiny_en.onnx ]; then
  curl -L -o app/src/main/assets/models/stt/whisper_tiny_en.onnx \
    https://huggingface.co/onnx-community/whisper-tiny-en/resolve/main/whisper-tiny-en.onnx
fi

# Download VITS ljspeech TTS model
if [ ! -f app/src/main/assets/models/tts/vits_ljspeech.onnx ]; then
  curl -L -o app/src/main/assets/models/tts/vits_ljspeech.onnx \
    https://huggingface.co/adiwajshing/vits-ljspeech/resolve/main/vits_ljspeech.onnx
fi
