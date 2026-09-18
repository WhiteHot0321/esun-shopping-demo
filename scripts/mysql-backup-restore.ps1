[CmdletBinding()]
param(
    [ValidateSet('Backup', 'Restore', 'Drill')]
    [string]$Action = 'Backup',
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$SourceDatabase = 'esun_shop',
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$TargetDatabase,
    [string]$BackupFile,
    [string]$BackupDirectory = (Join-Path $PSScriptRoot '..\backups'),
    [string]$Container = 'esun-mysql',
    [switch]$Force,
    [switch]$AllowSourceOverwrite
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Docker {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return $output
}

function Assert-ContainerRunning {
    $state = (Invoke-Docker -Arguments @('inspect', '-f', '{{.State.Running}}', $Container) | Out-String).Trim()
    if ($state -ne 'true') { throw "MySQL container '$Container' is not running." }
}

function Invoke-MySql {
    # SQL is shipped in via a temp file + `docker cp`, not an inline `-e "..."` argument: on Windows,
    # .NET's native-process argument encoding re-escapes embedded quotes in a single array element
    # before docker.exe ever sees them, corrupting hand-built `sh -c` quoting.
    param([Parameter(Mandatory)][string]$Sql, [string]$Database)
    $tempFile = Join-Path ([IO.Path]::GetTempPath()) "esun-sql-$([guid]::NewGuid().ToString('N')).sql"
    [IO.File]::WriteAllText($tempFile, $Sql, [Text.UTF8Encoding]::new($false))
    $containerFile = "/tmp/esun-sql-$([guid]::NewGuid().ToString('N')).sql"
    try {
        Invoke-Docker -Arguments @('cp', $tempFile, "${Container}:$containerFile") | Out-Null
        $mysql = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --batch --skip-column-names'
        if ($Database) { $mysql += " $Database" }
        return Invoke-Docker -Arguments @('exec', $Container, 'sh', '-c', "$mysql < '$containerFile'")
    }
    finally {
        Invoke-Docker -Arguments @('exec', $Container, 'rm', '-f', $containerFile) | Out-Null
        Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue
    }
}

function New-DatabaseDump {
    # -DataOnly (--no-create-info) is used only for the Drill's content-integrity hash, not for real
    # backup files: MySQL records whether a column's collation was "explicitly" resolved at CREATE
    # TABLE time, which can differ between the original schema-init path and a later restore-from-dump
    # even when the declared collation is byte-identical. That makes full structural dumps flip a
    # cosmetic CHARACTER SET/COLLATE annotation on a clean round-trip with zero data or table loss
    # (verified directly: only those annotation lines differed; every INSERT line matched exactly).
    # Hashing data-only content sidesteps that false negative without weakening the check that matters.
    param([Parameter(Mandatory)][string]$Database, [Parameter(Mandatory)][string]$Destination, [switch]$DataOnly)
    $parent = Split-Path -Parent $Destination
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $containerFile = "/tmp/esun-backup-$([guid]::NewGuid().ToString('N')).sql"
    try {
        $dump = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --single-transaction --routines --triggers --events --set-gtid-purged=OFF --default-character-set=utf8mb4 --no-tablespaces --skip-comments'
        if ($DataOnly) { $dump += ' --no-create-info' }
        Invoke-Docker -Arguments @('exec', $Container, 'sh', '-c', "$dump $Database > '$containerFile'") | Out-Null
        Invoke-Docker -Arguments @('cp', "${Container}:$containerFile", $Destination) | Out-Null
    }
    finally { Invoke-Docker -Arguments @('exec', $Container, 'rm', '-f', $containerFile) | Out-Null }
    if ((Get-Item -LiteralPath $Destination).Length -eq 0) { throw "Backup file is empty: $Destination" }
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Destination).Hash
}

function Get-SortedTableList {
    param([Parameter(Mandatory)][string]$Database)
    $rows = Invoke-MySql -Sql "SELECT table_name FROM information_schema.tables WHERE table_schema='$Database' AND table_type='BASE TABLE' ORDER BY table_name;"
    return ($rows | Where-Object { $_ }) -join ','
}

function Restore-Database {
    param([Parameter(Mandatory)][string]$Database, [Parameter(Mandatory)][string]$FromFile)
    if (-not (Test-Path -LiteralPath $FromFile -PathType Leaf)) { throw "Backup file does not exist: $FromFile" }
    if ($Database -eq $SourceDatabase -and -not $AllowSourceOverwrite) {
        throw 'Refusing to overwrite the source database. Pass -AllowSourceOverwrite only during an approved outage.'
    }
    if (-not $Force) { throw "Restore recreates database '$Database'. Pass -Force after confirming the target." }
    Invoke-MySql -Sql "DROP DATABASE IF EXISTS ``$Database``; CREATE DATABASE ``$Database`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
    $containerFile = "/tmp/esun-restore-$([guid]::NewGuid().ToString('N')).sql"
    try {
        Invoke-Docker -Arguments @('cp', $FromFile, "${Container}:$containerFile") | Out-Null
        $restore = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4'
        Invoke-Docker -Arguments @('exec', $Container, 'sh', '-c', "$restore $Database < '$containerFile'") | Out-Null
    }
    finally { Invoke-Docker -Arguments @('exec', $Container, 'rm', '-f', $containerFile) | Out-Null }
}

