import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';

/// Project detail content shared by ordinary projects and institution projects.
class ProjectDetailView extends StatelessWidget {
  const ProjectDetailView({
    required this.item,
    this.onBook,
    this.onInstitutionProjectTap,
    super.key,
  });

  final DiscoverItem item;
  final ValueChanged<DiscoverItem>? onBook;
  final void Function(String institutionId, String projectId)?
      onInstitutionProjectTap;

  @override
  Widget build(BuildContext context) {
    final detail = _ProjectDetailData.fromItem(item);
    final institutionProjects = _maps(item.raw['institutionProjects']);
    final colors = Theme.of(context).colorScheme;

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: EdgeInsets.zero,
            children: [
              _Gallery(images: detail.images),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (detail.category.isNotEmpty)
                      _Pill(label: detail.category),
                    const SizedBox(height: 10),
                    Text(
                      detail.name,
                      style:
                          Theme.of(context).textTheme.headlineSmall?.copyWith(
                                fontWeight: FontWeight.w700,
                              ),
                    ),
                    if (detail.institutionName.isNotEmpty) ...[
                      const SizedBox(height: 10),
                      Row(
                        children: [
                          Icon(Icons.apartment_rounded,
                              size: 18, color: colors.primary),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              detail.institutionName,
                              style: TextStyle(
                                color: colors.primary,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                    const SizedBox(height: 18),
                    _PriceLine(
                      price: detail.price,
                      originalPrice: detail.originalPrice,
                    ),
                    const SizedBox(height: 18),
                    _Stats(detail: detail),
                    if (detail.tags.isNotEmpty) ...[
                      const SizedBox(height: 18),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: detail.tags
                            .map((tag) => _Pill(label: tag, tonal: true))
                            .toList(growable: false),
                      ),
                    ],
                    if (detail.slogan.isNotEmpty) ...[
                      const SizedBox(height: 24),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: colors.primaryContainer.withValues(alpha: .45),
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: Text(
                          detail.slogan,
                          style:
                              Theme.of(context).textTheme.titleMedium?.copyWith(
                                    color: colors.onPrimaryContainer,
                                    fontWeight: FontWeight.w600,
                                  ),
                        ),
                      ),
                    ],
                    if (institutionProjects.isNotEmpty) ...[
                      const SizedBox(height: 28),
                      _InstitutionProjects(
                        entries: institutionProjects,
                        onTap: onInstitutionProjectTap,
                      ),
                    ],
                    if (detail.description.isNotEmpty)
                      _TextSection(
                        title: context.localized('项目简介', 'Overview'),
                        content: detail.description,
                      ),
                    if (detail.content.isNotEmpty)
                      _TextSection(
                        title: context.localized('项目详情', 'Details'),
                        content: detail.content,
                      ),
                    if (detail.description.isEmpty && detail.content.isEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 28),
                        child: Text(context.localized(
                          '暂无更多项目介绍',
                          'No additional project information',
                        )),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
        if (onBook != null)
          SafeArea(
            top: false,
            child: Container(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
              decoration: BoxDecoration(
                color: Theme.of(context).scaffoldBackgroundColor,
                border: Border(top: BorderSide(color: colors.outlineVariant)),
              ),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: () => onBook!(item),
                  icon: const Icon(Icons.calendar_month_outlined),
                  label: Text(context.localized('立即预约', 'Book now')),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _InstitutionProjects extends StatelessWidget {
  const _InstitutionProjects({required this.entries, this.onTap});

  final List<Map<String, Object?>> entries;
  final void Function(String institutionId, String projectId)? onTap;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          context.localized('选择服务机构', 'Choose an institution'),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 12),
        for (var index = 0; index < entries.length; index++) ...[
          _InstitutionProjectTile(entry: entries[index], onTap: onTap),
          if (index < entries.length - 1) const SizedBox(height: 10),
        ],
      ],
    );
  }
}

class _InstitutionProjectTile extends StatelessWidget {
  const _InstitutionProjectTile({required this.entry, this.onTap});

  final Map<String, Object?> entry;
  final void Function(String institutionId, String projectId)? onTap;

  @override
  Widget build(BuildContext context) {
    final institution = _map(entry['institution']);
    final institutionProject = _map(entry['institutionProject']);
    final institutionId = _text(
      [entry, institution, institutionProject],
      const ['institutionId', 'id'],
      '',
    );
    final projectId = _text(
      [entry, institutionProject],
      const ['projectId'],
      '',
    );
    final name = _text(
      [entry, institution],
      const ['institutionName', 'name'],
      context.localized('医疗机构', 'Institution'),
    );
    final city = _text([institution, entry], const ['city'], '');
    final price = _number([institutionProject, entry], const ['price']);
    return Card(
      margin: EdgeInsets.zero,
      child: ListTile(
        onTap: institutionId.isEmpty || projectId.isEmpty || onTap == null
            ? null
            : () => onTap!(institutionId, projectId),
        leading: const CircleAvatar(child: Icon(Icons.apartment_outlined)),
        title: Text(name),
        subtitle: city.isEmpty ? null : Text(city),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (price != null)
              Text(
                '¥${_money(price)}',
                style: TextStyle(
                  color: Theme.of(context).colorScheme.primary,
                  fontWeight: FontWeight.w700,
                ),
              ),
            const SizedBox(width: 4),
            const Icon(Icons.chevron_right_rounded),
          ],
        ),
      ),
    );
  }
}

class _Gallery extends StatefulWidget {
  const _Gallery({required this.images});

