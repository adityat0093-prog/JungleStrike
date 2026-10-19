# Jungle Strike

Original run-and-gun side-scroller for Android (Java, Canvas, no assets). Landscape, touch controls: left/right, aim-up, jump, fire. Pick up S (spread), R (rapid), L (extra life); beat the boss at the end.

## Get the APK

1. Create a GitHub repo and push this folder.
2. Actions tab -> "Build APK" (runs on every push, or run it manually).
3. Download the `JungleStrike-debug-apk` artifact, unzip, install `app-debug.apk` (allow unknown sources).

Local build: `gradle assembleDebug` (JDK 17 + Android SDK) -> `app/build/outputs/apk/debug/app-debug.apk`.
