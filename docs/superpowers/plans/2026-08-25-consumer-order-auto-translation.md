# Consumer Order AI Auto-Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing opt-in consumer AI translation path across booking, orders, payment/refund/review summaries, order-related notifications, and diary order associations without translating user input or professional operations.

**Architecture:** Add one stable request-owning wrapper around the existing translation builder, then make every reusable booking/order surface default-off through an `enableAutoTranslation` parameter. Eligible consumer entry points opt in explicitly, list owners preserve state with valid unique entity keys, and every field continues to use the existing authenticated `/api/translations` controller for source-first rendering, cache, concurrency, failure fallback, and stale-result rejection.

**Tech Stack:** Flutter/Dart, `StatefulWidget`, existing `AutoTranslationController`/`AutoTranslationScope`, existing `OrdersRepository` and `MessagingRepository`, Flutter widget tests.

**Spec:** `docs/superpowers/specs/2026-08-25-consumer-order-auto-translation-design.md`

## Global Constraints

- Work only in `D:\code\kotlin\joysong\.worktrees\order-auto-translation` on branch `codex/order-auto-translation`; do not edit the local `master` checkout.
- This phase is Flutter-only. Do not change backend contracts, YAML files, provider configuration, database schemas, order data, or payment behavior.
- Reuse authenticated `POST /api/translations`; do not introduce `order`, `refund`, or `notification` backend content types.
- Automatic translation requires English App language, the persisted AI translation switch enabled, an authenticated user, non-empty Chinese source text, and a locally allowlisted page/field.
- Source text renders immediately. Success replaces it asynchronously; failure, invalid output, disablement, logout, locale change, and stale completion retain or restore the exact source.
- Every public/reusable page added by this plan has `enableAutoTranslation = false`; only consumer `AppShell` entry points pass `true`.
- Translate only visible persisted read-only values. Never mutate DTOs, controller state, database values, form controllers, or submitted payloads.
- Never submit person names, IDs, order numbers, phone/certificate/verification values, URLs, money, dates/times/countdowns, status/workflow values, payment/provider data, localized labels, or API errors.
- Keep `features/identity/**`, professional institution/project/doctor/consultant management, `features/professional_management/**`, and `joysong-admin/**` behavior unchanged.
- Empty IDs and duplicate normalized IDs are source-only. Unique non-empty entity IDs own stable widget keys so refresh, prepend, reorder, pagination, and timers cannot attach a result to another entity.
- Reuse only these mappings:

| Visible value | `contentType` | `contentId` | `field` |
|---|---|---|---|
| Booking project name | `project` | `institution-project:<project.id>` | `name` |
| Booking institution name | `institution` | `institution:<project.institutionId>` | `name` |
| Booking doctor title | `doctor` | `doctor:<doctor.id>` | `title` |
| Order snapshot project | `project` | `order:<order.id>` | `projectName` |
| Order snapshot institution | `institution` | `order:<order.id>` | `institutionName` |
| Persisted order remark | `general` | `order:<order.id>` | `remark` |
| Refund reason | `general` | `refund:<refund.id>` | `reason` |
| Refund visible description | `general` | `refund:<refund.id>` | `description` |
| Refund rejection reason | `general` | `refund:<refund.id>` | `rejectReason` |
| Status-log remark | `general` | `order-status-log:<log.id>` | `remark` |
| Order-related notification title | `general` | `notification:<notification.id>` | `title` |
| Order-related notification content | `general` | `notification:<notification.id>` | `content` |
| Manual diary project association | `project` | `project:<project.id>` | `name` |
| Manual diary institution association | `institution` | `institution:<institution.id>` | `name` |

- Run Flutter commands from `joysong-flutter/` through the confirmed SDK at `D:\code\kotlin\joysong\.flutter-cache\sdk\flutter`. Use this PowerShell form to bypass the occupied global startup lock:

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
$env:FLUTTER_ROOT = $flutterSdk
$env:FLUTTER_ALREADY_LOCKED = 'true'
$env:CI = 'true'
$env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/core/translation/auto_translation_builder_test.dart
```

- Follow the repository test rules: start with the smallest new test, rerun only failed/relevant cases while fixing, run each already-green command no more than once after a change, run `flutter analyze` after focused tests, and run at most one full Flutter suite with a ten-minute limit.

## File Map

- `joysong-flutter/lib/core/translation/auto_translation_builder.dart`: owns stable request identity and the stable text convenience widget.
- `joysong-flutter/lib/features/booking/presentation/booking_page.dart`: consumer booking project, institution, and doctor-title rendering.
- `joysong-flutter/lib/features/orders/presentation/orders_page.dart`: order-card allowlist and stable list ownership.
- `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`: order snapshots, persisted remark, refund fields, log remarks, and refund/review flow summaries.
- `joysong-flutter/lib/features/orders/presentation/payment_page.dart`: payment summary project/institution only.
- `joysong-flutter/lib/features/messaging/presentation/messaging_pages.dart`: order-related notification title/content only.
- `joysong-flutter/lib/features/social/presentation/social_page.dart`: diary association summary and order picker only.
- `joysong-flutter/lib/features/shell/presentation/app_shell.dart`: explicit consumer opt-in wiring; no navigation changes.
- `joysong-flutter/test/core/translation/auto_translation_builder_test.dart`: stable request lifecycle contract.
- `joysong-flutter/test/features/booking/booking_page_auto_translation_test.dart`: booking allowlist and input exclusion.
- `joysong-flutter/test/features/orders/orders_page_auto_translation_test.dart`: order-card mapping and list ownership.
- `joysong-flutter/test/features/orders/order_detail_auto_translation_test.dart`: detail/refund/log mapping and stale protection.
- `joysong-flutter/test/features/orders/payment_page_auto_translation_test.dart`: timer-safe payment summary mapping.
- `joysong-flutter/test/features/messaging/notification_page_auto_translation_test.dart`: the three order-related target types and non-order exclusions.
- `joysong-flutter/test/features/social/social_page_auto_translation_test.dart`: diary order association mapping and combined-text privacy.
- Existing fixtures and navigation/professional tests are extended instead of creating duplicate repositories.

---

### Task 1: Stable Automatic Translation Builder

**Files:**
- Modify: `joysong-flutter/lib/core/translation/auto_translation_builder.dart:1-112`
- Modify: `joysong-flutter/test/core/translation/auto_translation_builder_test.dart`

**Interfaces:**
- Consumes: `AutoTranslationRequest`, `TranslationValidator`, `AutoTranslationBuilder`
- Produces: `StableAutoTranslationBuilder({required bool enabled, required String contentType, required String contentId, required String field, required String sourceText, TranslationValidator? validator, required Widget Function(BuildContext, String) builder})`
- Produces: `StableAutoTranslatedText` with the existing `AutoTranslatedText` text properties plus the stable builder parameters

- [ ] **Step 1: Add failing stable-identity tests**

Add `_stableTranslationHost` beside the existing `_translationHost`:

```dart
Widget _stableTranslationHost({
  required AutoTranslationController controller,
  bool enabled = true,
  String contentType = 'project',
  String contentId = 'project-1',
  String field = 'description',
  String sourceText = '项目说明',
  TranslationValidator? validator,
}) {
  return Directionality(
    textDirection: TextDirection.ltr,
    child: AutoTranslationScope(
      controller: controller,
      enabled: true,
      targetLanguage: 'en-US',
      child: StableAutoTranslationBuilder(
        enabled: enabled,
        contentType: contentType,
        contentId: contentId,
        field: field,
        sourceText: sourceText,
        validator: validator,
        builder: _visibleText,
      ),
    ),
  );
}
```

Add these tests with exact identity assertions:

```dart
testWidgets('stable builder reuses its request across equivalent rebuilds',
    (tester) async {
  final repository = RecordingTranslationRepository()..holdResponses = true;
  final controller = _activeController(repository);
  addTearDown(controller.dispose);

  await tester.pumpWidget(_stableTranslationHost(controller: controller));
  final first = tester
      .widget<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
      .request;
  repository.failNext();
  await _pumpTranslation(tester);

  await tester.pumpWidget(_stableTranslationHost(controller: controller));
  final rebuilt = tester
      .widget<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
      .request;

  expect(rebuilt, same(first));
  expect(repository.calls, hasLength(1));
});

