import 'package:flutter/widgets.dart';
import 'package:joysong_flutter/core/translation/auto_translation_controller.dart';

final class AutoTranslationScope extends InheritedWidget {
  const AutoTranslationScope({
    required this.controller,
    required this.enabled,
    required this.targetLanguage,
    required super.child,
    super.key,
  });

  final AutoTranslationController controller;
  final bool enabled;
  final String targetLanguage;

  static AutoTranslationScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AutoTranslationScope>();

  @override
  bool updateShouldNotify(AutoTranslationScope oldWidget) =>
      controller != oldWidget.controller ||
      enabled != oldWidget.enabled ||
      targetLanguage != oldWidget.targetLanguage;
}
