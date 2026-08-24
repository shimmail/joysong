import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/presentation/report_action_button.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

enum CatalogReviewFilter { all, positive, negative }

List<Map<String, Object?>> filterCatalogReviews(
  List<Map<String, Object?>> reviews,
  CatalogReviewFilter filter,
) =>
    switch (filter) {
      CatalogReviewFilter.all => reviews,
      CatalogReviewFilter.positive =>
        reviews.where((review) => _rating(review) >= 4).toList(growable: false),
      CatalogReviewFilter.negative => reviews
          .where((review) => _rating(review) > 0 && _rating(review) <= 2)
          .toList(growable: false),
    };

class CatalogReviewFilters extends StatelessWidget {
  const CatalogReviewFilters({
    required this.value,
    required this.onChanged,
    super.key,
  });

  final CatalogReviewFilter value;
  final ValueChanged<CatalogReviewFilter> onChanged;

  @override
  Widget build(BuildContext context) => Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (final filter in CatalogReviewFilter.values)
            ChoiceChip(
              label: Text(switch (filter) {
                CatalogReviewFilter.all => context.localized('全部', 'All'),
                CatalogReviewFilter.positive =>
                  context.localized('只看好评', 'Positive'),
                CatalogReviewFilter.negative =>
                  context.localized('只看差评', 'Negative'),
              }),
              selected: value == filter,
              onSelected: (_) => onChanged(filter),
            ),
        ],
      );
}

class CatalogReviewPreview extends StatefulWidget {
  const CatalogReviewPreview({
    required this.reviews,
    this.onViewAll,
    this.socialController,
    this.previewCount = 5,
    this.showHeader = true,
    this.enableAutoTranslation = false,
    this.ownerType = '',
    this.ownerId = '',
    super.key,
  });

  final List<Map<String, Object?>> reviews;
  final VoidCallback? onViewAll;
  final SocialController? socialController;
  final int previewCount;
  final bool showHeader;
  final bool enableAutoTranslation;
  final String ownerType;
  final String ownerId;

  @override
  State<CatalogReviewPreview> createState() => _CatalogReviewPreviewState();
}

class _CatalogReviewPreviewState extends State<CatalogReviewPreview> {
  CatalogReviewFilter _filter = CatalogReviewFilter.all;

  @override
  Widget build(BuildContext context) {
    final filtered = filterCatalogReviews(widget.reviews, _filter);
    final visible = filtered.take(widget.previewCount).toList(growable: false);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (widget.showHeader)
          Row(
            children: [
              Expanded(
                child: Text(
                  context.localized('用户评价', 'Patient reviews'),
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              if (widget.reviews.isNotEmpty && widget.onViewAll != null)
                TextButton(
                  onPressed: widget.onViewAll,
                  child: Text(context.localized(
                    '全部 (${widget.reviews.length})',
                    'All (${widget.reviews.length})',
                  )),
                ),
            ],
          ),
        if (widget.showHeader) const SizedBox(height: 8),
        CatalogReviewFilters(
          value: _filter,
          onChanged: (value) => setState(() => _filter = value),
        ),
        const SizedBox(height: 12),
        if (visible.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 22),
            child: Center(
              child: Text(context.localized(
                _filter == CatalogReviewFilter.all ? '暂无用户评价' : '暂无符合条件的评价',
                _filter == CatalogReviewFilter.all
                    ? 'No reviews yet'
                    : 'No matching reviews',
              )),
            ),
          )
        else
          for (var index = 0; index < visible.length; index++) ...[
            CatalogReviewCard(
              key: _reviewKey(
                visible[index],
                widget.ownerType,
                widget.ownerId,
              ),
              review: visible[index],
              socialController: widget.socialController,
              enableAutoTranslation: widget.enableAutoTranslation,
              ownerType: widget.ownerType,
              ownerId: widget.ownerId,
              reviewId: _reviewId(visible[index]),
            ),
            if (index != visible.length - 1) const SizedBox(height: 10),
          ],
      ],
    );
  }
}

class CatalogReviewCard extends StatelessWidget {
  const CatalogReviewCard({
    required this.review,
    this.socialController,
    this.enableAutoTranslation = false,
    this.ownerType = '',
    this.ownerId = '',
    this.reviewId = '',
    super.key,
  });

  final Map<String, Object?> review;
  final SocialController? socialController;
  final bool enableAutoTranslation;
  final String ownerType;
  final String ownerId;
  final String reviewId;

