# Android Full-Duplex App Contract, Corrected

## Direct Jetson target

`BASE_URL = http://10.8.0.3:13482`

Use this when the phone can route to Jetson's tunnel address.

If the phone is on Jetson's physical LAN instead, use:

`BASE_URL = http://192.168.8.52:13482`

Do not use G3 in the Android app. Do not use ADB reverse in the Android app. Do not use `https://jetsonsystem.jimmyandjonny.work/fdx` right now; that public URL currently routes only the System Server and returns 404 for `/fdx`.

## Server

`full_duplex_server.py --host 0.0.0.0 --port 13482`

Port `13482` is inside the infra agent range `13000-13999`.

## Health

`GET {BASE_URL}/health`

Expected: `ok=true`, `service=no_omni_full_duplex`, `model_ready=true`.

## Start session

`POST {BASE_URL}/fdx/start`

Body is empty. Response contains `sid`.

## Upload audio

`POST {BASE_URL}/fdx/upload?sid={sid}&seq={seq}&final={0_or_1}`

Header: `Content-Type: audio/wav`.

Body is complete WAV bytes, including header: RIFF/WAVE, PCM signed 16-bit little-endian, mono, 16000 Hz.

Do not send raw PCM. Do not send Opus, AAC, MP3, Ogg, WebM, or MediaRecorder compressed output.

## Downlink poll

Run this in a second thread while upload continues:

`GET {BASE_URL}/fdx/poll?sid={sid}`

Poll every 100 to 250 ms. When `audio_queue` contains a new `chunk_id`, download it.

## Download reply audio

`GET {BASE_URL}/fdx/audio?sid={sid}&chunk={chunk_id}`

Response is `audio/wav`, RIFF/WAVE PCM signed 16-bit little-endian, mono, 16000 Hz.

## Full-duplex rule

One thread records and uploads WAV chunks. A second thread polls and downloads reply WAV chunks. Playback starts immediately and must not stop the microphone upload thread.

## Pass condition

The phone starts playing downloaded reply audio before the final microphone upload completes.
