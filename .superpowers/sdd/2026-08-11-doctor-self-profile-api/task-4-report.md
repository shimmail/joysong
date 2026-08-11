# Task 4 report: Flutter doctor self-profile contract

## Status

DONE

## Files

- `joysong-flutter/lib/features/identity/domain/identity_models.dart`
  - Added immutable `DoctorInstitutionSummary`, `DoctorSelfProfile`, and `DoctorSelfProfileUpdate` models.
  - Parses the single complete response with required `id`, `userId`, and `name`, safe editable-field defaults, institution summaries, and read-only platform fields.
  - Serializes exactly the nine required editable strings and normalizes comma-separated fields.
- `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
  - Added single-resource load/update methods.
- `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
  - Added GET and PUT calls to `/management/doctor-profile`, single-object decoding, and the required null-response `FormatException`.
- `joysong-flutter/test/features/identity/doctor_self_profile_contract_test.dart`
  - Added three focused contract tests only.
- `joysong-flutter/test/features/identity/identity_models_controller_test.dart`
  - Added compile-only unimplemented methods to the existing strict fake; no tests or assertions were added there.

## TDD evidence

RED command:

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/identity/doctor_self_profile_contract_test.dart
```

Observed expected compilation failures for undefined `DoctorSelfProfile`, `DoctorSelfProfileUpdate`, `loadDoctorSelfProfile`, and `updateDoctorSelfProfile`.

GREEN command (same focused file, run once after implementation):

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/identity/doctor_self_profile_contract_test.dart
```

Result: 3 tests passed. Coverage is limited to complete response mapping/to-update empty-string preservation, exact normalized nine-key payload without platform fields, and exact single-resource GET/PUT repository paths/body/decoding.

Static verification:

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' analyze lib/features/identity/domain/identity_models.dart lib/features/identity/domain/identity_repository.dart lib/features/identity/data/identity_repository_impl.dart test/features/identity/identity_models_controller_test.dart test/features/identity/doctor_self_profile_contract_test.dart
```

Result: `No issues found!` for all five touched Dart files. `git diff --check` also passed for tracked task files.

## Temporary compatibility bridge retained for Task 5

Task 5 must delete these legacy doctor-management pieces after migrating the page:

- `ManagedDoctorProfile` and `ManagedDoctorProfileDraft` from `identity_models.dart`.
- `listManagedDoctorProfiles()` and `updateManagedDoctorProfile(...)` from `IdentityRepository`.
- Their `ApiIdentityRepository` implementations, including the old `/admin/doctors` and `/admin/doctors/{id}` calls.
- The corresponding legacy methods in identity test fakes once no page test consumes them.

No duplicate request or adapter from the old methods to the new endpoint was added. The new compile-only fake methods should be replaced by Task 5's page-oriented fake behavior if its tests call the new repository API.

## Commit

`refactor: use doctor self profile contract` (this task commit; final hash is reported in the handoff).

## Concerns

- The focused repository test records calls at the `ApiClient` boundary; it intentionally does not duplicate the existing `ApiClient` HTTP transport tests.
- The null-data guard is implemented with the required message but has no separate test, following the brief's minimal-test constraint.
- The known-dirty Android/iOS generated plugin registrants were not modified by this task and must remain unstaged.
- The known broad identity page test was not run because its four unrelated baseline failures are outside Task 4.
