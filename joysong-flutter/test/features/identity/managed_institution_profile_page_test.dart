import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_controller.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';

void main() {
  testWidgets(
      'one managed institution opens directly and platform facts are read only',
      (tester) async {
    _useTallView(tester);
    final repository = _InstitutionRepository(summaries: const [_summaryA]);

    await tester.pumpWidget(_app(repository));
    await tester.pumpAndSettle();

    expect(repository.loadedIds, ['institution-1']);
    expect(find.text('编辑机构档案'), findsOneWidget);
    expect(find.text('已认证'), findsOneWidget);
    expect(find.text('评分 4.8'), findsOneWidget);
    expect(find.text('评价 126'), findsOneWidget);
    expect(find.text('项目 12'), findsOneWidget);
    expect(find.text('医生 6'), findsOneWidget);
    expect(find.text('咨询 310'), findsOneWidget);
    expect(find.text('用户 280'), findsOneWidget);
    expect(find.text('案例 96'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '认证时间'), findsNothing);
    expect(find.widgetWithText(TextFormField, '用户数'), findsNothing);
    expect(find.widgetWithText(TextFormField, '案例数'), findsNothing);
  });

  testWidgets(
      'multiple institutions require selection and save all thirteen editable fields',
      (tester) async {
    _useTallView(tester);
    final repository = _InstitutionRepository(
      summaries: const [_summaryA, _summaryB],
    );

    await tester.pumpWidget(_app(repository));
    await tester.pumpAndSettle();

    expect(find.text('编辑机构档案'), findsNothing);
    expect(find.text('上海娇颜颂医美中心'), findsOneWidget);
    expect(find.text('北京悦颜中心'), findsOneWidget);
    await tester.tap(find.text('北京悦颜中心'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('institution-back-to-list')), findsOneWidget);
    await tester.tap(find.byKey(const Key('institution-back-to-list')));
    await tester.pumpAndSettle();
    expect(find.text('北京悦颜中心'), findsOneWidget);
    await tester.tap(find.text('北京悦颜中心'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('institution-name')), ' 新机构名 ');
    await tester.enterText(
        find.byKey(const Key('institution-address')), ' 新地址 ');
    await tester.enterText(find.byKey(const Key('institution-city')), ' 上海市 ');
    await tester.enterText(
        find.byKey(const Key('institution-description')), ' 新介绍 ');
    await tester.enterText(
        find.byKey(const Key('institution-establishedYear')), '');
    await tester.enterText(
        find.byKey(const Key('institution-credentials')), ' 新资质 ');
    await tester.enterText(
        find.byKey(const Key('institution-specialties')), '皮肤管理, 注射美容');
    await tester.enterText(find.byKey(const Key('institution-tags')), '连锁, 夜诊');
    await tester.enterText(
        find.byKey(const Key('institution-contactPhone')), '13800000000');
    await tester.enterText(
        find.byKey(const Key('institution-businessHours')), '09:00-20:00');
    await tester.ensureVisible(find.byKey(const Key('institution-save')));
    await tester.tap(find.byKey(const Key('institution-save')));
    await tester.pumpAndSettle();

    expect(repository.updatedId, 'institution-2');
    expect(repository.lastUpdate?.toJson(), {
      'name': '新机构名',
      'address': '新地址',
      'city': '上海',
      'description': '新介绍',
      'coverImage': 'cover-b.jpg',
      'images': ['room-a.jpg', 'room-b.jpg'],
      'establishedYear': null,
      'credentials': '新资质',
      'credentialImages': ['license-a.jpg', 'license-b.jpg'],
      'specialties': ['皮肤管理', '注射美容'],
      'tags': ['连锁', '夜诊'],
      'contactPhone': '13800000000',
      'businessHours': '09:00-20:00',
    });
  });

  testWidgets('established year validates range and empty explicitly clears it',
      (tester) async {
    _useTallView(tester);
    final repository = _InstitutionRepository(summaries: const [_summaryA]);

    await tester.pumpWidget(_app(repository));
    await tester.pumpAndSettle();

    final field = find.byKey(const Key('institution-establishedYear'));
    await tester.enterText(field, 'year');
    await tester.tap(find.byKey(const Key('institution-save')));
    await tester.pump();
    expect(
        find.text('请输入 1800 至 ${DateTime.now().year} 之间的整数年份'), findsOneWidget);
    expect(repository.updateAttempts, 0);

    await tester.enterText(field, '1799');
    await tester.tap(find.byKey(const Key('institution-save')));
    await tester.pump();
    expect(
        find.text('请输入 1800 至 ${DateTime.now().year} 之间的整数年份'), findsOneWidget);
    expect(repository.updateAttempts, 0);

    await tester.enterText(field, '');
    await tester.tap(find.byKey(const Key('institution-save')));
    await tester.pumpAndSettle();
    expect(repository.updateAttempts, 1);
    expect(repository.lastUpdate?.toJson()['establishedYear'], isNull);
  });

  testWidgets('institution profile states and form copy follow English locale',
      (tester) async {
    _useTallView(tester);
    final repository = _InstitutionRepository(summaries: const [_summaryA]);

    await tester.pumpWidget(_app(repository, locale: const Locale('en')));
    await tester.pumpAndSettle();

    expect(find.text('Edit institution profile'), findsOneWidget);
    expect(find.text('Established year'), findsOneWidget);
    expect(find.text('Save profile'), findsOneWidget);
    expect(find.text('Projects 12'), findsOneWidget);
    expect(find.text('Doctors 6'), findsOneWidget);
  });

  testWidgets('loading empty and error states follow English locale',
      (tester) async {
    final loading = Completer<List<ManagedInstitutionSummary>>();
    final emptyRepository = _InstitutionRepository(summaries: const [])
      ..listCompleter = loading;
    await tester.pumpWidget(
      _app(emptyRepository, locale: const Locale('en')),
    );
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    loading.complete(const []);
    await tester.pumpAndSettle();
    expect(
        find.text('No institutions are available to manage'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
    final failingRepository = _InstitutionRepository(summaries: const [])
      ..listError = true;
    await tester.pumpWidget(
      _app(failingRepository, locale: const Locale('en')),
    );
    await tester.pumpAndSettle();
    expect(
      find.text('Failed to load institution profile. Please try again.'),
      findsOneWidget,
    );
    expect(find.text('Retry'), findsOneWidget);
  });

  testWidgets(
      'images mutate, save disables, failure retries, and dispose ignores late work',
      (tester) async {
    _useTallView(tester);
    final repository = _InstitutionRepository(summaries: const [_summaryA]);
    final uploads = <String>[
      'cover-upload.jpg',
      'license-upload.jpg',
      'room-upload.jpg',
    ];
    final controller = InstitutionProfileController(repository);
    await controller.load();
    await controller.select('institution-1');
    addTearDown(controller.dispose);

    await tester.pumpWidget(_appShell(
      home: ManagedInstitutionProfileEditPage(
        controller: controller,
        profile: _profileA,
        imagePicker: () async => uploads.removeAt(0),
      ),
    ));
    await tester.pumpAndSettle();

    await _reveal(tester, const Key('institution-coverImage-add'));
    await tester.tap(find.byKey(const Key('institution-coverImage-add')));
    await tester.pump();
    await _reveal(tester, const Key('institution-credentialImages-add'));
    await tester.tap(find.byKey(const Key('institution-credentialImages-add')));
    await tester.pump();
    await _reveal(tester, const Key('institution-images-add'));
    await tester.tap(find.byKey(const Key('institution-images-add')));
    await tester.pump();
    await tester
        .ensureVisible(find.byKey(const Key('institution-images-remove-0')));
    await tester.tap(find.byKey(const Key('institution-images-remove-0')));

    repository.saveCompleter = Completer<ManagedInstitutionProfile>();
    await tester.ensureVisible(find.byKey(const Key('institution-save')));
    await tester.tap(find.byKey(const Key('institution-save')));
    await tester.pump();
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('institution-save')))
          .onPressed,
      isNull,
    );
    repository.saveCompleter!.completeError(Exception('network'));
    await tester.pumpAndSettle();
    expect(find.text('机构档案保存失败，请稍后重试'), findsOneWidget);

    repository.saveCompleter = null;
    await tester.tap(find.byKey(const Key('institution-save')));
    await tester.pumpAndSettle();
    expect(repository.updateAttempts, 2);
    expect(repository.lastUpdate?.coverImage, 'cover-upload.jpg');
    expect(repository.lastUpdate?.credentialImages,
        ['license-old.jpg', 'license-upload.jpg']);
    expect(repository.lastUpdate?.images, ['room-upload.jpg']);

    repository.detailCompleter = Completer<ManagedInstitutionProfile>();
    final lateController = InstitutionProfileController(repository);
    final pendingSelect = lateController.select('institution-1');
    lateController.dispose();
    repository.detailCompleter!.complete(_profileA);
    expect(await pendingSelect, isTrue);
  });
}

