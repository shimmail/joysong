enum LegalDocumentType {
  userAgreement('user-agreement'),
  privacyPolicy('privacy-policy');

  const LegalDocumentType(this.pathSegment);

  final String pathSegment;

  static LegalDocumentType parse(String value) => switch (value) {
    'user-agreement' => LegalDocumentType.userAgreement,
    'privacy-policy' => LegalDocumentType.privacyPolicy,
    _ => throw FormatException('Unsupported legal document type: $value'),
  };
}

final class LegalDocument {
  const LegalDocument({
    required this.type,
    required this.locale,
    required this.version,
    required this.title,
    required this.contentHtml,
    required this.publishedAt,
    required this.contentSha256,
  });

  final LegalDocumentType type;
  final String locale;
  final int version;
  final String title;
  final String contentHtml;
  final DateTime publishedAt;
  final String contentSha256;

  factory LegalDocument.fromJson(Object? json) {
    if (json is! Map) {
      throw const FormatException('Legal document data must be an object');
    }
    late final Map<String, Object?> map;
    try {
      map = Map<String, Object?>.from(json);
    } on Object {
      throw const FormatException('Legal document keys must be strings');
    }

    final type = LegalDocumentType.parse(_requiredString(map, 'type'));
    final locale = _requiredString(map, 'locale');
    if (locale != 'zh-CN' && locale != 'en-US') {
      throw FormatException('Unsupported legal document locale: $locale');
    }
    final version = map['version'];
    if (version is! int || version <= 0) {
      throw const FormatException('Legal document version must be positive');
    }
    final publishedAtText = _requiredString(map, 'publishedAt');
    if (!_localDateTimePattern.hasMatch(publishedAtText)) {
      throw const FormatException(
        'Legal document publishedAt must be a local date-time',
      );
    }
    final publishedAt = DateTime.tryParse(publishedAtText);
    if (publishedAt == null) {
      throw const FormatException('Invalid legal document publishedAt');
    }

    return LegalDocument(
      type: type,
      locale: locale,
      version: version,
      title: _requiredString(map, 'title', nonEmpty: true),
      contentHtml: _requiredString(map, 'contentHtml', nonEmpty: true),
      publishedAt: publishedAt,
      contentSha256: _requiredString(map, 'contentSha256', nonEmpty: true),
    );
  }

  LegalDocument validateRequest({
    required LegalDocumentType type,
    required String locale,
  }) {
    if (this.type != type || this.locale != locale) {
      throw const FormatException(
        'Legal document response does not match the request',
      );
    }
    return this;
  }
}

final _localDateTimePattern = RegExp(
  r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?$',
);

String _requiredString(
  Map<String, Object?> map,
  String key, {
  bool nonEmpty = false,
}) {
  final value = map[key];
  if (value is! String || (nonEmpty && value.trim().isEmpty)) {
    throw FormatException('Legal document $key must be a string');
  }
  return value;
}
