# Android03 durable typed-turn reconnect behavior

The Android03 client uses an editable text field for typed messages and
manually reviewed speech. The installed Jetson voice WebSocket accepts a
`client_request_id` for `text_turn`. This is **not** a second agent or a
separate conversation authority: it is the server's existing idempotency
mechanism for a single manually submitted typed message. An exact repeat
under the same authenticated conversation returns a duplicate submitted
receipt without restarting the SWAAG worker or creating a new turn.

A typed Send now generates one 32-character lowercase hexadecimal UUID and
persists its exact corresponding text in Android private SharedPreferences
using **synchronous `commit()` before the first `WebSocket.send()`**. Without
successful persistence the client refuses to send and keeps the draft for
manual review. The identifier remains pending until the server reports an
accepted `turn-client-ID` with the expected unchanged text. A phone process
restart or lost connection may retry only with the same identifier and
same text, and may only retry a request the user previously explicitly sent.
Ordinary unsent drafts have no request ID and are never auto-submitted after
restart. A draft edited while a previous send is unacknowledged is held for
manual review rather than sent automatically as a second new operation.

A delayed acknowledgment for an earlier request does not clear a changed
current draft. A confirmed speech draft is matched separately by its actual
server turn ID, not by the typed message identifier. The client continues to
require manual sending for unverified owner speakers, retains its protected
WebSocket bearer authentication and newest conversation-first UI, and does
not enable hands-free commands. The server may send multiple idempotent ACK
receipts if network confirmations were lost; these are not multiple agent
runs. If a user deliberately submits a new, changed typed message, that is
an independently requested new logical turn with a new ID.

The published October Jetson server source and the separately tested newer
safe voice server were inspected; **both** implement `client_request_id`,
look up the exact durable turn ID before any cancellation, reject a
conflicting text/ID pair, and return a duplicate receipt when already
submitted. The new Android client policy is tested both as pure Java
under JUnit and with a separate *no-agent* isolated WebSocket regression.
The latter must never be confused with live SWAAG tool execution, mobile
hardware microphone/playback acceptance, or end-to-end owner identity.

The Android source and tests are built offline without injecting any real
voice bearer, so that test APK cannot connect to the protected voice
service. An authenticated APK would contain an extractable bearer and must
not be distributed publicly; a per-device credential and real phone
acceptance remain separate release gates. Do not install on the Blackview
while the owner runs its separate ReverbProbe without coordination.

Source file: `app/src/main/java/myapp/app/VoiceTypedRequestPolicy.java`.
JUnit: `app/src/test/java/myapp/app/VoiceTypedRequestPolicyTest.java`.
The full exact private build, test and source checkpoints are held outside
Git under `/data/var/swaag-benchmarks/android03-typed-reconnect-20261121`.
