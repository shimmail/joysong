import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/social/presentation/diary_media_grid.dart';

class DiscoverContentCard extends StatelessWidget {
  const DiscoverContentCard({
    required this.item,
    required this.onTap,
    super.key,
  });

  final DiscoverItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => switch (item.type) {
        DiscoverContentType.project => _ProjectCard(item: item, onTap: onTap),
        DiscoverContentType.doctor => _DoctorCard(item: item, onTap: onTap),
        DiscoverContentType.institution =>
          _InstitutionCard(item: item, onTap: onTap),
        DiscoverContentType.diary => _DiaryCard(item: item, onTap: onTap),
        DiscoverContentType.article => _ArticleCard(item: item, onTap: onTap),
        DiscoverContentType.all => _BaseCard(item: item, onTap: onTap),
      };
}

class _ProjectCard extends StatelessWidget {
  const _ProjectCard({required this.item, required this.onTap});
  final DiscoverItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final data = _primary(item);
    final price = _number(data, const ['price', 'referencePrice']);
    final rating = _number(data, const ['rating']);
    final reviews = _integer(data, const ['reviewCount']);
    final institutions = item.raw['institutionProjects'];
    final institutionCount = institutions is List ? institutions.length : 0;
    return _CardShell(
      onTap: onTap,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _Cover(url: item.imageUrl, width: 116, height: 128, icon: Icons.spa),
          const SizedBox(width: 14),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 3),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _Title(item.title),
                  if (item.meta.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    _Muted(item.meta),
                  ],
                  if (item.subtitle.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    _Muted(item.subtitle, maxLines: 2),
                  ],
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      if (price != null)
                        Text(
                          '¥${_money(price)}',
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.primary,
                            fontWeight: FontWeight.w700,
                            fontSize: 16,
                          ),
                        ),
                      const Spacer(),
                      if (rating != null && rating > 0)
                        _Rating(value: rating, reviews: reviews),
                    ],
                  ),
                  if (institutionCount > 0) ...[
                    const SizedBox(height: 5),
                    Text(
                      context.isEnglish
                          ? '$institutionCount institutions available'
                          : '$institutionCount家机构可预约',
                      style: Theme.of(context).textTheme.labelSmall?.copyWith(
                            color: Theme.of(context).colorScheme.primary,
                          ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _DoctorCard extends StatelessWidget {
  const _DoctorCard({required this.item, required this.onTap});
  final DiscoverItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final data = _primary(item);
    final title = _text(data, const ['title']);
    final institution = _text(data, const ['institutionName']);
    final specialties = _tokens(data['specialties']).take(3).toList();
    final verified = _boolean(data['isVerified']);
    final rating = _number(data, const ['rating']);
    return _CardShell(
      onTap: onTap,
      child: Row(
        children: [
          ClipOval(
            child: _Cover(
              url: item.imageUrl,
              width: 78,
              height: 78,
              icon: Icons.person_outline,
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(child: _Title(item.title)),
                    if (verified) ...[
                      const SizedBox(width: 5),
                      Icon(
                        Icons.verified_rounded,
                        size: 17,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 4),
                _Muted(
                  [title, institution]
                      .where((value) => value.isNotEmpty)
                      .join(' · '),
                ),
                if (specialties.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 6,
                    runSpacing: 5,
                    children: [for (final value in specialties) _Tag(value)],
                  ),
                ],
                if (rating != null && rating > 0) ...[
                  const SizedBox(height: 8),
                  _Rating(
                    value: rating,
                    reviews: _integer(data, const ['reviewCount']),
                  ),
                ],
              ],
            ),
          ),
          const Icon(Icons.chevron_right_rounded),
        ],
      ),
    );
  }
}

class _InstitutionCard extends StatelessWidget {
  const _InstitutionCard({required this.item, required this.onTap});
  final DiscoverItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final data = _primary(item);
    final verified = _boolean(data['isVerified']);
    final address = [
      _text(data, const ['city']),
      _text(data, const ['address']),
    ].where((value) => value.isNotEmpty).join(' · ');
    return _CardShell(
      onTap: onTap,
      child: Row(
        children: [
          _Cover(
            url: item.imageUrl,
            width: 92,
            height: 92,
            icon: Icons.apartment_outlined,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(child: _Title(item.title)),
                    if (verified)
                      Icon(
                        Icons.verified_rounded,
                        size: 17,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                  ],
                ),
                if (address.isNotEmpty) ...[
                  const SizedBox(height: 5),
                  Row(
                    children: [
                      const Icon(Icons.location_on_outlined, size: 15),
                      const SizedBox(width: 3),
                      Expanded(child: _Muted(address)),
                    ],
                  ),
                ],
                if (item.subtitle.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  _Muted(item.subtitle, maxLines: 2),
                ],
                const SizedBox(height: 7),
                _Rating(
                  value: _number(data, const ['rating']) ?? 0,
                  reviews: _integer(data, const ['reviewCount']),
                ),
              ],
            ),
          ),
          const Icon(Icons.chevron_right_rounded),
        ],
      ),
    );
  }
}

