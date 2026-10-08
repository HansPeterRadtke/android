# Voice Agent infra-guideline compliance audit — 2026-10-06

Scope: Android `android03`, the Jetson WebSocket/STT/TTS glue used by that client, Nitro LuxTTS, and the read-only SWAAG status/question projection. SWAAG itself is explicitly outside the implementation scope and remains the authority for conversation, orchestration, workers, questions, tools, and task execution.

## Documentation coverage

The complete `/data/infra/docs` tree was inventoried before this audit: 236 files, including normalized guidelines, preserved source recordings, operational documentation, system descriptions, speech documentation, and project reports. The normalized guideline tree was treated as normative according to its own authority rules, with the preserved recordings used as source evidence. All agent and GUI guidance was reviewed, plus the cross-cutting security/privacy, networking, configuration, performance/reliability, logging, testing, programming, source-traceability, speech, system-description, SWAAG-launch, and voice-audio operational documents that can constrain this application.

Trading-, finance-, chart-, table-, game-, web-, presentation-, mail-, video-, and LLM-benchmark-specific requirements were classified as non-applicable to this Android voice interface except for universal rules that are already represented in the core agent/GUI/testing/security guidance.

## Architecture and ownership

- SWAAG remains the working agent and orchestration authority. The voice layer forwards finalized user turns to SWAAG; it does not run a competing reasoning loop or conversation authority.
- The Jetson worker/status stream is a read-only projection of SWAAG's global orchestrator state through `/orchestrator/events`. It does not maintain an independent worker/question database.
- Android renders compact background-work state plus a dedicated question field backed by SWAAG's complete read-only question inventory. Blocking/critical questions are ordered first; the highest-priority exact question is previewed on the main screen and tapping the field opens every exact outstanding question with worker identity, criticality, importance, reason, and provisional assumption. SWAAG remains the sole question authority and the same questions remain available through the normal orchestrator conversation.
- Replay is media inspection and does not cancel SWAAG work. Semantic interruption is authoritative; local VAD is only capture/UI evidence and cannot itself cancel a spoken answer.
- Cancellation of an intentional interruption propagates to the SWAAG run rather than only terminating a local waiter.

## User-input and transcription behavior

- The current editable message is separate from submitted history and behaves as a normal Android text editor, including cursor positioning, selection, insertion, deletion, replacement, cut, copy, and paste through platform behavior.
- Typed fallback remains usable when microphone permission, STT, TTS, or the voice path is unavailable.
- Automatic transcription can be disabled. Automatic send can be disabled and is forced off when automatic transcription is disabled.
- Fully manual mode records locally, exposes the user's unsent audio, requires explicit transcription, allows editing, and requires explicit Send.
- A provisional ASR partial can update the editor but is marked unstable and cannot be submitted. A real user edit takes ownership of that visible text so a later ASR update cannot silently overwrite the correction.
- Exact typed or user-corrected submission text is preserved across Android -> Jetson -> SWAAG. Whitespace normalization is used only to decide whether input is semantically empty; it is no longer applied to the retained submitted text.
- Correction learning is based on retained submitted corrections and explicit vocabulary rather than raw keystroke history or discarded intermediate ASR text.

## First-screen and Android UI compliance

- A written Module Purpose Contract and Screen Question Contract exist in `GUI_CONTRACT.md`.
- The first view is question-first: primary readiness state at the top, conversation as the dominant surface, contextual audio, then a persistent editable composer with Mic/Stop and Send. Settings and diagnostics are secondary.
- Healthy backend component detail is hidden. Degraded component state is surfaced in human terms rather than existing only in logs.
- The primary state no longer says `Ready` when the voice path is unavailable. Idle degraded operation now becomes `Text only`, `Connecting`, `Permission required`, or `Connection unavailable` according to the actual dependency state. Typed input remains available in the text-only state.
- User and assistant history are textually distinguished and therefore do not rely on color alone. Agent messages are not editable in normal operation.
- Main controls use at least 48 dp targets. Text uses Android scalable text sizing. The Settings icon and player seek control have accessibility descriptions where visible text alone is insufficient.
- Wide/short and large-window layouts use app-window configuration rather than raw physical screen pixels. The composer remains pinned while secondary audio can scroll independently.
- The microphone is explicitly user-controlled: `Mic` begins capture and changes to `Stop`; Stop tears down active capture/foreground recording service. The microphone is not permanently open merely because the app is connected.
- Unsent draft text and retained manual recording state survive Activity recreation. Active user-requested recording uses a foreground service rather than relying on unrestricted background execution.

## Playback and interruption

