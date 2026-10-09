# Voice Agent — current-message regression and working-agent routing

Date: 2026-10-09.

## Actual user-observed failures
Android version 1.7.13 intentionally rendered automatic speech hypotheses in the chat history and left the current-message editor blank. This violated the established voice UI requirement that speech appears in the editable current-message field before acknowledgement. The user also requested an autonomous worker to create, run, and verify a Python file; SWAAG responded repeatedly with promises but its task list showed no newly created worker.

## Android 1.7.14 correction
All new partial and final ASR text appears in Current message in automatic and manual modes. It is excluded from history until a real server turn submission acknowledgement. A transient ASR artifact disappears; user-typed corrections take precedence; acknowledgement clears only the exact matching draft. Distinct human-authored local drafts remain persistent across network reconnects. The app is unchanged in its basic WebSocket protocol; no second orchestration component is added.

## SWAAG routing diagnosis and fix
The underlying SWAAG `orchestrator.message` router incorrectly used `route=respond` for explicit work requests. Model answers such as "I'll write a Python program" were returned without tool execution. A narrowly scoped SWAAG source guard rejects unsupported future work claims from the fast direct-response path and invokes the normal full orchestrator. Another structural repair ignores the irrelevant nonempty direct answer field for `route=orchestrate`, avoiding a false 502.

The full orchestrator also repeatedly called `load_tools(orchestration_control)` without invoking the control capability. SWAAG's dedicated user-facing orchestrator now loads its core `orchestration_control` schema directly rather than using staged discovery. Normal workers retain their configured capability discovery policy. These are SWAAG changes, not a competing voice-side agent.

## Acceptance criteria
Confirm actual worker creation from a user-facing orchestrator request with durable worker ID and state; do not mistake an assistant promise for delegation. Distinguish queued/working/completed/failed. A launched worker must produce verifiable artifacts and test results. The physical-phone audio/visible editor, real automatic submission and playback are separate gates; an emulator-only test does not close them.