  final List<String> images;

  @override
  State<_Gallery> createState() => _GalleryState();
}

class _GalleryState extends State<_Gallery> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    if (widget.images.isEmpty) {
      return Container(
        height: 240,
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        alignment: Alignment.center,
        child: const Icon(Icons.image_outlined, size: 54),
      );
    }
    return Stack(
      alignment: Alignment.bottomCenter,
      children: [
        SizedBox(
          height: 260,
          child: PageView.builder(
            itemCount: widget.images.length,
            onPageChanged: (value) => setState(() => _index = value),
            itemBuilder: (context, index) => GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => Navigator.of(context).push<void>(
                MaterialPageRoute(
                  builder: (_) => FullscreenImagePager(
                    images: widget.images,
                    contentDescription:
                        context.localized('项目图片', 'Project image'),
                    initialPage: index,
                  ),
                ),
              ),
              child: OptimizedNetworkImage(
                url: widget.images[index],
                width: double.infinity,
                height: 260,
                errorBuilder: (_, __, ___) => Container(
                  color:
                      Theme.of(context).colorScheme.surfaceContainerHighest,
                  alignment: Alignment.center,
                  child: const Icon(Icons.broken_image_outlined, size: 48),
                ),
              ),
            ),
          ),
        ),
        if (widget.images.length > 1)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                child: Text(
                  '${_index + 1}/${widget.images.length}',
                  style: const TextStyle(color: Colors.white),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _PriceLine extends StatelessWidget {
  const _PriceLine({required this.price, required this.originalPrice});

  final double? price;
  final double? originalPrice;

  @override
  Widget build(BuildContext context) {
    if (price == null || price! <= 0) {
      return Text(
        context.localized('价格面议', 'Contact for price'),
        style: Theme.of(context).textTheme.titleLarge?.copyWith(
              color: Theme.of(context).colorScheme.primary,
              fontWeight: FontWeight.w700,
            ),
      );
    }
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 10,
      children: [
        Text(
          '¥${_money(price!)}',
          style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                color: Theme.of(context).colorScheme.error,
                fontWeight: FontWeight.w800,
              ),
        ),
        if (originalPrice != null && originalPrice! > price!)
          Text(
            '¥${_money(originalPrice!)}',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  decoration: TextDecoration.lineThrough,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
      ],
    );
  }
}

class _Stats extends StatelessWidget {
  const _Stats({required this.detail});

  final _ProjectDetailData detail;

  @override
  Widget build(BuildContext context) {
    final muted = Theme.of(context).colorScheme.onSurfaceVariant;
    return Wrap(
      spacing: 20,
      runSpacing: 8,
      children: [
        if (detail.rating != null)
          _Stat(
            icon: Icons.star_rounded,
            color: Colors.amber.shade700,
            text: '${detail.rating!.toStringAsFixed(1)}'
                '${detail.reviewCount == null ? '' : ' (${detail.reviewCount})'}',
          ),
        if (detail.salesCount != null)
          _Stat(
            icon: Icons.local_fire_department_outlined,
            color: muted,
            text: context.isEnglish
                ? '${detail.salesCount} sold'
                : '已售 ${detail.salesCount}',
          ),
      ],
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat({required this.icon, required this.color, required this.text});

  final IconData icon;
  final Color color;
  final String text;

  @override
  Widget build(BuildContext context) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 5),
          Text(text),
        ],
      );
}

class _Pill extends StatelessWidget {
  const _Pill({required this.label, this.tonal = false});

