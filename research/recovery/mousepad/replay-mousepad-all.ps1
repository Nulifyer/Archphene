param([int]$MaxEntries = 10000, [switch]$LogFailures)
$dst=(Resolve-Path 'prototypes/mousepad-android-app/src/org/archphene/linux/kcalc').Path
Get-ChildItem -LiteralPath $dst -File | Where-Object {$_.Name -like 'MainActivity$*.java'} | ForEach-Object {Remove-Item -LiteralPath $_.FullName -Force}
Copy-Item -LiteralPath 'prototypes/kcalc-android-app/src/org/archphene/linux/kcalc/MainActivity.java' -Destination (Join-Path $dst 'MainActivity.java') -Force
$lf=[string][char]10
$crlf=[string][char]13+$lf
function Apply-Hunk([ref]$content,[Collections.Generic.List[string]]$old,[Collections.Generic.List[string]]$new,[string]$stamp) {
  if($old.Count -eq 0 -and $new.Count -eq 0){return}
  $ot=$old -join $script:lf
  $nt=$new -join $script:lf
  $i=$content.Value.IndexOf($ot,[StringComparison]::Ordinal)
  if($i -lt 0){throw "patch hunk missing $stamp"}
  $content.Value=$content.Value.Remove($i,$ot.Length).Insert($i,$nt)
  $old.Clear();$new.Clear()
}
function Apply-RecordedPatch([string]$patch,[string]$stamp) {
  $target='prototypes/mousepad-android-app/src/org/archphene/linux/kcalc/MainActivity.java'
  $path=(Resolve-Path $target).Path
  $content=[IO.File]::ReadAllText($path).Replace($script:crlf,$script:lf)
  $active=$false;$inHunk=$false
  $old=[Collections.Generic.List[string]]::new()
  $new=[Collections.Generic.List[string]]::new()
  foreach($raw in ($patch.Replace($script:crlf,$script:lf) -split [char]10)){
    $line=$raw.TrimEnd([char]13)
    if($line.StartsWith('*** Update File: ')){
      if($inHunk -and $active){Apply-Hunk ([ref]$content) $old $new $stamp}
      $active=$line.Substring(17) -eq $target
      $inHunk=$false
      continue
    }
    if($line.StartsWith('@@')){
      if($inHunk -and $active){Apply-Hunk ([ref]$content) $old $new $stamp}
      $inHunk=$active
      continue
    }
    if(-not $active -or -not $inHunk){continue}
    if($line.StartsWith('+')){$new.Add($line.Substring(1));continue}
    if($line.StartsWith('-')){$old.Add($line.Substring(1));continue}
    if($line.StartsWith(' ')){$v=$line.Substring(1);$old.Add($v);$new.Add($v)}
  }
  if($inHunk -and $active){Apply-Hunk ([ref]$content) $old $new $stamp}
  [IO.File]::WriteAllText($path,$content,[Text.UTF8Encoding]::new($false))
}
$data=Get-Content (Join-Path $PSScriptRoot "replay-mousepad-all.json") -Raw|ConvertFrom-Json
$ok=0;$failed=0;$patchOk=0;$index=0
foreach($entry in $data.replay){
  $index++
  if($index -gt $MaxEntries){break}
  try{
    if($entry.kind -eq 'patch'){
      Apply-RecordedPatch ([string]$entry.value) ([string]$entry.timestamp)
      $patchOk++
    }else{
      & 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -Command ([string]$entry.value) *>&1 | Out-Null
      if($LASTEXITCODE -ne 0){throw "child PowerShell exit $LASTEXITCODE"}
    }
    $ok++
    if($LogFailures){Write-Output "ok $index $($entry.timestamp) $($entry.kind)"}
  }catch{
    $failed++
    if($LogFailures){Write-Output "FAIL $index $($entry.timestamp) $($entry.kind) $($_.Exception.Message)"}
  }
}
Write-Output "RESULT ok=$ok failed=$failed patchOk=$patchOk length=$((Get-Item (Join-Path $dst 'MainActivity.java')).Length) lines=$((Get-Content (Join-Path $dst 'MainActivity.java')).Count)"