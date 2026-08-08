import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';

class CatalogHero extends StatelessWidget {
  const CatalogHero({
    required this.images,
    required this.title,
    this.eyebrow = '',
    this.subtitle = '',
    this.price,
    this.originalPrice,
    super.key,
  });

  final List<String> images;
  final String title;
  final String eyebrow;
  final String subtitle;
  final double? price;
  final double? originalPrice;

  @override
  Widget build(BuildContext context) => Stack(
        clipBehavior: Clip.hardEdge,
        children: [
          DynamicImagePager(
            images: images,
            contentDescription: title,
            backgroundColor: const Color(0xffdddddd),
          ),
          Positioned.fill(
            child: IgnorePointer(
              child: ColoredBox(
                color: Colors.black.withValues(alpha: .25),
              ),
            ),
          ),
          Positioned(
            left: 20,
            right: 20,
            bottom: 28,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (eyebrow.isNotEmpty)
                  Text(
                    eyebrow,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 17,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                if (eyebrow.isNotEmpty) const SizedBox(height: 7),
                Text(
                  title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 32,
                    height: 1.08,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                if (subtitle.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text(
                    subtitle,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, height: 1.3),
                  ),
                ],
                if (price != null && price! > 0) ...[
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Text(
                        '\$${catalogMoney(price!)}',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 20,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      if (originalPrice != null && originalPrice! > price!) ...[
                        const SizedBox(width: 8),
                        Text(
                          '\$${catalogMoney(originalPrice!)}',
                          style: const TextStyle(
                            color: Colors.white70,
                            decoration: TextDecoration.lineThrough,
                          ),
                        ),
                      ],
                    ],
                  ),
                ],
              ],
            ),
          ),
        ],
      );
}

/// Shared responsive gallery for institution, project, and institution-project
/// details. Each page is measured from the decoded image dimensions, while a
/// 4:3 estimate keeps the layout stable until metadata arrives.
class DynamicImagePager extends StatefulWidget {
  const DynamicImagePager({
    required this.images,
    required this.contentDescription,
    this.backgroundColor = Colors.white,
    super.key,
  });

  final List<String> images;
  final String contentDescription;
  final Color backgroundColor;

  @override
  State<DynamicImagePager> createState() => _DynamicImagePagerState();
}

class _DynamicImagePagerState extends State<DynamicImagePager> {
  final _ratios = <int, double>{};
  final _streams = <int, ImageStream>{};
  final _listeners = <int, ImageStreamListener>{};
  final PageController _pageController = PageController();
  int _currentPage = 0;
  bool _resolvedDependencies = false;