testWidgets('stable builder changes identity when a request value changes',
    (tester) async {
  final repository = RecordingTranslationRepository()..holdResponses = true;
  final controller = _activeController(repository);
  addTearDown(controller.dispose);

  await tester.pumpWidget(_stableTranslationHost(controller: controller));
  final before = tester
      .widget<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
      .request;
  await tester.pumpWidget(
    _stableTranslationHost(
      controller: controller,
      contentId: 'project-2',
      field: 'summary',
      sourceText: '新的简介',
    ),
  );
  final after = tester
      .widget<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
      .request;

  expect(after, isNot(same(before)));
  expect(after.contentId, 'project-2');
  expect(after.field, 'summary');
  expect(after.sourceText, '新的简介');
});

testWidgets('disabled stable builder stays source-only and rejects late work',
    (tester) async {
  final repository = RecordingTranslationRepository()..holdResponses = true;
  final controller = _activeController(repository);
  addTearDown(controller.dispose);

  await tester.pumpWidget(_stableTranslationHost(controller: controller));
  await tester.pumpWidget(
    _stableTranslationHost(controller: controller, enabled: false),
  );
  repository.completeNext('Late translation');
  await _pumpTranslation(tester);

  expect(find.text('项目说明'), findsOneWidget);
  expect(find.text('Late translation'), findsNothing);
  expect(find.byType(AutoTranslationBuilder), findsNothing);
});
```

Also add table-driven cases for `contentType`, `contentId`, `field`, exact `sourceText`, and validator instance changes, plus a `StableAutoTranslatedText` property-forwarding test with `enabled: false` and zero repository calls.

- [ ] **Step 2: Run the smallest core test and verify RED**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
$env:FLUTTER_ROOT = $flutterSdk; $env:FLUTTER_ALREADY_LOCKED = 'true'; $env:CI = 'true'; $env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/core/translation/auto_translation_builder_test.dart --plain-name "stable builder reuses its request across equivalent rebuilds"
```

Expected: compile failure because `StableAutoTranslationBuilder` does not exist.

- [ ] **Step 3: Implement the stable request owner and text wrapper**

Append these public widgets to `auto_translation_builder.dart`; keep the existing builder unchanged:

```dart
final class StableAutoTranslationBuilder extends StatefulWidget {
  const StableAutoTranslationBuilder({
    required this.enabled,
    required this.contentType,
    required this.contentId,
    required this.field,
    required this.sourceText,
    required this.builder,
    this.validator,
    super.key,
  });

  final bool enabled;
  final String contentType;
  final String contentId;
  final String field;
  final String sourceText;
  final TranslationValidator? validator;
  final Widget Function(BuildContext context, String visibleText) builder;

  @override
  State<StableAutoTranslationBuilder> createState() =>
      _StableAutoTranslationBuilderState();
}

final class _StableAutoTranslationBuilderState
    extends State<StableAutoTranslationBuilder> {
  AutoTranslationRequest? _request;

  @override
  void initState() {
    super.initState();
    _synchronizeRequest();
  }

  @override
  void didUpdateWidget(StableAutoTranslationBuilder oldWidget) {
    super.didUpdateWidget(oldWidget);
    _synchronizeRequest();
  }

  void _synchronizeRequest() {
    if (widget.contentType.trim().isEmpty ||
        widget.contentId.trim().isEmpty ||
        widget.field.trim().isEmpty ||
        widget.sourceText.trim().isEmpty) {
      _request = null;
      return;
    }
    final previous = _request;
    if (previous != null &&
        previous.contentType == widget.contentType &&
        previous.contentId == widget.contentId &&
        previous.field == widget.field &&
        previous.sourceText == widget.sourceText &&
        identical(previous.validator, widget.validator)) {
      return;
    }
    _request = AutoTranslationRequest(
      contentType: widget.contentType,
      contentId: widget.contentId,
      field: widget.field,
      sourceText: widget.sourceText,
      validator: widget.validator,
    );
  }

  @override
  Widget build(BuildContext context) {
    final request = _request;
    if (!widget.enabled || request == null) {
      return widget.builder(context, widget.sourceText);
    }
    return AutoTranslationBuilder(request: request, builder: widget.builder);
  }
}

final class StableAutoTranslatedText extends StatelessWidget {
  const StableAutoTranslatedText({
    required this.enabled,
    required this.contentType,
    required this.contentId,
    required this.field,
    required this.sourceText,
    this.validator,
    this.style,
    this.maxLines,
    this.overflow,
    this.textAlign,
    this.semanticsLabel,
    super.key,
  });

  final bool enabled;
  final String contentType;
  final String contentId;
  final String field;
  final String sourceText;
  final TranslationValidator? validator;
  final TextStyle? style;
  final int? maxLines;
  final TextOverflow? overflow;
  final TextAlign? textAlign;
  final String? semanticsLabel;

  @override
  Widget build(BuildContext context) => StableAutoTranslationBuilder(
        enabled: enabled,
        contentType: contentType,
        contentId: contentId,
        field: field,
        sourceText: sourceText,
        validator: validator,
        builder: (context, visibleText) => Text(
          visibleText,
          style: style,
          maxLines: maxLines,
          overflow: overflow,
          textAlign: textAlign,
          semanticsLabel: semanticsLabel,
        ),
      );
}
```

