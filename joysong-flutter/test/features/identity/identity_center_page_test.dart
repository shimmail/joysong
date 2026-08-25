import 'dart:ui' show Tristate;

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/professional_catalog_page.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/institution_relationships_page.dart';

void main() {
  test('parses identity application workflow statuses', () {
    final approved = IdentityApplication.fromJson({
      'id': 'application-approved',
      'roleCode': 'DOCTOR',
      'status': 'APPROVED',
    });
    final withdrawn = IdentityApplication.fromJson({
      'id': 'application-withdrawn',
      'roleCode': 'CONSULTANT',
      'status': 'WITHDRAWN',
    });

    expect(approved.status, IdentityStatus.approved);
    expect(approved.status.label, '已通过');
    expect(withdrawn.status, IdentityStatus.withdrawn);
    expect(withdrawn.status.label, '已撤回');
  });

  testWidgets(
    'identity application history is collapsed and expands all records',
    (tester) async {
      tester.view.physicalSize = const Size(800, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final repository = _IdentityRepositoryStub(
        overview: const IdentityOverview(
          applications: [
            IdentityApplication(
              id: 'application-approved',
              role: IdentityRoleType.doctor,
              status: IdentityStatus.approved,
              reviewNote: '资质审核通过',
            ),
            IdentityApplication(
              id: 'application-rejected',
              role: IdentityRoleType.consultant,
              status: IdentityStatus.rejected,
              reviewNote: '请补充证明材料',
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        _localizedApp(home: IdentityCenterPage(repository: repository)),
      );
      await tester.pumpAndSettle();

      final history = find.byKey(const Key('identity-application-history'));
      expect(history, findsOneWidget);
      expect(find.text('资质审核通过'), findsNothing);
      expect(find.text('请补充证明材料'), findsNothing);

      await tester.tap(find.text('申请记录'));
      await tester.pumpAndSettle();

      expect(find.text('资质审核通过'), findsOneWidget);
      expect(find.text('请补充证明材料'), findsOneWidget);
      expect(find.text('已通过'), findsOneWidget);
      expect(find.text('未知状态'), findsNothing);

      await tester.tap(find.text('申请记录'));
      await tester.pumpAndSettle();
      expect(find.text('资质审核通过'), findsNothing);
      expect(find.text('请补充证明材料'), findsNothing);
    },
  );

  testWidgets(
    'identity notification target expands the matching application and shows its review note',
    (tester) async {
      final repository = _IdentityRepositoryStub(
        overview: const IdentityOverview(
          applications: [
            IdentityApplication(
              id: 'identity-request-1',
              role: IdentityRoleType.doctor,
              status: IdentityStatus.rejected,
              reviewNote: '请补充执业证明',
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        _localizedApp(
          home: IdentityCenterPage(
            repository: repository,
            initialApplicationId: 'identity-request-1',
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        tester
            .getSemantics(find.byKey(const Key('identity-application-history-toggle')))
            .flagsCollection
            .isExpanded,
        Tristate.isTrue,
      );
      expect(find.text('请补充执业证明'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('identity-application-identity-request-1')),
        findsOneWidget,
      );
    },
  );

  testWidgets('stale identity notification target keeps the identity overview usable',
      (tester) async {
    await tester.pumpWidget(
      _localizedApp(
        home: IdentityCenterPage(
          repository: const _IdentityRepositoryStub(),
          initialApplicationId: 'missing-request',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('身份认证'), findsOneWidget);
    expect(find.text('暂无身份申请记录'), findsOneWidget);
  });

  testWidgets('professional management only shows available identity groups',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = _IdentityRepositoryStub(
      managementContext: const ManagementContext(
        userId: 'doctor-1',
        platformRole: 'USER',
        activeRoles: ['DOCTOR'],
        doctorId: 'doctor-1',
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        canManageDoctors: true,
        canApplyToInstitutions: true,
        canSubmitPlatformProjectRequests: true,
      ),
    );

    await tester.pumpWidget(
      _localizedApp(
        home: ManagementCenterPage(
          repository: repository,
          discoverRepository: _DiscoverRepositoryStub(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('当前可用功能'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('management-group-doctor')),
      findsOneWidget,
    );
    expect(find.text('医生'), findsOneWidget);
    expect(find.text('医生档案'), findsOneWidget);
    expect(find.text('机构关系'), findsOneWidget);
    expect(find.text('法人'), findsNothing);
    expect(find.text('平台管理'), findsNothing);
    expect(find.text('顾问'), findsNothing);
  });

  testWidgets('consultant affiliation entry respects read-only access',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = _IdentityRepositoryStub(
      managementContext: const ManagementContext(
        userId: 'consultant-1',
        platformRole: 'USER',
        activeRoles: ['CONSULTANT'],
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        canViewAffiliations: true,
      ),
    );

    await tester.pumpWidget(
      _localizedApp(
        home: ManagementCenterPage(
          repository: repository,
          discoverRepository: _DiscoverRepositoryStub(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('机构关系'), findsOneWidget);
    expect(find.text('申请加入机构'), findsNothing);
    expect(find.text('机构归属'), findsNothing);

    await tester.tap(find.text('机构关系'));
    await tester.pumpAndSettle();

    final page = tester.widget<InstitutionRelationshipsPage>(
      find.byType(InstitutionRelationshipsPage),
    );
    expect(page.scope, InstitutionRelationshipScope.consultant);
    expect(
      find.byKey(const Key('relationship-institution-picker')),
      findsNothing,
    );
    expect(find.byKey(const Key('relationship-submit')), findsNothing);
  });

  testWidgets('catalog entry keeps the selected identity scope',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = _IdentityRepositoryStub(
      managementContext: const ManagementContext(
        userId: 'multi-role-1',
        platformRole: 'USER',
        activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE', 'DOCTOR'],
        doctorId: 'doctor-1',
        managedInstitutionIds: ['institution-1'],
        visibleInstitutionIds: ['institution-1'],
        canManageDoctors: true,
        canManageInstitutions: true,
      ),
    );
    final discoverRepository = _CatalogDiscoverRepositoryStub();

    await tester.pumpWidget(
      _localizedApp(
        home: ManagementCenterPage(
          repository: repository,
          discoverRepository: discoverRepository,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(
        const Key('management-professional-catalog-legal-representative'),
      ),
    );
    await tester.pumpAndSettle();
    final legalCatalog = tester.widget<ProfessionalCatalogPage>(
      find.byType(ProfessionalCatalogPage),
    );
    expect(
      legalCatalog.scope,
      ProfessionalCatalogScope.legalRepresentative,
    );

    Navigator.of(tester.element(find.byType(ProfessionalCatalogPage))).pop();
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const Key('management-professional-catalog-doctor')),
    );
    await tester.pumpAndSettle();
    final doctorCatalog = tester.widget<ProfessionalCatalogPage>(
      find.byType(ProfessionalCatalogPage),
    );
    expect(doctorCatalog.scope, ProfessionalCatalogScope.doctor);
  });
}

Widget _localizedApp({required Widget home}) => MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: home,
    );

final class _IdentityRepositoryStub implements IdentityRepository {
  const _IdentityRepositoryStub({
    this.overview = const IdentityOverview(),
    this.managementContext,
  });

  final IdentityOverview overview;
  final ManagementContext? managementContext;

  @override
  Future<IdentityOverview> loadOverview() async => overview;

  @override
  Future<ManagementContext> loadManagementContext() async =>
      managementContext ??
      const ManagementContext(
        userId: 'user-1',
        platformRole: 'USER',
        activeRoles: [],
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
      );

  @override
  Future<List<ConsultantMembership>> listConsultantMemberships() async =>
      const [];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _DiscoverRepositoryStub implements DiscoverRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _CatalogDiscoverRepositoryStub
    implements DiscoverRepository, ProfessionalCatalogRepository {
  @override
  Future<List<DiscoverItem>> loadVisibleInstitutionProjects() async => const [];

  @override
  Future<List<DiscoverItem>> loadVisibleInstitutions() async => const [];

  @override
  Future<List<DiscoverItem>> loadVisibleProjects() async => const [];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
