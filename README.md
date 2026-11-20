# Frigate for Android

An Android app that opens your [Frigate NVR](https://frigate.video) web UI full screen.
It shows Frigate's own interface, so it looks and behaves exactly like the website and stays
current with every Frigate release. The app adds the things a browser tab can't do well:

- **Self-signed certificates.** Frigate's default HTTPS port (8971) uses a certificate it made
  itself. The app shows that certificate's fingerprint once, remembers your answer, and warns you
  if the certificate ever changes.
- **Stays logged in** across restarts.
- **Real fullscreen** for live and recorded video, plus the Android back gesture.
- **Status bar colours** that follow Frigate's light or dark theme.
- **Downloads** (snapshots, clips, exports) go to the phone's Downloads folder.
- **Updates itself** from this repo's releases.

## Install

1. On the phone, download the latest `frigate-x.y.z.apk` from
   [Releases](https://github.com/kichappa/frigate-android/releases/latest).
2. Open it. When Android asks, allow your browser or file manager to install apps.
3. Enter the address you use for Frigate in a browser, e.g. `https://192.168.1.10:8971`.
4. Tap **Trust** for Frigate's certificate, then log in as usual.

To change the server later, long-press the app icon and choose **Server**.

## Updates

At every launch the app checks the latest release here. If it's newer, it offers to download
and install it. The first update asks you once to allow "Install unknown apps" for Frigate.

## Building

You need JDK 17 and the Android SDK (platform 35).

```powershell
.\build.ps1                      # debug APK, app\build\outputs\apk\debug\
.\release.ps1 1.0.1 "Notes"      # signed release: bump, build, tag, push, GitHub release
```

Releases are signed with a key that stays out of git: `keystore.properties` in the project root
points to it (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Every release must use
the same key, or installed apps refuse the update.
