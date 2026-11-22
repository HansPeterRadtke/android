# Offline typed-message admission with no voice-service credential

SWAAG's Android03 voice client requires a private authenticated WebSocket to
submit either voice or typed messages. The published application must **not**
claim to be connecting or silently queue a typed Send if its own build has
**no configured bearer credential at all**. The message must stay editable;
an attempt with no credential must not create a new request ID or contact
any service. The user should see a specific missing-credential explanation,
not a false “Connecting” state. Typed messages remain available for editing
while the service is unavailable.

Actual reproduced failure on an isolated, credential-free, airplane-mode
Android 14 emulator: enter a new text in the current message field and press
Send. The previous published Android main `d5b1cfce` displayed **“Connecting”**,
with **“Speech is not being sent yet.”** There was no valid voice-service
credential or network, so the app could not possibly complete this operation.
The text **survived a real app force-stop and relaunch**. No live agent work
was attempted; this was a user-feedback and state-admission defect.

The corrected `MainActivity` checks that the bearer is configured **before**
arming any disconnected typed submission. If absent, it leaves the draft and
any previously persisted idempotent request ID untouched, clears any in-memory
pending flag, disables automatic connection retries, and displays the existing
**“Connection unavailable”** headline and the specific
**“This private voice build has no service credential ... no request was sent”**
explanation. The Send button stays usable for a future explicitly retried
attempt once a properly provisioned, trusted build is installed. When a
credential *is* configured, existing connection and durable request ID rules
continue unchanged.

The opt-in `NoCredentialSubmitUiTest` is a **real Android Activity test** that
checks the missing-credential Send interaction, visible status, editable
text, operation ID admission, activity recreation and preservation of a
previously committed typed request ID. It is skipped unless the private host
passes `offlineNoCredentialAcceptance=1` and the app's build has an empty
credential. It must run only on a fresh, isolated emulator with network
blocked; never on the owner's actual phone or another worker's emulator.

Private evidence is stored under
`/data/var/swaag-benchmarks/android03-typed-noauth-20261122/`.
This confirms offline/absent-credential UI behavior, **not** real SWAAG tool
execution, authenticated speech, physical microphone behavior or readiness to
deploy. The production Jetson server and all other mobile apps remain
untouched.

## Actual measured no-action acceptance, November 22

- **Before correction:** The previously published credential-free app was
  installed only on the new Android 14 emulator in airplane mode. Entering
  `OfflineNoAction8235` and pressing its real **Send** button showed
  **“Connecting”** and **“Speech is not being sent yet”**; the app had no
  bearer and could never connect. The draft survived a real Android app
  force-stop/relaunch, and no typed request ID was created. This reproduced
  the feedback defect in a genuine Android UI, not a mocked view.
- **After correction:** The private APK, built with
  `VOICE_AGENT_AUTH_TOKEN=''` and SHA-256
  `18399c63c7d17725b61c7e01090110b656f0d9df4b8dd00b553acd808686801f`,
  completed **49 offline Android03 JUnit tests with zero failures**. A new
  source-backed **two-case Android 14 instrumentation run** independently
  reported **“OK (2 tests)”**. Those tests exercised actual `MainActivity`
  creation, explicit no-credential Send, local text retention, Activity
  recreation, and preservation (without blind replay) of an older request ID.
- A separate **manual Android UI acceptance** entered the distinct text
  `FinalNoAuth8290` and pressed Send on the same isolated, offline emulator.
  The verified post-fix UI now showed **“Connection unavailable”** and
  **“This private voice build has no service credential. Connect using a
  trusted private build; no request was sent.”** The exact draft remained
  editable, Send stayed available for future explicit attempts, and the
  local application preference contained **no newly created request ID**.
  The same draft remained intact after a full Android app force-stop and
  restart, with Send still available.

Private reproducible evidence, original and corrected UI accessibility XML,
source-hash-proven APKs, Android instrumentation stdout, Gradle unit-test
results and source-only proof are in
`/data/var/swaag-benchmarks/android03-typed-noauth-20261122/` on Nitro.
Only the disposable emulator was accessed. There was no owner-phone install,
shared emulator change, real speech capture, SWAAG/LLM/TTS call, live server
restart or provider account effect. The emulator was deliberately offline and
has no voice-service credential; the work cannot verify a real authenticated
voice conversation or safe production release.


## November 22: superseding current credential provisioning

The previous build-time `BuildConfig.VOICE_AGENT_AUTH_TOKEN` provision is
**historical only** and must NOT be used for new APKs. The current source
loads a bearer only from separately configured AndroidKeyStore-protected
private device storage, and no credential is embedded in an APK. See
`voice-device-credential-2026-11-22.md` for the new implementation, opt-in
Android acceptance and remaining server/physical-phone release gates.
