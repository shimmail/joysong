import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';

void main() {
  testWidgets(
      'multi-role entries submit their explicit membership request type',
      (tester) async {
    tester.view.physicalSize = const Size(900, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final identityRepository = _FakeIdentityRepository();
    final discoverRepository = _FakeDiscoverRepository();

    await tester.pumpWidget(MaterialApp(
      locale: const Locale('zh'),
      home: ManagementCenterPage(
        repository: identityRepository,
        discoverRepository: discoverRepository,
      ),
    ));
    await tester.pumpAndSettle();
    await _applyFromEntry(tester, find.text('Apply to institution').first);
    expect(identityRepository.submittedTypes, ['DOCTOR']);
    await tester.pageBack();
    await tester.pumpAndSettle();

    await tester.scrollUntilVisible(
      find.text('Consultant'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
    await _applyFromEntry(tester, find.text('Apply to institution').last);
    expect(identityRepository.submittedTypes, ['DOCTOR', 'CONSULTANT']);
  });
}

Future<void> _applyFromEntry(WidgetTester tester, Finder entry) async {
  await tester.tap(entry);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('membership-institution-picker')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Joysong Clinic'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Submit'));
  await tester.pumpAndSettle();
}

final class _FakeIdentityRepository implements IdentityRepository {
  final submittedTypes = <String>[];

  @override
  Future<ManagementContext> loadManagementContext() async =>
      const ManagementContext(
        userId: 'professional-1',
        platformRole: 'USER',
        activeRoles: ['DOCTOR', 'CONSULTANT'],
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        canApplyToInstitutions: true,
      );

  @override
  Future<List<InstitutionOption>> listInstitutionOptions() async => const [];

  @override
  Future<List<InstitutionMembershipRequest>>
      listInstitutionMembershipRequests() async => const [];

  @override
  Future<void> submitInstitutionMembershipRequest({
    required String requestType,
    required String institutionId,
    required String requestNote,
  }) async {
    submittedTypes.add(requestType);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _FakeDiscoverRepository implements DiscoverRepository {
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
      const DiscoverPageResult(
        items: [
          DiscoverItem(
            id: 'inst-1',
            type: DiscoverContentType.institution,
            title: 'Joysong Clinic',
          ),
        ],
        hasMore: false,
      );

  @override
  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  }) =>
      throw UnimplementedError();

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() async =>
      const DiscoverFilterOptions();
}
