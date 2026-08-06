import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';
import 'package:joysong_flutter/features/social/presentation/diary_media_grid.dart';

class CatalogProjectDetailView extends StatelessWidget {
  const CatalogProjectDetailView({
    required this.item,
    this.onBook,
    this.onInstitutionProjectTap,
    this.onInstitutionTap,
    this.onDiaryTap,
    this.onDoctorTap,
    this.onViewAllInstitutions,
    this.onViewAllDiaries,
    super.key,
  });

  final DiscoverItem item;
  final ValueChanged<DiscoverItem>? onBook;
  final void Function(String institutionId, String projectId)?
      onInstitutionProjectTap;
  final ValueChanged<String>? onInstitutionTap;
  final ValueChanged<String>? onDiaryTap;
  final ValueChanged<String>? onDoctorTap;
  final VoidCallback? onViewAllInstitutions;
  final VoidCallback? onViewAllDiaries;

  @override
  Widget build(BuildContext context) {
    final raw = item.raw;
    final project = _map(raw['project']);
    final ip = _map(raw['institutionProject']);
    final institution = _map(raw['institution']);
    final institutionProjects = _maps(raw['institutionProjects']);
    final diaries = _maps(raw['diaries']);
    final doctors = _maps(raw['doctors']);
    final isInstitutionProject = ip.isNotEmpty;
    final sources = [ip, project, raw];
    final name = _text(sources, const ['name', 'projectName'], item.title);
    final slogan = _text(sources, const ['slogan'], '');
    final description =
        _text(sources, const ['description', 'summary'], item.subtitle);
    final detailContent =
        _text(sources, const ['detailContent', 'content'], '');
    final price = _number(sources, const ['price', 'referencePrice']);
    final originalPrice = _number(sources, const ['originalPrice']);
    final tags = <String>{
      ..._tokens(sources, const ['categoryTags']),
      ..._tokens(sources, const ['tags']),
    }.toList(growable: false);
    final images = <String>{
      ..._tokens(sources, const ['images']),
      ..._tokens(sources, const ['coverImage']),
      if (item.imageUrl.isNotEmpty) item.imageUrl,
    }.toList(growable: false);
    final keys = List.generate(4, (_) => GlobalKey());
    void jump(int index) {
      final target = keys[index].currentContext;
      if (target == null) return;
      Scrollable.ensureVisible(target,
          duration: const Duration(milliseconds: 320),
          curve: Curves.easeOut,
          alignment: .04);
    }

    final labels = isInstitutionProject
        ? (context.isEnglish
            ? const ['Guide', 'Doctors', 'Diaries', 'Institution']
            : const ['项目百科', '可预约医生', '用户日记', '所属机构'])
        : (context.isEnglish
            ? const ['Guide', 'Institutions', 'Diaries']
            : const ['项目攻略', '认证机构', '用户日记']);

    return Column(children: [
      Expanded(
        child: CustomScrollView(slivers: [
          SliverToBoxAdapter(
            child: CatalogHero(
              images: images,
              title: name,
              eyebrow: slogan,
              subtitle: isInstitutionProject
                  ? _text([institution], const ['name'], '')
                  : context.localized('参考均价', 'Reference price'),
              price: price,
              originalPrice: originalPrice,
            ),
          ),
          SliverPersistentHeader(
            pinned: true,
            delegate: _ProjectNavDelegate(labels: labels, onTap: jump),
          ),
          SliverToBoxAdapter(
            child: CatalogSection(
              key: keys[0],
              title: context.localized(
                  isInstitutionProject ? '项目百科' : '百科攻略', 'Project guide'),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (tags.isNotEmpty) ...[
                    Wrap(
                      spacing: 7,
                      runSpacing: 7,
                      children: [for (final tag in tags) _TinyTag(tag)],
                    ),
                    const SizedBox(height: 12),
                  ],
                  Text(
                    description.isEmpty
                        ? context.localized('暂无项目介绍', 'No project overview')
                        : description,
                    style:
                        const TextStyle(height: 1.55, color: Color(0xff666666)),
                  ),
                  if (detailContent.isNotEmpty)
                    Center(
                      child: TextButton(
                        onPressed: () => _showDetails(
                          context,
                          name,
                          detailContent,
                        ),
                        child: Text(context.localized(
                            '查看项目更多信息', 'View more information')),
                      ),
                    ),
                ],
              ),
            ),
          ),
          SliverToBoxAdapter(
            child: CatalogSection(
              key: keys[1],
              title: isInstitutionProject
                  ? context.localized('可预约医生', 'Bookable doctors')
                  : context.localized('认证机构', 'Verified institutions'),
              trailing: TextButton(
                onPressed: isInstitutionProject
                    ? null
                    : institutionProjects.isEmpty
                        ? null
                        : onViewAllInstitutions,
                child: Text(context.isEnglish
                    ? 'All (${isInstitutionProject ? doctors.length : institutionProjects.length})'
                    : '全部 (${isInstitutionProject ? doctors.length : institutionProjects.length})'),
              ),
              child: isInstitutionProject
                  ? doctors.isEmpty
                      ? _Empty(context.localized('医生排班信息暂未开放',
                          'Doctor schedules are not available yet'))
                      : Column(
                          children: [
                            for (final doctor in doctors.take(5))
                              _DoctorRow(doctor, onDoctorTap),
                          ],
                        )
                  : institutionProjects.isEmpty
                      ? _Empty(context.localized(
                          '暂无可预约机构', 'No institutions available'))
                      : Column(
                          children: [
                            for (final entry in institutionProjects.take(5))
                              _InstitutionProjectRow(
                                entry,
                                item.id,
                                onInstitutionProjectTap,
                              ),
                          ],
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
          if (isInstitutionProject)
            SliverToBoxAdapter(
              child: CatalogSection(
                key: keys[3],
                title: context.localized('所属机构', 'Institution'),
                child: _InstitutionRow(institution, onInstitutionTap),
              ),
            ),
          const SliverToBoxAdapter(child: SizedBox(height: 18)),
        ]),
      ),
      CatalogBottomBar(
        primaryLabel: isInstitutionProject
            ? context.localized('预约项目', 'Book project')
            : context.localized('查看可预约机构', 'View institutions'),
        onPrimary: isInstitutionProject
            ? (onBook == null ? null : () => onBook!(item))
            : () => jump(1),
        secondaryLabel: isInstitutionProject
            ? context.localized('咨询机构', 'Consult institution')
            : null,
        onSecondary: isInstitutionProject
            ? () {
                final id = _text([institution], const ['id'], '');
                if (id.isNotEmpty) onInstitutionTap?.call(id);
              }
            : null,
        tertiaryLabel: context.localized(
            isInstitutionProject ? '与AI聊聊' : '与AI聊此项目', 'Chat with AI'),
        onTertiary: () => _aiHint(context),
      ),
    ]);
  }

  void _showDetails(BuildContext context, String name, String content) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => FractionallySizedBox(
        heightFactor: .82,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
          children: [
            Text(name,
                style: Theme.of(context)
                    .textTheme
                    .titleLarge
                    ?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 16),
            RichContentView(
              content: content,
              textStyle: const TextStyle(height: 1.65),
            ),
          ],
        ),
      ),
    );
  }

  void _aiHint(BuildContext context) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(context.localized(
            '可前往 AI 页继续咨询', 'Continue in the AI assistant tab')),
      ));
}

