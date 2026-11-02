# Runs the bot's test scenes on a fresh superflat dev world and prints the report.
#   scripts\run-scenarios.ps1            all scenes
#   scripts\run-scenarios.ps1 W2,H1      only scenes whose id starts with W2 or H1
# The report is written to run\scenario-report.md. Scenes: src\main\java\com\vale\helperbot\test\Scenarios.java
param([string[]]$Only = @("all"))

$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $root "run"
New-Item -ItemType Directory -Force $run | Out-Null
Set-Content -Encoding ascii (Join-Path $run "eula.txt") "eula=true"

# Superflat plains: bedrock, stone up to y=36, 3 dirt, grass at y=40 (stand at y=41).
$flat = '{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":100},{"block":"minecraft:dirt","height":3},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
@"
online-mode=false
level-name=scenarios
level-type=minecraft\:flat
generator-settings=$($flat.Replace(':', '\:'))
spawn-protection=0
max-tick-time=-1
view-distance=6
simulation-distance=6
"@ | Set-Content -Encoding utf8 (Join-Path $run "server.properties")

# A fresh world every run, so results don't depend on what an earlier run left behind.
$world = Join-Path $run "scenarios"
if (Test-Path $world) { Remove-Item -Recurse -Force $world }
$report = Join-Path $run "scenario-report.md"
if (Test-Path $report) { Remove-Item -Force $report }

$env:HELPERBOT_SCENARIOS = $Only -join ","
& (Join-Path $root "gradlew.bat") -p $root runServer --console=plain 2>&1 |
	Select-String -Pattern "SCENARIO|Exception|ERROR" | ForEach-Object { $_.Line }
Remove-Item Env:\HELPERBOT_SCENARIOS

if (Test-Path $report) { Get-Content -Encoding utf8 $report } else { Write-Output "No report: the server stopped before the scenes finished." }
