import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';

enum DiaryMediaKind { general, before, after }

final class DiaryMediaGrid extends StatelessWidget {
  const DiaryMediaGrid({
    required this.images,
    required this.beforeImages,
    required this.afterImages,
    this.height = 260,
    this.borderRadius = BorderRadius.zero,
    this.enableViewer = false,
    super.key,
  });

  final List<String> images;
  final List<String> beforeImages;
  final List<String> afterImages;
  final double height;
  final BorderRadius borderRadius;
  final bool enableViewer;

  bool get hasMedia =>
      images.isNotEmpty || beforeImages.isNotEmpty || afterImages.isNotEmpty;

  @override
  Widget build(BuildContext context) {
    if (!hasMedia) return const SizedBox.shrink();

    final hasGeneral = images.isNotEmpty;
    final hasBefore = beforeImages.isNotEmpty;
    final hasAfter = afterImages.isNotEmpty;
    final categorizedCount = (hasBefore ? 1 : 0) + (hasAfter ? 1 : 0);

    Widget content;
    if (hasGeneral && categorizedCount > 0) {
      content = Row(
        children: [
          Expanded(
            flex: 2,
            child: _tile(context, DiaryMediaKind.general, images),
          ),
          const SizedBox(width: 2),
          Expanded(
            child: categorizedCount == 2
                ? Column(
                    children: [
                      Expanded(
                        child: _tile(
                          context,
                          DiaryMediaKind.before,
                          beforeImages,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Expanded(
                        child: _tile(
                          context,
                          DiaryMediaKind.after,
                          afterImages,
                        ),
                      ),
                    ],
                  )
                : _tile(
                    context,
                    hasBefore ? DiaryMediaKind.before : DiaryMediaKind.after,
                    hasBefore ? beforeImages : afterImages,
                  ),
          ),
        ],
      );
    } else if (hasBefore && hasAfter) {
      content = Row(
        children: [
          Expanded(
            child: _tile(context, DiaryMediaKind.before, beforeImages),
          ),
          const SizedBox(width: 2),
          Expanded(
            child: _tile(context, DiaryMediaKind.after, afterImages),
          ),
        ],
      );
    } else {
      final kind = hasGeneral
          ? DiaryMediaKind.general
          : hasBefore
              ? DiaryMediaKind.before
              : DiaryMediaKind.after;
      final urls = hasGeneral
          ? images
          : hasBefore
              ? beforeImages
              : afterImages;
      content = _tile(context, kind, urls);
    }

    return SizedBox(
      height: height,
      width: double.infinity,
      child: ClipRRect(borderRadius: borderRadius, child: content),
    );
  }

  Widget _tile(
    BuildContext context,
    DiaryMediaKind kind,
    List<String> urls,
  ) {
    final label = switch (kind) {
      DiaryMediaKind.general => null,
      DiaryMediaKind.before => context.localized('术前', 'Before'),
      DiaryMediaKind.after => context.localized('术后', 'After'),
    };
    return Material(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: InkWell(
        onTap: enableViewer
            ? () => Navigator.of(context).push<void>(
                  MaterialPageRoute(
                    builder: (_) => _DiaryMediaViewer(
                      urls: urls,
                      title: label ?? context.localized('日记图片', 'Diary photos'),
                    ),
                  ),
                )
            : null,
        child: Stack(
          fit: StackFit.expand,
          children: [
            Image.network(
              urls.first,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => const Center(
                child: Icon(Icons.broken_image_outlined, size: 32),
              ),
            ),
            if (label != null)
              Positioned(
                left: 8,
                top: 7,
                child: _overlayLabel(label),
              ),
            if (urls.length > 1)
              Positioned(
                right: 8,
                bottom: 7,
                child: _overlayLabel('+${urls.length - 1}'),
              ),
          ],
        ),
      ),
    );
  }

  Widget _overlayLabel(String label) => DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.48),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
          child: Text(
            label,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
      );
}

final class _DiaryMediaViewer extends StatefulWidget {
  const _DiaryMediaViewer({required this.urls, required this.title});

  final List<String> urls;
  final String title;

  @override
  State<_DiaryMediaViewer> createState() => _DiaryMediaViewerState();
}

final class _DiaryMediaViewerState extends State<_DiaryMediaViewer> {
  static const _loopStart = 1000000;
  late final PageController _controller;
  late int _page;

  @override
  void initState() {
    super.initState();
    _page = widget.urls.length == 1
        ? 0
        : _loopStart - (_loopStart % widget.urls.length);
    _controller = PageController(initialPage: _page);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final index = _page % widget.urls.length;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: Text(widget.title),
        actions: [
          Center(
            child: Padding(
              padding: const EdgeInsets.only(right: 16),
              child: Text('${index + 1}/${widget.urls.length}'),
            ),
          ),
        ],
      ),
      body: PageView.builder(
        controller: _controller,
        itemCount: widget.urls.length == 1 ? 1 : null,
        onPageChanged: (value) => setState(() => _page = value),
        itemBuilder: (context, page) => InteractiveViewer(
          minScale: 1,
          maxScale: 4,
          child: Center(
            child: Image.network(
              widget.urls[page % widget.urls.length],
              fit: BoxFit.contain,
              errorBuilder: (_, __, ___) => const Icon(
                Icons.broken_image_outlined,
                color: Colors.white70,
                size: 48,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
