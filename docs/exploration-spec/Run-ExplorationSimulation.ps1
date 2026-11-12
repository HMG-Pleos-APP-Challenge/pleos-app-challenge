param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [string]$AndroidSerial = '',
    [switch]$WithSdk
)
$ErrorActionPreference = 'Stop'
$requirements = Import-Csv (Join-Path $PSScriptRoot 'requirements.csv')
Push-Location $RepoRoot
try {
    $revision = git rev-parse --short HEAD
    $dirty = [bool](git status --porcelain)
    & ./gradlew.bat --offline :app:testDebugUnitTest --rerun
    $buildPassed = $LASTEXITCODE -eq 0
    $cases = @{}
    if ($buildPassed) {
        foreach ($file in Get-ChildItem 'app/build/test-results/testDebugUnitTest/TEST-*.xml') {
            $suite = [xml](Get-Content -Raw -LiteralPath $file.FullName)
            foreach ($case in $suite.SelectNodes('//testcase')) { $cases[$case.GetAttribute('name')] = $case }
        }
    }
    $deviceLog = ''
    $sdkLog = ''
    $devicePassed = $false
    $sdkPassed = $false
    if ($AndroidSerial) {
        $adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
        function Device {
            $output = & $adb -s $AndroidSerial @args 2>&1
            if ($LASTEXITCODE) { throw ($output -join "`n") }
            return $output
        }
        & ./gradlew.bat --offline assembleDebug assembleDebugAndroidTest
        if ($LASTEXITCODE) { throw '장치 테스트 빌드 실패' }
        Device install -r app/build/outputs/apk/debug/app-debug.apk | Out-Host
        Device install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | Out-Host
        $androidUser = (Device shell am get-current-user).Trim()
        $deviceLog = (Device shell am instrument --user $androidUser -w com.daehyundai.map.test/com.daehyundai.map.ExplorationRequirementsProbe) -join "`n"
        $devicePassed = $deviceLog.Contains('PASS: exploration requirement simulations completed') -and !$deviceLog.Contains('FAIL:')
        if ($WithSdk) {
            $init = Join-Path $RepoRoot 'app/build/exploration-sdk-probe.init.gradle'
            'allprojects { plugins.withId("com.android.application") { androidComponents.finalizeDsl { dsl -> dsl.defaultConfig.testInstrumentationRunner = "com.daehyundai.map.SdkUnlockProbe" } } }' |
                Set-Content $init
            & ./gradlew.bat --offline -I $init assembleDebugAndroidTest
            if ($LASTEXITCODE) { throw 'SDK 테스트 빌드 실패' }
            Device install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | Out-Host
            $appOp = (Device shell appops get --user $androidUser com.daehyundai.map android:mock_location) -join "`n"
            $oldMode = if ($appOp -match 'MOCK_LOCATION:\s*(allow|ignore|deny|default|foreground)') { $Matches[1] } else { 'default' }
            try {
                Device shell appops set --user $androidUser com.daehyundai.map android:mock_location allow
                $sdkLog = (Device shell am instrument --user $androidUser -w -e syntheticEmulator true -e mode background com.daehyundai.map.test/com.daehyundai.map.SdkUnlockProbe) -join "`n"
                $sdkPassed = $sdkLog.Contains('PASS: background records restored') -and !$sdkLog.Contains('FAILED:')
            } finally {
                Device shell appops set --user $androidUser com.daehyundai.map android:mock_location $oldMode
            }
        }
    }
    $results = foreach ($row in $requirements) {
        $case = $cases[$row.'검증 방법']
        $unit = if (!$buildPassed) { '실행 실패' } elseif (!$case) { '결과 누락' } elseif ($case.SelectSingleNode('failure|error|skipped')) { '미통과' } else { '자동 판정 통과' }
        $device = switch ($row.'장치 시뮬레이션') {
            '' { '해당 없음' }
            'SDK' { if (!$WithSdk) { '미실행' } elseif ($sdkPassed) { '통과' } else { '실패' } }
            default { if (!$AndroidSerial) { '미실행' } elseif ($devicePassed -and $deviceLog.Contains($_+' PASS:')) { '통과' } elseif ($devicePassed -and $deviceLog.Contains($_+' ')) { '통과' } else { '실패' } }
        }
        [pscustomobject]@{ ID=$row.ID; Requirement=$row.'요구사항'; Unit=$unit; Device=$device; Scope=$row.'검증 범위'; Revision=$revision; Dirty=$dirty; CheckedAt=(Get-Date -Format o) }
    }
    $target = 'app/build/reports/exploration-simulation.csv'
    New-Item 'app/build/reports' -ItemType Directory -Force | Out-Null
    $results | Export-Csv $target -NoTypeInformation -Encoding utf8BOM
    $deviceLog | Set-Content 'app/build/reports/exploration-device-simulation.txt'
    $sdkLog | Set-Content 'app/build/reports/exploration-sdk-simulation.txt'
    $results | Format-Table ID,Unit,Device,Requirement
    Write-Host (Resolve-Path $target)
    if (!$buildPassed -or ($results.Unit -match '실행 실패|결과 누락|미통과') -or ($results.Device -contains '실패')) { throw '시뮬레이션 검증 실패' }
} finally { Pop-Location }
