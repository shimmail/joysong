# Institution Consultants Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user choose any consultant belonging to the current institution from a scrollable bottom sheet and enter the existing direct-message thread with that consultant.

**Architecture:** Reuse `BookingRepository.getConsultants()` and `BookingConsultant`, which already implement the required public consultant endpoint. Add one institution-ID callback through the discover navigation tree, keep the async picker in a focused shell presentation helper, and delegate the selected consultant to the existing `_openDirectMessage` method.

**Tech Stack:** Flutter, Dart, Material modal bottom sheets, existing `BookingRepository` and `MessagingRepository`, `flutter_test`.

## Global Constraints

- Change only `joysong-flutter/`; do not modify backend APIs or migrations.
- Request consultants only after the user taps **咨询机构** / **Consult institution**.
- The bottom sheet must be scrollable and show every consultant returned by the endpoint.
- Selecting a consultant must close the sheet before creating or opening the DM conversation.
- Reuse the existing consultant endpoint, consultant model, DM creation flow, and localized message style.
- Do not add duplicate consultant models, repositories, or network calls.
- Run the smallest related tests first; after they pass, run `flutter analyze` at most once.

## Baseline Evidence

- `test/features/booking/booking_remote_data_source_test.dart` passed all four tests before implementation, including the existing institution-consultant endpoint contract.
- `test/features/discover/catalog_institution_detail_reviews_test.dart` had three pre-existing failures before implementation: its scroll helper did not expose the expected review widgets, and its `example.com` image requests produced the Flutter test environment's expected HTTP 400 response. Do not treat that file as a feature regression gate or modify it as part of this work.

---

## File Structure

- Modify `joysong-flutter/lib/features/discover/presentation/catalog_institution_detail_view.dart`: emit the current institution ID from the primary button.
- Modify `joysong-flutter/lib/features/discover/presentation/discover_page.dart`: propagate the institution-consult callback through all discover/detail navigation paths.
- Create `joysong-flutter/lib/features/shell/presentation/institution_consultant_picker.dart`: own the modal loading, list, empty, error, retry, and selected-result behavior.
- Modify `joysong-flutter/lib/features/shell/presentation/app_shell.dart`: load through `BookingRepository`, prevent duplicate sheets, and delegate the selection to `_openDirectMessage`.
- Create `joysong-flutter/test/features/discover/catalog_institution_consultants_test.dart`: verify the detail button contract.
- Create `joysong-flutter/test/features/shell/institution_consultant_picker_test.dart`: verify modal states, scrolling, selection, and dismissal.

### Task 1: Institution detail consultation callback

**Files:**
- Create: `joysong-flutter/test/features/discover/catalog_institution_consultants_test.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_institution_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/discover_page.dart`

**Interfaces:**
- Consumes: the institution ID resolved from `DiscoverItem.raw['institution']['id']` or the root `id`/`institutionId` fields.
- Produces: `ValueChanged<String>? onConsultInstitution` on `CatalogInstitutionDetailView`, `DiscoverPage`, `_DiscoveryResultsPane`, and `DiscoverDetailPage`.

- [ ] **Step 1: Write the failing button contract tests**

Create a widget-test fixture with an institution whose nested ID is `institution-1`, pass an `onConsultInstitution` callback, tap `咨询机构`, and assert that the callback receives `institution-1`. Add a second test whose payload has no usable ID and assert that the `FilledButton` is disabled.

```dart
testWidgets('emits the current institution id when consultation is tapped',
    (tester) async {
  String? selectedInstitutionId;
  await tester.pumpWidget(_testApp(
    item: _institutionItem(id: 'institution-1'),
    onConsultInstitution: (id) => selectedInstitutionId = id,
  ));

  await tester.tap(find.text('咨询机构'));
  await tester.pump();

  expect(selectedInstitutionId, 'institution-1');
});

testWidgets('disables consultation when the institution id is missing',
    (tester) async {
  await tester.pumpWidget(_testApp(
    item: _institutionItem(id: ''),
    onConsultInstitution: (_) {},
  ));

  final button = tester.widget<FilledButton>(
    find.widgetWithText(FilledButton, '咨询机构'),
  );
  expect(button.onPressed, isNull);
});
```

