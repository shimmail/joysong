import 'dart:async';
import 'dart:collection';

import 'package:joysong_flutter/core/translation/auto_translation_request.dart';
import 'package:joysong_flutter/core/translation/translation_repository.dart';

final RegExp _chineseTextPattern =
    RegExp(r'[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]');

bool containsChineseText(String value) => _chineseTextPattern.hasMatch(value);

final class AutoTranslationController {
  AutoTranslationController({
    TranslationRepository? repository,
    int maxConcurrent = 3,
    int maxCacheEntries = 500,
  })  : _repository = repository,
        _maxConcurrent = maxConcurrent,
        _maxCacheEntries = maxCacheEntries {
    if (maxConcurrent <= 0) {
      throw ArgumentError.value(
        maxConcurrent,
        'maxConcurrent',
        'must be positive',
      );
    }
    if (maxCacheEntries <= 0) {
      throw ArgumentError.value(
        maxCacheEntries,
        'maxCacheEntries',
        'must be positive',
      );
    }
  }

  final TranslationRepository? _repository;
  final int _maxConcurrent;
  final int _maxCacheEntries;
  final Queue<_TranslationJob> _queue = Queue<_TranslationJob>();
  final Set<_TranslationJob> _running = <_TranslationJob>{};
  final Map<_TranslationKey, _TranslationJob> _inFlight =
      <_TranslationKey, _TranslationJob>{};
  final LinkedHashMap<_TranslationKey, String> _cache =
      LinkedHashMap<_TranslationKey, String>();

  bool _enabled = false;
  bool _authenticated = false;
  String _targetLanguage = '';
  bool _disposed = false;
  int _generation = 0;
  int _activeCalls = 0;

  void synchronize({
    required bool enabled,
    required bool authenticated,
    required String targetLanguage,
  }) {
    if (_disposed ||
        (_enabled == enabled &&
            _authenticated == authenticated &&
            _targetLanguage == targetLanguage)) {
      return;
    }
    _enabled = enabled;
    _authenticated = authenticated;
    _targetLanguage = targetLanguage;
    _invalidate();
  }

  Future<String> translateOrSource(AutoTranslationRequest request) {
    if (!_isEligible(request, _generation, _targetLanguage)) {
      return Future<String>.value(request.sourceText);
    }

    final key = _TranslationKey(
      targetLanguage: _targetLanguage,
      contentType: request.contentType,
      contentId: request.contentId,
      field: request.field,
      sourceText: request.sourceText,
    );
    final generation = _generation;
    final targetLanguage = _targetLanguage;
    final cached = _cache.remove(key);
    if (cached != null) {
      final accepted = _isValid(request, cached);
      if (!_isEligible(request, generation, targetLanguage)) {
        return Future<String>.value(request.sourceText);
      }
      if (accepted) {
        _cache[key] = cached;
        return Future<String>.value(cached);
      }
    }

    final existing = _inFlight[key];
    final caller = _TranslationCaller(
      request: request,
      generation: generation,
      targetLanguage: targetLanguage,
    );
    if (existing != null && existing.generation == generation) {
      existing.callers.add(caller);
      return caller.result.future;
    }

    final job = _TranslationJob(
      key: key,
      request: request,
      targetLanguage: targetLanguage,
      generation: generation,
    )..callers.add(caller);
    _inFlight[key] = job;
    _queue.add(job);
    _pump();
    return caller.result.future;
  }

  void dispose() {
    if (_disposed) {
      return;
    }
    _disposed = true;
    _invalidate();
  }

  bool _isValid(AutoTranslationRequest request, String translated) {
    final validator = request.validator;
    if (validator == null) {
      return true;
    }
    try {
      return validator(request.sourceText, translated);
    } on Object {
      return false;
    }
  }

  bool _isEligible(
    AutoTranslationRequest request,
    int generation,
    String targetLanguage,
  ) {
    return !_disposed &&
        generation == _generation &&
        _enabled &&
        _authenticated &&
        targetLanguage.isNotEmpty &&
        _repository != null &&
        containsChineseText(request.sourceText);
  }

