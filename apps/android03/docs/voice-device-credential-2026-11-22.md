# Android03 private voice credential: keep it off the installed APK

## User problem and verified source boundary

The November 21 Android03 client could accept `VOICE_AGENT_AUTH_TOKEN` from a
Gradle build environment and compile it into the generated `BuildConfig`
field. That is not appropriate for distribution: an APK's dex classes are
extractable, and a copied bearer authorizes access to the current Jetson voice
WebSocket regardless of the speaker's identity. The corrected source removes
this build-time injection completely; it does not provision any actual owner
credential or change the running server.

## Corrected device flow

The same Android03 `myapp.app` APK can be installed on a device without a
service password. Its Settings page now has a clear **Private voice
connection** section. **Set up a private connection key** opens an obscured,
non-autofill, non-personalized-learning input dialog protected from Android
screenshots. The owner can paste the current trusted private Jetson voice
service bearer once. The app validates the key's bounded printable format,
encrypts it using **AES-256-GCM** with a **non-exportable AndroidKeyStore key**
under the app identity, and commits only ciphertext and a random IV to the
app's private SharedPreferences. The UI never shows the credential again, or
logs any part of it. The WebSocket request reads the protected value at
connect time, inserts it **only** into the `Authorization: Bearer` header,
and still enforces the previously published pinned `wss://` host/path and
redirect restrictions. If Keystore storage, decryption or validation fails,
there is no outgoing handshake. No authentication secret comes from Gradle,
`BuildConfig`, an app resource, a URL, a server health check or a test file.

Settings can explicitly **Replace private connection key** or **Remove
private connection key**. Both operations close the previous authenticated
connection before a replacement handshake. Removing a key disables further
attempts, preserves typed drafts and old request identity so the user cannot
unknowingly lose messages, and prevents a background reconnect loop. The
status explains when no credential is configured rather than saying that a
private APK build is required. A previous APK compiled with an embedded
bearer must be treated as potentially extractable; its key may require
rotation. This change does not copy an old build-time token into the new
installation or change any saved phone data without an explicit user action.

An Android backup may restore encrypted preferences without the Keystore
key; that is intentionally unreadable and fails closed, requiring explicit
re-provisioning. This is at-rest protection against APK disclosure and
accidental backup/log exposure, not a guarantee against compromise of a
rooted phone, its running process, or an already leaked global server bearer.
The current server's bearer still authorizes the *device*, not the *speaker*.
Trusted owner-speaker enrollment and anti-replay must remain server-side
requirements before voice auto-submission is enabled. The existing client
manual-review and typed-request idempotency gates are preserved.

## Acceptance and deployment boundary

Source tests inspect the canonical APK and its generated `BuildConfig` for
the absence of any `VOICE_AGENT_AUTH_TOKEN` field, including when a harmless
**canary** is deliberately set in the Gradle environment. Full Android03
unit tests, credential-free existing Android tests and explicit Android
Keystore instrumentation tests on an isolated, offline Android14 emulator
must all pass. The instrumentation uses a **fake test key**, verifies that
sealed SharedPreferences contain no plaintext, that the Keystore AES key's
raw bytes cannot be exported, that repeated saves use distinct ciphertext,
that tampering fails closed, and that setup/removal through the real Android
UI works. A fresh Android process must still recover the credential only
from device-local secure storage, not from the APK. The test emulator never
connects to Jetson, has no real microphone/audio, and uses no owner token.

A private APK and test suite do **not** constitute a production install.
The current Blackview runs a different active ReverbProbe app, and the
Android03 voice client is not installed there. Before a phone rollout,
prepare an independently verified per-device token provisioning/rotation
process, confirm the intended real user phone, obtain compatible reversible
server release/rollback, and test actual microphone input, transcriptions,
manual confirmation, speech reply, playback completion, barge-in, long-term
reconnect and genuine owner-only authentication. Preserve the existing user
recordings and other workers' apps. Do not commit owner tokens, owner audio,
private raw log contents, or a signed APK containing private credentials.

## Verified offline security tests and UI acceptance status, November 22

A fresh source build, with the distinguishable **fake environment canary**
`SWAAG-CANARY-NOT-A-SECRET-20261122-BLOCK-EMBED`, passed **49/49 genuine
Android03 unit tests**, built the Android app and Android instrumentation
APK, and completed successfully on the corrected second Gradle attempt.
The first attempt failed *only* Android resource parsing for two apostrophes
in new user-facing Settings labels; that failure is retained as separate
private proof, and both strings were corrected before the successful build.
The finished APK has **889 entries**; independent byte-by-byte inspection of
all entries found neither the old build-time `VOICE_AGENT_AUTH_TOKEN` field
nor the supplied fake canary. This verifies the binary secret-embedding
regression, not real server authentication.

Three **real Android 14 emulator instrumented tests passed** on a freshly
installed APK in a private disconnected emulator with no default route.
They verified a non-exportable Android KeyStore AES-GCM key, encrypted
app-private preferences without plaintext, randomized ciphertext on
repeated saves, fail-closed tamper detection, invalid input rejection and
clear/removal. No real voice service, actual owner secret, phone or model
was contacted. Logs and receipts are private on Nitro under
`/data/var/swaag-benchmarks/android03-keystore-20261122/`.

The first **real settings-screen instrumentation test did not pass**:
Android's system UI froze before the first Settings button interaction,
and Espresso reported `RootViewWithoutFocusException`. The emulator then
showed an operating-system `Application Not Responding` dialog, which
persisted after one isolated device reboot. The original failure evidence
and system UI screenshots are retained; **do not claim Settings setup and
removal were verified** until a later clean Android UI run passes. The
working KeyStore itself was verified independently through its native
instrumented test. The tests are intentionally opt-in, and a GUI failure
must never be confused with a successful key enrollment.

**Not an approved release.** The actual Blackview, the Jetson production
voice service and other agents' Android emulator remain unchanged. This
source fixes APK disclosure of a bearer. It does not solve the lack of
owner voice authentication, anti-replay, real physical audio testing or
privileged API token rotation. Never provision the owner server key in a
public artifact or make an unsolicited connection while doing no-action
acceptance.
