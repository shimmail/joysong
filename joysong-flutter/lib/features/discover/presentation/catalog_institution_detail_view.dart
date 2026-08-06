import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/presentation/report_action_button.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';
import 'package:joysong_flutter/features/social/presentation/diary_media_grid.dart';

class CatalogInstitutionDetailView extends StatelessWidget {
  const CatalogInstitutionDetailView({
    required this.item,
    this.onProjectTap,
    this.onDoctorTap,
    this.onDiaryTap,
    this.onViewAllProjects,
    this.onViewAllDiaries,
    this.onViewAllReviews,
    this.onViewAllDoctors,
    this.socialController,
    super.key,
  });

  final DiscoverItem item;
  final void Function(String institutionId, String projectId)? onProjectTap;
  final ValueChanged<String>? onDoctorTap;
  final ValueChanged<String>? onDiaryTap;
  final VoidCallback? onViewAllProjects;
  final VoidCallback? onViewAllDiaries;
  final VoidCallback? onViewAllReviews;
  final VoidCallback? onViewAllDoctors;
  final SocialController? socialController;

  @override
  Widget build(BuildContext context) {
    final nested = _map(item.raw['institution']);
    final data = {...nested, ...item.raw};
    final institutionId = _text(data, const ['id', 'institutionId']);
    final projects = _maps(item.raw['projects']);
    final diaries = _maps(item.raw['diaries']);
    final reviews = _maps(item.raw['reviews']);
    final doctors = _maps(item.raw['doctors']);
    final images = <String>{
      ..._split(data['images']),
      ..._split(data['coverImage']),
      if (item.imageUrl.isNotEmpty) item.imageUrl,
    }.toList(growable: false);
    final name = _text(data, const ['name'], fallback: item.title);
    final address = [
      _text(data, const ['city']),
      _text(data, const ['address']),
    ].where((value) => value.isNotEmpty).join(' · ');
    final keys = List.generate(5, (_) => GlobalKey());
    void jump(int index) {
      final target = keys[index].currentContext;
      if (target == null) return;
      Scrollable.ensureVisible(target,
          duration: const Duration(milliseconds: 320),
          curve: Curves.easeOut,
          alignment: .04);
    }

    final labels = context.isEnglish
        ? const ['Credentials', 'Projects', 'Diaries', 'Reviews', 'Doctors']
        : const ['资质保险箱', '可预约项目', '用户日记', '用户评价', '医生团队'];

    return Column(children: [
      Expanded(
        child: CustomScrollView(slivers: [
          SliverToBoxAdapter(
            child: CatalogHero(
              images: images,
              title: name,
              eyebrow: _bool(data['isVerified'])
                  ? context.localized('安颜认证', 'Verified institution')
                  : '',
              subtitle: address,
            ),
          ),
          SliverToBoxAdapter(child: _FeatureCards(context)),
          SliverPersistentHeader(
            pinned: true,
            delegate: _InstitutionNavDelegate(labels: labels, onTap: jump),
          ),
          SliverToBoxAdapter(
            child: CatalogSection(
              key: keys[0],
              title: context.localized('资质保险箱', 'Credentials vault'),
              trailing: Text(context.localized('查资质', 'Verify')),
              child: _Credentials(data),
            ),
          ),
          SliverToBoxAdapter(
            child: CatalogSection(
              key: keys[1],
              title: context.localized('可预约项目', 'Bookable projects'),
              trailing: TextButton(
                onPressed: projects.isEmpty ? null : onViewAllProjects,
                child: Text(context.isEnglish
                    ? 'All (${projects.length})'
                    : '全部 (${projects.length})'),
              ),
              child: projects.isEmpty
                  ? _Empty(context.localized('暂无可预约项目', 'No bookable projects'))
                  : _FilterableProjectList(
                      projects: projects,
                      institutionId: institutionId,
                      onTap: onProjectTap,
                    ),
            ),
          ),
          SliverToBoxAdapter(
            child: CatalogSection(
              key: keys[2],
              title: context.localized('用户日记', 'Patient diaries'),
              trailing: TextButton(
                onPressed: diaries.isEmpty ? null : onViewAllDiaries,
                child: Text(context.isEnglish
                    ? 'All (${diaries.length})'
                    : '全部 (${diaries.length})'),
              ),
              child: diaries.isEmpty
                  ? _Empty(context.localized('暂无用户日记', 'No patient diaries'))
                  : SizedBox(
                      height: 220,
                      child: ListView.separated(
                        scrollDirection: Axis.horizontal,
                        itemCount: diaries.length > 5 ? 5 : diaries.length,
                        separatorBuilder: (_, __) => const SizedBox(width: 10),
                        itemBuilder: (_, index) =>
                            _DiaryCard(diaries[index], onDiaryTap),
                      ),
                    ),
            ),
          ),
          SliverToBoxAdapter(
            child: CatalogSection(
              key: keys[3],
              title: context.localized('用户评价', 'Patient reviews'),
              trailing: TextButton(
                onPressed: reviews.isEmpty ? null : onViewAllReviews,
                child: Text(context.isEnglish
                    ? 'All (${reviews.length})'
                    : '全部 (${reviews.length})'),
              ),
              child: reviews.isEmpty
                  ? _Empty(context.localized('暂无用户评价', 'No reviews'))
                  : SizedBox(
                      height: 180,
                      child: ListView.separated(
                        scrollDirection: Axis.horizontal,
                        itemCount: reviews.length > 5 ? 5 : reviews.length,
                        separatorBuilder: (_, __) => const SizedBox(width: 10),
                        itemBuilder: (_, index) => _ReviewCard(
                          reviews[index],
                          socialController,
                        ),
                      ),
                    ),
            ),
          ),
          SliverToBoxAdapter(
            child: CatalogSection(
              key: keys[4],
              title: context.localized('核心医生团队', 'Core medical team'),
              trailing: TextButton(
                onPressed: doctors.isEmpty ? null : onViewAllDoctors,
                child: Text(context.isEnglish
                    ? 'All (${doctors.length})'
                    : '全部 (${doctors.length})'),
              ),
              child: doctors.isEmpty
                  ? _Empty(context.localized('暂无医生信息', 'No doctors'))
                  : Column(
                      children: [
                        for (final doctor in doctors.take(5))
                          _DoctorRow(doctor, onDoctorTap),
                      ],
                    ),
            ),
          ),
          const SliverToBoxAdapter(child: SizedBox(height: 18)),
        ]),
      ),
      CatalogBottomBar(
        primaryLabel: context.localized('咨询机构', 'Consult institution'),
        onPrimary: () => ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(context.localized(
              '可从消息页联系机构', 'Open Messages to contact this institution')),
        )),
        secondaryLabel: context.localized('与AI聊聊', 'Chat with AI'),
        onSecondary: () => ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(context.localized(
              '可前往 AI 页继续咨询', 'Continue in the AI assistant tab')),
        )),
      ),
    ]);
  }
}

