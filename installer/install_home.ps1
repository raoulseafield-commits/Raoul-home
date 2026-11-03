# Dipixel Home installer for the Xiaomi Box 4K (2nd Gen, Google TV)
# Downloads adb + the APK, finds the box on your Wi-Fi, installs, makes it Home.
# Rolls the stock launcher back automatically if Home doesn't land on the new one.

$ErrorActionPreference = "Continue"
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $dir "install_log.txt"
$apkUrl = "https://github.com/raoulseafield-commits/Raoul-home/releases/latest/download/home.apk"
$apk = Join-Path $dir "home.apk"
$pkg = "za.co.dipixel.launcher"
$stock = @("com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith")

function Log($m) { $t = (Get-Date).ToString("HH:mm:ss"); "$t  $m" | Tee-Object -FilePath $log -Append }
"" | Out-File $log
Log "=== Dipixel Home installer ==="

# 1. adb
$pt = Join-Path $dir "platform-tools"
$adb = Join-Path $pt "adb.exe"
if (-not (Test-Path $adb)) {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) { $adb = $found.Source; Log "Using adb already on this PC: $adb" }
    else {
        Log "Downloading Android platform-tools (adb)..."
        $zip = Join-Path $dir "platform-tools.zip"
        Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
        Expand-Archive $zip -DestinationPath $dir -Force
        Remove-Item $zip
        Log "adb ready."
    }
}

# 2. APK
Log "Downloading home.apk..."
Invoke-WebRequest $apkUrl -OutFile $apk
Log ("APK size: {0:N0} bytes" -f (Get-Item $apk).Length)

# 3. Find the box
& $adb kill-server 2>$null | Out-Null
& $adb start-server 2>$null | Out-Null
$ipFile = Join-Path $dir "box_ip.txt"
$ip = $null
if (Test-Path $ipFile) { $ip = (Get-Content $ipFile -Raw).Trim(); Log "Using IP from box_ip.txt: $ip" }
if (-not $ip) {
    Log "Scanning your network for a TV box with network debugging on (port 5555)..."
    $local = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" -and $_.PrefixOrigin -ne "WellKnown" } | Select-Object -First 1).IPAddress
    $base = ($local -split "\.")[0..2] -join "."
    Log "PC is $local, scanning $base.1-254"
    $jobs = 1..254 | ForEach-Object {
        $target = "$base.$_"
        Start-Job -ScriptBlock {
            param($t)
            $c = New-Object Net.Sockets.TcpClient
            try { $r = $c.BeginConnect($t, 5555, $null, $null); if ($r.AsyncWaitHandle.WaitOne(700) -and $c.Connected) { $t } } catch {} finally { $c.Close() }
        } -ArgumentList $target
    }
    $hits = $jobs | Wait-Job -Timeout 60 | Receive-Job
    $jobs | Remove-Job -Force
    $hits = @($hits | Where-Object { $_ })
    if ($hits.Count -eq 0) { Log "No device with port 5555 open. On the box: Settings > System > About > tap build 7x, then Developer options > Network debugging ON. Then run this again."; exit 1 }
    $ip = $hits[0]
    Log "Found: $($hits -join ', ')  -> using $ip"
    $ip | Out-File $ipFile
}

# 4. Connect (the TV may show an 'Allow debugging?' prompt: tick Always allow, press OK)
$ok = $false
for ($i = 0; $i -lt 24; $i++) {
    $out = & $adb connect "$ip`:5555" 2>&1
    $state = (& $adb devices 2>&1 | Select-String "$ip").ToString()
    if ($state -match "\sdevice$") { $ok = $true; break }
    if ($state -match "unauthorized") { Log "Waiting for you to press OK on the 'Allow debugging' prompt on the TV..." }
    else { Log "Connecting... ($out)" }
    Start-Sleep 5
}
if (-not $ok) { Log "Could not get an authorised adb connection to $ip. Accept the prompt on the TV and run again."; exit 1 }
Log "Connected to the box at $ip"
Log ("Box: " + (& $adb shell getprop ro.product.model) + " / Android " + (& $adb shell getprop ro.build.version.release))

# 5. Install
Log "Installing home.apk..."
$res = & $adb install -r -g $apk 2>&1
Log "$res"
if ("$res" -notmatch "Success") { Log "Install failed. Stopping."; exit 1 }

# Overlay permission so the sleep timer can bring the launcher forward
& $adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow 2>&1 | Out-Null

# 6. Launch it once and check it comes up
& $adb shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep 4
$focus = (& $adb shell dumpsys window 2>&1 | Select-String "mCurrentFocus|mFocusedApp" | Out-String)
if ($focus -notmatch $pkg) { Log "The launcher did not come to the front. Not touching the stock launcher. Details: $focus"; exit 1 }
Log "Launcher runs. Now making it the Home app."

# 7. Note saved Wi-Fi (disabling setupwraith can drop one saved during first setup)
$wifi = (& $adb shell dumpsys wifi 2>&1 | Select-String 'cname=' | Out-String).Trim()
if ($wifi) { Log "Saved Wi-Fi before change: $wifi" }

# 8. Disable the stock launcher + setup wizard, then test Home
foreach ($p in $stock) { $r = & $adb shell pm disable-user --user 0 $p 2>&1; Log "$p -> $r" }
& $adb shell input keyevent 3 | Out-Null   # HOME
Start-Sleep 4
$focus = (& $adb shell dumpsys window 2>&1 | Select-String "mCurrentFocus|mFocusedApp" | Out-String)
if ($focus -match $pkg) {
    Log "SUCCESS. Home button now opens Dipixel Home."
} else {
    Log "Home did not land on Dipixel Home. Rolling back the stock launcher."
    foreach ($p in $stock) { & $adb shell pm enable $p 2>&1 | Out-Null }
    & $adb shell input keyevent 3 | Out-Null
    Log "Rolled back. Details: $focus"
    exit 1
}
Log "Done. Press Home on the remote."
