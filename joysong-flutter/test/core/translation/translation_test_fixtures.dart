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
  final Queue<Completer<ContentTranslation>> _pending =
      Queue<Completer<ContentTranslation>>();
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
    calls.add(TranslationCall(
      text: text,
      targetLanguage: targetLanguage,
      contentType: contentType,
    ));
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
        _pending.add(completer);
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
    final call = calls[calls.length - _pending.length];
    _pending.removeFirst().complete(
          _translation(translatedText, call.targetLanguage),
        );
  }

  void failNext([Object error = const FormatException('invalid response')]) {
    _pending.removeFirst().completeError(error);
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
