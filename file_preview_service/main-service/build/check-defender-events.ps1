$events = Get-WinEvent -LogName 'Microsoft-Windows-Windows Defender/Operational' -MaxEvents 5000 -ErrorAction SilentlyContinue |
  Where-Object { $_.TimeCreated -gt (Get-Date).AddDays(-2) -and $_.Id -in 1116,1117,5001,5004,5007,5010,5008,5011 }
if ($events) {
  $events | Select-Object -First 15 TimeCreated, Id, @{N='Msg';E={$_.Message.Substring(0,[Math]::Min(200,$_.Message.Length))}} | Format-List
} else {
  Write-Host "NO matching Defender events in the last 2 days"
}
