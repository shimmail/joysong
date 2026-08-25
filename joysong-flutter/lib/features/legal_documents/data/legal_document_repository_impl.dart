import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_models.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_repository.dart';

final class ApiLegalDocumentRepository implements LegalDocumentRepository {
  ApiLegalDocumentRepository(this._apiClient);

  final ApiClient _apiClient;
  final Map<(LegalDocumentType, String), LegalDocument> _cache = {};

  @override
  Future<LegalDocument> load({
    required LegalDocumentType type,
    required String locale,
    bool forceRefresh = false,
  }) async {
    if (locale != 'zh-CN' && locale != 'en-US') {
      throw ArgumentError.value(locale, 'locale', 'Unsupported locale');
    }
    final key = (type, locale);
    final cached = _cache[key];
    if (!forceRefresh && cached != null) return cached;

    try {
      final document = await _apiClient.get<LegalDocument>(
        'public/legal-documents/${type.pathSegment}',
        query: {'locale': locale},
        decodeData: (json) => LegalDocument.fromJson(
          json,
        ).validateRequest(type: type, locale: locale),
      );
      if (document == null) {
        throw const FormatException('Legal document response has no data');
      }
      _cache[key] = document;
      return document;
    } on ApiException catch (error) {
      if (error.httpStatus == 404 || error.businessCode == 404) {
        throw LegalDocumentNotFoundException(error);
      }
      rethrow;
    }
  }
}