`enabled` controls only whether the lower builder is mounted. It is not request identity. Do not duplicate Chinese detection, authentication, target-language checks, cache, or retry behavior in this wrapper.

- [ ] **Step 4: Format and run the full builder test file**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" format lib/core/translation/auto_translation_builder.dart test/core/translation/auto_translation_builder_test.dart
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/core/translation/auto_translation_builder_test.dart
```

Expected: all builder tests pass, including exact-source restoration and one-call equivalent rebuild behavior.

- [ ] **Step 5: Commit the core unit**

```powershell
git add -- joysong-flutter/lib/core/translation/auto_translation_builder.dart joysong-flutter/test/core/translation/auto_translation_builder_test.dart
git commit -m "feat(flutter): add stable auto-translation builders"
```

---

### Task 2: Booking Project, Institution, and Doctor Title

**Files:**
- Modify: `joysong-flutter/lib/features/booking/presentation/booking_page.dart`
- Modify: `joysong-flutter/test/features/booking/booking_test_fixtures.dart`
- Create: `joysong-flutter/test/features/booking/booking_page_auto_translation_test.dart`

**Interfaces:**
- Consumes: `StableAutoTranslatedText`, `StableAutoTranslationBuilder`
- Produces: `BookingPage.enableAutoTranslation: bool`, default `false`
- Preserves: `BookingCompletedCallback`, booking-note controller/payload, doctor/consultant names, date, quote, and price behavior

- [ ] **Step 1: Make booking fixtures controllable and write failing widget tests**

Change the fake to return assignable values:

```dart
InstitutionProject projectResult = sampleInstitutionProject();
List<BookingDoctor> doctorResults = [sampleDoctor()];

@override
Future<InstitutionProject> getInstitutionProject(
  String institutionId,
  String projectId,
) async =>
    projectResult;

@override
Future<List<BookingDoctor>> getDoctors(String institutionProjectId) async =>
    doctorResults;
```

Add the tests `opted-in booking translates project institution and doctor title only`, `booking defaults off under an active scope`, `booking failures preserve source text`, and `booking empty and duplicate doctor IDs remain source-only`. The opted-in test must open the doctor dropdown before checking its menu item and assert this exact mounted request set:

```dart
expect(_mountedRequests(tester), containsAll(const {
  ('project', 'institution-project:ip-1', 'name', '光子嫩肤'),
  ('institution', 'institution:institution-1', 'name', '娇颜颂医疗美容'),
  ('doctor', 'doctor:doctor-1', 'title', '主任医师'),
}));
expect(translations.calls.map((call) => call.text), isNot(contains('张医生')));
expect(translations.calls.map((call) => call.text),
    isNot(contains('李咨询师')));

await tester.enterText(
  find.byKey(const Key('booking-remark-field')),
  '请在术前电话联系我',
);
await tester.pump();
expect(translations.calls.map((call) => call.text),
    isNot(contains('请在术前电话联系我')));
```

Define the request collector in the test file so it validates local identity, not only API text/type:

```dart
Set<(String, String, String, String)> _mountedRequests(WidgetTester tester) =>
    tester
        .widgetList<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
        .map((widget) => (
              widget.request.contentType,
              widget.request.contentId,
              widget.request.field,
              widget.request.sourceText,
            ))
        .toSet();
```

- [ ] **Step 2: Run the opted-in booking test and verify RED**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
$env:FLUTTER_ROOT = $flutterSdk; $env:FLUTTER_ALREADY_LOCKED = 'true'; $env:CI = 'true'; $env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/booking/booking_page_auto_translation_test.dart --plain-name "opted-in booking translates project institution and doctor title only"
```

Expected: compile failure because `BookingPage.enableAutoTranslation` is absent.

- [ ] **Step 3: Add the default-off page flag and field allowlist**

Add the translation import and public parameter:

```dart
const BookingPage({
  required this.controller,
  required this.onOrderCreated,
  this.enableAutoTranslation = false,
  super.key,
});

final bool enableAutoTranslation;
```

Pass the flag into `_ProjectCard`. Render its two eligible values independently:

```dart
StableAutoTranslatedText(
  enabled: enableAutoTranslation && project.id.trim().isNotEmpty,
  contentType: 'project',
  contentId: 'institution-project:${project.id.trim()}',
  field: 'name',
  sourceText: project.name,
  style: theme.textTheme.titleLarge,
),
StableAutoTranslatedText(
  enabled: enableAutoTranslation && project.institutionId.trim().isNotEmpty,
  contentType: 'institution',
  contentId: 'institution:${project.institutionId.trim()}',
  field: 'name',
  sourceText: project.institutionName,
),
```

Before building doctor items, count normalized non-empty doctor IDs. A doctor with an empty ID or an ID count other than one is source-only and uses `ObjectKey(doctor)`; a unique doctor uses `ValueKey('booking-doctor:<id>')`. Build the combined visible row from a plain name and a translated-title-only source:

```dart
StableAutoTranslationBuilder(
  enabled: widget.enableAutoTranslation && doctorIdentityStable,
  contentType: 'doctor',
  contentId: 'doctor:$doctorId',
  field: 'title',
  sourceText: doctor.title,
  builder: (context, visibleTitle) => Text(
    '${doctor.name}${visibleTitle.isEmpty ? '' : ' · $visibleTitle'}',
    overflow: TextOverflow.ellipsis,
  ),
)
```

Never pass the combined name/title string, specialties, consultant text, or booking-note text to a translation builder.

