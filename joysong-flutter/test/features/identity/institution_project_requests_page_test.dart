import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

void main() {
  testWidgets(
    'review notification focuses the visible requested application',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 3000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      const requestId = 'review-request';
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          home: InstitutionProjectRequestsPage(
            repository: _ProjectRequestsRepository(
              requests: [_request(id: requestId, doctorId: 'doctor-2')],
            ),
            context: _legalRepresentativeContext,
            reviewMode: true,
            initialRequestId: requestId,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Review details'), findsOneWidget);
      expect(find.textContaining('已通过'), findsOneWidget);
      expect(
        find.textContaining('reviewed request'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    "application notification shows the doctor's requested review result",
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 3000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      const requestId = 'application-request';
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          home: InstitutionProjectRequestsPage(
            repository: _ProjectRequestsRepository(
              requests: [_request(id: requestId, doctorId: 'doctor-1')],
            ),
            context: _doctorContext,
            initialRequestId: requestId,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Review details'), findsOneWidget);
      expect(find.textContaining('已通过'), findsOneWidget);
      expect(
        find.textContaining('reviewed request'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'stale application notification keeps the new-request form available',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 3000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          home: InstitutionProjectRequestsPage(
            repository: _ProjectRequestsRepository(
              requests: [_request(id: 'another-request', doctorId: 'doctor-1')],
            ),
            context: _doctorContext,
            initialRequestId: 'missing-request',
          ),
        ),
      );
      await tester.pumpAndSettle();

      final submit = find.byKey(const Key('institution-submit'));
      expect(submit, findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}

const _legalRepresentativeContext = ManagementContext(
  userId: 'legal-user',
  platformRole: 'USER',
  activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
  managedInstitutionIds: ['institution-1'],
  visibleInstitutionIds: ['institution-1'],
  canReviewInstitutionProjectRequests: true,
);

const _doctorContext = ManagementContext(
  userId: 'doctor-user',
  platformRole: 'USER',
  activeRoles: ['DOCTOR'],
  doctorId: 'doctor-1',
  doctorInstitutionIds: ['institution-1'],
  managedInstitutionIds: [],
  visibleInstitutionIds: ['institution-1'],
  canSubmitInstitutionProjectRequests: true,
);

ProfessionalProjectRequest _request({
  required String id,
  required String doctorId,
}) =>
    ProfessionalProjectRequest(
      id: id,
      requestType: 'INSTITUTION',
      doctorId: doctorId,
      doctorName: 'Dr. Request',
      institutionId: 'institution-1',
      institutionName: 'Institution One',
      projectId: 'project-1',
      projectName: 'Platform Project',
      name: 'Requested Project',
      category: 'Skin',
      description: 'Request description',
      tags: const ['skin'],
      slogan: 'Fresh skin',
      detailContent: 'Detailed description',
      currency: 'USD',
      coverImage: '',
      images: const [],
      salesCount: 1,
      price: 100,
      originalPrice: 120,
      isActive: true,
      institutionSplit: const InstitutionProjectSplit(
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 20,
        platformRate: 10,
        doctorRate: 70,
      ),
      notes: 'Application note',
      status: 'APPROVED',
      reviewNote: 'reviewed request',
      reviewedBy: 'legal-user',
      reviewedAt: DateTime(2026, 8, 30),
      submittedAt: DateTime(2026, 8, 29),
      updatedAt: DateTime(2026, 8, 30),
    );

final class _ProjectRequestsRepository implements IdentityRepository {
  const _ProjectRequestsRepository({required this.requests});

  final List<ProfessionalProjectRequest> requests;

  @override
  Future<List<ProfessionalProjectRequest>>
      listProfessionalProjectRequests() async => requests;

  @override
  Future<List<InstitutionOption>> listInstitutionOptions() async => const [
        InstitutionOption(id: 'institution-1', name: 'Institution One'),
      ];

  @override
  Future<List<ManagementProjectOption>> listManagementProjects() async =>
      const [
        ManagementProjectOption(
          id: 'project-1',
          name: 'Platform Project',
          category: 'Skin',
          description: 'Project description',
          tags: 'skin',
          slogan: 'Fresh skin',
          coverImage: '',
          images: [],
          salesCount: 1,
        ),
      ];

  @override
  Future<InstitutionProjectApplicationFormConfig>
      loadInstitutionProjectApplicationFormConfig() async =>
          const InstitutionProjectApplicationFormConfig(platformRate: 10);

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnsupportedError(invocation.memberName.toString());
}
