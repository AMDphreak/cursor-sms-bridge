# Build the debug APK from the repo root:

```powershell
cd android
./gradlew :app:assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

## Android Studio

Open the `android/` folder as a project. Sync Gradle, run on a device or emulator.

**Note:** SMS send/receive requires a physical device with a SIM. Emulators can test pairing/UI only.

## SDK setup

Install Android Studio and ensure `ANDROID_HOME` is set, or create `android/local.properties`:

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
```

## Permissions

On first launch, grant:

- Receive / read / send SMS
- Notifications (Android 13+)
- Camera (QR scan)

Battery optimization: disable it for this app if the OS kills the foreground service aggressively.