- [ ] **Step 4: Format and run the booking translation file**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" format lib/features/booking/presentation/booking_page.dart test/features/booking/booking_test_fixtures.dart test/features/booking/booking_page_auto_translation_test.dart
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/booking/booking_page_auto_translation_test.dart
```

Expected: all new booking translation tests pass; default-off and invalid doctor identities make zero calls.

- [ ] **Step 5: Commit the booking unit**

```powershell
git add -- joysong-flutter/lib/features/booking/presentation/booking_page.dart joysong-flutter/test/features/booking/booking_test_fixtures.dart joysong-flutter/test/features/booking/booking_page_auto_translation_test.dart
git commit -m "feat(flutter): translate consumer booking summaries"
```

---

### Task 3: Order Cards with Stable Entity Ownership

**Files:**
- Modify: `joysong-flutter/lib/features/orders/presentation/orders_page.dart`
- Modify: `joysong-flutter/test/features/orders/order_test_fixtures.dart`
- Create: `joysong-flutter/test/features/orders/orders_page_auto_translation_test.dart`

**Interfaces:**
- Consumes: `StableAutoTranslatedText`
- Produces: `OrdersPage.enableAutoTranslation: bool`, default `false`
- Preserves: filtering, pagination footer, doctor name, status/refund status, amount, appointment time, image, and actions

- [ ] **Step 1: Extend the order fixture and add failing card tests**

Add optional `orderNo`, `projectName`, `doctorName`, and `remark` parameters to `sampleOrder` and pass them directly into its `Order` constructor. Keep every existing default unchanged. Add these widget tests:

```dart
testWidgets('opted-in order cards translate only project and institution',
    (tester) async {
  final ordersRepository = FakeOrdersRepository()
    ..orders = [
      sampleOrder(
        id: 'order-a',
        projectName: '项目甲',
        institutionName: '机构甲',
        doctorName: '张医生',
      ),
    ];
  final translations = RecordingTranslationRepository()..holdResponses = true;
  await tester.pumpWidget(_ordersHost(
    ordersRepository: ordersRepository,
    translations: translations,
    enableAutoTranslation: true,
  ));
  await tester.pump();

  expect(_mountedRequests(tester), const {
    ('project', 'order:order-a', 'projectName', '项目甲'),
    ('institution', 'order:order-a', 'institutionName', '机构甲'),
  });
  expect(translations.calls.map((call) => call.text), isNot(contains('张医生')));
});
```

Add `order cards retain translation ownership through prepend reorder and refresh`, `empty and duplicate order IDs remain source-only`, and `orders page defaults off under an active scope`. In the reorder test, complete translations by source text, reorder existing entities, prepend a third entity, and assert the two old English values remain with their original cards while only the third entity adds two repository calls.

- [ ] **Step 2: Run the allowlist test and verify RED**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
$env:FLUTTER_ROOT = $flutterSdk; $env:FLUTTER_ALREADY_LOCKED = 'true'; $env:CI = 'true'; $env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/orders/orders_page_auto_translation_test.dart --plain-name "opted-in order cards translate only project and institution"
```

Expected: compile failure because the page flag is absent.

- [ ] **Step 3: Implement unique-ID ownership and the two card fields**

Add the public default-off flag and pass it to `_OrderCard`. Compute normalized duplicate IDs from the visible filtered `orders`, not only the newly loaded page:

```dart
Set<String> _duplicateOrderIds(Iterable<Order> orders) {
  final seen = <String>{};
  final duplicates = <String>{};
  for (final order in orders) {
    final id = order.id.trim();
    if (id.isNotEmpty && !seen.add(id)) duplicates.add(id);
  }
  return duplicates;
}
```

For each item:

```dart
final id = order.id.trim();
final identityStable = id.isNotEmpty && !duplicateIds.contains(id);
return _OrderCard(
  key: identityStable
      ? ValueKey<String>('consumer-order:$id')
      : ObjectKey(order),
  order: order,
  enableAutoTranslation: widget.enableAutoTranslation && identityStable,
  onTap: () => widget.onOrderSelected(order),
  onEditReview: order.hasReview && widget.onEditReview != null
      ? () => widget.onEditReview!(order)
      : null,
);
```

Use this item-index callback to map a unique order key back to the current filtered list:

```dart
findItemIndexCallback: (key) {
  if (key is! ValueKey<String>) return null;
  const prefix = 'consumer-order:';
  if (!key.value.startsWith(prefix)) return null;
  final id = key.value.substring(prefix.length);
  final index = orders.indexWhere((order) {
    final candidate = order.id.trim();
    return candidate == id && !duplicateIds.contains(candidate);
  });
  return index < 0 ? null : index;
},
```

Render only these two values through stable text:

```dart
StableAutoTranslatedText(
  enabled: enableAutoTranslation,
  contentType: 'project',
  contentId: 'order:${order.id.trim()}',
  field: 'projectName',
  sourceText: order.projectName,
)
```

Render `institutionName` with `contentType: 'institution'` and field `institutionName` only when that database value is actually visible. If the card shows the deterministic localized Joysong fallback, render ordinary `Text` and create no institution request.

- [ ] **Step 4: Format and run the order-card translation file**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" format lib/features/orders/presentation/orders_page.dart test/features/orders/order_test_fixtures.dart test/features/orders/orders_page_auto_translation_test.dart
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/orders/orders_page_auto_translation_test.dart
```

Expected: all order-card tests pass; reordering does not duplicate old requests or swap visible translations.

- [ ] **Step 5: Commit the card unit**

```powershell
git add -- joysong-flutter/lib/features/orders/presentation/orders_page.dart joysong-flutter/test/features/orders/order_test_fixtures.dart joysong-flutter/test/features/orders/orders_page_auto_translation_test.dart
git commit -m "feat(flutter): translate consumer order cards"
```

---

### Task 4: Read-Only Order Detail, Refund, and Timeline Text

**Files:**
- Modify: `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`
- Modify: `joysong-flutter/test/features/orders/order_test_fixtures.dart`
- Create: `joysong-flutter/test/features/orders/order_detail_auto_translation_test.dart`

**Interfaces:**
- Consumes: `StableAutoTranslatedText`
- Produces: `OrderDetailPage.enableAutoTranslation: bool`, default `false`
- Propagates: flag to order information, travel-ground-service information, refund card, status timeline, and downstream flow pages
- Preserves: every order action, service-conversation callback, payment decision, and displayed field not in the allowlist

- [ ] **Step 1: Make status logs configurable and add failing detail tests**

Extend `FakeOrdersRepository`:

```dart
List<OrderStatusLog> statusLogs = const [];

