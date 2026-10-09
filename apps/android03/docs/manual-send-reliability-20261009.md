# Voice Agent manual-send reliability — 2026-10-09

## User-observed failure
On a physical phone, the prior 1.7.11 build frequently failed to submit speech and disabled the visible Send control. The previous broad health/compliance report did not test this failure adequately. An active WebSocket and healthy STT/model/TTS components alone did not demonstrate a successful user turn.

## Confirmed source defects
- VoiceDraftPolicy disabled Send whenever the text was an ASR partial or a network send had been queued. If final ASR was suppressed, the UI could leave valid text unsendable.
- The server sent an explicit `asr/artifact` event, but Android did not clear the rejected tentative draft; it remained visibly present in a disabled state.
- The reconnecting `hello` callback invoked a method that manipulates Android Views directly from the OkHttp listener thread.
- Subsequent ASR partials could overwrite text typed from scratch. An unrelated auto-submitted speech turn could erase the user's current typed draft on its acknowledgement.
- A client-side WebSocket `send()` success was treated like completed delivery. Transient failures could leave text unacknowledged without a reliable retry.
- The large-v3 verifier on Thor exceeded the live 30-second HTTP timeout. Jetson incorrectly treated every unverified long utterance as noise, even when its faster recognizers provided usable text.

## Implemented safeguards
- Nonblank text always has an enabled explicit Send control. Tapping Send claims even a partial ASR draft as user-confirmed text; automatic ASR cannot later overwrite it.
- Rejected tentative-only ASR drafts are discarded; human edits are never discarded by the artifact event.
- All submission UI access occurs on Android's main thread.
- A draft is cleared only after the server's `turn/submitted` acknowledgement matches its exact content. Other spoken turns cannot clear unrelated typed text.
- An in-flight send remains visible. After 15 seconds without acknowledgement, retry becomes possible while preserving the original text.
- New typed turns carry a per-message random client request ID. Jetson durably associates it with a turn ID, returns duplicate acknowledgements for retries, and does not run the same SWAAG task twice or cancel it on a retry.
- The large-v3 dependency is capped at eight seconds for Jetson's optional verification call. If unavailable, usable fast-ASR text is retained; conflicting low-confidence text requires manual Send rather than automatic execution. Non-speech annotations remain rejected.

## Verification
- Jetson runtime deployed from `devtests` commit `e724aef`; service and all seven component health flags true.
- Public endpoint rejects an unauthenticated WebSocket upgrade with HTTP 401.
- Authenticated live typed-turn probes received a `turn/submitted` response. A repeated request ID received `duplicate: true` for the same turn.
- Server regression suite: 65 passing tests.
- Android first three focused emulator tests: 3 passing. The fourth test intercepts actual Android outbound `text_turn` JSON, checks that the button stays enabled, that an awaiting-ACK second tap does not duplicate the request, that an ACK clears only matching text, and that a later independent message uses a fresh request ID: 1 passing on 1.7.12.
- Android 1.7.12 (versionCode 20): 34 JVM tests with zero failures, debug APK and debug-androidTest APK both assembled successfully.
- Physical-phone install artifact: `android03-voice-agent-1.7.12.apk`, SHA-256 `54ca2ec02284e2fbc7f3e0aff93e41ac28c6cc48a4d86683d2b0c0619c616b83`.
- Android build uses the canonical Nitro private credential injection, never writing the token into Git or test logs.

## Physical-device release gate
The Android APK must be installed on the actual phone and tested for typed Send, auto speech, noisy-background suppression, reconnection, Bluetooth/microphone routing, and interruption. This remains **unverified**; tests above must not be presented as proof of physical-phone acceptance. An APK with the injected WebSocket token is credential-bearing and must be distributed only via an authenticated/private channel, not as an anonymous public download.
