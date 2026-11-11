# Runs the bot's test scenes on a fresh superflat dev world and prints the report.
#   scripts\run-scenarios.ps1                 all scenes
#   scripts\run-scenarios.ps1 W2,H1           only scenes whose id starts with W2 or H1
#   scripts\run-scenarios.ps1 W -Repeat 5     the W scenes 5 times over, each on a fresh world,
#                                             then a pass count per scene (scenes with chance in
#                                             them, fish and drifting drops, need more than one run)
#   scripts\run-scenarios.ps1 NE5 -Seed 1   a fixed world seed: the Nether (made from the seed alone)
#                                             is then the one of a player's world with that seed
#   scripts/run-scenarios.ps1 -Coverage       then gradlew coverageReport: build/reports/jacoco/coverageReport/html/index.html
# The report is written to run\scenario-report.md (with -Repeat: run\repeat\report-N.md and
# run\repeat\summary.md). Scenes: src\main\java\com\vale\helperbot\test\Scenarios.java
param([string[]]$Only = @("all"), [int]$Repeat = 1, [switch]$Coverage, [string]$Seed = "")

$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $root "run"
New-Item -ItemType Directory -Force $run | Out-Null
Set-Content -Encoding ascii (Join-Path $run "eula.txt") "eula=true"

# No structures: a mineshaft or a village turning up at random made scenes pass or fail by luck.
# Superflat plains: bedrock, stone up to y=36, 3 dirt, grass at y=40 (stand at y=41).
$flat = '{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":100},{"block":"minecraft:dirt","height":3},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structure_overrides":[]}'
@"
online-mode=false
# No player joins: without this the server stops ticking after a minute (since 1.21).
pause-when-empty-seconds=0
level-seed=$Seed
level-name=scenarios
level-type=minecraft\:flat
generator-settings=$($flat.Replace(':', '\:'))
spawn-protection=0
max-tick-time=-1
view-distance=6
simulation-distance=6
"@ | Set-Content -Encoding utf8 (Join-Path $run "server.properties")

$world = Join-Path $run "scenarios"
$report = Join-Path $run "scenario-report.md"

function Invoke-Scenes {
	# A fresh world every run, so results don't depend on what an earlier run left behind.
	if (Test-Path $world) { Remove-Item -Recurse -Force $world }
	if (Test-Path $report) { Remove-Item -Force $report }
	$env:HELPERBOT_SCENARIOS = $Only -join ","
	& (Join-Path $root "gradlew.bat") -p $root runServer $(if ($Coverage) { "-Pcoverage" }) --console=plain 2>&1 |
		Select-String -Pattern "SCENARIO|Exception|ERROR" | ForEach-Object { $_.Line }
	Remove-Item Env:\HELPERBOT_SCENARIOS
}

if ($Repeat -le 1) {
	Invoke-Scenes
	if (Test-Path $report) { Get-Content -Encoding utf8 $report } else { Write-Output "No report: the server stopped before the scenes finished." }
	return
}

$dir = Join-Path $run "repeat"
if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
New-Item -ItemType Directory -Force $dir | Out-Null
$pass = [char]0x2705
$order = New-Object System.Collections.Generic.List[string]
$passed = @{}
$ran = @{}
for ($i = 1; $i -le $Repeat; $i++) {
	Write-Output "=== run $i of $Repeat"
	Invoke-Scenes | Out-Null
	if (-not (Test-Path $report)) { Write-Output "run ${i}: no report (the server stopped)"; continue }
	Copy-Item $report (Join-Path $dir "report-$i.md")
	foreach ($line in Get-Content -Encoding utf8 $report) {
		# The results table: | id | (pass/fail mark) | scene | time | note |
		if ($line -match '^\| (\S+) \| (\S+) \| ') {
			$id = $Matches[1]; $mark = $Matches[2]
			if ($mark.Length -ne 1) { continue } # the timing table (second column is a time)
			if (-not $ran.ContainsKey($id)) { $order.Add($id); $ran[$id] = 0; $passed[$id] = 0 }
			$ran[$id]++
			if ($mark[0] -eq $pass) { $passed[$id]++ }
		}
	}
}
$lines = @("| id | passed | runs |", "|---|---|---|")
foreach ($id in $order) { $lines += "| $id | $($passed[$id]) | $($ran[$id]) |" }
$lines | Set-Content -Encoding utf8 (Join-Path $dir "summary.md")
$lines