  void _pump() {
    while (_activeCalls < _maxConcurrent && _queue.isNotEmpty) {
      final job = _queue.removeFirst();
      if (!_isEligible(job.request, job.generation, job.targetLanguage)) {
        _finishWithSource(job);
        continue;
      }
      _activeCalls += 1;
      _running.add(job);
      unawaited(_run(job));
    }
  }

  Future<void> _run(_TranslationJob job) async {
    try {
      final response = await _repository!.translateText(
        text: job.request.sourceText,
        targetLanguage: job.targetLanguage,
        contentType: job.request.contentType,
      );
      final translated = response.translatedText;
      if (!_ownsCurrentJob(job)) {
        _finishWithSource(job);
        return;
      }

      var callerIndex = 0;
      while (callerIndex < job.callers.length) {
        final caller = job.callers[callerIndex];
        caller.accepted = _isValid(caller.request, translated);
        if (!_ownsCurrentCaller(job, caller)) {
          return;
        }
        callerIndex += 1;
      }

      if (job.callers.every((caller) => caller.accepted!)) {
        if (!_ownsCurrentJob(job)) {
          return;
        }
        _cache[job.key] = translated;
        while (_cache.length > _maxCacheEntries) {
          _cache.remove(_cache.keys.first);
        }
      }

      for (final caller in job.callers) {
        if (!_ownsCurrentCaller(job, caller)) {
          return;
        }
        if (!caller.result.isCompleted) {
          caller.result.complete(
            caller.accepted! ? translated : caller.request.sourceText,
          );
        }
      }
    } on Object {
      _finishWithSource(job);
    } finally {
      _activeCalls -= 1;
      _running.remove(job);
      if (identical(_inFlight[job.key], job)) {
        _inFlight.remove(job.key);
      }
      _pump();
    }
  }

  void _invalidate() {
    _generation += 1;
    _cache.clear();
    _inFlight.clear();
    while (_queue.isNotEmpty) {
      _finishWithSource(_queue.removeFirst());
    }
    for (final job in _running) {
      _finishWithSource(job);
    }
  }

  void _finishWithSource(_TranslationJob job) {
    for (final caller in job.callers) {
      if (!caller.result.isCompleted) {
        caller.result.complete(caller.request.sourceText);
      }
    }
    if (identical(_inFlight[job.key], job)) {
      _inFlight.remove(job.key);
    }
  }

  bool _ownsCurrentJob(_TranslationJob job) {
    return identical(_inFlight[job.key], job) &&
        _isEligible(job.request, job.generation, job.targetLanguage);
  }

  bool _ownsCurrentCaller(_TranslationJob job, _TranslationCaller caller) {
    return caller.generation == job.generation &&
        caller.targetLanguage == job.targetLanguage &&
        _ownsCurrentJob(job);
  }
}

final class _TranslationJob {
  _TranslationJob({
    required this.key,
    required this.request,
    required this.targetLanguage,
    required this.generation,
  });

  final _TranslationKey key;
  final AutoTranslationRequest request;
  final String targetLanguage;
  final int generation;
  final List<_TranslationCaller> callers = <_TranslationCaller>[];
}

final class _TranslationCaller {
  _TranslationCaller({
    required this.request,
    required this.generation,
    required this.targetLanguage,
  });

  final AutoTranslationRequest request;
  final int generation;
  final String targetLanguage;
  final Completer<String> result = Completer<String>();
  bool? accepted;
}

final class _TranslationKey {
  const _TranslationKey({
    required this.targetLanguage,
    required this.contentType,
    required this.contentId,
    required this.field,
    required this.sourceText,
  });

  final String targetLanguage;
  final String contentType;
  final String contentId;
  final String field;
  final String sourceText;

  @override
  bool operator ==(Object other) {
    return other is _TranslationKey &&
        targetLanguage == other.targetLanguage &&
        contentType == other.contentType &&
        contentId == other.contentId &&
        field == other.field &&
        sourceText == other.sourceText;
  }

  @override
  int get hashCode => Object.hash(
        targetLanguage,
        contentType,
        contentId,
        field,
        sourceText,
      );
}
