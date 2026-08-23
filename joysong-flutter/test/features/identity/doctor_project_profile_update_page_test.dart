import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

void main() {
  testWidgets('doctor form exposes one USD price and derives the travel fee',
      (tester,) async {
    _largeView(tester);
    final repository = _FakeRepository();
    await tester.pumpWidget(MaterialApp(
        home: DoctorProjectProfileUpdatePage(
      repository: repository,
      pickAndUploadImage: () async => 'uploaded.jpg',
    ),),);
    await tester.pumpAndSettle();

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
      find.byKey(const Key('profile-update-travel-ground-service-fee')), findsOneWidget,);
    expect(find.textContaining('USD 320.00'), findsOneWidget);
  });

  testWidgets('doctor submission reuses hidden legacy values from the target', (
    tester,
  ) async {
    _largeView(tester);
    final repository = _FakeRepository();
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorProjectProfileUpdatePage(
          repository: repository,
          pickAndUploadImage: () async => 'uploaded.jpg',
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const Key('profile-update-price')),
      '799.99',
    );
    await tester.tap(find.byKey(const Key('profile-update-upload')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('submit-profile-update')));
    await tester.pumpAndSettle();

    expect(repository.submitted?.institutionProjectId, 'ip-1');
    expect(repository.submitted?.images, ['one.jpg', 'uploaded.jpg']);
    expect(repository.submitted?.consultationFee, _target.consultationFee);
    expect(repository.submitted?.commissionRate, _target.commissionRate);
    expect(repository.submitted?.institutionRate, _target.institutionRate);
    expect(
      repository.submitted?.toJson(),
      containsPair('priceSuggestion', 799.99),
    );
    expect(
      repository.submitted?.toJson(),
      containsPair('medicalListPrice', 799.99),
    );
    expect(find.text('PENDING'), findsOneWidget);
  });

  testWidgets('doctor price rejects sub-cent fee and excess precision',
      (tester,) async {
    _largeView(tester);
    final repository = _FakeRepository();
    await tester.pumpWidget(MaterialApp(
        home: DoctorProjectProfileUpdatePage(repository: repository)),
    );
    await tester.pumpAndSettle();

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
    expect(repository.submitted?.priceSuggestion, 0.02);
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
    ),),);
    await tester.pumpAndSettle();

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

  testWidgets('admin supports ordinary decisions and confirmed force approval',
      (tester) async {
    _largeView(tester);
    final repository = _FakeRepository();
    await tester.pumpWidget(MaterialApp(
        home: DoctorProjectProfileReviewPage(
      repository: repository,
      context: _adminContext,
    ),),);
    await tester.pumpAndSettle();

    expect(find.text('Approve'), findsOneWidget);
    expect(find.text('Reject'), findsOneWidget);
    expect(find.text('Request changes'), findsOneWidget);
    expect(find.text('Force approve'), findsOneWidget);

    await tester.tap(find.text('Request changes'));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const Key('profile-review-note')), 'Please revise',);
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(repository.reviewDecision, 'CHANGES_REQUESTED');
    expect(repository.reviewForce, isFalse);

    await tester.tap(find.text('Force approve'));
    await tester.pumpAndSettle();
    expect(find.text('Confirm force approval'), findsOneWidget);
    await tester.enterText(
        find.byKey(const Key('profile-review-note')), 'Manual handling',);
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(repository.reviewForce, isTrue);
    expect(repository.reviewDecision, 'APPROVED');
    expect(repository.reviewNote, 'Manual handling');
  },);

  testWidgets(
      'platform admin enters profile reviews without ordinary professional entries',
      (tester) async {
    _largeView(tester);
    final repository = _FakeRepository(managementContext: _adminReviewContext,);
    await tester.pumpWidget(MaterialApp(
      home: ManagementCenterPage(
        repository: repository,
        discoverRepository: _UnusedDiscoverRepository(),
      ),
    ),);
    await tester.pumpAndSettle();

    expect(find.text('Doctor project profile reviews'), findsOneWidget);
    expect(find.text('Institution profile'), findsNothing);
    expect(find.text('Apply to institution'), findsNothing);
    await tester.tap(find.text('Doctor project profile reviews'));
    await tester.pumpAndSettle();
    expect(find.byType(DoctorProjectProfileReviewPage), findsOneWidget);
    expect(find.text('Force approve'), findsOneWidget);
  },);
}

void _largeView(WidgetTester tester) {
  tester.view.physicalSize = const Size(900, 4000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
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
  _FakeRepository({this.managementContext});
  final ManagementContext? managementContext;
  DoctorProjectProfileUpdateDraft? submitted;
  bool? reviewForce;
  String? reviewDecision;
  String? reviewNote;

  @override
  Future<ManagementContext> loadManagementContext() async =>
      managementContext ?? _legalContext;

  @override
  Future<List<DoctorProjectProfileUpdateTarget>>
      listDoctorProjectProfileUpdateTargets() async => const [_target];

  @override
  Future<List<DoctorProjectChangeRequest>>
      listDoctorProjectChangeRequests() async => const [_request];

  @override
  Future<DoctorProjectChangeRequest> submitDoctorProjectProfileUpdate(
      DoctorProjectProfileUpdateDraft draft,) async {
    submitted = draft;
    submissionCount++;
    return _request;
  }

  int submissionCount = 0;

  @override
  Future<void> reviewDoctorProjectChangeRequest(
      {required String id,
      required String decision,
      required String reviewNote,
      required bool force,}) async {
    reviewDecision = decision;
    reviewForce = force;
    this.reviewNote = reviewNote;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _UnusedDiscoverRepository implements DiscoverRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

const _target = DoctorProjectProfileUpdateTarget(
  institutionProjectId: 'ip-1',
  projectName: '项目一',
  institutionId: 'institution-1',
  institutionName: '娇颜颂',
  currentPrice: 12000,
  serviceDescription: '当前服务说明',
  serviceTags: ['自然'],
  scheduleNote: '周二',
  coverImage: 'cover.jpg',
  images: ['one.jpg'],
  consultationFee: 200,
  commissionRate: 10,
  institutionRate: 40,
  platformRate: 40,
  doctorRate: 10,
);
const _request = DoctorProjectChangeRequest(
  id: 'request-1',
  doctorId: 'doctor-1',
  doctorName: '李医生',
  institutionId: 'institution-1',
  institutionName: '娇颜颂',
  institutionProjectId: 'ip-1',
  projectName: '项目一',
  requestType: 'PROFILE_UPDATE',
  serviceDescription: '申请服务说明',
  priceSuggestion: 12800,
  notes: '',
  serviceTags: ['精细化'],
  scheduleNote: '周四',
  coverImage: 'new-cover.jpg',
  images: ['new.jpg'],
  consultationFee: 300,
  commissionRate: 10,
  institutionRate: 40,
  platformRate: 40,
  doctorRate: 10,
  forceProcessed: false,
  currentPrice: 12000,
  currentServiceDescription: '当前服务说明',
  currentServiceTags: ['自然'],
  currentScheduleNote: '周二',
  currentCoverImage: 'cover.jpg',
  currentImages: ['one.jpg'],
  currentConsultationFee: 200,
  currentCommissionRate: 5,
  currentInstitutionRate: 40,
  currentPlatformRate: 40,
  currentDoctorRate: 15,
  status: 'PENDING',
  reviewNote: '',
);
