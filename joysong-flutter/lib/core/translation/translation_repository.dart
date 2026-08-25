import 'package:joysong_flutter/core/translation/content_translation.dart';

abstract interface class TranslationRepository {
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  });
}