class _ProjectNavDelegate extends SliverPersistentHeaderDelegate {
  _ProjectNavDelegate({required this.labels, required this.onTap});
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
  bool shouldRebuild(covariant _ProjectNavDelegate oldDelegate) => true;
}

class _TinyTag extends StatelessWidget {
  const _TinyTag(this.label);
  final String label;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
        decoration: BoxDecoration(
          color: const Color(0xffeeeeee),
          borderRadius: BorderRadius.circular(5),
        ),
        child: Text(label, style: const TextStyle(fontSize: 12)),
      );
}

class _InstitutionProjectRow extends StatelessWidget {
  const _InstitutionProjectRow(this.data, this.projectId, this.onTap);
  final Map<String, Object?> data;
  final String projectId;
  final void Function(String institutionId, String projectId)? onTap;
  @override
  Widget build(BuildContext context) {
    final institution = _map(data['institution']);
    final ip = _map(data['institutionProject']);
    final id =
        _text([data, institution, ip], const ['institutionId', 'id'], '');
    return _InstitutionRow(
      {...institution, ...data},
      id.isEmpty || onTap == null ? null : (_) => onTap!(id, projectId),
      title: _text([data, institution], const ['institutionName', 'name'], ''),
    );
  }
}

class _InstitutionRow extends StatelessWidget {
  const _InstitutionRow(this.data, this.onTap, {this.title});
  final Map<String, Object?> data;
  final ValueChanged<String>? onTap;
  final String? title;
  @override
  Widget build(BuildContext context) {
    final id = _text([data], const ['id', 'institutionId'], '');
    final rating = _number([data], const ['rating']);
    return InkWell(
      onTap: id.isEmpty || onTap == null ? null : () => onTap!(id),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          CatalogImage(
            url: _text([data], const ['coverImage', 'logo'], ''),
            width: 86,
            height: 86,
            icon: Icons.apartment_outlined,
          ),
          const SizedBox(width: 12),
          Expanded(
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title ?? _text([data], const ['name'], ''),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      fontSize: 16, fontWeight: FontWeight.w800)),
              const SizedBox(height: 5),
              Text(_text([data], const ['address', 'city'], ''),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: Color(0xff777777))),
              if (rating != null && rating > 0) ...[
                const SizedBox(height: 6),
                Text('★ ${rating.toStringAsFixed(1)}'),
              ],
            ]),
          ),
          Text(context.localized('收藏', 'Save'),
              style: Theme.of(context).textTheme.bodySmall),
        ]),
      ),
    );
  }
}

