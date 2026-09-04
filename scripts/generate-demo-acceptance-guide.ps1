[CmdletBinding()]
param(
    [string]$CatalogPath = '',

    [string]$OutputPath = '',

    [string]$AdminPhone = $env:ADMIN_PHONE,

    [string]$AdminPassword = $env:ADMIN_PASSWORD,

    [string]$DemoAccountPassword = $env:DEMO_ACCOUNT_PASSWORD,

    [string]$DemoPublicUrl = $env:DEMO_PUBLIC_URL,

    [string]$DemoAdminUrl = $env:DEMO_ADMIN_URL
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot '.runtime'))

function Assert-Password {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -lt 12 -or $Value.Length -gt 128) {
        throw "$Name must contain 12-128 characters."
    }
    if ($Value.Contains("`r") -or $Value.Contains("`n")) {
        throw "$Name must not contain line breaks."
    }
}

function Escape-MarkdownTableCell {
    param([AllowEmptyString()][string]$Value)

    return $Value.Replace('\', '\\').Replace('|', '\|').Replace("`r", ' ').Replace("`n", ' ')
}

function Resolve-OutputFile {
    param([string]$RequestedPath)

    $candidate = if ([string]::IsNullOrWhiteSpace($RequestedPath)) {
        Join-Path $runtimeRoot 'acceptance/demo-acceptance-guide.md'
    } elseif ([System.IO.Path]::IsPathRooted($RequestedPath)) {
        $RequestedPath
    } else {
        Join-Path $PWD $RequestedPath
    }

    $fullPath = [System.IO.Path]::GetFullPath($candidate)
    $repositoryPath = [System.IO.Path]::GetFullPath($repositoryRoot)
    $insideRepository = $fullPath.StartsWith(
        $repositoryPath + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase
    )
    $insideRuntime = $fullPath.StartsWith(
        $runtimeRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase
    )
    if ($insideRepository -and -not $insideRuntime) {
        throw 'Credential-bearing acceptance guides may only be written under .runtime or outside the repository.'
    }
    return $fullPath
}

Assert-Password -Name 'ADMIN_PASSWORD' -Value $AdminPassword
Assert-Password -Name 'DEMO_ACCOUNT_PASSWORD' -Value $DemoAccountPassword
if ($AdminPassword -ceq $DemoAccountPassword) {
    throw 'ADMIN_PASSWORD and DEMO_ACCOUNT_PASSWORD must be different.'
}
if ($AdminPhone -notmatch '^\+?[1-9]\d{6,14}$') {
    throw 'ADMIN_PHONE must be a valid phone identifier.'
}

$resolvedCatalogPath = if ([string]::IsNullOrWhiteSpace($CatalogPath)) {
    Join-Path $repositoryRoot 'docs/test/catalog-v1.json'
} else {
    (Resolve-Path -LiteralPath $CatalogPath -ErrorAction Stop).Path
}
$catalog = Get-Content -Raw -LiteralPath $resolvedCatalogPath | ConvertFrom-Json
$accounts = @($catalog.accounts)
if ($accounts.Count -eq 0) {
    throw 'The demo catalog does not contain accounts.'
}
if (@($accounts.phone | Sort-Object -Unique).Count -ne $accounts.Count) {
    throw 'The demo catalog contains duplicate phone numbers.'
}
if ($AdminPhone -in $accounts.phone) {
    throw 'ADMIN_PHONE must not overlap a catalog account.'
}

$expectedRoles = @(
    'INSTITUTION_LEGAL_REPRESENTATIVE',
    'DOCTOR',
    'CONSULTANT'
)
$unexpectedRoles = @($accounts.roleCode | Where-Object { $_ -notin $expectedRoles } | Sort-Object -Unique)
if ($unexpectedRoles.Count -gt 0) {
    throw "Unsupported catalog roles: $($unexpectedRoles -join ', ')"
}
$nonChinaAccounts = @($accounts | Where-Object { $_.phone -notmatch '^\+86\d{11}$' })
if ($nonChinaAccounts.Count -gt 0) {
    throw "Catalog accounts must use +86 followed by 11 digits: $($nonChinaAccounts.code -join ', ')"
}

$accountInstitutions = @{}
function Add-AccountInstitution {
    param(
        [Parameter(Mandatory = $true)][string]$AccountCode,
        [Parameter(Mandatory = $true)][string]$InstitutionCode
    )

    if (-not $accountInstitutions.ContainsKey($AccountCode)) {
        $accountInstitutions[$AccountCode] = [System.Collections.Generic.List[string]]::new()
    }
    if (-not $accountInstitutions[$AccountCode].Contains($InstitutionCode)) {
        $accountInstitutions[$AccountCode].Add($InstitutionCode)
    }
}

$catalog.legalRepresentativeMemberships | ForEach-Object {
    Add-AccountInstitution -AccountCode $_.accountCode -InstitutionCode $_.institutionCode
}
$catalog.doctors | ForEach-Object {
    Add-AccountInstitution -AccountCode $_.accountCode -InstitutionCode $_.institutionCode
}
$catalog.consultantMemberships | ForEach-Object {
    Add-AccountInstitution -AccountCode $_.accountCode -InstitutionCode $_.institutionCode
}

$institutionNames = @{}
$catalog.institutions | ForEach-Object { $institutionNames[$_.code] = $_.name }
$missingRelations = @($accounts | Where-Object { -not $accountInstitutions.ContainsKey($_.code) })
if ($missingRelations.Count -gt 0) {
    throw "Accounts without an institution relation: $($missingRelations.code -join ', ')"
}

$roleLabels = @{
    INSTITUTION_LEGAL_REPRESENTATIVE = '机构法人'
    DOCTOR = '医生'
    CONSULTANT = '顾问'
}
$roleOrder = @{
    INSTITUTION_LEGAL_REPRESENTATIVE = 1
    DOCTOR = 2
    CONSULTANT = 3
}
$sortedAccounts = @(
    $accounts | Sort-Object @{ Expression = { $roleOrder[$_.roleCode] } }, code
)
$catalogSha256 = (Get-FileHash -LiteralPath $resolvedCatalogPath -Algorithm SHA256).Hash.ToLowerInvariant()
$resolvedOutputPath = Resolve-OutputFile -RequestedPath $OutputPath
$outputDirectory = Split-Path -Parent $resolvedOutputPath
$null = New-Item -ItemType Directory -Force -Path $outputDirectory

$publicUrl = if ([string]::IsNullOrWhiteSpace($DemoPublicUrl)) { '由部署人员填写' } else { $DemoPublicUrl }
$adminUrl = if ([string]::IsNullOrWhiteSpace($DemoAdminUrl)) { '由部署人员填写' } else { $DemoAdminUrl }
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# 娇颜颂 Demo 快速验收说明')
$lines.Add('')
$lines.Add('> 本文件包含测试账号密码，仅发送给指定验收人员；禁止提交 Git、转发到群聊或用于真实业务。Demo 结束后立即轮换或销毁全部账号。')
$lines.Add('')
$lines.Add('## 1. 开始前')
$lines.Add('')
$lines.Add("- App/API 地址：$(Escape-MarkdownTableCell $publicUrl)")
$lines.Add("- 管理后台地址：$(Escape-MarkdownTableCell $adminUrl)")
$lines.Add("- 数据版本：$(Escape-MarkdownTableCell ([string]$catalog.datasetVersion))")
$lines.Add("- Catalog SHA-256：$catalogSha256")
$lines.Add('- App 登录请选择“密码登录”和“中国大陆（+86）”，再输入表格中的 11 位 App 输入号码。')
$lines.Add('- 首轮 Demo 不测试短信、支付、OSS、账号注销或真实患者资料。')
$lines.Add('- 当前目录没有独立的纯消费者账号；专业账号仍可验收普通浏览入口，本轮不创建订单。')
$lines.Add('')
$lines.Add('## 2. 管理员账号')
$lines.Add('')
$lines.Add('| 用途 | 手机号 | 密码 |')
$lines.Add('|---|---|---|')
$lines.Add("| 管理后台 | $(Escape-MarkdownTableCell $AdminPhone) | $(Escape-MarkdownTableCell $AdminPassword) |")
$lines.Add('')
$lines.Add('## 3. 全部 App 测试账号')
$lines.Add('')
$lines.Add("以下 $($accounts.Count) 个账号共用部署时的 Demo 密码；表格逐行列出，便于直接复制。")

$currentRole = ''
foreach ($account in $sortedAccounts) {
    if ($account.roleCode -cne $currentRole) {
        $currentRole = $account.roleCode
        $lines.Add('')
        $lines.Add("### $($roleLabels[$currentRole])")
        $lines.Add('')
        $lines.Add('| 编号 | 显示名称 | 完整手机号 | App 输入 | 密码 | 关联机构 |')
        $lines.Add('|---|---|---|---|---|---|')
    }

    $loginInput = $account.phone.Substring(3)
    $relatedInstitutions = @(
        $accountInstitutions[$account.code] | ForEach-Object {
            if (-not $institutionNames.ContainsKey($_)) {
                throw "Unknown institution code for $($account.code): $_"
            }
            $institutionNames[$_]
        }
    ) -join '、'
    $cells = @(
        $account.code,
        $account.displayName,
        $account.phone,
        $loginInput,
        $DemoAccountPassword,
        $relatedInstitutions
    ) | ForEach-Object { Escape-MarkdownTableCell ([string]$_) }
    $lines.Add("| $($cells -join ' | ') |")
}

$lines.Add('')
$lines.Add('## 4. 推荐跨角色联动测试（约 15 分钟）')
$lines.Add('')
$lines.Add('建议使用上海澄光机构这一组账号；可在多台设备同时登录，也可退出后依次切换。')
$lines.Add('')
$lines.Add('1. 管理员登录 Web 后台，确认机构、医生、顾问和项目目录有数据。')
$lines.Add('2. 法人 ACCOUNT-LEGAL-01 登录 App，确认可以看到上海澄光和上海栖颜两个机构，并能进入机构管理。')
$lines.Add('3. 医生 ACCOUNT-DOCTOR-SH-01 登录，确认执业机构为上海澄光，能看到本人医生资料和已绑定项目。')
$lines.Add('4. 顾问 ACCOUNT-CONSULTANT-SH-01 登录，确认所属机构为上海澄光，不能进入法人或医生专属管理页。')
$lines.Add('5. 医生 ACCOUNT-DOCTOR-SH-02 使用普通浏览入口查看上海澄光、医生和项目公开详情，确认看不到任何身份材料。')
$lines.Add('6. 交叉检查：非管理员不能打开管理后台；医生不能管理机构法人权限；顾问不能编辑医生资料。')
$lines.Add('7. 如需验证第二组关系，使用 ACCOUNT-LEGAL-02、ACCOUNT-DOCTOR-BJ-01、ACCOUNT-CONSULTANT-BJ-01 检查北京和悦机构。')
$lines.Add('')
$lines.Add('## 5. 通过标准')
$lines.Add('')
$lines.Add('- 所有选定账号都能用密码登录，角色名称和关联机构正确。')
$lines.Add('- 法人、医生、顾问可以看到本角色入口，不能进入其他角色或管理员权限。')
$lines.Add('- 公开页面只显示虚构机构、医生和项目信息，不显示身份证件、执照等私有材料。')
$lines.Add('- 刷新、退出和重新登录后数据仍一致；异常请记录账号、时间、页面和截图。')
$lines.Add('- 短信、支付、OSS 和真实交易统一标记为“不在本轮范围”，不要触发真实外发或扣款。')

Set-Content -LiteralPath $resolvedOutputPath -Value $lines -Encoding utf8
if (-not $IsWindows) {
    & chmod 600 -- $resolvedOutputPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to restrict acceptance guide permissions.'
    }
}

Write-Host "Acceptance guide generated: $resolvedOutputPath"
Write-Host "Catalog accounts included: $($accounts.Count)"
Write-Host "Catalog SHA-256: $catalogSha256"
