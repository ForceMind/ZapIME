# SPDX-License-Identifier: GPL-3.0-only
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
function Checked([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "Native command failed: $Command" }
}
Checked { python scripts/verify_upstream.py }
Checked { python scripts/fetch_resources.py }
Checked { cargo build --release --locked -p zapime-core }
Checked { cmake -S platforms/windows -B target/windows-native -A x64 }
Checked { cmake --build target/windows-native --config Release }
$Stage = 'target/windows-native/Release'
New-Item -ItemType Directory -Force "$Stage/EngineResources" | Out-Null
Copy-Item target/resources/* "$Stage/EngineResources/" -Force
Copy-Item third_party "$Stage/third_party" -Recurse -Force
Copy-Item LICENSE "$Stage/LICENSE" -Force
Checked { python scripts/collect_rust_notices.py x86_64-pc-windows-msvc "$Stage/rust-licenses" }
Push-Location $Stage
try { Checked { ./ZapIMEWindowsTests.exe } } finally { Pop-Location }
Write-Output "Built Windows x64 TSF development DLL; not registered, signed or system-input accepted."