class _InstitutionNavDelegate extends SliverPersistentHeaderDelegate {
  _InstitutionNavDelegate({required this.labels, required this.onTap});
  final List<String> labels;
  final ValueChanged<int> onTap;
  @override
  double get minExtent => 54;
  @override
  double get maxExtent => 54;
  @override
  Widget build(
          BuildContext context, double shrinkOffset, bool overlapsContent) =>
      DetailAnchorBar(labels: labels, onTap: onTap);
  @override
  bool shouldRebuild(covariant _InstitutionNavDelegate oldDelegate) => true;
}

class _FeatureCards extends StatelessWidget {
  const _FeatureCards(this.rootContext);
  final BuildContext rootContext;
  @override
  Widget build(BuildContext context) => Container(
        color: Colors.white,
        padding: const EdgeInsets.all(14),
        child: Row(children: [
          Expanded(
              child: _Feature(
                  Icons.medical_services_outlined,
                  rootContext.localized('专业医生', 'Expert doctors'),
                  rootContext.localized('经验丰富', 'Experienced'))),
          const SizedBox(width: 8),
          Expanded(
              child: _Feature(
                  Icons.sentiment_satisfied_alt_outlined,
                  rootContext.localized('先进设备', 'Advanced equipment'),
                  rootContext.localized('国际标准', 'International standards'))),
        ]),
      );
}

