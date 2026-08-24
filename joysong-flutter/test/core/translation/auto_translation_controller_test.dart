import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';

import 'translation_test_fixtures.dart';

void main() {
  group('containsChineseText', () {
    test('covers extension A, unified, and compatibility ideographs exactly',
        () {
      for (final value in <String>[
        '\u3400',
        '\u4DBF',
        '\u4E00',
        '\u9FFF',
        '\uF900',
        '\uFAFF',
      ]) {
        expect(containsChineseText(value), isTrue, reason: value);
      }
      for (final value in <String>[
        '\u33FF',
        '\u4DC0',
        '\uF8FF',
        '\uFB00',
        'Alice 2026',
        '',
      ]) {
        expect(containsChineseText(value), isFalse, reason: value);
      }
    });
  });

  group('eligibility and fallback', () {
    test('translates Chinese only while automatic mode is active', () async {
      final repository = RecordingTranslationRepository();
      final controller = AutoTranslationController(repository: repository)
        ..synchronize(
          enabled: true,
          authenticated: true,
          targetLanguage: 'en-US',
        );
      addTearDown(controller.dispose);

      final translated = await controller.translateOrSource(
        const AutoTranslationRequest(
          contentType: 'article',
          contentId: 'article-1',
          field: 'title',
          sourceText: '术后护理',
        ),
      );
      final untouched = await controller.translateOrSource(
        const AutoTranslationRequest(
          contentType: 'article',
          contentId: 'article-1',
          field: 'author',
          sourceText: 'Alice 2026',
        ),
      );

      expect(translated, 'en-US:术后护理');
      expect(untouched, 'Alice 2026');
      expect(repository.calls, hasLength(1));
    });

    test('inactive states return source without repository calls', () async {
      final repository = RecordingTranslationRepository();
      final controller = AutoTranslationController(repository: repository);
      addTearDown(controller.dispose);
      const request = AutoTranslationRequest(
        contentType: 'article',
        contentId: 'article-1',
        field: 'title',
        sourceText: '术后护理',
      );

      expect(await controller.translateOrSource(request), request.sourceText);
      for (final state in <({bool enabled, bool authenticated, String target})>[
        (enabled: false, authenticated: true, target: 'en-US'),
        (enabled: true, authenticated: false, target: 'en-US'),
        (enabled: true, authenticated: true, target: ''),
      ]) {
        controller.synchronize(
          enabled: state.enabled,
          authenticated: state.authenticated,
          targetLanguage: state.target,
        );
        expect(await controller.translateOrSource(request), request.sourceText);
      }
      expect(repository.calls, isEmpty);
    });

    test('a controller without a repository is source-only', () async {
      final controller = AutoTranslationController()
        ..synchronize(
          enabled: true,
          authenticated: true,
          targetLanguage: 'en-US',
        );
      addTearDown(controller.dispose);

      expect(
        await controller.translateOrSource(_request('source-only')),
        '中文source-only',
      );
    });

    test('failure returns source and remains retryable', () async {
      final repository = RecordingTranslationRepository()
        ..failuresRemaining = 1;
      final controller = _activeController(repository);
      addTearDown(controller.dispose);
      final request = _request('failure');

      expect(await controller.translateOrSource(request), request.sourceText);
      expect(
        await controller.translateOrSource(request),
        'en-US:${request.sourceText}',
      );
      expect(repository.calls, hasLength(2));
    });

    test('validator rejection returns source and remains retryable', () async {
      final repository = RecordingTranslationRepository()
        ..translations.addAll(<String>['invalid', 'accepted translation']);
      final controller = _activeController(repository);
      addTearDown(controller.dispose);
      final request = AutoTranslationRequest(
        contentType: 'article',
        contentId: 'validator',
        field: 'body',
        sourceText: '中文validator',
        validator: (_, translated) => translated.startsWith('accepted'),
      );

      expect(await controller.translateOrSource(request), request.sourceText);
      expect(
        await controller.translateOrSource(request),
        'accepted translation',
      );
      expect(repository.calls, hasLength(2));
    });

    test('a stateful validator is evaluated once for its repository candidate',
        () async {
      final repository = RecordingTranslationRepository();
      final controller = _activeController(repository);
      addTearDown(controller.dispose);
      var evaluations = 0;
      final request = AutoTranslationRequest(
        contentType: 'article',
        contentId: 'stateful-validator',
        field: 'body',
        sourceText: '中文stateful-validator',
        validator: (_, __) => ++evaluations == 1,
      );

      expect(
        await controller.translateOrSource(request),
        'en-US:${request.sourceText}',
      );
      expect(evaluations, 1);
      expect(repository.calls, hasLength(1));

      final cachedCaller = AutoTranslationRequest(
        contentType: request.contentType,
        contentId: request.contentId,
        field: request.field,
        sourceText: request.sourceText,
      );
      expect(
        await controller.translateOrSource(cachedCaller),
        'en-US:${request.sourceText}',
      );
      expect(repository.calls, hasLength(1));
    });

    test('a validator-rejected candidate is evaluated once and never cached',
        () async {
      final repository = RecordingTranslationRepository();
      final controller = _activeController(repository);
      addTearDown(controller.dispose);
      var evaluations = 0;
      final request = AutoTranslationRequest(
        contentType: 'article',
        contentId: 'rejected-validator',
        field: 'body',
        sourceText: '中文rejected-validator',
        validator: (_, __) {
          evaluations += 1;
          return false;
        },
      );

      expect(await controller.translateOrSource(request), request.sourceText);
      expect(await controller.translateOrSource(request), request.sourceText);
      expect(evaluations, 2);
      expect(repository.calls, hasLength(2));
    });

    test('a true synchronous repository throw returns source and retries',
        () async {
      final repository = SynchronousThrowTranslationRepository();
      final controller = AutoTranslationController(repository: repository)
        ..synchronize(
          enabled: true,
          authenticated: true,
          targetLanguage: 'en-US',
        );
      addTearDown(controller.dispose);
      final request = _request('sync-throw');

      expect(await controller.translateOrSource(request), request.sourceText);
      expect(await controller.translateOrSource(request), request.sourceText);
      expect(repository.calls, 2);
    });
  });

  group('identity, single-flight, and cache', () {
    test('identical concurrent requests use one repository call', () async {
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = _activeController(repository);
      addTearDown(controller.dispose);
      final request = _request('same');

      final first = controller.translateOrSource(request);
      final second = controller.translateOrSource(request);
      await pumpEventQueue();
      expect(repository.calls, hasLength(1));

      repository.completeNext('shared');
      expect(await Future.wait(<Future<String>>[first, second]),
          ['shared', 'shared']);
    });

    test('validators are per caller but excluded from single-flight identity',
        () async {
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = _activeController(repository);
      addTearDown(controller.dispose);
      final accepted = _request('validator-flight');
      final rejected = AutoTranslationRequest(
        contentType: accepted.contentType,
        contentId: accepted.contentId,
        field: accepted.field,
        sourceText: accepted.sourceText,
        validator: (_, translated) => translated == 'different',
      );

      final acceptedFuture = controller.translateOrSource(accepted);
      final rejectedFuture = controller.translateOrSource(rejected);
      await pumpEventQueue();
      expect(repository.calls, hasLength(1));
      repository.completeNext('shared');

      expect(await acceptedFuture, 'shared');
      expect(await rejectedFuture, rejected.sourceText);
      repository.holdResponses = false;
      expect(await controller.translateOrSource(accepted),
          'en-US:${accepted.sourceText}');
      expect(repository.calls, hasLength(2));
    });

    test('cached text is revalidated for each caller and rejection retries',
        () async {
      final repository = RecordingTranslationRepository()
        ..translations.addAll(<String>['permissive', 'strictly accepted']);
      final controller = _activeController(repository);
      addTearDown(controller.dispose);
      final permissive = _request('cached-validator');
      final strict = AutoTranslationRequest(
        contentType: permissive.contentType,
        contentId: permissive.contentId,
        field: permissive.field,
        sourceText: permissive.sourceText,
        validator: (_, translated) => translated.startsWith('strictly'),
      );

      expect(await controller.translateOrSource(permissive), 'permissive');
      expect(await controller.translateOrSource(strict), 'strictly accepted');
      expect(repository.calls, hasLength(2));
    });

    test('identity includes target, type, ID, field, and exact source text',
        () async {
      final repository = RecordingTranslationRepository();
      final controller = _activeController(repository);
      addTearDown(controller.dispose);

      await controller.translateOrSource(_request('base'));
      await controller
          .translateOrSource(_request('base', contentType: 'project'));
      await controller.translateOrSource(_request('base', contentId: 'other'));
      await controller.translateOrSource(_request('base', field: 'body'));
      await controller.translateOrSource(_request('base '));
      controller.synchronize(
        enabled: true,
        authenticated: true,
        targetLanguage: 'fr-FR',
      );
      await controller.translateOrSource(_request('base'));

      expect(repository.calls, hasLength(6));
      expect(repository.calls.last.targetLanguage, 'fr-FR');
      expect(repository.calls[4].text, '中文base ');
    });

    test('successful cache is bounded LRU and reads refresh recency', () async {
      final repository = RecordingTranslationRepository();
      final controller = AutoTranslationController(
        repository: repository,
        maxCacheEntries: 2,
      )..synchronize(
          enabled: true,
          authenticated: true,
          targetLanguage: 'en-US',
        );
      addTearDown(controller.dispose);

      await controller.translateOrSource(_request('A'));
      await controller.translateOrSource(_request('B'));
      await controller.translateOrSource(_request('A'));
      await controller.translateOrSource(_request('C'));
      await controller.translateOrSource(_request('B'));

      expect(repository.calls.map((call) => call.text), [
        '中文A',
        '中文B',
        '中文C',
        '中文B',
      ]);
    });
  });

  group('FIFO scheduling and invalidation', () {
    test('runs at most three calls and starts queued work FIFO', () async {
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = _activeController(repository);
      addTearDown(controller.dispose);
      final futures = <Future<String>>[
        for (var index = 0; index < 5; index += 1)
          controller.translateOrSource(_request('$index')),
      ];

      await pumpEventQueue();
      expect(repository.calls.map((call) => call.text), [
        '中文0',
        '中文1',
        '中文2',
      ]);
      expect(repository.maximumActiveCalls, 3);

      repository.completeNext('zero');
      await pumpEventQueue();
      expect(repository.calls[3].text, '中文3');
      repository.completeNext('one');
      await pumpEventQueue();
      expect(repository.calls[4].text, '中文4');
      expect(repository.maximumActiveCalls, 3);

      repository.completeNext('two');
      repository.completeNext('three');
      repository.completeNext('four');
      expect(
          await Future.wait(futures), ['zero', 'one', 'two', 'three', 'four']);
    });

    test('an asynchronous failure releases its slot and pumps FIFO work',
        () async {
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = AutoTranslationController(
        repository: repository,
        maxConcurrent: 1,
      )..synchronize(
          enabled: true,
          authenticated: true,
          targetLanguage: 'en-US',
        );
      addTearDown(controller.dispose);
      final requests = <AutoTranslationRequest>[
        _request('async-failure-0'),
        _request('async-failure-1'),
        _request('async-failure-2'),
      ];
      final futures = requests.map(controller.translateOrSource).toList();
      await pumpEventQueue();
      expect(repository.calls.map((call) => call.text), ['中文async-failure-0']);

      repository.failNext();
      await pumpEventQueue();
      expect(repository.calls.map((call) => call.text), [
        '中文async-failure-0',
        '中文async-failure-1',
      ]);
      repository.completeNext('second');
      await pumpEventQueue();
      expect(repository.calls.map((call) => call.text), [
        '中文async-failure-0',
        '中文async-failure-1',
        '中文async-failure-2',
      ]);
      repository.completeNext('third');

      expect(
        await Future.wait(futures),
        [requests.first.sourceText, 'second', 'third'],
      );
      expect(repository.maximumActiveCalls, 1);
    });

    test('validator rollover cannot publish or cache the old candidate',
        () async {
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = AutoTranslationController(
        repository: repository,
        maxConcurrent: 2,
      )..synchronize(
          enabled: true,
          authenticated: true,
          targetLanguage: 'en-US',
        );
      addTearDown(controller.dispose);
      var evaluations = 0;
      final oldRequest = AutoTranslationRequest(
        contentType: 'article',
        contentId: 'validator-rollover',
        field: 'title',
        sourceText: '中文validator-rollover',
        validator: (_, __) {
          evaluations += 1;
          controller
            ..synchronize(
              enabled: true,
              authenticated: true,
              targetLanguage: 'fr-FR',
            )
            ..synchronize(
              enabled: true,
              authenticated: true,
              targetLanguage: 'en-US',
            );
          return true;
        },
      );
      final currentRequest = AutoTranslationRequest(
        contentType: oldRequest.contentType,
        contentId: oldRequest.contentId,
        field: oldRequest.field,
        sourceText: oldRequest.sourceText,
      );

      final oldFuture = controller.translateOrSource(oldRequest);
      await pumpEventQueue();
      repository.completeNext('old candidate');
      expect(await oldFuture, oldRequest.sourceText);
      await pumpEventQueue();
      expect(evaluations, 1);

      final currentFuture = controller.translateOrSource(currentRequest);
      await pumpEventQueue();
      expect(repository.calls, hasLength(2));
      repository.completeNext('current candidate');
      expect(await currentFuture, 'current candidate');
      expect(
        await controller.translateOrSource(currentRequest),
        'current candidate',
      );
      expect(repository.calls, hasLength(2));
    });

    test('same-key rollover preserves new ownership when old completes first',
        () async {
      await _expectSameKeyRollover(newCompletesFirst: false);
    });

    test('same-key rollover preserves new ownership when new completes first',
        () async {
      await _expectSameKeyRollover(newCompletesFirst: true);
    });

    test('disablement invalidates running and queued old work', () async {
      await _expectQueuedInvalidation((controller) {
        controller.synchronize(
          enabled: false,
          authenticated: true,
          targetLanguage: 'en-US',
        );
      });
    });

    test('target change invalidates running and queued old work', () async {
      await _expectQueuedInvalidation((controller) {
        controller.synchronize(
          enabled: true,
          authenticated: true,
          targetLanguage: 'fr-FR',
        );
      });
    });

    test('authentication loss invalidates running and queued old work',
        () async {
      await _expectQueuedInvalidation((controller) {
        controller.synchronize(
          enabled: true,
          authenticated: false,
          targetLanguage: 'en-US',
        );
      });
    });

    test('a stale running completion returns source and is not cached',
        () async {
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = _activeController(repository);
      addTearDown(controller.dispose);
      final request = _request('stale');

      final pending = controller.translateOrSource(request);
      await pumpEventQueue();
      controller.synchronize(
        enabled: false,
        authenticated: true,
        targetLanguage: 'en-US',
      );
      expect(await pending, request.sourceText);
      repository.completeNext('stale result');
      await pumpEventQueue();

      repository.holdResponses = false;
      controller.synchronize(
        enabled: true,
        authenticated: true,
        targetLanguage: 'en-US',
      );
      expect(
        await controller.translateOrSource(request),
        'en-US:${request.sourceText}',
      );
      expect(repository.calls, hasLength(2));
    });

    test('dispose drains queued work and invalidates running results and cache',
        () async {
      final repository = RecordingTranslationRepository();
      final controller = _activeController(repository);
      final cachedRequest = _request('cached');
      expect(
        await controller.translateOrSource(cachedRequest),
        'en-US:${cachedRequest.sourceText}',
      );

      repository.holdResponses = true;
      final requests = <AutoTranslationRequest>[
        for (var index = 0; index < 4; index += 1) _request('dispose-$index'),
      ];
      final futures = requests.map(controller.translateOrSource).toList();
      await pumpEventQueue();
      expect(repository.calls, hasLength(4));

      controller.dispose();
      controller.dispose();
      expect(
        await Future.wait(futures),
        requests.map((request) => request.sourceText).toList(),
      );
      expect(await controller.translateOrSource(cachedRequest),
          cachedRequest.sourceText);
      expect(repository.calls, hasLength(4));

      repository.completeNext('ignored-0');
      repository.completeNext('ignored-1');
      repository.completeNext('ignored-2');
      await pumpEventQueue();
    });
  });

  test('constructor rejects non-positive scheduler bounds', () {
    expect(
        () => AutoTranslationController(maxConcurrent: 0), throwsArgumentError);
    expect(() => AutoTranslationController(maxCacheEntries: 0),
        throwsArgumentError);
  });
}

