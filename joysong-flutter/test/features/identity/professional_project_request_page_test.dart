import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

void main() {
  setUp(() {});

  testWidgets(
      'platform application keeps the complete ordered plain-text form and uses the injected cover/gallery uploader',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository()
      ..managementContext = _doctorContext;
    final uploads = <Object>[
      'https://cdn.example.com/platform-cover.jpg',
      'https://cdn.example.com/platform-gallery.jpg',
      StateError('upload failed'),
    ];

    await tester.pumpWidget(_app(
      ManagementCenterPage(
        repository: repository,
        discoverRepository: const _DiscoverRepository(),
        doctorImagePicker: () async {
          final next = uploads.removeAt(0);
          if (next is Error) throw next;
          return next as String;
        },
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('申请新增平台项目'));
    await tester.pumpAndSettle();

    const orderedKeys = [
      'platform-name',
      'platform-reference-price',
      'platform-currency',
      'platform-slogan',
      'platform-sales-count',
      'platform-cover-upload',
      'platform-gallery-upload',
      'platform-category',
      'platform-description',
      'platform-detail-content',
      'platform-tags',
      'platform-category-tags',
      'platform-notes',
    ];
    final tops = <double>[];
    for (final key in orderedKeys) {
      final finder = find.byKey(Key(key));
      expect(finder, findsOneWidget, reason: 'missing $key');
      tops.add(tester.getTopLeft(finder).dy);
    }
    expect(tops, orderedEquals([...tops]..sort()));
    expect(
      tester
          .widget<TextField>(
            find.byKey(const Key('platform-detail-content')),
          )
          .maxLines,
      greaterThan(1),
    );
    expect(find.textContaining('评分'), findsNothing);
    expect(find.textContaining('评价数'), findsNothing);
    expect(find.textContaining('选择医生'), findsNothing);
    expect(find.byType(EditableText).evaluate().length, 10);

    await tester.tap(find.byKey(const Key('platform-cover-upload')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('platform-gallery-upload')));
    await tester.pump();
    expect(find.text('https://cdn.example.com/platform-cover.jpg'),
        findsOneWidget);
    expect(find.text('https://cdn.example.com/platform-gallery.jpg'),
        findsOneWidget);

    await tester.tap(find.byKey(const Key('platform-gallery-upload')));
    await tester.pump();
    expect(find.text('https://cdn.example.com/platform-cover.jpg'),
        findsOneWidget);
    expect(find.text('https://cdn.example.com/platform-gallery.jpg'),
        findsOneWidget);
    expect(find.text('图片上传失败，请重试'), findsOneWidget);
  });

  testWidgets(
      'institution application loads config, exposes all inheritance hints, applicant-only binding, and live derived rates',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository();
    final uploads = [
      'https://cdn.example.com/institution-cover.jpg',
      'https://cdn.example.com/institution-gallery.jpg',
    ];
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    )));
    await tester.pumpAndSettle();

    expect(find.textContaining('doctor-1'), findsOneWidget);
    expect(find.textContaining('唯一申请医生'), findsOneWidget);
    expect(find.textContaining('选择医生'), findsNothing);
    expect(find.textContaining('多医生'), findsNothing);

    await _choose(tester, const Key('institution-project'), 'Hydrating Facial');
    for (final inherited in const [
      'Hydrating Facial',
      'Skin',
      'Inherited description',
      'hydration,gentle',
      'facial,skin',
      'https://cdn.example.com/inherited-cover.jpg',
      '899.25',
      'USD',
      'Glow naturally',
      'Inherited plain detail',
      'https://cdn.example.com/inherited-one.jpg',
      'https://cdn.example.com/inherited-two.jpg',
      '18',
    ]) {
      expect(find.textContaining(inherited), findsWidgets,
          reason: 'missing inherited value $inherited');
    }
    for (final key in const [
      'institution-name',
      'institution-category',
      'institution-description',
      'institution-tags',
      'institution-slogan',
      'institution-detail-content',
    ]) {
      expect(_text(tester, key), isEmpty,
          reason: '$key must remain override-only');
    }
    expect(_dropdownValue(tester, 'institution-currency'), 'USD');
    expect(
      tester
          .widget<TextField>(
            find.byKey(const Key('institution-detail-content')),
          )
          .maxLines,
      greaterThan(1),
    );
    expect(find.text('平台比例（只读）：10.25%'), findsOneWidget);

    await tester.enterText(
        find.byKey(const Key('institution-consultant-rate')), '9.5');
    await tester.enterText(find.byKey(const Key('institution-rate')), '40.25');
    await tester.pump();
    expect(find.text('医生比例（自动推导）：40%'), findsOneWidget);

    await tester.tap(find.byKey(const Key('institution-cover-upload')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('institution-gallery-upload')));
    await tester.pump();
    expect(find.text('https://cdn.example.com/institution-cover.jpg'),
        findsOneWidget);
    expect(find.text('https://cdn.example.com/institution-gallery.jpg'),
        findsOneWidget);
  });

  testWidgets(
      'platform draft blocks malformed values, survives submit and refresh failures, and clears only after both succeed',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository();
    final uploads = [
      'https://cdn.example.com/retained-cover.jpg',
      'https://cdn.example.com/retained-gallery.jpg',
    ];
    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      key: const ValueKey('applicant-platform'),
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    )));
    await tester.pumpAndSettle();
    await _fillPlatformDraft(tester, referencePrice: '1.234');
    await tester.tap(find.byKey(const Key('platform-cover-upload')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('platform-gallery-upload')));
    await tester.pump();

    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, isEmpty);
    expect(_text(tester, 'platform-name'), 'Retained platform name');

    await tester.enterText(
        find.byKey(const Key('platform-reference-price')), '199.99');
    repository.platformSubmitError = true;
    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, hasLength(1));
    expect(_text(tester, 'platform-notes'), 'Retained notes');
    expect(find.text('https://cdn.example.com/retained-cover.jpg'),
        findsOneWidget);

    repository.platformSubmitError = false;
    repository.failNextRequestList = true;
    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, hasLength(2));
    expect(_text(tester, 'platform-name'), 'Retained platform name');
    expect(find.text('https://cdn.example.com/retained-gallery.jpg'),
        findsOneWidget);

    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, hasLength(3));
    expect(_text(tester, 'platform-name'), isEmpty);
    expect(_text(tester, 'platform-notes'), isEmpty);
    expect(
        find.text('https://cdn.example.com/retained-cover.jpg'), findsNothing);
    expect(find.text('https://cdn.example.com/retained-gallery.jpg'),
        findsNothing);
  });

  testWidgets(
      'institution draft blocks precision and rate sum, retains every override on failures, and never submits platform or doctor rate',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository();
    final uploads = [
      'https://cdn.example.com/clinic-cover.jpg',
      'https://cdn.example.com/clinic-gallery.jpg',
    ];
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('target-legal-institution'),
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    )));
    await tester.pumpAndSettle();
    await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
    await _choose(tester, const Key('institution-project'), 'Hydrating Facial');
    await _fillInstitutionDraft(tester,
        price: '799.999', institutionRate: '60');
    await tester.tap(find.byKey(const Key('institution-cover-upload')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('institution-gallery-upload')));
    await tester.pump();

    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, isEmpty);
    await tester.enterText(
        find.byKey(const Key('institution-price')), '799.99');
    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, isEmpty,
        reason: '10.25 + 60 + 30 exceeds 100');

    await tester.enterText(find.byKey(const Key('institution-rate')), '40');
    repository.institutionSubmitError = true;
    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(1));
    expect(_text(tester, 'institution-name'), 'Clinic override');
    expect(find.text('https://cdn.example.com/clinic-gallery.jpg'),
        findsOneWidget);

    repository.institutionSubmitError = false;
    repository.failNextRequestList = true;
    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(2));
    expect(
        _text(tester, 'institution-detail-content'), 'Override plain detail');
    expect(_dropdownValue(tester, 'institution-currency'), 'USD');

    final body = repository.institutionSubmissions.last.toJson();
    expect(body, isNot(contains('platformRate')));
    expect(body, isNot(contains('doctorRate')));
    expect(body, isNot(contains('doctorId')));

    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(3));
    expect(_text(tester, 'institution-name'), isEmpty);
    expect(_text(tester, 'institution-price'), isEmpty);
    expect(find.text('https://cdn.example.com/clinic-cover.jpg'), findsNothing);
  });

  testWidgets(
      'immutable history is role scoped and creation reviews dispatch to the exact authority endpoint',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository()
      ..requests = [
        _request(id: 'platform-own', type: 'PLATFORM', doctorId: 'doctor-1'),
        _request(id: 'platform-other', type: 'PLATFORM', doctorId: 'doctor-2'),
        _request(
          id: 'institution-target',
          type: 'INSTITUTION',
          doctorId: 'doctor-1',
          institutionId: 'inst-1',
        ),
        _request(
          id: 'institution-other',
          type: 'INSTITUTION',
          doctorId: 'doctor-2',
          institutionId: 'inst-2',
        ),
      ];

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      repository: repository,
      context: _doctorContext,
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsOneWidget);
    expect(find.byKey(const Key('professional-request-platform-other')),
        findsNothing);
    expect(find.byKey(const Key('review-creation-platform-own')), findsNothing);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('applicant-institution'),
      repository: repository,
      context: _doctorContext,
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsOneWidget);
    expect(find.byKey(const Key('professional-request-institution-other')),
        findsNothing);
    expect(find.byKey(const Key('review-creation-institution-target')),
        findsNothing);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('non-target-legal-institution'),
      repository: repository,
      context: _legalContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsOneWidget);
    expect(find.byKey(const Key('professional-request-institution-other')),
        findsNothing);
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsNothing);
    expect(find.textContaining('Complete immutable detail'), findsOneWidget);
    expect(find.text('面诊费：80.25'), findsOneWidget);
    expect(find.text('顾问比例：12.5%'), findsOneWidget);
    expect(find.text('机构比例：42.25%'), findsOneWidget);
    expect(find.text('平台比例：10%'), findsOneWidget);
    expect(find.text('医生比例：35.25%'), findsOneWidget);
    expect(find.byType(TextField), findsNothing,
        reason: 'the submitted snapshot must be immutable');

    await tester
        .tap(find.byKey(const Key('review-creation-institution-target')));
    await tester.pumpAndSettle();
    await _selectReviewDecision(tester, 'REJECTED');
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pump();
    expect(find.byType(AlertDialog), findsOneWidget,
        reason: 'blank rejection note must not close the dialog');
    await tester.enterText(
        find.byKey(const Key('creation-review-note')), 'Not ready');
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.institutionReviews.single,
        (id: 'institution-target', decision: 'REJECTED', note: 'Not ready'));

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _nonTargetLegalContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsNothing);
    expect(find.byKey(const Key('review-creation-institution-target')),
        findsNothing);

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      key: const ValueKey('admin-platform'),
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsOneWidget);
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsNothing);
    await tester.tap(find.byKey(const Key('review-creation-platform-own')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.platformReviews.single,
        (id: 'platform-own', decision: 'APPROVED', note: ''));

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('admin-institution'),
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    await tester
        .tap(find.byKey(const Key('review-creation-institution-other')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.institutionReviews.last,
        (id: 'institution-other', decision: 'APPROVED', note: ''));
  });
}

