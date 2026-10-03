# Voice Button 0.95 compact layout correction

The 0.94 recorder reserved two queue viewports even when there was no work, duplicated section headings, and used oversized status, setup and dock spacing. The player reserved waveform and Studio-progress height while inactive and stacked navigation and Stop on separate rows. Allowing those regions to scroll did not correct the wasted space.

Version 0.95 removes empty queue rows and their reserved height, keeps overall upload/transcription progress and counts, measures an active queue from its real first row, removes duplicate headings and reduces unnecessary padding. The recording timer remains readable and all action targets remain at least 48 dp. Recovery warnings precede optional upload-policy information. Both recorder and player use two columns in wide, short windows. Player Stop shares the transport row; Library and More share a navigation row. Missing waveforms and inactive Studio progress occupy no height.

The authoritative GUI contract and player documentation now describe these requirements. The permanent native Robolectric harness measures actual ScrollView content and viewport heights. It rejects any overflow in standard portrait layouts and normal-font landscape, except the deliberately extreme long-title case. Loaded-player cases include the waveform's actual 64 dp allocation. Large-font, short-landscape windows and unusually long content retain access through scrolling; controls remain reachable.

## Reproduction

```sh
./gradlew --no-daemon --max-workers=2 :apps:voicebutton:testDebugUnitTest :apps:voicebutton:lintDebug :apps:voicebutton:assembleDebug
```

PNG and JSON evidence is generated under `apps/voicebutton/app/build/reports/guidelines`. Geometry reports record `contentHeight`, `viewportHeight` and `scrollOverflow`; the tests also enforce 48 dp action targets and visible dialog actions. This correction was made directly in the existing Nitro repair worktree. No source bundle was uploaded to Nitro.

## Verified geometry, 2026-10-03

The final native-rendered matrix contains 59 cases, including seven additional compact/loaded-phone cases. All 94 application tests passed without failures, errors or skips. Minimum-target and dialog-visibility assertions also passed.

| Screen | Content height (dp) | Available height (dp) | Overflow (dp) |
| --- | ---: | ---: | ---: |
| main-360x640-font100 | 256 | 526 | 0 |
| main-recording-360x640-font100 | 292 | 497 | 0 |
| main-recording-393x803-font200 | 477 | 674 | 0 |
| main-queue-393x803-font200 | 497 | 674 | 0 |
| player-loaded-360x640-font100 | 375 | 518 | 0 |
| player-loaded-393x803-font200 | 471 | 681 | 0 |
| player-loaded-803x345-font100 | 221 | 223 | 0 |

These are production Android view trees rendered by Robolectric at mdpi, with synthetic operational fixtures. The loaded-player fixture includes the full waveform allocation; it does not call the live rendering service. The short 803 × 345 window at 200% fonts and the intentionally extreme long title can still require scrolling. Those cases retain reachable fixed controls and are not represented as zero-overflow cases. No physical-device or live-backend validation is claimed.

## Build artifact

The final build completed successfully. All 21 standard-overview no-scroll checks passed. Lint reports zero errors and two existing warnings (UsableSpace and SetTextI18n). The package remains `com.hans.android.voicebutton`, versionName `0.95`, versionCode `95`, signed with the same certificate as 0.94.

APK: 205056525 bytes; SHA-256 `b190fc73f9bcabf0418289d1ecdeae8b8f72f7984d46f2b6244903077e5c7bdf`.

Nitro verification log: `/data/tmp/voicebutton-guideline-audit-20260930/compact-095-final.log`. Aggregated evidence: `release-095-verification.json`; rendered evidence archive: `verified-layouts-095.zip`, in the same directory.

Automatic approval rejected a proposed GitHub default-branch push. No remote write was retried. The completed source changes and build remain directly on Nitro.