AutoTranslationController _activeController(
  RecordingTranslationRepository repository,
) {
  return AutoTranslationController(repository: repository)
    ..synchronize(
      enabled: true,
      authenticated: true,
      targetLanguage: 'en-US',
    );
}

AutoTranslationRequest _request(
  String suffix, {
  String contentType = 'article',
  String contentId = 'content',
  String field = 'title',
}) {
  return AutoTranslationRequest(
    contentType: contentType,
    contentId: contentId,
    field: field,
    sourceText: '中文$suffix',
  );
}

Future<void> _expectQueuedInvalidation(
  void Function(AutoTranslationController controller) invalidate,
) async {
  final repository = RecordingTranslationRepository()..holdResponses = true;
  final controller = _activeController(repository);
  final requests = <AutoTranslationRequest>[
    for (var index = 0; index < 4; index += 1) _request('invalidate-$index'),
  ];
  final futures = requests.map(controller.translateOrSource).toList();
  await pumpEventQueue();
  expect(repository.calls, hasLength(3));

  invalidate(controller);
  expect(
    await Future.wait(futures),
    requests.map((request) => request.sourceText).toList(),
  );
  expect(repository.calls, hasLength(3));

  repository.completeNext('ignored-0');
  repository.completeNext('ignored-1');
  repository.completeNext('ignored-2');
  await pumpEventQueue();
  controller.dispose();
}

