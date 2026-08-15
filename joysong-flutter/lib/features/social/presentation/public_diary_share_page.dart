import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/social/presentation/diary_media_grid.dart';

final class PublicDiarySharePage extends StatefulWidget {
  const PublicDiarySharePage({
    required this.apiRoot,
    required this.token,
    super.key,
  });

  final Uri apiRoot;
  final String token;

  @override
  State<PublicDiarySharePage> createState() => _PublicDiarySharePageState();
}

final class _PublicDiarySharePageState extends State<PublicDiarySharePage> {
  late final ApiClient _apiClient;
  late final PublicMediaUrlResolver _mediaResolver;
  PublicDiaryShare? _share;
  Object? _error;
  bool _loading = true;

  bool get _english => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    _apiClient = ApiClient(apiRoot: widget.apiRoot);
    _mediaResolver = ApiPublicMediaUrlResolver(widget.apiRoot);
    _load();
  }

  @override
  void dispose() {
    _apiClient.close();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final value = await _apiClient.get<PublicDiaryShare>(
        '/public/diary-shares/${Uri.encodeComponent(widget.token)}',
        decodeData: (json) => _publicDiaryShare(
          resolvePublicMediaUrlsInJson(json, resolver: _mediaResolver),
        ),
      );
      if (!mounted) return;
      setState(() => _share = value);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final share = _share;
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.transparent,
        title: Text(_english ? 'Shared diary' : '分享日记'),
      ),
      body: _loading && share == null
          ? const Center(child: CircularProgressIndicator())
          : _error != null && share == null
              ? _ErrorState(
                  english: _english,
                  onRetry: _load,
                )
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 28),
                    children: [
                      _ShareHeader(share: share!, english: _english),
                      const SizedBox(height: 16),
                      if (share.hasAssociations) ...[
                        _AssociationChips(share: share, english: _english),
                        const SizedBox(height: 16),
                      ],
                      if (share.hasAnyImages)
                        DiaryMediaGrid(
                          images: share.images,
                          beforeImages: share.beforeImages,
                          afterImages: share.afterImages,
                          height: 280,
                          borderRadius: BorderRadius.circular(12),
                          enableViewer: true,
                        ),
                      if (share.hasAnyImages) const SizedBox(height: 16),
                      _SectionCard(
                        title: _english ? 'Content' : '正文',
                        child: SelectableText(
                          share.content.trim().isEmpty
                              ? (_english ? 'No content' : '暂无正文')
                              : share.content.trim(),
                          style: Theme.of(context).textTheme.bodyLarge,
                        ),
                      ),
                    ],
                  ),
                ),
    );
  }
}

final class PublicDiaryShare {
  const PublicDiaryShare({
    required this.diaryId,
    required this.title,
    required this.content,
    required this.author,
    required this.publishDate,
    required this.images,
    required this.beforeImages,
    required this.afterImages,
    required this.tags,
    required this.rating,
    required this.likeCount,
    required this.commentCount,
    required this.project,
    required this.doctor,
    required this.institution,
  });

  final String diaryId;
  final String title;
  final String content;
  final PublicDiaryAuthor author;
  final DateTime publishDate;
  final List<String> images;
  final List<String> beforeImages;
  final List<String> afterImages;
  final List<String> tags;
  final int rating;
  final int likeCount;
  final int commentCount;
  final PublicDiaryAssociation? project;
  final PublicDiaryAssociation? doctor;
  final PublicDiaryAssociation? institution;

  bool get hasAnyImages =>
      images.isNotEmpty || beforeImages.isNotEmpty || afterImages.isNotEmpty;

  bool get hasAssociations =>
      project != null || doctor != null || institution != null;
}

final class PublicDiaryAuthor {
  const PublicDiaryAuthor({
    required this.id,
    required this.name,
    required this.avatar,
  });

  final String id;
  final String name;
  final String avatar;
}

final class PublicDiaryAssociation {
  const PublicDiaryAssociation({required this.id, required this.name});

  final String id;
  final String name;
}

PublicDiaryShare _publicDiaryShare(Object? json) {
  final map = _map(json, '公开分享日记');
  return PublicDiaryShare(
    diaryId: _requiredString(map['diaryId'], 'diaryId'),
    title: _requiredString(map['title'], 'title'),
    content: _string(map['content']),
    author: (() {
      final authorMap = _map(map['author'], 'author');
      return PublicDiaryAuthor(
        id: _requiredString(authorMap['id'], 'author.id'),
        name: _string(authorMap['name'], fallback: '用户'),
        avatar: _string(authorMap['avatar']),
      );
    })(),
    publishDate: _date(map['publishDate']) ?? DateTime.now(),
    images: _stringList(map['images']),
    beforeImages: _stringList(map['beforeImages']),
    afterImages: _stringList(map['afterImages']),
    tags: _csv(map['tags']),
    rating: _integer(map['rating']),
    likeCount: _integer(map['likeCount']),
    commentCount: _integer(map['commentCount']),
    project: _association(map['project']),
    doctor: _association(map['doctor']),
    institution: _association(map['institution']),
  );
}

