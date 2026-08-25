typedef TranslationValidator = bool Function(
  String source,
  String translated,
);

final class AutoTranslationRequest {
  const AutoTranslationRequest({
    required this.contentType,
    required this.contentId,
    required this.field,
    required this.sourceText,
    this.validator,
  });

  final String contentType;
  final String contentId;
  final String field;
  final String sourceText;
  final TranslationValidator? validator;
}