@override
Future<List<OrderStatusLog>> getStatusLogs(String id) async => statusLogs;
```

Add tests named `detail translates snapshots persisted note refund fields and log remarks`, `detail sends only each log remark without its date`, `detail failure keeps source text`, `detail rejects a stale completion after reload`, `invalid order refund and duplicate log IDs remain source-only`, and `detail defaults off under an active scope`. The main request assertion is:

```dart
expect(_mountedRequests(tester), const {
  ('project', 'order:order-1', 'projectName', '光子嫩肤'),
  ('institution', 'order:order-1', 'institutionName', '娇颜颂医疗美容'),
  ('general', 'order:order-1', 'remark', '术后请电话联系'),
  ('general', 'refund:refund-1', 'reason', '行程调整'),
  ('general', 'refund:refund-1', 'description', '无法按期到院'),
  ('general', 'refund:refund-1', 'rejectReason', '服务已开始'),
  ('general', 'order-status-log:11', 'remark', '订单已创建'),
  ('general', 'order-status-log:12', 'remark', '机构已确认'),
});
expect(translations.calls.map((call) => call.text), isNot(contains('张医生')));
expect(translations.calls.any((call) => call.text.contains('2026-')), isFalse);
expect(translations.calls.any((call) => call.text.contains('JOY2026')), isFalse);
```

Build the refund fixture so `_refundDescription(refund)` visibly returns `无法按期到院`; assert that exact visible value is the source and the legacy embedded rejection suffix is never submitted.

- [ ] **Step 2: Run the main detail test and verify RED**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
$env:FLUTTER_ROOT = $flutterSdk; $env:FLUTTER_ALREADY_LOCKED = 'true'; $env:CI = 'true'; $env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/orders/order_detail_auto_translation_test.dart --plain-name "detail translates snapshots persisted note refund fields and log remarks"
```

Expected: compile failure because `OrderDetailPage.enableAutoTranslation` is absent.

- [ ] **Step 3: Implement detail mappings and invalid-ID gates**

Add `enableAutoTranslation = false` to `OrderDetailPage` and pass it to every relevant private child. For order values, compute:

```dart
final orderId = order.id.trim();
final translateOrder = enableAutoTranslation && orderId.isNotEmpty;
```

Wrap only project name, a visible non-empty institution name, and a non-empty persisted `order.remark` with the mapping table above. Doctor and consultant names remain ordinary `Text`.

For `_RefundCard`, compute a non-empty trimmed refund ID once. Use `visibleDescription = _refundDescription(refund)` as the description source and create separate stable text widgets for `reason`, `visibleDescription`, and `rejectReason`; empty values render nothing and create no request.

For `_StatusTimeline`, count positive log IDs and mark every repeated positive ID plus every `id <= 0` source-only. Give unique logs `ValueKey<String>('order-status-log:<id>')`, invalid/duplicate logs `ObjectKey(log)`, and split date from remark:

```dart
Row(
  crossAxisAlignment: CrossAxisAlignment.start,
  children: [
    Text(_detailDateTime(log.createdAt)),
    if (log.remark.isNotEmpty) ...[
      const Text(' · '),
      Expanded(
        child: StableAutoTranslatedText(
          enabled: enableAutoTranslation && identityStable,
          contentType: 'general',
          contentId: 'order-status-log:${log.id}',
          field: 'remark',
          sourceText: log.remark,
        ),
      ),
    ],
  ],
)
```

Do not add fields to the travel-ground-service UI. Translate only the institution snapshot when that widget already displays it.

- [ ] **Step 4: Format and run the detail translation file**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" format lib/features/orders/presentation/order_detail_page.dart test/features/orders/order_test_fixtures.dart test/features/orders/order_detail_auto_translation_test.dart
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/orders/order_detail_auto_translation_test.dart
```

Expected: all detail translation tests pass; a late old value cannot replace refreshed source.

- [ ] **Step 5: Commit the detail unit**

```powershell
git add -- joysong-flutter/lib/features/orders/presentation/order_detail_page.dart joysong-flutter/test/features/orders/order_test_fixtures.dart joysong-flutter/test/features/orders/order_detail_auto_translation_test.dart
git commit -m "feat(flutter): translate read-only order details"
```

---

### Task 5: Payment, Refund, and Review Summaries Without Input Mutation

**Files:**
- Modify: `joysong-flutter/lib/features/orders/presentation/payment_page.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`
- Create: `joysong-flutter/test/features/orders/payment_page_auto_translation_test.dart`
- Modify: `joysong-flutter/test/features/orders/order_detail_auto_translation_test.dart`
- Modify: `joysong-flutter/test/features/orders/review_order_page_test.dart`

**Interfaces:**
- Produces: `PaymentPage.enableAutoTranslation`, `RefundApplyPage.enableAutoTranslation`, and `ReviewOrderPage.enableAutoTranslation`, each default `false`
- Consumes: `OrderDetailPage.enableAutoTranslation`
- Preserves: payment timers/polling, provider state, refund reason/description form values, review content/tags/rating/images, and exact submission payloads

- [ ] **Step 1: Add failing summary/timer and form-isolation tests**

Add payment tests named `payment summary translates project and institution once across timer rebuilds`, `payment page defaults off under an active scope`, and `payment state provider amount url and errors never create translation requests`. The first test uses an injected `now` and asserts:

```dart
expect(_mountedRequests(tester), const {
  ('project', 'order:order-1', 'projectName', '光子嫩肤'),
  ('institution', 'order:order-1', 'institutionName', '娇颜颂医疗美容'),
});
expect(translations.calls, hasLength(2));

await tester.pump(const Duration(seconds: 3));
expect(translations.calls, hasLength(2));

await paymentController.refresh();
await tester.pump();
expect(translations.calls, hasLength(2));
```

Extend order-detail/review tests so refund and review summaries have exactly the same two mounted requests, while entered values never appear in the translation repository and remain exact in the submitted draft:

```dart
expect(ordersRepository.lastRefundReason, '临时改变行程');
expect(ordersRepository.lastRefundDescription, '需要延期处理');
expect(translations.calls.map((call) => call.text),
    isNot(contains('临时改变行程')));
expect(translations.calls.map((call) => call.text),
    isNot(contains('需要延期处理')));
```

For review editing, initialize Chinese content and tags, submit them unchanged, and assert neither string is a translation source.

- [ ] **Step 2: Run the payment timer test and verify RED**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
$env:FLUTTER_ROOT = $flutterSdk; $env:FLUTTER_ALREADY_LOCKED = 'true'; $env:CI = 'true'; $env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/orders/payment_page_auto_translation_test.dart --plain-name "payment summary translates project and institution once across timer rebuilds"
```

