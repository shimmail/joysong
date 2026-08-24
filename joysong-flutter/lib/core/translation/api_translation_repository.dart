import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/core/translation/content_translation.dart';
import 'package:joysong_flutter/core/translation/translation_repository.dart';

final class ApiTranslationRepository implements TranslationRepository {
  ApiTranslationRepository(this._apiClient);

  final ApiClient _apiClient;

  @override
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  }) async {
    final source = text.trim();
    if (source.isEmpty) {
      throw ArgumentError.value(text, 'text', '待翻译内容不能为空');
    }
    if (source.length > 12000) {
      throw ArgumentError.value(text, 'text', '待翻译内容不能超过 12000 字符');
    }

    try {
      final value = await _apiClient.post<ContentTranslation>(
        'translations',
        body: {
          'text': source,
          'targetLanguage': targetLanguage.trim(),
          'contentType': contentType.trim(),
        },
        decodeData: _decodeTranslation,
      );
      if (value == null) {
        throw const FormatException('翻译响应 data 为空');
      }
      return value;
    } on ApiException catch (error) {
      final cause = error.cause;
      if (cause is FormatException) {
        throw cause;
      }
      rethrow;
    }
  }
}

ContentTranslation _decodeTranslation(Object? json) {
  if (json is! Map) {
    throw const FormatException('翻译响应不是 JSON 对象');
  }
  return ContentTranslation(
    translatedText: _requiredString(json, 'translatedText'),
    detectedLanguage: _requiredString(json, 'detectedLanguage'),
    targetLanguage: _requiredString(json, 'targetLanguage'),
    provider: _requiredString(json, 'provider'),
    cached: _requiredBool(json, 'cached'),
  );
}

String _requiredString(Map<Object?, Object?> json, String field) {
  final value = json[field];
  if (value is! String || value.trim().isEmpty) {
    throw FormatException('翻译响应缺少有效的 $field');
  }
  return value;
}

bool _requiredBool(Map<Object?, Object?> json, String field) {
  final value = json[field];
  if (value is! bool) {
    throw FormatException('翻译响应缺少有效的 $field');
  }
  return value;
}
