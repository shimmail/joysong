import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localized_text.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_models.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_repository.dart';
import 'package:joysong_flutter/features/legal_documents/presentation/legal_document_controller.dart';

class LegalDocumentPage extends StatefulWidget {
  const LegalDocumentPage({
    required this.type,
    required this.repository,
    super.key,
  });

  final LegalDocumentType type;
  final LegalDocumentRepository repository;

  @override
  State<LegalDocumentPage> createState() => _LegalDocumentPageState();
}

final class _LegalDocumentPageState extends State<LegalDocumentPage> {
  late final LegalDocumentController _controller;
  String? _locale;

  @override
  void initState() {
    super.initState();
    _controller = LegalDocumentController(
      repository: widget.repository,
      type: widget.type,
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final locale = Localizations.localeOf(context).languageCode == 'en'
        ? 'en-US'
        : 'zh-CN';
    if (_locale == locale) return;
    _locale = locale;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _locale == locale) {
        _controller.load(locale: locale);
      }
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _controller,
      builder: (context, _) {
        final state = _controller.state;
        final title = state.document?.title ?? _typeTitle(context);
        return Scaffold(
          appBar: AppBar(title: Text(title)),
          body: SafeArea(child: _body(context, state)),
        );
      },
    );
  }

  Widget _body(BuildContext context, LegalDocumentState state) {
    return switch (state.status) {
      LegalDocumentStatus.initial || LegalDocumentStatus.loading => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: 16),
            Text(context.localized('正在加载协议…', 'Loading document…')),
          ],
        ),
      ),
      LegalDocumentStatus.ready => _ready(state.document!),
      LegalDocumentStatus.notFound => _message(
        context,
        icon: Icons.description_outlined,
        message: context.localized(
          '暂未发布该协议',
          'This document is not available yet.',
        ),
      ),
      LegalDocumentStatus.failure => _message(
        context,
        icon: Icons.cloud_off_outlined,
        message: context.localized(
          '协议加载失败，请稍后重试',
          'Could not load this document. Please try again.',
        ),
        retry: true,
      ),
    };
  }

  Widget _ready(LegalDocument document) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'V${document.version} · ${_date(document.publishedAt)}',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 20),
          RichContentView(content: document.contentHtml),
        ],
      ),
    );
  }

  Widget _message(
    BuildContext context, {
    required IconData icon,
    required String message,
    bool retry = false,
  }) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 40),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            if (retry) ...[
              const SizedBox(height: 16),
              FilledButton(
                onPressed: _controller.retry,
                child: Text(context.localized('重试', 'Retry')),
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _typeTitle(BuildContext context) => switch (widget.type) {
    LegalDocumentType.userAgreement => context.localized(
      '用户协议',
      'User Agreement',
    ),
    LegalDocumentType.privacyPolicy => context.localized(
      '隐私政策',
      'Privacy Policy',
    ),
  };
}

String _date(DateTime value) =>
    '${value.year.toString().padLeft(4, '0')}-'
    '${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')}';
