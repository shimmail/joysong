import 'package:joysong_flutter/features/legal_documents/domain/legal_document_models.dart';

abstract interface class LegalDocumentRepository {
  Future<LegalDocument> load({
    required LegalDocumentType type,
    required String locale,
    bool forceRefresh = false,
  });
}

final class LegalDocumentNotFoundException implements Exception {
  const LegalDocumentNotFoundException([this.cause]);

  final Object? cause;
}