- Both user and assistant PCM players provide play/pause/resume, Stop, seek, backward/forward jumps, current position, remaining time, and total/available duration.
- Streaming reply audio keeps the player usable while the available duration grows.
- Manual Stop is immediate. Acoustic activity alone does not cancel assistant playback; a semantic interruption decision is required.
- Replay of prior user or assistant audio is separate from conversational interruption and cannot cancel an unrelated active SWAAG run.

## Networking, security, and reliability

- The public voice path uses WSS through the existing managed proxy.
- WebSocket upgrades now require an explicit Bearer credential. The committed configuration contains only the credential-file path; the credential itself is not in Git or ordinary logs.
- Jetson stores the deployed credential at `/etc/voice-agent/ws.token`, mode 0600, owned by the service account that must read it. Android receives the matching value only through build-time `VOICE_AGENT_AUTH_TOKEN` injection; source does not contain the secret.
- Public verification rejects an unauthenticated WebSocket upgrade and accepts the same endpoint with the configured credential. The health endpoint remains unauthenticated for monitoring and exposes no credential.
- Connection ownership uses generations so stale sockets cannot mutate current UI state. Heartbeat/media acknowledgement monitoring, reconnect backoff, bounded queues/buffers, and single-session-per-conversation behavior are explicit.
- Jetson reaches Nitro LuxTTS only through the authenticated SSH tunnel bound at `127.0.0.1:15304`; the voice server no longer sends LuxTTS HTTP directly across the LAN. The dedicated tunnel service is enabled and active, and the voice configuration points both LuxTTS synthesis and health checks at that localhost tunnel.

## TTS latency and continuity

- LuxTTS exposes a framed streaming endpoint. Its text chunker now preserves sentence boundaries and bounds long unpunctuated chunks instead of merging ordinary replies back into one large synthesis unit.
- Live measurement proved multiple independently arriving PCM frames. The Jetson WebSocket begins forwarding first PCM before later reply speech has finished synthesis, so streaming is real rather than post-generation chunking.
- The normal Jetson path retains the local Piper fallback if the Nitro LuxTTS service fails.
- Live fallback acceptance was exercised by deliberately stopping only the LuxTTS SSH tunnel. Health remained usable with `tts_primary=false`, `tts_fallback=true`; a real authenticated SWAAG turn returned a non-empty assistant answer, started `piper_en_US-lessac-medium_16000` audio, delivered the first 1,920-byte PCM frame at 10.437 s, and completed audio at 13.340 s. The tunnel was then restored and health returned to both primary and fallback TTS ready.

## Automated evidence obtained

