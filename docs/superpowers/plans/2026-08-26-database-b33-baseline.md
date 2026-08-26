# Database B33 Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a schema-only `B33` Flyway baseline that represents the exact MySQL schema after `V33`, while preserving the complete legacy migration path for existing databases.

**Architecture:** Generate `B33__current_schema.sql` from an isolated empty MySQL 8.0.39 database after applying `B26 + V27..V33`. Extend the existing MySQL migration integration test so a fresh database proves it selects only `B33`, while a second isolated container proves the legacy `B26 + V27..V33` path produces identical canonical `information_schema` metadata.

**Tech Stack:** Kotlin 1.9, Spring Boot 3.2.2, Flyway 9.22.3, JUnit 5, Spring JDBC, Testcontainers 1.19.3, MySQL 8.0.39, Gradle Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-26-database-b33-baseline-design.md`

## Global Constraints

- Work only in `D:\code\kotlin\joysong\.worktrees\database-baseline-b33` on branch `codex/database-baseline-b33`.
- `B33__current_schema.sql` must equal the schema after `B26 + V27..V33`; future migrations start at `V34`.
- Do not modify `B1`, `B26`, or any `V1..V33` migration.
- Do not include business data, test data, `flyway_schema_history`, `CREATE DATABASE`, `USE`, GTID, lock statements, triggers, routines, events, views, or `DEFINER` in `B33`.
- Use only MySQL `8.0.39`; do not pull an image, download dependencies, or access a shared development/production database.
- Disable Testcontainers startup checks and Ryuk for every test command so it cannot pull auxiliary images; JUnit-owned containers must stop normally.
- Derive the database name from the worktree as `myapp_worktree_database_baseline_b33` and print the resolved host/container plus database name before migration.
- Never drop or reset a database. Cleanup may remove only the exact task-owned container after its name and task label have both been validated.
- Run Gradle with `--offline`; run focused tests first and the full `mysqlIntegrationTest` task at most once.
- Do not retain raw dumps, temporary scripts, containers, or other generated artifacts after verification.

## File Map

- Create: `joysong-server/src/main/resources/db/migration/B33__current_schema.sql`
  - Frozen, schema-only MySQL 8.0.39 snapshot after `V33`.
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt`
  - Fresh baseline selection, legacy path migration, and canonical schema equivalence.
- Read/verify only: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorInstitutionProjectFullEditMigrationTest.kt`
  - Existing V32 data compatibility and V32.1/V32.2/V33 upgrade coverage.
- Read/verify only: `joysong-server/src/test/kotlin/com/joysong/server/support/WorktreeTestDatabase.kt`
  - Enforces and prints the worktree-isolated database identity.
- Read/verify only: `joysong-server/build.gradle.kts`
  - Existing `mysqlIntegrationTest` task; no build change is required.

---

### Task 1: Generate B33 and switch the fresh-database contract

**Files:**
- Create: `joysong-server/src/main/resources/db/migration/B33__current_schema.sql`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt`

**Interfaces:**
- Consumes: the frozen `B26__current_schema.sql` and versioned migrations `V27` through `V33`.
- Produces: a Flyway baseline resource named exactly `B33__current_schema.sql`; the fresh-database test contract expects `Triple("33", "SQL_BASELINE", "B33__current_schema.sql")`.

- [ ] **Step 1: Verify the isolated workspace and offline prerequisites**

Run from the worktree root:

```powershell
git status --short
git branch --show-current
git check-ignore .runtime
docker image inspect mysql:8.0.39
```

Expected:

- the branch is `codex/database-baseline-b33`;
- the worktree is clean before implementation;
- `.runtime` is ignored by Git;
- `mysql:8.0.39` is present locally.

If `docker image inspect` fails, stop and report the missing cached image. Do not run
`docker pull`, and do not allow Testcontainers to pull it implicitly.

- [ ] **Step 2: Change the fresh baseline test first**

In `BaselineMigrationIntegrationTest.kt`, replace the existing test with:

