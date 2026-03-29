# LightIME (T9 + Deepgram Streaming Dictation)

LightIME is a lightweight Android IME designed for Android 8.1 Go class hardware.

## Build
1. Install Android Studio Giraffe+ (AGP 8.2.2 compatible) and Android SDK 34.
2. Open project root and run:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install on device:
   ```bash
   ./gradlew installDebug
   ```

## Enable as default keyboard
1. Open **Settings > System > Languages & input > Virtual keyboard**.
2. Enable **LightIME T9**.
3. Select LightIME as active keyboard.

## Configure Deepgram API key
1. Open app **LightIME Settings** activity.
2. Set **Deepgram API key** in `dg_api_key` field.
3. Hold 🎤 key in keyboard to start streaming dictation.

## Add keyterms
1. Open settings field **Deepgram keyterms**.
2. Add one term/phrase per line.
3. Terms are appended as repeated `keyterm=` query params in WebSocket URL.

## Configure emoji replacements
1. Use **Emoji replacements** setting.
2. Enter `phrase<TAB>replacement` lines, for example:
   - `emoji thumbs up<TAB>👍`
   - `emoji smiley face<TAB>🙂`

## Limitation note
Hardware key capture (volume/camera) is **not supported** for IMEs. Use the in-keyboard hold-to-talk button.

## Performance rationale (512MB RAM target)
- XML/View UI only (no Compose).
- Single `AudioRecord` buffer reused in hot path.
- No SDK-heavy networking; direct OkHttp WebSocket.
- SQLite-based dictionary lookup with a compact text seed (`base_words.tsv`) loaded once into SQLite on first run.
- Minimal object churn during streaming; composing text updated in-place.
- No background services, analytics, Firebase, or crash SDKs.

## Test plan
- Verify IME appears in system keyboard list.
- T9 multi-tap entry + suggestion strip commit behavior.
- Backspace and long-press delete word.
- Dictation button hold: interim composing updates.
- Release dictation: Finalize sent and final text committed once.
- Missing API key path shows status + fallback typing.
- Network loss path keeps keyboard usable.
- Post-processing: correction -> spelling normalize -> emoji replacement.
- Unit tests for spelling normalization.
