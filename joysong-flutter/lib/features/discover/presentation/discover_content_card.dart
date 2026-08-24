import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/social/presentation/diary_media_grid.dart';

class DiscoverContentCard extends StatelessWidget {
  const DiscoverContentCard({
    required this.item,
    required this.onTap,
    this.onInstitutionProjectTap,
    super.key,
  });

  final DiscoverItem item;
  final VoidCallback onTap;
  final void Function(String institutionId, String projectId)?
      onInstitutionProjectTap;

  @override
  Widget build(BuildContext context) => switch (item.type) {
        DiscoverContentType.project => _ProjectCard(
            item: item,
            onTap: onTap,
            onInstitutionProjectTap: onInstitutionProjectTap,
          ),
        DiscoverContentType.doctor => _DoctorCard(item: item, onTap: onTap),
        DiscoverContentType.institution =>
          _InstitutionCard(item: item, onTap: onTap),
        DiscoverContentType.diary => _DiaryCard(item: item, onTap: onTap),
        DiscoverContentType.article => _ArticleCard(item: item, onTap: onTap),
        DiscoverContentType.all => _BaseCard(item: item, onTap: onTap),
      };
}

class _ProjectCard extends StatefulWidget {
  const _ProjectCard({
    required this.item,
    required this.onTap,
    this.onInstitutionProjectTap,
  });
  final DiscoverItem item;
  final VoidCallback onTap;
  final void Function(String institutionId, String projectId)?
      onInstitutionProjectTap;

  @override
  State<_ProjectCard> createState() => _ProjectCardState();
}