class _DoctorRow extends StatelessWidget {
  const _DoctorRow(this.data, this.onTap);
  final Map<String, Object?> data;
  final ValueChanged<String>? onTap;
  @override
  Widget build(BuildContext context) {
    final id = _text([data], const ['id', 'doctorId'], '');
    final avatar = _text([data], const ['avatar'], '');
    return ListTile(
      onTap: id.isEmpty || onTap == null ? null : () => onTap!(id),
      leading: CircleAvatar(
        foregroundImage: avatar.isEmpty ? null : NetworkImage(avatar),
        child: avatar.isEmpty ? const Icon(Icons.person_outline) : null,
      ),
      title: Text(_text([data], const ['name'], '')),
      subtitle: Text(_text([data], const ['title'], '')),
    );
  }
}

class _DiaryCard extends StatelessWidget {
  const _DiaryCard(this.data, this.onTap);
  final Map<String, Object?> data;
  final ValueChanged<String>? onTap;
  @override
  Widget build(BuildContext context) {
    final id = _text([data], const ['id', 'diaryId'], '');
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
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(
                  _text([data], const ['authorName'],
                      context.localized('用户', 'Patient')),
                  style: const TextStyle(fontWeight: FontWeight.w700)),
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
              Text(_text([data], const ['title', 'content'], ''),
                  maxLines: 2, overflow: TextOverflow.ellipsis),
            ]),
          ),
        ),
      ),
    );
  }
}

class _DiaryImage extends StatelessWidget {
  const _DiaryImage(this.label, this.url);
  final String label;
  final String url;
  @override
  Widget build(BuildContext context) => Stack(fit: StackFit.expand, children: [
        CatalogImage(url: url, width: 120, height: 120, radius: 7),
        Positioned(
            left: 7,
            top: 6,
            child: Text(label,
                style:
                    const TextStyle(fontSize: 11, color: Color(0xff777777)))),
      ]);
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
String _text(
    List<Map<String, Object?>> sources, List<String> keys, String fallback) {
  for (final source in sources) {
    for (final key in keys) {
      final text = source[key]?.toString().trim() ?? '';
      if (text.isNotEmpty && text.toLowerCase() != 'null') return text;
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

Iterable<String> _tokens(
    List<Map<String, Object?>> sources, List<String> keys) sync* {
  for (final source in sources) {
    for (final key in keys) {
      yield* _split(source[key]);
    }
  }
}

List<String> _split(Object? value) {
  final values = value is List ? value : (value?.toString() ?? '').split(',');
  return values
      .map((value) => value.toString().trim())
      .where((value) => value.isNotEmpty)
      .toList(growable: false);
}
