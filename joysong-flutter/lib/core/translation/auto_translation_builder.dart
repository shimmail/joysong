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

final class StableAutoTranslationBuilder extends StatefulWidget {
  const StableAutoTranslationBuilder({
    required this.enabled,
    required this.contentType,
    required this.contentId,
    required this.field,
    required this.sourceText,
    required this.builder,
    this.validator,
    this.retryToken,
    super.key,
  });

  final bool enabled;
  final String contentType;
  final String contentId;
  final String field;
  final String sourceText;
  final TranslationValidator? validator;

  /// Scheduling-only snapshot identity; it is never added to the request.
  final Object? retryToken;
  final Widget Function(BuildContext context, String visibleText) builder;

  @override
  State<StableAutoTranslationBuilder> createState() =>
      _StableAutoTranslationBuilderState();
}

final class _StableAutoTranslationBuilderState
    extends State<StableAutoTranslationBuilder> {
  AutoTranslationRequest? _request;
  Object? _retryToken;

  @override
  void initState() {
    super.initState();
    _synchronizeRequest();
  }

  @override
  void didUpdateWidget(StableAutoTranslationBuilder oldWidget) {
    super.didUpdateWidget(oldWidget);
    _synchronizeRequest();
  }

  void _synchronizeRequest() {
    if (widget.contentType.trim().isEmpty ||
        widget.contentId.trim().isEmpty ||
        widget.field.trim().isEmpty ||
        widget.sourceText.trim().isEmpty) {
      _request = null;
      _retryToken = widget.retryToken;
      return;
    }
    final previous = _request;
    if (previous != null &&
        previous.contentType == widget.contentType &&
        previous.contentId == widget.contentId &&
        previous.field == widget.field &&
        previous.sourceText == widget.sourceText &&
        identical(previous.validator, widget.validator) &&
        identical(_retryToken, widget.retryToken)) {
      return;
    }
    _retryToken = widget.retryToken;
    _request = AutoTranslationRequest(
      contentType: widget.contentType,
      contentId: widget.contentId,
      field: widget.field,
      sourceText: widget.sourceText,
      validator: widget.validator,
    );
  }

  @override
  Widget build(BuildContext context) {
    final request = _request;
    if (!widget.enabled || request == null) {
      return widget.builder(context, widget.sourceText);
    }
    return AutoTranslationBuilder(request: request, builder: widget.builder);
  }
}

final class StableAutoTranslatedText extends StatelessWidget {
  const StableAutoTranslatedText({
    required this.enabled,
    required this.contentType,
    required this.contentId,
    required this.field,
    required this.sourceText,
    this.validator,
    this.retryToken,
    this.style,
    this.maxLines,
    this.overflow,
    this.textAlign,
    this.semanticsLabel,
    super.key,
  });

  final bool enabled;
  final String contentType;
  final String contentId;
  final String field;
  final String sourceText;
  final TranslationValidator? validator;
  final Object? retryToken;
  final TextStyle? style;
  final int? maxLines;
  final TextOverflow? overflow;
  final TextAlign? textAlign;
  final String? semanticsLabel;

  @override
  Widget build(BuildContext context) => StableAutoTranslationBuilder(
        enabled: enabled,
        contentType: contentType,
        contentId: contentId,
        field: field,
        sourceText: sourceText,
        validator: validator,
        retryToken: retryToken,
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
