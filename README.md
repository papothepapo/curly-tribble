# LightIME (T9 + Deepgram Streaming Dictation)

LightIME is a lightweight Android IME designed for Android 8.1 Go class hardware. it supports blazing fast accurate (more accurate than whisperv3 large!!) speach to text using deepgram. deepgram offers 200 dollars worth of api credits which should last a very long time for casual texting usage.

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
