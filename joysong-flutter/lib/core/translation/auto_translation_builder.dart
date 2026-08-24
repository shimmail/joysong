import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:joysong_flutter/core/translation/auto_translation_controller.dart';
import 'package:joysong_flutter/core/translation/auto_translation_request.dart';
import 'package:joysong_flutter/core/translation/auto_translation_scope.dart';

final class AutoTranslationBuilder extends StatefulWidget {
  const AutoTranslationBuilder({
    required this.request,
    required this.builder,
    super.key,
  });

  final AutoTranslationRequest request;
  final Widget Function(BuildContext context, String visibleText) builder;

  @override
  State<AutoTranslationBuilder> createState() => _AutoTranslationBuilderState();
}

final class _AutoTranslationBuilderState extends State<AutoTranslationBuilder> {
  late String _visibleText;
  AutoTranslationController? _controller;
  bool _enabled = false;
  String _targetLanguage = '';
  bool _hasDependencies = false;
  int _token = 0;

  @override
  void initState() {
    super.initState();
    _visibleText = widget.request.sourceText;
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final scope = AutoTranslationScope.maybeOf(context);
    final controller = scope?.controller;
    final enabled = scope?.enabled ?? false;
    final targetLanguage = scope?.targetLanguage ?? '';
    if (_hasDependencies &&
        identical(controller, _controller) &&
        enabled == _enabled &&
        targetLanguage == _targetLanguage) {
      return;
    }
    _hasDependencies = true;
    _controller = controller;
    _enabled = enabled;
    _targetLanguage = targetLanguage;
    _reschedule();
  }

  @override
  void didUpdateWidget(AutoTranslationBuilder oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(widget.request, oldWidget.request)) {
      _reschedule();
    }
  }

  @override
  void dispose() {
    _token += 1;
    super.dispose();
  }

  void _reschedule() {
    final token = ++_token;
    final request = widget.request;
    _visibleText = request.sourceText;
    final controller = _controller;
    if (!_enabled || controller == null || _targetLanguage.isEmpty) {
      return;
    }
    final targetLanguage = _targetLanguage;
    unawaited(
      _translate(
        controller: controller,
        request: request,
        targetLanguage: targetLanguage,
        token: token,
      ),
    );
  }

  Future<void> _translate({
    required AutoTranslationController controller,
    required AutoTranslationRequest request,
    required String targetLanguage,
    required int token,
  }) async {
    final translated = await controller.translateOrSource(request);
    if (!mounted ||
        token != _token ||
        !identical(widget.request, request) ||
        !identical(_controller, controller) ||
        !_enabled ||
        _targetLanguage != targetLanguage) {
      return;
    }
    if (_visibleText == translated) {
      return;
    }
    setState(() => _visibleText = translated);
  }

  @override
  Widget build(BuildContext context) => widget.builder(context, _visibleText);
}

final class AutoTranslatedText extends StatelessWidget {
  const AutoTranslatedText({
    required this.request,
    this.style,
    this.maxLines,
    this.overflow,
    this.textAlign,
    this.semanticsLabel,
    super.key,
  });

  final AutoTranslationRequest request;
  final TextStyle? style;
  final int? maxLines;
  final TextOverflow? overflow;
  final TextAlign? textAlign;
  final String? semanticsLabel;

  @override
  Widget build(BuildContext context) {
    return AutoTranslationBuilder(
      request: request,
      builder: (context, visibleText) => Text(
        visibleText,
        style: style,
        maxLines: maxLines,
        overflow: overflow,
        textAlign: textAlign,
        semanticsLabel: semanticsLabel,
      ),
    );
  }
}