class _Feature extends StatelessWidget {
  const _Feature(this.icon, this.title, this.subtitle);
  final IconData icon;
  final String title;
  final String subtitle;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: const Color(0xfff4f4f4),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(children: [
          Icon(icon, size: 23),
          const SizedBox(width: 9),
          Expanded(
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                Text(title,
                    style: const TextStyle(fontWeight: FontWeight.w700)),
                Text(subtitle,
                    style:
                        const TextStyle(fontSize: 11, color: Color(0xff888888)))
              ])),
        ]),
      );
}

class _Credentials extends StatelessWidget {
  const _Credentials(this.data);
  final Map<String, Object?> data;
  @override
  Widget build(BuildContext context) {
    final text = _text(data, const ['credentials', 'description']);
    final images = _split(data['credentialImages']);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(
          text.isEmpty
              ? context.localized('资质信息正在完善中', 'Credentials are being updated')
              : text,
          maxLines: 6,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(height: 1.5, color: Color(0xff666666))),
      if (images.isNotEmpty) ...[
        const SizedBox(height: 12),
        SizedBox(
            height: 90,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: images.length,
              separatorBuilder: (_, __) => const SizedBox(width: 8),
              itemBuilder: (_, index) =>
                  CatalogImage(url: images[index], width: 90, height: 90),
            )),
      ],
    ]);
  }
}

class _FilterChip extends StatelessWidget {
  const _FilterChip(
    this.label, {
    required this.selected,
    required this.onTap,
  });
  final String label;
  final bool selected;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => ChoiceChip(
        label: Text(label),
        selected: selected,
        onSelected: (_) => onTap(),
        visualDensity: VisualDensity.compact,
      );
}

class _FilterableProjectList extends StatefulWidget {
  const _FilterableProjectList({
    required this.projects,
    required this.institutionId,
    required this.onTap,
  });

  final List<Map<String, Object?>> projects;
  final String institutionId;
  final void Function(String institutionId, String projectId)? onTap;

  @override
  State<_FilterableProjectList> createState() => _FilterableProjectListState();
}

class _FilterableProjectListState extends State<_FilterableProjectList> {
  String _selectedTag = '';

  @override
  Widget build(BuildContext context) {
    final tags = _allProjectTags(widget.projects);
    final projects = _selectedTag.isEmpty
        ? widget.projects
        : widget.projects
            .where((project) => _projectTags(project).contains(_selectedTag))
            .toList(growable: false);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (tags.isNotEmpty) ...[
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(children: [
              _FilterChip(
                context.localized('全部', 'All'),
                selected: _selectedTag.isEmpty,
                onTap: () => setState(() => _selectedTag = ''),
              ),
              for (final tag in tags) ...[
                const SizedBox(width: 8),
                _FilterChip(
                  tag,
                  selected: _selectedTag == tag,
                  onTap: () => setState(() => _selectedTag = tag),
                ),
              ],
            ]),
          ),
          const SizedBox(height: 12),
        ],
        for (final project in projects.take(5))
          _ProjectRow(
            project,
            widget.institutionId,
            widget.onTap,
          ),
      ],
    );
  }
}

