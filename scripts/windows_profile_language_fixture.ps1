# SPDX-License-Identifier: GPL-3.0-only
# Disposable-runner fixture only; never used by installers or local ctest.
$ErrorActionPreference = 'Stop'
if ($env:OS -cne 'Windows_NT' -or $env:GITHUB_ACTIONS -cne 'true' -or
    $env:RUNNER_ENVIRONMENT -cne 'github-hosted' -or
    $env:GITHUB_RUN_ID -cnotmatch '\A[0-9]+\z' -or
    $env:GITHUB_RUN_ATTEMPT -cnotmatch '\A[0-9]+\z') {
    throw 'dedicated_github_hosted_runner_required_language_fixture'
}
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
function Snapshot-Languages($Languages) {
    $values = @($Languages | ForEach-Object {
        [pscustomobject][ordered]@{
            tag = $_.LanguageTag
            tips = @($_.InputMethodTips)
            spell = $_.Spellchecking
            handwriting = $_.Handwriting
        }
    })
    ConvertTo-Json -InputObject $values -Depth 6 -Compress
}
$original = Get-WinUserLanguageList
if ($original.Count -lt 1 -or $original.Count -gt 32) { throw 'unexpected_original_language_count' }
$before = Snapshot-Languages $original
$hadChinese = @($original | Where-Object { $_.LanguageTag -eq 'zh-CN' }).Count -gt 0
$executable = Join-Path (Split-Path $PSScriptRoot -Parent) 'target/windows-native/Release/ZapIMEWindowsProfileProbe.exe'
if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'missing_exact_profile_probe' }
$modified = $false
$probeCode = 1
try {
    Write-Output ("language_fixture original_count={0} chinese_present={1}" -f $original.Count, [int]$hadChinese)
    if (-not $hadChinese) {
        # Keep the original list unmodified for restoration.
        $temporary = New-WinUserLanguageList -Language ($original[0].LanguageTag)
        $temporary.Clear()
        foreach ($entry in $original) { [void]$temporary.Add($entry) }
        $chinese = New-WinUserLanguageList -Language 'zh-CN'
        [void]$temporary.Add($chinese[0])
        $modified = $true # Restore even after a partially failed setter.
        Set-WinUserLanguageList -LanguageList $temporary -Force
    }
    $current = Get-WinUserLanguageList
    if (@($current | Where-Object { $_.LanguageTag -eq 'zh-CN' }).Count -ne 1) {
        throw 'chinese_user_language_not_present_after_fixture'
    }
    Write-Output 'language_fixture chinese_user_language_present=1; no language-pack installation'
    & $executable
    $probeCode = $LASTEXITCODE
}
finally {
    if ($modified) { Set-WinUserLanguageList -LanguageList $original -Force }
    $after = Snapshot-Languages (Get-WinUserLanguageList)
    if ($after -cne $before) { throw 'language_fixture_restore_mismatch; cleanup is NOT verified' }
    Write-Output 'PASS temporary Chinese user-language fixture restored and verified'
}
if ($probeCode -ne 0) { throw ("profile_probe_failed_after_fixture_restore exit={0}" -f $probeCode) }
