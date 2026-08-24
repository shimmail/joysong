import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/professional_catalog_page.dart';

import '../../core/translation/translation_test_fixtures.dart';

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
    expect(find.text('Visible institution'), findsOneWidget);
    await tester.tap(find.text('Visible institution'));

    for (var frame = 0; frame < 4; frame += 1) {
      await tester.pump(Duration.zero, EnginePhase.build);
    }
    final doctorTile = _unmountedListTile(tester, 'Visible doctor');
    doctorTile.onTap!();
    for (var frame = 0; frame < 4; frame += 1) {
      await tester.pump(Duration.zero, EnginePhase.build);
    }

    final projectTile = _unmountedListTile(tester, 'Doctor project');
    expect((projectTile.title as Text).data, 'Doctor project');
    expect(repository.calls, contains('institution/i-1'));
    expect(repository.calls, contains('institution/i-1/doctors'));
    expect(repository.calls, contains('institution/i-1/doctor/d-1/projects'));
    await tester.pumpWidget(const SizedBox.shrink());
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

  testWidgets(
      'professional project catalog makes zero requests under active translation scope',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1200, 1800);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    final translationRepository = RecordingTranslationRepository();
    final translationController = AutoTranslationController(
      repository: translationRepository,
    )..synchronize(
        enabled: true,
        authenticated: true,
        targetLanguage: 'en-US',
      );
    addTearDown(translationController.dispose);
    final repository = _CatalogRepository(
      selectedProject: _chineseProfessionalProject,
    );

    await tester.pumpWidget(
      AutoTranslationScope(
        controller: translationController,
        enabled: true,
        targetLanguage: 'en-US',
        child: MaterialApp(
          locale: const Locale('en'),
          home: ProfessionalCatalogPage(
            repository: repository,
            scope: ProfessionalCatalogScope.legalRepresentative,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('专业端中文项目'));
    await tester.pumpAndSettle();

    expect(find.text('专业端中文项目'), findsWidgets);
    expect(find.text('专业端项目说明'), findsOneWidget);
    await tester.drag(find.byType(CustomScrollView), const Offset(0, -1500));
    await tester.pumpAndSettle();
    expect(find.text('专业端日记标题'), findsOneWidget);
    expect(find.text('专业端评价内容'), findsOneWidget);
    expect(translationRepository.calls, isEmpty);
  });
}

ListTile _unmountedListTile(WidgetTester tester, String title) {
  for (final listView in tester.widgetList<ListView>(
    find.byType(ListView, skipOffstage: false),
  )) {
    final delegate = listView.childrenDelegate;
    if (delegate is! SliverChildListDelegate) continue;
    for (final child in delegate.children) {
      if (child is! Card || child.child is! ListTile) continue;
      final tile = child.child! as ListTile;
      final tileTitle = tile.title;
      if (tileTitle is Text && tileTitle.data == title) return tile;
    }
  }
  throw StateError('No production ListTile titled $title');
}

final class _CatalogRepository implements ProfessionalCatalogRepository {
  _CatalogRepository({this.selectedProject});

  final DiscoverItem? selectedProject;
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
    return [
      selectedProject ??
          item('p-1', DiscoverContentType.project, 'Platform project')
    ];
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

const _chineseProfessionalProject = DiscoverItem(
  id: 'professional-project-1',
  type: DiscoverContentType.project,
  title: '专业端中文项目',
  subtitle: '专业端项目说明',
  raw: {
    'project': {
      'id': 'professional-project-1',
      'name': '专业端中文项目',
      'description': '专业端项目说明',
    },
    'diaries': [
      {
        'id': 'professional-diary-1',
        'title': '专业端日记标题',
        'content': '专业端日记内容',
        'authorName': '专业端用户姓名',
      },
    ],
    'reviews': [
      {
        'id': 'professional-review-1',
        'userName': '专业端评价用户',
        'content': '专业端评价内容',
        'rating': 5,
      },
    ],
  },
);
