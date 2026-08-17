import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

void main() {
  setUp(() {});

  testWidgets(
      'platform application keeps the complete ordered plain-text form and uses the injected cover/gallery uploader',
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
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('申请新增平台项目'));
    await tester.pumpAndSettle();

    const orderedKeys = [
      'platform-name',
      'platform-reference-price',
      'platform-currency',
      'platform-slogan',
      'platform-sales-count',
      'platform-cover-upload',
      'platform-gallery-upload',
      'platform-category',
      'platform-description',
      'platform-detail-content',
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
            find.byKey(const Key('platform-detail-content')),
          )
          .maxLines,
      greaterThan(1),
    );
    expect(find.textContaining('评分'), findsNothing);
    expect(find.textContaining('评价数'), findsNothing);
    expect(find.textContaining('选择医生'), findsNothing);
    expect(find.byType(EditableText).evaluate().length, 10);

    await tester.tap(find.byKey(const Key('platform-cover-upload')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('platform-gallery-upload')));
    await tester.pump();
    expect(find.text('https://cdn.example.com/platform-cover.jpg'),
        findsOneWidget);
    expect(find.text('https://cdn.example.com/platform-gallery.jpg'),
        findsOneWidget);
    const coverRemoveLabel =
        '移除封面图 1：https://cdn.example.com/platform-cover.jpg';
    const galleryRemoveLabel =
        '移除项目图片 1：https://cdn.example.com/platform-gallery.jpg';
    final coverRemove = find.byWidgetPredicate(
        (widget) => widget is IconButton && widget.tooltip == coverRemoveLabel);
    final galleryRemove = find.byWidgetPredicate((widget) =>
        widget is IconButton && widget.tooltip == galleryRemoveLabel);
    expect(coverRemove, findsOneWidget);
    expect(galleryRemove, findsOneWidget);
    expect(
      find.ancestor(
        of: coverRemove,
        matching: find.byWidgetPredicate((widget) =>
            widget is Semantics && widget.properties.label == coverRemoveLabel),
      ),
      findsOneWidget,
    );
    expect(
      find.ancestor(
        of: galleryRemove,
        matching: find.byWidgetPredicate((widget) =>
            widget is Semantics &&
            widget.properties.label == galleryRemoveLabel),
      ),
      findsOneWidget,
    );

    await tester.tap(find.byKey(const Key('platform-gallery-upload')));
    await tester.pump();
    expect(find.text('https://cdn.example.com/platform-cover.jpg'),
        findsOneWidget);
    expect(find.text('https://cdn.example.com/platform-gallery.jpg'),
        findsOneWidget);
    expect(find.text('图片上传失败，请重试'), findsOneWidget);
  });

  testWidgets(
      'institution application loads config, exposes all inheritance hints, applicant-only binding, and live derived rates',
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
    )));
    await tester.pumpAndSettle();

    expect(find.textContaining('doctor-1'), findsOneWidget);
    expect(find.textContaining('唯一申请医生'), findsOneWidget);
    expect(find.textContaining('选择医生'), findsNothing);
    expect(find.textContaining('多医生'), findsNothing);

    await _choose(tester, const Key('institution-project'), 'Hydrating Facial');
    for (final inherited in const [
      'Hydrating Facial',
      'Skin',
      'Inherited description',
      'hydration,gentle',
      'facial,skin',
      'https://cdn.example.com/inherited-cover.jpg',
      '899.25',
      'USD',
      'Glow naturally',
      'Inherited plain detail',
      'https://cdn.example.com/inherited-one.jpg',
      'https://cdn.example.com/inherited-two.jpg',
      '18',
    ]) {
      expect(find.textContaining(inherited), findsWidgets,
          reason: 'missing inherited value $inherited');
    }
    const inheritedHints = {
      'institution-name': 'Hydrating Facial',
      'institution-category': 'Skin',
      'institution-description': 'Inherited description',
      'institution-tags': 'hydration,gentle',
      'institution-slogan': 'Glow naturally',
      'institution-detail-content': 'Inherited plain detail',
      'institution-price': '899.25',
      'institution-sales-count': '18',
    };
    for (final entry in inheritedHints.entries) {
      final field = tester.widget<TextField>(find.byKey(Key(entry.key)));
      expect(field.controller!.text, isEmpty,
          reason: '${entry.key} must remain override-only');
      expect(field.decoration?.hintText, entry.value,
          reason: '${entry.key} must expose its inherited hint');
    }
    expect(_dropdownValue(tester, 'institution-currency'), 'USD');
    expect(
      tester
          .widget<TextField>(
            find.byKey(const Key('institution-detail-content')),
          )
          .maxLines,
      greaterThan(1),
    );
    expect(find.text('平台比例（只读）：10.25%'), findsOneWidget);

    final orderedFinders = <Finder>[
      _dropdown('institution-id'),
      _dropdown('institution-project'),
      find.byKey(const Key('institution-applicant-notice')),
      find.byKey(const Key('institution-inheritance-preview')),
      find.byKey(const Key('institution-name')),
      find.byKey(const Key('institution-category')),
      find.byKey(const Key('institution-description')),
      find.byKey(const Key('institution-tags')),
      find.byKey(const Key('institution-slogan')),
      find.byKey(const Key('institution-detail-content')),
      find.byKey(const Key('institution-price')),
      find.byKey(const Key('institution-original-price')),
      _dropdown('institution-currency'),
      find.byKey(const Key('institution-cover-upload')),
      find.byKey(const Key('institution-gallery-upload')),
      find.byKey(const Key('institution-sales-count')),
      find.byKey(const Key('institution-is-active')),
      find.byKey(const Key('institution-consultation-fee')),
      find.byKey(const Key('institution-consultant-rate')),
      find.byKey(const Key('institution-rate')),
      find.byKey(const Key('institution-platform-rate')),
      find.byKey(const Key('institution-doctor-rate')),
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
        find.byKey(const Key('institution-consultant-rate')), '33.33');
    await tester.enterText(find.byKey(const Key('institution-rate')), '33.33');
    await tester.pump();
    expect(find.text('医生比例（自动推导）：23.09%'), findsOneWidget);

    await tester.tap(find.byKey(const Key('institution-cover-upload')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('institution-gallery-upload')));
    await tester.pump();
    expect(find.text('https://cdn.example.com/institution-cover.jpg'),
        findsOneWidget);
    expect(find.text('https://cdn.example.com/institution-gallery.jpg'),
        findsOneWidget);
  });

  testWidgets(
      'institution rate preview rejects an oversized paste without throwing or submitting',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository();
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
    )));
    await tester.pumpAndSettle();
    await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
    await _choose(tester, const Key('institution-project'), 'Hydrating Facial');
    await _fillInstitutionDraft(
      tester,
      price: '799.99',
      institutionRate: '40',
    );

    await tester.enterText(
      find.byKey(const Key('institution-consultant-rate')),
      '999999999999999999999',
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(find.text('医生比例（自动推导）：-%'), findsOneWidget);
    await _submit(tester, const Key('institution-submit'));
    expect(find.text('分账比例必须在 0 到 100 之间且最多两位小数'), findsOneWidget);
    expect(repository.institutionSubmissions, isEmpty);
  });

  testWidgets(
      'institution rate preview normalizes decimal shorthand scientific notation and trailing zeros like the draft',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository();
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
    )));
    await tester.pumpAndSettle();
    await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
    await _choose(tester, const Key('institution-project'), 'Hydrating Facial');
    await _fillInstitutionDraft(
      tester,
      price: '799.99',
      institutionRate: '40.25',
    );

    for (final accepted in const ['.5', '5e-1', '0.5000']) {
      await tester.enterText(
        find.byKey(const Key('institution-consultant-rate')),
        accepted,
      );
      await tester.pump();
      expect(find.text('医生比例（自动推导）：49%'), findsOneWidget,
          reason: '$accepted parses to the exact two-decimal value 0.5');
    }

    await tester.enterText(
      find.byKey(const Key('institution-consultant-rate')),
      '5.01e-1',
    );
    await tester.pump();
    expect(find.text('医生比例（自动推导）：-%'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('institution-consultant-rate')),
      '.5',
    );
    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(1));
    expect(repository.institutionSubmissions.single.commissionRate, 0.5);
  });

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
    )));
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
        find.byKey(const Key('platform-reference-price')), '199.99');
    repository.platformSubmitError = true;
    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, hasLength(1));
    expect(_text(tester, 'platform-notes'), 'Retained notes');
    expect(find.text('https://cdn.example.com/retained-cover.jpg'),
        findsOneWidget);

    repository.platformSubmitError = false;
    repository.failNextRequestList = true;
    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, hasLength(2));
    expect(_text(tester, 'platform-name'), 'Retained platform name');
    expect(find.text('https://cdn.example.com/retained-gallery.jpg'),
        findsOneWidget);

    await _submit(tester, const Key('platform-submit'));
    expect(repository.platformSubmissions, hasLength(3));
    expect(_text(tester, 'platform-name'), isEmpty);
    expect(_text(tester, 'platform-notes'), isEmpty);
    expect(
        find.text('https://cdn.example.com/retained-cover.jpg'), findsNothing);
    expect(find.text('https://cdn.example.com/retained-gallery.jpg'),
        findsNothing);
  });

  testWidgets(
      'institution draft blocks precision and rate sum, retains every override on failures, and never submits platform or doctor rate',
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
    )));
    await tester.pumpAndSettle();
    await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
    await _choose(tester, const Key('institution-project'), 'Hydrating Facial');
    await _fillInstitutionDraft(tester,
        price: '799.999', institutionRate: '60');
    await tester.tap(find.byKey(const Key('institution-cover-upload')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('institution-gallery-upload')));
    await tester.pump();

    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, isEmpty);
    await tester.enterText(
        find.byKey(const Key('institution-price')), '799.99');
    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, isEmpty,
        reason: '10.25 + 60 + 30 exceeds 100');

    await tester.enterText(find.byKey(const Key('institution-rate')), '40');
    repository.institutionSubmitError = true;
    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(1));
    expect(_text(tester, 'institution-name'), 'Clinic override');
    expect(find.text('https://cdn.example.com/clinic-gallery.jpg'),
        findsOneWidget);

    repository.institutionSubmitError = false;
    repository.failNextRequestList = true;
    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(2));
    expect(
        _text(tester, 'institution-detail-content'), 'Override plain detail');
    expect(_dropdownValue(tester, 'institution-currency'), 'USD');

    final body = repository.institutionSubmissions.last.toJson();
    expect(body, isNot(contains('platformRate')));
    expect(body, isNot(contains('doctorRate')));
    expect(body, isNot(contains('doctorId')));

    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(3));
    expect(_text(tester, 'institution-name'), isEmpty);
    expect(_text(tester, 'institution-price'), isEmpty);
    expect(find.text('https://cdn.example.com/clinic-cover.jpg'), findsNothing);
  });

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
    )));
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
    )));
    await tester.pumpAndSettle();
    await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
    await _choose(tester, const Key('institution-project'), 'Hydrating Facial');
    await _fillInstitutionDraft(
      tester,
      price: '799.99',
      institutionRate: '40',
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
  });

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
          const ApiException(message: 'conflict', httpStatus: 409);
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
    )));
    await tester.pumpAndSettle();
    await _preparePlatformConflictDraft(tester);
    expect(repository.requestListLoads, 1);

    await _submit(tester, const Key('platform-submit'));

    expect(repository.platformSubmissions, hasLength(1),
        reason: 'a 409 must never auto-replay the POST');
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
      findsOneWidget,
    );
  });

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
          const ApiException(message: 'conflict', httpStatus: 409);

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      key: const ValueKey('platform-conflict-refresh-failure'),
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    )));
    await tester.pumpAndSettle();
    await _preparePlatformConflictDraft(tester);
    repository.failNextRequestList = true;

    await _submit(tester, const Key('platform-submit'));

    expect(repository.platformSubmissions, hasLength(1));
    expect(repository.requestListLoads, 2);
    _expectPlatformConflictDraftRetained(tester);
    expect(
      find.text('提交冲突，申请列表刷新失败；草稿已保留，请手动刷新后再提交'),
      findsOneWidget,
    );
  });

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
          const ApiException(message: 'conflict', httpStatus: 409);
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
    )));
    await tester.pumpAndSettle();
    await _prepareInstitutionConflictDraft(tester);
    expect(repository.requestListLoads, 1);
    expect(repository.institutionOptionLoads, 1);
    expect(repository.managementProjectLoads, 1);
    expect(repository.formConfigLoads, 1);

    await _submit(tester, const Key('institution-submit'));

    expect(repository.institutionSubmissions, hasLength(1),
        reason: 'a 409 must never auto-replay the POST');
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
      'Refreshed Hydrating Facial',
    );
    expect(find.text('当前平台比例：12.5%'), findsOneWidget);
    expect(find.text('医生比例（自动推导）：17.5%'), findsOneWidget);
    expect(
      find.byKey(const Key('professional-request-institution-conflict-latest')),
      findsOneWidget,
    );
    expect(
      find.text('提交冲突，申请、项目目录与分账配置已刷新；草稿已保留，请核对后重新提交'),
      findsOneWidget,
    );
  });

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
          const ApiException(message: 'conflict', httpStatus: 409);
    repository.onInstitutionSubmit = () {
      repository
        ..managementContext = _doctorWithoutInstitutionsContext
        ..formPlatformRate = 12.5;
    };

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    )));
    await tester.pumpAndSettle();
    await _prepareInstitutionConflictDraft(tester);
    repository.calls.clear();

    await _submit(tester, const Key('institution-submit'));

    expect(repository.institutionSubmissions, hasLength(1),
        reason: 'the conflicted POST must not be replayed');
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
        reason: 'the public directory deliberately still contains the target');
    expect(_dropdownValue(tester, 'institution-id'), isNull,
        reason: 'the refreshed doctor relationship, not the public directory, '
            'must authorize the selection');
    expect(_dropdownValue(tester, 'institution-project'), 'project-1');
    _expectInstitutionConflictDraftValuesRetained(tester);
    expect(find.text('平台比例（只读）：12.5%'), findsOneWidget);

    await _submit(tester, const Key('institution-submit'));
    expect(repository.institutionSubmissions, hasLength(1));
    expect(find.text('请选择机构和平台项目，并填写有效金额、销量与分账比例'), findsOneWidget);
  });

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
          const ApiException(message: 'conflict', httpStatus: 409);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    )));
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
        reason: 'a failed context refresh must not claim fresh authorization');
  });

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
            const ApiException(message: 'conflict', httpStatus: 409);
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
      )));
      await tester.pumpAndSettle();
      await _prepareInstitutionConflictDraft(tester);

      await _submit(tester, const Key('institution-submit'));

      expect(tester.takeException(), isNull,
          reason:
              '${scenario.name} must render without a stale-value assertion');
      expect(repository.institutionSubmissions, hasLength(1));
      expect(_dropdownValue(tester, 'institution-id'),
          scenario.expectedInstitution);
      expect(_dropdownValue(tester, 'institution-project'),
          scenario.expectedProject);
      _expectInstitutionConflictDraftValuesRetained(tester);
      expect(find.text('平台比例（只读）：12.5%'), findsOneWidget);
      expect(find.text('医生比例（自动推导）：17.5%'), findsOneWidget);
      expect(
        find.text('提交冲突，申请、项目目录与分账配置已刷新；草稿已保留，请核对后重新提交'),
        findsOneWidget,
      );

      await _submit(tester, const Key('institution-submit'));
      expect(repository.institutionSubmissions, hasLength(1),
          reason: '${scenario.name} must require a new valid target selection');
      expect(find.text('请选择机构和平台项目，并填写有效金额、销量与分账比例'), findsOneWidget);
      _expectInstitutionConflictDraftValuesRetained(tester);
    }
  });

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
          const ApiException(message: 'conflict', httpStatus: 409);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('institution-conflict-refresh-failure'),
      repository: repository,
      context: _doctorContext,
      pickAndUploadImage: () async => uploads.removeAt(0),
    )));
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
      find.text('提交冲突，申请、项目目录或分账配置刷新失败；草稿已保留，请手动刷新后再提交'),
      findsOneWidget,
    );
  });

  testWidgets(
      'platform submit ignores a successful refresh completed after the page is disposed',
      (tester) async {
    _useLargeSurface(tester);
    final repository = _ProjectRequestRepository();
    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      repository: repository,
      context: _doctorContext,
    )));
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
        reason: 'a disposed form must not be cleared by a late refresh');
  });

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
    )));
    await tester.pumpAndSettle();
    expect(
        find.byKey(const Key('malformed-review-snapshot-malformed-platform')),
        findsOneWidget);
    expect(find.text('申请快照不完整，无法审核，请刷新后重试'), findsOneWidget);
    expect(find.byKey(const Key('review-creation-malformed-platform')),
        findsNothing);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    expect(
        find.byKey(
            const Key('malformed-review-snapshot-malformed-institution')),
        findsOneWidget);
    expect(find.text('申请快照不完整，无法审核，请刷新后重试'), findsOneWidget);
    expect(find.byKey(const Key('review-creation-malformed-institution')),
        findsNothing);
  });

  testWidgets(
      'negative current doctor rate stays visible and rejectable but cannot be approved',
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
    )));
    await tester.pumpAndSettle();
    expect(
        find.byKey(
            const Key('professional-request-institution-negative-initial')),
        findsOneWidget);
    expect(find.text('按当前平台比例推导的医生净比例：-54.75%'), findsOneWidget);
    expect(
        find.byKey(const Key(
            'malformed-review-snapshot-institution-negative-initial')),
        findsNothing);

    await tester.tap(
        find.byKey(const Key('review-creation-institution-negative-initial')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('creation-review-approval-blocked')),
        findsOneWidget);
    expect(find.text('当前医生净比例为负，无法批准；可驳回申请并说明原因'), findsOneWidget);
    final decisionField = find.byKey(const Key('creation-review-decision'));
    final decision = tester.widget<DropdownButton<String>>(find.descendant(
      of: decisionField,
      matching: find.byType(DropdownButton<String>),
    ));
    expect(decision.items!.map((item) => item.value), ['REJECTED']);
    expect(
      tester
          .widget<DropdownButtonFormField<String>>(decisionField)
          .initialValue,
      'REJECTED',
    );
    await tester.enterText(find.byKey(const Key('creation-review-note')),
        'Current split is invalid');
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.institutionReviews, [
      (
        id: 'institution-negative-initial',
        decision: 'REJECTED',
        note: 'Current split is invalid'
      ),
    ]);
  });

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
    )));
    await tester.pumpAndSettle();
    expect(find.text('institution-stable-title'), findsOneWidget);
    expect(find.text('Live project v1'), findsNothing);
    expect(find.text('当前平台项目名称：Live project v1'), findsOneWidget);

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
    )));
    await tester.pumpAndSettle();
    expect(find.text('institution-stable-title'), findsOneWidget);
    expect(find.text('Live project v2'), findsNothing);
    expect(find.text('当前平台项目名称：Live project v2'), findsOneWidget);
  });

  testWidgets(
      'platform and institution creation review actions synchronously block re-entry',
      (tester) async {
    _useLargeSurface(tester);
    final platformGate = Completer<void>();
    final platformRepository = _ProjectRequestRepository()
      ..requests = [
        _request(id: 'platform-pending', type: 'PLATFORM', doctorId: 'doctor-1')
      ]
      ..platformReviewGate = platformGate;
    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      repository: platformRepository,
      context: _adminContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('review-creation-platform-pending')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(platformRepository.platformReviews, hasLength(1));
    expect(
      tester
          .widget<FilledButton>(
              find.byKey(const Key('review-creation-platform-pending')))
          .onPressed,
      isNull,
    );
    await tester.tap(
      find.byKey(const Key('review-creation-platform-pending')),
      warnIfMissed: false,
    );
    await tester.pump();
    expect(find.byType(AlertDialog), findsNothing,
        reason: 'reject re-entry must not open while approval is pending');
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
        )
      ]
      ..institutionReviewGate = institutionGate;
    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: institutionRepository,
      context: _adminContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    await tester
        .tap(find.byKey(const Key('review-creation-institution-pending')));
    await tester.pumpAndSettle();
    await _selectReviewDecision(tester, 'REJECTED');
    await tester.enterText(
        find.byKey(const Key('creation-review-note')), 'Reject later');
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(institutionRepository.institutionReviews, hasLength(1));
    expect(
      tester
          .widget<FilledButton>(
              find.byKey(const Key('review-creation-institution-pending')))
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
        institutionRepository.institutionReviews.single.decision, 'REJECTED');
    institutionGate.complete();
    await tester.pumpAndSettle();
  });

  testWidgets(
      'review conflicts refresh current snapshots without submission-only loads or clearing its draft',
      (tester) async {
    _useLargeSurface(tester);
    final platformRepository = _ProjectRequestRepository()
      ..requests = [
        _request(
            id: 'platform-conflict', type: 'PLATFORM', doctorId: 'doctor-1')
      ]
      ..platformReviewError =
          const ApiException(message: 'conflict', httpStatus: 409);
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
    )));
    await tester.pumpAndSettle();
    await tester
        .tap(find.byKey(const Key('review-creation-platform-conflict')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(find.text('审核状态已变化，申请列表已刷新，请基于最新内容重试'), findsOneWidget);
    expect(find.text('当前医生名称：Dr. Chen Updated'), findsOneWidget);
    expect(find.byKey(const Key('review-creation-platform-conflict')),
        findsNothing);

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
          const ApiException(message: 'conflict', httpStatus: 409);
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
    )));
    await tester.pumpAndSettle();
    expect(institutionRepository.formConfigLoads, 1);
    await tester.enterText(
        find.byKey(const Key('institution-name')), 'Unsubmitted draft');

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: institutionPageKey,
      repository: institutionRepository,
      context: _adminContext,
      reviewMode: true,
    )));
    await tester.pump();
    await tester
        .tap(find.byKey(const Key('review-creation-institution-conflict')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(find.text('审核状态已变化，申请列表已刷新，请基于最新内容重试'), findsOneWidget);
    expect(find.text('当前平台比例：100%'), findsOneWidget);
    expect(find.text('按当前平台比例推导的医生净比例：-54.75%'), findsOneWidget);
    expect(institutionRepository.formConfigLoads, 1);
    expect(institutionRepository.institutionOptionLoads, 1);
    expect(institutionRepository.managementProjectLoads, 1);

    await tester
        .tap(find.byKey(const Key('review-creation-institution-conflict')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('creation-review-approval-blocked')),
        findsOneWidget);
    final refreshedDecision = tester.widget<DropdownButton<String>>(
      find.descendant(
        of: find.byKey(const Key('creation-review-decision')),
        matching: find.byType(DropdownButton<String>),
      ),
    );
    expect(refreshedDecision.items!.map((item) => item.value), ['REJECTED']);
    await tester.enterText(find.byKey(const Key('creation-review-note')),
        'Reject refreshed split');
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(
      institutionRepository.institutionReviews.map((review) => review.decision),
      ['APPROVED', 'REJECTED'],
    );

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: institutionPageKey,
      repository: institutionRepository,
      context: _doctorContext,
    )));
    await tester.pump();
    expect(_text(tester, 'institution-name'), 'Unsubmitted draft');
  });

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
          const ApiException(message: 'conflict', httpStatus: 409);
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
    )));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('review-creation-admin-conflict')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(adminRepository.requestListLoads, 2);
    expect(find.text('当前医生名称：Admin refreshed doctor'), findsOneWidget);
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
          const ApiException(message: 'conflict', httpStatus: 409);
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
    )));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('review-creation-legal-conflict')));
    await tester.pumpAndSettle();
    await _selectReviewDecision(tester, 'REJECTED');
    await tester.enterText(
        find.byKey(const Key('creation-review-note')), 'Legal reject');
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(legalRepository.requestListLoads, 2);
    expect(find.text('当前医生名称：Legal refreshed doctor'), findsOneWidget);
    expect(find.text('审核状态已变化，申请列表已刷新，请基于最新内容重试'), findsOneWidget);
    expect(legalRepository.formConfigLoads, 0);
    expect(legalRepository.institutionOptionLoads, 0);
    expect(legalRepository.managementProjectLoads, 0);
  });

  testWidgets(
      'immutable history is role scoped and creation reviews dispatch to the exact authority endpoint',
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
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsOneWidget);
    expect(find.byKey(const Key('professional-request-platform-other')),
        findsNothing);
    expect(find.byKey(const Key('review-creation-platform-own')), findsNothing);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('applicant-institution'),
      repository: repository,
      context: _doctorContext,
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsOneWidget);
    expect(find.byKey(const Key('professional-request-institution-other')),
        findsNothing);
    expect(find.byKey(const Key('review-creation-institution-target')),
        findsNothing);

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('non-target-legal-institution'),
      repository: repository,
      context: _legalContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsOneWidget);
    expect(find.byKey(const Key('professional-request-institution-other')),
        findsNothing);
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsNothing);
    expect(find.textContaining('Complete immutable detail'), findsOneWidget);
    expect(find.text('申请编号：institution-target'), findsOneWidget);
    expect(find.text('机构编号：inst-1'), findsOneWidget);
    expect(find.text('申请医生编号：doctor-1'), findsOneWidget);
    expect(find.text('当前医生名称：Dr. Chen'), findsOneWidget);
    expect(find.text('当前机构名称：Joysong Clinic'), findsOneWidget);
    expect(find.text('平台项目编号：project-1'), findsOneWidget);
    expect(find.text('当前平台项目名称：Hydrating Facial'), findsOneWidget);
    expect(find.text('面诊费：80.25'), findsOneWidget);
    expect(find.text('顾问比例：12.5%'), findsOneWidget);
    expect(find.text('机构比例：42.25%'), findsOneWidget);
    expect(find.text('当前平台比例：10%'), findsOneWidget);
    expect(find.text('按当前平台比例推导的医生净比例：35.25%'), findsOneWidget);
    expect(find.text('审核意见：未提供'), findsOneWidget);
    expect(find.text('审核人：未提供'), findsOneWidget);
    expect(find.text('生成平台项目：未提供'), findsOneWidget);
    expect(find.byType(TextField), findsNothing,
        reason: 'the submitted snapshot must be immutable');

    await tester
        .tap(find.byKey(const Key('review-creation-institution-target')));
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
        reason: 'blank rejection note must not close the dialog');
    expect(find.text('驳回时必须填写审核意见'), findsOneWidget);
    await tester.enterText(
        find.byKey(const Key('creation-review-note')), 'Not ready');
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.institutionReviews.single,
        (id: 'institution-target', decision: 'REJECTED', note: 'Not ready'));

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      repository: repository,
      context: _nonTargetLegalContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsNothing);
    expect(find.byKey(const Key('review-creation-institution-target')),
        findsNothing);

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      key: const ValueKey('non-admin-platform-review'),
      repository: repository,
      context: _legalContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsNothing);
    expect(find.byKey(const Key('professional-request-platform-other')),
        findsNothing);
    expect(find.byKey(const Key('review-creation-platform-own')), findsNothing);

    await tester.pumpWidget(_app(PlatformProjectRequestPage(
      key: const ValueKey('admin-platform'),
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('professional-request-platform-own')),
        findsOneWidget);
    expect(find.byKey(const Key('professional-request-institution-target')),
        findsNothing);
    expect(find.text('项目标语：空字符串'), findsOneWidget);
    expect(find.text('封面图：空字符串'), findsOneWidget);
    expect(find.text('项目图片：空列表'), findsOneWidget);
    expect(find.text('分类标签：空列表'), findsOneWidget);
    await tester.tap(find.byKey(const Key('review-creation-platform-own')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.platformReviews.single,
        (id: 'platform-own', decision: 'APPROVED', note: ''));

    await tester.pumpWidget(_app(InstitutionProjectRequestsPage(
      key: const ValueKey('admin-institution'),
      repository: repository,
      context: _adminContext,
      reviewMode: true,
    )));
    await tester.pumpAndSettle();
    expect(find.text('项目标语：未提供'), findsOneWidget);
    expect(find.text('封面图：未提供'), findsOneWidget);
    expect(find.text('项目图片：未提供'), findsOneWidget);
    expect(find.text('分类标签：未提供'), findsWidgets);
    await tester
        .tap(find.byKey(const Key('review-creation-institution-other')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('creation-review-confirm')));
    await tester.pumpAndSettle();
    expect(repository.institutionReviews.last,
        (id: 'institution-other', decision: 'APPROVED', note: ''));
  });
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
    {required String referencePrice}) async {
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
  required String institutionRate,
}) async {
  final values = {
    'institution-name': 'Clinic override',
    'institution-category': 'Clinic category',
    'institution-description': 'Clinic description',
    'institution-tags': 'clinic,signature',
    'institution-slogan': 'Clinic glow',
    'institution-detail-content': 'Override plain detail',
    'institution-price': price,
    'institution-original-price': '999.99',
    'institution-sales-count': '7',
    'institution-consultation-fee': '80.25',
    'institution-consultant-rate': '30',
    'institution-rate': institutionRate,
    'institution-notes': 'Clinic notes',
  };
  for (final entry in values.entries) {
    await tester.enterText(find.byKey(Key(entry.key)), entry.value);
  }
}

