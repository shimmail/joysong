import 'dart:async';
import 'dart:collection';

import 'package:joysong_flutter/core/translation/translation.dart';

final class TranslationCall {
  const TranslationCall({
    required this.text,
    required this.targetLanguage,
    required this.contentType,
  });

  final String text;
  final String targetLanguage;
  final String contentType;
}

final class RecordingTranslationRepository implements TranslationRepository {
  final List<TranslationCall> calls = <TranslationCall>[];
  final List<_PendingTranslation> _pending = <_PendingTranslation>[];
  final Queue<String> translations = Queue<String>();

  bool holdResponses = false;
  int failuresRemaining = 0;
  int activeCalls = 0;
  int maximumActiveCalls = 0;

  int get pendingCount => _pending.length;

  @override
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  }) async {
    final call = TranslationCall(
      text: text,
      targetLanguage: targetLanguage,
      contentType: contentType,
    );
    calls.add(call);
    activeCalls += 1;
    if (activeCalls > maximumActiveCalls) {
      maximumActiveCalls = activeCalls;
    }

    try {
      if (failuresRemaining > 0) {
        failuresRemaining -= 1;
        throw StateError('configured translation failure');
      }
      if (holdResponses) {
        final completer = Completer<ContentTranslation>();
        _pending.add(_PendingTranslation(call, completer));
        return await completer.future;
      }
      return _translation(
        translations.isEmpty
            ? '$targetLanguage:$text'
            : translations.removeFirst(),
        targetLanguage,
      );
    } finally {
      activeCalls -= 1;
    }
  }

  void completeNext(String translatedText) {
    completePending(0, translatedText);
  }

  void completePending(int index, String translatedText) {
    final pending = _pending.removeAt(index);
    pending.completer.complete(
      _translation(translatedText, pending.call.targetLanguage),
    );
  }

  void failNext([Object error = const FormatException('invalid response')]) {
    _pending.removeAt(0).completer.completeError(error);
  }

  ContentTranslation _translation(String text, String targetLanguage) {
    return ContentTranslation(
      translatedText: text,
      detectedLanguage: 'zh',
      targetLanguage: targetLanguage,
      provider: 'test',
      cached: false,
    );
  }
}

final class SynchronousThrowTranslationRepository
    implements TranslationRepository {
  int calls = 0;

  @override
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  }) {
    calls += 1;
    throw StateError('synchronous translation failure');
  }
}

final class _PendingTranslation {
  const _PendingTranslation(this.call, this.completer);

  final TranslationCall call;
  final Completer<ContentTranslation> completer;
}
