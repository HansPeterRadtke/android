# Android UI for speaker-unverified voice review — November 18, 2026

Status: isolated app source, unit and emulator test; not installed on
the owner's physical phone and not paired with a live production release.

The corresponding Jetson voice-server development branch has no
trusted enrolled human-owner speaker verification backend. An
authenticated phone connection and a positive semantic completion
vote do not establish who spoke the recorded words. The source
therefore defaults to preserving all voice transcripts as
manual-review drafts, without automatically sending them to SWAAG.
The server returns a specific awaiting_send reason:
speaker_identity_unverified, and a hello capability flag
automatic_voice_requires_manual_review=true.

MainActivity now reads that authoritative server capability and
displays a clear review message when the speaker identity cannot
be verified: the recognized text is not automatically submitted,
and the user must check the text and tap Send if appropriate.
The existing explicit reviewed Send remains available. The client
does not invent owner identity evidence, and optional speaker_verified
fields from an untrusted client have no server authority. With
the capability enabled the client's auto_commit_ready telemetry
does not misleadingly claim automatic permission; it can still
request auto mode but the SERVER blocks automatic commits.

The pure VoiceSpeakerReviewPolicy has two unit tests for the strict
reason/capability conditions and the manual review requirement.
All eighty-three offline Android JVM tests passed without failures
or skips. The whole debug app and Android instrumentation APK built.

The TARGETED emulator UI class has exactly eight
checks (seven inherited review/reconnect/replay tests plus one
new speaker-warning test). All eight passed on Nitro's existing
emulator in the GUIDELINE_TEST package. The test build's voice
endpoint is loopback only, port nine, with its own test-only bearer
token and a package name distinct from the production Voice
Button. The test injected server protocol events and inspected
the actual rendered review editor and status text. It did not
record audio, click Send, submit to SWAAG, run a TTS model, or
test a previously denied physical Android lost-playback-ACK case.
The two temporary test packages were removed after the run.

This is a truthfully labelled manual-review fallback, not a
functioning automatic owner-speaker authentication solution.
The automatic voice action gate cannot be lifted until a
server-side owner-speaker verifier is developed and passes
recorded corrections, overlapping/two-speaker input and
physical phone tests, along with reliable semantic turn
completion. The production Jetson voice worker remains on
its previous source; no live Android installation occurred.

Private logs and artifacts stay outside Git in Nitro's
/data/var/infra-read-audit/ and Jetson's
/data/var/swaag-benchmarks/foreground-authorization-20261118/.