Future<void> _preparePlatformConflictDraft(WidgetTester tester) async {
  await _fillPlatformDraft(tester, referencePrice: '199.99');
  await _choose(tester, const Key('platform-currency'), 'USD');
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
  expect(_dropdownValue(tester, 'platform-currency'), 'USD');
  expect(find.text('https://cdn.example.com/conflict-platform-cover.jpg'),
      findsOneWidget);
  expect(find.text('https://cdn.example.com/conflict-platform-gallery.jpg'),
      findsOneWidget);
}

Future<void> _prepareInstitutionConflictDraft(WidgetTester tester) async {
  await _choose(tester, const Key('institution-id'), 'Joysong Clinic');
  await _choose(tester, const Key('institution-project'), 'Hydrating Facial');
  await _fillInstitutionDraft(
    tester,
    price: '799.99',
    institutionRate: '40',
  );
  await _choose(tester, const Key('institution-currency'), 'USD');
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
    'institution-original-price': '999.99',
    'institution-sales-count': '7',
    'institution-consultation-fee': '80.25',
    'institution-consultant-rate': '30',
    'institution-rate': '40',
    'institution-notes': 'Clinic notes',
  }.entries) {
    expect(_text(tester, entry.key), entry.value);
  }
  expect(_dropdownValue(tester, 'institution-currency'), 'USD');
  expect(
    tester
        .widget<SwitchListTile>(find.byKey(const Key('institution-is-active')))
        .value,
    isFalse,
  );
  expect(find.text('https://cdn.example.com/conflict-clinic-cover.jpg'),
      findsOneWidget);
  expect(find.text('https://cdn.example.com/conflict-clinic-gallery.jpg'),
      findsOneWidget);
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
      'currency': 'CNY',
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
      currency: 'CNY',
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
        platformRate: formPlatformRate);
  }

  @override
  Future<void> submitPlatformProjectRequest(
      PlatformProjectRequestDraft draft) async {
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
      InstitutionProjectRequestDraft draft) async {
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