```kotlin
@Test
fun `fresh database applies B33 baseline`() {
    val history = jdbcTemplate.query(
        """
        SELECT version, type, script
        FROM flyway_schema_history
        WHERE success = 1 AND version IS NOT NULL
        ORDER BY installed_rank
        """.trimIndent()
    ) { rs, _ -> Triple(rs.getString("version"), rs.getString("type"), rs.getString("script")) }

    assertEquals(
        listOf(Triple("33", "SQL_BASELINE", "B33__current_schema.sql")),
        history
    )
}
```

- [ ] **Step 3: Run the focused test and confirm the RED state**

Run:

```powershell
Set-Location joysong-server
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
$env:TESTCONTAINERS_CHECKS_DISABLE = 'true'
.\gradlew.bat --offline mysqlIntegrationTest --tests "com.joysong.server.migration.BaselineMigrationIntegrationTest" --no-daemon
Set-Location ..
```

Expected: FAIL because the actual fresh history still starts at `B26` and continues
through `V33`, while the new assertion requires only `B33`.

If the command attempts a network download or reports the MySQL image missing, stop;
do not retry without `--offline` and do not pull the image.

- [ ] **Step 4: Build the old V33 schema in an isolated task-owned container**

Use these exact identities and validate them before creation:

```powershell
$ErrorActionPreference = 'Stop'
$b33Container = 'joysong-b33-baseline'
$b33TaskLabel = 'database-baseline-b33'
$b33Database = 'myapp_worktree_database_baseline_b33'
$b33Password = 'b33-local-only'
$b33MigrationRoot = (Resolve-Path 'joysong-server\src\main\resources\db\migration').Path

if (-not $b33Database.StartsWith('myapp_worktree_', [System.StringComparison]::Ordinal)) {
    throw "Unsafe database name: $b33Database"
}
if ((docker ps -a --filter "name=^/$b33Container$" --format '{{.ID}}')) {
    throw "Container already exists; inspect it before proceeding: $b33Container"
}
docker image inspect mysql:8.0.39 | Out-Null

Write-Host "Migration database host=container:$b33Container:3306, database=$b33Database"
docker run --pull=never --detach `
    --name $b33Container `
    --label "com.joysong.task=$b33TaskLabel" `
    --env "MYSQL_ROOT_PASSWORD=$b33Password" `
    --env "MYSQL_DATABASE=$b33Database" `
    mysql:8.0.39 `
    --character-set-server=utf8mb4 `
    --collation-server=utf8mb4_0900_ai_ci
if ($LASTEXITCODE -ne 0) { throw 'Unable to start the isolated MySQL container' }

$b33Ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    docker exec $b33Container mysqladmin ping `
        --host=127.0.0.1 --user=root --password=$b33Password --silent 2>$null
    if ($LASTEXITCODE -eq 0) {
        $b33Ready = $true
        break
    }
    Start-Sleep -Seconds 1
}
if (-not $b33Ready) { throw 'MySQL was not ready within 60 seconds' }
```

Copy and execute only the old baseline path, in order:

```powershell
$b33Migrations = @(
    'B26__current_schema.sql',
    'V27__add_consultant_institution_change_requests.sql',
    'V28__expand_professional_project_requests.sql',
    'V29__travel_ground_service_order_flow.sql',
    'V30__order_service_conversations.sql',
    'V31__store_raw_payment_event_payload.sql',
    'V32__payment_compensation_and_usd_price_precision.sql',
    'V32_1__expand_notification_type_columns.sql',
    'V32_2__add_legal_documents.sql',
    'V33__doctor_institution_project_full_edit.sql'
)

foreach ($migration in $b33Migrations) {
    $source = Join-Path $b33MigrationRoot $migration
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Missing migration: $source"
    }
    $containerTarget = "/tmp/$migration"
    docker cp $source "${b33Container}:$containerTarget"
    if ($LASTEXITCODE -ne 0) { throw "Unable to copy $migration" }
    docker exec $b33Container mysql `
        --host=127.0.0.1 --user=root --password=$b33Password `
        --database=$b33Database --execute="source $containerTarget"
    if ($LASTEXITCODE -ne 0) { throw "Migration failed: $migration" }
}
```

