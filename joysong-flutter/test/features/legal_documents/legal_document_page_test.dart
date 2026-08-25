import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_models.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_repository.dart';
import 'package:joysong_flutter/features/legal_documents/presentation/legal_document_page.dart';

void main() {
  testWidgets(
    'maps English locale and renders title metadata and rich content',
    (tester) async {
      final repository = _FakeRepository(
        (call) async => _document(locale: call.locale),
      );

      await tester.pumpWidget(
        _app(
          locale: const Locale('en'),
          repository: repository,
          type: LegalDocumentType.privacyPolicy,
        ),
      );
      await tester.pumpAndSettle();

      expect(repository.calls.single.locale, 'en-US');
      expect(find.text('Privacy policy'), findsOneWidget);
      expect(find.text('V2 · 2026-08-25'), findsOneWidget);
      expect(find.textContaining('Privacy body'), findsOneWidget);
      expect(find.byType(RichContentView), findsOneWidget);
      expect(find.byType(SelectableText), findsOneWidget);
    },
  );

  testWidgets('shows localized loading and not-found states without a body', (
    tester,
  ) async {
    final pending = Completer<LegalDocument>();
    final repository = _FakeRepository((_) => pending.future);

    await tester.pumpWidget(
      _app(
        locale: const Locale('zh'),
        repository: repository,
        type: LegalDocumentType.userAgreement,
      ),
    );
    await tester.pump();

    expect(repository.calls.single.locale, 'zh-CN');
    expect(find.text('正在加载协议…'), findsOneWidget);
    expect(find.byType(RichContentView), findsNothing);

    pending.completeError(const LegalDocumentNotFoundException());
    await tester.pumpAndSettle();

    expect(find.text('暂未发布该协议'), findsOneWidget);
    expect(find.byType(RichContentView), findsNothing);
  });

  testWidgets(
    'shows localized failure and retry force-refreshes without fallback body',
    (tester) async {
      var attempt = 0;
      final repository = _FakeRepository((call) async {
        attempt += 1;
        if (attempt == 1) throw StateError('offline');
        return _document(locale: call.locale);
      });

      await tester.pumpWidget(
        _app(
          locale: const Locale('en'),
          repository: repository,
          type: LegalDocumentType.privacyPolicy,
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.text('Could not load this document. Please try again.'),
        findsOneWidget,
      );
      expect(find.byType(RichContentView), findsNothing);

      await tester.tap(find.text('Retry'));
      await tester.pumpAndSettle();

      expect(repository.calls, hasLength(2));
      expect(repository.calls.last.forceRefresh, isTrue);
      expect(find.textContaining('Privacy body'), findsOneWidget);
    },
  );

  testWidgets('owns a controller that can be disposed while loading', (
    tester,
  ) async {
    final pending = Completer<LegalDocument>();
    final repository = _FakeRepository((_) => pending.future);

    await tester.pumpWidget(
      _app(
        locale: const Locale('en'),
        repository: repository,
        type: LegalDocumentType.privacyPolicy,
      ),
    );
    await tester.pump();
    await tester.pumpWidget(const SizedBox.shrink());
    pending.complete(_document(locale: 'en-US'));
    await tester.pump();

    expect(tester.takeException(), isNull);
  });
}

Widget _app({
  required Locale locale,
  required LegalDocumentRepository repository,
  required LegalDocumentType type,
}) {
  return MaterialApp(
    locale: locale,
    supportedLocales: const [Locale('zh'), Locale('en')],
    localizationsDelegates: const [
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    home: LegalDocumentPage(type: type, repository: repository),
  );
}

LegalDocument _document({required String locale}) => LegalDocument(
  type: LegalDocumentType.privacyPolicy,
  locale: locale,
  version: 2,
  title: 'Privacy policy',
  contentHtml: '<h1>Privacy body</h1><p>Complete body</p>',
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
