param([string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference = 'Stop'
$requirements = Import-Csv (Join-Path $PSScriptRoot 'requirements.csv')
Push-Location $RepoRoot
try {
    $revision = git rev-parse --short HEAD
    & ./gradlew.bat :app:testDebugUnitTest --rerun
    $buildPassed = $LASTEXITCODE -eq 0
    $cases = @{}
    if ($buildPassed) {
        foreach ($file in Get-ChildItem 'app/build/test-results/testDebugUnitTest/TEST-*.xml') {
            $suite = [xml](Get-Content -Raw -LiteralPath $file.FullName)
            foreach ($case in $suite.SelectNodes('//testcase')) {
                $cases[$case.GetAttribute('name')] = $case
            }
        }
    }
    $results = foreach ($row in $requirements) {
        $method = $row.'검증 방법'
        $state = '수동 확인 필요'
        if ($method -match '^[a-z][A-Za-z0-9]+$') {
            $case = $cases[$method]
            $state = if (!$buildPassed) { '실행 실패' } elseif (!$case) { '결과 누락' } elseif ($case.SelectSingleNode('failure|error|skipped')) { '미통과' } else { '자동 판정 통과' }
        } elseif ($method -eq '실행 도구') {
            $state = '실행 보고서 생성'
        }
        [pscustomobject]@{ ID=$row.ID; Requirement=$row.'요구사항'; Scenario=$method; Result=$state; Revision=$revision; CheckedAt=(Get-Date -Format o) }
    }
    $target = 'app/build/reports/exploration-simulation.csv'
    New-Item 'app/build/reports' -ItemType Directory -Force | Out-Null
    $results | Export-Csv $target -NoTypeInformation -Encoding utf8BOM
    $results | Format-Table ID,Result,Requirement
    Write-Host (Resolve-Path $target)
    if (!$buildPassed -or ($results.Result -match '실행 실패|결과 누락|미통과')) { throw '자동 시뮬레이션 검증 실패' }
} finally { Pop-Location }
