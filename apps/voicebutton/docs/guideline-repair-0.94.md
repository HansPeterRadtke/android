# Voice Button 0.94 guideline repair evidence

The baseline audit read all 202 guideline files (including original transcripts and 32 GUI files), the complete first-party application and its shared audio/network/UI modules. Baseline Android revision: 12861798c28ba3ac68c11105615b45143f5b8a43. Relevant guideline content is unchanged between infra 030f20cd80eea5f21ee872b8252e93eea8224df0 and 15594e2e4460fb9237acf108eb4b92c4040ec60c. Repairs were made directly in the Nitro repair checkout. Existing unrelated dirty checkouts were preserved.

This document records implemented corrections, reproducible verification and remaining deployment/device boundaries. It does not certify every possible device or backend condition.

| Finding | Correction | Evidence / boundary |
| --- | --- | --- |
| F01, clipped main/player controls | Scroll information while keeping toolbar and primary recording/playback controls outside it | Native Robolectric production layouts at phone portrait/landscape, tablet portrait and 1x/2x font |
| F02, undersized targets | Minimum 48 dp controls, seek bar, library modes/Up and settings fields | Production view bounds and dialog action visibility assertions |
| F03, stale or missing state treated as current | Last-known labels, receipt age, unknown progress and refresh remedies | Offline layout cases; transcription parser regression |
| F04, automatic retry as the only remedy | Manual retry/send actions and explicit offline/local-preservation messages | Main menu and status implementation |
| F05, missing Stop/remaining time | Stop action plus Position/Total/Remaining display | Player production layout and timeline tests |
| F06, mandatory automation | Persisted upload/transcription/waveform controls, manual actions, worker gates and backend per-recording policy | Eight Jetson policy tests including real MP3 commit/restart. Live backend activation remains approval-blocked; Android requires explicit false acknowledgement before transferring audio when automatic transcription is off |
| F07, eager queue rows and hidden identities | Recycled ListView rows, stable queue slots, full identity/status through tap and accessibility | Long-queue layout and instrumentation assertions |
| F08, unbounded PCM backlog/drain race | Bounded pre-read admission, exact captured samples, wait for writer termination before final journal publication | Stalled-writer queue regression and recorder tests |
| F09, valid backup ignored | Load and restore valid backup before any manifest rotation | Corrupt-primary/paused-backup production store test |
| F10, invented remote durability proof | Require server identity and exact chunk length/hash proof before accepting committed state | Empty proof, wrong identity/hash and matching proof tests |
| F11, changed local audio adopted as trusted | Reject truncated/mismatched MP3 without replacing trusted ledger values | Production store truncation fault test |
| F12, fixed-rate MP3 duration | Derive frame duration from actual MPEG sample rate | 48 kHz ten-frame result is 240 ms; mixed-rate/truncated-frame tests |
| F13, unlink before replacement/ignored fsync | Atomic rename retains old target on failure; propagate directory synchronization errors | Injected rename-failure retention test and production store recovery test |
| F14, unsafe provider move | Verify source and destination content before deleting source, including unknown provider sizes | FileOperations implementation review |
| F15, quarantined work presented as complete | Keep quarantined sessions incomplete and show a recovery remedy | Failure classification and progress policy tests |
| F16, unbounded diagnostics | Bounded admission, dropped-event accounting and complete-line disk retention for all severity levels | BoundedLogFile retention tests |
| F17, no logging controls | Persist user-action logging and verbosity independently of essential error/recovery events | AppSettings and both diagnostic sinks |
| F18, synchronous trace work | Bounded asynchronous trace worker; memory/cache inspection also runs off the UI thread | Worker and call-path review |
| F19, tracked Studio credential | Read environment/private mode-0600 build configuration outside Git; remove literal from tracked build file | BuildConfig preserves current Studio compatibility. Historical revisions and already-built APKs still contain the previous client credential; this change does not rotate it or rewrite Git history |
| F20, invalid and partial numeric configuration | Finite validated settings candidate before save, runtime limit schema with ranges/defaults/units | NaN/infinity and boundary tests; runtime-configuration.md |
| F21, unbounded Studio data/cache | Explicit disk reserve/cache/response/image bounds, cancellation, serialized cache writes and active-file protection | Client implementation review and build/lint |
| F22, activity-owned sleep deadline | Service-owned monotonic persisted deadline with boot identity | Lifecycle call-path review; physical sleep/background timing remains device validation |
| F23, existing-file playback bypass during capture | Process-wide capture/playback gate; pause engine before opening microphone | Existing-final-file capture policy regression; actual engine/microphone transition requires a device |
| F24, timed Studio autoplay and stale callbacks | Intent-preserving open-at-position; cancel work/connections and recheck callback generation | Playback handoff/cancellation implementation review; native engine timing requires a device |
| F25, conflicting contracts/incomplete metrics | One current GUI contract and metric dictionary; historical documents identify superseding requirements | GUI_CONTRACT.md, player.md, runtime-configuration.md and screen-question-contract.md |
| F26, obsolete/tautological tests | Assert actual production crash output, proof validation, recovery behavior and rendered layouts | Unit tests plus permanent native Robolectric capture harness |

