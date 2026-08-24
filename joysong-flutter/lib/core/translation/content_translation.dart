final class ContentTranslation {
  const ContentTranslation({
    required this.translatedText,
    required this.detectedLanguage,
    required this.targetLanguage,
    required this.provider,
    required this.cached,
  });

  final String translatedText;
  final String detectedLanguage;
  final String targetLanguage;
  final String provider;
  final bool cached;
}
