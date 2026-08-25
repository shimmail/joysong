import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';

import 'translation_test_fixtures.dart';

void main() {
  testWidgets('renders source first and replaces it after translation',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_translationHost(controller: controller));

    expect(find.text('项目说明'), findsOneWidget);
    expect(repository.calls, hasLength(1));

    repository.completeNext('Project description');
    await _pumpTranslation(tester);

    expect(find.text('Project description'), findsOneWidget);
    expect(find.text('项目说明'), findsNothing);
  });

  testWidgets('disabled scope renders source without a controller call',
      (tester) async {
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _translationHost(controller: controller, enabled: false),
    );

    expect(find.text('项目说明'), findsOneWidget);
    expect(repository.calls, isEmpty);
  });

  testWidgets('missing scope renders source without a controller call',
      (tester) async {
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      const Directionality(
        textDirection: TextDirection.ltr,
        child: AutoTranslationBuilder(
          request: _initialRequest,
          builder: _visibleText,
        ),
      ),
    );

    expect(find.text('项目说明'), findsOneWidget);
    expect(repository.calls, isEmpty);
  });

  testWidgets('request changes reject stale completion and use the new key',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_translationHost(controller: controller));
    await tester.pumpWidget(
      _translationHost(
        controller: controller,
        request: const AutoTranslationRequest(
          contentType: 'project',
          contentId: 'project-1',
          field: 'summary',
          sourceText: '新的简介',
        ),
      ),
    );

    expect(find.text('新的简介'), findsOneWidget);
    expect(repository.calls.map((call) => (call.text, call.contentType)), [
      ('项目说明', 'project'),
      ('新的简介', 'project'),
    ]);

    repository.completePending(0, 'Stale description');
    await _pumpTranslation(tester);
    expect(find.text('新的简介'), findsOneWidget);
    expect(find.text('Stale description'), findsNothing);

    repository.completeNext('New summary');
    await _pumpTranslation(tester);
    expect(find.text('New summary'), findsOneWidget);
  });

  testWidgets('controller changes reject the old controller completion',
      (tester) async {
    final oldRepository = RecordingTranslationRepository()
      ..holdResponses = true;
    final newRepository = RecordingTranslationRepository()
      ..holdResponses = true;
    final oldController = _activeController(oldRepository);
    final newController = _activeController(newRepository);
    addTearDown(oldController.dispose);
    addTearDown(newController.dispose);

    await tester.pumpWidget(_translationHost(controller: oldController));
    await tester.pumpWidget(_translationHost(controller: newController));

    expect(oldRepository.calls, hasLength(1));
    expect(newRepository.calls, hasLength(1));
    oldRepository.completeNext('Old controller result');
    await _pumpTranslation(tester);
    expect(find.text('项目说明'), findsOneWidget);
    expect(find.text('Old controller result'), findsNothing);

    newRepository.completeNext('New controller result');
    await _pumpTranslation(tester);
    expect(find.text('New controller result'), findsOneWidget);
  });

  testWidgets('target changes reschedule and reject the old target result',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_translationHost(controller: controller));
    controller.synchronize(
      enabled: true,
      authenticated: true,
      targetLanguage: 'fr-FR',
    );
    await tester.pumpWidget(
      _translationHost(controller: controller, targetLanguage: 'fr-FR'),
    );

    expect(repository.calls.map((call) => call.targetLanguage), [
      'en-US',
      'fr-FR',
    ]);
    repository.completePending(0, 'Old English result');
    await _pumpTranslation(tester);
    expect(find.text('项目说明'), findsOneWidget);
    expect(find.text('Old English result'), findsNothing);

    repository.completeNext('Description du projet');
    await _pumpTranslation(tester);
    expect(find.text('Description du projet'), findsOneWidget);
  });

  testWidgets('disabling immediately restores exact source from translation',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_translationHost(controller: controller));
    repository.completeNext('Visible translation');
    await _pumpTranslation(tester);
    expect(find.text('Visible translation'), findsOneWidget);

    await tester.pumpWidget(
      _translationHost(controller: controller, enabled: false),
    );

    expect(find.text(_initialRequest.sourceText), findsOneWidget);
    expect(find.text('Visible translation'), findsNothing);
    expect(repository.calls, hasLength(1));
  });

  testWidgets(
      'disable and reactivation reject completion for the previous request',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const replacementRequest = AutoTranslationRequest(
      contentType: 'project',
      contentId: 'project-2',
      field: 'description',
      sourceText: '第二个项目说明',
    );

    await tester.pumpWidget(_translationHost(controller: controller));
    await tester.pumpWidget(
      _translationHost(controller: controller, enabled: false),
    );
    await tester.pumpWidget(
      _translationHost(
        controller: controller,
        request: replacementRequest,
      ),
    );

    expect(find.text(replacementRequest.sourceText), findsOneWidget);
    expect(repository.calls, hasLength(2));
    repository.completePending(0, 'Late previous translation');
    await _pumpTranslation(tester);
    expect(find.text(replacementRequest.sourceText), findsOneWidget);
    expect(find.text('Late previous translation'), findsNothing);

    repository.completeNext('Current translation');
    await _pumpTranslation(tester);
    expect(find.text('Current translation'), findsOneWidget);
  });

  testWidgets('late completion after widget disposal produces no Flutter error',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_translationHost(controller: controller));
    await tester.pumpWidget(const SizedBox.shrink());
    repository.completeNext('Disposed result');
    await _pumpTranslation(tester);

    expect(tester.takeException(), isNull);
  });

  testWidgets('controller disposal and late repository completion are safe',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);

    await tester.pumpWidget(_translationHost(controller: controller));
    controller.dispose();
    await tester.pump();
    expect(find.text(_initialRequest.sourceText), findsOneWidget);
    expect(tester.takeException(), isNull);

    repository.completeNext('Result after controller disposal');
    await _pumpTranslation(tester);
    expect(find.text(_initialRequest.sourceText), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('AutoTranslatedText forwards every requested Text property',
      (tester) async {
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const style = TextStyle(fontSize: 21, fontWeight: FontWeight.w600);

    await tester.pumpWidget(
      Directionality(
        textDirection: TextDirection.ltr,
        child: AutoTranslationScope(
          controller: controller,
          enabled: false,
          targetLanguage: 'en-US',
          child: const AutoTranslatedText(
            request: _initialRequest,
            style: style,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.end,
            semanticsLabel: 'project description',
          ),
        ),
      ),
    );

    final text = tester.widget<Text>(find.byType(Text));
    expect(text.data, same(_initialRequest.sourceText));
    expect(text.style, same(style));
    expect(text.maxLines, 2);
    expect(text.overflow, TextOverflow.ellipsis);
    expect(text.textAlign, TextAlign.end);
    expect(text.semanticsLabel, 'project description');
    expect(repository.calls, isEmpty);
  });

  testWidgets('stable builder reuses its request across equivalent rebuilds',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_stableTranslationHost(controller: controller));
    final first = tester
        .widget<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
        .request;
    repository.failNext();
    await _pumpTranslation(tester);

    await tester.pumpWidget(_stableTranslationHost(controller: controller));
    final rebuilt = tester
        .widget<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
        .request;

    expect(rebuilt, same(first));
    expect(repository.calls, hasLength(1));
  });

  testWidgets('stable builder changes identity when a request value changes',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_stableTranslationHost(controller: controller));
    final before = tester
        .widget<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
        .request;
    await tester.pumpWidget(
      _stableTranslationHost(
        controller: controller,
        contentId: 'project-2',
        field: 'summary',
        sourceText: '新的简介',
      ),
    );
    final after = tester
        .widget<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
        .request;

    expect(after, isNot(same(before)));
    expect(after.contentId, 'project-2');
    expect(after.field, 'summary');
    expect(after.sourceText, '新的简介');
  });

  for (final testCase in _stableIdentityCases) {
    testWidgets('stable builder changes identity when ${testCase.name} changes',
        (tester) async {
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = _activeController(repository);
      addTearDown(controller.dispose);

      await tester.pumpWidget(_stableTranslationHost(controller: controller));
      final before = tester
          .widget<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
          .request;
      await tester.pumpWidget(
        _stableTranslationHost(
          controller: controller,
          contentType: testCase.contentType,
          contentId: testCase.contentId,
          field: testCase.field,
          sourceText: testCase.sourceText,
          validator: testCase.validator,
        ),
      );
      final after = tester
          .widget<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
          .request;

      expect(after, isNot(same(before)));
      expect(after.contentType, testCase.contentType);
      expect(after.contentId, testCase.contentId);
      expect(after.field, testCase.field);
      expect(after.sourceText, testCase.sourceText);
      expect(identical(after.validator, testCase.validator), isTrue);
    });
  }

  testWidgets('disabled stable builder stays source-only and rejects late work',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_stableTranslationHost(controller: controller));
    await tester.pumpWidget(
      _stableTranslationHost(controller: controller, enabled: false),
    );
    repository.completeNext('Late translation');
    await _pumpTranslation(tester);

    expect(find.text('项目说明'), findsOneWidget);
    expect(find.text('Late translation'), findsNothing);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
  });

  testWidgets('StableAutoTranslatedText forwards every requested Text property',
      (tester) async {
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const style = TextStyle(fontSize: 21, fontWeight: FontWeight.w600);

    await tester.pumpWidget(
      Directionality(
        textDirection: TextDirection.ltr,
        child: AutoTranslationScope(
          controller: controller,
          enabled: true,
          targetLanguage: 'en-US',
          child: const StableAutoTranslatedText(
            enabled: false,
            contentType: 'project',
            contentId: 'project-1',
            field: 'description',
            sourceText: '项目说明',
            style: style,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.end,
            semanticsLabel: 'project description',
          ),
        ),
      ),
    );

    final text = tester.widget<Text>(find.byType(Text));
    expect(text.data, '项目说明');
    expect(text.style, same(style));
    expect(text.maxLines, 2);
    expect(text.overflow, TextOverflow.ellipsis);
    expect(text.textAlign, TextAlign.end);
    expect(text.semanticsLabel, 'project description');
    expect(repository.calls, isEmpty);
  });
}

