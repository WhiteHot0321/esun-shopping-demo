# 一次啟動 MySQL/Redis (docker) + 後端 + 前端，後端與前端各開一個視窗顯示 log。
# 用法（在專案根目錄）：  .\scripts\dev.ps1            # 全部啟動
#                         .\scripts\dev.ps1 -NoDocker  # 略過 docker compose（DB 已在跑時）
param([switch]$NoDocker)

$root = Split-Path -Parent $PSScriptRoot

if (-not $NoDocker) {
    # Docker Desktop 沒開時自動啟動，並等 engine 就緒（最多約 3 分鐘）
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        $dockerExe = "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
        if (-not (Test-Path $dockerExe)) { Write-Error "找不到 Docker Desktop：$dockerExe"; exit 1 }
        Write-Host "啟動 Docker Desktop，等待 engine 就緒..."
        Start-Process $dockerExe
        $ready = $false
        for ($i = 0; $i -lt 90; $i++) {
            Start-Sleep -Seconds 2
            docker info *> $null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        }
        if (-not $ready) { Write-Error "Docker engine 3 分鐘內未就緒，已中止"; exit 1 }
    }
    Push-Location $root
    docker compose up -d mysql redis
    Pop-Location
}

# Spring Boot 不會讀 .env，所以在子視窗內先匯出成環境變數再啟動後端（同 README「3. 啟動後端」）
$backendCmd = @"
Set-Location '$root'
Get-Content .env | Where-Object { `$_ -match '^\s*[^#\s][^=]*=' } | ForEach-Object { `$k,`$v = `$_ -split '=',2; Set-Item "env:`$(`$k.Trim())" `$v.Trim() }
Set-Location backend
mvn spring-boot:run
"@

$frontendCmd = @"
Set-Location '$root\frontend'
npm run dev
"@

Start-Process powershell -ArgumentList '-NoExit', '-Command', $backendCmd
Start-Process powershell -ArgumentList '-NoExit', '-Command', $frontendCmd

Write-Host "後端:  http://localhost:8080"
Write-Host "前端:  http://localhost:5173"
Write-Host "關閉：在各自視窗按 Ctrl+C（docker 服務用 docker compose stop 停止）"