- [ ] **Step 2: Run the test and verify the expected compile failure**

Run:

```powershell
flutter test test/features/discover/catalog_institution_consultants_test.dart
```

Expected: FAIL because `CatalogInstitutionDetailView` does not yet define `onConsultInstitution`.

- [ ] **Step 3: Implement the minimal detail-view callback**

Add the nullable callback and replace the current SnackBar-only action:

```dart
final ValueChanged<String>? onConsultInstitution;

onPrimary: institutionId.isEmpty || onConsultInstitution == null
    ? null
    : () => onConsultInstitution!(institutionId),
```

Do not retain the old “open Messages” SnackBar.

- [ ] **Step 4: Propagate the callback through discover navigation**

Add the same `ValueChanged<String>? onConsultInstitution` field to `DiscoverPage`, `_DiscoveryResultsPane`, and `DiscoverDetailPage`. Pass it alongside `onConsultDoctor` at every `DiscoverDetailPage` construction in `discover_page.dart`. In the institution branch, connect it directly:

```dart
CatalogInstitutionDetailView(
  item: item,
  onConsultInstitution: widget.onConsultInstitution,
  // existing arguments remain unchanged
)
```

Keep the callback nullable so professional catalog callers that construct `CatalogInstitutionDetailView` directly remain compatible.

- [ ] **Step 5: Run the focused test and verify it passes**

Run:

```powershell
flutter test test/features/discover/catalog_institution_consultants_test.dart
```

Expected: PASS with both callback and missing-ID cases green.

- [ ] **Step 6: Commit the callback contract**

```powershell
git add joysong-flutter/test/features/discover/catalog_institution_consultants_test.dart joysong-flutter/lib/features/discover/presentation/catalog_institution_detail_view.dart joysong-flutter/lib/features/discover/presentation/discover_page.dart
git commit -m "feat: expose institution consultation action"
```

### Task 2: Scrollable consultant picker bottom sheet

**Files:**
- Create: `joysong-flutter/lib/features/shell/presentation/institution_consultant_picker.dart`
- Create: `joysong-flutter/test/features/shell/institution_consultant_picker_test.dart`

**Interfaces:**
- Consumes: `Future<List<BookingConsultant>> Function() loadConsultants`.
- Produces: `Future<BookingConsultant?> showInstitutionConsultantPicker({required BuildContext context, required Future<List<BookingConsultant>> Function() loadConsultants})`.
- Returns `null` when dismissed and the selected `BookingConsultant` when a row is tapped.

- [ ] **Step 1: Write the failing loading and list tests**

Use a `Completer<List<BookingConsultant>>` to prove that the sheet displays a progress indicator while awaiting the request. Complete it with at least 25 uniquely named consultants, pump the widget, drag the keyed list, and assert that both the first and last names are reachable.

```dart
final pending = Completer<List<BookingConsultant>>();
await tester.pumpWidget(_pickerHost(loadConsultants: () => pending.future));
await tester.tap(find.text('open'));
await tester.pump();
expect(find.byType(CircularProgressIndicator), findsOneWidget);

pending.complete(List.generate(
  25,
  (index) => BookingConsultant(id: 'c-$index', name: '顾问 $index'),
));
await tester.pumpAndSettle();
expect(find.text('顾问 0'), findsOneWidget);
await tester.drag(
  find.byKey(const Key('institution-consultant-list')),
  const Offset(0, -1200),
);
await tester.pumpAndSettle();
expect(find.text('顾问 24'), findsOneWidget);
```

- [ ] **Step 2: Run the picker test and verify the expected compile failure**

Run:

```powershell
flutter test test/features/shell/institution_consultant_picker_test.dart
```