  final String label;
  final bool tonal;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: tonal ? colors.secondaryContainer : colors.primaryContainer,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelMedium?.copyWith(
              color: tonal
                  ? colors.onSecondaryContainer
                  : colors.onPrimaryContainer,
            ),
      ),
    );
  }
}

class _TextSection extends StatelessWidget {
  const _TextSection({required this.title, required this.content});

  final String title;
  final String content;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 28),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            RichContentView(
              content: content,
              textStyle: const TextStyle(height: 1.65),
            ),
          ],
        ),
      );
}

class _ProjectDetailData {
  const _ProjectDetailData({
    required this.name,
    required this.institutionName,
    required this.price,
    required this.originalPrice,
    required this.category,
    required this.rating,
    required this.reviewCount,
    required this.salesCount,
    required this.tags,
    required this.description,
    required this.slogan,
    required this.content,
    required this.images,
  });

  factory _ProjectDetailData.fromItem(DiscoverItem item) {
    final raw = item.raw;
    final institutionProject = _map(raw['institutionProject']);
    final project = _map(raw['project']);
    final institution = _map(raw['institution']);
    final sources = [institutionProject, raw, project];
    final images = <String>{};
    for (final source in sources) {
      for (final key in const ['coverImage', 'imageUrl', 'images']) {
        images.addAll(_strings(source[key]));
      }
    }
    if (item.imageUrl.isNotEmpty) images.add(item.imageUrl);

    return _ProjectDetailData(
      name: _text(sources, const ['name', 'projectName', 'title'], item.title),
      institutionName: _text(
        [institutionProject, raw, institution],
        const ['institutionName', 'name'],
        '',
      ),
      price: _number(sources, const ['price', 'referencePrice']),
      originalPrice: _number(sources, const ['originalPrice']),
      category: _text(sources, const ['category'], item.meta),
      rating: _number(sources, const ['rating']),
      reviewCount: _integer(sources, const ['reviewCount']),
      salesCount: _integer(sources, const ['salesCount']),
      tags: {
        ..._values(sources, const ['tags']),
        ..._values(sources, const ['categoryTags']),
      }.toList(growable: false),
      description:
          _text(sources, const ['description', 'summary'], item.subtitle),
      slogan: _text(sources, const ['slogan'], ''),
      content: _text(sources, const ['detailContent', 'content'], ''),
      images: images.where((value) => value.isNotEmpty).toList(growable: false),
    );
  }

  final String name;
  final String institutionName;
  final double? price;
  final double? originalPrice;
  final String category;
  final double? rating;
  final int? reviewCount;
  final int? salesCount;
  final List<String> tags;
  final String description;
  final String slogan;
  final String content;
  final List<String> images;
}

List<Map<String, Object?>> _maps(Object? value) {
  if (value is! List) return const [];
  return value
      .whereType<Map>()
      .map((item) => item.map((key, value) => MapEntry(key.toString(), value)))
      .toList(growable: false);
}

Map<String, Object?> _map(Object? value) => value is Map
    ? value.map((key, value) => MapEntry(key.toString(), value))
    : const {};

String _text(
    List<Map<String, Object?>> sources, List<String> keys, String fallback) {
  for (final source in sources) {
    for (final key in keys) {
      final value = source[key]?.toString().trim() ?? '';
      if (value.isNotEmpty) return value;
    }
  }
  return fallback;
}

double? _number(List<Map<String, Object?>> sources, List<String> keys) {
  for (final source in sources) {
    for (final key in keys) {
      final value = source[key];
      final parsed =
          value is num ? value.toDouble() : double.tryParse('$value');
      if (parsed != null) return parsed;
    }
  }
  return null;
}

int? _integer(List<Map<String, Object?>> sources, List<String> keys) =>
    _number(sources, keys)?.round();

Iterable<String> _values(
    List<Map<String, Object?>> sources, List<String> keys) sync* {
  for (final source in sources) {
    for (final key in keys) {
      yield* _strings(source[key]);
    }
  }
}

Iterable<String> _strings(Object? value) sync* {
  final values = value is List ? value : (value?.toString() ?? '').split(',');
  for (final part in values) {
    final normalized = part.toString().trim();
    if (normalized.isNotEmpty) yield normalized;
  }
}

String _money(double value) => value == value.roundToDouble()
    ? value.toStringAsFixed(0)
    : value.toStringAsFixed(2);
