param(
    [string]$Source = (Join-Path $PSScriptRoot '../docs/data/namyang-conquest-source.json'),
    [string]$Destination = (Join-Path $PSScriptRoot '../app/src/main/assets/conquest/namyang.segments')
)
$ErrorActionPreference = 'Stop'
$culture = [System.Globalization.CultureInfo]::InvariantCulture
$data = Get-Content -LiteralPath $Source -Raw | ConvertFrom-Json
$nodes = @{}
foreach ($node in $data.nodes) { $nodes[[string]$node.id] = $node }
$neighbors = @{}
foreach ($way in $data.ways) {
    for ($i=1; $i -lt $way.nodes.Count; $i++) {
        $a=[string]$way.nodes[$i-1]; $b=[string]$way.nodes[$i]
        if (!$neighbors.ContainsKey($a)) { $neighbors[$a]=[System.Collections.Generic.HashSet[string]]::new() }
        if (!$neighbors.ContainsKey($b)) { $neighbors[$b]=[System.Collections.Generic.HashSet[string]]::new() }
        [void]$neighbors[$a].Add($b); [void]$neighbors[$b].Add($a)
    }
}
function Get-Length($points) {
    $sum=0.0
    for ($j=1; $j -lt $points.Count; $j++) {
        $a=$points[$j-1]; $b=$points[$j]
        $dy=([double]$b.lat-[double]$a.lat)*[Math]::PI/180*6371008.8
        $dx=([double]$b.lon-[double]$a.lon)*[Math]::PI/180*6371008.8*[Math]::Cos(([double]$a.lat+[double]$b.lat)/2*[Math]::PI/180)
        $sum += [Math]::Sqrt($dx*$dx+$dy*$dy)
    }
    return $sum
}
$candidates=@()
foreach ($way in ($data.ways | Where-Object { $_.candidate } | Sort-Object id)) {
    if ($way.tags.'oneway:conditional') { continue }
    if ($way.tags.oneway -and $way.tags.oneway -notin @('no','yes','1','true','-1')) { continue }
    if ($way.tags.junction -in @('roundabout','circular') -or $way.tags.highway -in @('motorway','motorway_link')) { continue }
    $cuts=@(0..($way.nodes.Count-1) | Where-Object { $neighbors[[string]$way.nodes[$_]].Count -ge 3 })
    for ($i=1; $i -lt $cuts.Count; $i++) {
        $ids=@($way.nodes[$cuts[$i-1]..$cuts[$i]])
        $points=@($ids | ForEach-Object { $nodes[[string]$_] })
        $length=Get-Length $points
        if ($length -lt 100 -or $length -gt 450) { continue }
        $candidates += [pscustomobject]@{way=$way; ids=$ids; points=$points; length=$length}
    }
}
# Small deterministic prototype subset; expand only by changing the dataset version.
$selected=@($candidates | Sort-Object @{e={$_.way.id}},@{e={$_.ids[0]}} | Select-Object -First 3)
if ($selected.Count -ne 3) { throw 'Expected three eligible intersection-to-intersection roads' }
$lines=[System.Collections.Generic.List[string]]::new()
$lines.Add('PLEOS_CONQUEST_SEGMENTS_V1')
$lines.Add($data.datasetVersion)
foreach ($entry in $selected) {
    $way=$entry.way
    $directions=if ($way.tags.oneway -eq '-1') { @('reverse') } elseif ($way.tags.oneway -in @('yes','1','true')) { @('forward') } else { @('forward','reverse') }
    foreach ($direction in $directions) {
        $indices=if ($direction -eq 'forward') { @(0..($entry.ids.Count-1)) } else { @(($entry.ids.Count-1)..0) }
        $from=$entry.ids[$indices[0]]; $to=$entry.ids[$indices[-1]]
        $coordinates=@($indices | ForEach-Object {
            $p=$entry.points[$_]
            ([double]$p.lat).ToString('R',$culture)+','+([double]$p.lon).ToString('R',$culture)
        }) -join ';'
        $id="osm:way:$($way.id):v$($way.version):$from`:$to"
        $lines.Add("$id|$($way.tags.name)|$from|$to|$coordinates")
    }
    Write-Output ("way={0} name={1} from={2} to={3} length={4:N1}m" -f $way.id,$way.tags.name,$entry.ids[0],$entry.ids[-1],$entry.length)
}
[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination))
[System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($Destination),($lines -join "`n")+"`n",[System.Text.UTF8Encoding]::new($false))
