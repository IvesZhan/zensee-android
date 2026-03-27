# ZenSee Android Project Rules

This is the single source of truth for project-level AI guidance in this repository.
Codex, Claude, Cursor, and other AI assistants should read this file first for Android work in this repo.

## Primary References

- Read `PROJECT_SSoT.md` for product scope, feature map, architecture, and Android-specific design guidance.
- Read `SECURITY_CHECKLIST.md` before pushing code, publishing APKs, or touching local configuration.
- Read `README.md` for local setup and the current release publishing flow.

If these documents conflict, use this priority:
1. `AGENTS.md`
2. `PROJECT_SSoT.md`
3. `SECURITY_CHECKLIST.md`
4. `README.md`

## Product Context

### Users
ZenSee serves meditation practitioners who want a calm, low-friction Android app for starting sessions, recording mood, reviewing progress, and joining group practice.

### Brand Personality
Zen · Meditation · Serenity

The product should feel grounded, quiet, and respectful. It should not feel playful, flashy, gamified, or overly technical.

### Android Aesthetic Direction
Warm stone surfaces, centered focus, structured cards, soft material depth, rounded panels, and restrained motion.

Keep the current Android look intentional and native to Android. Do not force iOS layout logic, spacing habits, or interaction patterns onto this codebase.

## Design Rules

### Visual Principles

1. Preserve the warm neutral palette already defined in Android resources.
2. Prefer calm hierarchy over dense screens, but do not sacrifice Android-side scannability and tap efficiency.
3. Use structure, spacing, and typography for hierarchy before adding decorative chrome.
4. Motion should be subtle and purposeful: tap feedback, breathing emphasis, meditation immersion.
5. The home screen anchor is the centered meditation action, not an iOS-style freeform hero layout.

### Anti-Patterns

- Do not introduce neon gradients, blue-purple tech visuals, or glassmorphism.
- Do not redesign Android screens to imitate SwiftUI or iOS information architecture.
- Do not add hard-coded one-off visual values when a shared Android resource already exists.
- Do not change stable public download/share URLs unless the user explicitly asks for a distribution migration.

## Technical Rules

### Android Stack

- This app is a native Android app built with Kotlin, the Android View system, XML layouts, and ViewBinding.
- Preserve the existing Activity-centered structure unless there is a concrete product or maintenance reason to refactor.
- Reuse existing managers, repositories, domain helpers, widgets, and presentation rules before adding new abstractions.

### Configuration and Secrets

- Never hard-code Supabase URL, Supabase anon key, keystore passwords, or other secrets in Kotlin, XML, Markdown, or scripts.
- Keep sensitive values in local `key.properties`.
- Public repo content may include `key.properties.example`, but never real secrets.
- Current secure injection path is via `BuildConfig.SUPABASE_URL` and `BuildConfig.SUPABASE_ANON_KEY` from `app/build.gradle`.
- Treat `key.properties`, `local.properties`, and release keystore files as local-only artifacts.

### Release and Distribution

- The stable Android APK URL must remain:
  `https://raw.githubusercontent.com/IvesZhan/zensee-android/main/downloads/latest/ZenSee-android-latest.apk`
- Web download pages and Android share logic are intentionally wired to stable URLs and locale-specific landing pages. Do not change them casually.
- When publishing a new Android version, prefer `./scripts/publish-release.sh` so both `downloads/latest/` and `downloads/vX.Y.Z/` stay in sync.
- If versioning or release flow changes, verify that the stable download URL still works and the app share entry still points to the correct localized page.

### Localization

- Existing user-facing language coverage is Simplified Chinese, Traditional Chinese, and Japanese.
- For Android app share links:
  - `zh` -> `/download/`
  - `ja` -> `/download/ja/`
  - `zh-Hant` style locales -> `/download/zh-hant/`
  - all other locales -> `/download/en/`
- If you change surrounding localized copy, update the relevant Android string resources when that feature already participates in localization.

## Implementation Expectations

- Keep changes scoped to the user request.
- Prefer small, verifiable changes over speculative refactors.
- Match existing naming, file organization, and UI structure.
- When editing behavior in one tab or Activity, check nearby flows for refresh, login state, and empty/error state handling.
- Use existing domain logic and repositories instead of duplicating business rules in Activities.
- When changing build, release, or security behavior, verify the effect instead of assuming it.

## Project Pointers

- App entry: `app/src/main/java/com/zensee/android/MainActivity.kt`
- App manifest: `app/src/main/AndroidManifest.xml`
- Build config and secret injection: `app/build.gradle`
- Auth/session layer: `app/src/main/java/com/zensee/android/AuthManager.kt`
- Meditation and mood data: `app/src/main/java/com/zensee/android/data/ZenRepository.kt`
- Group data: `app/src/main/java/com/zensee/android/data/GroupRepository.kt`
- Reminder scheduling: `app/src/main/java/com/zensee/android/ReminderManager.kt`
- About/legal entry: `app/src/main/java/com/zensee/android/AboutActivity.kt`
- Android resources: `app/src/main/res`
- Release flow: `scripts/publish-release.sh`
- Stable download artifacts: `downloads/latest`

## Working Style

- Verify the most relevant command before claiming success.
- If verification cannot be run, say so explicitly.
- Before commits or pushes, check for accidental sensitive files or values.
- When design and engineering trade off, choose the calmer and simpler Android-native solution unless product requirements say otherwise.