PublicDiaryAssociation? _association(Object? json) {
  if (json == null) return null;
  final map = _map(json, '关联');
  return PublicDiaryAssociation(
    id: _requiredString(map['id'], 'id'),
    name: _string(map['name']),
  );
}

Map<String, Object?> _map(Object? json, String label) {
  if (json is Map<String, Object?>) return json;
  if (json is Map) {
    return json.map((key, value) => MapEntry(key.toString(), value));
  }
  throw FormatException('$label 不是 JSON 对象');
}

String _requiredString(Object? value, String field) {
  final result = value?.toString().trim() ?? '';
  if (result.isEmpty) {
    throw FormatException('$field 不能为空');
  }
  return result;
}

String _string(Object? value, {String fallback = ''}) {
  final result = value?.toString().trim() ?? '';
  return result.isEmpty ? fallback : result;
}

int _integer(Object? value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}

DateTime? _date(Object? value) {
  final raw = value?.toString().trim();
  if (raw == null || raw.isEmpty) return null;
  return DateTime.tryParse(raw);
}

List<String> _stringList(Object? value) {
  if (value == null) return const [];
  if (value is List) {
    return List.unmodifiable(
      value
          .map((item) => item?.toString().trim() ?? '')
          .where((item) => item.isNotEmpty),
    );
  }
  final raw = value.toString().trim();
  if (raw.isEmpty) return const [];
  return List.unmodifiable(
    raw.split(',').map((item) => item.trim()).where((item) => item.isNotEmpty),
  );
}

List<String> _csv(Object? value) => _stringList(value);

final class _ShareHeader extends StatelessWidget {
  const _ShareHeader({
    required this.share,
    required this.english,
  });

  final PublicDiaryShare share;
  final bool english;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final author = share.author;
    final initials = author.name.isEmpty ? '?' : author.name.characters.first;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: colors.surfaceContainerHighest.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              CircleAvatar(
                radius: 24,
                foregroundImage:
                    author.avatar.isEmpty ? null : NetworkImage(author.avatar),
                child: Text(initials),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      share.title,
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      english ? 'By ${author.name}' : '作者：${author.name}',
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _formatDate(share.publishDate),
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _StatChip(
                  label: english ? 'Likes' : '点赞', value: share.likeCount),
              _StatChip(
                  label: english ? 'Comments' : '评论',
                  value: share.commentCount),
              _StatChip(label: english ? 'Rating' : '评分', value: share.rating),
            ],
          ),
          if (share.tags.isNotEmpty) ...[
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final tag in share.tags)
                  Chip(
                    label: Text('#$tag'),
                    visualDensity: VisualDensity.compact,
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

final class _AssociationChips extends StatelessWidget {
  const _AssociationChips({
    required this.share,
    required this.english,
  });

  final PublicDiaryShare share;
  final bool english;

  @override
  Widget build(BuildContext context) {
    final chips = <Widget>[
      if (share.project != null)
        Chip(
          label: Text(english
              ? 'Project: ${share.project!.name}'
              : '项目：${share.project!.name}'),
          visualDensity: VisualDensity.compact,
        ),
      if (share.doctor != null)
        Chip(
          label: Text(english
              ? 'Doctor: ${share.doctor!.name}'
              : '医生：${share.doctor!.name}'),
          visualDensity: VisualDensity.compact,
        ),
      if (share.institution != null)
        Chip(
          label: Text(english
              ? 'Institution: ${share.institution!.name}'
              : '机构：${share.institution!.name}'),
          visualDensity: VisualDensity.compact,
        ),
    ];
    return Wrap(spacing: 8, runSpacing: 8, children: chips);
  }
}

final class _SectionCard extends StatelessWidget {
  const _SectionCard({
    required this.title,
    required this.child,
  });

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          child,
        ],
      ),
    );
  }
}

final class _StatChip extends StatelessWidget {
  const _StatChip({required this.label, required this.value});

  final String label;
  final int value;

  @override
  Widget build(BuildContext context) {
    return Chip(
      label: Text('$label $value'),
      visualDensity: VisualDensity.compact,
    );
  }
}

final class _ErrorState extends StatelessWidget {
  const _ErrorState({
    required this.english,
    required this.onRetry,
  });

  final bool english;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.cloud_off_outlined,
                size: 48, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 12),
            Text(
              english ? 'Unable to load the diary' : '无法加载分享内容',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Text(
              english
                  ? 'Please check the share link and try again.'
                  : '请检查分享链接后重试。',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => onRetry(),
              child: Text(english ? 'Retry' : '重试'),
            ),
          ],
        ),
      ),
    );
  }
}

String _formatDate(DateTime value) {
  final y = value.year.toString().padLeft(4, '0');
  final m = value.month.toString().padLeft(2, '0');
  final d = value.day.toString().padLeft(2, '0');
  return '$y-$m-$d';
}