class _ProjectCardState extends State<_ProjectCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final data = _primary(item);
    final titleField = _matchingField(
      data,
      item.title,
      const ['title', 'name', 'projectName'],
    );
    final metaField = _matchingField(
      data,
      item.meta,
      const ['city', 'category', 'institutionName', 'department'],
    );
    final subtitleField = _matchingField(
      data,
      item.subtitle,
      const ['description', 'summary', 'content', 'specialties', 'address'],
    );
    final price = _number(data, const ['price', 'referencePrice']);
    final rating = _number(data, const ['rating']);
    final reviews = _integer(data, const ['reviewCount']);
    final institutions = _maps(item.raw['institutionProjects']);
    return Card(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          InkWell(
            onTap: widget.onTap,
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _Cover(
                    url: _projectCover(item, institutions),
                    width: 116,
                    height: 128,
                    icon: Icons.spa,
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 3),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          titleField == null
                              ? _Title(item.title)
                              : _discoverTitle(
                                  item,
                                  titleField,
                                  item.title,
                                ),
                          if (item.meta.isNotEmpty) ...[
                            const SizedBox(height: 4),
                            metaField == null
                                ? _Muted(item.meta)
                                : _discoverMuted(
                                    item,
                                    metaField,
                                    item.meta,
                                  ),
                          ],
                          if (item.subtitle.isNotEmpty) ...[
                            const SizedBox(height: 6),
                            subtitleField == null
                                ? _Muted(item.subtitle, maxLines: 2)
                                : _discoverMuted(
                                    item,
                                    subtitleField,
                                    item.subtitle,
                                    maxLines: 2,
                                  ),
                          ],
                          const SizedBox(height: 8),
                          Row(
                            children: [
                              if (price != null)
                                Text(
                                  '\$${_money(price)}',
                                  style: TextStyle(
                                    color:
                                        Theme.of(context).colorScheme.primary,
                                    fontWeight: FontWeight.w700,
                                    fontSize: 16,
                                  ),
                                ),
                              const Spacer(),
                              if (rating != null && rating > 0)
                                _Rating(value: rating, reviews: reviews),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (institutions.isNotEmpty) ...[
            const Divider(height: 1),
            InkWell(
              onTap: () => setState(() => _expanded = !_expanded),
              child: Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        context.isEnglish
                            ? '${institutions.length} available institution projects'
                            : '${institutions.length}个相关机构项目',
                        style: Theme.of(context).textTheme.labelLarge?.copyWith(
                              color: Theme.of(context).colorScheme.primary,
                              fontWeight: FontWeight.w600,
                            ),
                      ),
                    ),
                    Text(
                      _expanded
                          ? context.localized('收起', 'Collapse')
                          : context.localized('展开', 'Expand'),
                      style: Theme.of(context).textTheme.labelMedium,
                    ),
                    AnimatedRotation(
                      turns: _expanded ? .5 : 0,
                      duration: const Duration(milliseconds: 180),
                      child: const Icon(Icons.keyboard_arrow_down_rounded),
                    ),
                  ],
                ),
              ),
            ),
            AnimatedCrossFade(
              duration: const Duration(milliseconds: 180),
              crossFadeState: _expanded
                  ? CrossFadeState.showSecond
                  : CrossFadeState.showFirst,
              firstChild: const SizedBox(width: double.infinity),
              secondChild: Column(
                children: [
                  for (final entry in institutions)
                    _InstitutionProjectEntry(
                      entry: entry,
                      onTap: widget.onInstitutionProjectTap,
                    ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _InstitutionProjectEntry extends StatelessWidget {
  const _InstitutionProjectEntry({required this.entry, this.onTap});

  final Map<String, Object?> entry;
  final void Function(String institutionId, String projectId)? onTap;

  @override
  Widget build(BuildContext context) {
    final nested = _map(entry['institutionProject']);
    final institution = _map(entry['institution']);
    final data = <String, Object?>{...nested, ...institution, ...entry};
    final institutionId = _text(data, const ['institutionId']);
    final projectId = _text(data, const ['projectId']);
    final name = _text(data, const ['name', 'projectName']);
    final institutionName = _text(data, const ['institutionName']);
    final price = _number(data, const ['price']);
    final cover = _firstImageValue(data);
    final enabled =
        institutionId.isNotEmpty && projectId.isNotEmpty && onTap != null;
    return InkWell(
      onTap: enabled ? () => onTap!(institutionId, projectId) : null,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 8, 10, 8),
        child: Row(
          children: [
            _Cover(
              url: cover,
              width: 64,
              height: 52,
              icon: Icons.apartment_outlined,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    name.isEmpty
                        ? context.localized('机构项目', 'Institution project')
                        : name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                  if (institutionName.isNotEmpty) ...[
                    const SizedBox(height: 3),
                    _Muted(institutionName),
                  ],
                ],
              ),
            ),
            if (price != null)
              Text(
                '\$${_money(price)}',
                style: TextStyle(
                  color: Theme.of(context).colorScheme.primary,
                  fontWeight: FontWeight.w700,
                ),
              ),
            if (enabled) const Icon(Icons.chevron_right_rounded, size: 20),
          ],
        ),
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
    final name = _text(data, const ['name', 'nickname']);
    final title = _text(
      data,
      const ['title', 'professionalTitle', 'doctorTitle'],
    );
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
                    Flexible(
                      child: _Title(name.isEmpty ? item.title : name),
                    ),
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
                _discoverJoinedMuted(
                  item,
                  [
                    ('professionalTitle', title),
                    ('institutionName', institution),
                  ],
                ),
                if (specialties.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 6,
                    runSpacing: 5,
                    children: [
                      for (final value in specialties)
                        _discoverTag(item, 'specialties', value),
                    ],
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
    final city = _text(data, const ['city']);
    final streetAddress = _text(data, const ['address']);
    final address =
        [city, streetAddress].where((value) => value.isNotEmpty).join(' · ');
    final titleField = _matchingField(
      data,
      item.title,
      const ['title', 'name', 'projectName'],
    );
    final subtitleField = _matchingField(
      data,
      item.subtitle,
      const ['description', 'summary', 'content', 'specialties', 'address'],
    );
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
                    Expanded(
                      child: titleField == null
                          ? _Title(item.title)
                          : _discoverTitle(
                              item,
                              titleField,
                              item.title,
                            ),
                    ),
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
                      Expanded(
                        child: _discoverJoinedMuted(
                          item,
                          [
                            ('city', city),
                            ('address', streetAddress),
                          ],
                        ),
                      ),
                    ],
                  ),
                ],
                if (item.subtitle.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  subtitleField == null
                      ? _Muted(item.subtitle, maxLines: 2)
                      : _discoverMuted(
                          item,
                          subtitleField,
                          item.subtitle,
                          maxLines: 2,
                        ),
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
    return DiaryPreviewCard(
      title: item.title,
      content: item.subtitle,
      authorName: _text(data, const ['authorName', 'nickname']),
      authorAvatar: _text(
        data,
        const ['authorAvatar', 'avatarUrl', 'avatar'],
      ),
      publishDate: _diaryDate(data, fallback: item.meta),
      projectName: _text(data, const ['projectName']),
      images: _tokens(data['imageUrls'] ?? data['images']),
      beforeImages: _tokens(
        data['beforeImageUrls'] ?? data['beforeImages'],
      ),
      afterImages: _tokens(data['afterImageUrls'] ?? data['afterImages']),
      likeCount: _integer(data, const ['likeCount']),
      favoriteCount: _integer(data, const ['favoriteCount']),
      commentCount: _integer(data, const ['commentCount']),
      enableAutoTranslation: true,
      autoTranslationContentId: '${item.type.name}:${item.id}',
      isLiked: _boolean(data['isLiked']),
      isFavorited: _boolean(data['isFavorited']),
      onTap: onTap,
    );
  }
}

class DiaryPreviewCard extends StatelessWidget {
  const DiaryPreviewCard({
    required this.title,
    required this.content,
    required this.authorName,
    required this.authorAvatar,
    required this.publishDate,
    required this.projectName,
    required this.images,
    required this.beforeImages,
    required this.afterImages,
    required this.likeCount,
    required this.favoriteCount,
    required this.commentCount,
    required this.onTap,
    this.enableAutoTranslation = false,
    this.autoTranslationContentId = '',
    this.isLiked = false,
    this.isFavorited = false,
    this.trailing,
    super.key,
  });

  factory DiaryPreviewCard.fromData({
    required Map<String, Object?> data,
    required VoidCallback onTap,
    bool enableAutoTranslation = false,
    Key? key,
  }) {
    final nested = data['diary'];
    final source = nested is Map
        ? <String, Object?>{
            ...nested.map((key, value) => MapEntry(key.toString(), value)),
            ...data,
          }
        : data;
    final images = _tokens(source['imageUrls'] ?? source['images']);
    final coverImage = _text(source, const ['coverImage', 'imageUrl']);
    final id = _text(source, const ['id', 'diaryId']);
    return DiaryPreviewCard(
      key: key,
      enableAutoTranslation: enableAutoTranslation,
      autoTranslationContentId: 'diary:$id',
      title: _text(source, const ['title']),
      content: _text(source, const ['content', 'description', 'summary']),
      authorName: _text(
        source,
        const ['authorName', 'userName', 'nickname'],
      ),
      authorAvatar: _text(
        source,
        const ['authorAvatar', 'userAvatar', 'avatarUrl', 'avatar'],
      ),
      publishDate: _diaryDate(source),
      projectName: _text(source, const ['projectName']),
      images: images.isEmpty && coverImage.isNotEmpty ? [coverImage] : images,
      beforeImages: _tokens(
        source['beforeImageUrls'] ?? source['beforeImages'],
      ),
      afterImages: _tokens(
        source['afterImageUrls'] ?? source['afterImages'],
      ),
      likeCount: _integer(source, const ['likeCount', 'likesCount']),
      favoriteCount: _integer(
        source,
        const ['favoriteCount', 'favoritesCount', 'collectionCount'],
      ),
      commentCount: _integer(source, const ['commentCount', 'commentsCount']),
      isLiked: _boolean(source['isLiked']),
      isFavorited: _boolean(source['isFavorited']),
      onTap: onTap,
    );
  }

  final String title;
  final String content;
  final String authorName;
  final String authorAvatar;
  final String publishDate;
  final String projectName;
  final List<String> images;
  final List<String> beforeImages;
  final List<String> afterImages;
  final int likeCount;
  final int favoriteCount;
  final int commentCount;
  final bool enableAutoTranslation;
  final String autoTranslationContentId;
  final bool isLiked;
  final bool isFavorited;
  final Widget? trailing;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final initial =
        authorName.trim().isEmpty ? '?' : authorName.trim().characters.first;
    return _CardShell(
      padding: EdgeInsets.zero,
      onTap: onTap,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          DiaryMediaGrid(
            images: images,
            beforeImages: beforeImages,
            afterImages: afterImages,
            height: 220,
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    CircleAvatar(
                      radius: 16,
                      foregroundImage: authorAvatar.trim().isEmpty
                          ? null
                          : NetworkImage(authorAvatar.trim()),
                      child: authorAvatar.trim().isEmpty ? Text(initial) : null,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            authorName.trim().isEmpty
                                ? context.localized('用户', 'User')
                                : authorName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelLarge?.copyWith(
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          if (publishDate.isNotEmpty)
                            Text(
                              publishDate,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.labelSmall?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant,
                              ),
                            ),
                        ],
                      ),
                    ),
                    if (projectName.isNotEmpty)
                      enableAutoTranslation && containsChineseText(projectName)
                          ? AutoTranslationBuilder(
                              request: _diaryRequest(
                                field: 'projectName',
                                source: projectName,
                              ),
                              builder: (_, visibleText) => _Tag(visibleText),
                            )
                          : _Tag(projectName),
                    if (trailing != null) ...[
                      const SizedBox(width: 6),
                      trailing!,
                    ],
                  ],
                ),
                const SizedBox(height: 10),
                enableAutoTranslation
                    ? AutoTranslationBuilder(
                        request: _diaryRequest(
                          field: 'title',
                          source: title,
                        ),
                        builder: (_, visibleText) => _Title(visibleText),
                      )
                    : _Title(title),
                if (content.isNotEmpty) ...[
                  const SizedBox(height: 5),
                  enableAutoTranslation
                      ? AutoTranslationBuilder(
                          request: _diaryRequest(
                            field: 'content',
                            source: content,
                          ),
                          builder: (_, visibleText) =>
                              _Muted(visibleText, maxLines: 2),
                        )
                      : _Muted(content, maxLines: 2),
                ],
                const SizedBox(height: 10),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    _Counter(
                      icon: isLiked ? Icons.favorite : Icons.favorite_border,
                      value: likeCount,
                      activeColor: isLiked ? Colors.red : null,
                    ),
                    const SizedBox(width: 14),
                    _Counter(
                      icon:
                          isFavorited ? Icons.bookmark : Icons.bookmark_border,
                      value: favoriteCount,
                      activeColor:
                          isFavorited ? theme.colorScheme.primary : null,
                    ),
                    const SizedBox(width: 14),
                    _Counter(
                      icon: Icons.chat_bubble_outline,
                      value: commentCount,
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

  AutoTranslationRequest _diaryRequest({
    required String field,
    required String source,
  }) {
    return AutoTranslationRequest(
      contentType: 'diary',
      contentId: autoTranslationContentId,
      field: field,
      sourceText: source,
    );
  }
}

