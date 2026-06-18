# Video Analyzer — Android

A self-hosted Android app that lets you point at **any OpenAI-compatible API** and analyze
videos with whichever model that provider exposes. You bring your own base URL + API key,
the app pulls the model list, you pick one, and you can ask natural-language questions
about any video on your phone.

Built end-to-end as a single-module Compose app. APK is built automatically by
GitHub Actions on every push — download the artifact, side-load, done.

---

## Features

- **Bring-your-own backend.** Paste any base URL + API key. App auto-fetches
  `GET /v1/models` and populates a picker.
- **Two send modes.**
  - **Video URL mode** (default) — sends the full video as a `video_url` base64
    data URL inside an OpenAI-compatible `chat/completions` request. Works with
    Moonshot Kimi and any provider that accepts that shape.
  - **Frames mode** — extracts up to 8 evenly-spaced JPEG frames and sends them
    as `image_url` parts. Works with vision-only models.
- **Persistent config.** Base URL, API key, selected model, and cached model
  list are stored in Jetpack DataStore. API keys are excluded from auto-backup
  (`res/xml/backup_rules.xml`) so they never leave your device via Google's
  backup channel.
- **No telemetry.** No Firebase, no Crashlytics, no analytics. The only network
  traffic is to whatever base URL you configured.

## Quick start (downloading the APK)

1. Go to the **Actions** tab of this repo on GitHub.
2. Click the most recent green "Build Android APK" run.
3. Scroll to the bottom — under **Artifacts** there is a `video-analyzer-apk.zip`.
4. Download it, unzip it, transfer `app-debug.apk` to your Android phone.
5. On the phone, allow "Install from unknown sources" for whichever app you
   used to open the APK (Files / Chrome / etc.), then tap the APK to install.
6. Launch **Video Analyzer**, paste your base URL + API key, tap **Fetch
   Models**, pick one, save, and you're done.

> To get a signed release APK, push a tag like `v1.0.0` — the workflow creates
> a GitHub Release with the APK attached.

## Quick start (building from source)

You don't actually need Android Studio — the GitHub Actions workflow handles
everything. But if you want to build locally:

```bash
# 1. Install JDK 17 (any flavor) and Android SDK with platform 34 + build-tools 34.
# 2. Set ANDROID_HOME.
# 3. From the repo root:
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/app-debug.apk
```

## Configuration guide

### Base URL examples

| Provider          | Base URL                          | Notes                                                |
|-------------------|-----------------------------------|------------------------------------------------------|
| Moonshot AI       | `https://api.moonshot.cn`         | Accepts video_url directly — fastest path.           |
| OpenRouter        | `https://openrouter.ai/api`       | Vision models only — use the frames toggle.         |
| MiniMax-compatible | `https://api.MiniMax.chat`        | Configure under "API key" in their dashboard.        |
| Self-hosted vLLM  | `http://your-server:8000`         | Enable `--api-key` and an OpenAI-compatible server.  |
| Ollama            | `http://your-server:11434`        | Use the OpenAI-compat layer if you have it enabled.  |

The "Fetch Models" button hits `{baseUrl}/v1/models`. If your provider exposes
the list at a different path, the underlying call is overridable — edit
`OpenAiCompatibleService.kt`.

### Switching providers later

Open the app, hit the gear icon → **Settings** → edit the base URL / API key →
**Re-fetch models** → pick a new model → **Save changes**.

## Request shape

The app sends:

```json
POST {baseUrl}/v1/chat/completions
Authorization: Bearer {apiKey}
Content-Type: application/json

{
  "model": "your-selected-model",
  "temperature": 0.4,
  "stream": false,
  "messages": [
    {
      "role": "user",
      "content": [
        { "type": "text",  "text": "your question" },
        { "type": "video_url", "video_url": { "url": "data:video/mp4;base64,..." } }
      ]
    }
  ]
}
```

In frames mode, the `video_url` part is replaced by up to 8 `image_url`
JPEG parts.

If your provider needs a different envelope, the request body is built in
`VideoAnalyzerRepository.kt` — change it there and rebuild.

## Project layout

```
app/
  src/main/java/com/example/videoanalyzer/
    MainActivity.kt
    VideoAnalyzerApp.kt          # Application — manual DI
    data/
      ApiConfig.kt
      ModelsResponse.kt          # /v1/models wire format
      PreferencesRepository.kt   # DataStore-backed config
      VideoAnalyzerRepository.kt # fetchModels / analyzeVideo
    network/
      NetworkModule.kt           # OkHttp + Retrofit
      OpenAiCompatibleService.kt
      ChatCompletionsService.kt
      ChatCompletionRequest.kt   # request + response envelopes
    ui/
      nav/NavGraph.kt            # Compose Navigation
      setup/                     # first-launch config screen
      home/                      # main analyze screen
      settings/                  # edit config / wipe
      theme/                     # Material 3 colors + theme
    util/VideoUtils.kt           # read bytes + extract frames
  src/main/res/                  # icons, themes, backup rules
build.gradle.kts                 # root project
settings.gradle.kts
gradle.properties
gradle/wrapper/gradle-wrapper.properties
.github/workflows/build.yml      # CI: build APKs on push, release on tag
```

## Troubleshooting

- **"Connected, but zero models."** Your key works but the provider filtered
  the list to nothing — check permissions in their dashboard.
- **HTTP 400 "unsupported content type"** when sending video. Your provider
  doesn't accept `video_url`. Turn on the **Use frames** toggle on the home
  screen and try again.
- **HTTP 413 / payload too large.** Your clip is over the provider's limit.
  Trim it or enable the frames mode (frames are usually a few MB total).
- **App crashes on startup.** Wipe everything from **Settings → Danger zone**
  and reconfigure.

## Security notes

- The release APK is signed with the **debug keystore** so you can install it
  without managing your own keystore. For Play Store distribution, replace
  `signingConfig = signingConfigs.getByName("debug")` in `app/build.gradle.kts`
  with a real release keystore (keep it out of git).
- API keys live in DataStore in the app's private files directory
  (`/data/data/com.example.videoanalyzer/files/datastore/`) and are excluded
  from `android:autoBackup` and `android:dataExtractionRules`.

## License

MIT — do whatever, just don't blame us if your provider charges you.