void _useLargeSurface(WidgetTester tester) {
  tester.view.physicalSize = const Size(900, 3200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Widget _app(Widget home) => MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: home,
    );

String _text(WidgetTester tester, String key) =>
    tester.widget<TextField>(find.byKey(Key(key))).controller!.text;

String? _dropdownValue(WidgetTester tester, String key) =>
    tester.widget<DropdownButtonFormField<String>>(_dropdown(key)).initialValue;

Future<void> _choose(WidgetTester tester, Key key, String label) async {
  await tester.tap(_dropdown((key as ValueKey<String>).value));
  await tester.pumpAndSettle();
  await tester.tap(find.text(label).last);
  await tester.pumpAndSettle();
}

Finder _dropdown(String key) => find.byWidgetPredicate(
      (widget) =>
          widget is DropdownButtonFormField<String> &&
          (widget.key?.toString().contains("'$key-") ?? false),
    );

Future<void> _submit(WidgetTester tester, Key key) async {
  await tester.ensureVisible(find.byKey(key));
  await tester.tap(find.byKey(key));
  await tester.pumpAndSettle();
}

Future<void> _fillPlatformDraft(WidgetTester tester,
    {required String referencePrice}) async {
  final values = {
    'platform-name': 'Retained platform name',
    'platform-reference-price': referencePrice,
    'platform-slogan': 'Retained slogan',
    'platform-sales-count': '8',
    'platform-category': 'Skin',
    'platform-description': 'Retained description',
    'platform-detail-content': 'Retained plain detail',
    'platform-tags': 'hydration, gentle',
    'platform-category-tags': 'facial, skin',
    'platform-notes': 'Retained notes',
  };
  for (final entry in values.entries) {
    await tester.enterText(find.byKey(Key(entry.key)), entry.value);
  }
}

Future<void> _fillInstitutionDraft(
  WidgetTester tester, {
  required String price,
  required String institutionRate,
}) async {
  final values = {
    'institution-name': 'Clinic override',
    'institution-category': 'Clinic category',
    'institution-description': 'Clinic description',
    'institution-tags': 'clinic,signature',
    'institution-slogan': 'Clinic glow',
    'institution-detail-content': 'Override plain detail',
    'institution-price': price,
    'institution-original-price': '999.99',
    'institution-sales-count': '7',
    'institution-consultation-fee': '80.25',
    'institution-consultant-rate': '30',
    'institution-rate': institutionRate,
    'institution-notes': 'Clinic notes',
  };
  for (final entry in values.entries) {
    await tester.enterText(find.byKey(Key(entry.key)), entry.value);
  }
}

Future<void> _selectReviewDecision(WidgetTester tester, String decision) async {
  await tester.tap(find.byKey(const Key('creation-review-decision')));
  await tester.pumpAndSettle();
  await tester.tap(find.text(decision == 'REJECTED' ? '驳回' : '通过').last);
  await tester.pumpAndSettle();
}

const _doctorContext = ManagementContext(
  userId: 'doctor-user-1',
  platformRole: 'USER',
  activeRoles: ['DOCTOR'],
  doctorId: 'doctor-1',
  managedInstitutionIds: [],
  visibleInstitutionIds: ['inst-1'],
  doctorInstitutionIds: ['inst-1'],
  canManageDoctors: true,
  canSubmitPlatformProjectRequests: true,
  canSubmitInstitutionProjectRequests: true,
);

const _legalContext = ManagementContext(
  userId: 'legal-user-1',
  platformRole: 'USER',
  activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
  managedInstitutionIds: ['inst-1'],
  visibleInstitutionIds: ['inst-1'],
  canReviewInstitutionProjectRequests: true,
);

const _nonTargetLegalContext = ManagementContext(
  userId: 'legal-user-2',
  platformRole: 'USER',
  activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
  managedInstitutionIds: ['inst-3'],
  visibleInstitutionIds: ['inst-3'],
  canReviewInstitutionProjectRequests: true,
);

const _adminContext = ManagementContext(
  userId: 'admin-1',
  platformRole: 'ADMIN',
  activeRoles: [],
  managedInstitutionIds: [],
  visibleInstitutionIds: [],
  canReviewInstitutionProjectRequests: true,
);

ProfessionalProjectRequest _request({
  required String id,
  required String type,
  required String doctorId,
  String? institutionId,
}) =>
    ProfessionalProjectRequest.fromJson({
      'id': id,
      'requestType': type,
      'doctorId': doctorId,
      'doctorName': doctorId == 'doctor-1' ? 'Dr. Chen' : 'Dr. Other',
      'institutionId': type == 'INSTITUTION' ? institutionId : null,
      'institutionName': type == 'INSTITUTION'
          ? institutionId == 'inst-1'
              ? 'Joysong Clinic'
              : 'Other Clinic'
          : null,
      'projectId': type == 'INSTITUTION' ? 'project-1' : null,
      'projectName': type == 'INSTITUTION' ? 'Hydrating Facial' : null,
      'name': 'Clinic Hydrating Facial $id',
      'category': 'Skin',
      'description': 'Clinic description',
      'tags': ['hydration', 'signature'],
      'slogan': 'Clinic glow',
      'detailContent': 'Complete immutable detail',
      'currency': 'CNY',
      'coverImage': 'cover.jpg',
      'images': ['one.jpg', 'two.jpg'],
      'salesCount': 7,
      'referencePrice': type == 'PLATFORM' ? 899.25 : null,
      'categoryTags': type == 'PLATFORM' ? ['facial'] : null,
      'price': type == 'INSTITUTION' ? 799.5 : null,
      'originalPrice': type == 'INSTITUTION' ? 999.99 : null,
      'isActive': type == 'INSTITUTION' ? true : null,
      'institutionSplit': type == 'INSTITUTION'
          ? {
              'consultationFee': 80.25,
              'commissionRate': 12.5,
              'institutionRate': 42.25,
              'platformRate': 10,
              'doctorRate': 35.25,
            }
          : null,
      'notes': 'Clinic note',
      'status': 'PENDING',
      'reviewNote': null,
      'reviewedBy': null,
      'reviewedAt': null,
      'resultingProjectId': null,
      'resultingInstitutionProjectId': null,
      'submittedAt': '2026-08-16T08:00:00',
      'updatedAt': '2026-08-16T08:05:00',
    });

final class _ProjectRequestRepository implements IdentityRepository {
  ManagementContext managementContext = _doctorContext;
  List<ProfessionalProjectRequest> requests = const [];
  final platformSubmissions = <PlatformProjectRequestDraft>[];
  final institutionSubmissions = <InstitutionProjectRequestDraft>[];
  final platformReviews = <({String id, String decision, String note})>[];
  final institutionReviews = <({String id, String decision, String note})>[];
  bool platformSubmitError = false;
  bool institutionSubmitError = false;
  bool failNextRequestList = false;

  @override
  Future<ManagementContext> loadManagementContext() async => managementContext;

  @override
  Future<List<ProfessionalProjectRequest>>
      listProfessionalProjectRequests() async {
    if (failNextRequestList) {
      failNextRequestList = false;
      throw StateError('refresh failed');
    }
    return requests;
  }

  @override
  Future<List<InstitutionOption>> listInstitutionOptions() async => const [
        InstitutionOption(id: 'inst-1', name: 'Joysong Clinic'),
      ];

  @override
  Future<List<ManagementProjectOption>> listManagementProjects() async =>
      const [
        ManagementProjectOption(
          id: 'project-1',
          name: 'Hydrating Facial',
          category: 'Skin',
          description: 'Inherited description',
          tags: 'hydration,gentle',
          categoryTags: 'facial,skin',
          coverImage: 'https://cdn.example.com/inherited-cover.jpg',
          referencePrice: 899.25,
          currency: 'USD',
          slogan: 'Glow naturally',
          detailContent: 'Inherited plain detail',
          images: [
            'https://cdn.example.com/inherited-one.jpg',
            'https://cdn.example.com/inherited-two.jpg',
          ],
          salesCount: 18,
        ),
      ];

  @override
  Future<InstitutionProjectApplicationFormConfig>
      loadInstitutionProjectApplicationFormConfig() async =>
          const InstitutionProjectApplicationFormConfig(platformRate: 10.25);

  @override
  Future<void> submitPlatformProjectRequest(
      PlatformProjectRequestDraft draft) async {
    platformSubmissions.add(draft);
    if (platformSubmitError) throw StateError('submit failed');
  }

  @override
  Future<void> submitInstitutionProjectRequest(
      InstitutionProjectRequestDraft draft) async {
    institutionSubmissions.add(draft);
    if (institutionSubmitError) throw StateError('submit failed');
  }

  @override
  Future<void> reviewInstitutionProjectRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {
    institutionReviews.add((id: id, decision: decision, note: reviewNote));
  }

  @override
  Future<void> reviewPlatformProjectRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {
    platformReviews.add((id: id, decision: decision, note: reviewNote));
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _DiscoverRepository implements DiscoverRepository {
  const _DiscoverRepository();

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() async =>
      const DiscoverFilterOptions();

  @override
  Future<DiscoverPageResult> loadPage({
    required DiscoverContentType type,
    required int offset,
    required int limit,
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
  }) async =>
      const DiscoverPageResult(items: [], hasMore: false);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