Expected: FAIL because `showInstitutionConsultantPicker` does not exist.

- [ ] **Step 3: Implement loading, constrained scrolling, and selection**

Implement `showModalBottomSheet<BookingConsultant>` with `isScrollControlled: true`, `useSafeArea: true`, and `showDragHandle: true`. Its stateful body starts one load in `initState`, constrains itself with `FractionallySizedBox(heightFactor: .72)`, and renders the success list as:

```dart
ListView.builder(
  key: const Key('institution-consultant-list'),
  itemCount: consultants.length,
  itemBuilder: (context, index) {
    final consultant = consultants[index];
    return ListTile(
      key: ValueKey('institution-consultant-${consultant.id}'),
      leading: const CircleAvatar(child: Icon(Icons.support_agent_rounded)),
      title: Text(consultant.name),
      trailing: const Icon(Icons.chevron_right_rounded),
      onTap: () => Navigator.of(context).pop(consultant),
    );
  },
)
```

The localized title is `选择机构咨询师` / `Choose a consultant`.

- [ ] **Step 4: Add failing empty, failure, retry, selection, and dismissal tests**

Cover these observable behaviors:

- `const []` displays `该机构暂无可咨询的咨询师` / `No consultants are currently available.`
- A thrown exception displays `加载咨询师失败，请重试` / `Unable to load consultants. Try again.` and a `重试` / `Retry` button.
- Retry replaces the failed future and shows the returned rows.
- Tapping `institution-consultant-c-2` closes the sheet and completes the returned future with that consultant.
- Dismissing the sheet completes with `null` and does not invoke any selection behavior in the host.

- [ ] **Step 5: Implement empty, error, and retry states**

Store the request as `late Future<List<BookingConsultant>> _consultants`; reset it in a `_retry()` method under `setState`. Use `FutureBuilder<List<BookingConsultant>>` to render exactly one of loading, failure, empty, or list states. Check `mounted` only where an asynchronous continuation updates or navigates after the modal returns.

- [ ] **Step 6: Run the picker tests and verify they pass**

Run:

```powershell
flutter test test/features/shell/institution_consultant_picker_test.dart
```

Expected: PASS for loading, long-list scrolling, empty, retry, selection, and dismissal.

- [ ] **Step 7: Commit the picker**

```powershell
git add joysong-flutter/lib/features/shell/presentation/institution_consultant_picker.dart joysong-flutter/test/features/shell/institution_consultant_picker_test.dart
git commit -m "feat: add institution consultant picker"
```

### Task 3: Connect consultant selection to direct messaging