const _initialRequest = AutoTranslationRequest(
  contentType: 'project',
  contentId: 'project-1',
  field: 'description',
  sourceText: '项目说明',
);

Widget _visibleText(BuildContext context, String visibleText) =>
    Text(visibleText);

Future<void> _pumpTranslation(WidgetTester tester) async {
  await tester.pump();
  await tester.pump();
}

AutoTranslationController _activeController(
  RecordingTranslationRepository repository,
) =>
    AutoTranslationController(repository: repository)
      ..synchronize(
        enabled: true,
        authenticated: true,
        targetLanguage: 'en-US',
      );

Widget _translationHost({
  required AutoTranslationController controller,
  bool enabled = true,
  String targetLanguage = 'en-US',
  AutoTranslationRequest request = _initialRequest,
}) {
  return Directionality(
    textDirection: TextDirection.ltr,
    child: AutoTranslationScope(
      controller: controller,
      enabled: enabled,
      targetLanguage: targetLanguage,
      child: AutoTranslationBuilder(
        request: request,
        builder: _visibleText,
      ),
    ),
  );
}

Widget _stableTranslationHost({
  required AutoTranslationController controller,
  bool enabled = true,
  String contentType = 'project',
  String contentId = 'project-1',
  String field = 'description',
  String sourceText = '项目说明',
  TranslationValidator? validator,
}) {
  return Directionality(
    textDirection: TextDirection.ltr,
    child: AutoTranslationScope(
      controller: controller,
      enabled: true,
      targetLanguage: 'en-US',
      child: StableAutoTranslationBuilder(
        enabled: enabled,
        contentType: contentType,
        contentId: contentId,
        field: field,
        sourceText: sourceText,
        validator: validator,
        builder: _visibleText,
      ),
    ),
  );
}

