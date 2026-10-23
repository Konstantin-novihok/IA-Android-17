# Скачивает исходники llama.cpp нужной версии в app\src\main\cpp\llama.cpp (Windows PowerShell)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$ver = (Get-Content "app\src\main\cpp\LLAMA_CPP_VERSION.txt" -Raw).Trim()
$dest = "app\src\main\cpp\llama.cpp"
if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
git clone --depth 1 --branch $ver https://github.com/ggml-org/llama.cpp $dest
Write-Host "llama.cpp $ver готов: $dest"
