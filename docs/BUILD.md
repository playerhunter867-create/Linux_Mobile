# Building LinOx Mobile

## Option A — GitHub Actions (recommended, no local Android setup needed)

1. Push this project to your GitHub repo (root of the repo should be this
   `linox07` folder's contents — i.e. `build.gradle.kts` and `app/` sit at
   the repo root, not nested one level down).

   ```bash
   cd linox07
   git init
   git add .
   git commit -m "LinOx Mobile v0.9.0"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
   ```

2. On GitHub, open the **Actions** tab. The `Build LinOx Mobile` workflow
   runs automatically on every push to `main` (and can also be triggered
   manually via "Run workflow").
3. When the run finishes (green check), open it and scroll to **Artifacts**
   at the bottom — download `LinOx-Mobile-debug`. It's a zip containing
   `app-debug.apk`.
4. Copy the APK to your phone and install it. You'll need to allow
   "Install unknown apps" for whichever app you use to open it (Files,
   Chrome, etc.) — debug APKs are signed with a local debug key, which is
   fine for your own device but not for distributing to others.

No secrets or signing keys are required for a debug build.

## Option B — Locally with Android Studio

1. Open the project folder in Android Studio (Ladybird/Koala or newer).
   Studio will generate its own Gradle Wrapper on first sync.
2. Let it install the Android SDK platform 35, NDK `27.0.12077973`, and
   CMake `3.22.1` if prompted (Tools → SDK Manager if it doesn't prompt
   automatically).
3. Run ▶ or **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

## Notes

- This repo intentionally does **not** commit a Gradle Wrapper
  (`gradlew` / `gradle-wrapper.jar`), so the CI workflow installs a pinned
  Gradle version directly instead. If you generate a wrapper locally
  (`gradle wrapper --gradle-version 8.9`) and commit it, you can simplify
  the workflow back to `./gradlew assembleDebug`.
- `assembleDebug` produces an installable but debug-signed APK. For a
  release build you'd add your own signing config — not set up here.
