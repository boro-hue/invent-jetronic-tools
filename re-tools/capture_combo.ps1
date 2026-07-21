# Elevated USBPcap capture for the COMBINED decode session (Commander logs CSV at the same time).
$dir = "$PSScriptRoot\captures"
$log = Join-Path $dir "combo_status.log"
$out = Join-Path $dir "combo_session.pcap"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
"[$(Get-Date -Format HH:mm:ss)] combo wrapper started" | Out-File -Encoding utf8 $log
try {
    $p = Start-Process -FilePath "C:\Program Files\USBPcap\USBPcapCMD.exe" `
        -ArgumentList "-d", "\\.\USBPcap1", "-o", "`"$out`"", "--devices", "2" `
        -PassThru -WindowStyle Hidden
    "[$(Get-Date -Format HH:mm:ss)] USBPcapCMD PID $($p.Id) - capturing 210s" | Out-File -Append -Encoding utf8 $log
    Start-Sleep -Seconds 210
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    Start-Sleep 1
    $size = (Get-Item $out -ErrorAction SilentlyContinue).Length
    "[$(Get-Date -Format HH:mm:ss)] stopped, size = $size bytes" | Out-File -Append -Encoding utf8 $log
} catch {
    "[$(Get-Date -Format HH:mm:ss)] ERROR: $($_.Exception.Message)" | Out-File -Append -Encoding utf8 $log
}
