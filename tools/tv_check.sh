#!/bin/bash
# Runs inside the Android TV emulator job. Everything lands in ci-out/.
OUT=ci-out; mkdir -p $OUT
PKG=za.co.dipixel.launcher
APK=app/build/outputs/apk/debug/app-debug.apk
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
log() { echo "== $*" | tee -a $OUT/steps.txt; }
# the emulator asks "Allow USB debugging?" when Mi TV connects to its own adb: tick Always allow, then Allow
allow_debug() {
  for i in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    X=$(adb shell cat /sdcard/ui.xml 2>/dev/null)
    echo "$X" | grep -q "Allow USB debugging" || return 0
    for label in "Always allow" 'text="Allow"'; do
      b=$(echo "$X" | tr '>' '\n' | grep -F "$label" | grep -o 'bounds="[^"]*"' | head -1 | grep -o '[0-9]\+' | tr '\n' ' ')
      set -- $b; [ -n "$4" ] && adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 1
    done
    sleep 2
  done
}

log "install"
adb root; sleep 3; adb wait-for-device
adb install -r -g $APK 2>&1 | tee -a $OUT/steps.txt
for l in com.google.android.tvlauncher com.google.android.leanbacklauncher com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
  adb shell pm disable-user --user 0 $l >/dev/null 2>&1
done
adb shell am start -n $PKG/.MainActivity
sleep 12

log "start the key helper the same way Mi TV does on the box"
APKPATH=$(adb shell pm path $PKG | head -1 | cut -d: -f2 | tr -d '\r')
timeout 15 adb shell "(CLASSPATH=$APKPATH setsid nohup app_process /system/bin za.co.dipixel.launcher.hub.InjectorMain </dev/null >/data/local/tmp/mitv_injector.log 2>&1 &); exit 0"
sleep 3
adb shell cat /data/local/tmp/mitv_injector.log | tee -a $OUT/steps.txt
adb shell settings put secure screensaver_enabled 1
adb shell settings put secure screensaver_components $PKG/$PKG.art.ArtDream
adb shell am force-stop $PKG; sleep 1
adb shell am start -n $PKG/.MainActivity
sleep 25
allow_debug; sleep 3
shot 01_home

log "pair like a phone would"
adb forward tcp:8765 tcp:8765
TOKEN=ci-test
H=(-H "x-hub-token: $TOKEN" -H 'Content-Type: application/json')
for t in "The Big Bang Theory" "The Night Agent" "Stranger Things"; do
  curl -s -m 40 -X POST localhost:8765/show/add "${H[@]}" -d "{\"title\":\"$t\",\"pkg\":\"com.android.tv.settings\",\"label\":\"Netflix\"}" >> $OUT/add.json; echo >> $OUT/add.json
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
curl -s -m 40 localhost:8765/diag "${H[@]}" | tee $OUT/diag.json; echo
curl -s -m 40 localhost:8765/state "${H[@]}" > $OUT/state.json

log "key latency through the hub (each call presses a key on the TV)"
for k in down right left right left up volup voldown; do
  curl -s -m 40 -o /dev/null -w "$k %{time_total}s\n" -X POST localhost:8765/key "${H[@]}" -d "{\"key\":\"$k\"}" | tee -a $OUT/latency.txt
done
sleep 2; shot 07_after_hub_keys

log "agent without a key (built-in rules)"
for q in "what's the latest superman movie" "where can i watch the big bang theory" "play it"; do
  echo ">> $q" >> $OUT/agent.txt
  curl -s -m 40 -X POST localhost:8765/agent "${H[@]}" -d "{\"session\":\"ci\",\"text\":\"$q\"}" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("reply")); [print("   card:",c["title"],c["year"],c["on"],c["elsewhere"]) for c in d.get("cards",[])]' >> $OUT/agent.txt 2>&1
done
cat $OUT/agent.txt

allow_debug
log "agent: songs and DStv"
for q in "play staying alive" "play the big bang theory" "play shaka ilembe on dstv"; do
  echo ">> $q" >> $OUT/agent.txt
  curl -s -m 40 -X POST localhost:8765/agent "${H[@]}" -d "{\"session\":\"ci2\",\"text\":\"$q\"}" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("reply"))' >> $OUT/agent.txt 2>&1
  adb shell input keyevent 3; sleep 3
done

log "remote Assistant button"
adb shell cmd role get-role-holders --user 0 android.app.role.ASSISTANT >> $OUT/steps.txt 2>&1
adb shell settings get secure assistant >> $OUT/steps.txt 2>&1
adb shell am start -a android.intent.action.ASSIST >> $OUT/steps.txt 2>&1; sleep 5; shot 10_assist
adb shell input keyevent 4; sleep 2; adb shell input keyevent 4; sleep 2
adb shell settings get secure enabled_accessibility_services >> $OUT/steps.txt 2>&1

log "art + lofi mode"
curl -s -m 40 -X POST localhost:8765/screensaver "${H[@]}" -d '{}' > $OUT/screensaver.json; sleep 12; shot 08_art_mode; timeout 10 adb shell input keyevent 23; sleep 3; shot 09_art_skip
adb shell input keyevent 4; sleep 3

adb shell cat /data/local/tmp/mitv_injector.log > $OUT/injector.log 2>&1
adb shell ps -A | grep -i -E "app_process|dipixel" > $OUT/ps.txt
timeout 30 adb logcat -d -v brief > $OUT/logcat.txt
exit 0