class _DiaryCard extends StatelessWidget {
  const _DiaryCard({required this.item, required this.onTap});
  final DiscoverItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final data = _primary(item);
    final author = _text(data, const ['authorName']);
    final images = _tokens(data['imageUrls'] ?? data['images']);
    final beforeImages =
        _tokens(data['beforeImageUrls'] ?? data['beforeImages']);
    final afterImages = _tokens(data['afterImageUrls'] ?? data['afterImages']);
    return _CardShell(
      padding: EdgeInsets.zero,
      onTap: onTap,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DiaryMediaGrid(
            images: images,
            beforeImages: beforeImages,
            afterImages: afterImages,
            height: 220,
          ),
          Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _Title(item.title),
                if (item.subtitle.isNotEmpty) ...[
                  const SizedBox(height: 5),
                  _Muted(item.subtitle, maxLines: 2),
                ],
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(child: _Muted(author)),
                    _Counter(
                      icon: Icons.favorite_border,
                      value: _integer(data, const ['likeCount']),
                    ),
                    const SizedBox(width: 10),
                    _Counter(
                      icon: Icons.chat_bubble_outline,
                      value: _integer(data, const ['commentCount']),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ArticleCard extends StatelessWidget {
  const _ArticleCard({required this.item, required this.onTap});
  final DiscoverItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final data = _primary(item);
    return _CardShell(
      padding: EdgeInsets.zero,
      onTap: onTap,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _Cover(
            url: item.imageUrl,
            width: double.infinity,
            height: 178,
            icon: Icons.article_outlined,
          ),
          Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _Title(item.title),
                if (item.subtitle.isNotEmpty) ...[
                  const SizedBox(height: 5),
                  _Muted(item.subtitle, maxLines: 2),
                ],
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        _text(data, const ['authorName']),
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.primary,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    _Counter(
                      icon: Icons.visibility_outlined,
                      value: _integer(data, const ['readCount']),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _BaseCard extends StatelessWidget {
  const _BaseCard({required this.item, required this.onTap});
  final DiscoverItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => _CardShell(
        onTap: onTap,
        child: Row(
          children: [
            _Cover(
              url: item.imageUrl,
              width: 72,
              height: 72,
              icon: Icons.search_rounded,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _Title(item.title),
                  if (item.subtitle.isNotEmpty) ...[
                    const SizedBox(height: 5),
                    _Muted(item.subtitle, maxLines: 2),
                  ],
                ],
              ),
            ),
          ],
        ),
      );
}

class _CardShell extends StatelessWidget {
  const _CardShell({
    required this.onTap,
    required this.child,
    this.padding = const EdgeInsets.all(12),
  });
  final VoidCallback onTap;
  final Widget child;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => Card(
        margin: EdgeInsets.zero,
        clipBehavior: Clip.antiAlias,
        child: InkWell(
            onTap: onTap, child: Padding(padding: padding, child: child)),
      );
}

class _Cover extends StatelessWidget {
  const _Cover({
    required this.url,
    required this.width,
    required this.height,
    required this.icon,
  });
  final String url;
  final double width;
  final double height;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final fallback = Container(
      width: width,
      height: height,
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      alignment: Alignment.center,
      child: Icon(icon, color: Theme.of(context).colorScheme.primary, size: 30),
    );
    if (url.isEmpty) return fallback;
    return ClipRRect(
      borderRadius: BorderRadius.circular(10),
      child: Image.network(
        url,
        width: width,
        height: height,
        fit: BoxFit.cover,
        errorBuilder: (_, __, ___) => fallback,
      ),
    );
  }
}

class _Title extends StatelessWidget {
  const _Title(this.value);
  final String value;
  @override
  Widget build(BuildContext context) => Text(
        value,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.w700,
            ),
      );
}

class _Muted extends StatelessWidget {
  const _Muted(this.value, {this.maxLines = 1});
  final String value;
  final int maxLines;
  @override
  Widget build(BuildContext context) => Text(
        value,
        maxLines: maxLines,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
      );
}

class _Rating extends StatelessWidget {
  const _Rating({required this.value, required this.reviews});
  final double value;
  final int reviews;
  @override
  Widget build(BuildContext context) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.star_rounded, size: 16, color: Color(0xffffa000)),
          const SizedBox(width: 3),
          Text(value.toStringAsFixed(1)),
          if (reviews > 0)
            Text(
              ' ($reviews)',
              style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant),
            ),
        ],
      );
}