## Reproduction

Run on Nitro from the Android repository:

```
./gradlew --no-daemon --max-workers=2 :apps:voicebutton:testDebugUnitTest :modules:audio:testDebugUnitTest :modules:network:testDebugUnitTest :apps:voicebutton:lintDebug :apps:voicebutton:assembleDebug
```

Generated screen images and geometry reports are in `apps/voicebutton/app/build/reports/guidelines`. Lint and JUnit reports are in each module's standard build reports. The expanded matrix has 52 rendered screen/dialog cases: 18 base layouts, eight recorder operational states, long-queue and long-title states, and 24 menu/settings configurations. Dialog controls are checked for actual visible rectangles after layout and animation settlement.

The receiver implementation is committed in devtests master at d2404b7 (with implementation commit 21d8609). Eight isolated tests pass on Jetson, including real FFmpeg MP3 assembly and persisted disabled-transcription behavior after restart. Jetson's FFmpeg requires device access, so that test was run with the same root permissions as the service. Test storage is temporary and no live recordings were created.

## Deployment and verification limits

Automatic approval review rejected replacing the two live Jetson receiver files because explicit live-deployment authorization was required. The service was not restarted and remains version 2.9_canonical_visible_transcripts. To activate, install the committed durable_audio_automation.py and full_duplex_server.py, preserve unrelated staged/unstaged files, restart jetson-voice-audio-store.service and verify version 2.10_transcription_policy plus invalid-identity rejection. A controlled restart briefly interrupts receiver connections, which existing clients retry. Until then, disabling automatic transcription intentionally holds new audio locally instead of allowing the older receiver to ignore the choice.

No physical Android device was connected. Actual microphone routing, Bluetooth, TalkBack, OEM process management, native VLC handoff and long-running background/sleep behavior are not claimed as device-verified. The emulator attempt was not accepted as evidence because it repeatedly ANRed.

The publication script atomically writes versioned and latest APKs to Nitro Explorer and verifies copied bytes, retaining older versions. Installing the APK on a phone is a separate step; no phone installation is claimed.

## Final verification, 2026-10-03

The final Android unit run passed 178 tests: 94 application, 53 audio and 31 network, with no failures or errors. All 52 native-rendered screen/dialog cases passed minimum-target and viewport checks, including actual dialog action visibility after animation settlement. The initially blank dialog footer was a capture-before-settlement artifact; the final settled images show Save/Back correctly. Per-file percentages precede state text, so filename ellipsis cannot hide them. The test harness captures real production view trees without starting microphone capture or creating live server audio.

The APK retains package com.hans.android.voicebutton and the 0.93 signing certificate, with versionName 0.94/versionCode 94. The certificate SHA-256 is 31629973244747b972ec7c8e4e428f4090bee75e1d7e36ea3671c479a02e44e9. Current tracked files contain no copy of the private Studio credential, and the private build configuration has mode 0600.

Final APK SHA-256: `45277fbebae63a2f0f484902214833d0b840039f1e09d09ee584dd61ab1ce4b4` (205056193 bytes). The same bytes are used for the versioned and latest Explorer downloads.

Final combined Gradle invocation completed successfully (direct-repair-final-02.log). Lint reports zero errors and 2 warnings: SetTextI18n, UsableSpace. Dependency upgrades were not bundled into these correctness repairs.
