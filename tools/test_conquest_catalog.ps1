$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../docs/data/namyang-conquest-source.json'
$asset = Join-Path $PSScriptRoot '../app/src/main/assets/conquest/namyang.segments'
$temporarySource = [IO.Path]::GetTempFileName()
$temporaryOutput = [IO.Path]::GetTempFileName()
try {
    & (Join-Path $PSScriptRoot 'build_conquest_catalog.ps1') -Source $source -Destination $temporaryOutput | Out-Null
    if ((Get-FileHash $asset).Hash -ne (Get-FileHash $temporaryOutput).Hash) { throw 'Catalog is not reproducible' }
    $data = Get-Content $source -Raw | ConvertFrom-Json
    foreach ($way in $data.ways | Where-Object candidate) { $way.tags | Add-Member -NotePropertyName oneway -NotePropertyValue 'yes' -Force }
    $data | ConvertTo-Json -Depth 14 | Set-Content $temporarySource -Encoding utf8NoBOM
    & (Join-Path $PSScriptRoot 'build_conquest_catalog.ps1') -Source $temporarySource -Destination $temporaryOutput | Out-Null
    $forward = @(Get-Content $temporaryOutput | Select-Object -Skip 2)
    if ($forward.Count -ne 3) { throw 'One-way=yes must generate only one direction per road' }
    foreach ($way in $data.ways | Where-Object candidate) { $way.tags.oneway='-1' }
    $data | ConvertTo-Json -Depth 14 | Set-Content $temporarySource -Encoding utf8NoBOM
    & (Join-Path $PSScriptRoot 'build_conquest_catalog.ps1') -Source $temporarySource -Destination $temporaryOutput | Out-Null
    $reverse = @(Get-Content $temporaryOutput | Select-Object -Skip 2)
    if ($reverse.Count -ne 3) { throw 'One-way=-1 must generate only one direction per road' }
    for ($i=0; $i -lt 3; $i++) {
        $a=$forward[$i].Split('|'); $b=$reverse[$i].Split('|')
        if ($a[2] -ne $b[3] -or $a[3] -ne $b[2]) { throw 'Reverse direction endpoints do not match' }
    }
    foreach ($way in $data.ways | Where-Object candidate) { $way.tags.oneway='reversible' }
    $data | ConvertTo-Json -Depth 14 | Set-Content $temporarySource -Encoding utf8NoBOM
    $rejected=$false
    try { & (Join-Path $PSScriptRoot 'build_conquest_catalog.ps1') -Source $temporarySource -Destination $temporaryOutput | Out-Null }
    catch { $rejected=$true }
    if (!$rejected) { throw 'Unknown direction must not silently create two-way roads' }
    Write-Output 'PASS: reproducibility, one-way forward, one-way reverse, unknown direction rejection'
} finally {
    Remove-Item -LiteralPath $temporarySource,$temporaryOutput -Force
}