Expected: compile failure because `PaymentPage.enableAutoTranslation` is absent.

- [ ] **Step 3: Implement summary flag propagation and eligible fields**

Add default-off flags and pass them through these exact chains:

```text
OrderDetailPage
  -> PaymentPage -> _PaymentBody -> _OrderSummary
  -> RefundApplyPage -> _FlowOrderSummary
  -> ReviewOrderPage -> _FlowOrderSummary
```

Both summary widgets use only:

```dart
StableAutoTranslatedText(
  enabled: enableAutoTranslation && order.id.trim().isNotEmpty,
  contentType: 'project',
  contentId: 'order:${order.id.trim()}',
  field: 'projectName',
  sourceText: order.projectName,
)
```

Use `contentType: 'institution'` and field `institutionName` for a visible institution value. Keep order number, doctor/consultant names, prices, statuses, dates, countdown, payment method/provider, URLs, provider failure fields, and controller/API errors as their current ordinary widgets.

Pass the flag into both payment route constructions and both review route constructions in `OrderDetailPage`, as well as the refund route. Do not wrap `_customReason`, `_description`, review `_content`, review `_tags`, rating, images, or any controller-created draft.

- [ ] **Step 4: Format and run the three focused order-flow test files**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" format lib/features/orders/presentation/payment_page.dart lib/features/orders/presentation/order_detail_page.dart test/features/orders/payment_page_auto_translation_test.dart test/features/orders/order_detail_auto_translation_test.dart test/features/orders/review_order_page_test.dart
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/orders/payment_page_auto_translation_test.dart test/features/orders/order_detail_auto_translation_test.dart test/features/orders/review_order_page_test.dart
```

Expected: all focused payment/detail/review tests pass and timer rebuilds do not create repeat calls.

- [ ] **Step 5: Commit the order-flow unit**

```powershell
git add -- joysong-flutter/lib/features/orders/presentation/payment_page.dart joysong-flutter/lib/features/orders/presentation/order_detail_page.dart joysong-flutter/test/features/orders/payment_page_auto_translation_test.dart joysong-flutter/test/features/orders/order_detail_auto_translation_test.dart joysong-flutter/test/features/orders/review_order_page_test.dart
git commit -m "feat(flutter): translate order flow summaries"
```

---

### Task 6: Order-Related Notification Text

**Files:**
- Modify: `joysong-flutter/lib/features/messaging/presentation/messaging_pages.dart:1-145`
- Create: `joysong-flutter/test/features/messaging/notification_page_auto_translation_test.dart`

**Interfaces:**
- Consumes: `NotificationTarget.parse(String targetType, String targetId)` and `StableAutoTranslatedText`
- Produces: `NotificationPage.enableAutoTranslation: bool`, default `false`
- Eligible parsed kinds: `NotificationTargetKind.orderDetail` and `NotificationTargetKind.orderServiceConversation`
- Preserves: notification type/icon/read state/time/filter/target/navigation and every non-order notification

- [ ] **Step 1: Add failing notification allowlist tests**

Create a `Fake implements MessagingRepository` that returns a controlled notification list, `0` unread count, and completes `markNotificationRead`; its uncalled methods may remain inherited through `Fake`. Add the test `NotificationPage translates all order-related target title and content fields`, using four Chinese rows with target types `order`, `order_refund`, `order_service_conversation`, and `identity_application`. Assert:

```dart
expect(_mountedRequests(tester), const {
  ('general', 'notification:n-order', 'title', '订单已取消'),
  ('general', 'notification:n-order', 'content', '请查看订单详情'),
  ('general', 'notification:n-refund', 'title', '退款已批准'),
  ('general', 'notification:n-refund', 'content', '退款申请已通过'),
  ('general', 'notification:n-service', 'title', '服务消息'),
  ('general', 'notification:n-service', 'content', '医生回复了订单服务会话'),
});
expect(translations.calls.map((call) => call.text),
    isNot(contains('身份申请待审核')));
expect(translations.calls.any((call) => call.text.contains('order-1')), isFalse);
```

Add `NotificationPage defaults off under an active translation scope`, `non-order notifications make zero calls`, and `blank or duplicate normalized notification IDs remain source-only`. The duplicate test uses IDs `n-duplicate` and ` n-duplicate ` so controller exact-ID deduplication does not hide the normalized collision.

- [ ] **Step 2: Run the allowlist test and verify RED**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
$env:FLUTTER_ROOT = $flutterSdk; $env:FLUTTER_ALREADY_LOCKED = 'true'; $env:CI = 'true'; $env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/messaging/notification_page_auto_translation_test.dart --plain-name "NotificationPage translates all order-related target title and content fields"
```

Expected: compile failure because `NotificationPage.enableAutoTranslation` is absent.

- [ ] **Step 3: Implement the three-type notification boundary**

Add the default-off flag and propagate it into `_NotificationList`. Classify with the existing parser so current target normalization remains centralized:

```dart
bool _isOrderRelatedNotification(AppNotification item) {
  final kind = NotificationTarget.parse(
    item.targetType,
    item.targetId,
  ).kind;
  return kind == NotificationTargetKind.orderDetail ||
      kind == NotificationTargetKind.orderServiceConversation;
}
```

Compute duplicate normalized IDs across the filtered visible list. Give a unique non-empty row `ValueKey<String>('notification-row:<id>')`, every invalid/duplicate row `ObjectKey(item)`, and use this callback for unique-key reordering:

```dart
findItemIndexCallback: (key) {
  if (key is! ValueKey<String>) return null;
  const prefix = 'notification-row:';
  if (!key.value.startsWith(prefix)) return null;
  final id = key.value.substring(prefix.length);
  final index = items.indexWhere((item) {
    final candidate = item.id.trim();
    return candidate == id && !duplicateIds.contains(candidate);
  });
  return index < 0 ? null : index;
},
```

Replace only title and content:

```dart
StableAutoTranslatedText(
  enabled: enableAutoTranslation &&
      identityStable &&
      _isOrderRelatedNotification(item),
  contentType: 'general',
  contentId: 'notification:$notificationId',
  field: 'title',
  sourceText: item.title,
),
StableAutoTranslatedText(
  enabled: enableAutoTranslation &&
      identityStable &&
      _isOrderRelatedNotification(item),
  contentType: 'general',
  contentId: 'notification:$notificationId',
  field: 'content',
  sourceText: item.content,
  maxLines: 3,
),
```

