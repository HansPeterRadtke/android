# Android voice UI: saved original recording when every ASR service is down

Date: November 16, 2026. This is an isolated client development branch,
not installed on the owner's physical device or deployed to production.

## Reproduced missing feedback

The verified paired Jetson voice-server branch persists a non-executable
asr_unavailable history record when all configured transcribers fail.
It contains a private original WAV reference but no user text. The old
Android client only set Replay availability for an asr.final event or
from a completed history fetch. Its generic asr.artifact handler merely
discarded unstable partial text. Consequently, immediately after the
server saved a failed-transcription recording, the user could not
discover the Replay button without reconnecting or requesting history.
After reconnect, a blank-text recording was not visibly distinguished
from an empty conversation.

## Bounded UI correction

The MainActivity ASR event path now recognizes ONLY an artifact event with
reason recognition_unavailable, a nonempty server artifact turn ID and
has_user_audio true. It makes that recording available through the
already implemented Replay row without pretending it is an editable
transcript. The matching STT error code produces a plain message:
'Recording saved, but transcription failed. Replay it or record again.
Nothing was sent to SWAAG.'

The normal conversation history renderer inserts the same system
information for a status of asr_unavailable with has_user_audio true.
No system message is inserted for genuine silence, plain failed network
capturing, other artifact status, or a fake record without audio. Normal
user and assistant messages and their submitted-status logic remain
unchanged. A history refresh replaces the text as usual instead of
appending duplicate warning lines.

A small pure Java VoiceAudioRecoveryNotice helper centralizes strict
status/event/code checks; three new JUnit tests prevent accidental
false notices for silence, non-ASR errors and absent audio IDs.
All recorded audio remains private on the owner server, outside Git.

## Independent checks

The Android debug Gradle build and unit tests completed offline using
a local test token with -PvoiceGuidelineTest. The new build ran 73 JUnit
tests with zero failures or skipped tests and produced a debug APK.
The companion Jetson source passed its original PCM reconnect and
replay acceptance and broader server tests independently.

No APK was installed, no live production server or shared model was
restarted, no SWAAG action was submitted, and the previously denied
physical Android lost-playback-ACK test was not rerun.

This does not establish actual phone UI behavior or speaker playback;
those acceptance steps require a separately permitted on-device
deployment and test.
