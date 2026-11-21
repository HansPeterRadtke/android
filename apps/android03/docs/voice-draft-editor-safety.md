# Android03 human-edited speech must not be overwritten

The SWAAG Android03 app continuously displays provisional recognition words
while speech audio is arriving, then a finalized editable transcript. Those
words remain a draft until the human approves them with Send. Automatic
transcription is not authority to execute words spoken by an unknown speaker.
The published owner-speaker admission policy therefore stays **manual-only**.

An asynchronous server recognition update is not a human edit. Its
`TextWatcher` callback is explicitly identified with
`updatingDraftFromServer=true` while changing the visible field. Only a
**real human edit of an existing provisional/final speech draft** may claim
that draft. A normal manually typed message, even if the microphone is
running, does **not** claim anything: the Jetson server's `draft_claim`
control fences off future microphone frames. Accidentally sending it during
ordinary typing would silently mute speech.

When a human edits recognized words, the phone sets `userClaimedDraft=true`
and sends the existing `draft_claim` control once. Later ASR partial/final
callbacks check this flag **again on the UI thread** before touching visible
text, because updates can already be queued while the user starts editing.
The client stops its active microphone capture and keeps the status in
Review: otherwise a seemingly live microphone would actually be ignored by
the server after the claim. `client_state` carries `draft_claimed=true` on
a new connection to preserve this server-side fence. Reconnected history
must not overwrite a human-edited draft with the old automatic transcript.

The human may still press Send explicitly. A late submitted ACK clears a
visible draft **only if the exact submitted words still match the current
editable field when the UI thread handles the acknowledgment**. An ACK for
an older word choice cannot delete new work. After confirmed submission the
claim and speech-draft state are cleared; the next recording can proceed.
The existing safe typed-turn durable retry and private WSS authentication
remain in place, as do current conversation-first layouts and playback
controls. A manual local recording (with automatic transcription off) is
independent and never triggers the claim logic.

Focused policy: `app/src/main/java/myapp/app/VoiceDraftClaimPolicy.java`;
regressions: `app/src/test/java/myapp/app/VoiceDraftClaimPolicyTest.java`.
This is source/Android JUnit acceptance, not evidence of real physical
microphone, Bluetooth, echo, speaker authorization or deployment. The real
source-pinned Jetson server separately implements the `draft_claim` message,
but any changes to the installed Jetson voice process still require their
own release and rollback acceptance. This does not authorize installing an
APK on another person's active phone or embedding a production token.