class _ProjectRow extends StatelessWidget {
  const _ProjectRow(this.data, this.institutionId, this.onTap);
  final Map<String, Object?> data;
  final String institutionId;
  final void Function(String institutionId, String projectId)? onTap;
  @override
  Widget build(BuildContext context) {
    final id = _text(data, const ['projectId', 'id']);
    final price = _number(data['price']);
    final original = _number(data['originalPrice']);
    final tags = _projectTags(data);
    return InkWell(
      onTap:
          id.isEmpty || onTap == null ? null : () => onTap!(institutionId, id),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          CatalogImage(
              url: _text(data, const ['coverImage']), width: 80, height: 80),
          const SizedBox(width: 12),
          Expanded(
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                Text(_text(data, const ['projectName', 'name']),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w800)),
                const SizedBox(height: 5),
                Text(_text(data, const ['description']),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Color(0xff888888))),
                if (tags.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 5,
                    runSpacing: 4,
                    children: tags
                        .take(3)
                        .map((tag) => _ProjectTag(tag))
                        .toList(growable: false),
                  ),
                ],
                if (price > 0) ...[
                  const SizedBox(height: 6),
                  Row(children: [
                    Text('¥${catalogMoney(price)}',
                        style: const TextStyle(fontWeight: FontWeight.w700)),
                    if (original > price) ...[
                      const SizedBox(width: 6),
                      Text('¥${catalogMoney(original)}',
                          style: const TextStyle(
                              color: Color(0xff999999),
                              decoration: TextDecoration.lineThrough))
                    ]
                  ])
                ],
              ])),
          Text(context.localized('收藏', 'Save'),
              style: Theme.of(context).textTheme.bodySmall),
        ]),
      ),
    );
  }
}

class _ProjectTag extends StatelessWidget {
  const _ProjectTag(this.label);
  final String label;

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.primaryContainer,
          borderRadius: BorderRadius.circular(5),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
          child: Text(
            label,
            style: TextStyle(
              fontSize: 10,
              color: Theme.of(context).colorScheme.onPrimaryContainer,
            ),
          ),
        ),
      );
}

class _DiaryCard extends StatelessWidget {
  const _DiaryCard(this.data, this.onTap);
  final Map<String, Object?> data;
  final ValueChanged<String>? onTap;
  @override
  Widget build(BuildContext context) {
    final id = _text(data, const ['id', 'diaryId']);
    final images = _split(data['imageUrls'] ?? data['images']);
    final before = _split(data['beforeImageUrls'] ?? data['beforeImages']);
    final after = _split(data['afterImageUrls'] ?? data['afterImages']);
    return SizedBox(
        width: 280,
        child: Card(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: id.isEmpty || onTap == null ? null : () => onTap!(id),
              child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                            _text(data, const ['authorName'],
                                fallback: context.localized('用户', 'Patient')),
                            style:
                                const TextStyle(fontWeight: FontWeight.w700)),
                        const SizedBox(height: 9),
                        Expanded(
                          child: DiaryMediaGrid(
                            images: images,
                            beforeImages: before,
                            afterImages: after,
                            borderRadius: BorderRadius.circular(7),
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(_text(data, const ['title', 'content']),
                            maxLines: 2, overflow: TextOverflow.ellipsis),
                      ])),
            )));
  }
}

class _ReviewCard extends StatelessWidget {
  const _ReviewCard(this.data, this.socialController);
  final Map<String, Object?> data;
  final SocialController? socialController;
  @override
  Widget build(BuildContext context) {
    final reviewId = _text(data, const ['id', 'reviewId']);
    return SizedBox(
        width: 270,
        child: Card(
          margin: EdgeInsets.zero,
          child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(children: [
                      Expanded(
                        child: Text(
                          _text(
                            data,
                            const ['projectName'],
                            fallback:
                                context.localized('服务评价', 'Service review'),
                          ),
                          style: const TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ),
                      if (socialController != null && reviewId.isNotEmpty)
                        ReportActionButton(
                          key: Key('review-report-$reviewId'),
                          controller: socialController!,
                          type: ReportTargetType.review,
                          targetId: reviewId,
                          compact: true,
                          iconSize: 19,
                        ),
                    ]),
                    const SizedBox(height: 8),
                    Text(_text(data, const ['content']),
                        maxLines: 4,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            height: 1.45, color: Color(0xff666666))),
                    const Spacer(),
                    Row(children: [
                      Expanded(
                          child: Text(
                              _text(data, const ['userName'],
                                  fallback:
                                      context.localized('匿名用户', 'Anonymous')),
                              style: const TextStyle(
                                  fontWeight: FontWeight.w700))),
                      Text(_text(data, const ['createdAt']),
                          style: Theme.of(context).textTheme.bodySmall)
                    ]),
                  ])),
        ));
  }
}