class _Counter extends StatelessWidget {
  const _Counter({required this.icon, required this.value});
  final IconData icon;
  final int value;
  @override
  Widget build(BuildContext context) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon,
              size: 15, color: Theme.of(context).colorScheme.onSurfaceVariant),
          const SizedBox(width: 3),
          Text('$value', style: Theme.of(context).textTheme.labelSmall),
        ],
      );
}

class _Tag extends StatelessWidget {
  const _Tag(this.value);
  final String value;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.primaryContainer,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Text(value, style: Theme.of(context).textTheme.labelSmall),
      );
}

Map<String, Object?> _primary(DiscoverItem item) {
  final key = switch (item.type) {
    DiscoverContentType.project => 'project',
    DiscoverContentType.doctor => 'doctor',
    DiscoverContentType.institution => 'institution',
    DiscoverContentType.diary => 'diary',
    DiscoverContentType.article => 'article',
    DiscoverContentType.all => '',
  };
  final nested = item.raw[key];
  if (nested is! Map) return item.raw;
  return <String, Object?>{
    ...nested.map((key, value) => MapEntry(key.toString(), value)),
    ...item.raw,
  };
}

String _text(Map<String, Object?> data, List<String> keys) {
  for (final key in keys) {
    final value = data[key]?.toString().trim() ?? '';
    if (value.isNotEmpty && value != 'null') return value;
  }
  return '';
}

double? _number(Map<String, Object?> data, List<String> keys) {
  for (final key in keys) {
    final value = data[key];
    final parsed = value is num ? value.toDouble() : double.tryParse('$value');
    if (parsed != null) return parsed;
  }
  return null;
}

int _integer(Map<String, Object?> data, List<String> keys) =>
    _number(data, keys)?.round() ?? 0;

bool _boolean(Object? value) =>
    value == true || value == 1 || value?.toString().toLowerCase() == 'true';

List<String> _tokens(Object? value) {
  final values = value is List ? value : (value?.toString() ?? '').split(',');
  return values
      .map((item) => item.toString().trim())
      .where((item) => item.isNotEmpty)
      .toList(growable: false);
}

String _money(double value) => value == value.roundToDouble()
    ? value.toStringAsFixed(0)
    : value.toStringAsFixed(2);
