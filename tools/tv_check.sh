#!/bin/bash
# Runs inside the Android TV emulator job. Everything lands in ci-out/.
OUT=ci-out; mkdir -p $OUT
PKG=za.co.dipixel.launcher
APK=app/build/outputs/apk/debug/app-debug.apk
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
log() { echo "== $*" | tee -a $OUT/steps.txt; }

log "install"
adb install -r -g $APK 2>&1 | tee -a $OUT/steps.txt
adb shell cmd role add-role-holder android.app.role.HOME $PKG 2>&1 | tee -a $OUT/steps.txt
adb shell cmd package set-home-activity $PKG/.MainActivity 2>&1 | tee -a $OUT/steps.txt
adb shell am start -n $PKG/.MainActivity
sleep 8

log "authorise the app's own adb key (on a real box the TV shows Allow debugging once)"
adb root; sleep 4; adb wait-for-device
for i in 1 2 3 4 5 6; do adb shell run-as $PKG cat files/adbkey.pub > $OUT/adbkey.pub 2>/dev/null; [ -s $OUT/adbkey.pub ] && break; sleep 3; done
if [ -s $OUT/adbkey.pub ]; then
  adb push $OUT/adbkey.pub /data/local/tmp/k.pub
  adb shell "cat /data/local/tmp/k.pub >> /data/misc/adb/adb_keys; chmod 640 /data/misc/adb/adb_keys"
fi
adb shell "getprop service.adb.tcp.port; getprop persist.adb.tcp.port" | tee -a $OUT/steps.txt
adb shell setprop service.adb.tcp.port 5555; adb shell "setprop ctl.restart adbd"; sleep 5; adb wait-for-device
adb shell am force-stop $PKG; sleep 1
adb shell am start -n $PKG/.MainActivity
sleep 25
shot 01_home

log "pair like a phone would"
adb shell run-as $PKG cat shared_prefs/hub.xml > $OUT/hub.xml
CODE=$(grep -o 'name="code">[0-9]*' $OUT/hub.xml | grep -o '[0-9]*$')
adb forward tcp:8765 tcp:8765
TOKEN=$(curl -s -X POST localhost:8765/pair -H 'Content-Type: application/json' -d "{\"code\":\"$CODE\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))')
echo "code=$CODE token=${TOKEN:0:8}" | tee -a $OUT/steps.txt
H=(-H "x-hub-token: $TOKEN" -H 'Content-Type: application/json')
for t in "The Big Bang Theory" "The Night Agent" "Stranger Things"; do
  curl -s -X POST localhost:8765/show/add "${H[@]}" -d "{\"title\":\"$t\",\"pkg\":\"com.android.tv.settings\",\"label\":\"Netflix\"}" >> $OUT/add.json; echo >> $OUT/add.json
done
sleep 20
adb shell input keyevent 3; sleep 6
shot 02_home_with_shows

log "navigate"
adb shell input keyevent 19; sleep 4; shot 03_show_focused
adb shell input keyevent 22; sleep 4; shot 04_show2_focused
adb shell input keyevent 20; sleep 4; shot 05_back_on_dock
adb shell input keyevent 19; adb shell input keyevent 19; sleep 3; shot 06_top_bar

log "diagnostics"
curl -s localhost:8765/diag "${H[@]}" | tee $OUT/diag.json; echo
curl -s localhost:8765/state "${H[@]}" > $OUT/state.json

log "key latency through the hub (each call presses a key on the TV)"
for k in down right left right left up volup voldown; do
  curl -s -o /dev/null -w "$k %{time_total}s\n" -X POST localhost:8765/key "${H[@]}" -d "{\"key\":\"$k\"}" | tee -a $OUT/latency.txt
done
sleep 2; shot 07_after_hub_keys

log "screensaver"
adb shell am start -n com.android.systemui/.Somnambulator; sleep 12; shot 08_screensaver
adb shell input keyevent 4; sleep 3

adb shell cat /data/local/tmp/mitv_injector.log > $OUT/injector.log 2>&1
adb shell ps -A | grep -i -E "app_process|dipixel" > $OUT/ps.txt
adb logcat -d -v brief > $OUT/logcat.txt
exit 0
