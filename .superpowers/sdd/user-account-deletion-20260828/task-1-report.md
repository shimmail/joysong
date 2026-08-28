# Task 1: Account lifecycle foundation report

## Scope delivered

- Added `AccountState` with `ACTIVE`, `ADMIN_SUSPENDED`, and `ERASED`.
- Added V36 for user lifecycle columns plus `account_deletion_requests` and `user_media_assets`.
- Removed user `@SQLDelete` and `@Where`; the only remaining user `deleted_at` reference is the compatibility mapping on `UserEntity`.
- Changed JWT, password/code/Google login, admin login, and refresh-token rotation to require `ACTIVE`.
- Removed automatic revival. An `ERASED` record causes login/registration creation paths to construct a new user rather than mutate its user ID; the later erasure stage must first clear the raw phone/email as designed by V36.
- Changed admin stop/recover to `ACTIVE <-> ADMIN_SUSPENDED`; `ERASED` is never recovered.
- Added `AccountLifecycleGuard.requireActiveForWrite`, which obtains `SELECT ... FOR UPDATE` through `UserRepository.findByIdForUpdate` and requires an ambient transaction.
- Disabled the legacy account delete endpoint with HTTP 410 before it can physically delete a user after removing Hibernate soft-delete annotations.

## TDD evidence

Tests were written before the associated implementation changes:

- `AccountLifecycleGuardTest`: active locked write versus erased rejection.
- `JwtAuthenticationFilterTest`: erased access token does not authenticate.
- `AuthenticationServiceTest`: suspended login is refused and erased registration creates a distinct user ID.
- `AdminAccountCommandServiceTest`: deactivation writes `ADMIN_SUSPENDED`, suspension can return to `ACTIVE`, and erasure cannot be reactivated.
- `BaselineMigrationIntegrationTest`: fresh B33/V34/V35/V36 migration history and V36 tables/columns.

### RED command and output

Attempted from `joysong-server`:

```powershell
.\gradlew.bat test --tests com.joysong.server.user.service.AccountLifecycleGuardTest --tests com.joysong.server.config.JwtAuthenticationFilterTest --tests com.joysong.server.auth.service.AuthenticationServiceTest --tests com.joysong.server.user.service.AdminAccountCommandServiceTest
```

Initial run could not download Gradle in the sandbox:

```text
java.net.SocketException: Permission denied: getsockopt
```

After permission was granted, the Gradle wrapper download timed out. A subsequent run used the preinstalled Gradle 8.9 cache. The wrapper client returned after daemon start before the test result was available, so a normal RED assertion failure could not be captured. This is an environment/tooling limitation, not a claimed test pass.

### GREEN command and output

Attempted from `joysong-server` after implementation:

```powershell
.\gradlew.bat cleanTest test --tests com.joysong.server.user.service.AccountLifecycleGuardTest --tests com.joysong.server.config.JwtAuthenticationFilterTest --tests com.joysong.server.auth.service.AuthenticationServiceTest --tests com.joysong.server.user.service.AdminAccountCommandServiceTest --tests com.joysong.server.admin.controller.AdminUserControllerTest --tests com.joysong.server.user.service.UserProfileServiceSecurityTest --no-daemon --console=plain
```

The single-use Gradle daemon continued consuming CPU without creating test-result XML or returning a build result. Per coordination instruction, it was stopped rather than retried indefinitely. Therefore GREEN and MySQL migration execution are **not verified** in this task.

## Migration isolation

`BaselineMigrationIntegrationTest` uses `WorktreeTestDatabase.databaseName()` and its container prints the resolved migration host and database before Flyway runs. The expected database name for this worktree is `myapp_worktree_account_deletion`. The migration test was not executed because the Gradle run above did not complete; no shared development database was contacted.

## Files changed

Production:

- `joysong-server/src/main/kotlin/com/joysong/server/user/entity/AccountState.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/user/entity/UserEntity.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/user/repository/UserRepository.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/user/service/AccountLifecycleGuard.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/user/service/AdminAccountCommandService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/user/service/UserProfileService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/auth/service/AuthenticationService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/auth/service/RefreshTokenService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/auth/controller/AuthController.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/auth/dto/AuthDtos.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/config/JwtAuthenticationFilter.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/admin/dto/AdminUserView.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/admin/service/AdminCustomerServiceService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/cs/service/CsService.kt`
- `joysong-server/src/main/resources/db/migration/V36__add_account_lifecycle_foundation.sql`

Tests:

- `joysong-server/src/test/kotlin/com/joysong/server/user/service/AccountLifecycleGuardTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/user/service/AdminAccountCommandServiceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/user/service/AdminAccountCommandServiceMySqlIntegrationTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/user/service/UserProfileServiceSecurityTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/auth/service/AuthenticationServiceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/config/JwtAuthenticationFilterTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminAuthControllerTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminUserControllerTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/admin/dto/AdminUserViewSerializationTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt`

## Self-review

- No order, payment, refund, wallet, settlement, review, or direct-message production module was changed.
- Repository methods and runtime SQL no longer use `deleted_at`; only the entity compatibility field maps that legacy column.
- V36 does not store a raw verification code, authorization token, or idempotency key.
- New account state is the authentication gate; administrator suspension revokes existing refresh sessions.
- The legacy delete endpoint cannot invoke repository deletion.
- `git diff --check` completed with no whitespace errors.
- Required focused tests and the isolated migration test still need a successful Gradle execution before merge because the local daemon did not return results.
