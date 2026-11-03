# Dipixel Home

A custom home screen for the Xiaomi Box 4K (2nd Gen, Google TV). Lock screen look: big clock, date and live weather, cycling wallpapers, a row of your shows per profile, a row of your apps. No ads, no tracking, no accounts.

## Get the APK

Every push to `main` builds the APK on GitHub and publishes it here:

`https://github.com/OWNER/REPO/releases/latest/download/home.apk`

(Replace OWNER/REPO. The repo must be public for the Downloader app to fetch it. If you keep it private, use the USB or adb route.)

## Install on the box (pick one)

**A. Downloader app, no PC**
1. On the box: Play Store, install "Downloader" (AFTV).
2. Settings > Apps > Security & restrictions > Unknown sources, allow Downloader.
3. Open Downloader, type the APK link above, install.

**B. USB stick**
1. Copy `home.apk` to a USB stick, plug it into the box.
2. Install "File Commander" or "X-plore" from the Play Store, open the APK from the stick, install.

**C. adb from your PC** (also needed for step 2 below)
```
adb connect <box-ip>      # Settings > System > About > Status shows the IP; enable Developer options > USB/Network debugging first
adb install -r home.apk
```

## Make it the real home screen (Google TV needs this once)

Google TV hard-wires the Home button to its own launcher, so a second launcher only shows up as an app until the stock one is disabled. One-time, from your PC with the box on the same Wi-Fi:

```
adb connect <box-ip>
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Press Home on the remote. Pick "Home" and "Always". Done. The Google TV ad rows are gone with it.

To go back to stock at any time:
```
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```

## First run checklist (in the launcher's Settings, gear icon top right)

1. Profiles & shows: rename the three profiles, add shows with their links.
2. Apps row: tick the apps you want pinned, in order.
3. Wallpapers: pick which of the 5 scenes rotate and how often.
4. System: "Allow display over other apps" and "Allow exact alarms" so the sleep timer can pull the launcher forward when it fires.

## What the sleep timer does

The moon button sets night mode (dimmed, warm) and a timer. When it fires the launcher pauses whatever is playing and shows a black screen. A normal app can't power the box off, so this is as far as Android lets it go. Any remote button wakes it.

## Building locally

Android Studio: open the folder, Build > Build APK. Signing uses `keystore/launcher.jks` (checked in on purpose so every build installs over the previous one).