class DiaryPreviewRail extends StatelessWidget {
  const DiaryPreviewRail({
    required this.diaries,
    this.onDiaryTap,
    this.maxItems,
    this.cardWidth = 300,
    this.enableAutoTranslation = false,
    super.key,
  });

  final List<Map<String, Object?>> diaries;
  final ValueChanged<String>? onDiaryTap;
  final int? maxItems;
  final double cardWidth;
  final bool enableAutoTranslation;

  @override
  Widget build(BuildContext context) {
    final count = maxItems == null || diaries.length <= maxItems!
        ? diaries.length
        : maxItems!;
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (var index = 0; index < count; index++) ...[
            if (index > 0) const SizedBox(width: 10),
            SizedBox(
              width: cardWidth,
              child: DiaryPreviewCard.fromData(
                data: diaries[index],
                enableAutoTranslation: enableAutoTranslation,
                onTap: () {
                  final id = _text(diaries[index], const ['id', 'diaryId']);
                  if (id.isNotEmpty) onDiaryTap?.call(id);
                },
              ),
            ),
          ],
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
    final titleField = _matchingField(
      data,
      item.title,
      const ['title', 'name', 'projectName'],
    );
    final subtitleField = _matchingField(
      data,
      item.subtitle,
      const ['description', 'summary', 'content', 'specialties', 'address'],
    );
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
                titleField == null
                    ? _Title(item.title)
                    : _discoverTitle(item, titleField, item.title),
                if (item.subtitle.isNotEmpty) ...[
                  const SizedBox(height: 5),
                  subtitleField == null
                      ? _Muted(item.subtitle, maxLines: 2)
                      : _discoverMuted(
                          item,
                          subtitleField,
                          item.subtitle,
                          maxLines: 2,
                        ),
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
                  _discoverTitle(item, 'title', item.title),
                  if (item.subtitle.isNotEmpty) ...[
                    const SizedBox(height: 5),
                    _discoverMuted(
                      item,
                      'summary',
                      item.subtitle,
                      maxLines: 2,
                    ),
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
      child: OptimizedNetworkImage(
        url: url,
        width: width,
        height: height,
        errorBuilder: (_, __, ___) => fallback,
      ),
    );
  }
}

AutoTranslationRequest _discoverRequest(
  DiscoverItem item,
  String field,
  String source,
) =>
    AutoTranslationRequest(
      contentType:
          item.type == DiscoverContentType.all ? 'general' : item.type.name,
      contentId: '${item.type.name}:${item.id}',
      field: field,
      sourceText: source,
    );

Widget _discoverTitle(
  DiscoverItem item,
  String field,
  String source,
) =>
    AutoTranslationBuilder(
      request: _discoverRequest(item, field, source),
      builder: (_, visibleText) => _Title(visibleText),
    );

Widget _discoverMuted(
  DiscoverItem item,
  String field,
  String source, {
  int maxLines = 1,
}) =>
    AutoTranslationBuilder(
      request: _discoverRequest(item, field, source),
      builder: (_, visibleText) => _Muted(visibleText, maxLines: maxLines),
    );

Widget _discoverJoinedMuted(
  DiscoverItem item,
  List<(String, String)> parts, {
  int maxLines = 1,
}) =>
    _DiscoverJoinedMuted(
      item: item,
      parts: parts,
      maxLines: maxLines,
    );

class _DiscoverJoinedMuted extends StatefulWidget {
  const _DiscoverJoinedMuted({
    required this.item,
    required this.parts,
    required this.maxLines,
  });