void _useTallView(WidgetTester tester) {
  tester.view.physicalSize = const Size(900, 6000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Future<void> _reveal(WidgetTester tester, Key key) async {
  final finder = find.byKey(key);
  for (var attempt = 0; attempt < 12 && finder.evaluate().isEmpty; attempt++) {
    await tester.drag(find.byType(ListView).last, const Offset(0, -350));
    await tester.pump();
  }
  await tester.ensureVisible(finder);
}

Widget _app(
  _InstitutionRepository repository, {
  Future<String?> Function()? imagePicker,
  Locale locale = const Locale('zh'),
}) =>
    _appShell(
      locale: locale,
      home: ManagedInstitutionProfilesPage(
        repository: repository,
        imagePicker: imagePicker,
      ),
    );

Widget _appShell({required Widget home, Locale locale = const Locale('zh')}) =>
    MaterialApp(
      locale: locale,
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: home,
    );

final class _InstitutionRepository implements IdentityRepository {
  _InstitutionRepository({required this.summaries});

  List<ManagedInstitutionSummary> summaries;
  final List<String> loadedIds = [];
  String? updatedId;
  ManagedInstitutionProfileUpdate? lastUpdate;
  int updateAttempts = 0;
  Completer<ManagedInstitutionProfile>? saveCompleter;
  Completer<ManagedInstitutionProfile>? detailCompleter;
  Completer<List<ManagedInstitutionSummary>>? listCompleter;
  bool listError = false;

  @override
  Future<List<ManagedInstitutionSummary>> listManagedInstitutions() async {
    if (listError) throw Exception('network');
    final pending = listCompleter;
    if (pending != null) return pending.future;
    return summaries;
  }

  @override
  Future<ManagedInstitutionProfile> loadManagedInstitution(String id) async {
    loadedIds.add(id);
    final pending = detailCompleter;
    if (pending != null) return pending.future;
    return id == 'institution-2' ? _profileB : _profileA;
  }

  @override
  Future<ManagedInstitutionProfile> updateManagedInstitution(
    String id,
    ManagedInstitutionProfileUpdate update,
  ) async {
    updateAttempts += 1;
    updatedId = id;
    lastUpdate = update;
    final pending = saveCompleter;
    if (pending != null) return pending.future;
    return id == 'institution-2' ? _profileB : _profileA;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

const _summaryA = ManagedInstitutionSummary(
  id: 'institution-1',
  name: '上海娇颜颂医美中心',
  city: '上海',
  address: '南京西路 100 号',
  isVerified: true,
  rating: 4.8,
  reviewCount: 126,
  projectCount: 12,
  doctorCount: 6,
);

const _summaryB = ManagedInstitutionSummary(
  id: 'institution-2',
  name: '北京悦颜中心',
  city: '北京',
  address: '朝阳路 8 号',
);

final _profileA = ManagedInstitutionProfile(
  id: 'institution-1',
  name: '上海娇颜颂医美中心',
  address: '南京西路 100 号',
  city: '上海',
  description: '专业医美服务',
  coverImage: 'cover-old.jpg',
  images: const ['room-old.jpg'],
  establishedYear: 2016,
  credentials: '医疗机构执业许可证',
  credentialImages: const ['license-old.jpg'],
  specialties: const ['皮肤管理'],
  tags: const ['连锁'],
  contactPhone: '021-12345678',
  businessHours: '09:00-18:00',
  rating: 4.8,
  reviewCount: 126,
  isVerified: true,
  certificationTime: DateTime(2026, 1, 15),
  projectCount: 12,
  doctorCount: 6,
  consultationCount: 310,
  userCount: 280,
  caseCount: 96,
);

const _profileB = ManagedInstitutionProfile(
  id: 'institution-2',
  name: '北京悦颜中心',
  address: '朝阳路 8 号',
  city: '北京',
  coverImage: 'cover-b.jpg',
  images: ['room-a.jpg', 'room-b.jpg'],
  credentialImages: ['license-a.jpg', 'license-b.jpg'],
);