**Files:**
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/institution_consultant_picker.dart`
- Modify: `joysong-flutter/test/features/shell/institution_consultant_picker_test.dart`

**Interfaces:**
- Consumes: `BookingRepository.getConsultants(String institutionId)`, `showInstitutionConsultantPicker(...)`, and `_openDirectMessage(String targetId, {String? title})`.
- Produces: `openInstitutionConsultantChat(...)`, which maps a selected consultant to exact DM arguments, plus `_openInstitutionConsultants(String institutionId)` as the guarded shell callback.

- [ ] **Step 1: Add a failing picker-to-chat handoff test**

Add a test for a wished-for `openInstitutionConsultantChat` helper that records the exact DM arguments:

```dart
final opened = <String>[];
await openInstitutionConsultantChat(
  context: context,
  loadConsultants: () async => const [
    BookingConsultant(id: 'consultant-2', name: '林顾问'),
  ],
  openDirectMessage: (id, {title}) async => opened.add('$id|$title'),
);
expect(opened, ['consultant-2|林顾问']);
```

Run:

```powershell
flutter test test/features/shell/institution_consultant_picker_test.dart
```

Expected: FAIL because `openInstitutionConsultantChat` does not exist.

- [ ] **Step 2: Implement the picker-to-chat orchestration helper**

Add this public, independently tested helper beside `showInstitutionConsultantPicker`:

```dart
Future<void> openInstitutionConsultantChat({
  required BuildContext context,
  required Future<List<BookingConsultant>> Function() loadConsultants,
  required Future<void> Function(String targetId, {String? title})
      openDirectMessage,
}) async {
  final consultant = await showInstitutionConsultantPicker(
    context: context,
    loadConsultants: loadConsultants,
  );
  if (consultant == null || !context.mounted) return;
  await openDirectMessage(consultant.id, title: consultant.name);
}
```

Add a dismissal case to the same test and verify `openDirectMessage` is not called when the modal returns `null`.

- [ ] **Step 3: Implement guarded shell orchestration**

Import the picker and add `_institutionConsultantPickerOpen = false`. Implement:

```dart
Future<void> _openInstitutionConsultants(String institutionId) async {
  final repository = _bookingRepository;
  final id = institutionId.trim();
  if (repository == null || id.isEmpty || _institutionConsultantPickerOpen) {
    return;
  }
  _institutionConsultantPickerOpen = true;
  try {
    await openInstitutionConsultantChat(
      context: context,
      loadConsultants: () => repository.getConsultants(id),
      openDirectMessage: _openDirectMessage,
    );
  } finally {
    _institutionConsultantPickerOpen = false;
  }
}
```

This ordering guarantees that the modal future completes, and therefore the sheet closes, before DM creation begins.

- [ ] **Step 4: Wire every discover entry point**

Pass `onConsultInstitution: _bookingRepository == null ? null : _openInstitutionConsultants` to the main `DiscoverPage` and every `DiscoverDetailPage` created directly by `AppShell`, including home, diary association, favorites/notifications, and deep-link paths. Within `discover_page.dart`, retain the Task 1 forwarding at every nested detail-page construction.

- [ ] **Step 5: Run both focused widget test files**

Run:

```powershell
flutter test test/features/discover/catalog_institution_consultants_test.dart test/features/shell/institution_consultant_picker_test.dart
```

Expected: PASS with no exceptions after modal dismissal or consultant selection.

- [ ] **Step 6: Preserve the existing endpoint contract test**

Run the already-existing parser/endpoint test without changing it:

```powershell
flutter test test/features/booking/booking_remote_data_source_test.dart
```

Expected: PASS, including `loads institution consultants from discover contract`, proving the reused request path and `id`/`name` parsing.

- [ ] **Step 7: Commit the end-to-end wiring**

```powershell
git add joysong-flutter/lib/features/shell/presentation/app_shell.dart joysong-flutter/lib/features/shell/presentation/institution_consultant_picker.dart joysong-flutter/test/features/shell/institution_consultant_picker_test.dart
git commit -m "feat: open consultant direct messages from institutions"
```

### Task 4: Final verification and developer-diagram review

**Files:**
- Inspect: `design/`
- Modify only if an existing institution/chat flow diagram is directly affected.

**Interfaces:**
- Consumes: the completed Flutter implementation and the project testing rules.
- Produces: fresh test and analyzer evidence plus a clear UML follow-up status.

- [ ] **Step 1: Run the complete focused regression set once**

```powershell
flutter test test/features/discover/catalog_institution_consultants_test.dart test/features/shell/institution_consultant_picker_test.dart test/features/booking/booking_remote_data_source_test.dart
```

Expected: all focused tests PASS.

- [ ] **Step 2: Run Flutter analysis once**

```powershell
flutter analyze
```

Expected: exit code 0 with no analyzer errors. Do not rerun after success.

- [ ] **Step 3: Review `design/` for an affected flow diagram**

Search only for institution consultation, consultant membership, and DM flow diagrams. If a directly affected diagram exists, update its source and generated image using the repository's existing generation method; otherwise report that no matching diagram required a change. Do not create a new diagram solely for this feature.

- [ ] **Step 4: Verify the final diff and working tree**

```powershell
git diff --check
git status --short
git log --oneline -5
```

Expected: no whitespace errors, only scoped feature/doc changes, and the planned commits visible at the branch tip.