Do not add `B33` to the migration list used to generate itself.

- [ ] **Step 5: Export and normalize the schema-only B33 snapshot**

Capture the deterministic dump in the ignored runtime directory, validate it, and then
write the generated artifact:

```powershell
$b33Runtime = Join-Path (Resolve-Path '.').Path '.runtime\database-baseline-b33'
New-Item -ItemType Directory -Force -Path $b33Runtime | Out-Null
$b33RawPath = Join-Path $b33Runtime 'B33.raw.sql'
$b33FinalPath = Join-Path $b33MigrationRoot 'B33__current_schema.sql'

$b33Dump = docker exec $b33Container mysqldump `
    --host=127.0.0.1 --user=root --password=$b33Password `
    --no-data --skip-add-drop-table --skip-add-locks --skip-comments `
    --skip-lock-tables --skip-triggers --routines=false --events=false `
    --set-gtid-purged=OFF --column-statistics=0 $b33Database
if ($LASTEXITCODE -ne 0) { throw 'Schema dump failed' }

$b33Utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllLines($b33RawPath, [string[]]$b33Dump, $b33Utf8)

$forbidden = Select-String -LiteralPath $b33RawPath -Pattern @(
    '^INSERT ',
    'flyway_schema_history',
    '^CREATE DATABASE ',
    '^USE ',
    'GTID_PURGED',
    '^LOCK TABLES',
    '^UNLOCK TABLES',
    '\bVIEW\b',
    '\b(PROCEDURE|FUNCTION|TRIGGER|EVENT)\b',
    'DEFINER='
)
if ($forbidden) { throw "Forbidden baseline content: $($forbidden.Line -join '; ')" }
if (-not (Select-String -LiteralPath $b33RawPath -Pattern '^CREATE TABLE ' -Quiet)) {
    throw 'The schema dump contains no CREATE TABLE statements'
}

$b33Header = @(
    '-- Flyway baseline for the schema produced by B26 followed by V27 through V33.',
    '-- Generated from an empty isolated MySQL 8.0.39 database. Keep B1, B26, and V1-V33 unchanged',
    '-- so existing databases can continue to validate and migrate normally. Add future changes as V34+.',
    ''
)
[System.IO.File]::WriteAllLines(
    $b33FinalPath,
    [string[]]($b33Header + (Get-Content -LiteralPath $b33RawPath)),
    $b33Utf8
)
```

Writing the final SQL file is the single bulk mechanical generation step. Do not
manually copy DDL from `V27..V33` into the snapshot.

- [ ] **Step 6: Inspect the generated artifact**

Run:

```powershell
git diff --check
git diff --stat
Select-String -LiteralPath 'joysong-server\src\main\resources\db\migration\B33__current_schema.sql' `
    -Pattern '^INSERT |flyway_schema_history|^CREATE DATABASE |^USE |GTID_PURGED|^LOCK TABLES|^UNLOCK TABLES|\bVIEW\b|\b(PROCEDURE|FUNCTION|TRIGGER|EVENT)\b|DEFINER='
```

Expected:

- `git diff --check` succeeds;
- the only migration change is the new `B33` file;
- the forbidden-content scan produces no matches.

- [ ] **Step 7: Run the focused fresh-baseline test and confirm GREEN**

Run once:

```powershell
Set-Location joysong-server
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
$env:TESTCONTAINERS_CHECKS_DISABLE = 'true'
.\gradlew.bat --offline mysqlIntegrationTest --tests "com.joysong.server.migration.BaselineMigrationIntegrationTest" --no-daemon
Set-Location ..
```

Expected: PASS, with migration output printing a database named
`myapp_worktree_database_baseline_b33`; Flyway history contains only B33.

- [ ] **Step 8: Commit the new baseline contract**

```powershell
git add -- `
    joysong-server/src/main/resources/db/migration/B33__current_schema.sql `
    joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt
