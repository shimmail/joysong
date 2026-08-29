import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/institution_project_preview_body.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/institution_project_review_widgets.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

void main() {
  setUp(() {});

  test('preview adapters preserve snapshot, creation, and legacy contracts',
      () {
    const snapshot = DoctorInstitutionProjectSnapshot(
      rawOverrides: {},
      effective: {
        'name': 'Effective facial',
        'category': 'Skin',
        'description': 'Effective description',
        'tags': ['skin'],
        'slogan': 'Effective slogan',
        'detailContent': 'Effective detail',
        'coverImage': ' cover.jpg ',
        'images': ['gallery.jpg', 'cover.jpg', ' ', 'gallery-2.jpg'],
        'salesCount': 18,
      },
      source: {},
    );
    final v2 = DoctorProjectChangeRequest(
      id: 'v2',
      doctorId: 'doctor',
      doctorName: 'Dr. Chen',
      institutionId: 'institution',
      institutionName: 'Joysong Clinic',
      institutionProjectId: 'ip',
      projectName: 'Legacy name',
      requestType: 'EDIT',
      serviceDescription: '',
      priceSuggestion: 0,
      notes: '',
      serviceTags: const [],
      scheduleNote: '',
      coverImage: '',
      images: const [],
      consultationFee: null,
      commissionRate: null,
      institutionRate: null,
      platformRate: 10,
      doctorRate: null,
      forceProcessed: false,
      status: 'PENDING',
      reviewNote: null,
      payloadVersion: 2,
      proposedProject: snapshot,
      proposedDoctorPrice: 321.25,
    );
    final v2Preview = InstitutionProjectPreviewAdapters.fromV2(v2);
    expect(v2Preview.name, 'Effective facial');
    expect(v2Preview.price, 321.25);
    expect(v2Preview.currency, 'USD');
    expect(v2Preview.images, ['cover.jpg', 'gallery.jpg', 'gallery-2.jpg']);

    const creation = InstitutionProjectRequestDraft(
      institutionId: 'institution',
      projectId: 'platform-project',
      name: 'Created facial',
      description: 'Created description',
      price: 88.5,
      currency: 'CNY',
      coverImage: 'created-cover.jpg',
      images: ['created-cover.jpg', 'created-gallery.jpg'],
      salesCount: 7,
    );
    final creationPreview = InstitutionProjectPreviewAdapters.fromCreation(
        creation,
        institutionName: 'Joysong Clinic');
    expect(creationPreview.name, 'Created facial');
    expect(creationPreview.currency, 'CNY');
    expect(
        creationPreview.images, ['created-cover.jpg', 'created-gallery.jpg']);

    final v1 = DoctorProjectChangeRequest(
      id: 'v1',
      doctorId: 'doctor',
      doctorName: 'Dr. Chen',
      institutionId: 'institution',
      institutionName: 'Joysong Clinic',
      institutionProjectId: 'ip',
      projectName: 'Legacy facial',
      requestType: 'PROFILE_UPDATE',
      serviceDescription: 'Legacy description',
      priceSuggestion: 66,
      notes: '',
      serviceTags: const ['legacy'],
      scheduleNote: 'Tuesday 10:00',
      coverImage: 'legacy-cover.jpg',
      images: const ['legacy-gallery.jpg'],
      consultationFee: 0,
      commissionRate: 0,
      institutionRate: 0,
      platformRate: 0,
      doctorRate: 100,
      forceProcessed: false,
      status: 'PENDING',
      reviewNote: null,
      payloadVersion: 1,
    );
    final legacyPreview = InstitutionProjectPreviewAdapters.fromV1(v1);
    expect(legacyPreview.model.name, 'Legacy facial');
    expect(legacyPreview.scheduleNote, 'Tuesday 10:00');
    expect(
        legacyPreview.model.images, ['legacy-cover.jpg', 'legacy-gallery.jpg']);
  });

  testWidgets(
    'institution creation reviews reuse grouped cards and the shared detail route',
    (tester) async {
      _useLargeSurface(tester);
      final repository = _ProjectRequestRepository()
        ..requests = [
          _request(
            id: 'creation-1',
            type: 'INSTITUTION',
            doctorId: 'doctor-1',
            institutionId: 'inst-1',
          ),
          _request(
            id: 'creation-2',
            type: 'INSTITUTION',
            doctorId: 'doctor-2',
            institutionId: 'inst-1',
          ),
          _request(
            id: 'creation-3',
            type: 'INSTITUTION',
            doctorId: 'doctor-2',
            institutionId: 'inst-2',
          ),
        ];

      await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
        repository: repository,
        context: _adminContext,
        reviewMode: true,
      )));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('institution-review-group-inst-1')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('institution-review-group-inst-2')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('institution-review-card-creation-1')),
        findsOneWidget,
      );

      final card = find.byKey(
        const Key('institution-review-card-creation-1'),
      );
      for (final text in const [
        'Clinic Hydrating Facial creation-1',
        '平台项目: Hydrating Facial',
        '医生: Dr. Chen',
        'USD 799.50',
      ]) {
        expect(
          find.descendant(of: card, matching: find.text(text)),
          findsOneWidget,
        );
      }
      expect(
        find.descendant(
          of: card,
          matching: find.textContaining('旅游地接服务费'),
        ),
        findsOneWidget,
      );
      for (final key in const [
        'request-status-creation-1',
        'doctor-active-status-creation-1',
        'institution-review-detail-creation-1',
        'review-creation-creation-1',
      ]) {
        expect(
          find.descendant(of: card, matching: find.byKey(Key(key))),
          findsOneWidget,
        );
      }
      for (final label in const [
        '申请编号',
        '机构编号',
        '申请医生编号',
        '项目标语',
        '项目详情',
        '封面图',
        '项目图片',
        '分类标签',
        '审核意见',
        '审核人',
        '生成平台项目',
      ]) {
        expect(
          find.descendant(of: card, matching: find.textContaining(label)),
          findsNothing,
        );
      }

      await tester.tap(
        find.byKey(const Key('institution-review-detail-creation-1')),
      );
      await tester.pumpAndSettle();

      expect(find.byType(InstitutionProjectPreviewBody), findsOneWidget);
      expect(find.text('Clinic Hydrating Facial creation-1'), findsOneWidget);
      expect(find.text('Current values at submission'), findsNothing);
      expect(find.text('申请编号：creation-1'), findsOneWidget);
      expect(find.textContaining('申请医生编号：doctor-1'), findsOneWidget);
      expect(find.textContaining('Complete immutable detail'), findsOneWidget);
      expect(find.textContaining('项目详情：'), findsNothing);
      await tester.drag(find.byType(ListView).last, const Offset(0, -1200));
      await tester.pumpAndSettle();
      expect(
        find.text('这是新增申请，因此没有变更前快照。'),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('creation-detail-review-creation-1')),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'institution creation review revokes access and refreshes management context on 403',
    (tester) async {
      _useLargeSurface(tester);
      var refreshes = 0;
      final repository = _ProjectRequestRepository()
        ..requests = [
          _request(
            id: 'creation-403',
            type: 'INSTITUTION',
            doctorId: 'doctor-1',
            institutionId: 'inst-1',
          ),
        ]
        ..institutionReviewError = const ApiException(
          message: 'forbidden server text',
          httpStatus: 403,
        );

      await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
        repository: repository,
        context: _legalContext,
        reviewMode: true,
        onRefreshManagementContext: () async {
          refreshes++;
          return _nonTargetLegalContext;
        },
      )));
      await tester.pumpAndSettle();

      await tester.tap(
        find.byKey(const Key('review-creation-creation-403')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('creation-review-confirm')));
      await tester.pumpAndSettle();

      expect(refreshes, 1);
      expect(
        find.byKey(const Key('review-creation-creation-403')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'fix round 1: creation 403 from detail exits detail and parent review routes',
    (tester) async {
      _useLargeSurface(tester);
      final repository = _ProjectRequestRepository()
        ..requests = [
          _request(
            id: 'creation-detail-403',
            type: 'INSTITUTION',
            doctorId: 'doctor-1',
            institutionId: 'inst-1',
          ),
        ]
        ..institutionReviewError = const ApiException(
          message: 'detail permission revoked',
          httpStatus: 403,
        );

      await tester.pumpWidget(_app(Builder(builder: (context) {
        return Scaffold(
          body: FilledButton(
            key: const Key('open-creation-review-route'),
            onPressed: () => Navigator.of(context).push<void>(
              MaterialPageRoute(
                builder: (_) => InstitutionProjectRequestsPage(
                  repository: repository,
                  context: _legalContext,
                  reviewMode: true,
                  onRefreshManagementContext: () async =>
                      _nonTargetLegalContext,
                ),
              ),
            ),
            child: const Text('Open creation reviews'),
          ),
        );
      })));
      await tester.tap(find.byKey(const Key('open-creation-review-route')));
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(
          const Key('institution-review-detail-creation-detail-403'),
        ),
      );
      await tester.pumpAndSettle();
      final review = find.byKey(
        const Key('creation-detail-review-creation-detail-403'),
      );
      await tester.ensureVisible(review.last);
      await tester.tap(review.last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('creation-review-confirm')));
      await tester.pumpAndSettle();

      expect(find.byType(InstitutionProjectRequestsPage), findsNothing);
      expect(find.byType(InstitutionProjectReviewDetailPage), findsNothing);
    },
  );

  testWidgets(
      'platform application keeps the complete ordered form and uses the injected cover/gallery uploader',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository()
      ..managementContext = _doctorContext;
    final uploads = <Object>[
      'https://cdn.example.com/platform-cover.jpg',
      'https://cdn.example.com/platform-gallery.jpg',
      StateError('upload failed'),
    ];

    await tester.pumpWidget(_app(
      ManagementCenterPage(
        repository: repository,
        discoverRepository: const _DiscoverRepository(),
        doctorImagePicker: () async {
          final next = uploads.removeAt(0);
          if (next is Error) throw next;
          return next as String;
        },
      ),
    ),);
    await tester.pumpAndSettle();
    await tester.tap(find.text('申请新增平台项目'));
    await tester.pumpAndSettle();

    const orderedKeys = [
      'platform-name',
      'platform-reference-price',
      'platform-slogan',
      'platform-sales-count',
      'platform-cover-upload',
      'platform-gallery-upload',
      'platform-category',
      'platform-description',
      'platform-detail-content',
      'platform-detail-suitable',
      'platform-detail-contraindications',
      'platform-detail-recovery',
      'platform-detail-highlights',
      'platform-detail-risks',
      'platform-tags',
      'platform-category-tags',
      'platform-notes',
    ];
    final tops = <double>[];
    for (final key in orderedKeys) {
      final finder = find.byKey(Key(key));
      expect(finder, findsOneWidget, reason: 'missing $key');
      tops.add(tester.getTopLeft(finder).dy);
    }
    expect(tops, orderedEquals([...tops]..sort()));
    expect(
      tester
          .widget<TextField>(
            find.byKey(const Key('platform-detail-content'))
          )
          .maxLines,
      greaterThan(1),
    );
    expect(find.textContaining('评分'), findsNothing);
    expect(find.textContaining('评价数'), findsNothing);
    expect(find.textContaining('选择医生'), findsNothing);
    expect(find.byKey(const Key('platform-currency')), findsNothing);
    expect(
      tester
          .widget<TextField>(
            find.byKey(const Key('platform-reference-price')),
          )
          .decoration
          ?.labelText,
      '参考价格（USD）',
    );
    expect(find.byType(EditableText).evaluate().length, 15);

    await tester.tap(find.byKey(const Key('platform-cover-upload')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('platform-gallery-upload')));
    await tester.pump();
    expect(find.text('https://cdn.example.com/platform-cover.jpg'),
        findsOneWidget,);
    expect(find.text('https://cdn.example.com/platform-gallery.jpg'),
        findsOneWidget,);
    const coverRemoveLabel =
        '移除封面图 1：https://cdn.example.com/platform-cover.jpg';
    const galleryRemoveLabel =
        '移除项目图片 1：https://cdn.example.com/platform-gallery.jpg';
    final coverRemove = find.byWidgetPredicate(
        (widget) => widget is IconButton && widget.tooltip == coverRemoveLabel,);
    final galleryRemove = find.byWidgetPredicate((widget) =>
        widget is IconButton && widget.tooltip == galleryRemoveLabel,);
    expect(coverRemove, findsOneWidget);
    expect(galleryRemove, findsOneWidget);
    expect(
      find.ancestor(
        of: coverRemove,
        matching: find.byWidgetPredicate((widget) =>
            widget is Semantics && widget.properties.label == coverRemoveLabel,),
      ),
      findsOneWidget,
    );
    expect(
      find.ancestor(
        of: galleryRemove,
        matching: find.byWidgetPredicate((widget) =>
            widget is Semantics &&
            widget.properties.label == galleryRemoveLabel,),
      ),
      findsOneWidget,
    );

    await tester.tap(find.byKey(const Key('platform-gallery-upload')));
    await tester.pump();
    expect(find.text('https://cdn.example.com/platform-cover.jpg'),
        findsOneWidget,);
    expect(find.text('https://cdn.example.com/platform-gallery.jpg'),
        findsOneWidget,);
    expect(find.text('图片上传失败，请重试'), findsOneWidget);
  },);

  testWidgets(
    'platform application composes six structured project detail fields into canonical HTML',
    (tester) async {
      _useLargeSurface(tester);
      final repository = _ProjectRequestRepository();
      await tester.pumpWidget(_app(PlatformProjectRequestPage(
        repository: repository,
        context: _doctorContext,
      ),),);
      await tester.pumpAndSettle();

      for (final key in const [
        'platform-name',
        'platform-reference-price',
        'platform-slogan',
        'platform-sales-count',
        'platform-category',
        'platform-description',
        'platform-tags',
        'platform-category-tags',
        'platform-notes',
      ]) {
        expect(find.byKey(Key(key)), findsOneWidget);
      }
      for (final key in const [
        'platform-detail-content',
        'platform-detail-suitable',
        'platform-detail-contraindications',
        'platform-detail-recovery',
        'platform-detail-highlights',
        'platform-detail-risks',
      ]) {
        expect(find.byKey(Key(key)), findsOneWidget);
        expect(tester.widget<TextField>(find.byKey(Key(key))).maxLines,
            greaterThan(1),);
      }
      expect(find.text('作用原理'), findsOneWidget);
      expect(find.text('适合人群'), findsOneWidget);
      expect(find.text('禁忌人群'), findsOneWidget);
      expect(find.text('恢复周期'), findsOneWidget);
      expect(find.text('项目亮点'), findsOneWidget);
      expect(find.text('潜在风险及副作用'), findsOneWidget);
      expect(find.textContaining('标题不可修改'), findsNothing);

      const values = {
        'platform-name': 'Hydrating Facial',
        'platform-reference-price': '320',
        'platform-sales-count': '0',
        'platform-category': 'Skin',
        'platform-description': 'Clinic description',
        'platform-detail-content': 'A&B <设备>\n改善方向',
        'platform-detail-suitable': '干燥、缺水肌肤',
        'platform-detail-contraindications': '孕期及感染期人群',
        'platform-detail-recovery': '通常 1–3 天恢复',
        'platform-detail-highlights': '方案可按面诊结果调整',
        'platform-detail-risks': '短期泛红；异常时及时复诊',
      };
      for (final entry in values.entries) {
        await tester.enterText(find.byKey(Key(entry.key)), entry.value);
      }

      await _submit(tester, const Key('platform-submit'));

      expect(repository.platformSubmissions, hasLength(1));
      expect(
        repository.platformSubmissions.single.detailContent,
        '<h1>作用原理</h1>\n'
        '<p>A&amp;B &lt;设备&gt;<br>改善方向</p>\n'
        '<h1>适合人群</h1>\n'
        '<p>干燥、缺水肌肤</p>\n'
        '<h1>禁忌人群</h1>\n'
        '<p>孕期及感染期人群</p>\n'
        '<h1>恢复周期</h1>\n'
        '<p>通常 1–3 天恢复</p>\n'
        '<h1>项目亮点</h1>\n'
        '<p>方案可按面诊结果调整</p>\n'
        '<h1>潜在风险及副作用</h1>\n'
        '<p>实际效果和恢复情况因人而异，具体方案需由专业医生面诊后确定。</p>\n'
        '<p>短期泛红；异常时及时复诊</p>',
      );
    },
  );

  testWidgets(
    'institution application loads inherited project details into editable fields and keeps one USD price',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository();
    final uploads = [
      'https://cdn.example.com/institution-cover.jpg',
      'https://cdn.example.com/institution-gallery.jpg',
    ];
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    ),),);
    await tester.pumpAndSettle();

    expect(find.textContaining('doctor-1'), findsOneWidget);
    expect(find.textContaining('唯一申请医生'), findsOneWidget);
    expect(find.textContaining('选择医生'), findsNothing);
    expect(find.textContaining('多医生'), findsNothing);

    await _choose(tester, const Key('institution-project'), 'Hydrating Facial',);
    expect(find.byKey(const Key('institution-inheritance-preview')), findsNothing);
    const inheritedValues = {
      'institution-name': 'Hydrating Facial',
      'institution-category': 'Skin',
      'institution-description': 'Inherited description',
      'institution-tags': 'hydration,gentle',
      'institution-slogan': 'Glow naturally',
      'institution-detail-content': 'Inherited plain detail',
      'institution-sales-count': '18',
    };
    for (final entry in inheritedValues.entries) {
      final field = tester.widget<TextField>(find.byKey(Key(entry.key)));
      expect(field.controller!.text, entry.value,
          reason: '${entry.key} must contain an editable inherited value',);
      expect(field.readOnly, isFalse);
      if (entry.key == 'institution-detail-content') {
        expect(
          field.decoration?.hintText,
          '请填写项目的治疗原理、作用层次、主要材料或设备，以及预期改善方向。',
        );
      } else {
        expect(field.decoration?.hintText, isNull);
      }
    }
    for (final key in const [
      'institution-detail-suitable',
      'institution-detail-contraindications',
      'institution-detail-recovery',
      'institution-detail-highlights',
      'institution-detail-risks',
    ]) {
      final field = tester.widget<TextField>(find.byKey(Key(key)));
      expect(field.controller!.text, isEmpty);
      expect(field.maxLines, greaterThan(1));
    }
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('institution-name')))
          .decoration
          ?.labelText,
      '项目名称',
    );
    expect(find.text('https://cdn.example.com/inherited-cover.jpg'), findsOneWidget);
    expect(find.text('https://cdn.example.com/inherited-one.jpg'), findsOneWidget);
    expect(find.text('https://cdn.example.com/inherited-two.jpg'), findsOneWidget);
    expect(find.textContaining('继承值'), findsNothing);
    expect(find.textContaining('可选覆盖'), findsNothing);
    expect(
      find.byTooltip(
        '移除封面图 1：https://cdn.example.com/inherited-cover.jpg',
      ),
      findsOneWidget,
    );
    expect(
      find.byTooltip(
        '移除项目图片 1：https://cdn.example.com/inherited-one.jpg',
      ),
      findsOneWidget,
    );
    await tester.enterText(
      find.byKey(const Key('institution-name')),
      'Doctor Hydrating Facial',
    );
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('institution-name')))
          .controller
          ?.text,
      'Doctor Hydrating Facial',
    );
    final priceField =
        tester.widget<TextField>(find.byKey(const Key('institution-price')));
    expect(priceField.decoration?.labelText, '医生项目价格（USD）');
    expect(priceField.decoration?.hintText, isNull);
    expect(
      tester
          .widget<TextField>(
            find.byKey(const Key('institution-detail-content')),
          )
          .maxLines,
      greaterThan(1),
    );
    expect(find.byKey(const Key('institution-travel-ground-service-fee')), findsOneWidget,);

    final orderedFinders = <Finder>[
      _dropdown('institution-id'),
      _dropdown('institution-project'),
      find.byKey(const Key('institution-applicant-notice')),
      find.byKey(const Key('institution-name')),
      find.byKey(const Key('institution-category')),
      find.byKey(const Key('institution-description')),
      find.byKey(const Key('institution-tags')),
      find.byKey(const Key('institution-slogan')),
      find.byKey(const Key('institution-detail-content')),
      find.byKey(const Key('institution-detail-suitable')),
      find.byKey(const Key('institution-detail-contraindications')),
      find.byKey(const Key('institution-detail-recovery')),
      find.byKey(const Key('institution-detail-highlights')),
      find.byKey(const Key('institution-detail-risks')),
      find.byKey(const Key('institution-price')),
      find.byKey(const Key('institution-travel-ground-service-fee')),
      find.byKey(const Key('institution-cover-upload')),
      find.byKey(const Key('institution-gallery-upload')),
      find.byKey(const Key('institution-sales-count')),
      find.byKey(const Key('institution-is-active')),
      find.byKey(const Key('institution-notes')),
    ];
    final tops =
        orderedFinders.map(tester.getTopLeft).map((p) => p.dy).toList();
    expect(tops, orderedEquals([...tops]..sort()));
    expect(find.textContaining('评分'), findsNothing);
    expect(find.textContaining('评价数'), findsNothing);
    expect(find.textContaining('选择医生'), findsNothing);
    expect(find.textContaining('多医生'), findsNothing);

    await tester.enterText(
        find.byKey(const Key('institution-price')),
        '799.99',);
    await tester.pump();
      expect(find.textContaining('USD 82.00'), findsOneWidget);

      await tester.tap(find.byKey(const Key('institution-cover-upload')));
    await tester.pump();
      expect(
        find.text('https://cdn.example.com/inherited-cover.jpg'),
        findsNothing,
      );
      await tester.tap(find.byKey(const Key('institution-gallery-upload')));
      await tester.pump();
    expect(find.text('https://cdn.example.com/institution-cover.jpg'), findsOneWidget,);
      expect(
        find.text('https://cdn.example.com/institution-gallery.jpg'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'switching the platform project replaces inherited editable values but keeps doctor-only inputs',
    (tester) async {
      _useLargeSurface(tester);
      final repository = _ProjectRequestRepository();
      repository.managementProjects = [
        ...repository.managementProjects,
        const ManagementProjectOption(
          id: 'project-2',
          name: 'Laser Toning',
          category: 'Laser',
          description: 'Second inherited description',
          tags: 'laser,toning',
          categoryTags: 'laser,skin',
          coverImage: 'https://cdn.example.com/second-cover.jpg',
          referencePrice: 699,
          currency: 'USD',
          slogan: 'Clear and bright',
          detailContent: '<h1>作用原理</h1>\n'
              '<p>Second inherited detail &#999999999999999999;</p>\n'
              '<h1>适合人群</h1><p>Second suitable group</p>\n'
              '<h1>禁忌人群</h1><p>Second contraindications</p>\n'
              '<h1>恢复周期</h1><p>Second recovery</p>\n'
              '<h1>项目亮点</h1><p>Second highlights</p>\n'
              '<h1>Potential risks and side effects</h1>\n'
              '<p>Results and recovery vary by patient. A qualified doctor must confirm the final treatment plan after an in-person assessment.</p>\n'
              '<p>Second risks</p>',
          images: ['https://cdn.example.com/second-gallery.jpg'],
          salesCount: 9,
        ),
      ];

      await tester.pumpWidget(
        _app(
          InstitutionProjectRequestsPage(
            repository: repository,
            context: _doctorContext,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await _choose(
        tester,
        const Key('institution-project'),
        'Hydrating Facial',
      );
      await tester.enterText(
        find.byKey(const Key('institution-name')),
        'Temporary doctor edit',
      );
      await tester.enterText(
        find.byKey(const Key('institution-price')),
        '799.99',
      );
      await tester.ensureVisible(_dropdown('institution-project'));
      await tester.pump();

      await _choose(
        tester,
        const Key('institution-project'),
        'Laser Toning',
      );

      expect(_dropdownValue(tester, 'institution-project'), 'project-2');
      expect(_text(tester, 'institution-name'), 'Laser Toning');
      expect(_text(tester, 'institution-category'), 'Laser');
      expect(
        _text(tester, 'institution-description'),
        'Second inherited description',
      );
      expect(_text(tester, 'institution-tags'), 'laser,toning');
      expect(_text(tester, 'institution-slogan'), 'Clear and bright');
      expect(
        _text(tester, 'institution-detail-content'),
        'Second inherited detail &#999999999999999999;',
      );
      expect(
        _text(tester, 'institution-detail-suitable'),
        'Second suitable group',
      );
      expect(
        _text(tester, 'institution-detail-contraindications'),
        'Second contraindications',
      );
      expect(
        _text(tester, 'institution-detail-recovery'),
        'Second recovery',
      );
      expect(
        _text(tester, 'institution-detail-highlights'),
        'Second highlights',
      );
      expect(_text(tester, 'institution-detail-risks'), 'Second risks');
      expect(tester.takeException(), isNull);
      expect(_text(tester, 'institution-sales-count'), '9');
      expect(_text(tester, 'institution-price'), '799.99');
      expect(find.text('https://cdn.example.com/second-cover.jpg'), findsOneWidget);
      expect(find.text('https://cdn.example.com/second-gallery.jpg'), findsOneWidget);
      expect(
        find.text('https://cdn.example.com/inherited-cover.jpg'),
        findsNothing,
      );
      expect(
        find.text('https://cdn.example.com/inherited-one.jpg'),
        findsNothing,
      );
      expect(
        find.text('https://cdn.example.com/inherited-two.jpg'),
        findsNothing,
      );
    },
  );

  testWidgets('institution application exposes one USD price and travel fee', (
    tester,
  ) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository()..formPlatformRate = 40;

    await tester.pumpWidget(
      _app(
        InstitutionProjectRequestsPage(
          repository: repository,
          context: _doctorContext,
        ),
      ),
    );
    await tester.pumpAndSettle();

    final price = tester.widget<TextField>(find.byKey(const Key('institution-price')),);
    expect(price.decoration?.labelText, '医生项目价格（USD）');
    expect(find.byKey(const Key('institution-original-price')), findsNothing);
    expect(_dropdown('institution-currency'), findsNothing);
    expect(find.byKey(const Key('institution-consultation-fee')), findsNothing);
    expect(find.byKey(const Key('institution-consultant-rate')), findsNothing);
    expect(find.byKey(const Key('institution-rate')), findsNothing);
    expect(find.byKey(const Key('institution-platform-rate')), findsNothing);
    expect(find.byKey(const Key('institution-doctor-rate')), findsNothing);
    await tester.enterText(find.byKey(const Key('institution-price')),
      '799.99',);
    await tester.pump();
    expect(find.byKey(const Key('institution-travel-ground-service-fee')),
        findsOneWidget,);
    expect(find.textContaining('USD 320.00'),
        findsOneWidget);
  });

  testWidgets('institution application submits editable inherited project details and hidden compatibility defaults',
      (tester,) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository()..formPlatformRate = 40;
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
    ),),);
    await tester.pumpAndSettle();
    await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
    await _choose(tester, const Key('institution-project'), 'Hydrating Facial');
    await tester.enterText(
      find.byKey(const Key('institution-price')),
      '799.99',
    );

    await _submit(
      tester, const Key('institution-submit'));

    expect(repository.institutionSubmissions, hasLength(1));
    final body = repository.institutionSubmissions.single.toJson();
    expect(body['name'], 'Hydrating Facial');
    expect(body['category'], 'Skin');
    expect(body['description'], 'Inherited description');
    expect(body['tags'], ['hydration', 'gentle']);
    expect(body['slogan'], 'Glow naturally');
    expect(body['detailContent'], 'Inherited plain detail');
    expect(body['coverImage'], 'https://cdn.example.com/inherited-cover.jpg');
    expect(body['images'], [
      'https://cdn.example.com/inherited-one.jpg',
      'https://cdn.example.com/inherited-two.jpg',
    ]);
    expect(body['salesCount'], 18);
    expect(body['price'], 799.99);
    expect(body['currency'], 'USD');
    expect(body['originalPrice'], isNull);
    expect(body['consultationFee'], 0);
    expect(body['commissionRate'], 0);
    expect(body['institutionRate'], 0);
  });

  testWidgets('join application loads the rate and submits one doctor price', (
    tester,
  ) async {
    _useLargeSurface(tester);
    final repository = _JoinProjectRepository();
    await tester.pumpWidget(
      _app(
        InstitutionProjectJoinRequestsPage(
          repository: repository,
          context: _doctorContext,
    ),
      ),
    );

    await tester.pumpAndSettle();

    expect(repository.formConfigLoads, 1);
    await _choose(
      tester,
      const Key('join-institution-project'),
      'Hydrating Facial',
    );
    await tester.enterText(
      find.byKey(const Key('join-service-description')),
      'Service details',
    );
    await tester.enterText(
      find.byKey(const Key('join-project-price')),
      '799.99',
    );
    await tester.pump();
    expect(
      find.byKey(const Key('join-travel-ground-service-fee')),
      findsOneWidget,
    );
    expect(find.textContaining('USD 320.00'), findsOneWidget);

    await _submit(tester, const Key('join-submit'));

    expect(repository.submissions, hasLength(1));
    expect(repository.submissions.single.toJson(), {
      'requestType': 'JOIN',
      'institutionProjectId': 'ip-1',
      'serviceDescription': 'Service details',
      'priceSuggestion': 799.99,
      'notes': '',
    });
  });

  testWidgets(
    'institution price preview rejects an oversized paste without throwing or submitting',
    (tester) async {
      _useLargeSurface(tester);
      final repository = _ProjectRequestRepository();
      await tester.pumpWidget(
        _app(
          InstitutionProjectRequestsPage(
            repository: repository,
            context: _doctorContext,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
      await _choose(
        tester,
        const Key('institution-project'),
        'Hydrating Facial',
      );
      await _fillInstitutionDraft(
        tester,
        price:
      '999999999999999999999',
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(find.byKey(const Key('institution-travel-ground-service-fee')),
        findsOneWidget,
      );
      expect(find.text('旅游地接服务费（10.25%）：-'), findsOneWidget);
    await _submit(tester, const Key('institution-submit'));
    expect(find.text('医生项目价格必须能按当前比例计算出至少 USD 0.01 的旅游地接服务费'), findsOneWidget,);
    expect(repository.institutionSubmissions, isEmpty);
  },);

  testWidgets(
    'institution doctor price enforces cent precision and minimum payable fee',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository()..formPlatformRate = 40;
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
    ),),);
    await tester.pumpAndSettle();
    await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
    await _choose(tester, const Key('institution-project'), 'Hydrating Facial',);
    await _fillInstitutionDraft(
      tester,
      price: '0.01',
    );

    for (final rejected in const ['0.01', '1.001']) {
      await tester.enterText(
        find.byKey(const Key('institution-price')),
          rejected,
      );
      await _submit( tester, const Key('institution-submit'));
      expect(repository.institutionSubmissions, isEmpty);
    }

    await tester.enterText(
      find.byKey(const Key('institution-price')),
        '0.02',
    );
    await tester.pump();
    final feePreview = tester.widget<Text>(
      find.byKey(const Key('institution-travel-ground-service-fee')),
    );
    expect(feePreview.data, contains('USD 0.01'));

    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(1));
    expect(repository.institutionSubmissions.single.price, 0.02);
  },);

  testWidgets(
      'platform draft blocks malformed values, survives submit and refresh failures, and clears only after both succeed',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository();
    final uploads = [
      'https://cdn.example.com/retained-cover.jpg',
      'https://cdn.example.com/retained-gallery.jpg',
    ];
    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      key: const ValueKey('applicant-platform'),
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    ),),);
    await tester.pumpAndSettle();
    await _fillPlatformDraft(tester, referencePrice: '1.234');
    await tester.tap(find.byKey(const Key('platform-cover-upload')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('platform-gallery-upload')));
    await tester.pump();

    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, isEmpty);
    expect(_text(tester, 'platform-name'), 'Retained platform name');

    await tester.enterText(
        find.byKey(const Key('platform-reference-price')), '199.99',);
    repository.platformSubmitError = true;
    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, hasLength(1));
    expect(repository.platformSubmissions.single.toJson()['currency'], 'USD');
    expect(_text(tester, 'platform-notes'), 'Retained notes');
    expect(find.text('https://cdn.example.com/retained-cover.jpg'),
        findsOneWidget,);

    repository.platformSubmitError = false;
    repository.failNextRequestList = true;
    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, hasLength(2));
    expect(_text(tester, 'platform-name'), 'Retained platform name');
    expect(find.text('https://cdn.example.com/retained-gallery.jpg'),
        findsOneWidget,);

    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, hasLength(3));
    expect(_text(tester, 'platform-name'), isEmpty);
    expect(_text(tester, 'platform-notes'), isEmpty);
    expect(
        find.text('https://cdn.example.com/retained-cover.jpg'), findsNothing,);
    expect(find.text('https://cdn.example.com/retained-gallery.jpg'),
        findsNothing,);
  },);

  testWidgets(
    'institution draft blocks price precision, retains every visible override on failures, and never submits platform or doctor rate',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository();
    final uploads = [
      'https://cdn.example.com/clinic-cover.jpg',
      'https://cdn.example.com/clinic-gallery.jpg',
    ];
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('target-legal-institution'),
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    ),),);
    await tester.pumpAndSettle();
    await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
    await _choose(tester, const Key('institution-project'), 'Hydrating Facial',);
    await _fillInstitutionDraft(tester,
        price: '799.999',);
    await tester.tap(find.byKey(const Key('institution-cover-upload')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('institution-gallery-upload')));
    await tester.pump();

    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, isEmpty);
    await tester.enterText(
        find.byKey(const Key('institution-price')), '799.99',);repository.institutionSubmitError = true;
    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(1));
    expect(_text(tester, 'institution-name'), 'Clinic override');
    expect(find.text('https://cdn.example.com/clinic-gallery.jpg'),
        findsOneWidget,);

    repository.institutionSubmitError = false;
    repository.failNextRequestList = true;
    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(2));
    expect(
        _text(tester, 'institution-detail-content'), 'Override plain detail',);
    expect(find.textContaining('USD 82.00'), findsOneWidget);

    final body = repository.institutionSubmissions.last.toJson();
    expect(body, isNot(contains('platformRate')));
    expect(body, isNot(contains('doctorRate')));
    expect(body, isNot(contains('doctorId')));

    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(3));
    expect(_text(tester, 'institution-name'), isEmpty);
    expect(_text(tester, 'institution-price'), isEmpty);
    expect(find.text('https://cdn.example.com/clinic-cover.jpg'), findsNothing,);
  },);

  testWidgets(
      'platform and institution creation submissions synchronously block same-frame re-entry',
      (tester) async {
    _useLargeSurface(tester);
    final platformGate = Completer<void>();
    final platformRepository = _ProjectRequestRepository()
      ..platformSubmitGate = platformGate;

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      repository: platformRepository,
      context: _doctorContext,
    ),),);
    await tester.pumpAndSettle();
    await _fillPlatformDraft(tester, referencePrice: '199.99');
    final platformSubmit = tester
        .widget<FilledButton>(find.byKey(const Key('platform-submit')))
        .onPressed!;

    platformSubmit();
    platformSubmit();
    await tester.pump();

    expect(platformRepository.platformSubmissions, hasLength(1));
    platformGate.complete();
    await tester.pumpAndSettle();

    final institutionGate = Completer<void>();
    final institutionRepository = _ProjectRequestRepository()
      ..institutionSubmitGate = institutionGate;
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: institutionRepository,
      context: _doctorContext,
    ),),);
    await tester.pumpAndSettle();
    await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
    await _choose(tester, const Key('institution-project'), 'Hydrating Facial',);
    await _fillInstitutionDraft(
      tester,
      price: '799.99',
    );
    final institutionSubmit = tester
        .widget<FilledButton>(find.byKey(const Key('institution-submit')))
        .onPressed!;

    institutionSubmit();
    institutionSubmit();
    await tester.pump();

    expect(institutionRepository.institutionSubmissions, hasLength(1));
    institutionGate.complete();
    await tester.pumpAndSettle();
  },);

  testWidgets(
      'platform submission conflict refreshes the latest own requests without replaying or clearing the draft',
      (tester) async {
    _useLargeSurface(tester);
    final uploads = [
      'https://cdn.example.com/conflict-platform-cover.jpg',
      'https://cdn.example.com/conflict-platform-gallery.jpg',
    ];
    final repository = _ProjectRequestRepository()
      ..platformSubmitException =
          const ApiException(message: 'conflict', httpStatus: 409,);
    repository.onPlatformSubmit = () {
      repository.requests = [
        _request(
          id: 'platform-conflict-latest',
          type: 'PLATFORM',
          doctorId: 'doctor-1',
        ),
      ];
    };

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    ),),);
    await tester.pumpAndSettle();
    await _preparePlatformConflictDraft(tester);
    expect(repository.requestListLoads, 1);

    await _submit(tester, const Key('platform-submit'));

    expect(repository.platformSubmissions, hasLength(1),
        reason: 'a 409 must never auto-replay the POST',);
    expect(repository.requestListLoads, 2);
    expect(repository.institutionOptionLoads, 0);
    expect(repository.managementProjectLoads, 0);
    expect(repository.formConfigLoads, 0);
    _expectPlatformConflictDraftRetained(tester);
    expect(
      find.byKey(const Key('professional-request-platform-conflict-latest')),
      findsOneWidget,
    );
    expect(
      find.text('提交冲突，申请列表已刷新；草稿已保留，请核对最新申请后再决定是否重新提交'),
      findsOneWidget
    );
  },);

  testWidgets(
      'platform submission conflict keeps its draft and gives recovery guidance when refresh fails',
      (tester) async {
    _useLargeSurface(tester);
    final uploads = [
      'https://cdn.example.com/conflict-platform-cover.jpg',
      'https://cdn.example.com/conflict-platform-gallery.jpg',
    ];
    final repository = _ProjectRequestRepository()
      ..platformSubmitException =
          const ApiException(message: 'conflict', httpStatus: 409,);

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      key: const ValueKey('platform-conflict-refresh-failure'),
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    ),),);
    await tester.pumpAndSettle();
    await _preparePlatformConflictDraft(tester);
    repository.failNextRequestList = true;

    await _submit(tester, const Key('platform-submit'));

    expect(repository.platformSubmissions, hasLength(1));
    expect(repository.requestListLoads, 2);
    _expectPlatformConflictDraftRetained(tester);
    expect(
      find.text('提交冲突，申请列表刷新失败；草稿已保留，请手动刷新后再提交'),
      findsOneWidget
    );
  },);

  testWidgets(
      'institution submission conflict refreshes authorized form data without replaying or clearing the draft',
      (tester) async {
    _useLargeSurface(tester);
    final uploads = [
      'https://cdn.example.com/conflict-clinic-cover.jpg',
      'https://cdn.example.com/conflict-clinic-gallery.jpg',
    ];
    final repository = _ProjectRequestRepository()
      ..institutionSubmitException =
          const ApiException(message: 'conflict', httpStatus: 409,);
    repository.onInstitutionSubmit = () {
      repository
        ..requests = [
          _request(
            id: 'institution-conflict-latest',
            type: 'INSTITUTION',
            doctorId: 'doctor-1',
            institutionId: 'inst-1',
            platformRate: 12.5,
            doctorRate: 32.75,
          ),
        ]
        ..formPlatformRate = 12.5
        ..institutionOptions = const [
          InstitutionOption(id: 'inst-1', name: 'Refreshed Joysong Clinic'),
        ]
        ..managementProjects = const [
          ManagementProjectOption(
            id: 'project-1',
            name: 'Refreshed Hydrating Facial',
            category: 'Refreshed Skin',
            description: 'Refreshed inherited description',
            tags: 'refreshed,hydration',
            categoryTags: 'refreshed,facial',
            coverImage: 'https://cdn.example.com/refreshed-cover.jpg',
            referencePrice: 777.77,
            currency: 'USD',
            slogan: 'Refreshed glow',
            detailContent: 'Refreshed inherited detail',
            images: ['https://cdn.example.com/refreshed-gallery.jpg'],
            salesCount: 27,
          ),
        ];
    };

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    ),),);
    await tester.pumpAndSettle();
    await _prepareInstitutionConflictDraft(tester);
    expect(repository.requestListLoads, 1);
    expect(repository.institutionOptionLoads, 1);
    expect(repository.managementProjectLoads, 1);
    expect(repository.formConfigLoads, 1);

    await _submit(tester, const Key('institution-submit'));

    expect(repository.institutionSubmissions, hasLength(1),
        reason: 'a 409 must never auto-replay the POST',);
    expect(repository.requestListLoads, 2);
    expect(repository.institutionOptionLoads, 2);
    expect(repository.managementProjectLoads, 2);
    expect(repository.formConfigLoads, 2);
    _expectInstitutionConflictDraftRetained(tester);
    expect(find.text('Refreshed Joysong Clinic'), findsOneWidget);
    expect(find.text('Refreshed Hydrating Facial'), findsWidgets);
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('institution-name')))
          .decoration
          ?.hintText,
      isNull,
    );
    expect(find.text('https://cdn.example.com/refreshed-cover.jpg'), findsNothing);
    expect(find.text('https://cdn.example.com/refreshed-gallery.jpg'), findsNothing);
    expect(
      find.textContaining('USD 100.00'), findsOneWidget);
    expect(
      find.byKey(
        const Key('professional-request-institution-conflict-latest'),
      ),
      findsNothing,
    );
    expect(
      find.text('提交冲突，申请、项目目录与价格配置已刷新；草稿已保留，请核对后重新提交'),
      findsOneWidget
    );
  },);

  testWidgets(
      'institution submission conflict refreshes management context first and clears a relationship-revoked target',
      (tester) async {
    _useLargeSurface(tester);
    final uploads = [
      'https://cdn.example.com/conflict-clinic-cover.jpg',
      'https://cdn.example.com/conflict-clinic-gallery.jpg',
    ];
    final repository = _ProjectRequestRepository()
      ..institutionSubmitException =
          const ApiException(message: 'conflict', httpStatus: 409,);
    repository.onInstitutionSubmit = () {
      repository
        ..managementContext = _doctorWithoutInstitutionsContext
        ..formPlatformRate = 12.5;
    };

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    ),),);
    await tester.pumpAndSettle();
    await _prepareInstitutionConflictDraft(tester);
    repository.calls.clear();

    await _submit(tester, const Key('institution-submit'));

    expect(repository.institutionSubmissions, hasLength(1),
        reason: 'the conflicted POST must not be replayed',);
    expect(repository.managementContextLoads, 1);
    expect(repository.calls, [
      'submit-institution',
      'management-context',
      'requests',
      'institution-options',
      'management-projects',
      'form-config',
    ]);
    expect(repository.institutionOptions.single.id, 'inst-1',
        reason: 'the public directory deliberately still contains the target',);
    expect(_dropdownValue(tester, 'institution-id'), isNull,
        reason: 'the refreshed doctor relationship, not the public directory, '
            'must authorize the selection',);
    expect(_dropdownValue(tester, 'institution-project'), 'project-1');
    _expectInstitutionConflictDraftValuesRetained(tester);
    expect(find.textContaining('USD 100.00'), findsOneWidget);

    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(1));
    expect(find.text('请选择机构和平台项目，并填写有效的医生项目价格与销量'), findsOneWidget);
  },);

  testWidgets(
      'institution submission conflict reports stale authorization and skips form refresh when management context fails',
      (tester) async {
    _useLargeSurface(tester);
    final uploads = [
      'https://cdn.example.com/conflict-clinic-cover.jpg',
      'https://cdn.example.com/conflict-clinic-gallery.jpg',
    ];
    final repository = _ProjectRequestRepository()
      ..institutionSubmitException =
          const ApiException(message: 'conflict', httpStatus: 409,);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    ),),);
    await tester.pumpAndSettle();
    await _prepareInstitutionConflictDraft(tester);
    repository
      ..calls.clear()
      ..failNextManagementContext = true;

    await _submit(tester, const Key('institution-submit'));

    expect(repository.institutionSubmissions, hasLength(1));
    expect(repository.managementContextLoads, 1);
    expect(repository.requestListLoads, 1);
    expect(repository.institutionOptionLoads, 1);
    expect(repository.managementProjectLoads, 1);
    expect(repository.formConfigLoads, 1);
    expect(repository.calls, ['submit-institution', 'management-context']);
    _expectInstitutionConflictDraftRetained(tester);
    expect(find.textContaining('权限刷新失败'), findsOneWidget);
    expect(find.textContaining('可能已过期'), findsOneWidget);
    expect(find.textContaining('已刷新；草稿已保留'), findsNothing,
        reason: 'a failed context refresh must not claim fresh authorization',);
  },);

  testWidgets(
      'institution submission conflict clears only targets removed from refreshed authorized catalogs and requires reselection',
      (tester) async {
    _useLargeSurface(tester);
    for (final scenario in const [
      (
        name: 'institution-removed',
        removeInstitution: true,
        removeProject: false,
        expectedInstitution: null,
        expectedProject: 'project-1',
      ),
      (
        name: 'project-removed',
        removeInstitution: false,
        removeProject: true,
        expectedInstitution: 'inst-1',
        expectedProject: null,
      ),
      (
        name: 'both-removed',
        removeInstitution: true,
        removeProject: true,
        expectedInstitution: null,
        expectedProject: null,
      ),
    ]) {
      final uploads = [
        'https://cdn.example.com/conflict-clinic-cover.jpg',
        'https://cdn.example.com/conflict-clinic-gallery.jpg',
      ];
      final repository = _ProjectRequestRepository()
        ..institutionSubmitException =
            const ApiException(message: 'conflict', httpStatus: 409,);
      repository.onInstitutionSubmit = () {
        repository
          ..formPlatformRate = 12.5
          ..institutionOptions = scenario.removeInstitution
              ? const [
                  InstitutionOption(
                    id: 'inst-2',
                    name: 'Alternative Authorized Clinic',
                  ),
                ]
              : repository.institutionOptions
          ..managementProjects = scenario.removeProject
              ? const [
                  ManagementProjectOption(
                    id: 'project-2',
                    name: 'Alternative Platform Project',
                    category: 'Alternative category',
                    description: 'Alternative description',
                    tags: 'alternative',
                    categoryTags: 'alternative',
                    coverImage: '',
                    referencePrice: 500,
                    currency: 'CNY',
                    slogan: '',
                    detailContent: null,
                    images: [],
                    salesCount: 0,
                  ),
                ]
              : repository.managementProjects;
      };

      await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
        key: ValueKey('stale-target-${scenario.name}'),
        repository: repository,
        context: _multiInstitutionDoctorContext,
        pickAndUploadImage: () async => uploads.removeAt(0),
      ),),);
      await tester.pumpAndSettle();
      await _prepareInstitutionConflictDraft(tester);

      await _submit(tester, const Key('institution-submit'));

      expect(tester.takeException(), isNull,
          reason:
              '${scenario.name} must render without a stale-value assertion',);
      expect(repository.institutionSubmissions, hasLength(1));
      expect(_dropdownValue(tester, 'institution-id'),
          scenario.expectedInstitution,);
      expect(_dropdownValue(tester, 'institution-project'),
          scenario.expectedProject,);
      _expectInstitutionConflictDraftValuesRetained(tester);
      expect(find.textContaining('USD 100.00'), findsOneWidget);
      expect(find.text('提交冲突，申请、项目目录与价格配置已刷新；草稿已保留，请核对后重新提交'), findsOneWidget,
      );

      await _submit(tester, const Key('institution-submit'));
      expect(repository.institutionSubmissions, hasLength(1),
          reason: '${scenario.name} must require a new valid target selection',);
      expect(find.text('请选择机构和平台项目，并填写有效的医生项目价格与销量'), findsOneWidget);
      _expectInstitutionConflictDraftValuesRetained(tester);
    }
  },);

  testWidgets(
      'institution submission conflict keeps selections and gives recovery guidance when form refresh fails',
      (tester) async {
    _useLargeSurface(tester);
    final uploads = [
      'https://cdn.example.com/conflict-clinic-cover.jpg',
      'https://cdn.example.com/conflict-clinic-gallery.jpg',
    ];
    final repository = _ProjectRequestRepository()
      ..institutionSubmitException =
          const ApiException(message: 'conflict', httpStatus: 409,);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('institution-conflict-refresh-failure'),
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    ),),);
    await tester.pumpAndSettle();
    await _prepareInstitutionConflictDraft(tester);
    repository.failNextRequestList = true;

    await _submit(tester, const Key('institution-submit'));

    expect(repository.institutionSubmissions, hasLength(1));
    expect(repository.requestListLoads, 2);
    expect(repository.institutionOptionLoads, 2);
    expect(repository.managementProjectLoads, 2);
    expect(repository.formConfigLoads, 2);
    _expectInstitutionConflictDraftRetained(tester);
    expect(
      find.text('提交冲突，申请、项目目录或价格配置刷新失败；草稿已保留，请手动刷新后再提交'),
      findsOneWidget,
    );
    final feePreview = tester.widget<Text>(
      find.byKey(const Key('institution-travel-ground-service-fee')),
    );
    expect(feePreview.data, endsWith('：-'));
    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(1),
        reason: 'a stale fee rate must never be reused after refresh fails');
  },);

  testWidgets(
      'platform submit ignores a successful refresh completed after the page is disposed',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository();
    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      repository: repository,
      context: _doctorContext,
    ),),);
    await tester.pumpAndSettle();
    await _fillPlatformDraft(tester, referencePrice: '199.99');
    final refresh = Completer<List<ProfessionalProjectRequest>>();
    final refreshStarted = Completer<void>();
    repository.nextRequestList = refresh;
    repository.requestListStarted = refreshStarted;
    final nameController = tester
        .widget<TextField>(find.byKey(const Key('platform-name')))
        .controller!;

    await tester.tap(find.byKey(const Key('platform-submit')));
    await tester.pump();
    await refreshStarted.future;
    expect(repository.platformSubmissions, hasLength(1));
    expect(nameController.text, 'Retained platform name');

    await tester.pumpWidget(_app(const SizedBox.shrink()));
    refresh.complete(const []);
    await tester.pump();
    await tester.pump();
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(nameController.text, 'Retained platform name',
        reason: 'a disposed form must not be cleared by a late refresh',);
  },);

  testWidgets(
      'malformed direct review rows show a warning and fail closed without review actions',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository()
      ..requests = [
        _malformedRequest(id: 'malformed-platform', type: 'PLATFORM'),
        _malformedRequest(id: 'malformed-institution', type: 'INSTITUTION'),
      ];

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    expect(
        find.byKey(const Key('malformed-review-snapshot-malformed-platform')),
        findsOneWidget,);
    expect(find.text('申请快照不完整，无法审核，请刷新后重试'), findsOneWidget);
    expect(find.byKey(const Key('review-creation-malformed-platform')),
        findsNothing,);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    expect(
        find.byKey(
            const Key('malformed-review-snapshot-malformed-institution'),),
        findsOneWidget,);
    expect(find.text('申请快照不完整，无法审核，请刷新后重试'), findsOneWidget);
    expect(find.byKey(const Key('review-creation-malformed-institution')),
        findsNothing,);
  },);

  testWidgets(
    'invalid hidden compatibility rates keep the request rejectable but not approvable',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository()
      ..requests = [
        _negativeDriftRequest(
          id: 'institution-negative-initial',
          projectName: 'Current live project',
        ),
      ];

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    expect(
        find.byKey(
            const Key('professional-request-institution-negative-initial'),),
        findsOneWidget,);
    expect(find.text('USD 799.50'), findsOneWidget);
    expect(
        find.text('旅游地接服务费：USD 799.50'), findsOneWidget);
      expect(
        find.byKey(const Key(
            'malformed-review-snapshot-institution-negative-initial'),),
        findsNothing,);

    await tester.tap(
        find.byKey(const Key('review-creation-institution-negative-initial')),);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('creation-review-approval-blocked')),
        findsOneWidget,);
    final decisionField = find.byKey(const Key('creation-review-decision'));
    final decision = tester.widget<DropdownButton<String>>(find.descendant(
      of: decisionField,
      matching: find.byType(DropdownButton<String>),
    ),);
    expect(decision.items!.map((item) => item.value), ['REJECTED']);
    expect(
      tester
          .widget<DropdownButtonFormField<String>>(decisionField)
          .initialValue,
      'REJECTED',
    );
    await tester.enterText(find.byKey(const Key('creation-review-note')),
        'Current compatibility values are invalid',);
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.institutionReviews, [
      (
        id: 'institution-negative-initial',
        decision: 'REJECTED',
        note: 'Current compatibility values are invalid',
      ),
    ]);
  },);

  testWidgets(
      'institution request without a name uses stable request id instead of the live project name as its title',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository()
      ..requests = [
        _request(
          id: 'institution-stable-title',
          type: 'INSTITUTION',
          doctorId: 'doctor-1',
          institutionId: 'inst-1',
          inheritName: true,
          projectName: 'Live project v1',
        ),
      ];

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('stable-title-v1'),
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    expect(find.text('institution-stable-title'), findsOneWidget);
    expect(find.text('Live project v1'), findsNothing);
    expect(find.text('平台项目: Live project v1'), findsOneWidget);

    repository.requests = [
      _request(
        id: 'institution-stable-title',
        type: 'INSTITUTION',
        doctorId: 'doctor-1',
        institutionId: 'inst-1',
        inheritName: true,
        projectName: 'Live project v2',
      ),
    ];
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('stable-title-v2'),
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    expect(find.text('institution-stable-title'), findsOneWidget);
    expect(find.text('Live project v2'), findsNothing);
    expect(find.text('平台项目: Live project v2'), findsOneWidget);
  },);

  testWidgets(
      'platform and institution creation review actions synchronously block re-entry',
      (tester) async {
    _useLargeSurface(tester);
    final platformGate = Completer<void>();
    final platformRepository = _ProjectRequestRepository()
      ..requests = [
        _request(id: 'platform-pending', type: 'PLATFORM', doctorId: 'doctor-1',),
      ]
      ..platformReviewGate = platformGate;
    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      repository: platformRepository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('review-creation-platform-pending')),);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(platformRepository.platformReviews, hasLength(1));
    expect(
      tester
          .widget<FilledButton>(
              find.byKey(const Key('review-creation-platform-pending')),)
          .onPressed,
      isNull,
    );
    await tester.tap(
      find.byKey(const Key('review-creation-platform-pending')),
      warnIfMissed: false,
    );
    await tester.pump();
    expect(find.byType(AlertDialog), findsNothing,
        reason: 'reject re-entry must not open while approval is pending',);
    expect(platformRepository.platformReviews, hasLength(1));
    platformGate.complete();
    await tester.pumpAndSettle();

    final institutionGate = Completer<void>();
    final institutionRepository = _ProjectRequestRepository()
      ..requests = [
        _request(
          id: 'institution-pending',
          type: 'INSTITUTION',
          doctorId: 'doctor-1',
          institutionId: 'inst-1',
        ),
      ]
      ..institutionReviewGate = institutionGate;
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: institutionRepository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    await tester
        .tap(find.byKey(const Key('review-creation-institution-pending')),);
    await tester.pumpAndSettle();
    await _selectReviewDecision(tester, 'REJECTED');
    await tester.enterText(
        find.byKey(const Key('creation-review-note')), 'Reject later',);
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(institutionRepository.institutionReviews, hasLength(1));
    expect(
      tester
          .widget<FilledButton>(
              find.byKey(const Key('review-creation-institution-pending')),)
          .onPressed,
      isNull,
    );
    await tester.tap(
      find.byKey(const Key('review-creation-institution-pending')),
      warnIfMissed: false,
    );
    await tester.pump();
    expect(find.byType(AlertDialog), findsNothing);
    expect(
        institutionRepository.institutionReviews.single.decision, 'REJECTED',);
    institutionGate.complete();
    await tester.pumpAndSettle();
  },);

  testWidgets(
      'review conflicts refresh current snapshots without submission-only loads or clearing its draft',
      (tester) async {
    _useLargeSurface(tester);
    final platformRepository = _ProjectRequestRepository()
      ..requests = [
        _request(
            id: 'platform-conflict', type: 'PLATFORM', doctorId: 'doctor-1',),
      ]
      ..platformReviewError =
          const ApiException(message: 'conflict', httpStatus: 409,);
    platformRepository.onPlatformReview = () {
      platformRepository.requests = [
        _request(
          id: 'platform-conflict',
          type: 'PLATFORM',
          doctorId: 'doctor-1',
          doctorName: 'Dr. Chen Updated',
          status: 'APPROVED',
        ),
      ];
    };
    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      repository: platformRepository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    await tester
        .tap(find.byKey(const Key('review-creation-platform-conflict')),);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(find.text('审核状态已变化，申请列表已刷新，请基于最新内容重试'), findsOneWidget);
    expect(find.text('当前医生名称：Dr. Chen Updated'), findsOneWidget);
    expect(find.byKey(const Key('review-creation-platform-conflict')),
        findsNothing,);

    const institutionPageKey = ValueKey('institution-conflict-page');
    final institutionRepository = _ProjectRequestRepository()
      ..requests = [
        _request(
          id: 'institution-conflict',
          type: 'INSTITUTION',
          doctorId: 'doctor-1',
          institutionId: 'inst-1',
        ),
      ]
      ..institutionReviewError =
          const ApiException(message: 'conflict', httpStatus: 409,);
    institutionRepository.onInstitutionReview = () {
      institutionRepository
        ..formPlatformRate = 100
        ..requests = [
          _request(
            id: 'institution-conflict',
            type: 'INSTITUTION',
            doctorId: 'doctor-1',
            institutionId: 'inst-1',
            platformRate: 100,
            doctorRate: -54.75,
          ),
        ];
    };
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: institutionPageKey,
      repository: institutionRepository,
      context: _doctorContext,
    ),),);
    await tester.pumpAndSettle();
    expect(institutionRepository.formConfigLoads, 1);
    await tester.enterText(
        find.byKey(const Key('institution-name')), 'Unsubmitted draft',);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: institutionPageKey,
      repository: institutionRepository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pump();
    await tester
        .tap(find.byKey(const Key('review-creation-institution-conflict')),);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(find.text('审核状态已变化，申请列表已刷新，请基于最新内容重试'), findsOneWidget);
    expect(find.text('USD 799.50'), findsOneWidget);
    expect(find.text('旅游地接服务费：USD 799.50'), findsOneWidget);
    expect(institutionRepository.formConfigLoads, 1);
    expect(institutionRepository.institutionOptionLoads, 1);
    expect(institutionRepository.managementProjectLoads, 1);

    await tester
        .tap(find.byKey(const Key('review-creation-institution-conflict')),);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('creation-review-approval-blocked')),
        findsOneWidget,);
    final refreshedDecision = tester.widget<DropdownButton<String>>(
      find.descendant(
        of: find.byKey(const Key('creation-review-decision')),
        matching: find.byType(DropdownButton<String>),
      ),
    );
    expect(refreshedDecision.items!.map((item) => item.value), ['REJECTED']);
    await tester.enterText(find.byKey(const Key('creation-review-note')),
        'Reject refreshed pricing',);
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(
      institutionRepository.institutionReviews.map((review) => review.decision,),
      ['APPROVED', 'REJECTED'],
    );

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: institutionPageKey,
      repository: institutionRepository,
      context: _doctorContext,
    ),),);
    await tester.pump();
    expect(_text(tester, 'institution-name'), 'Unsubmitted draft');
  },);

  testWidgets(
      'admin and legal review conflicts refresh requests without doctor-only form loads',
      (tester) async {
    _useLargeSurface(tester);
    final adminRepository = _ProjectRequestRepository()
      ..requests = [
        _request(
          id: 'admin-conflict',
          type: 'INSTITUTION',
          doctorId: 'doctor-1',
          institutionId: 'inst-1',
        ),
      ]
      ..institutionReviewError =
          const ApiException(message: 'conflict', httpStatus: 409,);
    adminRepository.onInstitutionReview = () {
      adminRepository.requests = [
        _request(
          id: 'admin-conflict',
          type: 'INSTITUTION',
          doctorId: 'doctor-1',
          doctorName: 'Admin refreshed doctor',
          institutionId: 'inst-1',
          status: 'APPROVED',
        ),
      ];
    };
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: adminRepository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('review-creation-admin-conflict')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(adminRepository.requestListLoads, 2);
    expect(find.text('医生: Admin refreshed doctor'), findsOneWidget);
    expect(find.text('审核状态已变化，申请列表已刷新，请基于最新内容重试'), findsOneWidget);
    expect(adminRepository.formConfigLoads, 0);
    expect(adminRepository.institutionOptionLoads, 0);
    expect(adminRepository.managementProjectLoads, 0);

    final legalRepository = _ProjectRequestRepository()
      ..requests = [
        _request(
          id: 'legal-conflict',
          type: 'INSTITUTION',
          doctorId: 'doctor-1',
          institutionId: 'inst-1',
        ),
      ]
      ..institutionReviewError =
          const ApiException(message: 'conflict', httpStatus: 409,);
    legalRepository.onInstitutionReview = () {
      legalRepository.requests = [
        _request(
          id: 'legal-conflict',
          type: 'INSTITUTION',
          doctorId: 'doctor-1',
          doctorName: 'Legal refreshed doctor',
          institutionId: 'inst-1',
          status: 'REJECTED',
        ),
      ];
    };
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('legal-conflict-page'),
      repository: legalRepository,
      context: _legalContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('review-creation-legal-conflict')));
    await tester.pumpAndSettle();
    await _selectReviewDecision(tester, 'REJECTED');
    await tester.enterText(
        find.byKey(const Key('creation-review-note')), 'Legal reject',);
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(legalRepository.requestListLoads, 2);
    expect(find.text('医生: Legal refreshed doctor'), findsOneWidget);
    expect(find.text('审核状态已变化，申请列表已刷新，请基于最新内容重试'), findsOneWidget);
    expect(legalRepository.formConfigLoads, 0);
    expect(legalRepository.institutionOptionLoads, 0);
    expect(legalRepository.managementProjectLoads, 0);
  },);

  testWidgets(
      'institution creation hides history while immutable reviews remain role scoped',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository()
      ..requests = [
        _request(
          id: 'platform-own',
          type: 'PLATFORM',
          doctorId: 'doctor-1',
          slogan: '',
          coverImage: '',
          images: const [],
          categoryTags: const [],
        ),
        _request(
          id: 'platform-other',
          type: 'PLATFORM',
          doctorId: 'doctor-2',
        ),
        _request(
          id: 'institution-target',
          type: 'INSTITUTION',
          doctorId: 'doctor-1',
          institutionId: 'inst-1',
        ),
        _request(
          id: 'institution-other',
          type: 'INSTITUTION',
          doctorId: 'doctor-2',
          institutionId: 'inst-2',
          slogan: null,
          coverImage: null,
          images: null,
        ),
      ];

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      repository: repository,
      context: _doctorContext,
    ),),);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsOneWidget,);
    expect(find.byKey(const Key('professional-request-platform-other')),
        findsNothing,);
    expect(find.byKey(const Key('review-creation-platform-own')), findsNothing,);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('applicant-institution'),
      repository: repository,
      context: _doctorContext,
    ),),);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsNothing,);
    expect(find.byKey(const Key('professional-request-institution-other')),
        findsNothing,);
    expect(find.byKey(const Key('review-creation-institution-target')),
        findsNothing,);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('non-target-legal-institution'),
      repository: repository,
      context: _legalContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsOneWidget,);
    expect(find.byKey(const Key('professional-request-institution-other')),
        findsNothing,);
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsNothing,);
    final institutionCard =
        find.byKey(const Key('institution-review-card-institution-target'));
    expect(
      find.descendant(
        of: institutionCard,
        matching: find.text('平台项目: Hydrating Facial'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: institutionCard,
        matching: find.text('医生: Dr. Chen'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: institutionCard,
        matching: find.textContaining('Complete immutable detail'),
      ),
      findsNothing,
    );

    await tester.tap(
      find.byKey(const Key('institution-review-detail-institution-target')),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('Complete immutable detail'), findsOneWidget);
    expect(find.textContaining('项目详情：'), findsNothing);
    expect(find.text('申请编号：institution-target'), findsOneWidget);
    expect(find.text('机构编号：inst-1'), findsOneWidget);
    expect(find.text('申请医生编号：doctor-1'), findsOneWidget);
    expect(find.text('当前医生名称：Dr. Chen'), findsOneWidget);
    expect(find.text('当前机构名称：Joysong Clinic'), findsOneWidget);
    expect(find.text('平台项目编号：project-1'), findsOneWidget);
    expect(find.text('当前平台项目名称：Hydrating Facial'), findsOneWidget);
    expect(find.text('项目分类：Skin'), findsOneWidget);
    expect(find.text('原价（USD）：USD 999.99'), findsOneWidget);
    expect(find.text('旅游地接服务费：USD 79.95'), findsOneWidget);
    expect(find.text('申请说明：Clinic note'), findsOneWidget);
    expect(find.text('申请状态：待审核'), findsOneWidget);
    expect(find.text('审核意见：未提供'), findsOneWidget);
    expect(find.text('审核人：未提供'), findsOneWidget);
    expect(find.text('生成平台项目：未提供'), findsOneWidget);
    expect(find.text('生成机构项目：未提供'), findsOneWidget);
    expect(find.byType(TextField), findsNothing,
        reason: 'the submitted snapshot must be immutable',);
    Navigator.of(
      tester.element(find.byType(InstitutionProjectReviewDetailPage)),
    ).pop();
    await tester.pumpAndSettle();

    await tester
        .tap(find.byKey(const Key('review-creation-institution-target')),);
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<DropdownButtonFormField<String>>(
            find.byKey(const Key('creation-review-decision')),
          )
          .decoration
          .labelText,
      '审核决定',
    );
    await _selectReviewDecision(tester, 'REJECTED');
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pump();
    expect(find.byType(AlertDialog), findsOneWidget,
        reason: 'blank rejection note must not close the dialog',);
    expect(find.text('驳回时必须填写审核意见'), findsOneWidget);
    await tester.enterText(
        find.byKey(const Key('creation-review-note')), 'Not ready',);
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.institutionReviews.single,
        (id: 'institution-target', decision: 'REJECTED', note: 'Not ready',));

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _nonTargetLegalContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsNothing,);
    expect(find.byKey(const Key('review-creation-institution-target')),
        findsNothing,);

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      key: const ValueKey('non-admin-platform-review'),
      repository: repository,
      context: _legalContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsNothing,);
    expect(find.byKey(const Key('professional-request-platform-other')),
        findsNothing,);
    expect(find.byKey(const Key('review-creation-platform-own')), findsNothing,);

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      key: const ValueKey('admin-platform'),
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsOneWidget,);
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsNothing,);
    expect(find.text('项目标语：空字符串'), findsOneWidget);
    expect(find.text('封面图：空字符串'), findsOneWidget);
    expect(find.text('项目图片：空列表'), findsOneWidget);
    expect(find.text('分类标签：空列表'), findsOneWidget);
    await tester.tap(find.byKey(const Key('review-creation-platform-own')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.platformReviews.single,
        (id: 'platform-own', decision: 'APPROVED', note: '',));

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('admin-institution'),
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    ),),);
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const Key('institution-review-detail-institution-other')),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('项目详情：'), findsNothing);
    expect(find.text('分类标签：未提供'), findsWidgets);
    expect(find.text('审核时间：-'), findsOneWidget);
    expect(find.text('生成机构项目：未提供'), findsOneWidget);
    Navigator.of(
      tester.element(find.byType(InstitutionProjectReviewDetailPage)),
    ).pop();
    await tester.pumpAndSettle();
    await tester
        .tap(find.byKey(const Key('review-creation-institution-other')),);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.institutionReviews.last,
        (id: 'institution-other', decision: 'APPROVED', note: '',));
  },);
}

