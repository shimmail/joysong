import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_institution_detail_view.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_page.dart';
import 'package:joysong_flutter/features/discover/presentation/doctor_detail_view.dart';

import '../../core/translation/translation_test_fixtures.dart';

void main() {
  testWidgets(
      'consumer institution detail translates only visible allowlisted fields',
      (tester) async {
    await _useTallSurface(tester);
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _host(
        controller,
        DiscoverDetailPage(
          repository: const _DetailRepository(_institution),
          type: DiscoverContentType.institution,
          id: _institution.id,
          initialItem: _institution,
        ),
      ),
    );
    await tester.pump();

    for (final source in const [
      '悦美机构',
      '上海市 · 静安路',
      '公开资质说明',
      '水光项目',
      '项目说明',
      '补水标签',
      '机构日记',
      '机构评价',
    ]) {
      expect(find.text(source), findsWidgets, reason: source);
    }
    expect(
      _requestRecords(tester),
      containsAll(const {
        ('institution', 'institution:institution-1', 'name', '悦美机构'),
        (
          'institution',
          'institution:institution-1',
          'address',
          '上海市 · 静安路',
        ),
        (
          'institution',
          'institution:institution-1',
          'credentials',
          '公开资质说明',
        ),
        ('project', 'project:project-1', 'projectName', '水光项目'),
        ('project', 'project:project-1', 'description', '项目说明'),
        ('project', 'project:project-1', 'tags', '补水标签'),
        ('diary', 'diary:diary-1', 'title', '机构日记'),
        ('diary', 'diary:diary-1', 'content', '日记恢复过程'),
        (
          'comment',
          'institution:institution-1:review:review-1',
          'content',
          '机构评价',
        ),
      }),
    );

    await _completeAll(tester, repository, const {
      '悦美机构': 'Yuemei Institution',
      '上海市 · 静安路': 'Shanghai · Jingan Road',
      '公开资质说明': 'Public credentials',
      '水光项目': 'Hydration project',
      '项目说明': 'Project description',
      '补水标签': 'Hydration tag',
      '项目分类': 'Project category',
      '机构日记': 'Institution diary',
      '日记恢复过程': 'Diary recovery progress',
      '机构评价': 'Institution review',
      '评价项目': 'Reviewed project',
      '评价标签': 'Review tag',
    });

    expect(find.text('Yuemei Institution'), findsWidgets);
    expect(find.text('Shanghai · Jingan Road'), findsWidgets);
    expect(find.text('Public credentials'), findsOneWidget);
    expect(find.text('Hydration project'), findsWidgets);
    expect(find.text('Institution diary'), findsOneWidget);
    expect(find.text('Institution review'), findsOneWidget);
    _expectNeverRequested(repository, const [
      '不可见的机构备用说明',
      '小美',
      '李女士',
      '021-12345678',
      '1280',
      '2026-08-20',
      '4.8',
      'https://images.test/review.jpg',
    ]);
  });

  testWidgets(
      'consumer doctor detail translates professional text but not identities',
      (tester) async {
    await _useTallSurface(tester);
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _host(
        controller,
        DiscoverDetailPage(
          repository: const _DetailRepository(_doctor),
          type: DiscoverContentType.doctor,
          id: _doctor.id,
          initialItem: _doctor,
        ),
      ),
    );
    await tester.pump();

    expect(find.textContaining('张医生'), findsOneWidget);
    expect(find.text('激光美容'), findsOneWidget);
    expect(find.text('CERT-2026-001'), findsOneWidget);
    expect(
      _requestRecords(tester),
      containsAll(const {
        ('doctor', 'doctor:doctor-1', 'title', '主任医师'),
        ('doctor', 'doctor:doctor-1', 'institutionName', '悦美机构'),
        ('doctor', 'doctor:doctor-1', 'specialties', '皮肤管理'),
        ('doctor', 'doctor:doctor-1', 'credentials', '医生资质说明'),
        (
          'project',
          'project:related-project-1',
          'projectName',
          '光电项目',
        ),
        (
          'comment',
          'doctor:doctor-1:review:doctor-review-1',
          'content',
          '医生评价',
        ),
      }),
    );

    await _completeAll(tester, repository, const {
      '主任医师': 'Chief physician',
      '悦美机构': 'Yuemei Institution',
      '皮肤管理': 'Skin management',
      '医生资质说明': 'Doctor credentials',
      '光电项目': 'Energy-based project',
      '项目所属机构': 'Project institution',
      '面部护理': 'Facial care',
      '分院名称': 'Branch institution',
      '上海市 静安路': 'Shanghai Jingan Road',
      '医生日记': 'Doctor diary',
      '医生日记内容': 'Doctor diary content',
      '医生评价': 'Doctor review',
      '评价项目': 'Reviewed project',
      '医生评价标签': 'Doctor review tag',
    });

    expect(find.textContaining('张医生'), findsOneWidget);
    expect(find.textContaining('Chief physician'), findsOneWidget);
    expect(find.text('Skin management'), findsOneWidget);
    expect(find.text('Doctor credentials'), findsOneWidget);
    expect(find.text('Energy-based project'), findsOneWidget);
    expect(find.text('Doctor review'), findsOneWidget);
    expect(find.text('CERT-2026-001'), findsOneWidget);
    expect(find.text('平台认证'), findsOneWidget);
    _expectNeverRequested(repository, const [
      '张医生',
      'CERT-2026-001',
      '平台认证',
      '激光美容',
      '不可见的医生简介',
      '日记作者',
      '评价用户',
      '13800000000',
      '980',
      '2026-08-21',
      '4.9',
      'https://images.test/certificate.jpg',
    ]);
  });

  testWidgets(
      'shared details default off and raw fallback provenance stays honest',
      (tester) async {
    await _useTallSurface(tester);
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _host(
        controller,
        const CatalogInstitutionDetailView(item: _institution),
      ),
    );
    await tester.pump();
    expect(repository.calls, isEmpty);

    const institutionFallback = DiscoverItem(
      id: 'institution-fallback',
      type: DiscoverContentType.institution,
      title: 'Fallback institution',
      raw: {
        'institution': {
          'id': 'institution-fallback',
          'name': 'Fallback institution',
          'description': '机构描述回退',
        },
      },
    );
    await tester.pumpWidget(
      _host(
        controller,
        const CatalogInstitutionDetailView(
          item: institutionFallback,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pump();
    expect(
      _requestRecords(tester),
      contains(const (
        'institution',
        'institution:institution-fallback',
        'description',
        '机构描述回退',
      )),
    );

    const bioFallbackDoctor = DiscoverItem(
      id: 'doctor-bio-fallback',
      type: DiscoverContentType.doctor,
      title: 'Doctor',
      raw: {
        'doctor': {
          'id': 'doctor-bio-fallback',
          'name': 'Doctor',
          'bio': '医生简介回退',
        },
      },
    );
    await tester.pumpWidget(
      _host(
        controller,
        const DoctorDetailView(
          item: bioFallbackDoctor,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pump();
    expect(
      _requestRecords(tester),
      contains(const (
        'doctor',
        'doctor:doctor-bio-fallback',
        'bio',
        '医生简介回退',
      )),
    );

    final authorOnlyDoctor = DiscoverItem.fromJson(
      const {
        'id': 'doctor-author-only',
        'name': '张医生',
        'authorName': '人物姓名回退',
      },
      type: DiscoverContentType.doctor,
    );
    final callsBeforeDoctor = repository.calls.length;
    await tester.pumpWidget(
      _host(
        controller,
        DoctorDetailView(
          item: authorOnlyDoctor,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pump();
    expect(find.text('人物姓名回退'), findsOneWidget);
    expect(repository.calls.length, callsBeforeDoctor);
    expect(
      _requestRecords(tester).where((record) => record.$4 == '人物姓名回退'),
      isEmpty,
    );
  });

  testWidgets('nested projects without real IDs stay source only',
      (tester) async {
    await _useTallSurface(tester);
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const institution = DiscoverItem(
      id: 'institution-missing-project-ids',
      type: DiscoverContentType.institution,
      title: 'Institution',
      raw: {
        'institution': {
          'id': 'institution-missing-project-ids',
          'name': 'Institution',
        },
        'projects': [
          {'projectName': '无标识机构项目一'},
          {'projectName': '无标识机构项目二'},
        ],
      },
    );
    await tester.pumpWidget(
      _host(
        controller,
        const CatalogInstitutionDetailView(
          item: institution,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pump();
    expect(find.text('无标识机构项目一'), findsOneWidget);
    expect(find.text('无标识机构项目二'), findsOneWidget);
    expect(repository.calls, isEmpty);

    const doctor = DiscoverItem(
      id: 'doctor-missing-project-ids',
      type: DiscoverContentType.doctor,
      title: 'Doctor',
      raw: {
        'doctor': {'id': 'doctor-missing-project-ids', 'name': 'Doctor'},
        'institutionProjects': [
          {'projectName': '无标识医生项目一'},
          {'projectName': '无标识医生项目二'},
        ],
      },
    );
    await tester.pumpWidget(
      _host(
        controller,
        const DoctorDetailView(
          item: doctor,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pump();
    expect(find.text('无标识医生项目一'), findsOneWidget);
    expect(find.text('无标识医生项目二'), findsOneWidget);
    expect(repository.calls, isEmpty);
  });
}

typedef _RequestRecord = (String, String, String, String);

Set<_RequestRecord> _requestRecords(WidgetTester tester) => tester
    .widgetList<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
    .map(
      (builder) => (
        builder.request.contentType,
        builder.request.contentId,
        builder.request.field,
        builder.request.sourceText,
      ),
    )
    .toSet();

AutoTranslationController _activeController(
  TranslationRepository repository,
) =>
    AutoTranslationController(repository: repository, maxConcurrent: 40)
      ..synchronize(
        enabled: true,
        authenticated: true,
        targetLanguage: 'en-US',
      );

Widget _host(AutoTranslationController controller, Widget child) =>
    AutoTranslationScope(
      controller: controller,
      enabled: true,
      targetLanguage: 'en-US',
      child: MaterialApp(
        locale: const Locale('en'),
        home: Scaffold(body: child),
      ),
    );

Future<void> _completeAll(
  WidgetTester tester,
  RecordingTranslationRepository repository,
  Map<String, String> translations,
) async {
  var completed = 0;
  var safety = 0;
  while (completed < repository.calls.length || repository.activeCalls > 0) {
    while (completed < repository.calls.length) {
      final call = repository.calls[completed];
      final translated = translations[call.text];
      if (translated == null) fail('Missing translation for ${call.text}');
      repository.completeNext(translated!);
      completed += 1;
      await tester.pump();
      await tester.pump();
    }
    safety += 1;
    if (safety > translations.length * 3 + 3)
      fail('translations did not drain');
    await tester.pump();
  }
}

void _expectNeverRequested(
  RecordingTranslationRepository repository,
  List<String> excluded,
) {
  final requested = repository.calls.map((call) => call.text).toList();
  for (final source in excluded) {
    expect(requested, isNot(contains(source)), reason: source);
  }
}

Future<void> _useTallSurface(WidgetTester tester) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1200, 5200);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
}

final class _DetailRepository implements DiscoverRepository {
  const _DetailRepository(this.item);

  final DiscoverItem item;

  @override
  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  }) async =>
      item;

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() =>
      throw UnimplementedError();

  @override
  Future<DiscoverPageResult> loadPage({
    required DiscoverContentType type,
    required int offset,
    required int limit,
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
  }) =>
      throw UnimplementedError();
}

const _institution = DiscoverItem(
  id: 'institution-1',
  type: DiscoverContentType.institution,
  title: '悦美机构',
  raw: {
    'institution': {
      'id': 'institution-1',
      'name': '悦美机构',
      'city': '上海市',
      'address': '静安路',
      'credentials': '公开资质说明',
      'description': '不可见的机构备用说明',
      'contactPhone': '021-12345678',
      'rating': 4.8,
    },
    'projects': [
      {
        'id': 'project-1',
        'projectName': '水光项目',
        'description': '项目说明',
        'tags': ['补水标签'],
        'category': '项目分类',
        'price': 1280,
      },
    ],
    'diaries': [
      {
        'id': 'diary-1',
        'title': '机构日记',
        'content': '日记恢复过程',
        'authorName': '小美',
        'publishDate': '2026-08-20',
      },
    ],
    'reviews': [
      {
        'id': 'review-1',
        'content': '机构评价',
        'projectName': '评价项目',
        'tags': ['评价标签'],
        'userName': '李女士',
        'createdAt': '2026-08-20',
        'rating': 5,
        'images': ['https://images.test/review.jpg'],
      },
    ],
    'doctors': [],
  },
);

const _doctor = DiscoverItem(
  id: 'doctor-1',
  type: DiscoverContentType.doctor,
  title: '张医生',
  raw: {
    'doctor': {
      'id': 'doctor-1',
      'name': '张医生',
      'title': '主任医师',
      'institutionName': '悦美机构',
      'specialties': ['激光美容', '皮肤管理'],
      'certificationTags': ['CERT-2026-001', '平台认证', '激光美容'],
      'credentials': '医生资质说明',
      'bio': '不可见的医生简介',
      'credentialImages': ['https://images.test/certificate.jpg'],
      'rating': 4.9,
      'reviewCount': 8,
    },
    'institutionProjects': [
      {
        'projectId': 'related-project-1',
        'projectName': '光电项目',
        'institutionName': '项目所属机构',
        'tags': ['面部护理'],
        'price': 980,
      },
    ],
    'institutions': [
      {
        'id': 'institution-branch',
        'name': '分院名称',
        'city': '上海市',
        'address': '静安路',
        'phone': '13800000000',
      },
    ],
    'diaries': [
      {
        'id': 'doctor-diary-1',
        'title': '医生日记',
        'content': '医生日记内容',
        'authorName': '日记作者',
        'publishDate': '2026-08-21',
      },
    ],
    'reviews': [
      {
        'id': 'doctor-review-1',
        'content': '医生评价',
        'projectName': '评价项目',
        'tags': ['医生评价标签'],
        'userName': '评价用户',
        'rating': 5,
      },
    ],
  },
);
