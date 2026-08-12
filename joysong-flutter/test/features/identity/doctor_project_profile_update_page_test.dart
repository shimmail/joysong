import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

void main() {
  testWidgets(
      'doctor form prefills server target and submits full profile update',
      (tester) async {
    _largeView(tester);
    final repository = _FakeRepository();
    await tester.pumpWidget(MaterialApp(
        home: DoctorProjectProfileUpdatePage(
      repository: repository,
      pickAndUploadImage: () async => 'uploaded.jpg',
    )));
    await tester.pumpAndSettle();

    expect(find.text('12000'), findsOneWidget);
    expect(find.textContaining('Platform rate (read only)'), findsOneWidget);
    await tester.tap(find.byKey(const Key('profile-update-upload')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('submit-profile-update')));
    await tester.pumpAndSettle();

    expect(repository.submitted?.institutionProjectId, 'ip-1');
    expect(repository.submitted?.images, ['one.jpg', 'uploaded.jpg']);
    expect(find.textContaining('PENDING'), findsOneWidget);
  });

  testWidgets('legal representative sees proposed rates but no force action',
      (tester) async {
    _largeView(tester);
    await tester.pumpWidget(MaterialApp(
        home: DoctorProjectProfileReviewPage(
      repository: _FakeRepository(),
      context: _legalContext,
    )));
    await tester.pumpAndSettle();

    expect(find.text('Consultant rate: 5%'), findsOneWidget);
    expect(find.text('Consultant rate: 10%'), findsOneWidget);
    expect(find.text('Force approve'), findsNothing);
    expect(find.text('Approve'), findsOneWidget);
  });

  testWidgets('admin force approval requires explicit confirmation and note',
      (tester) async {
    _largeView(tester);
    final repository = _FakeRepository();
    await tester.pumpWidget(MaterialApp(
        home: DoctorProjectProfileReviewPage(
      repository: repository,
      context: _adminContext,
    )));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Force approve'));
    await tester.pumpAndSettle();
    expect(find.text('Confirm force approval'), findsOneWidget);
    await tester.enterText(
        find.byKey(const Key('profile-review-note')), 'Manual handling');
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(repository.reviewForce, isTrue);
    expect(repository.reviewNote, 'Manual handling');
  });
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

final class _FakeRepository implements IdentityRepository {
  DoctorProjectProfileUpdateDraft? submitted;
  bool? reviewForce;
  String? reviewNote;

  @override
  Future<List<DoctorProjectProfileUpdateTarget>>
      listDoctorProjectProfileUpdateTargets() async => const [_target];

  @override
  Future<List<DoctorProjectChangeRequest>>
      listDoctorProjectChangeRequests() async => const [_request];

  @override
  Future<DoctorProjectChangeRequest> submitDoctorProjectProfileUpdate(
      DoctorProjectProfileUpdateDraft draft) async {
    submitted = draft;
    return _request;
  }

  @override
  Future<void> reviewDoctorProjectChangeRequest(
      {required String id,
      required String decision,
      required String reviewNote,
      required bool force}) async {
    reviewForce = force;
    this.reviewNote = reviewNote;
  }

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
  platformRate: 10,
  doctorRate: 40,
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
  platformRate: 10,
  doctorRate: 40,
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
  currentPlatformRate: 10,
  currentDoctorRate: 45,
  status: 'PENDING',
  reviewNote: '',
);
