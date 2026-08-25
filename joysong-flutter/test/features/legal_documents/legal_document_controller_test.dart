import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_models.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_repository.dart';
import 'package:joysong_flutter/features/legal_documents/presentation/legal_document_controller.dart';

void main() {
  test('moves from initial through loading to ready', () async {
    final pending = Completer<LegalDocument>();
    final repository = _FakeRepository((_) => pending.future);
    final controller = LegalDocumentController(
      repository: repository,
      type: LegalDocumentType.privacyPolicy,
    );
    final statuses = <LegalDocumentStatus>[];
    controller.addListener(() => statuses.add(controller.state.status));

    expect(controller.state.status, LegalDocumentStatus.initial);
    final load = controller.load(locale: 'en-US');
    expect(controller.state.status, LegalDocumentStatus.loading);
    pending.complete(_document(locale: 'en-US'));
    await load;

    expect(statuses, [LegalDocumentStatus.loading, LegalDocumentStatus.ready]);
    expect(controller.state.document?.title, 'Privacy policy');
  });

  test('distinguishes not found from other failures', () async {
    final notFoundController = LegalDocumentController(
      repository: _FakeRepository(
        (_) async => throw const LegalDocumentNotFoundException(),
      ),
      type: LegalDocumentType.userAgreement,
    );
    final failedController = LegalDocumentController(
      repository: _FakeRepository((_) async => throw StateError('offline')),
      type: LegalDocumentType.userAgreement,
    );

    await notFoundController.load(locale: 'zh-CN');
    await failedController.load(locale: 'zh-CN');

    expect(notFoundController.state.status, LegalDocumentStatus.notFound);
    expect(notFoundController.state.error, isNull);
    expect(failedController.state.status, LegalDocumentStatus.failure);
    expect(failedController.state.error, isA<StateError>());
  });

  test('retry reloads the last locale with force refresh', () async {
    final repository = _FakeRepository((call) async {
      if (call.forceRefresh) return _document(locale: call.locale);
      throw StateError('offline');
    });
    final controller = LegalDocumentController(
      repository: repository,
      type: LegalDocumentType.userAgreement,
    );

    await controller.load(locale: 'zh-CN');
    expect(controller.state.status, LegalDocumentStatus.failure);
    await controller.retry();

    expect(controller.state.status, LegalDocumentStatus.ready);
    expect(repository.calls.map((call) => call.locale), ['zh-CN', 'zh-CN']);
    expect(repository.calls.map((call) => call.forceRefresh), [false, true]);
  });

  test('ignores stale async completions from an earlier load', () async {
    final chinese = Completer<LegalDocument>();
    final english = Completer<LegalDocument>();
    final repository = _FakeRepository(
      (call) => call.locale == 'zh-CN' ? chinese.future : english.future,
    );
    final controller = LegalDocumentController(
      repository: repository,
      type: LegalDocumentType.privacyPolicy,
    );

    final first = controller.load(locale: 'zh-CN');
    final second = controller.load(locale: 'en-US');
    english.complete(_document(locale: 'en-US'));
    await second;
    chinese.complete(_document(locale: 'zh-CN'));
    await first;

    expect(controller.state.status, LegalDocumentStatus.ready);
    expect(controller.state.document?.locale, 'en-US');
  });

  test('does not notify after disposal when a request completes', () async {
    final pending = Completer<LegalDocument>();
    final controller = LegalDocumentController(
      repository: _FakeRepository((_) => pending.future),
      type: LegalDocumentType.privacyPolicy,
    );
    var notifications = 0;
    controller.addListener(() => notifications += 1);

    final load = controller.load(locale: 'en-US');
    expect(notifications, 1);
    controller.dispose();
    pending.complete(_document(locale: 'en-US'));
    await load;

    expect(notifications, 1);
  });
}

LegalDocument _document({required String locale}) => LegalDocument(
  type: LegalDocumentType.privacyPolicy,
  locale: locale,
  version: 2,
  title: 'Privacy policy',
  contentHtml: '<p>Privacy body</p>',
  publishedAt: DateTime(2026, 8, 25, 10),
  contentSha256: 'hash-en',
);

final class _LoadCall {
  const _LoadCall({
    required this.type,
    required this.locale,
    required this.forceRefresh,
  });

  final LegalDocumentType type;
  final String locale;
  final bool forceRefresh;
}

final class _FakeRepository implements LegalDocumentRepository {
  _FakeRepository(this._handler);

  final Future<LegalDocument> Function(_LoadCall call) _handler;
  final calls = <_LoadCall>[];

  @override
  Future<LegalDocument> load({
    required LegalDocumentType type,
    required String locale,
    bool forceRefresh = false,
  }) {
    final call = _LoadCall(
      type: type,
      locale: locale,
      forceRefresh: forceRefresh,
    );
    calls.add(call);
    return _handler(call);
  }
}