Assert-ContainerRunning
$resolvedBackupDirectory = [IO.Path]::GetFullPath($BackupDirectory)

switch ($Action) {
    'Backup' {
        if (-not $BackupFile) {
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $BackupFile = Join-Path $resolvedBackupDirectory "$SourceDatabase-$stamp.sql"
        }
        $resolvedBackupFile = [IO.Path]::GetFullPath($BackupFile)
        $hash = New-DatabaseDump -Database $SourceDatabase -Destination $resolvedBackupFile
        Write-Output "BACKUP_OK file=$resolvedBackupFile sha256=$hash"
    }
    'Restore' {
        if (-not $TargetDatabase -or -not $BackupFile) { throw 'Restore requires -TargetDatabase and -BackupFile.' }
        $resolvedBackupFile = [IO.Path]::GetFullPath($BackupFile)
        Restore-Database -Database $TargetDatabase -FromFile $resolvedBackupFile
        Write-Output "RESTORE_OK database=$TargetDatabase file=$resolvedBackupFile"
    }
    'Drill' {
        $drillId = Get-Date -Format 'yyyyMMddHHmmss'
        $drillDatabase = "esun_restore_drill_$drillId"
        $drillDirectory = Join-Path $resolvedBackupDirectory "drill-$drillId"
        $sourceDump = Join-Path $drillDirectory "$SourceDatabase.sql"
        $corruptDump = Join-Path $drillDirectory 'corrupted.sql'
        $restoredDump = Join-Path $drillDirectory 'restored.sql'
        $sourceDataDump = Join-Path $drillDirectory "$SourceDatabase.data.sql"
        $corruptDataDump = Join-Path $drillDirectory 'corrupted.data.sql'
        $restoredDataDump = Join-Path $drillDirectory 'restored.data.sql'
        try {
            $sourceHash = New-DatabaseDump -Database $SourceDatabase -Destination $sourceDump
            $sourceDataHash = New-DatabaseDump -Database $SourceDatabase -Destination $sourceDataDump -DataOnly
            $sourceTables = Get-SortedTableList -Database $SourceDatabase
            $script:Force = $true
            Restore-Database -Database $drillDatabase -FromFile $sourceDump
            $table = (Invoke-MySql -Database $drillDatabase -Sql "SELECT table_name FROM information_schema.tables WHERE table_schema='$drillDatabase' AND table_type='BASE TABLE' ORDER BY table_name LIMIT 1;" | Out-String).Trim()
            if (-not $table) { throw 'The restored drill database contains no base tables.' }
            Invoke-MySql -Database $drillDatabase -Sql "SET FOREIGN_KEY_CHECKS=0; DELETE FROM ``$table`` LIMIT 1; CREATE TABLE ``__restore_drill_damage`` (id INT PRIMARY KEY); SET FOREIGN_KEY_CHECKS=1;" | Out-Null
            $corruptDataHash = New-DatabaseDump -Database $drillDatabase -Destination $corruptDataDump -DataOnly
            New-DatabaseDump -Database $drillDatabase -Destination $corruptDump | Out-Null
            $corruptTables = Get-SortedTableList -Database $drillDatabase
            if ($corruptDataHash -eq $sourceDataHash -and $corruptTables -eq $sourceTables) {
                throw 'The simulated corruption did not change the database content or table list.'
            }
            Restore-Database -Database $drillDatabase -FromFile $sourceDump
            $restoredDataHash = New-DatabaseDump -Database $drillDatabase -Destination $restoredDataDump -DataOnly
            New-DatabaseDump -Database $drillDatabase -Destination $restoredDump | Out-Null
            $restoredTables = Get-SortedTableList -Database $drillDatabase
            if ($restoredDataHash -ne $sourceDataHash) { throw "Restore verification failed (data). source=$sourceDataHash restored=$restoredDataHash" }
            if ($restoredTables -ne $sourceTables) { throw "Restore verification failed (tables). source=$sourceTables restored=$restoredTables" }
            Write-Output "DRILL_OK source=$SourceDatabase temporary=$drillDatabase data_sha256=$sourceDataHash full_sha256=$sourceHash tables=$sourceTables evidence=$drillDirectory"
        }
        finally { Invoke-MySql -Sql "DROP DATABASE IF EXISTS ``$drillDatabase``;" | Out-Null }
    }
}