  final DiscoverItem item;
  final List<(String, String)> parts;
  final int maxLines;

  @override
  State<_DiscoverJoinedMuted> createState() => _DiscoverJoinedMutedState();
}

class _DiscoverJoinedMutedState extends State<_DiscoverJoinedMuted> {
  late List<AutoTranslationRequest?> _requests;

  @override
  void initState() {
    super.initState();
    _requests = _updatedRequests(const []);
  }

  @override
  void didUpdateWidget(_DiscoverJoinedMuted oldWidget) {
    super.didUpdateWidget(oldWidget);
    _requests = _updatedRequests(_requests);
  }

  List<AutoTranslationRequest?> _updatedRequests(
    List<AutoTranslationRequest?> previous,
  ) {
    return [
      for (var index = 0; index < widget.parts.length; index++)
        _updatedRequest(
          widget.parts[index],
          index < previous.length ? previous[index] : null,
        ),
    ];
  }

  AutoTranslationRequest? _updatedRequest(
    (String, String) part,
    AutoTranslationRequest? previous,
  ) {
    if (!containsChineseText(part.$2)) return null;
    final contentType = widget.item.type == DiscoverContentType.all
        ? 'general'
        : widget.item.type.name;
    final contentId = '${widget.item.type.name}:${widget.item.id}';
    if (previous != null &&
        previous.contentType == contentType &&
        previous.contentId == contentId &&
        previous.field == part.$1 &&
        previous.sourceText == part.$2) {
      return previous;
    }
    return _discoverRequest(widget.item, part.$1, part.$2);
  }

