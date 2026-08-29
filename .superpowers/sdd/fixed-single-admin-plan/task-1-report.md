# Task 1 report: fixed single administrator backend

## Scope

- Modified only `joysong-server` production code and tests.
- Did not add a database migration or touch admin/Flutter/docs product code.
- Kept `ADMIN_PHONE` as the configured bare 11-digit number and retained `ADMIN_PASSWORD`; password validation now occurs only when an empty users table needs its first fixed account.

## Implementation

- Added `FixedAdminPhone` as the shared bare-number / `+86` equivalence policy.
- Startup now creates the account only for an empty table. For a nonempty table it only verifies the configured active, password-hashed `ADMIN`, rejects phone drift, conflicts, unavailable accounts, and any second non-erased `ADMIN`, without encoding or writing anything.
- Removed the admin role-update controller route and the related service command path.
- Restricted `/api/admin/login` to the exact configured bare phone while keeping the same generic credentials error and dummy password comparison for rejected requests.
- Reserved both configured-phone forms for ordinary password/code login, registration, phone binding, and new-phone verification; fixed-account lifecycle and phone mutations are rejected.

## TDD evidence

### RED

Command (after the Gradle distribution was available locally):

```powershell
$gradleHome = 'D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'; $env:GRADLE_USER_HOME = $gradleHome; .\gradlew.bat --offline test --tests com.joysong.server.user.service.AdminAccountCommandServiceTest --tests com.joysong.server.admin.controller.AdminUserControllerTest
```

Observed expected failures before implementation:

- `valid configured administrator is verified without an admin password or writes`: existing code threw `ADMIN_PASSWORD must contain 12-128 characters` before verifying the persisted fixed account.
- `a second non-erased administrator fails startup without writes`: existing code returned the configured account instead of rejecting a second admin.
- `phone drift of the fixed administrator fails startup without writes`: existing code attempted bootstrap creation instead of failing closed.
- `fixed administrator cannot be reactivated`: existing code reactivated the configured account.
- `role update endpoint is unavailable`: existing route still processed `PUT /api/admin/users/{id}/role`.

The first Gradle attempt was blocked by sandbox networking while downloading Gradle; the recorded RED run then used the already available local cache in offline mode.

### GREEN

Focused unit/controller/auth/profile command:

```powershell
$gradleHome = 'D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'; $env:GRADLE_USER_HOME = $gradleHome; .\gradlew.bat --offline test --tests com.joysong.server.user.service.AdminAccountCommandServiceTest --tests com.joysong.server.admin.controller.AdminUserControllerTest --tests com.joysong.server.auth.service.AuthenticationServiceTest --tests com.joysong.server.user.service.UserProfileServiceSecurityTest
```

Result: `BUILD SUCCESSFUL in 13s` (12 startup/lifecycle tests, 5 controller tests, 20 authentication tests, 10 profile security tests; 0 failures).

MySQL integration command (run against a Testcontainers database, never a shared development database):

```powershell
Write-Output 'MySQL migration target: host=Testcontainers dynamic host; database=myapp_worktree_fixed_single_admin'; $gradleHome = 'D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'; $env:GRADLE_USER_HOME = $gradleHome; .\gradlew.bat --offline mysqlIntegrationTest --tests com.joysong.server.user.service.AdminAccountCommandServiceMySqlIntegrationTest
```

Result after fixing a missing test-only `AccountLifecycleGuard` bean: 2 tests, 0 failures. The test result confirms the containerized startup checks preserve an existing account without `ADMIN_PASSWORD` and reject a second non-erased admin. The Testcontainers helper also validates and prints its worktree-derived migration database target.

## Review

- `git diff --check` passed.
- Verified no remaining `adminUpdateRole` or `updateRole` production/test references.
- No full backend suite was run: the requested minimal relevant unit, controller, and isolated MySQL integration coverage passed, and no migration changed.