  @override
  void initState() {
    super.initState();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_resolvedDependencies) return;
    _resolvedDependencies = true;
    _resolveImageDimensions();
  }

  @override
  void didUpdateWidget(covariant DynamicImagePager oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (listEquals(oldWidget.images, widget.images)) return;
    _currentPage = 0;
    _ratios.clear();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _pageController.hasClients) {
        _pageController.jumpToPage(0);
      }
    });
    _resolveImageDimensions();
  }

  void _resolveImageDimensions() {
    _removeImageListeners();
    for (var index = 0; index < widget.images.length; index++) {
      final url = widget.images[index].trim();
      if (url.isEmpty) continue;
      final stream = NetworkImage(url).resolve(
        createLocalImageConfiguration(context),
      );
      late final ImageStreamListener listener;
      listener = ImageStreamListener(
        (info, _) {
          final width = info.image.width;
          final height = info.image.height;
          if (width <= 0 || height <= 0 || !mounted) return;
          final ratio = height / width;
          if (_ratios[index] == ratio) return;
          setState(() => _ratios[index] = ratio);
        },
        onError: (_, __) {},
      );
      _streams[index] = stream;
      _listeners[index] = listener;
      stream.addListener(listener);
    }
  }

  void _removeImageListeners() {
    for (final entry in _streams.entries) {
      final listener = _listeners[entry.key];
      if (listener != null) entry.value.removeListener(listener);
    }
    _streams.clear();
    _listeners.clear();
  }

  @override
  void dispose() {
    _removeImageListeners();
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final width = constraints.maxWidth.isFinite
          ? constraints.maxWidth
          : MediaQuery.sizeOf(context).width;
      final ratio = _ratios[_currentPage] ?? .75;
      final height = width * ratio;
      return AnimatedSize(
        duration: const Duration(milliseconds: 260),
        curve: Curves.easeOutCubic,
        alignment: Alignment.topCenter,
        child: SizedBox(
          width: width,
          height: height,
          child: widget.images.isEmpty
              ? _imageFallback(Icons.image_outlined)
              : Stack(
                  fit: StackFit.expand,
                  children: [
                    PageView.builder(
                      controller: _pageController,
                      itemCount: widget.images.length,
                      onPageChanged: (page) =>
                          setState(() => _currentPage = page),
                      itemBuilder: (context, index) => GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTap: () => _openViewer(index),
                        child: Semantics(
                          image: true,
                          label: widget.contentDescription,
                          button: true,
                          child: OptimizedNetworkImage(
                            url: widget.images[index],
                            width: width,
                            height: height,
                            fit: BoxFit.fitWidth,
                            alignment: Alignment.topCenter,
                            errorBuilder: (_, __, ___) =>
                                _imageFallback(Icons.broken_image_outlined),
                          ),
                        ),
                      ),
                    ),
                    if (widget.images.length > 1)
                      Positioned(
                        right: 12,
                        bottom: 12,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: .62),
                            borderRadius: BorderRadius.circular(16),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 9,
                              vertical: 4,
                            ),
                            child: Text(
                              '${_currentPage + 1}/${widget.images.length}',
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 12,
                              ),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
        ),
      );
    });
  }

  Widget _imageFallback(IconData icon) => ColoredBox(
        color: widget.backgroundColor,
        child: Center(
          child: Icon(
            icon,
            size: 48,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      );

  void _openViewer(int initialPage) {
    Navigator.of(context).push<void>(MaterialPageRoute(
      builder: (_) => FullscreenImagePager(
        images: widget.images,
        contentDescription: widget.contentDescription,
        initialPage: initialPage,
      ),
    ));
  }
}

class FullscreenImagePager extends StatefulWidget {
  const FullscreenImagePager({
    required this.images,
    required this.contentDescription,
    this.initialPage = 0,
    super.key,
  });

  final List<String> images;
  final String contentDescription;
  final int initialPage;

  @override
  State<FullscreenImagePager> createState() => _FullscreenImagePagerState();
}

class _FullscreenImagePagerState extends State<FullscreenImagePager> {
  late final PageController _controller;
  late int _currentPage;

  int get _initialVirtualPage {
    if (widget.images.length <= 1) return 0;
    const base = 10000;
    return base - (base % widget.images.length) + widget.initialPage;
  }

  @override
  void initState() {
    super.initState();
    _currentPage = widget.initialPage;
    _controller = PageController(initialPage: _initialVirtualPage);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: Colors.black,
        body: SafeArea(
          child: Stack(
            children: [
              PageView.builder(
                controller: _controller,
                itemCount: widget.images.length == 1 ? 1 : null,
                onPageChanged: (page) => setState(
                  () => _currentPage = page % widget.images.length,
                ),
                itemBuilder: (_, page) => InteractiveViewer(
                  minScale: 1,
                  maxScale: 5,
                  child: Center(
                    child: Image.network(
                      widget.images[page % widget.images.length],
                      semanticLabel: widget.contentDescription,
                      fit: BoxFit.contain,
                      errorBuilder: (_, __, ___) => const Icon(
                        Icons.broken_image_outlined,
                        color: Colors.white70,
                        size: 52,
                      ),
                    ),
                  ),
                ),
              ),
              Positioned(
                left: 8,
                top: 8,
                child: IconButton.filledTonal(
                  tooltip: MaterialLocalizations.of(context).closeButtonTooltip,
                  onPressed: () => Navigator.of(context).pop(),
                  icon: const Icon(Icons.close),
                ),
              ),
              if (widget.images.length > 1)
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 24,
                  child: Center(
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: Colors.black54,
                        borderRadius: BorderRadius.circular(18),
                      ),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 6,
                        ),
                        child: Text(
                          '${_currentPage + 1} / ${widget.images.length}',
                          style: const TextStyle(color: Colors.white),
                        ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      );
}

class DetailAnchorBar extends StatefulWidget {
  const DetailAnchorBar({required this.labels, required this.onTap, super.key});
  final List<String> labels;
  final ValueChanged<int> onTap;

  @override
  State<DetailAnchorBar> createState() => _DetailAnchorBarState();
}

class _DetailAnchorBarState extends State<DetailAnchorBar> {
  int _selectedIndex = 0;

  @override
  void didUpdateWidget(covariant DetailAnchorBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_selectedIndex >= widget.labels.length) {
      _selectedIndex = widget.labels.isEmpty ? 0 : widget.labels.length - 1;
    }
  }

  void _select(int index) {
    if (_selectedIndex != index) {
      setState(() => _selectedIndex = index);
    }
    widget.onTap(index);
  }

  @override
  Widget build(BuildContext context) => Material(
        color: Colors.white,
        elevation: 1,
        child: SizedBox(
          height: 54,
          child: ListView.builder(
            scrollDirection: Axis.horizontal,
            itemCount: widget.labels.length,
            itemBuilder: (_, index) => InkWell(
              onTap: () => _select(index),
              child: Semantics(
                selected: index == _selectedIndex,
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 180),
                  curve: Curves.easeOut,
                  constraints: const BoxConstraints(minWidth: 96),
                  padding: const EdgeInsets.symmetric(horizontal: 14),
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    border: Border(
                      bottom: BorderSide(
                        color: index == _selectedIndex
                            ? const Color(0xff111111)
                            : Colors.transparent,
                        width: 2,
                      ),
                    ),
                  ),
                  child: AnimatedDefaultTextStyle(
                    duration: const Duration(milliseconds: 180),
                    curve: Curves.easeOut,
                    style: TextStyle(
                      color: index == _selectedIndex
                          ? const Color(0xff111111)
                          : const Color(0xff888888),
                      fontWeight: index == _selectedIndex
                          ? FontWeight.w700
                          : FontWeight.w500,
                    ),
                    child: Text(
                      widget.labels[index],
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
}

class CatalogSection extends StatelessWidget {
  const CatalogSection({
    required this.title,
    required this.child,
    this.subtitle,
    this.trailing,
    super.key,
  });
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final Widget child;

  @override
  Widget build(BuildContext context) => Container(
        color: Colors.white,
        padding: const EdgeInsets.fromLTRB(16, 20, 16, 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(
                child: Text(
                  title,
                  style: Theme.of(context)
                      .textTheme
                      .titleLarge
                      ?.copyWith(fontWeight: FontWeight.w800),
                ),
              ),
              if (trailing != null) trailing!,
            ]),
            if (subtitle != null && subtitle!.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(
                subtitle!,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
            const SizedBox(height: 14),
            child,
          ],
        ),
      );
}

class RiskTopicRow extends StatelessWidget {
  const RiskTopicRow({super.key});

  @override
  Widget build(BuildContext context) {
    final labels = context.isEnglish
        ? const ['How it works', 'Who it is for', 'Who should avoid it']
        : const ['作用原理', '适合人群', '禁忌人群'];
    return SizedBox(
      height: 44,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: labels.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (_, index) => Container(
          padding: const EdgeInsets.symmetric(horizontal: 14),
          decoration: BoxDecoration(
            color: const Color(0xfff2f2f2),
            borderRadius: BorderRadius.circular(22),
          ),
          alignment: Alignment.center,
          child: Row(children: [
            const Icon(Icons.info_outline, size: 17),
            const SizedBox(width: 6),
            Text(labels[index],
                style: const TextStyle(fontWeight: FontWeight.w600)),
          ]),
        ),
      ),
    );
  }
}

class CatalogImage extends StatelessWidget {
  const CatalogImage({
    required this.url,
    required this.width,
    required this.height,
    this.radius = 12,
    this.icon = Icons.image_outlined,
    super.key,
  });
  final String url;
  final double width;
  final double height;
  final double radius;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final fallback = Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        color: const Color(0xffeeeeee),
        borderRadius: BorderRadius.circular(radius),
      ),
      alignment: Alignment.center,
      child: Icon(
        icon,
        color: Theme.of(context).colorScheme.onSurfaceVariant,
      ),
    );
    if (url.isEmpty) return fallback;
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: OptimizedNetworkImage(
        url: url,
        width: width,
        height: height,
        errorBuilder: (_, __, ___) => fallback,
      ),
    );
  }
}

class CatalogBottomBar extends StatelessWidget {
  const CatalogBottomBar({
    required this.primaryLabel,
    this.onPrimary,
    this.secondaryLabel,
    this.onSecondary,
    this.tertiaryLabel,
    this.onTertiary,
    super.key,
  });
  final String primaryLabel;
  final VoidCallback? onPrimary;
  final String? secondaryLabel;
  final VoidCallback? onSecondary;
  final String? tertiaryLabel;
  final VoidCallback? onTertiary;

  @override
  Widget build(BuildContext context) => SafeArea(
        top: false,
        child: Container(
          color: Colors.white,
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
          child: Row(children: [
            if (tertiaryLabel != null) ...[
              Expanded(
                child: OutlinedButton(
                  onPressed: onTertiary,
                  style: _lightButtonStyle(),
                  child: Text(tertiaryLabel!, maxLines: 1),
                ),
              ),
              const SizedBox(width: 8),
            ],
            if (secondaryLabel != null) ...[
              Expanded(
                child: OutlinedButton(
                  onPressed: onSecondary,
                  style: _lightButtonStyle(),
                  child: Text(secondaryLabel!, maxLines: 1),
                ),
              ),
              const SizedBox(width: 8),
            ],
            Expanded(
              child: FilledButton(
                onPressed: onPrimary,
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                  backgroundColor: Colors.black,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                child: Text(primaryLabel, maxLines: 1),
              ),
            ),
          ]),
        ),
      );

  ButtonStyle _lightButtonStyle() => OutlinedButton.styleFrom(
        minimumSize: const Size.fromHeight(48),
        foregroundColor: Colors.black,
        backgroundColor: const Color(0xfff5f5f5),
        side: BorderSide.none,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      );
}

String catalogMoney(double value) => value == value.roundToDouble()
    ? value.toStringAsFixed(0)
    : value.toStringAsFixed(2);