  @override
  Widget build(BuildContext context) {
    Widget buildPart(int index, List<String> visibleParts) {
      if (index == widget.parts.length) {
        return _Muted(
          visibleParts.where((value) => value.isNotEmpty).join(' · '),
          maxLines: widget.maxLines,
        );
      }
      final part = widget.parts[index];
      final request = _requests[index];
      if (request == null) {
        return buildPart(index + 1, [...visibleParts, part.$2]);
      }
      return AutoTranslationBuilder(
        request: request,
        builder: (_, visibleText) =>
            buildPart(index + 1, [...visibleParts, visibleText]),
      );
    }

    return buildPart(0, const []);
  }
}

Widget _discoverTag(
  DiscoverItem item,
  String field,
  String source,
) {
  if (!containsChineseText(source)) return _Tag(source);
  return AutoTranslationBuilder(
    request: _discoverRequest(item, field, source),
    builder: (_, visibleText) => _Tag(visibleText),
  );
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
  const _Counter({required this.icon, required this.value, this.activeColor});
  final IconData icon;
  final int value;
  final Color? activeColor;
  @override
  Widget build(BuildContext context) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            icon,
            size: 15,
            color:
                activeColor ?? Theme.of(context).colorScheme.onSurfaceVariant,
          ),
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

String? _matchingField(
  Map<String, Object?> data,
  String visibleText,
  List<String> keys,
) {
  for (final key in keys) {
    if (_text(data, [key]) == visibleText) return key;
  }
  return null;
}

String _diaryDate(Map<String, Object?> data, {String fallback = ''}) {
  final value = _text(data, const [
    'publishDate',
    'publishedAt',
    'publishTime',
    'createdAt',
    'createdDate',
  ]);
  final candidate = value.isNotEmpty ? value : fallback.trim();
  if (candidate.isEmpty) return '';
  final parsed = DateTime.tryParse(candidate);
  if (parsed != null) {
    final month = parsed.month.toString().padLeft(2, '0');
    final day = parsed.day.toString().padLeft(2, '0');
    return '${parsed.year}-$month-$day';
  }
  return candidate.length >= 10 ? candidate.substring(0, 10) : candidate;
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

List<Map<String, Object?>> _maps(Object? value) {
  if (value is! List) return const [];
  return value
      .whereType<Map>()
      .map(
          (entry) => entry.map((key, value) => MapEntry(key.toString(), value)))
      .toList(growable: false);
}

Map<String, Object?> _map(Object? value) {
  if (value is! Map) return const {};
  return value.map((key, value) => MapEntry(key.toString(), value));
}

String _projectCover(
  DiscoverItem item,
  List<Map<String, Object?>> institutionProjects,
) {
  if (item.imageUrl.trim().isNotEmpty) return item.imageUrl.trim();
  final ownCover = _firstImageValue(item.raw);
  if (ownCover.isNotEmpty) return ownCover;
  for (final entry in institutionProjects) {
    final nested = _map(entry['institutionProject']);
    final cover = _firstImageValue(<String, Object?>{...nested, ...entry});
    if (cover.isNotEmpty) return cover;
  }
  return '';
}

String _firstImageValue(Map<String, Object?> data) {
  for (final key in const [
    'coverImage',
    'coverImageUrl',
    'imageUrl',
    'images',
    'imageUrls',
    'logo',
  ]) {
    final values = _tokens(data[key]);
    if (values.isNotEmpty) return values.first;
  }
  return '';
}

String _money(double value) => value == value.roundToDouble()
    ? value.toStringAsFixed(0)
    : value.toStringAsFixed(2);