  @override
  Widget build(BuildContext context) {
    final id = reviewId.trim().isEmpty ? _reviewId(review) : reviewId.trim();
    final translationContentId = _reviewTranslationContentId(
      ownerType: ownerType,
      ownerId: ownerId,
      reviewId: id,
    );
    final userName = _text(review['userName']).isEmpty
        ? context.localized('匿名用户', 'Anonymous user')
        : _text(review['userName']);
    final avatar = _text(
      review['userAvatar'] ?? review['avatarUrl'] ?? review['avatar'],
    );
    final content = _text(review['content']);
    final rating = _rating(review);
    final createdAt = _text(review['createdAt']);
    final tags = _list(review['tags']);
    final images = _list(review['imageUrls'] ?? review['images']);
    final projectName = _text(review['projectName'] ?? review['serviceName']);
    final eligibleProjectName = _text(review['projectName']);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
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
                  child:
                      avatar.isEmpty ? Text(userName.characters.first) : null,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(userName,
                          style: const TextStyle(fontWeight: FontWeight.w700)),
                      if (createdAt.isNotEmpty)
                        Text(
                          createdAt.length >= 10
                              ? createdAt.substring(0, 10)
                              : createdAt,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                    ],
                  ),
                ),
                if (rating > 0)
                  Text(
                    '★ ${rating.toStringAsFixed(1)}',
                    style: const TextStyle(
                      color: Color(0xffffa000),
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                if (id.isNotEmpty && socialController != null)
                  ReportActionButton(
                    controller: socialController!,
                    type: ReportTargetType.review,
                    targetId: id,
                    compact: true,
                    iconSize: 20,
                  ),
              ],
            ),
            const SizedBox(height: 10),
            if (content.isEmpty)
              Text(
                context.localized('用户未填写文字评价', 'No written review'),
                style: const TextStyle(height: 1.45),
              )
            else
              _ReviewTranslatedText(
                enabled: enableAutoTranslation,
                contentId: translationContentId,
                field: 'content',
                source: content,
                style: const TextStyle(height: 1.45),
              ),
            if (projectName.isNotEmpty) ...[
              const SizedBox(height: 7),
              eligibleProjectName.isEmpty
                  ? Text(
                      projectName,
                      style: Theme.of(context).textTheme.bodySmall,
                    )
                  : _ReviewTranslatedText(
                      enabled: enableAutoTranslation,
                      contentId: translationContentId,
                      field: 'projectName',
                      source: eligibleProjectName,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
            ],
            if (tags.isNotEmpty) ...[
              const SizedBox(height: 10),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (var index = 0; index < tags.length; index++)
                    Chip(
                      label: _ReviewTranslatedText(
                        enabled: enableAutoTranslation,
                        contentId: translationContentId,
                        field: 'tags:$index',
                        source: tags[index],
                      ),
                      visualDensity: VisualDensity.compact,
                    ),
                ],
              ),
            ],
            if (images.isNotEmpty) ...[
              const SizedBox(height: 10),
              SizedBox(
                height: 88,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: images.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 8),
                  itemBuilder: (context, index) => GestureDetector(
                    onTap: () => Navigator.of(context).push<void>(
                      MaterialPageRoute(
                        builder: (_) => FullscreenImagePager(
                          images: images,
                          contentDescription:
                              context.localized('评价图片', 'Review image'),
                          initialPage: index,
                        ),
                      ),
                    ),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(10),
                      child: OptimizedNetworkImage(
                        url: images[index],
                        width: 88,
                        height: 88,
                        errorBuilder: (context, _, __) => Container(
                          width: 88,
                          height: 88,
                          color: Theme.of(context)
                              .colorScheme
                              .surfaceContainerHighest,
                          alignment: Alignment.center,
                          child: const Icon(Icons.broken_image_outlined),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ReviewTranslatedText extends StatefulWidget {
  const _ReviewTranslatedText({
    required this.enabled,
    required this.contentId,
    required this.field,
    required this.source,
    this.style,
  });

  final bool enabled;
  final String contentId;
  final String field;
  final String source;
  final TextStyle? style;

  @override
  State<_ReviewTranslatedText> createState() => _ReviewTranslatedTextState();
}

class _ReviewTranslatedTextState extends State<_ReviewTranslatedText> {
  AutoTranslationRequest? _request;

  @override
  void initState() {
    super.initState();
    _updateRequest();
  }

  @override
  void didUpdateWidget(_ReviewTranslatedText oldWidget) {
    super.didUpdateWidget(oldWidget);
    _updateRequest();
  }

  void _updateRequest() {
    if (!widget.enabled || widget.contentId.isEmpty || widget.source.isEmpty) {
      _request = null;
      return;
    }
    final previous = _request;
    if (previous != null &&
        previous.contentId == widget.contentId &&
        previous.field == widget.field &&
        previous.sourceText == widget.source) {
      return;
    }
    _request = AutoTranslationRequest(
      contentType: 'comment',
      contentId: widget.contentId,
      field: widget.field,
      sourceText: widget.source,
    );
  }

  @override
  Widget build(BuildContext context) {
    final request = _request;
    if (request == null) return Text(widget.source, style: widget.style);
    return AutoTranslatedText(request: request, style: widget.style);
  }
}

double _rating(Map<String, Object?> review) {
  final value = review['rating'];
  return value is num ? value.toDouble() : double.tryParse('$value') ?? 0;
}

String _reviewId(Map<String, Object?> review) =>
    _text(review['id'] ?? review['reviewId']);

Key _reviewKey(
  Map<String, Object?> review,
  String ownerType,
  String ownerId,
) {
  final reviewId = _reviewId(review);
  if (reviewId.isEmpty) return ObjectKey(review);
  return ValueKey<String>(
    'catalog-review:${ownerType.trim()}:${ownerId.trim()}:$reviewId',
  );
}

String _reviewTranslationContentId({
  required String ownerType,
  required String ownerId,
  required String reviewId,
}) {
  final type = ownerType.trim();
  final owner = ownerId.trim();
  final review = reviewId.trim();
  if (type.isEmpty || owner.isEmpty || review.isEmpty) return '';
  return '$type:$owner:review:$review';
}

String _text(Object? value) => value?.toString().trim() ?? '';

List<String> _list(Object? value) =>
    (value is Iterable ? value : _text(value).split(','))
        .map((item) => _text(item))
        .where((item) => item.isNotEmpty)
        .toList(growable: false);
