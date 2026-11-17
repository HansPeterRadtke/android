# Canonical Android voice draft review after reconnect — November 17, 2026

**Later same-day emulator acceptance:** See
voice-review-emulator-noaction-2026-11-17.md for seven actual
Android Activity/UI tests, an additional independently reproduced
stale saved-recording retry-target defect and its source fix.
This remains isolated from the physical Android phone and SWAAG.

**Status:** isolated source repair and offline unit/build verification.
This has not been installed on a physical Android device or released
to the live Jetson voice server. Previously denied Android lost-playback-
ACK instrumentation was not repeated, disguised or routed around.

## Proven source-level problem

The server's durable voice history design explicitly restores the
LATEST unfinished speech draft as a manual-review message, even when
automatic sending is the phone's global preference. This is necessary
when the semantic turn classifier is unavailable or the transcript is
ambiguous: an incomplete draft must not authorize automatic execution.

The prior Android MainActivity source did the opposite when rebuilding
history. It set pendingTurnId to an EMPTY identifier whenever the local
auto-transcribe and auto-send preferences were both enabled. It also
selected any historical draft even if a newer completed entry followed.
The history rendering callback can run after the server's restored-final
and awaiting-send events, so that blanking can erase the review identity.
An older draft can also appear in the composer despite being superseded.

A small pure-Java VoicePendingDraftRecovery helper was initially
extracted with precisely the previous behavior. Three new JUnit
regressions failed against that unchanged baseline:
- a latest unfinished draft was lost in automatic mode;
- an old draft was selected ahead of a newer completed turn;
- an empty draft was considered reviewable.
The exact initial failure log is retained privately on Nitro.

## Bounded correction

MainActivity now restores a pending review identity only when the
canonical history row is the LAST row, its status is draft and its
recognized text is nonempty. This rule does not depend on whether
automatic sending is normally enabled. It does NOT submit that draft:
the existing Android Send control still requires an explicit tap, and
the server requires the matching canonical draft and rejects stale
or invalid submissions.

Existing user-entered text and correction ownership remain protected.
The source still refuses to replace an explicitly claimed local draft
with an unconfirmed ASR suggestion. Historical completed, discarded,
failed, empty and unrecognized-audio entries do not become a new
reviewable draft.

The fix also removes the race in which processing an earlier history
callback could clear pendingTurnId after the later restored-final
event had set it. Both paths now retain the same canonical latest
draft identity. This is a source-level/protocol correction, not a
claim about on-device rendering or speaker identity.

## Observed verification

The original extracted policy failed all THREE focused tests. After
the correction, all three passed. The complete offline Android
testDebugUnitTest suite then passed **81 tests with zero failures
and zero skips**, and assembleDebug built a debug APK. No model,
phone microphone, background capture service or live SWAAG agent
was touched.

The paired Jetson source branch also ran a different continuous
no-action local acceptance: a checksum-verified original speech WAV
was recognized by real resident Whisper, reviewed and explicitly
confirmed to an inert local answer stub. A pre-existing reply WAV
was streamed with interruption and normal acknowledgment across
four loopback WebSocket sessions. Three independent runs passed,
and the 181-test Jetson server suite passed with 16 intentional
optional/opt-in skips. This does not replace physical Android
acceptance or the previously denied lost-ACK case.

The Nitro source and private tests are in a dedicated versioned
development branch. Exact build/test logs remain outside Git under
the owner's local private audit area. No original audio or tokens
are committed.

## Release boundary

Production Jetson still runs the older October source. The
dedicated Thor semantic classifier is unavailable, and real
foreground-speaker identification, interruptions and overlap
need permitted acceptance. These source/test improvements must
not be interpreted as permission to perform consequential
automatic user actions. An actual Android device test requires
a separate permitted release path.