Future<void> _expectSameKeyRollover({required bool newCompletesFirst}) async {
  final repository = RecordingTranslationRepository()..holdResponses = true;
  final controller = AutoTranslationController(
    repository: repository,
    maxConcurrent: 2,
  )..synchronize(
      enabled: true,
      authenticated: true,
      targetLanguage: 'en-US',
    );
  final request = _request('same-key-rollover');

  final oldFuture = controller.translateOrSource(request);
  await pumpEventQueue();
  controller
    ..synchronize(
      enabled: true,
      authenticated: true,
      targetLanguage: 'fr-FR',
    )
    ..synchronize(
      enabled: true,
      authenticated: true,
      targetLanguage: 'en-US',
    );
  final newFuture = controller.translateOrSource(request);
  await pumpEventQueue();
  expect(repository.calls, hasLength(2));

  if (newCompletesFirst) {
    repository.completePending(1, 'new candidate');
    expect(await newFuture, 'new candidate');
    repository.completePending(0, 'old candidate');
  } else {
    repository.completePending(0, 'old candidate');
    await pumpEventQueue();
    repository.completePending(0, 'new candidate');
    expect(await newFuture, 'new candidate');
  }
  expect(await oldFuture, request.sourceText);
  await pumpEventQueue();
  expect(await controller.translateOrSource(request), 'new candidate');
  expect(repository.calls, hasLength(2));
  expect(repository.maximumActiveCalls, lessThanOrEqualTo(2));
  controller.dispose();
}
