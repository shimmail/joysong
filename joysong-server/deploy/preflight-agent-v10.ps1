[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatabaseHost,

    [ValidateRange(1, 65535)]
    [int]$DatabasePort = 3306,

    [Parameter(Mandatory = $true)]
    [string]$DatabaseName,

    [Parameter(Mandatory = $true)]
    [string]$DatabaseUser,

    [switch]$TestMode
)

$ErrorActionPreference = 'Stop'

if ($TestMode -and -not $DatabaseName.StartsWith('myapp_worktree_', [System.StringComparison]::Ordinal)) {
    Write-Error 'Unsafe test database name. Expected prefix myapp_worktree_.'
    exit 2
}
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    Write-Error 'DB_PASSWORD is required.'
    exit 2
}
if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) {
    Write-Error 'mysql client is required.'
    exit 2
}

Write-Host "Agent V10 preflight database host: $DatabaseHost`:$DatabasePort"
Write-Host "Agent V10 preflight database name: $DatabaseName"

$escapedDatabaseName = $DatabaseName.Replace("'", "''")
$mysqlArguments = @(
    "--host=$DatabaseHost",
    "--port=$DatabasePort",
    "--user=$DatabaseUser",
    "--database=$DatabaseName",
    '--batch',
    '--skip-column-names'
)

function Invoke-MySqlScalar {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $output = & mysql @mysqlArguments "--execute=$Sql" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Agent V10 preflight query failed with exit code $LASTEXITCODE."
        exit 2
    }
    return (($output | Select-Object -Last 1).ToString()).Trim()
}

$previousMysqlPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $env:DB_PASSWORD

    $historyTableExists = [int](Invoke-MySqlScalar @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = '$escapedDatabaseName'
  AND table_name = 'flyway_schema_history'
"@)

    $v10Applied = $false
    if ($historyTableExists -gt 0) {
        $v10Applied = [int](Invoke-MySqlScalar "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '10' AND success = 1") -gt 0
    }
    if ($v10Applied) {
        Write-Host 'V10 migration already applied; legacy-data guard not required.'
        exit 0
    }

    $legacyTables = @(
        'agent_plan_items',
        'agent_messages',
        'agent_turns',
        'agent_safety_events',
        'agent_plans',
        'agent_assessments',
        'agent_user_profiles',
        'agent_tool_audits',
        'agent_sessions'
    )
    $populatedTables = [System.Collections.Generic.List[string]]::new()
    foreach ($table in $legacyTables) {
        $exists = [int](Invoke-MySqlScalar @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = '$escapedDatabaseName'
  AND table_name = '$table'
"@)
        if ($exists -gt 0 -and [long](Invoke-MySqlScalar "SELECT COUNT(*) FROM $table") -gt 0) {
            $populatedTables.Add($table)
        }
    }

    if ($populatedTables.Count -gt 0) {
        Write-Error "V10 migration blocked: legacy Agent data exists in $($populatedTables -join ', ')."
        exit 3
    }

    Write-Host 'V10 migration preflight passed: legacy Agent tables are absent or empty.'
    exit 0
}
finally {
    $env:MYSQL_PWD = $previousMysqlPassword
}