class _DoctorRow extends StatelessWidget {
  const _DoctorRow(this.data, this.onTap);
  final Map<String, Object?> data;
  final ValueChanged<String>? onTap;
  @override
  Widget build(BuildContext context) {
    final id = _text(data, const ['id', 'doctorId']);
    final avatar = _text(data, const ['avatar']);
    return ListTile(
        contentPadding: const EdgeInsets.symmetric(vertical: 6),
        onTap: id.isEmpty || onTap == null ? null : () => onTap!(id),
        leading: CircleAvatar(
            radius: 30,
            foregroundImage: avatar.isEmpty ? null : NetworkImage(avatar),
            child: avatar.isEmpty ? const Icon(Icons.person_outline) : null),
        title: Text(_text(data, const ['name']),
            style: const TextStyle(fontWeight: FontWeight.w800)),
        subtitle: Text(_text(data, const ['title', 'bio']),
            maxLines: 2, overflow: TextOverflow.ellipsis),
        trailing: Text(context.localized('收藏', 'Save'),
            style: Theme.of(context).textTheme.bodySmall));
  }
}

class _Empty extends StatelessWidget {
  const _Empty(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => SizedBox(
      height: 110,
      child: Center(
          child: Text(text, style: const TextStyle(color: Color(0xff999999)))));
}

Map<String, Object?> _map(Object? value) => value is Map
    ? value.map((key, value) => MapEntry(key.toString(), value))
    : const {};
List<Map<String, Object?>> _maps(Object? value) => value is List
    ? value.whereType<Map>().map(_map).toList(growable: false)
    : const [];
String _text(Map<String, Object?> data, List<String> keys,
    {String fallback = ''}) {
  for (final key in keys) {
    final text = data[key]?.toString().trim() ?? '';
    if (text.isNotEmpty && text.toLowerCase() != 'null') return text;
  }
  return fallback;
}

List<String> _split(Object? value) {
  final values = value is List ? value : (value?.toString() ?? '').split(',');
  return values
      .map((value) => value.toString().trim())
      .where((value) => value.isNotEmpty)
      .toList(growable: false);
}

List<String> _projectTags(Map<String, Object?> project) {
  final tags = <String>{};
  final nested = _map(project['project']);
  if (nested.isNotEmpty) tags.addAll(_projectTags(nested));
  for (final key in const [
    'tags',
    'projectTags',
    'tagNames',
    'categories',
    'category',
  ]) {
    final value = project[key];
    if (value is List) {
      for (final item in value) {
        if (item is Map) {
          final tag = _text(
            _map(item),
            const ['name', 'label', 'tagName', 'categoryName'],
          );
          if (tag.isNotEmpty) tags.add(tag);
        } else {
          tags.addAll(_split(item));
        }
      }
    } else {
      tags.addAll(_split(value));
    }
  }
  return tags.where((tag) => tag.isNotEmpty).toList(growable: false);
}

List<String> _allProjectTags(List<Map<String, Object?>> projects) {
  final tags = <String>{};
  for (final project in projects) {
    tags.addAll(_projectTags(project));
  }
  return tags.toList(growable: false);
}

double _number(Object? value) =>
    value is num ? value.toDouble() : double.tryParse('$value') ?? 0;
bool _bool(Object? value) =>
    value == true || value == 1 || '$value'.toLowerCase() == 'true';