Do not modify `notification_target.dart`, `messaging_models.dart`, controller deduplication, or the notification tap callback.

- [ ] **Step 4: Format and run notification tests**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" format lib/features/messaging/presentation/messaging_pages.dart test/features/messaging/notification_page_auto_translation_test.dart
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/messaging/notification_page_auto_translation_test.dart
```

Expected: all three order-related target types translate; identity and professional-review target types make zero calls.

- [ ] **Step 5: Commit the notification unit**

```powershell
git add -- joysong-flutter/lib/features/messaging/presentation/messaging_pages.dart joysong-flutter/test/features/messaging/notification_page_auto_translation_test.dart
git commit -m "feat(flutter): translate order-related notifications"
```

---

### Task 7: Diary Order Association Summary and Picker

**Files:**
- Modify: `joysong-flutter/lib/features/social/presentation/social_page.dart:184-914`
- Modify: `joysong-flutter/test/features/social/social_page_auto_translation_test.dart`

**Interfaces:**
- Produces: `DiaryEditorPage.enableAutoTranslation: bool`, default `false`
- Propagates: flag and raw IDs to `_AssociationPicker`; flag to `_DiaryOrderPickerPage`
- Preserves: diary title/content/tags input controllers, doctor name, order number, IDs, manual association selection, and save payload

- [ ] **Step 1: Add failing association and picker tests**

Use the real `DiaryEditorPage`, tap the existing `Related order` tile, and return orders through `FakeOrdersRepository`. Add tests named `diary order picker translates snapshots without doctor or order number`, `diary association summary translates project and institution only`, `diary order picker defaults off under an active scope`, and `diary order picker keeps blank and duplicate order IDs source-only`.

For a unique order assert:

```dart
expect(_mountedRequests(tester), const {
  ('project', 'order:order-1', 'projectName', '光子嫩肤'),
  ('institution', 'order:order-1', 'institutionName', '娇颜颂医疗美容'),
});
expect(find.text('张医生'), findsOneWidget);
expect(find.text('JOY202608060001'), findsOneWidget);
expect(translations.calls.map((call) => call.text), isNot(contains('张医生')));
expect(translations.calls.map((call) => call.text),
    isNot(contains('JOY202608060001')));
expect(translations.calls.any((call) => call.text.contains('order-1')), isFalse);
```

Select the order, return to the editor, and assert its association summary uses the two order-snapshot requests. For a persisted/manual association without an order ID, assert project and institution display values use `project:<projectId>/name` and `institution:<institutionId>/name`; a missing matching raw ID keeps that field source-only. Enter Chinese diary title/body/tags and verify those editor values are never added by this task to translation calls.

- [ ] **Step 2: Run the picker test and verify RED**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
$env:FLUTTER_ROOT = $flutterSdk; $env:FLUTTER_ALREADY_LOCKED = 'true'; $env:CI = 'true'; $env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/social/social_page_auto_translation_test.dart --plain-name "diary order picker translates snapshots without doctor or order number"
```

Expected: the picker remains Chinese and contains no order snapshot translation builders.

- [ ] **Step 3: Implement split-field association rendering**

Add `enableAutoTranslation = false` to `DiaryEditorPage`. Pass it to `_AssociationPicker` together with `_projectId` and `_institutionId`, and into `_DiaryOrderPickerPage` from `_pickOrder`.

In the association summary, use non-empty `order.id`/`orderId` first; otherwise use the specific project or institution ID. Never translate combined strings. Replace `${order.projectName} · ${order.orderNo}` with a row/wrap containing stable project text, an ordinary separator, and ordinary order-number text. Keep the doctor row ordinary. For manual values use:

```dart
StableAutoTranslatedText(
  enabled: enableAutoTranslation && projectId.trim().isNotEmpty,
  contentType: 'project',
  contentId: 'project:${projectId.trim()}',
  field: 'name',
  sourceText: projectName,
)
```

Use the institution mapping with `institution:<institutionId>` and `name`.

In `_DiaryOrderPickerPage`, compute empty/duplicate normalized order IDs, assign stable keys to unique rows, and supply `findItemIndexCallback`. Render project and institution through separate stable widgets. Render order number and doctor name in ordinary widgets with separators/newlines outside every translation source.

- [ ] **Step 4: Format and run the social automatic translation file**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" format lib/features/social/presentation/social_page.dart test/features/social/social_page_auto_translation_test.dart
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/social/social_page_auto_translation_test.dart
```

Expected: association/picker tests pass and existing social automatic translation tests remain green.

- [ ] **Step 5: Commit the social adjacency unit**

```powershell
git add -- joysong-flutter/lib/features/social/presentation/social_page.dart joysong-flutter/test/features/social/social_page_auto_translation_test.dart
git commit -m "feat(flutter): translate diary order associations"
```

---

### Task 8: Consumer Entry Wiring and Professional Zero-Request Boundary

**Files:**
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Modify: `joysong-flutter/test/features/shell/app_shell_navigation_test.dart`
- Modify: `joysong-flutter/test/features/professional_management/professional_readonly_catalog_test.dart`
- Do not modify: `joysong-flutter/lib/features/professional_management/presentation/professional_pages.dart`

**Interfaces:**
- Consumes: all default-off page flags from Tasks 2-7
- Produces: consumer-only opt-in at every current `AppShell` construction path
- Preserves: latest `NotificationTarget` navigation, including direct order detail, service conversation, and fallback behavior

- [ ] **Step 1: Add failing AppShell opt-in assertions**

Extend existing navigation tests at the moment each page is mounted:

```dart
expect(
  tester.widget<BookingPage>(find.byType(BookingPage)).enableAutoTranslation,
  isTrue,
);
expect(
  tester.widget<OrdersPage>(find.byType(OrdersPage)).enableAutoTranslation,
  isTrue,
);
expect(
  tester
      .widget<OrderDetailPage>(find.byType(OrderDetailPage))
      .enableAutoTranslation,
  isTrue,
);
expect(
  tester.widget<ReviewOrderPage>(find.byType(ReviewOrderPage))
      .enableAutoTranslation,
  isTrue,
);
expect(
  tester.widget<DiaryEditorPage>(find.byType(DiaryEditorPage))
      .enableAutoTranslation,
  isTrue,
);
expect(
  tester.widget<NotificationPage>(find.byType(NotificationPage))
      .enableAutoTranslation,
  isTrue,
);
```

Place the detail assertion in both the normal `_openOrderDetail` route and notification `_openOrderDetailById` route. Keep the existing notification navigation assertions intact.

- [ ] **Step 2: Add the professional order zero-request regression**

Extend `professional_readonly_catalog_test.dart` with an active scope, a `Fake implements ApiClient` that returns one Chinese `DoctorOrder` for both list and detail endpoints, and this behavior:

```dart
await tester.pumpWidget(
  AutoTranslationScope(
    controller: autoController,
    enabled: true,
    targetLanguage: 'en-US',
    child: MaterialApp(
      locale: const Locale('en'),
      home: DoctorOrdersPage(repository: ProfessionalRepository(apiClient)),
    ),
  ),
);
await tester.pumpAndSettle();
expect(find.text('专业端中文订单项目'), findsOneWidget);
expect(translations.calls, isEmpty);

