import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/institution_project_preview_body.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

void main() {
  testWidgets('groups doctor projects by institution and collapses a group',
      (tester) async {
    _largeView(tester);
    final repository = _FakeRepository(
      targets: [_target, _targetB],
      requests: const [],
      doctorProfile: _doctorProfileWithThreeInstitutions,
    );
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileUpdatePage(repository: repository),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(ExpansionTile), findsNWidgets(3));
    expect(find.text('娇颜颂'), findsOneWidget);
    expect(find.text('悦颜'), findsOneWidget);
    expect(find.text('无项目机构'), findsOneWidget);
    expect(find.text('项目一'), findsOneWidget);
    expect(find.text('项目二'), findsOneWidget);
    expect(find.text('No joined projects'), findsOneWidget);

    await tester.tap(find.text('娇颜颂'));
    await tester.pumpAndSettle();
    expect(find.text('项目一'), findsNothing);
    expect(find.text('项目二'), findsOneWidget);
  });

  testWidgets('pending profile update disables edit and leave actions',
      (tester) async {
    _largeView(tester);
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileUpdatePage(
          repository: _FakeRepository(
            requests: [_request],
            doctorProfile: _doctorProfileWithThreeInstitutions,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('doctor-project-menu-ip-1')));
    await tester.pumpAndSettle();
    final edit = tester.widget<PopupMenuItem<String>>(
      find.byKey(const Key('doctor-project-edit-ip-1')),
    );
    final leave = tester.widget<PopupMenuItem<String>>(
      find.byKey(const Key('doctor-project-leave-ip-1')),
    );
    expect(edit.enabled, isFalse);
    expect(leave.enabled, isFalse);

    await tester.tap(
      find.byKey(const Key('doctor-project-edit-ip-1')),
      warnIfMissed: false,
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('profile-update-price')), findsNothing);
    expect(find.text('Confirm leave'), findsNothing);
  });

  testWidgets('another doctor pending request does not lock this doctor',
      (tester) async {
    _largeView(tester);
    const otherDoctorRequest = DoctorProjectChangeRequest(
      id: 'other-doctor-request',
      doctorId: 'doctor-2',
      doctorName: '其他医生',
      institutionId: 'institution-1',
      institutionName: '娇颜颂',
      institutionProjectId: 'ip-1',
      projectName: '项目一',
      requestType: 'LEAVE',
      serviceDescription: '',
      priceSuggestion: null,
      notes: '',
      serviceTags: [],
      scheduleNote: '',
      coverImage: '',
      images: [],
      consultationFee: null,
      commissionRate: null,
      institutionRate: null,
      platformRate: null,
      doctorRate: null,
      forceProcessed: false,
      status: 'PENDING',
      reviewNote: '',
    );
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileUpdatePage(
          repository: _FakeRepository(
            requests: [otherDoctorRequest],
            doctorProfile: _doctorProfileWithThreeInstitutions,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('doctor-project-menu-ip-1')));
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<PopupMenuItem<String>>(
            find.byKey(const Key('doctor-project-edit-ip-1')),
          )
          .enabled,
      isTrue,
    );
    expect(
      tester
          .widget<PopupMenuItem<String>>(
            find.byKey(const Key('doctor-project-leave-ip-1')),
          )
          .enabled,
      isTrue,
    );
  });

  testWidgets('in-flight leave cannot be submitted twice and ends pending',
      (tester) async {
    _largeView(tester);
    final leaveCompleter = Completer<DoctorProjectChangeRequest>();
    final repository = _FakeRepository(
      requests: const [],
      doctorProfile: _doctorProfileWithThreeInstitutions,
      leaveCompleter: leaveCompleter,
    );
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileUpdatePage(repository: repository),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('doctor-project-menu-ip-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('doctor-project-leave-ip-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirm'));
    await tester.pump();

    expect(repository.leaveIds, ['ip-1']);
    await tester.tap(
      find.byKey(const Key('doctor-project-menu-ip-1')),
      warnIfMissed: false,
    );
    await tester.pump();
    expect(find.byKey(const Key('doctor-project-leave-ip-1')), findsNothing);
    expect(repository.leaveIds, ['ip-1']);

    leaveCompleter.complete(_pendingLeaveRequest);
    await tester.pumpAndSettle();
    expect(find.text('PENDING'), findsOneWidget);
    expect(find.text('项目一'), findsOneWidget);
  });

  testWidgets('doctor editor does not render the project request history',
      (tester) async {
    _largeView(tester);
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileUpdatePage(
          repository: _FakeRepository(requests: [_request]),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('My project requests'), findsNothing);
    expect(find.text('我的项目申请'), findsNothing);
  });

  testWidgets(
      'canceling leave does not submit and confirming keeps a pending row',
      (tester) async {
    _largeView(tester);
    final repository = _FakeRepository(requests: const []);
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileUpdatePage(repository: repository),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('doctor-project-menu-ip-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('doctor-project-leave-ip-1')));
    await tester.pumpAndSettle();
    expect(find.text('Confirm leave'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(repository.leaveIds, isEmpty);

    await tester.tap(find.byKey(const Key('doctor-project-menu-ip-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('doctor-project-leave-ip-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();
    expect(repository.leaveIds, ['ip-1']);
    expect(find.text('PENDING'), findsOneWidget);
    expect(find.text('项目一'), findsOneWidget);
  });

  testWidgets('doctor form exposes one USD price and derives the travel fee', (
    tester,
  ) async {
    _largeView(tester);
    final repository = _FakeRepository(requests: const []);
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileUpdatePage(
          repository: repository,
          pickAndUploadImage: () async => 'uploaded.jpg',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('profile-update-price')), findsNothing);
    await _openDoctorProject(tester, 'ip-1');

    final priceField = tester.widget<TextField>(
      find.byKey(const Key('profile-update-price')),
    );

    expect(priceField.decoration?.labelText, 'Doctor project price (USD)');
    expect(
      tester.widgetList<TextField>(find.byType(TextField)).where(
            (field) =>
                field.decoration?.labelText?.toLowerCase().contains('price') ==
                true,
          ),
      hasLength(1),
    );
    expect(find.text('Consultation fee'), findsNothing);
    expect(find.text('Consultant rate (%)'), findsNothing);
    expect(find.text('Institution rate (%)'), findsNothing);
    expect(find.textContaining('Platform rate'), findsNothing);
    expect(find.textContaining('Doctor net rate'), findsNothing);

    await tester.enterText(
      find.byKey(const Key('profile-update-price')),
      '799.99',
    );
    await tester.pump();
    expect(
      find.byKey(const Key('profile-update-travel-ground-service-fee')),
      findsOneWidget,
    );
    expect(find.textContaining('USD 320.00'), findsOneWidget);
  });

  testWidgets('doctor submission emits exact v2 state from the loaded target', (
    tester,
  ) async {
    _largeView(tester);
    final repository = _FakeRepository(requests: const []);
    final uploads = <String>['new-cover.jpg', 'new-gallery.jpg'];
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileUpdatePage(
          repository: repository,
          pickAndUploadImage: () async => uploads.removeAt(0),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await _openDoctorProject(tester, 'ip-1');

    expect(
      tester.widgetList<TextField>(find.byType(TextField)).where((field) {
        final label = field.decoration?.labelText?.toLowerCase() ?? '';
        return label.contains('cover') || label.contains('project image');
      }),
      isEmpty,
    );

    await tester.enterText(
      find.byKey(const Key('profile-update-price')),
      '799.99',
    );
    await tester.tap(find.byKey(const Key('profile-update-cover-upload')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile-update-gallery-upload')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile-update-gallery-remove-0')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('submit-profile-update')));
    await tester.pumpAndSettle();

    expect(
      repository.submitted?.toJson(),
      {
        'requestType': 'PROFILE_UPDATE',
        'institutionProjectId': 'ip-1',
        'baseRevision': 'base-revision-1',
        'name': null,
        'category': 'raw-category',
        'description': '当前服务说明',
        'tags': ['自然'],
        'slogan': null,
        'detailContent': null,
        'price': 799.99,
        'salesCount': 17,
        'doctorActive': false,
        'coverImage': 'new-cover.jpg',
        'images': ['new-gallery.jpg'],
        'notes': '',
      },
    );
    expect(find.text('PENDING'), findsOneWidget);
  });

  testWidgets('doctor price rejects sub-cent fee and excess precision', (
    tester,
  ) async {
    _largeView(tester);
    final repository = _FakeRepository(requests: const []);
    await tester.pumpWidget(
      MaterialApp(home: DoctorProjectProfileUpdatePage(repository: repository)),
    );
    await tester.pumpAndSettle();

    await _openDoctorProject(tester, 'ip-1');

    for (final invalidPrice in ['0.01', '1.001']) {
      await tester.enterText(
        find.byKey(const Key('profile-update-price')),
        invalidPrice,
      );
      await tester.tap(find.byKey(const Key('submit-profile-update')));
      await tester.pumpAndSettle();
      expect(repository.submissionCount, 0);
    }

    await tester.enterText(
      find.byKey(const Key('profile-update-price')),
      '0.02',
    );
    await tester.tap(find.byKey(const Key('submit-profile-update')));
    await tester.pumpAndSettle();
    expect(repository.submissionCount, 1);
    expect(repository.submitted?.price, 0.02);
  });

  testWidgets(
    'legal review groups institutions and opens a full proposed comparison detail',
    (tester) async {
      _largeView(tester);
      final repository = _FakeRepository(
        requests: [
          _v2Request(),
          _v2Request(
            id: 'request-2',
            institutionProjectId: 'ip-2',
            projectName: '项目二',
            platformProjectName: '平台项目二',
            doctorName: '王医生',
            proposedDoctorPrice: 9800,
          ),
          _v2Request(
            id: 'request-3',
            institutionId: 'institution-2',
            institutionName: '悦颜',
            institutionProjectId: 'ip-3',
            projectName: '项目三',
          ),
        ],
      );
      await tester.pumpWidget(MaterialApp(
        home: DoctorProjectProfileReviewPage(
          repository: repository,
          context: _adminContext,
        ),
      ));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('institution-review-group-institution-1')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('institution-review-group-institution-2')),
        findsOneWidget,
      );
      expect(find.textContaining('平台项目一'), findsWidgets);
      expect(find.textContaining('李医生'), findsWidgets);
      expect(find.textContaining('USD 12800.00'), findsWidgets);
      expect(
        find.byKey(const Key('request-status-request-1')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('doctor-active-status-request-1')),
        findsOneWidget,
      );

      await _openReviewDetail(tester, 'request-1');
      expect(find.byType(InstitutionProjectPreviewBody), findsOneWidget);
      expect(find.text('申请后项目名称'), findsOneWidget);
      expect(find.text('Current values at submission'), findsOneWidget);
      expect(find.text('Proposed values'), findsOneWidget);
      expect(find.text('Latest values'), findsNothing);
      expect(
        find.text(
          'Shared project changes affect every doctor offering this institution project.',
        ),
        findsOneWidget,
      );
      expect(
        find.text(
          'Doctor price and availability affect only the applying doctor.',
        ),
        findsOneWidget,
      );
      expect(find.text('https://cdn.example.com/proposed-cover.jpg'), findsNothing);
      expect(find.byKey(const Key('approve-request-1')), findsOneWidget);
      expect(find.byKey(const Key('reject-request-1')), findsOneWidget);
      expect(find.byKey(const Key('changes-request-1')), findsOneWidget);
      expect(find.byKey(const Key('force-request-1')), findsNothing);
    },
  );

  testWidgets('detail review submission is synchronously de-duplicated', (
    tester,
  ) async {
    _largeView(tester);
    final gate = Completer<void>();
    final repository = _FakeRepository(
      requests: [_v2Request()],
      reviewCompleter: gate,
    );
    await tester.pumpWidget(MaterialApp(
      home: DoctorProjectProfileReviewPage(
        repository: repository,
        context: _legalContext,
      ),
    ));
    await tester.pumpAndSettle();
    await _openReviewDetail(tester, 'request-1');

    await tester.tap(find.byKey(const Key('approve-request-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirm'));
    await tester.pump();

    expect(repository.reviewDecisions, ['APPROVED']);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('approve-request-1')))
          .onPressed,
      isNull,
    );
    await tester.tap(
      find.byKey(const Key('approve-request-1')),
      warnIfMissed: false,
    );
    await tester.pump();
    expect(repository.reviewDecisions, ['APPROVED']);

    gate.complete();
    await tester.pumpAndSettle();
  });

  testWidgets('pending legacy leave remains reviewable with legacy schedule', (
    tester,
  ) async {
    _largeView(tester);
    final repository = _FakeRepository(requests: const [_pendingLeaveRequest]);
    await tester.pumpWidget(MaterialApp(
      home: DoctorProjectProfileReviewPage(
        repository: repository,
        context: _legalContext,
      ),
    ));
    await tester.pumpAndSettle();

    expect(
      find.byKey(const Key('institution-review-card-leave-request-1')),
      findsOneWidget,
    );
    await _openReviewDetail(tester, 'leave-request-1');
    expect(find.textContaining('Legacy schedule'), findsOneWidget);
    expect(find.byKey(const Key('approve-leave-request-1')), findsOneWidget);
    expect(find.byKey(const Key('reject-leave-request-1')), findsOneWidget);
    expect(find.byKey(const Key('changes-leave-request-1')), findsOneWidget);
  });

  testWidgets('review shows only current and proposed price and travel fee', (
    tester,
  ) async {
    _largeView(tester);
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileReviewPage(
          repository: _FakeRepository(),
          context: _legalContext,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await _openReviewDetail(tester, 'request-1');

    expect(find.text('Current values at submission'), findsOneWidget);
    expect(find.text('Proposed values'), findsOneWidget);
    expect(find.textContaining('USD 12000.00'), findsOneWidget);
    expect(find.textContaining('USD 4800.00'), findsOneWidget);
    expect(find.textContaining('USD 12800.00'), findsOneWidget);
    expect(find.textContaining('USD 5120.00'), findsOneWidget);
    expect(find.textContaining('Consultation fee'), findsNothing);
    expect(find.textContaining('Consultant rate'), findsNothing);
    expect(find.textContaining('Institution rate'), findsNothing);
    expect(find.textContaining('Platform rate'), findsNothing);
    expect(find.textContaining('Doctor net rate'), findsNothing);
    expect(find.text('Force approve'), findsNothing);
    expect(find.text('Approve'), findsOneWidget);
  });

  testWidgets(
    'admin supports ordinary decisions and confirmed force approval',
    (tester) async {
      _largeView(tester);
      final repository = _FakeRepository();
      await tester.pumpWidget(
        MaterialApp(
          home: DoctorProjectProfileReviewPage(
            repository: repository,
            context: _adminContext,
          ),
        ),
      );
      await tester.pumpAndSettle();

      await _openReviewDetail(tester, 'request-1');
      expect(find.text('Approve'), findsOneWidget);
      expect(find.text('Reject'), findsOneWidget);
      expect(find.text('Request changes'), findsOneWidget);
      expect(find.text('Force approve'), findsNothing);

      await tester.tap(find.text('Request changes'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('profile-review-note')),
        'Please revise',
      );
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();

      expect(repository.reviewDecision, 'CHANGES_REQUESTED');
      expect(repository.reviewForce, isFalse);

      repository.reviewError = const ApiException(
        message: 'server text must not select force',
        httpStatus: 409,
        errorCode: 'APPROVAL_BASE_STALE',
      );
      await _openReviewDetail(tester, 'request-1');
      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();

      await _openReviewDetail(tester, 'request-1');
      expect(find.text('Latest values'), findsOneWidget);
      await tester.tap(find.text('Force approve'));
      await tester.pumpAndSettle();
      expect(find.text('Confirm force approval'), findsOneWidget);
      await tester.enterText(
        find.byKey(const Key('profile-review-note')),
        'Manual handling',
      );
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();

      expect(repository.reviewForce, isTrue);
      expect(repository.reviewDecision, 'APPROVED');
      expect(repository.reviewNote, 'Manual handling');
      expect(repository.reviewForceBaseRevision, 'latest-revision-request-1');
      expect(repository.reviewForceBaseRevisions, [
        null,
        null,
        'latest-revision-request-1',
      ]);
    },
  );

  testWidgets('damaged snapshot keeps the request visible but disables review', (
    tester,
  ) async {
    _largeView(tester);
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileReviewPage(
          repository: _FakeRepository(requests: const [_damagedRequest]),
          context: _adminContext,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await _openReviewDetail(tester, 'damaged-request');

    final approve = tester.widget<FilledButton>(
      find.byKey(const Key('approve-damaged-request')),
    );
    expect(approve.onPressed, isNull);
    expect(find.byKey(const Key('force-damaged-request')), findsNothing);
  });

  testWidgets('only APPROVAL_BASE_STALE exposes force after refreshing detail', (
    tester,
  ) async {
    _largeView(tester);
    for (final code in const [
      'EDIT_BASE_STALE',
      'APPROVAL_BASE_STALE',
      'INHERITANCE_SOURCE_STALE',
      'PRICING_POLICY_STALE',
      'FORCE_BASE_STALE',
      'REQUEST_ALREADY_PENDING',
      'REQUEST_ALREADY_HANDLED',
      'CLIENT_UPGRADE_REQUIRED',
      'FORCE_NOT_APPLICABLE',
      'PROJECT_PAYLOAD_INVALID',
      'REQUEST_SNAPSHOT_INVALID',
      'INSTITUTION_PROJECT_VERSION_STALE',
    ]) {
      final repository = _FakeRepository(
        requests: [_v2Request()],
        reviewError: ApiException(
          message: 'server text must not select force',
          httpStatus: code == 'CLIENT_UPGRADE_REQUIRED' ? 426 : 409,
          errorCode: code,
        ),
      );
      await tester.pumpWidget(MaterialApp(
        key: ValueKey('error-matrix-$code'),
        home: DoctorProjectProfileReviewPage(
          repository: repository,
          context: _adminContext,
        ),
      ));
      await tester.pumpAndSettle();
      await _openReviewDetail(tester, 'request-1');
      await tester.tap(find.byKey(const Key('approve-request-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();

      await _openReviewDetail(tester, 'request-1');
      expect(
        find.byKey(const Key('force-request-1')),
        code == 'APPROVAL_BASE_STALE' ? findsOneWidget : findsNothing,
        reason: code,
      );
    }
  });

  testWidgets('403 refreshes management access and exits a revoked review', (
    tester,
  ) async {
    _largeView(tester);
    var refreshCount = 0;
    final repository = _FakeRepository(
      requests: [_v2Request()],
      reviewError: const ApiException(
        message: 'arbitrary forbidden response',
        httpStatus: 403,
      ),
    );
    await tester.pumpWidget(MaterialApp(
      home: Builder(builder: (context) {
        return Scaffold(
          body: FilledButton(
            key: const Key('open-review-route'),
            onPressed: () => Navigator.of(context).push<void>(
              MaterialPageRoute(
                builder: (_) => DoctorProjectProfileReviewPage(
                  repository: repository,
                  context: _adminContext,
                  onRefreshManagementContext: () async {
                    refreshCount++;
                    return const ManagementContext(
                      userId: 'former-admin',
                      platformRole: 'USER',
                      activeRoles: [],
                      managedInstitutionIds: [],
                      visibleInstitutionIds: [],
                      canReviewInstitutionProjectRequests: false,
                    );
                  },
                ),
              ),
            ),
            child: const Text('Open review'),
          ),
        );
      }),
    ));
    await tester.tap(find.byKey(const Key('open-review-route')));
    await tester.pumpAndSettle();
    await _openReviewDetail(tester, 'request-1');
    await tester.tap(find.byKey(const Key('approve-request-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(refreshCount, 1);
    expect(find.byType(DoctorProjectProfileReviewPage), findsNothing);
  });

  for (final errorCode in const [
    'EDIT_BASE_STALE',
    'REQUEST_ALREADY_PENDING',
  ]) {
    testWidgets('stable $errorCode submit conflict refreshes and retains text', (
      tester,
    ) async {
      _largeView(tester);
      final repository = _FakeRepository(
        requests: const [],
        submitError: ApiException(
          message: 'server detail must not drive the branch',
          httpStatus: 409,
          errorCode: errorCode,
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          home: DoctorProjectProfileUpdatePage(repository: repository),
        ),
      );
      await tester.pumpAndSettle();
      expect(repository.targetLoadCount, 1);
      expect(repository.requestLoadCount, 1);

      await _openDoctorProject(tester, 'ip-1');
      await tester.tap(find.byKey(const Key('submit-profile-update')));
      await tester.pumpAndSettle();

      expect(repository.targetLoadCount, 2);
      expect(repository.requestLoadCount, 2);
      expect(
        find.text(
          'The profile or pending request changed. Refresh and submit again.',
        ),
        findsOneWidget,
      );
    });
  }

  testWidgets(
    'platform admin enters profile reviews without ordinary professional entries',
    (tester) async {
      _largeView(tester);
      final repository = _FakeRepository(
        managementContext: _adminReviewContext,
      );
      await tester.pumpWidget(
        MaterialApp(
          home: ManagementCenterPage(
            repository: repository,
            discoverRepository: _UnusedDiscoverRepository(),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Doctor project profile reviews'), findsOneWidget);
      expect(find.text('Institution profile'), findsNothing);
      expect(find.text('Apply to institution'), findsNothing);
      await tester.tap(find.text('Doctor project profile reviews'));
      await tester.pumpAndSettle();
      expect(find.byType(DoctorProjectProfileReviewPage), findsOneWidget);
      expect(
        find.byKey(const Key('institution-review-card-request-1')),
        findsOneWidget,
      );
    },
  );
}

void _largeView(WidgetTester tester) {
  tester.view.physicalSize = const Size(900, 4000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Future<void> _openDoctorProject(WidgetTester tester, String projectId) async {
  await tester.tap(find.byKey(Key('doctor-project-menu-$projectId')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(Key('doctor-project-edit-$projectId')));
  await tester.pumpAndSettle();
}

Future<void> _openReviewDetail(WidgetTester tester, String requestId) async {
  await tester.tap(find.byKey(Key('institution-review-detail-$requestId')));
  await tester.pumpAndSettle();
}

const _legalContext = ManagementContext(
  userId: 'legal-1',
  platformRole: 'USER',
  activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
  managedInstitutionIds: ['institution-1'],
  visibleInstitutionIds: ['institution-1'],
);
const _adminContext = ManagementContext(
  userId: 'admin-1',
  platformRole: 'ADMIN',
  activeRoles: [],
  managedInstitutionIds: [],
  visibleInstitutionIds: [],
);
const _adminReviewContext = ManagementContext(
  userId: 'admin-1',
  platformRole: 'ADMIN',
  activeRoles: [],
  managedInstitutionIds: [],
  visibleInstitutionIds: [],
  canReviewInstitutionProjectRequests: true,
);

final class _FakeRepository implements IdentityRepository {
  _FakeRepository({
    this.managementContext,
    List<DoctorProjectProfileUpdateTarget>? targets,
    List<DoctorProjectChangeRequest>? requests,
    this.doctorProfile,
    this.leaveCompleter,
    this.submitError,
    this.reviewCompleter,
    this.reviewError,
  })  : requests = requests ?? [_request],
        targets = targets ?? [_target];
  final ManagementContext? managementContext;
  final List<DoctorProjectProfileUpdateTarget> targets;
  List<DoctorProjectChangeRequest> requests;
  final DoctorSelfProfile? doctorProfile;
  final Completer<DoctorProjectChangeRequest>? leaveCompleter;
  final ApiException? submitError;
  final Completer<void>? reviewCompleter;
  ApiException? reviewError;
  DoctorProjectProfileUpdateDraft? submitted;
  final leaveIds = <String>[];
  bool? reviewForce;
  String? reviewDecision;
  String? reviewNote;
  String? reviewForceBaseRevision;
  final reviewForceBaseRevisions = <String?>[];
  final reviewDecisions = <String>[];
  int targetLoadCount = 0;
  int requestLoadCount = 0;

  @override
  Future<ManagementContext> loadManagementContext() async =>
      managementContext ?? _legalContext;

  @override
  Future<List<DoctorProjectProfileUpdateTarget>>
      listDoctorProjectProfileUpdateTargets() async {
    targetLoadCount++;
    return targets;
  }

  @override
  Future<List<DoctorProjectChangeRequest>>
      listDoctorProjectChangeRequests() async {
    requestLoadCount++;
    return requests;
  }

  @override
  Future<DoctorSelfProfile> loadDoctorSelfProfile() async {
    final profile = doctorProfile;
    if (profile == null) throw StateError('doctor profile unavailable');
    return profile;
  }

  @override
  Future<DoctorProjectChangeRequest> submitDoctorProjectLeave({
    required String institutionProjectId,
  }) async {
    leaveIds.add(institutionProjectId);
    final pending = leaveCompleter;
    if (pending != null) {
      return pending.future.then((request) {
        requests = [...requests, request];
        return request;
      });
    }
    requests = [...requests, _pendingLeaveRequest];
    return _pendingLeaveRequest;
  }

  @override
  Future<DoctorProjectChangeRequest> submitDoctorProjectProfileUpdate(
    DoctorProjectProfileUpdateDraft draft,
  ) async {
    submitted = draft;
    submissionCount++;
    final error = submitError;
    if (error != null) throw error;
    requests = [
      ...requests.where((request) => request.id != _request.id),
      _request,
    ];
    return _request;
  }

  int submissionCount = 0;

  @override
  Future<void> reviewDoctorProjectChangeRequest({
    required String id,
    required String decision,
    required String reviewNote,
    required bool force,
    required String? forceBaseRevision,
  }) async {
    reviewDecisions.add(decision);
    reviewDecision = decision;
    reviewForce = force;
    this.reviewNote = reviewNote;
    reviewForceBaseRevision = forceBaseRevision;
    reviewForceBaseRevisions.add(forceBaseRevision);
    final error = reviewError;
    reviewError = null;
    if (error != null) throw error;
    await reviewCompleter?.future;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _UnusedDiscoverRepository implements DiscoverRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

const _doctorProfileWithThreeInstitutions = DoctorSelfProfile(
  id: 'doctor-1',
  userId: 'user-1',
  name: '李医生',
  institutions: [
    DoctorInstitutionSummary(id: 'institution-1', name: '娇颜颂'),
    DoctorInstitutionSummary(id: 'institution-2', name: '悦颜'),
    DoctorInstitutionSummary(id: 'institution-3', name: '无项目机构'),
  ],
  institutionCount: 3,
);

final _target = DoctorProjectProfileUpdateTarget.fromJson({
  'payloadVersion': 2,
  'institutionProjectId': 'ip-1',
  'institutionId': 'institution-1',
  'institutionName': '娇颜颂',
  'platformProjectId': 'platform-project-1',
  'platformProjectName': '平台项目一',
  'doctorId': 'doctor-1',
  'doctorName': '李医生',
  'baseRevision': 'base-revision-1',
  'currentProject': {
    'schemaVersion': 2,
    'association': {
      'institutionProjectId': 'ip-1',
      'institutionId': 'institution-1',
      'platformProjectId': 'platform-project-1',
    },
    'rawOverrides': {
      'name': null,
      'category': 'raw-category',
      'description': null,
      'tags': null,
      'slogan': null,
      'detailContent': null,
      'coverImage': null,
      'images': null,
    },
    'effective': {
      'name': '项目一',
      'category': '项目分类',
      'description': '当前服务说明',
      'tags': ['自然'],
      'slogan': '自然效果',
      'detailContent': '项目详情',
      'salesCount': 17,
      'coverImage': 'cover.jpg',
      'images': ['one.jpg'],
    },
    'source': {
      'institutionProjectVersion': 7,
      'platformInheritanceHash': 'inheritance-hash-1',
    },
  },
  'currentDoctorPrice': 12000,
  'currentDoctorActive': false,
  'platformRate': 40,
  'pricingPolicyRevision': 'pricing-policy-1',
  'travelGroundServiceFee': 4800,
});

const _targetB = DoctorProjectProfileUpdateTarget(
  institutionProjectId: 'ip-2',
  projectName: '项目二',
  institutionId: 'institution-2',
  institutionName: '悦颜',
  currentPrice: 8800,
  serviceDescription: '第二个项目说明',
  serviceTags: ['精细'],
  scheduleNote: '周三',
  coverImage: 'cover-2.jpg',
  images: ['two.jpg'],
  consultationFee: 200,
  commissionRate: 10,
  institutionRate: 40,
  platformRate: 40,
  doctorRate: 10,
);
final _request = _v2Request();

DoctorProjectChangeRequest _v2Request({
  String id = 'request-1',
  String doctorId = 'doctor-1',
  String doctorName = '李医生',
  String institutionId = 'institution-1',
  String institutionName = '娇颜颂',
  String institutionProjectId = 'ip-1',
  String projectName = '项目一',
  String platformProjectName = '平台项目一',
  num proposedDoctorPrice = 12800,
  bool proposedDoctorActive = true,
  String status = 'PENDING',
  bool reviewable = true,
}) =>
    DoctorProjectChangeRequest.fromJson({
      'payloadVersion': 2,
      'id': id,
      'requestType': 'PROFILE_UPDATE',
      'doctorId': doctorId,
      'doctorName': doctorName,
      'institutionId': institutionId,
      'institutionName': institutionName,
      'institutionProjectId': institutionProjectId,
      'institutionProjectName': projectName,
      'platformProjectId': 'platform-project-$institutionProjectId',
      'platformProjectName': platformProjectName,
      'baseRevision': 'base-revision-$id',
      'currentProject': _v2Snapshot(
        institutionId: institutionId,
        institutionProjectId: institutionProjectId,
        name: '当前项目名称',
        description: '当前说明',
        coverImage: 'https://cdn.example.com/current-cover.jpg',
        images: const ['https://cdn.example.com/current-gallery.jpg'],
        version: 7,
      ),
      'proposedProject': _v2Snapshot(
        institutionId: institutionId,
        institutionProjectId: institutionProjectId,
        name: '申请后项目名称',
        description: '申请后说明',
        coverImage: 'https://cdn.example.com/proposed-cover.jpg',
        images: const ['https://cdn.example.com/proposed-gallery.jpg'],
        version: 7,
      ),
      'latestProject': _v2Snapshot(
        institutionId: institutionId,
        institutionProjectId: institutionProjectId,
        name: '最新项目名称',
        description: '最新说明',
        coverImage: 'https://cdn.example.com/latest-cover.jpg',
        images: const ['https://cdn.example.com/latest-gallery.jpg'],
        version: 8,
      ),
      'latestRevision': 'latest-revision-$id',
      'sharedChanged': true,
      'currentDoctorPrice': 12000,
      'proposedDoctorPrice': proposedDoctorPrice,
      'latestDoctorPrice': 12500,
      'currentDoctorActive': false,
      'proposedDoctorActive': proposedDoctorActive,
      'latestDoctorActive': false,
      'platformRate': 40,
      'pricingPolicyRevision': 'travel-ground-service-rate:0.400000',
      'travelGroundServiceFee': proposedDoctorPrice * .4,
      'requestStatus': status,
      'notes': '请审核完整变更',
      'forceProcessed': false,
      'submittedBy': doctorId,
      'submittedAt': '2026-08-24T09:00:00',
      'reviewedBy': null,
      'reviewerName': null,
      'reviewNote': null,
      'reviewedAt': null,
      'updatedAt': '2026-08-24T09:00:00',
      'snapshotState': 'VALID',
      'snapshotError': null,
      'reviewable': reviewable,
    });

Map<String, Object?> _v2Snapshot({
  required String institutionId,
  required String institutionProjectId,
  required String name,
  required String description,
  required String coverImage,
  required List<String> images,
  required int version,
}) =>
    {
      'schemaVersion': 2,
      'association': {
        'institutionProjectId': institutionProjectId,
        'institutionId': institutionId,
        'platformProjectId': 'platform-project-$institutionProjectId',
      },
      'rawOverrides': {
        'name': name,
        'category': '皮肤管理',
        'description': description,
        'tags': ['自然', '精细'],
        'slogan': '自然焕新',
        'detailContent': '完整项目详情',
        'coverImage': coverImage,
        'images': images,
      },
      'effective': {
        'name': name,
        'category': '皮肤管理',
        'description': description,
        'tags': ['自然', '精细'],
        'slogan': '自然焕新',
        'detailContent': '完整项目详情',
        'salesCount': 17,
        'coverImage': coverImage,
        'images': images,
      },
      'source': {
        'institutionProjectVersion': version,
        'platformInheritanceHash': 'inheritance-hash-$version',
      },
    };

const _damagedRequest = DoctorProjectChangeRequest(
  id: 'damaged-request',
  doctorId: 'doctor-1',
  doctorName: '李医生',
  institutionId: 'institution-1',
  institutionName: '娇颜颂',
  institutionProjectId: 'ip-1',
  projectName: '项目一',
  requestType: 'EDIT',
  serviceDescription: '',
  priceSuggestion: 12800,
  notes: '',
  serviceTags: [],
  scheduleNote: '',
  coverImage: '',
  images: [],
  consultationFee: null,
  commissionRate: null,
  institutionRate: null,
  platformRate: 40,
  doctorRate: null,
  forceProcessed: false,
  currentPrice: 12000,
  currentPlatformRate: 40,
  status: 'PENDING',
  reviewNote: '',
  payloadVersion: 2,
  baseRevision: 'base-revision-1',
  latestRevision: 'latest-revision-1',
  hasCompleteSnapshot: false,
  snapshotError: 'REQUEST_SNAPSHOT_INVALID',
  reviewable: false,
);

const _pendingLeaveRequest = DoctorProjectChangeRequest(
  id: 'leave-request-1',
  doctorId: 'doctor-1',
  doctorName: '李医生',
  institutionId: 'institution-1',
  institutionName: '娇颜颂',
  institutionProjectId: 'ip-1',
  projectName: '项目一',
  requestType: 'LEAVE',
  serviceDescription: '',
  priceSuggestion: null,
  notes: '',
  serviceTags: [],
  scheduleNote: '周五',
  coverImage: '',
  images: [],
  consultationFee: null,
  commissionRate: null,
  institutionRate: null,
  platformRate: null,
  doctorRate: null,
  forceProcessed: false,
  currentPrice: null,
  currentServiceDescription: '',
  currentServiceTags: [],
  currentScheduleNote: '',
  currentCoverImage: '',
  currentImages: [],
  currentConsultationFee: null,
  currentCommissionRate: null,
  currentInstitutionRate: null,
  currentPlatformRate: null,
  currentDoctorRate: null,
  status: 'PENDING',
  reviewNote: '',
);
