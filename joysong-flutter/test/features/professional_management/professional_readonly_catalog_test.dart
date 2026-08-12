import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/professional_catalog_page.dart';

void main() {
  testWidgets('active doctor browses visible catalog', (tester) async {
    final repository = _CatalogRepository();
    await tester.pumpWidget(MaterialApp(
        home: ProfessionalCatalogPage(
      repository: repository,
      scope: ProfessionalCatalogScope.doctor,
    )));
    await tester.pumpAndSettle();

    expect(repository.calls.take(3),
        ['institutions', 'projects', 'institution-projects']);
    await tester.tap(find.text('Visible institution'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Visible doctor'));
    await tester.pumpAndSettle();
    expect(find.text('Doctor project'), findsOneWidget);
    expect(
        repository.calls,
        containsAll([
          'institution/i-1',
          'institution/i-1/doctors',
          'institution/i-1/doctor/d-1/projects'
        ]));
  });

  testWidgets('legal representative browses read-only catalog', (tester) async {
    final repository = _CatalogRepository();
    await tester.pumpWidget(MaterialApp(
        home: ProfessionalCatalogPage(
      repository: repository,
      scope: ProfessionalCatalogScope.legalRepresentative,
    )));
    await tester.pumpAndSettle();

    expect(find.text('Platform project'), findsOneWidget);
    expect(find.text('Institution project'), findsOneWidget);
    expect(find.byIcon(Icons.add), findsNothing);
    expect(find.byIcon(Icons.edit), findsNothing);
    expect(find.byIcon(Icons.delete), findsNothing);
    expect(
        repository.calls.every((call) => !call.startsWith('write:')), isTrue);
  });
}

final class _CatalogRepository implements ProfessionalCatalogRepository {
  final calls = <String>[];
  DiscoverItem item(String id, DiscoverContentType type, String title) =>
      DiscoverItem(id: id, type: type, title: title);
  @override
  Future<List<DiscoverItem>> loadVisibleInstitutions() async {
    calls.add('institutions');
    return [
      item('i-1', DiscoverContentType.institution, 'Visible institution')
    ];
  }

  @override
  Future<List<DiscoverItem>> loadVisibleProjects() async {
    calls.add('projects');
    return [item('p-1', DiscoverContentType.project, 'Platform project')];
  }

  @override
  Future<List<DiscoverItem>> loadVisibleInstitutionProjects() async {
    calls.add('institution-projects');
    return [item('ip-1', DiscoverContentType.project, 'Institution project')];
  }

  @override
  Future<DiscoverItem> loadVisibleInstitution(String id) async {
    calls.add('institution/$id');
    return item(id, DiscoverContentType.institution, 'Visible institution');
  }

  @override
  Future<List<DiscoverItem>> loadVisibleInstitutionDoctors(String id) async {
    calls.add('institution/$id/doctors');
    return [item('d-1', DiscoverContentType.doctor, 'Visible doctor')];
  }

  @override
  Future<List<DiscoverItem>> loadVisibleDoctorProjects(
      String institutionId, String doctorId) async {
    calls.add('institution/$institutionId/doctor/$doctorId/projects');
    return [item('dp-1', DiscoverContentType.project, 'Doctor project')];
  }
}
