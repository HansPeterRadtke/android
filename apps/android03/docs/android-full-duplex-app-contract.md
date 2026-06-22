# Android Full-Duplex App Contract

## Current working dev URL from the Android app

`BASE_URL = http://127.0.0.1:13483`

This is the current tested phone path.

It requires this ADB reverse on G3:

`adb reverse tcp:13483 tcp:13483`

And this G3 relay:

G3 listens on `127.0.0.1:13483`.
G3 forwards to `10.8.0.3:13482`.
Jetson listens on `0.0.0.0:13482`.

Direct URL only if the phone can route to Jetson:

`BASE_URL = http://10.8.0.3:13482`

Use plain HTTP, not HTTPS, for this dev endpoint.

Jetson port `13482` is inside infra agent range `13000-13999`.
G3 relay port `13483` is the phone-facing dev relay port.
The old `18182` path is not the app target anymore.

## Endpoints

Health: `GET {BASE_URL}/health` returns `ok`, `service=no_omni_full_duplex`, and `model_ready=true`.

Start session: `POST {BASE_URL}/fdx/start` with an empty body returns `sid`.

Upload audio chunk: `POST {BASE_URL}/fdx/upload?sid={sid}&seq={seq}&final={0_or_1}` with `Content-Type: audio/wav` and complete RIFF/WAVE PCM signed 16-bit little-endian, mono, 16000 Hz.

Poll loop: `GET {BASE_URL}/fdx/poll?sid={sid}` every 100 to 250 ms. Download unseen chunk ids immediately.

Download reply audio: `GET {BASE_URL}/fdx/audio?sid={sid}&chunk={chunk_id}` returns WAV bytes, PCM signed 16-bit little-endian, mono, 16000 Hz.

## Full-duplex requirement

The app must run two independent loops. The uplink loop keeps recording and uploading while playback is active. The downlink loop polls, downloads, and plays reply audio without blocking or stopping the uplink loop.

Pass condition: the phone receives and starts playing reply audio before the final microphone upload has completed.
