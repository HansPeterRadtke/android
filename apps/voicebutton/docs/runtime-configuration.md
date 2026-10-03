# Runtime configuration

More → Automation and privacy controls automatic upload, transcription of new uploads, remote waveforms, user-action logging and verbosity. Automation defaults on. User-action logging defaults on and verbosity defaults to Normal. Warning/error records remain enabled. Manual upload authorizes recordings already present at the selected cutoff; later recordings remain local while automatic upload is disabled. Already submitted server jobs are not recalled.

Advanced limits accepts known keys with finite integer values within the ranges below. The complete candidate validates before one preference commit. Omitted keys retain their current values. Precedence is valid saved preference, then schema default; invalid persisted values fall back to that default. Neither preferences nor retries can override hash/length proof, original-audio preservation, capture exclusion, or waiting for the writer to terminate before publishing its journal.

| Key | Default | Range | Unit | Purpose |
| --- | ---: | --- | --- | --- |
| `pcm_queue_blocks` | 600 | 20–1200 | 50 ms blocks | Maximum buffered capture; next recording |
| `pcm_sync_ms` | 1000 | 100–5000 | ms | Maximum normal unsynced capture interval; safety-critical |
| `writer_drain_warning_ms` | 60000 | 5000–300000 | ms | Warn while draining; never discard audio on timeout |
| `status_poll_ms` | 5000 | 1000–60000 | ms | Transcription status refresh interval |
| `log_queue_events` | 256 | 16–4096 | events | Maximum admitted diagnostic tasks |
| `log_pending_bytes` | 134217728 | 1048576–536870912 | bytes | Hard retained diagnostics limit including errors |
| `log_corrupt_bytes` | 1048576 | 65536–8388608 | bytes | Malformed diagnostic record retention |
| `trace_bytes` | 786432 | 65536–8388608 | bytes | Local trace retention |
| `http_response_bytes` | 16777216 | 262144–67108864 | bytes | Maximum upload JSON response body |
| `studio_response_bytes` | 2097152 | 65536–16777216 | bytes | Maximum Studio JSON response body |
| `studio_timeout_ms` | 1800000 | 10000–1800000 | ms | Studio response wait; cancellation disconnects sooner |
| `studio_cache_bytes` | 4294967296 | 67108864–68719476736 | bytes | Combined Studio cache budget |
| `storage_reserve_bytes` | 536870912 | 67108864–4294967296 | bytes | Free storage reserved before cache writes |

Queue capacity applies to the next recording. PCM sync interval is safety-critical: increasing it increases the normal unsynced interval. A drain warning never discards pending audio or closes a journal while its writer is alive. Cache limits apply to source copies, renders and waveforms together. Cache exhaustion preserves original recordings and names the manual clear-cache remedy. Active playback cache is retained; cache clearing refuses while Studio work is active.

Log retention includes warnings and errors: complete oldest lines are removed at the hard byte limit so recent records remain bounded. Queue overflow is counted and reported later. User-action filtering is checked again when processing queued events. Logging and rotation run off the UI thread and failures do not interrupt capture.

Protocol formats, hash algorithms, sequence identities, complete-byte proof, and minimum interaction targets are safety/interoperability invariants. Player speed/skip settings validate separately before an atomic candidate save. Studio cancellation disconnects its active HTTP connection; the configured timeout remains an upper bound.