git commit -m "feat: add V33 database baseline"
```

---

### Task 2: Prove B33 and the legacy migration chain are structurally equivalent

**Files:**
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt`
- Verify: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorInstitutionProjectFullEditMigrationTest.kt`

**Interfaces:**
- Consumes: `B33__current_schema.sql`, `WorktreeTestDatabase.databaseName()`, and Flyway's existing classpath migration location.
- Produces: `SchemaSnapshot`, `schemaSnapshot(JdbcTemplate)`, `canonicalRows(JdbcTemplate, String)`, and a second isolated `legacyMysql` container used only by this test class.

- [ ] **Step 1: Add the equivalence test call before its helper exists**

Add imports:

```kotlin
import org.flywaydb.core.Flyway
import org.springframework.jdbc.datasource.DriverManagerDataSource
```

Add this test and migration helper inside `BaselineMigrationIntegrationTest`:

```kotlin
@Test
fun `B33 schema matches legacy migrations through V33`() {
    WorktreeTestDatabase.validateAndPrint(legacyMysql)
    val legacyJdbc = JdbcTemplate(
        DriverManagerDataSource(legacyMysql.jdbcUrl, legacyMysql.username, legacyMysql.password)
    )

    migrateLegacy("32")
    migrateLegacy()

    assertEquals(
        listOf("26", "27", "28", "29", "30", "31", "32", "32.1", "32.2", "33"),
        legacyJdbc.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
            String::class.java
        )
    )
    assertEquals(schemaSnapshot(legacyJdbc), schemaSnapshot(jdbcTemplate))
}

private fun migrateLegacy(target: String? = null) {
    val configuration = Flyway.configure()
        .dataSource(legacyMysql.jdbcUrl, legacyMysql.username, legacyMysql.password)
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
    if (target != null) configuration.target(target)
    configuration.load().migrate()
}
```

Add the second container to the companion object without `@ServiceConnection`:

```kotlin
@Container
@JvmField
val legacyMysql = IsolatedBaselineMySqlContainer("mysql:8.0.39")
    .withDatabaseName(WorktreeTestDatabase.databaseName())
    .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
```

- [ ] **Step 2: Run compilation and confirm the RED state**

Run:

```powershell
Set-Location joysong-server
.\gradlew.bat --offline testClasses --no-daemon
Set-Location ..
```

Expected: FAIL with unresolved reference `schemaSnapshot`. This confirms the new
equivalence assertion cannot pass until canonical metadata capture exists.

- [ ] **Step 3: Add the canonical schema snapshot implementation**

Add the following inside `BaselineMigrationIntegrationTest`:

```kotlin
private data class SchemaSnapshot(
    val tables: List<List<String?>>,
    val columns: List<List<String?>>,
    val indexes: List<List<String?>>,
    val constraints: List<List<String?>>,
    val foreignKeys: List<List<String?>>,
    val checks: List<List<String?>>,
)

