# Voice Button 0.96 — 0.95-preserving reliability and security release

Date: 2026-10-10. Scope: Android recording app, **not** the separate voice conversation app.

## Release source and retained behavior

This release starts from verified Voice Button 0.95 at commit `a8d01e1`. The 0.95 compact layout, 59-configuration rendering evidence, fixed-size touch targets, hidden empty operation queues, full recording/upload/transcription status, dynamic portrait/wide layouts, and the playback service's persistent sleep timer are preserved. The simulator is still a real Android foreground recording service and not the unrelated AI voice-agent app.

## New fixes

- The existing bounded PCM writer queue and wait-for-drain logic from 0.95 are retained. On completion the recorder now explicitly compares captured sample count, writer-completed sample count and durable PCM byte length before publishing a closed journal. Mismatch or writer failure leaves a recoverable open journal, not a falsely labeled complete file.
- An unreadable recording manifest is represented as a visibly recoverable, non-mutating Library item instead of silently vanishing. Its synthetic placeholder is not treated as resumable and must be excluded from upload workers. Original audio/metadata bytes are never deleted by creating this listing.
- Service status restoration revalidates the known current session ID against durable storage after an incomplete background session scan, instead of accepting a spurious empty set as proof of Ready. Pausing/finalizing state is protected during asynchronous reconciliation.
- Swiping away the app task and pressing Back no longer deliberately shut down protected capture. The foreground recording service is not task-scoped. Finish from a notification opens the existing destructive-action confirmation dialog.
- `MobileAudioCredential` stores the Jetson recording API token and Thor Studio renderer token in app-private, non-backed-up preferences. The APK contains neither secret. An ordinary Studio operation reports a useful configuration error when not paired.
- Authorized `X-VoiceButton-Token` is included in every recording API request (uploader, folder creation, summary/transcription reads and manual transcription), and credential changes wake the current upload worker. Users pair from More → Recording server access or More → Studio player access.
- Version code 96 / 0.96. All original 0.95 GUI and scheduling acceptance contracts still apply.

## Staged server security

The production Jetson server already has a provisioned secret at `/data/var/voice_agent_live_runtime/secrets/voicebutton_api_token`, checked by `voicebutton_access.py`. The active Cloudflare ingress returns 404 for public `/audio/v2/file` and detailed `/audio/v2/status`. Anonymous folder listings receive 401; anonymous transcription overview returns only numerical aggregates. Valid private-token requests retain full functionality.

**Strict authorization is deliberately not enabled for the remaining legacy upload routes** while an unpaired older phone may still have unsent recordings. Once an up-to-date phone is paired and its real upload/transcription is verified, set `VOICEBUTTON_REQUIRE_TOKEN=1` on Jetson's `jetson-voice-audio-store.service`, restart it, and verify authenticated GET/POST and anonymous 401. Verify no queued audio is stranded before enforcing; otherwise leave transition mode active.

The separate old Studio token is on Thor at `/data/var/speech_mobile_player/token`. Rotate it after the updated Android app is paired and rendering works. Do not expose either token in source, telemetry, publication pages or test artifacts.

## Validation required before publication

- Successful 0.96 Gradle build, all audio/network/app unit tests, `:apps:voicebutton:lintDebug` and the 0.95 retained Robolectric rendered geometry tests.
- New focused tests: captured versus written PCM invariants, unreadable metadata placeholder, durable Paused recovery, and authenticated real HTTP request header.
- APK audit: application ID unchanged, version 0.96, no compiled `THOR_PLAYER_TOKEN`, matching published SHA-256, consistent signing certificate with installed 0.95.
- **Still requires actual phone acceptance:** long capture while backgrounded/task-swiped, sleep/wake/reboot, Pause/Resume/Finish under slow storage and poor connectivity, microphone routing/permissions, final transcription/Studio pairing, 200% font size and short/landscape device. A passing simulated UI does not prove OEM foreground-service behavior.

## Source and privacy constraints

Never replace the running phone app by uninstalling it: uninstall could remove locally stored recordings. Install the APK as an *update*. Original production recordings are not to be accessed/deleted during development. Never enable strict auth while an old unpaired client might have pending audio. The scope of this release does not include SWAAG or other Android apps.

## Delivery

Canonical build: `apps/voicebutton/app/build/outputs/apk/debug/voicebutton-debug.apk`.

Explorer canonical published artifact: `/data/var/web_portal/uploads/voicebutton-debug.apk`. Update only after verifying tests and exact bytes; retain a versioned rollback APK in a private local release folder, not in the public app listing.