void _useLargeSurface(WidgetTester tester) {
  tester.view.physicalSize = const Size(900, 3200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Widget _app(Widget home) => MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: home,
    );

String _text(WidgetTester tester, String key) =>
    tester.widget<TextField>(find.byKey(Key(key))).controller!.text;

String? _dropdownValue(WidgetTester tester, String key) =>
    tester.widget<DropdownButtonFormField<String>>(_dropdown(key)).initialValue;

Future<void> _choose(WidgetTester tester, Key key, String label) async {
  await tester.tap(_dropdown((key as ValueKey<String>).value));
  await tester.pumpAndSettle();
  await tester.tap(find.text(label).last);
  await tester.pumpAndSettle();
}

Finder _dropdown(String key) => find.byWidgetPredicate(
      (widget) =>
          widget is DropdownButtonFormField<String> &&
          (widget.key?.toString().contains("'$key-") ?? false),
    );

Future<void> _submit(WidgetTester tester, Key key) async {
  await tester.ensureVisible(find.byKey(key));
  await tester.tap(find.byKey(key));
  await tester.pumpAndSettle();
}

Future<void> _fillPlatformDraft(WidgetTester tester,
    {required String referencePrice,}) async {
  final values = {
    'platform-name': 'Retained platform name',
    'platform-reference-price': referencePrice,
    'platform-slogan': 'Retained slogan',
    'platform-sales-count': '8',
    'platform-category': 'Skin',
    'platform-description': 'Retained description',
    'platform-detail-content': 'Retained plain detail',
    'platform-tags': 'hydration, gentle',
    'platform-category-tags': 'facial, skin',
    'platform-notes': 'Retained notes',
  };
  for (final entry in values.entries) {
    await tester.enterText(find.byKey(Key(entry.key)), entry.value);
  }
}

Future<void> _fillInstitutionDraft(
  WidgetTester tester, {
  required String price,
}) async {
  final values = {
    'institution-name': 'Clinic override',
    'institution-category': 'Clinic category',
    'institution-description': 'Clinic description',
    'institution-tags': 'clinic,signature',
    'institution-slogan': 'Clinic glow',
    'institution-detail-content': 'Override plain detail',
    'institution-price': price,
    'institution-sales-count': '7',
    'institution-notes': 'Clinic notes',
  };
  for (final entry in values.entries) {
    await tester.enterText(find.byKey(Key(entry.key)), entry.value);
  }
}

Future<void> _preparePlatformConflictDraft(WidgetTester tester) async {
  await _fillPlatformDraft(tester, referencePrice: '199.99');
  await tester.tap(find.byKey(const Key('platform-cover-upload')));
  await tester.pump();
  await tester.tap(find.byKey(const Key('platform-gallery-upload')));
  await tester.pump();
}

void _expectPlatformConflictDraftRetained(WidgetTester tester) {
  for (final entry in const {
    'platform-name': 'Retained platform name',
    'platform-reference-price': '199.99',
    'platform-slogan': 'Retained slogan',
    'platform-sales-count': '8',
    'platform-category': 'Skin',
    'platform-description': 'Retained description',
    'platform-detail-content': 'Retained plain detail',
    'platform-tags': 'hydration, gentle',
    'platform-category-tags': 'facial, skin',
    'platform-notes': 'Retained notes',
  }.entries) {
    expect(_text(tester, entry.key), entry.value);
  }
  expect(find.byKey(const Key('platform-currency')), findsNothing);
  expect(find.text('https://cdn.example.com/conflict-platform-cover.jpg'),
      findsOneWidget,);
  expect(find.text('https://cdn.example.com/conflict-platform-gallery.jpg'),
      findsOneWidget,);
}

Future<void> _prepareInstitutionConflictDraft(WidgetTester tester) async {
  await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
  await _choose(tester, const Key('institution-project'), 'Hydrating Facial');
  await _fillInstitutionDraft(
    tester,
    price: '799.99'
  );
  await tester.tap(find.byKey(const Key('institution-is-active')));
  await tester.pump();
  await tester.tap(find.byKey(const Key('institution-cover-upload')));
  await tester.pump();
  await tester.tap(find.byKey(const Key('institution-gallery-upload')));
  await tester.pump();
}

void _expectInstitutionConflictDraftRetained(WidgetTester tester) {
  _expectInstitutionConflictDraftValuesRetained(tester);
  expect(_dropdownValue(tester, 'institution-id'), 'inst-1');
  expect(_dropdownValue(tester, 'institution-project'), 'project-1');
}

void _expectInstitutionConflictDraftValuesRetained(WidgetTester tester) {
  for (final entry in const {
    'institution-name': 'Clinic override',
    'institution-category': 'Clinic category',
    'institution-description': 'Clinic description',
    'institution-tags': 'clinic,signature',
    'institution-slogan': 'Clinic glow',
    'institution-detail-content': 'Override plain detail',
    'institution-price': '799.99',
    'institution-sales-count': '7',
    'institution-notes': 'Clinic notes',
  }.entries) {
    expect(_text(tester, entry.key), entry.value);
  }
  expect(
    find.byKey(const Key('institution-travel-ground-service-fee')),
    findsOneWidget,);
  expect(
    tester
        .widget<SwitchListTile>(find.byKey(const Key('institution-is-active')))
        .value,
    isFalse,
  );
  expect(find.text('https://cdn.example.com/conflict-clinic-cover.jpg'),
      findsOneWidget,);
  expect(find.text('https://cdn.example.com/conflict-clinic-gallery.jpg'),
      findsOneWidget,);
}

Future<void> _selectReviewDecision(WidgetTester tester, String decision) async {
  await tester.tap(find.byKey(const Key('creation-review-decision')));
  await tester.pumpAndSettle();
  await tester.tap(find.text(decision == 'REJECTED' ? '驳回' : '通过').last);
  await tester.pumpAndSettle();
}

const _doctorContext = ManagementContext(
  userId: 'doctor-user-1',
  platformRole: 'USER',
  activeRoles: ['DOCTOR'],
  doctorId: 'doctor-1',
  managedInstitutionIds: [],
  visibleInstitutionIds: ['inst-1'],
  doctorInstitutionIds: ['inst-1'],
  canManageDoctors: true,
  canSubmitPlatformProjectRequests: true,
  canSubmitInstitutionProjectRequests: true,
);

const _doctorWithoutInstitutionsContext = ManagementContext(
  userId: 'doctor-user-1',
  platformRole: 'USER',
  activeRoles: ['DOCTOR'],
  doctorId: 'doctor-1',
  managedInstitutionIds: [],
  visibleInstitutionIds: ['inst-1'],
  doctorInstitutionIds: [],
  canManageDoctors: true,
  canSubmitPlatformProjectRequests: true,
  canSubmitInstitutionProjectRequests: true,
);

const _multiInstitutionDoctorContext = ManagementContext(
  userId: 'doctor-user-1',
  platformRole: 'USER',
  activeRoles: ['DOCTOR'],
  doctorId: 'doctor-1',
  managedInstitutionIds: [],
  visibleInstitutionIds: ['inst-1', 'inst-2'],
  doctorInstitutionIds: ['inst-1', 'inst-2'],
  canManageDoctors: true,
  canSubmitPlatformProjectRequests: true,
  canSubmitInstitutionProjectRequests: true,
);

const _legalContext = ManagementContext(
  userId: 'legal-user-1',
  platformRole: 'USER',
  activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
  managedInstitutionIds: ['inst-1'],
  visibleInstitutionIds: ['inst-1'],
  canReviewInstitutionProjectRequests: true,
);

const _nonTargetLegalContext = ManagementContext(
  userId: 'legal-user-2',
  platformRole: 'USER',
  activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
  managedInstitutionIds: ['inst-3'],
  visibleInstitutionIds: ['inst-3'],
  canReviewInstitutionProjectRequests: true,
);

const _adminContext = ManagementContext(
  userId: 'admin-1',
  platformRole: 'ADMIN',
  activeRoles: [],
  managedInstitutionIds: [],
  visibleInstitutionIds: [],
  canReviewInstitutionProjectRequests: true,
);

ProfessionalProjectRequest _request({
  required String id,
  required String type,
  required String doctorId,
  String? doctorName,
  String? institutionId,
  String? institutionName,
  String? projectName,
  String? slogan = 'Clinic glow',
  String? coverImage = 'cover.jpg',
  List<String>? images = const ['one.jpg', 'two.jpg'],
  List<String>? categoryTags = const ['facial'],
  bool inheritName = false,
  String status = 'PENDING',
  num platformRate = 10,
  num doctorRate = 35.25,
}) =>
    ProfessionalProjectRequest.fromJson({
      'id': id,
      'requestType': type,
      'doctorId': doctorId,
      'doctorName':
          doctorName ?? (doctorId == 'doctor-1' ? 'Dr. Chen' : 'Dr. Other'),
      'institutionId': type == 'INSTITUTION' ? institutionId : null,
      'institutionName': type == 'INSTITUTION'
          ? institutionName ??
              (institutionId == 'inst-1' ? 'Joysong Clinic' : 'Other Clinic')
          : null,
      'projectId': type == 'INSTITUTION' ? 'project-1' : null,
      'projectName':
          type == 'INSTITUTION' ? projectName ?? 'Hydrating Facial' : null,
      'name': inheritName ? null : 'Clinic Hydrating Facial $id',
      'category': 'Skin',
      'description': 'Clinic description',
      'tags': ['hydration', 'signature'],
      'slogan': slogan,
      'detailContent': 'Complete immutable detail',
      'currency': type == 'INSTITUTION' ? 'USD' : 'CNY',
      'coverImage': coverImage,
      'images': images,
      'salesCount': 7,
      'referencePrice': type == 'PLATFORM' ? 899.25 : null,
      'categoryTags': type == 'PLATFORM' ? categoryTags : null,
      'price': type == 'INSTITUTION' ? 799.5 : null,
      'originalPrice': type == 'INSTITUTION' ? 999.99 : null,
      'isActive': type == 'INSTITUTION' ? true : null,
      'institutionSplit': type == 'INSTITUTION'
          ? {
              'consultationFee': 80.25,
              'commissionRate': 12.5,
              'institutionRate': 42.25,
              'platformRate': platformRate,
              'doctorRate': doctorRate,
            }
          : null,
      'notes': 'Clinic note',
      'status': status,
      'reviewNote': null,
      'reviewedBy': null,
      'reviewedAt': null,
      'resultingProjectId': null,
      'resultingInstitutionProjectId': null,
      'submittedAt': '2026-08-16T08:00:00',
      'updatedAt': '2026-08-16T08:05:00',
    });

ProfessionalProjectRequest _malformedRequest({
  required String id,
  required String type,
}) =>
    ProfessionalProjectRequest(
      id: id,
      requestType: type,
      doctorId: 'doctor-1',
      doctorName: 'Dr. Chen',
      institutionId: type == 'INSTITUTION' ? 'inst-1' : null,
      institutionName: type == 'INSTITUTION' ? 'Joysong Clinic' : null,
      projectId: type == 'INSTITUTION' ? 'project-1' : null,
      projectName: type == 'INSTITUTION' ? 'Hydrating Facial' : null,
      name: 'Malformed project',
      category: type == 'PLATFORM' ? null : 'Skin',
      description: 'Description',
      tags: const [],
      slogan: '',
      currency: 'CNY',
      coverImage: '',
      images: const [],
      salesCount: 0,
      referencePrice: type == 'PLATFORM' ? 1 : null,
      categoryTags: type == 'PLATFORM' ? const [] : null,
      price: type == 'INSTITUTION' ? 1 : null,
      isActive: type == 'INSTITUTION' ? true : null,
      institutionSplit: null,
      notes: '',
      status: 'PENDING',
      submittedAt: DateTime(2026, 8, 16, 8),
      updatedAt: DateTime(2026, 8, 16, 8, 5),
    );

ProfessionalProjectRequest _negativeDriftRequest({
  required String id,
  required String projectName,
}) =>
    ProfessionalProjectRequest(
      id: id,
      requestType: 'INSTITUTION',
      doctorId: 'doctor-1',
      doctorName: 'Dr. Chen',
      institutionId: 'inst-1',
      institutionName: 'Joysong Clinic',
      projectId: 'project-1',
      projectName: projectName,
      currency: 'USD',
      salesCount: 7,
      price: 799.5,
      isActive: true,
      institutionSplit: const InstitutionProjectSplit(
        consultationFee: 80.25,
        commissionRate: 12.5,
        institutionRate: 42.25,
        platformRate: 100,
        doctorRate: -54.75,
      ),
      status: 'PENDING',
      submittedAt: DateTime(2026, 8, 16, 8),
      updatedAt: DateTime(2026, 8, 16, 8, 5),
    );

final class _ProjectRequestRepository implements IdentityRepository {
  ManagementContext managementContext = _doctorContext;
  List<ProfessionalProjectRequest> requests = const [];
  final platformSubmissions = <PlatformProjectRequestDraft>[];
  final institutionSubmissions = <InstitutionProjectRequestDraft>[];
  final platformReviews = <({String id, String decision, String note})>[];
  final institutionReviews = <({String id, String decision, String note})>[];
  bool platformSubmitError = false;
  bool institutionSubmitError = false;
  Object? platformSubmitException;
  Object? institutionSubmitException;
  bool failNextRequestList = false;
  bool failNextManagementContext = false;
  Completer<List<ProfessionalProjectRequest>>? nextRequestList;
  Completer<void>? requestListStarted;
  Completer<void>? platformSubmitGate;
  Completer<void>? institutionSubmitGate;
  Completer<void>? platformReviewGate;
  Completer<void>? institutionReviewGate;
  Object? platformReviewError;
  Object? institutionReviewError;
  void Function()? onPlatformReview;
  void Function()? onInstitutionReview;
  void Function()? onPlatformSubmit;
  void Function()? onInstitutionSubmit;
  num formPlatformRate = 10.25;
  List<InstitutionOption> institutionOptions = const [
    InstitutionOption(id: 'inst-1', name: 'Joysong Clinic'),
  ];
  List<ManagementProjectOption> managementProjects = const [
    ManagementProjectOption(
      id: 'project-1',
      name: 'Hydrating Facial',
      category: 'Skin',
      description: 'Inherited description',
      tags: 'hydration,gentle',
      categoryTags: 'facial,skin',
      coverImage: 'https://cdn.example.com/inherited-cover.jpg',
      referencePrice: 899.25,
      currency: 'USD',
      slogan: 'Glow naturally',
      detailContent: 'Inherited plain detail',
      images: [
        'https://cdn.example.com/inherited-one.jpg',
        'https://cdn.example.com/inherited-two.jpg',
      ],
      salesCount: 18,
    ),
  ];
  int institutionOptionLoads = 0;
  int managementProjectLoads = 0;
  int formConfigLoads = 0;
  int requestListLoads = 0;
  int managementContextLoads = 0;
  final calls = <String>[];

  @override
  Future<ManagementContext> loadManagementContext() async {
    managementContextLoads++;
    calls.add('management-context');
    if (failNextManagementContext) {
      failNextManagementContext = false;
      throw StateError('management context refresh failed');
    }
    return managementContext;
  }

  @override
  Future<List<ProfessionalProjectRequest>>
      listProfessionalProjectRequests() async {
    requestListLoads++;
    calls.add('requests');
    final pending = nextRequestList;
    if (pending != null) {
      nextRequestList = null;
      requestListStarted?.complete();
      requestListStarted = null;
      return pending.future;
    }
    if (failNextRequestList) {
      failNextRequestList = false;
      throw StateError('refresh failed');
    }
    return requests;
  }

  @override
  Future<List<InstitutionOption>> listInstitutionOptions() async {
    institutionOptionLoads++;
    calls.add('institution-options');
    return institutionOptions;
  }

  @override
  Future<List<ManagementProjectOption>> listManagementProjects() async {
    managementProjectLoads++;
    calls.add('management-projects');
    return managementProjects;
  }

  @override
  Future<InstitutionProjectApplicationFormConfig>
      loadInstitutionProjectApplicationFormConfig() async {
    formConfigLoads++;
    calls.add('form-config');
    return InstitutionProjectApplicationFormConfig(
        platformRate: formPlatformRate,);
  }

  @override
  Future<void> submitPlatformProjectRequest(
      PlatformProjectRequestDraft draft,) async {
    platformSubmissions.add(draft);
    calls.add('submit-platform');
    onPlatformSubmit?.call();
    await platformSubmitGate?.future;
    final exception = platformSubmitException;
    if (exception != null) throw exception;
    if (platformSubmitError) throw StateError('submit failed');
  }

  @override
  Future<void> submitInstitutionProjectRequest(
      InstitutionProjectRequestDraft draft,) async {
    institutionSubmissions.add(draft);
    calls.add('submit-institution');
    onInstitutionSubmit?.call();
    await institutionSubmitGate?.future;
    final exception = institutionSubmitException;
    if (exception != null) throw exception;
    if (institutionSubmitError) throw StateError('submit failed');
  }

  @override
  Future<void> reviewInstitutionProjectRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {
    institutionReviews.add((id: id, decision: decision, note: reviewNote));
    final error = institutionReviewError;
    onInstitutionReview?.call();
    if (error != null) {
      institutionReviewError = null;
      throw error;
    }
    await institutionReviewGate?.future;
  }

  @override
  Future<void> reviewPlatformProjectRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {
    platformReviews.add((id: id, decision: decision, note: reviewNote));
    final error = platformReviewError;
    onPlatformReview?.call();
    if (error != null) {
      platformReviewError = null;
      throw error;
    }
    await platformReviewGate?.future;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _JoinProjectRepository implements IdentityRepository {
  final submissions = <InstitutionProjectJoinRequestDraft>[];
  var formConfigLoads = 0;

  @override
  Future<List<InstitutionProjectJoinRequest>>
      listInstitutionProjectJoinRequests() async => const [];

  @override
  Future<List<ManagedInstitutionProject>>
      listManagedInstitutionProjects() async => const [
            ManagedInstitutionProject(
              id: 'ip-1',
              institutionId: 'inst-1',
              projectId: 'project-1',
              effectiveName: 'Hydrating Facial',
            ),
          ];

  @override
  Future<InstitutionProjectApplicationFormConfig>
      loadInstitutionProjectApplicationFormConfig() async {
    formConfigLoads++;
    return const InstitutionProjectApplicationFormConfig(platformRate: 40);
  }

  @override
  Future<void> submitInstitutionProjectJoinRequest(
    InstitutionProjectJoinRequestDraft draft,
  ) async {
    submissions.add(draft);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _DiscoverRepository implements DiscoverRepository {
  const _DiscoverRepository();

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() async =>
      const DiscoverFilterOptions();

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
      const DiscoverPageResult(items: [], hasMore: false);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
