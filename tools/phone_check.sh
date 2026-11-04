#!/bin/bash
# Mi Remote on a phone emulator: pairing screen, then each tab (no TV attached, so it shows Reconnecting).
OUT=ci-out/phone; mkdir -p $OUT
PKG=za.co.dipixel.remote
adb install -r -g remote/build/outputs/apk/debug/remote-debug.apk
adb shell am start -n $PKG/.MainActivity; sleep 10
adb exec-out screencap -p > $OUT/01_pair.png
adb shell am force-stop $PKG
adb shell run-as $PKG mkdir -p shared_prefs
adb shell "run-as $PKG sh -c 'echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?><map><string name=\\\"host\\\">10.0.2.2</string><string name=\\\"token\\\">test</string></map>\" > shared_prefs/remote.xml'"
adb shell am start -n $PKG/.MainActivity; sleep 8
adb exec-out screencap -p > $OUT/02_remote.png
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | head -1); X=${W%x*}; Y=${W#*x}
for i in 1 2 3 4; do
  adb shell input tap $(( X * (2*i+1) / 10 )) $(( Y - 120 )); sleep 4
  adb exec-out screencap -p > $OUT/0$((i+2))_tab$i.png
done
adb logcat -d -v brief | grep -iE "AndroidRuntime|FATAL|remote" > $OUT/logcat.txt
exit 0
