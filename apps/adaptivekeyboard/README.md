# KontextTastatur — local adaptive German Android IME

A standalone Android InputMethodService for German QWERTZ typing, focused on user-confirmed contextual predictions. This project is developed in a separate Android repository worktree to avoid disturbing the existing Android clients.

## Implementation and privacy contract

- Three independent suggestions, in rank order **second, first, third**, so the most probable appears in the center. No forced autocorrection or automatic acceptance.
- Predictions are based on German lexical frequency and user-confirmed word frequency, previous-word and previous-two-word context, and exact user-selected prefix/word combinations. Word forms are keyed with `Locale.GERMAN` case folding; user-confirmed spelling and canonical German noun casing are preserved.
- The UI calls `predict` after a text insertion; a visible or discarded suggestion does **not** increase any count. A typed word is learned only when the IME sees a delimiter or the user explicitly taps a suggestion. The latter replaces the typed prefix, inserts a space and records the exact selection separately. Immediate deletion of a delimiter attempts to undo the most recent learning event.
- Personalization is locally persisted in `learned.db` (SQLite) in the keyboard app's private data directory, without raw typed sentences, text-field dumps or cloud syncing. Database writes are serialized on a worker thread. Android backup is disabled. Only local context words and count statistics are retained, including a prefix when the user explicitly selects a completion. Other apps can access keyboard output only through the normal Android input method API.
- Passwords, sensitive text variations, URL/email fields, `NO_SUGGESTIONS`, `IME_FLAG_NO_PERSONALIZED_LEARNING`, and non-text fields disable learning and suggestions. Manual private mode disables both learning and suggestions globally. Users can explicitly reset learned data; the bundled lexicon remains.
- German umlauts, sharp S, uppercase ẞ, symbols, alternate accented letters via long press, emoji categories, cursor movement, copy/cut/paste, manual clipboard pinning and local clipboard history (twenty entries, up to three thousand characters) are supported.
- GIF search is only performed after explicitly entering the GIF search panel and pressing search. A user-provided GIPHY API key is needed; no secret key is included in the APK. GIF thumbnails are fetched from GIPHY, and a selected GIF is committed with Android `InputConnection.commitContent` using a read-only `content://` provider if the receiving app advertises `image/gif` support. Otherwise the public GIF link is committed as text. Original data and provider attributions appear in the settings. GIF search and sharing require connection; typing and prediction do not.

## Build and install

From the Android repository root:

```sh
ANDROID_HOME=/data/install/android-sdk ./gradlew :apps:adaptivekeyboard:assembleDebug
ANDROID_HOME=/data/install/android-sdk ./gradlew :apps:adaptivekeyboard:testDebugUnitTest
adb install -r apps/adaptivekeyboard/app/build/outputs/apk/debug/adaptivekeyboard-debug.apk
```

Open the launcher activity and enable/select `KontextTastatur` using the two system controls. Do not switch the owner's real-phone default keyboard without approval. Emulator testing uses API thirty-four and does not change any physical device.

## Required further acceptance

Test on the actual target phone, touch keyboard sizing, layout/rotation, selection replacement in other apps, IME field variations, private-learning guarantees, copy/paste permission behavior, persistent restore after process death, corrupted database handling, negative suggestion and latency benchmarks, and real GIPHY API and rich-content interoperability. A built APK and passing JUnit tests are **not** evidence of those unperformed tests. GIPHY integration requires a valid provider key and respect for the vendor's terms.

## Reference research

- Android `InputMethodService`, `InputConnection`, `EditorInfo`, `InputContentInfo`, `commitContent`, and clipboard access from developer.android.com. Code uses the Android API thirty-four SDK installed on Nitro.
- HeliBoard, FlorisBoard and FUTO Keyboard were reviewed as alternative IME architectures; all are significantly larger codebases than the tested standalone word-learning engine. None was imported, forked or claimed to pass its test suite.
- Raspi KnowMoreDiRT FileDB semantic search was queried for Android keyboard prediction and mobile touch design; no directly applicable keyboard source was returned. The complete original index is preserved. The owner's Infra Android GUI rules were used for immediate tap feedback and conservative feedback semantics.
- See `THIRD_PARTY_DATA.md` for the generated German lexicon's CC BY-SA/CC0 source licensing and offline generation recipe. Do not redistribute input archives or paid/uncleared research papers.

## November 6 emulator acceptance

The Android API 34 emulator (Nitro) was used for actual `InputMethodService` registration, IME selection, keyboard rendering in `SettingsActivity`, touch entry, completed-word SQLite persistence, and a separately verified tap on the middle suggestion. With `haus` in the editor, tapping the center suggestion inserted `Haus ` and the app SQLite recorded `token=haus`, `selected=1` and the exact `prefix=haus`. `PRAGMA integrity_check` returned `ok`. Twelve+ JVM tests cover contextual ranking, rejected predictions, explicit selections, German word boundaries, typo fallbacks, retrievable saved state, and undo. The Android emulator was not a physical phone. No provider credential was configured, so real GIPHY search and MIME interoperability were not verified. No user data was imported or reset.

Caveats: This is a tested first keyboard release, **not** feature parity with Gboard. Gesture typing, multilingual switching, a verified cloud GIF provider integration, and real handset touch-size benchmarks are not implemented/verified. The clipboard retains at most twenty user-pinned items and requires explicit pinning. The ranker is count-based and not a trained transformer; quality still needs measured long-term usage evidence.

## Prebuilt emulator-tested Android package

The signed debug APK is checked in under `release/kontexttastatur-0.1.0-debug.apk`, alongside its SHA-256. It is deliberately a development/test build, not a production Play Store release. It can be sideloaded after allowing installs from the chosen file source; choose the keyboard in Android system input method settings. It never changes the default IME automatically.