await tester.tap(find.text('专业端中文订单项目'));
await tester.pumpAndSettle();
expect(find.byType(DoctorOrderDetailPage), findsOneWidget);
expect(find.text('PRO-20260825-001'), findsOneWidget);
expect(translations.calls, isEmpty);
```

The fake's `get<T>` decodes a list for `/management/orders` and one map for the detail path. Do not add a production flag or translation widget to either professional page.

- [ ] **Step 3: Run one navigation case and the professional case to verify RED**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
$env:FLUTTER_ROOT = $flutterSdk; $env:FLUTTER_ALREADY_LOCKED = 'true'; $env:CI = 'true'; $env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/shell/app_shell_navigation_test.dart --plain-name "order notification is read once and opens its detail directly"
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/professional_management/professional_readonly_catalog_test.dart --plain-name "doctor order list and detail make zero automatic translation requests under active scope"
```

Expected: the navigation test sees `enableAutoTranslation == false`; the professional regression already passes once its fake is complete because production remains unchanged.

- [ ] **Step 4: Enable every consumer construction path explicitly**

Add this exact named argument to the existing `BookingPage`, `OrdersPage`,
`ReviewOrderPage`, `OrderDetailPage`, `DiaryEditorPage`, and `NotificationPage`
constructors in `AppShell`:

```dart
enableAutoTranslation: true,
```

Retain every existing named argument and callback. Apply the order-detail flag
to both `_openOrderDetail` and `_openOrderDetailById`; apply review to
`_openOrderReviewEditor`; apply diary to `_openDiaryEditor`; apply notification
to `_openNotificationCategory`. Do not change `_openNotificationTarget` or any
route destination.

- [ ] **Step 5: Format and run the two affected test files**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" format lib/features/shell/presentation/app_shell.dart test/features/shell/app_shell_navigation_test.dart test/features/professional_management/professional_readonly_catalog_test.dart
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/features/shell/app_shell_navigation_test.dart test/features/professional_management/professional_readonly_catalog_test.dart
```

Expected: all navigation tests pass and both professional order pages make zero calls under an active global scope.

- [ ] **Step 6: Commit wiring and boundary coverage**

```powershell
git add -- joysong-flutter/lib/features/shell/presentation/app_shell.dart joysong-flutter/test/features/shell/app_shell_navigation_test.dart joysong-flutter/test/features/professional_management/professional_readonly_catalog_test.dart
git commit -m "feat(flutter): enable consumer order translation entries"
```

---

### Task 9: Consolidated Verification and Boundary Audit

**Files:**
- Modify only when a focused verification exposes a defect: files listed in Tasks 1-8
- Do not modify: backend, YAML, admin, identity, or professional production files

**Interfaces:**
- Consumes: Tasks 1-8
- Produces: verified Flutter-only consumer order translation with documented exclusions

- [ ] **Step 1: Run the focused translation suite once**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
$env:FLUTTER_ROOT = $flutterSdk; $env:FLUTTER_ALREADY_LOCKED = 'true'; $env:CI = 'true'; $env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test test/core/translation/auto_translation_builder_test.dart test/features/booking/booking_page_auto_translation_test.dart test/features/orders/orders_page_auto_translation_test.dart test/features/orders/order_detail_auto_translation_test.dart test/features/orders/payment_page_auto_translation_test.dart test/features/orders/review_order_page_test.dart test/features/messaging/notification_page_auto_translation_test.dart test/features/social/social_page_auto_translation_test.dart test/features/shell/app_shell_navigation_test.dart test/features/professional_management/professional_readonly_catalog_test.dart
```

Expected: zero failures. If one case fails, rerun only that case or its containing file after the correction; do not repeat already-green files without a related code change.

- [ ] **Step 2: Run Flutter static analysis once**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" analyze
```

Expected: zero analyzer issues. Record unrelated pre-existing issues instead of widening this change.

- [ ] **Step 3: Audit changed production scope and configuration boundaries**

```powershell
git diff --name-only master...HEAD -- joysong-server joysong-admin joysong-flutter/lib/features/identity joysong-flutter/lib/features/professional_management
git diff --name-only master...HEAD -- "*.yml" "*.yaml"
```

Expected: no output. The professional test file may change, but the professional production directory must not.

- [ ] **Step 4: Run at most one complete Flutter test suite**

```powershell
$flutterSdk = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter'
& "$flutterSdk\bin\cache\dart-sdk\bin\dart.exe" --disable-dart-dev "$flutterSdk\bin\cache\flutter_tools.snapshot" test
```

Stop after ten minutes if it is still running and report completed/slow tests. Do not retry the entire suite for flaky or environment-only failures.

- [ ] **Step 5: Inspect final diff and worktree cleanliness**

```powershell
git diff --check master...HEAD
git status --short
git diff --stat master...HEAD
```

Expected: no whitespace errors, no temporary files, and changes limited to the design/plan docs, shared translation builder, listed consumer pages, AppShell wiring, fixtures, and tests.

- [ ] **Step 6: Request code review and commit a correction only if needed**

Use `superpowers:requesting-code-review` against `master...HEAD`. Address only findings within this spec, rerun the smallest affected test, and use this commit message only when a correction was required:

```powershell
git commit -m "fix(flutter): finalize consumer order translation"
```

When review and verification require no correction, do not create an empty commit. Remind the user that developer-facing UML in `design/` should be updated if the project expects the translation boundary diagram to track this new consumer coverage.