private fun schemaSnapshot(jdbc: JdbcTemplate): SchemaSnapshot = SchemaSnapshot(
    tables = canonicalRows(
        jdbc,
        """
        SELECT table_name, table_type, engine, row_format, table_collation, create_options, table_comment
        FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
        ORDER BY BINARY table_name
        """.trimIndent()
    ),
    columns = canonicalRows(
        jdbc,
        """
        SELECT table_name, ordinal_position, column_name, column_type, is_nullable,
               column_default, extra, character_set_name, collation_name,
               numeric_precision, numeric_scale, datetime_precision, generation_expression, column_comment
        FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
        ORDER BY BINARY table_name, ordinal_position
        """.trimIndent()
    ),
    indexes = canonicalRows(
        jdbc,
        """
        SELECT table_name, index_name, non_unique, seq_in_index, column_name, collation,
               sub_part, nullable, index_type, expression, is_visible
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
        ORDER BY BINARY table_name, BINARY index_name, seq_in_index
        """.trimIndent()
    ),
    constraints = canonicalRows(
        jdbc,
        """
        SELECT table_name, constraint_name, constraint_type, enforced
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name <> 'flyway_schema_history'
        ORDER BY BINARY table_name, BINARY constraint_name
        """.trimIndent()
    ),
    foreignKeys = canonicalRows(
        jdbc,
        """
        SELECT kcu.table_name, kcu.constraint_name, kcu.ordinal_position, kcu.column_name,
               kcu.referenced_table_name, kcu.referenced_column_name,
               rc.update_rule, rc.delete_rule
        FROM information_schema.key_column_usage kcu
        JOIN information_schema.referential_constraints rc
          ON rc.constraint_schema = kcu.constraint_schema
         AND rc.constraint_name = kcu.constraint_name
         AND rc.table_name = kcu.table_name
        WHERE kcu.constraint_schema = DATABASE()
          AND kcu.table_name <> 'flyway_schema_history'
          AND kcu.referenced_table_name IS NOT NULL
        ORDER BY BINARY kcu.table_name, BINARY kcu.constraint_name, kcu.ordinal_position
        """.trimIndent()
    ),
    checks = canonicalRows(
        jdbc,
        """
        SELECT tc.table_name, tc.constraint_name, cc.check_clause, tc.enforced
        FROM information_schema.table_constraints tc
        JOIN information_schema.check_constraints cc
          ON cc.constraint_schema = tc.constraint_schema
         AND cc.constraint_name = tc.constraint_name
        WHERE tc.constraint_schema = DATABASE()
          AND tc.table_name <> 'flyway_schema_history'
          AND tc.constraint_type = 'CHECK'
        ORDER BY BINARY tc.table_name, BINARY tc.constraint_name
        """.trimIndent()
    ),
)

private fun canonicalRows(jdbc: JdbcTemplate, sql: String): List<List<String?>> =
    jdbc.query(sql) { rs, _ ->
        (1..rs.metaData.columnCount).map { column -> rs.getString(column) }
    }
```

The queries deliberately omit volatile timestamps, row counts, auto-increment next
values, and Flyway history. Column `extra` still verifies the structural
`AUTO_INCREMENT` attribute.

- [ ] **Step 4: Run the focused B33 test class and confirm GREEN**

Run once:

```powershell
Set-Location joysong-server
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
$env:TESTCONTAINERS_CHECKS_DISABLE = 'true'
.\gradlew.bat --offline mysqlIntegrationTest --tests "com.joysong.server.migration.BaselineMigrationIntegrationTest" --no-daemon
Set-Location ..
```

Expected:

- PASS for fresh B33 history;
- PASS for legacy target 32 followed by V32.1, V32.2, and V33;
- exact equality for canonical tables, columns, indexes, constraints, foreign keys,
  and checks;
- both containers print the exact worktree-derived database name.

- [ ] **Step 5: Run the existing legacy-data migration test**

Run once:

```powershell
Set-Location joysong-server
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
$env:TESTCONTAINERS_CHECKS_DISABLE = 'true'
.\gradlew.bat --offline mysqlIntegrationTest --tests "com.joysong.server.institution.service.DoctorInstitutionProjectFullEditMigrationTest" --no-daemon
Set-Location ..
```

Expected: PASS. Its history remains
`26,27,28,29,30,31,32,32.1,32.2,33`, proving that an existing database ignores
`B33` and retains representative V32 data through V33.

- [ ] **Step 6: Commit the structural equivalence guard**

```powershell
git add -- joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt
git commit -m "test: verify B33 schema equivalence"
```

---

### Task 3: Final migration verification and cleanup

**Files:**
- Verify: `joysong-server/src/main/resources/db/migration/B33__current_schema.sql`
- Verify: `joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt`
- Verify: all pre-existing migration resources remain byte-for-byte unchanged.

**Interfaces:**
- Consumes: the two implementation commits from Tasks 1 and 2.
- Produces: one clean, reviewed feature branch with no temporary resources and evidence that all MySQL migration integration tests pass offline.

- [ ] **Step 1: Verify frozen migration history was not edited**

Run:

```powershell
git diff --exit-code 59dfddbe -- `
    joysong-server/src/main/resources/db/migration/B1__init_schema.sql `
    joysong-server/src/main/resources/db/migration/B26__current_schema.sql `
    'joysong-server/src/main/resources/db/migration/V*.sql'
```