- Combined voice gateway/orchestrator-projection/server/config/core/direct-SWAAG/LuxTTS suite: 101 tests passed on the current clean server head, including exact question projection/forwarding and primary/fallback TTS-health semantics.
- Android JVM suite: 32 tests passed with zero failures, errors, or skips on the current 1.7.10 source.
- The final debug APK builds with a non-empty injected credential while source control remains credential-free.
- The current physical-phone acceptance artifact is `android03-voice-agent-1.7.10.apk`, versionName 1.7.10 / versionCode 18. The exact tested app APK has SHA-256 `389303a1911a737a9c8c7fdcdb11c1a745b4672f44cdb820fa7456e947997641`.
- The authenticated download root exposes the current 1.7.10 acceptance APK; older acceptance/pre-auth APKs are retained outside the primary download slot for rollback/audit rather than being presented as the current install.
- The Android build accepts credential injection by environment variable or explicit Gradle credential-file property and has a Nitro-only runtime fallback at `/data/var/voice-agent-build/credential`; that file is outside Git and mode 0600. This avoids putting credential material in source or command output.
- Public WSS smoke: unauthenticated upgrade rejected; authenticated upgrade accepted and returned the normal voice-agent hello.
- A full authenticated public WSS transaction also passed: the exact typed submission was echoed unchanged, SWAAG produced a non-empty assistant answer at 8.801 s, the first 1,920-byte PCM frame arrived at 11.697 s, and the audio stream ended at 16.900 s. This proves the public proxy path carries authenticated text -> SWAAG -> streamed TTS end to end.
- Jetson health reports server, primary/secondary STT, SWAAG agent, and TTS dependencies healthy.
- Jetson production now executes the pushed voice server commit from the clean `/data/src/worktrees/voice-infra-gaps-20261003` deployment worktree rather than the dirty historical `master` checkout. The clean worktree was first started on a spare port and returned healthy server/STT/SWAAG/TTS status before systemd was switched; the production process command line and working directory were verified afterward.
- Thor's global orchestrator projection was checked directly; at audit time it reported zero active workers and zero open/blocking questions, demonstrating the live read-only question/status path.
- The live Thor-to-Jetson wire schema now carries `question_inventory_complete` and the exact `questions` array. With no outstanding questions the production path was verified to report a complete empty inventory; non-empty criticality-sorted records are covered by gateway, Jetson-forwarding, and Android UI integration tests.
- All 15 current Android instrumentation tests have passing evidence on the API 34 emulator. The ordinary online run completed with every executable case passing and the offline-only case assumption-skipped; that offline case then passed separately with both emulator radios disabled. The expanded suite includes exact-question inspection, accessibility live regions, accessible player controls, process-death recovery, manual recording background/foreground continuity, and streaming-player behavior.
- Dedicated offline acceptance was then run with emulator Wi-Fi and mobile data disabled and its cached-history/latest-navigation test passed.
- The primary-screen suite passes at Android font scale 2.0, including the dedicated exact-question field, its full details dialog, accessibility live regions, and touch-target checks.
- The primary-screen suite also passes in forced landscape at normal font scale, including exact-question inspection. Emulator font scale, rotation, Wi-Fi, and mobile-data settings were restored after verification.
- The same six core UI/player tests also passed in a tablet-sized portrait window (800 x 1280 dp equivalent), then the emulator display override was restored.
- The current seven primary-screen/streaming-player tests also passed in a deliberately wide tablet-landscape display, exercising the two-pane branch; the emulator display/orientation overrides were restored afterward.
- A dedicated accessibility regression now requires accessible names on Settings, current-message, Mic/Stop, and Send plus at least 48 dp touch height on the primary buttons; it passes on the clean API 34 AVD.
- A dedicated process-death regression now writes an unsent draft and retained manual PCM recording, terminates the app process, cold-launches the Activity, and requires both the draft and the complete user-audio player/Transcribe path to be restored; it passes on the clean AVD.
- Microphone permission was revoked on the emulator: the first-screen status became `Microphone permission required`, explicitly stated that typed messages still work, and the conversation, editable current-message field, Mic, and Send controls remained reachable. Permission was restored afterward.
- Emulator Wi-Fi and mobile data were disabled during an active client session: the primary state became `Reconnecting` with an explicit server-unreachable explanation; after connectivity was restored the state returned to `Ready`.
- Jetson production logs contain an authenticated `Voice Agent 1.7.9` Android session with 99 binary PCM frames / 190,080 media bytes received from the built-in-microphone path, providing end-to-end emulator evidence that the APK sends real microphone frames through the public authenticated WebSocket rather than only exercising UI state.
- An unsent editor draft remained present after a forced application stop and relaunch, providing additional process-recreation evidence beyond the Activity-recreation instrumentation test.
- A dedicated lifecycle instrumentation test verifies that user-started manual recording remains active across Activity background/foreground transitions and can still be stopped normally after resume, exercising the foreground-service ownership path. Its setup now establishes manual mode before Activity launch; the corrected lifecycle class passes all four tests.

## Remaining release gates and non-violating limitations

- Physical-phone acceptance build is version `1.7.10` / versionCode 18, superseding the authenticated 1.7.9 acceptance build.
- A physical-phone acceptance pass is still mandatory. Emulator, unit, and server evidence cannot prove real microphone routing, speaker behavior, Bluetooth/OEM audio routing, acoustic semantic interruption, mobile-radio loss/recovery, background restrictions, thermal behavior, or real-device lifecycle behavior.
- Complete screen-reader acceptance is not yet proven. Maximum supported font scale, portrait/landscape/tablet-sized layout, accessible names/touch targets, permission revocation, network loss/reconnect, and process-death recovery of the unsent draft plus manual audio now have emulator evidence. TalkBack is not installed in the current AVD, so real TalkBack traversal/announcement quality and physical-device accessibility behavior remain unverified. Foldable-specific postures also remain untested.
- The dedicated Android question channel is now implemented as a read-only projection of SWAAG's complete exact inventory; it does not duplicate question ownership or resolution state. The normal orchestrator conversation remains an equally valid way to review or answer those questions.
- Conversation history currently renders into one selectable text surface with explicit role labels. This satisfies current role distinction without color dependence, but per-message accessibility semantics and very-large-history rendering remain reasonable future improvements if actual history size or TalkBack testing shows a need.
- The Android build-time Bearer token is possession-based application authentication, not per-device identity. It closes the current unauthenticated trust-boundary violation, but credential rotation and per-device provisioning remain separate security concerns if the threat model later requires them.

## Release decision

Do not call the voice client fully release-verified until the physical-phone/accessibility/failure-path acceptance above is completed. The ordinary emulator suite, dedicated offline acceptance, two-hundred-percent font-scale checks, and forced-landscape checks are now green. The implemented architecture itself now matches the applicable agent/voice/UI ownership, fallback, input-stability, interruption, playback, readiness, authentication, configuration, and streaming requirements reviewed in this audit.
