# Task 5 report: Flutter doctor self-profile page

## Status

DONE

## Implementation

- Replaced the legacy managed-doctor list editor with `DoctorSelfProfilePage`, backed only by `loadDoctorSelfProfile()` and `updateDoctorSelfProfile()`.
- Added all nine editable fields, required-name validation, avatar replacement/removal, credential-image addition/removal, async disabled states, concise retryable errors, and the required stable keys.
- Threaded the nullable doctor-image callback through `ProfilePage`, `ManagementCenterPage`, and `_ManagementCapabilities`.
- Reused `AppFilePicker` and the injected `SocialRepository.uploadPublicMedia` path with explicit `MediaPrivacy.publicContent`; added `PublicMediaPurpose.doctorProfile` mapped to `doctors`.
- Reworded the public doctor material section and tab without changing the real `isVerified` badge.
- Deleted `ManagedDoctorProfile`, `ManagedDoctorProfileDraft`, their repository methods/implementations, old Flutter `/admin/doctors` calls, and legacy fake methods.

## TDD evidence

RED command:

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/identity/doctor_self_profile_page_test.dart test/features/identity/doctor_self_profile_contract_test.dart
```

Observed the expected compilation failure because `DoctorSelfProfilePage` did not yet exist. The three pre-existing contract tests passed in the same run.

The first post-implementation group run compiled production code but exposed one shared test-harness problem: all three new tests declared the `zh` locale without Flutter's Chinese Material/Cupertino localization delegates. The contract tests remained 3/3 passing. After adding the same delegate setup used by existing Chinese widget tests, only the failed test file was rerun, per the project test rules:

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/identity/doctor_self_profile_page_test.dart
```

Result: 3/3 passed.

The exact three new user-behavior tests are:

1. `doctor loads, edits, clears, and saves all profile fields`
2. `doctor upload choices determine saved public image state`
3. `doctor detail labels public materials without verification claims`

No plugin, getter, source-grep, widget-existence-only, framework-default, or duplicate validation test was added.

## Analyze

Targeted `flutter analyze` ran once over all eleven changed Dart files.

Result: `No issues found!`

`git diff --check` also passed.

## Compatibility deletion search

Recursive Flutter `lib`/`test` search returned zero matches for:

- `ManagedDoctorProfile`
- `ManagedDoctorProfileDraft`
- `listManagedDoctorProfiles`
- `updateManagedDoctorProfile`
- `/admin/doctors`

The doctor detail production file also returned zero matches for `资质保险箱` and `查资质`.

## Staged files

- `.superpowers/sdd/2026-08-11-doctor-self-profile-api/task-5-report.md`
- `joysong-flutter/lib/features/discover/presentation/doctor_detail_view.dart`
- `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- `joysong-flutter/lib/features/social/data/social_repository_impl.dart`
- `joysong-flutter/lib/features/social/domain/social_models.dart`
- `joysong-flutter/test/features/identity/doctor_self_profile_page_test.dart`
- `joysong-flutter/test/features/identity/identity_models_controller_test.dart`

The three baseline dirty `GeneratedPluginRegistrant` files are not included.

## Commit

`feat: adapt doctor self profile page` (final hash reported in the handoff)

## Concerns

- The first post-implementation group run failed only because the new test harness omitted Chinese Flutter localization delegates. The focused rerun verified all three affected behaviors after the harness correction.
- The known dirty Android/iOS generated plugin registrants remain untouched and must stay unstaged.
