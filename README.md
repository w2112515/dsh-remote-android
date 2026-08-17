# DSH Remote Android

Phone client for [DSH Remote Host](https://github.com/w2112515/dsh-remote-host).

Install the Host plugin (or the [DSH Remote pack](https://github.com/w2112515/dsh-remote-pack)) on the PC first. Then download **one APK** from [Releases](https://github.com/w2112515/dsh-remote-android/releases). A marketplace pack cannot install this app.

## Use

1. On the PC: `dsh plugin --profile web add @w2112515/dsh-remote-host`, restart `dsh web`, open Settings → Mobile access, turn on nearby discovery.
2. Install the APK on the phone.
3. Join the same Wi-Fi, scan the Host QR, confirm the eight-digit code on the PC.

This repository is the Android application. It is not a DSH plugin.

## Build

Android Studio or:

```powershell
.\gradlew.bat assembleDebug
```

The debug package id is `dev.dshremote.gate0c`.
