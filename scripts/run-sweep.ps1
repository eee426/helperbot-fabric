# Plans every item from an empty bag at the spawn of a generated world, and lists what it can't.
#   scripts\run-sweep.ps1             seed 12345
#   scripts\run-sweep.ps1 -Seed 42
# The list is written to run\plan-sweep.txt: OK / NO / REFUSED, item, kinds of step, problems.
# The test scenes use a flat world without structures, so this is what shows if the knowledge
# of structures and biomes really works. It leaves server.properties as it found it.
param([string]$Seed = "12345")

$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $root "run"
New-Item -ItemType Directory -Force $run | Out-Null
Set-Content -Encoding ascii (Join-Path $run "eula.txt") "eula=true"
$properties = Join-Path $run "server.properties"
$kept = if (Test-Path $properties) { Get-Content -Raw $properties } else { $null }
@"
online-mode=false
# No player joins: without this the server stops ticking after a minute (since 1.21).
pause-when-empty-seconds=0
level-name=sweep
level-seed=$Seed
spawn-protection=0
max-tick-time=-1
view-distance=8
simulation-distance=6
"@ | Set-Content -Encoding utf8 $properties
$world = Join-Path $run "sweep"
if (Test-Path $world) { Remove-Item -Recurse -Force $world }
$env:HELPERBOT_BENCH = "sweep"
& (Join-Path $root "gradlew.bat") -p $root runServer --console=plain 2>&1 |
	Select-String -Pattern "BENCH|structure index|chest tables with|indexes built|Exception|BUILD FAILED" | ForEach-Object { $_.Line }
Remove-Item Env:\HELPERBOT_BENCH
if (Test-Path $world) { Remove-Item -Recurse -Force $world }
if ($null -ne $kept) { Set-Content -NoNewline -Encoding utf8 $properties $kept }
