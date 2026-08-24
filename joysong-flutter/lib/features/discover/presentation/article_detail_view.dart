import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/core/translation/rich_translation_validator.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';
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
    final body = _text(raw, const ['content', 'body', 'contentHtml']);
    final summary = _text(raw, const ['summary']);
    final content = body.isNotEmpty
        ? body
        : summary.isNotEmpty
            ? summary
            : item.subtitle;
    final contentField = body.isEmpty ? 'summary' : 'content';
    final contentHasEligibleSource = body.isNotEmpty || summary.isNotEmpty;
    final contentIsHtml = _looksLikeHtml(content);
    final contentId = 'article:${item.id}';
    final images = _images(raw);
    final galleryImages = <String>{
      if (item.imageUrl.isNotEmpty) item.imageUrl,
      ...richContentImageUrls(content),
      ...images,
    }.toList(growable: false);
    return SelectionArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
        children: [
          if (item.imageUrl.isNotEmpty)
            GestureDetector(
              onTap: () => Navigator.of(context).push<void>(
                MaterialPageRoute(
                  builder: (_) => FullscreenImagePager(
                    images: galleryImages,
                    contentDescription:
                        context.localized('文章图片', 'Article image'),
                  ),
                ),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(18),
                child: OptimizedNetworkImage(
                  url: item.imageUrl,
                  width: double.infinity,
                  height: 230,
                  errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                ),
              ),
            ),
          const SizedBox(height: 20),
          AutoTranslatedText(
            request: AutoTranslationRequest(
              contentType: 'article',
              contentId: contentId,
              field: 'title',
              sourceText: item.title,
            ),
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 14),
          Row(children: [
            CircleAvatar(
              radius: 20,
              foregroundImage: avatar.isEmpty
                  ? null
                  : optimizedNetworkImageProvider(
                      context,
                      avatar,
                      width: 40,
                      height: 40,
                    ),
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
              child: Chip(
                label: AutoTranslatedText(
                  request: AutoTranslationRequest(
                    contentType: 'article',
                    contentId: contentId,
                    field: 'category',
                    sourceText: category,
                  ),
                ),
              ),
            ),
          ],
          const Divider(height: 28),
          if (contentHasEligibleSource)
            AutoTranslationBuilder(
              request: AutoTranslationRequest(
                contentType: contentIsHtml ? 'article_html' : 'article',
                contentId: contentId,
                field: contentField,
                sourceText: content,
                validator: preservesRichContentStructure,
              ),
              builder: (_, visibleContent) => _ArticleContent(
                content: visibleContent,
                galleryImages: galleryImages,
              ),
            )
          else
            _ArticleContent(
              content: content,
              galleryImages: galleryImages,
            ),
          for (final image in galleryImages.skip(
            item.imageUrl.isEmpty ? 0 : 1,
          )) ...[
            const SizedBox(height: 18),
            GestureDetector(
              onTap: () => Navigator.of(context).push<void>(
                MaterialPageRoute(
                  builder: (_) => FullscreenImagePager(
                    images: galleryImages,
                    contentDescription:
                        context.localized('文章图片', 'Article image'),
                    initialPage: galleryImages.indexOf(image),
                  ),
                ),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(14),
                child: Image.network(image,
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) => const SizedBox.shrink()),
              ),
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

class _ArticleContent extends StatelessWidget {
  const _ArticleContent({required this.content, required this.galleryImages});

  final String content;
  final List<String> galleryImages;

  @override
  Widget build(BuildContext context) => RichContentView(
        content: content,
        textStyle:
            Theme.of(context).textTheme.bodyLarge?.copyWith(height: 1.75),
        onImageTap: (image) => Navigator.of(context).push<void>(
          MaterialPageRoute(
            builder: (_) => FullscreenImagePager(
              images: galleryImages,
              contentDescription: context.localized('文章图片', 'Article image'),
              initialPage: galleryImages.indexOf(image),
            ),
          ),
        ),
      );
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

bool _looksLikeHtml(String value) =>
    RegExp(r'<\/?[a-z][^>]*>', caseSensitive: false).hasMatch(value);
