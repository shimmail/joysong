import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_models.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_repository.dart';

enum LegalDocumentStatus { initial, loading, ready, notFound, failure }

final class LegalDocumentState {
  const LegalDocumentState._({required this.status, this.document, this.error});

  const LegalDocumentState.initial()
    : this._(status: LegalDocumentStatus.initial);

  const LegalDocumentState.loading()
    : this._(status: LegalDocumentStatus.loading);

  const LegalDocumentState.ready(LegalDocument document)
    : this._(status: LegalDocumentStatus.ready, document: document);

  const LegalDocumentState.notFound()
    : this._(status: LegalDocumentStatus.notFound);

  const LegalDocumentState.failure(Object error)
    : this._(status: LegalDocumentStatus.failure, error: error);

  final LegalDocumentStatus status;
  final LegalDocument? document;
  final Object? error;
}

final class LegalDocumentController extends ChangeNotifier {
  LegalDocumentController({
    required LegalDocumentRepository repository,
    required LegalDocumentType type,
  }) : _repository = repository,
       _type = type;

  final LegalDocumentRepository _repository;
  final LegalDocumentType _type;
  LegalDocumentState _state = const LegalDocumentState.initial();
  String? _lastLocale;
  int _requestVersion = 0;
  bool _disposed = false;

  LegalDocumentState get state => _state;

  Future<void> load({required String locale, bool forceRefresh = false}) async {
    if (_disposed) return;
    _lastLocale = locale;
    final requestVersion = ++_requestVersion;
    _emit(const LegalDocumentState.loading());
    try {
      final document = await _repository.load(
        type: _type,
        locale: locale,
        forceRefresh: forceRefresh,
      );
      if (_isCurrent(requestVersion)) {
        _emit(LegalDocumentState.ready(document));
      }
    } on LegalDocumentNotFoundException {
      if (_isCurrent(requestVersion)) {
        _emit(const LegalDocumentState.notFound());
      }
    } on Object catch (error) {
      if (_isCurrent(requestVersion)) {
        _emit(LegalDocumentState.failure(error));
      }
    }
  }

  Future<void> retry() {
    final locale = _lastLocale;
    if (locale == null || _disposed) return Future<void>.value();
    return load(locale: locale, forceRefresh: true);
  }

  bool _isCurrent(int requestVersion) =>
      !_disposed && requestVersion == _requestVersion;

  void _emit(LegalDocumentState state) {
    if (_disposed) return;
    _state = state;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _requestVersion += 1;
    super.dispose();
  }
}
