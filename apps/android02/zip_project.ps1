$dst = Join-Path (Get-Location) "android_02.zip"
if (Test-Path $dst) { Remove-Item -Force $dst } $exclude = @(
  "\.git\",
  "\.gradle\",
  "\.idea\",
  "\.vs\",
  "\.vscode\",
  "\build\",
  "\out\",
  "\captures\",
  "\externalNativeBuild\",
  "\.cxx\",
  "\app\build\",
  "\gradle\caches\"
)
$files = Get-ChildItem -File -Recurse -Force | Where-Object {
  $p = $_.FullName
  -not ($exclude | Where-Object { $p -match [regex]::Escape($_) })
}
Compress-Archive -Path $files.FullName -DestinationPath $dst
