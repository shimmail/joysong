import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';

class ArticleDetailView extends StatelessWidget {
  const ArticleDetailView({required this.item, super.key});
  final DiscoverItem item;

  @override
  Widget build(BuildContext context) {
    final raw = item.raw;
    final author = _text(raw, const ['authorName', 'author', 'doctorName']);
    final avatar = _text(raw, const ['authorAvatar', 'avatar']);
    final date = _text(raw, const ['publishDate', 'publishedAt', 'createdAt']);
    final category = _text(raw, const ['category', 'categoryName']);
    final content = _text(raw, const ['content', 'body', 'contentHtml'],
        fallback: item.subtitle);
    final images = _images(raw);
    return SelectionArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
        children: [
          if (item.imageUrl.isNotEmpty)
            ClipRRect(
              borderRadius: BorderRadius.circular(18),
              child: Image.network(item.imageUrl,
                  height: 230,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => const SizedBox.shrink()),
            ),
          const SizedBox(height: 20),
          Text(item.title, style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 14),
          Row(children: [
            CircleAvatar(
              radius: 20,
              foregroundImage: avatar.isEmpty ? null : NetworkImage(avatar),
              child: avatar.isEmpty ? const Icon(Icons.person_outline) : null,
            ),
            const SizedBox(width: 10),
            Expanded(
                child: Text(author.isEmpty
                    ? context.localized('娇颜颂专业团队', 'Joysong editorial team')
                    : author)),
            if (date.isNotEmpty)
              Text(date, style: Theme.of(context).textTheme.bodySmall),
          ]),
          if (category.isNotEmpty) ...[
            const SizedBox(height: 10),
            Align(
              alignment: Alignment.centerLeft,
              child: Chip(label: Text(category)),
            ),
          ],
          const Divider(height: 28),
          RichContentView(
            content: content,
            textStyle:
                Theme.of(context).textTheme.bodyLarge?.copyWith(height: 1.75),
          ),
          for (final image
              in images.where((value) => value != item.imageUrl)) ...[
            const SizedBox(height: 18),
            ClipRRect(
              borderRadius: BorderRadius.circular(14),
              child: Image.network(image,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => const SizedBox.shrink()),
            ),
          ],
          const SizedBox(height: 24),
          Card(
            color: Theme.of(context).colorScheme.surfaceContainerLow,
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Text(context.localized(
                '本文用于健康科普，不替代医生面诊和个体化诊疗建议。',
                'This article is for education and does not replace an in-person medical consultation.',
              )),
            ),
          ),
        ],
      ),
    );
  }
}

String _text(Map<String, Object?> map, List<String> keys,
    {String fallback = ''}) {
  for (final key in keys) {
    final value = map[key]?.toString().trim() ?? '';
    if (value.isNotEmpty) return value;
  }
  return fallback;
}

List<String> _images(Map<String, Object?> map) {
  final value = map['images'];
  return (value is List
          ? value.map((item) => item.toString())
          : (value?.toString() ?? '').split(','))
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toList(growable: false);
}