Expected: no output and exit code 0. The new `B33` is intentionally outside this
frozen-file check.

- [ ] **Step 2: Run the broader MySQL migration suite once**

Run:

```powershell
Set-Location joysong-server
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
$env:TESTCONTAINERS_CHECKS_DISABLE = 'true'
.\gradlew.bat --offline mysqlIntegrationTest --no-daemon
Set-Location ..
```

Expected: PASS. Do not rerun this command after it passes. If it exceeds ten minutes,
stop it and report current progress plus the slowest observed test instead of retrying.
The persistence integration tests in this task also start Spring contexts with the
project's `spring.jpa.hibernate.ddl-auto=validate`, covering entity/schema validation
against the new fresh baseline.

- [ ] **Step 3: Perform final static checks**

Run:

```powershell
git diff --check 59dfddbe..HEAD
git status --short
git log --oneline --decorate -5
Select-String -LiteralPath 'joysong-server\src\main\resources\db\migration\B33__current_schema.sql' `
    -Pattern '^INSERT |flyway_schema_history|^CREATE DATABASE |^USE |GTID_PURGED|^LOCK TABLES|^UNLOCK TABLES|\bVIEW\b|\b(PROCEDURE|FUNCTION|TRIGGER|EVENT)\b|DEFINER='
```

Expected:

- no whitespace errors;
- no forbidden baseline content;
- only the plan, design, B33 baseline, and focused test changes belong to this branch;
- no uncommitted implementation files remain after cleanup.

- [ ] **Step 4: Remove only validated task-owned temporary resources**

Validate the exact container before stopping or removing it:

```powershell
$ErrorActionPreference = 'Stop'
$b33Container = 'joysong-b33-baseline'
$b33TaskLabel = 'database-baseline-b33'
$b33ContainerId = docker ps -a --filter "name=^/$b33Container$" --format '{{.ID}}'
if ($b33ContainerId) {
    $actualName = docker inspect --format '{{.Name}}' $b33ContainerId
    $actualLabel = docker inspect --format '{{index .Config.Labels "com.joysong.task"}}' $b33ContainerId
    if ($actualName -ne "/$b33Container" -or $actualLabel -ne $b33TaskLabel) {
        throw "Refusing to remove unverified container: $actualName / $actualLabel"
    }
    docker stop $b33ContainerId
    if ($LASTEXITCODE -ne 0) { throw 'Container stop failed; do not retry with a stronger command' }
    docker rm $b33ContainerId
    if ($LASTEXITCODE -ne 0) { throw 'Container removal failed; stop cleanup and report it' }
}
```

Then validate and remove the exact runtime directory:

```powershell
$worktreeRoot = (Resolve-Path '.').Path
$b33Runtime = Join-Path $worktreeRoot '.runtime\database-baseline-b33'
$resolvedRuntime = [System.IO.Path]::GetFullPath($b33Runtime)
$expectedRuntime = [System.IO.Path]::GetFullPath((Join-Path $worktreeRoot '.runtime\database-baseline-b33'))
if ($resolvedRuntime -ne $expectedRuntime -or -not $resolvedRuntime.StartsWith($worktreeRoot + '\')) {
    throw "Unsafe runtime cleanup path: $resolvedRuntime"
}
if (Test-Path -LiteralPath $resolvedRuntime) {
    $item = Get-Item -LiteralPath $resolvedRuntime -Force
    if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
        throw "Refusing to traverse reparse point: $resolvedRuntime"
    }
    Get-ChildItem -LiteralPath $resolvedRuntime -Force | Select-Object FullName, Length
    Remove-Item -LiteralPath $resolvedRuntime -Recurse
}
```

If any cleanup action fails because a resource is locked, stop and report it. Do not
retry with a broader path or stronger deletion primitive.

- [ ] **Step 5: Confirm final branch state**

Run:

```powershell
git status --short
git log --oneline 59dfddbe..HEAD
```

Expected: clean status and exactly the design/plan plus the two implementation commits.