const _stableIdentityCases = <_StableIdentityCase>[
  _StableIdentityCase(
    name: 'content type',
    contentType: 'appointment',
    contentId: 'project-1',
    field: 'description',
    sourceText: '项目说明',
  ),
  _StableIdentityCase(
    name: 'content ID',
    contentType: 'project',
    contentId: 'project-2',
    field: 'description',
    sourceText: '项目说明',
  ),
  _StableIdentityCase(
    name: 'field',
    contentType: 'project',
    contentId: 'project-1',
    field: 'summary',
    sourceText: '项目说明',
  ),
  _StableIdentityCase(
    name: 'source text',
    contentType: 'project',
    contentId: 'project-1',
    field: 'description',
    sourceText: '新的简介',
  ),
  _StableIdentityCase(
    name: 'validator instance',
    contentType: 'project',
    contentId: 'project-1',
    field: 'description',
    sourceText: '项目说明',
    validator: _acceptTranslation,
  ),
];

final class _StableIdentityCase {
  const _StableIdentityCase({
    required this.name,
    required this.contentType,
    required this.contentId,
    required this.field,
    required this.sourceText,
    this.validator,
  });

  final String name;
  final String contentType;
  final String contentId;
  final String field;
  final String sourceText;
  final TranslationValidator? validator;
}

bool _acceptTranslation(String source, String translated) => true;
