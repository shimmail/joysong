[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Apply', 'Verify')]
    [string]$Action,

    [string]$JarPath = '',

    [string]$CatalogPath = '',

    [string]$DatabaseName = $env:DEMO_DATABASE_NAME,

    [string]$JavaCommand = 'java'
)

$ErrorActionPreference = 'Stop'
$safeDatabasePrefix = 'myapp_worktree_'
$repositoryRoot = Split-Path -Parent $PSScriptRoot

function Assert-RequiredEnvironmentVariable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $value = [System.Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "$Name is required."
    }
    return $value
}

function Resolve-DemoJarPath {
    param([string]$RequestedPath)

    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        return (Resolve-Path -LiteralPath $RequestedPath -ErrorAction Stop).Path
    }

    $buildDirectory = Join-Path $repositoryRoot 'joysong-server/build/libs'
    $candidates = @(
        Get-ChildItem -LiteralPath $buildDirectory -Filter '*.jar' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*-plain.jar' } |
            Sort-Object LastWriteTimeUtc -Descending
    )
    if ($candidates.Count -eq 0) {
        throw 'No Spring Boot jar was found. Build bootJar or pass -JarPath explicitly.'
    }
    return $candidates[0].FullName
}

function Resolve-DemoCatalogPath {
    param([string]$RequestedPath)

    $candidate = if ([string]::IsNullOrWhiteSpace($RequestedPath)) {
        Join-Path $repositoryRoot 'docs/test/catalog-v1.json'
    } else {
        $RequestedPath
    }
    $resolved = Get-Item -LiteralPath (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path
    if ($resolved.PSIsContainer) {
        throw "Catalog path must be a file: $($resolved.FullName)"
    }
    return $resolved.FullName
}

function Resolve-DatabaseTarget {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DatabaseUrl
    )

    if (-not $DatabaseUrl.StartsWith('jdbc:mysql://', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'DB_URL must use the jdbc:mysql:// scheme.'
    }
    if ($DatabaseUrl.IndexOf('createDatabaseIfNotExist', [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'DB_URL must not contain createDatabaseIfNotExist.'
    }

    $uri = [System.Uri]::new($DatabaseUrl.Substring(5))
    if (-not [string]::IsNullOrWhiteSpace($uri.UserInfo)) {
        throw 'DB_URL must not contain embedded credentials.'
    }
    $database = [System.Uri]::UnescapeDataString($uri.AbsolutePath.Trim('/'))
    if ([string]::IsNullOrWhiteSpace($uri.Host) -or
        [string]::IsNullOrWhiteSpace($database) -or
        $database.Contains('/')) {
        throw 'DB_URL must contain one explicit host and database name.'
    }

    [pscustomobject]@{
        Host = $uri.Host
        Port = if ($uri.IsDefaultPort -or $uri.Port -lt 1) { 3306 } else { $uri.Port }
        Database = $database
    }
}

$databaseUrl = Assert-RequiredEnvironmentVariable 'DB_URL'
$null = Assert-RequiredEnvironmentVariable 'DB_USERNAME'
$null = Assert-RequiredEnvironmentVariable 'DB_PASSWORD'
$jwtSecret = Assert-RequiredEnvironmentVariable 'JWT_SECRET'
$adminPhone = Assert-RequiredEnvironmentVariable 'ADMIN_PHONE'
$null = Assert-RequiredEnvironmentVariable 'GOOGLE_CLIENT_ID'
$demoAccountPassword = Assert-RequiredEnvironmentVariable 'DEMO_ACCOUNT_PASSWORD'

if ($jwtSecret.Length -lt 32) {
    throw 'JWT_SECRET must contain at least 32 characters.'
}
if ($adminPhone -notmatch '^1\d{10}$') {
    throw 'ADMIN_PHONE must be a valid 11-digit mobile number.'
}
if ($demoAccountPassword.Length -lt 12 -or $demoAccountPassword.Length -gt 128) {
    throw 'DEMO_ACCOUNT_PASSWORD must contain 12-128 characters.'
}
if ($Action -eq 'Apply') {
    $adminPassword = Assert-RequiredEnvironmentVariable 'ADMIN_PASSWORD'
    if ($adminPassword.Length -lt 12 -or $adminPassword.Length -gt 128) {
        throw 'ADMIN_PASSWORD must contain 12-128 characters for Apply.'
    }
}

if ([string]::IsNullOrWhiteSpace($DatabaseName)) {
    throw 'DEMO_DATABASE_NAME or -DatabaseName is required.'
}
if (-not $DatabaseName.StartsWith($safeDatabasePrefix, [System.StringComparison]::Ordinal)) {
    throw "Unsafe demo database name. Expected prefix $safeDatabasePrefix."
}

$databaseTarget = Resolve-DatabaseTarget $databaseUrl
if ($databaseTarget.Database -cne $DatabaseName) {
    throw "DB_URL database '$($databaseTarget.Database)' does not match DEMO_DATABASE_NAME '$DatabaseName'."
}

foreach ($profileVariable in @('SPRING_PROFILES_ACTIVE', 'SPRING_PROFILES_INCLUDE')) {
    $profiles = [System.Environment]::GetEnvironmentVariable($profileVariable) -split '[,;]'
    if ($profiles.Trim() | Where-Object { $_ -in @('prod', 'dev') }) {
        throw "$profileVariable must not include prod or dev when running demo data tasks."
    }
}

$resolvedJarPath = Resolve-DemoJarPath $JarPath
$resolvedCatalogPath = Resolve-DemoCatalogPath $CatalogPath
$catalog = Get-Content -Raw -LiteralPath $resolvedCatalogPath | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($catalog.datasetVersion) -or
    [string]::IsNullOrWhiteSpace($catalog.namespace)) {
    throw 'The demo catalog must contain datasetVersion and namespace.'
}
$catalogSha256 = (Get-FileHash -LiteralPath $resolvedCatalogPath -Algorithm SHA256).Hash.ToLowerInvariant()

$null = Get-Command $JavaCommand -ErrorAction Stop

Write-Host "Demo data action: $Action"
Write-Host "Demo database host: $($databaseTarget.Host):$($databaseTarget.Port)"
Write-Host "Demo database name: $($databaseTarget.Database)"
Write-Host "Demo catalog version: $($catalog.datasetVersion)"
Write-Host "Demo catalog namespace: $($catalog.namespace)"
Write-Host "Demo catalog SHA-256: $catalogSha256"

$javaArguments = @(
    '-jar',
    $resolvedJarPath,
    '--spring.profiles.active=demo',
    '--spring.main.web-application-type=none',
    '--spring.jpa.hibernate.ddl-auto=validate',
    '--spring.flyway.enabled=true',
    '--spring.flyway.validate-on-migrate=true',
    '--spring.flyway.baseline-on-migrate=false',
    '--spring.flyway.clean-disabled=true',
    '--seed.demo.enabled=true',
    "--seed.demo.action=$($Action.ToUpperInvariant())",
    "--seed.demo.catalog-path=$resolvedCatalogPath",
    "--seed.demo.expected-database-name=$DatabaseName",
    '--app.scheduling.enabled=false',
    '--app.account-deletion.enabled=false',
    '--app.account-deletion.allow-commerce-bypass=false',
    '--oss.enabled=false',
    '--aliyun.sms.enabled=false',
    '--security.verification-code.log-for-dev=false',
    '--payment.alipay-plus.auto-pay-on-order-create-enabled=false',
    '--payment.alipay-plus.simulated-enabled=false',
    '--payment.stripe.legacy-enabled=false',
    '--payment.reconciliation.enabled=false'
)

& $JavaCommand @javaArguments
$javaExitCode = $LASTEXITCODE
if ($javaExitCode -ne 0) {
    Write-Host "Demo data task failed with exit code $javaExitCode." -ForegroundColor Red
    exit $javaExitCode
}

Write-Host 'Demo data task completed successfully.' -ForegroundColor Green
exit 0
