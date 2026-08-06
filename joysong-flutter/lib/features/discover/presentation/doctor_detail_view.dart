import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_content_card.dart';

/// Doctor-specific body for [DiscoverItem] details.
///
/// The discover detail endpoint returns an aggregate object. [DiscoverItem.raw]
/// retains that object, so this view can render the doctor, qualifications,
/// institution projects, and related counters without introducing a second DTO.
class DoctorDetailView extends StatelessWidget {
  const DoctorDetailView({
    required this.item,
    this.onInstitutionTap,
    this.onProjectTap,
    this.onDiaryTap,
    this.onConsult,
    this.onAiChat,
    this.onViewAllProjects,
    this.onViewAllDiaries,
    super.key,
  });

  final DiscoverItem item;
  final ValueChanged<String>? onInstitutionTap;
  final void Function(String institutionId, String projectId)? onProjectTap;
  final ValueChanged<String>? onDiaryTap;
  final VoidCallback? onConsult;
  final VoidCallback? onAiChat;
  final VoidCallback? onViewAllProjects;
  final VoidCallback? onViewAllDiaries;

  @override
  Widget build(BuildContext context) {
    final doctor = _map(item.raw['doctor']);
    final data = doctor.isEmpty ? item.raw : doctor;
    final avatar = _text(data, const ['avatar', 'imageUrl'], item.imageUrl);
    final name = _text(data, const ['name'], item.title);
    final title = _text(data, const ['title']);
    final primaryInstitution = _map(item.raw['institution']);
    final institutionName = _text(
      data,
      const ['institutionName'],
      _text(primaryInstitution, const ['name']),
    );
    final institutionId = _text(
      data,
      const ['institutionId'],
      _text(primaryInstitution, const ['id']),
    );
    final rating = _number(data['rating']);
    final verified = _boolean(data['isVerified']);
    final specialties = _texts(data['specialties']);
    final certificationTags = _texts(data['certificationTags']);
    final credentialImages = _texts(data['credentialImages']);
    final bio = _text(data, const ['bio', 'description'], item.subtitle);
    final credentials = _text(data, const ['credentials']);
    final projects = _maps(item.raw['institutionProjects']);
    final institutions = _maps(item.raw['institutions']);
    final diaries = _maps(item.raw['diaries']);

    final reviewCount = _integer(data['reviewCount']);
    final consultationCount = _integer(data['consultationCount']);
    final caseCount = _integer(data['caseCount']);
    final sectionKeys = List.generate(4, (_) => GlobalKey());
    void openSection(int index) {
      final targetContext = sectionKeys[index].currentContext;
      if (targetContext == null) return;
      Scrollable.ensureVisible(
        targetContext,
        duration: const Duration(milliseconds: 320),
        curve: Curves.easeOut,
        alignment: 0.04,
      );
    }

    return Column(
      children: [
        Expanded(
          child: CustomScrollView(
            slivers: [
              SliverToBoxAdapter(
                child: _DoctorHeader(
                  avatar: avatar,
                  name: name,
                  title: title,
                  institutionName: institutionName,
                  verified: verified,
                  rating: rating,
                  reviewCount: reviewCount,
                  caseCount: caseCount,
                  consultationCount: consultationCount,
                  certificationTags: {
                    ...certificationTags,
                    ...specialties,
                  }.toList(growable: false),
                  onInstitutionTap:
                      institutionId.isEmpty || onInstitutionTap == null
                          ? null
                          : () => onInstitutionTap!(institutionId),
                ),
              ),
              SliverPersistentHeader(
                pinned: true,
                delegate: _DoctorTabsDelegate(onSectionClick: openSection),
              ),
              SliverToBoxAdapter(
                child: KeyedSubtree(
                  key: sectionKeys[0],
                  child: _Section(
                    title: context.localized('资质保险箱', 'Credentials vault'),
                    trailing: Text(context.localized('查资质', 'Verify')),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          context.localized('医生实力', 'Professional profile'),
                          style: const TextStyle(
                            color: Color(0xff777777),
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          credentials.isNotEmpty
                              ? credentials
                              : bio.isNotEmpty
                                  ? bio
                                  : context.localized(
                                      '资质信息正在完善中',
                                      'Credentials are being updated',
                                    ),
                          style: const TextStyle(
                            height: 1.55,
                            color: Color(0xff666666),
                          ),
                        ),
                        if (credentialImages.isNotEmpty) ...[
                          const SizedBox(height: 14),
                          SizedBox(
                            height: 90,
                            child: ListView.separated(
                              scrollDirection: Axis.horizontal,
                              itemCount: credentialImages.length,
                              separatorBuilder: (_, __) =>
                                  const SizedBox(width: 8),
                              itemBuilder: (context, index) =>
                                  _QualificationImage(
                                urls: credentialImages,
                                index: index,
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
              SliverToBoxAdapter(
                child: KeyedSubtree(
                  key: sectionKeys[1],
                  child: _Section(
                    title: context.localized('可预约项目', 'Bookable projects'),
                    trailing: TextButton(
                      onPressed: projects.isEmpty ? null : onViewAllProjects,
                      child: Text(context.isEnglish
                          ? 'All (${projects.length})'
                          : '全部 (${projects.length})'),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (projects.isEmpty)
                          _EmptySection(
                            message: context.localized(
                              '暂无可预约项目',
                              'No bookable projects',
                            ),
                          ),
                        if (projects.isNotEmpty)
                          _FilterableProjects(
                            projects: projects,
                            onProjectTap: (project) =>
                                _projectTap(project, onProjectTap),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
              SliverToBoxAdapter(
                child: KeyedSubtree(
                  key: sectionKeys[2],
                  child: _Section(
                    title: context.localized('用户日记', 'Patient diaries'),
                    trailing: TextButton(
                      onPressed: diaries.isEmpty ? null : onViewAllDiaries,
                      child: Text(context.isEnglish
                          ? 'All (${diaries.length})'
                          : '全部 (${diaries.length})'),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (rating > 0)
                          Text(
                            '★ ${rating.toStringAsFixed(1)} ($reviewCount)',
                            style: Theme.of(context)
                                .textTheme
                                .titleLarge
                                ?.copyWith(color: const Color(0xff666666)),
                          ),
                        const SizedBox(height: 12),
                        if (diaries.isEmpty)
                          _EmptySection(
                            message: context.localized(
                              '暂无用户日记',
                              'No patient diaries',
                            ),
                          )
                        else
                          DiaryPreviewRail(
                            diaries: diaries,
                            onDiaryTap: onDiaryTap,
                            maxItems: 5,
                          ),
                      ],
                    ),
                  ),
                ),
              ),
              SliverToBoxAdapter(
                child: KeyedSubtree(
                  key: sectionKeys[3],
                  child: _Section(
                    title: context.localized('出诊机构', 'Clinic affiliations'),
                    child: Column(
                      children: [
                        if (institutions.isEmpty)
                          _EmptySection(
                            message: context.localized(
                              '暂无出诊机构',
                              'No clinic affiliations',
                            ),
                          ),
                        for (var index = 0;
                            index < institutions.length;
                            index++) ...[
                          _InstitutionTile(
                            data: institutions[index],
                            onTap: onInstitutionTap == null
                                ? null
                                : () {
                                    final id = _text(
                                      institutions[index],
                                      const ['id'],
                                    );
                                    if (id.isNotEmpty) onInstitutionTap!(id);
                                  },
                          ),
                          if (index < institutions.length - 1)
                            const SizedBox(height: 10),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
              const SliverToBoxAdapter(child: SizedBox(height: 18)),
            ],
          ),
        ),
        _DoctorBottomBar(
          onConsult: onConsult,
          onAiChat: onAiChat,
        ),
      ],
    );
  }

  VoidCallback? _projectTap(
    Map<String, Object?> project,
    void Function(String institutionId, String projectId)? callback,
  ) {
    if (callback == null) return null;
    return () {
      final institutionId = _text(project, const ['institutionId']);
      final projectId = _text(project, const ['projectId', 'id']);
      if (institutionId.isNotEmpty && projectId.isNotEmpty) {
        callback(institutionId, projectId);
      }
    };
  }
}

class _DoctorHeader extends StatelessWidget {
  const _DoctorHeader({
    required this.avatar,
    required this.name,
    required this.title,
    required this.institutionName,
    required this.verified,
    required this.rating,
    required this.reviewCount,
    required this.caseCount,
    required this.consultationCount,
    required this.certificationTags,
    this.onInstitutionTap,
  });

  final String avatar;
  final String name;
  final String title;
  final String institutionName;
  final bool verified;
  final double rating;
  final int reviewCount;
  final int caseCount;
  final int consultationCount;
  final List<String> certificationTags;
  final VoidCallback? onInstitutionTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.fromLTRB(16, 18, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Align(
            child: ClipOval(
              child: _NetworkImage(
                url: avatar,
                width: 80,
                height: 80,
                radius: 40,
              ),
            ),
          ),
          const SizedBox(height: 14),
          if (verified) ...[
            Row(children: [
              const Icon(Icons.verified_outlined, size: 15),
              const SizedBox(width: 5),
              Text(
                context.localized(
                  '安颜认证 · 官方认证操作师',
                  'Verified · Certified practitioner',
                ),
                style: const TextStyle(fontSize: 12),
              ),
            ]),
            const SizedBox(height: 7),
          ],
          Text(
            [name, title].where((value) => value.isNotEmpty).join('  '),
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
          ),
          if (certificationTags.isNotEmpty) ...[
            const SizedBox(height: 8),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(children: [
                for (final tag in certificationTags) ...[
                  _Tag(label: tag),
                  const SizedBox(width: 6),
                ],
              ]),
            ),
          ],
          if (institutionName.isNotEmpty) ...[
            const SizedBox(height: 9),
            InkWell(
              onTap: onInstitutionTap,
              child: Row(children: [
                const Icon(Icons.apartment_outlined,
                    size: 15, color: Color(0xff777777)),
                const SizedBox(width: 5),
                Expanded(
                  child: Text(
                    institutionName,
                    style: const TextStyle(
                      color: Color(0xff666666),
                      decoration: TextDecoration.underline,
                    ),
                  ),
                ),
              ]),
            ),
          ],
          const SizedBox(height: 11),
          Wrap(spacing: 14, runSpacing: 6, children: [
            Text(context.isEnglish
                ? '$reviewCount reviews · $consultationCount consultations'
                : '评价 $reviewCount · 咨询 $consultationCount'),
            Text(context.isEnglish ? '$caseCount cases' : '案例 $caseCount'),
            if (rating > 0) Text('★ ${rating.toStringAsFixed(1)}'),
          ]),
        ],
      ),
    );
  }
}

class _DoctorTabsDelegate extends SliverPersistentHeaderDelegate {
  _DoctorTabsDelegate({required this.onSectionClick});

  final ValueChanged<int> onSectionClick;

  @override
  double get minExtent => 54;

  @override
  double get maxExtent => 54;

  @override
  Widget build(
      BuildContext context, double shrinkOffset, bool overlapsContent) {
    final labels = context.isEnglish
        ? const ['Credentials', 'Projects', 'Diaries', 'Clinics']
        : const ['资质保险箱', '可预约项目', '用户日记', '出诊机构'];
    return DetailAnchorBar(labels: labels, onTap: onSectionClick);
  }

  @override
  bool shouldRebuild(covariant _DoctorTabsDelegate oldDelegate) => true;
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child, this.trailing});
  final String title;
  final Widget child;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(top: 10),
        padding: const EdgeInsets.all(16),
        color: Colors.white,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
              child: Text(title,
                  style: Theme.of(context)
                      .textTheme
                      .titleMedium
                      ?.copyWith(fontWeight: FontWeight.w700)),
            ),
            if (trailing != null) trailing!,
          ]),
          const SizedBox(height: 14),
          child,
        ]),
      );
}

class _EmptySection extends StatelessWidget {
  const _EmptySection({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) => SizedBox(
        height: 112,
        child: Center(
          child: Text(
            message,
            style: const TextStyle(color: Color(0xff999999)),
          ),
        ),
      );
}

class _ProjectCard extends StatelessWidget {
  const _ProjectCard({required this.data, this.onTap});
  final Map<String, Object?> data;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final price = _number(data['price']);
    final originalPrice = _number(data['originalPrice']);
    final tags = _projectTags(data);
    return Material(
      color: const Color(0xfff7f7f7),
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Row(children: [
            _NetworkImage(
              url: _text(data, const ['coverImage']),
              width: 76,
              height: 76,
              radius: 9,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(_text(data, const ['projectName', 'name']),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 5),
                  Text(_text(data, const ['institutionName', 'category']),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall),
                  if (tags.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 5,
                      runSpacing: 4,
                      children: tags
                          .take(3)
                          .map((tag) => _ProjectTag(label: tag))
                          .toList(growable: false),
                    ),
                  ],
                  if (price > 0) ...[
                    const SizedBox(height: 7),
                    Row(children: [
                      Text('¥${_money(price)}',
                          style: const TextStyle(
                              color: Color(0xffe53935),
                              fontWeight: FontWeight.w700)),
                      if (originalPrice > price) ...[
                        const SizedBox(width: 7),
                        Text('¥${_money(originalPrice)}',
                            style: const TextStyle(
                                color: Color(0xff999999),
                                decoration: TextDecoration.lineThrough)),
                      ],
                    ]),
                  ],
                ],
              ),
            ),
            if (onTap != null) const Icon(Icons.chevron_right_rounded),
          ]),
        ),
      ),
    );
  }
}

class _FilterableProjects extends StatefulWidget {
  const _FilterableProjects({
    required this.projects,
    required this.onProjectTap,
  });

  final List<Map<String, Object?>> projects;
  final VoidCallback? Function(Map<String, Object?> project) onProjectTap;

  @override
  State<_FilterableProjects> createState() => _FilterableProjectsState();
}

class _FilterableProjectsState extends State<_FilterableProjects> {
  String _selectedTag = '';

  @override
  Widget build(BuildContext context) {
    final tags = _allProjectTags(widget.projects);
    final filtered = _selectedTag.isEmpty
        ? widget.projects
        : widget.projects
            .where((project) => _projectTags(project).contains(_selectedTag))
            .toList(growable: false);
    final visible = filtered.take(5).toList(growable: false);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (tags.isNotEmpty) ...[
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                _FilterChip(
                  label: context.localized('全部', 'All'),
                  selected: _selectedTag.isEmpty,
                  onSelected: () => setState(() => _selectedTag = ''),
                ),
                for (final tag in tags) ...[
                  const SizedBox(width: 8),
                  _FilterChip(
                    label: tag,
                    selected: _selectedTag == tag,
                    onSelected: () => setState(() => _selectedTag = tag),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 14),
        ],
        for (var index = 0; index < visible.length; index++) ...[
          _ProjectCard(
            data: visible[index],
            onTap: widget.onProjectTap(visible[index]),
          ),
          if (index < visible.length - 1) const SizedBox(height: 14),
        ],
      ],
    );
  }
}

class _FilterChip extends StatelessWidget {
  const _FilterChip({
    required this.label,
    required this.selected,
    required this.onSelected,
  });

  final String label;
  final bool selected;
  final VoidCallback onSelected;

  @override
  Widget build(BuildContext context) => ChoiceChip(
        label: Text(label),
        selected: selected,
        onSelected: (_) => onSelected(),
        visualDensity: VisualDensity.compact,
      );
}

class _ProjectTag extends StatelessWidget {
  const _ProjectTag({required this.label});
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

class _DoctorBottomBar extends StatelessWidget {
  const _DoctorBottomBar({this.onConsult, this.onAiChat});
  final VoidCallback? onConsult;
  final VoidCallback? onAiChat;

  @override
  Widget build(BuildContext context) => SafeArea(
        top: false,
        child: Container(
          color: Colors.white,
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
          child: Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: onAiChat,
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                    foregroundColor: Colors.black,
                    side: const BorderSide(color: Color(0xffdddddd)),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                  ),
                  child: Text(context.localized('与AI聊聊', 'Chat with AI')),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton(
                  onPressed: onConsult,
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                    backgroundColor: Colors.black,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                  ),
                  child: Text(
                    context.localized('咨询医生', 'Consult doctor'),
                    style: const TextStyle(color: Colors.white),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
}

class _InstitutionTile extends StatelessWidget {
  const _InstitutionTile({required this.data, this.onTap});
  final Map<String, Object?> data;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final address = [
      _text(data, const ['city']),
      _text(data, const ['address']),
    ].where((value) => value.isNotEmpty).join(' ');
    return Material(
      color: const Color(0xfff7f7f7),
      borderRadius: BorderRadius.circular(12),
      child: ListTile(
        onTap: onTap,
        title: Row(children: [
          Flexible(
              child: Text(_text(data, const ['name']),
                  style: const TextStyle(fontWeight: FontWeight.w700))),
          if (_boolean(data['isVerified'])) ...[
            const SizedBox(width: 5),
            Icon(Icons.verified_outlined,
                size: 17, color: Theme.of(context).colorScheme.primary),
          ],
        ]),
        subtitle: address.isEmpty ? null : Text(address),
        trailing:
            onTap == null ? null : const Icon(Icons.chevron_right_rounded),
      ),
    );
  }
}

class _QualificationImage extends StatelessWidget {
  const _QualificationImage({required this.urls, required this.index});
  final List<String> urls;
  final int index;

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: () => showDialog<void>(
          context: context,
          builder: (_) => _ImageViewer(urls: urls, initialIndex: index),
        ),
        child:
            _NetworkImage(url: urls[index], width: 90, height: 90, radius: 9),
      );
}

class _ImageViewer extends StatefulWidget {
  const _ImageViewer({required this.urls, required this.initialIndex});
  final List<String> urls;
  final int initialIndex;

  @override
  State<_ImageViewer> createState() => _ImageViewerState();
}

class _ImageViewerState extends State<_ImageViewer> {
  late final PageController _controller;
  late int _index;

  @override
  void initState() {
    super.initState();
    _index = widget.initialIndex;
    _controller = PageController(initialPage: _index);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Dialog.fullscreen(
        backgroundColor: Colors.black,
        child: Stack(children: [
          PageView.builder(
            controller: _controller,
            itemCount: widget.urls.length,
            onPageChanged: (value) => setState(() => _index = value),
            itemBuilder: (_, index) => InteractiveViewer(
              child: Center(
                child: Image.network(widget.urls[index],
                    fit: BoxFit.contain,
                    errorBuilder: (_, __, ___) => const Icon(
                        Icons.broken_image_outlined,
                        color: Colors.white,
                        size: 48)),
              ),
            ),
          ),
          SafeArea(
            child: Align(
              alignment: Alignment.topRight,
              child: IconButton(
                onPressed: () => Navigator.of(context).pop(),
                color: Colors.white,
                icon: const Icon(Icons.close_rounded),
              ),
            ),
          ),
          if (widget.urls.length > 1)
            SafeArea(
              child: Align(
                alignment: Alignment.bottomCenter,
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text('${_index + 1} / ${widget.urls.length}',
                      style: const TextStyle(color: Colors.white)),
                ),
              ),
            ),
        ]),
      );
}

class _NetworkImage extends StatelessWidget {
  const _NetworkImage({
    required this.url,
    required this.width,
    required this.height,
    required this.radius,
  });
  final String url;
  final double width;
  final double height;
  final double radius;

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
      child:
          const Icon(Icons.medical_services_outlined, color: Color(0xffaaaaaa)),
    );
    if (url.isEmpty) return fallback;
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: Image.network(url,
          width: width,
          height: height,
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => fallback),
    );
  }
}

class _Tag extends StatelessWidget {
  const _Tag({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
        decoration: BoxDecoration(
          color: const Color(0xfff0f0f0),
          borderRadius: BorderRadius.circular(6),
        ),
        child: Text(label, style: Theme.of(context).textTheme.bodySmall),
      );
}

Map<String, Object?> _map(Object? value) {
  if (value is! Map) return const {};
  return value.map((key, value) => MapEntry(key.toString(), value));
}

List<Map<String, Object?>> _maps(Object? value) {
  if (value is! List) return const [];
  return value
      .map(_map)
      .where((item) => item.isNotEmpty)
      .toList(growable: false);
}

String _text(Map<String, Object?> data, List<String> keys,
    [String fallback = '']) {
  for (final key in keys) {
    final value = data[key];
    if (value != null && value.toString().trim().isNotEmpty) {
      return value.toString().trim();
    }
  }
  return fallback;
}

List<String> _texts(Object? value) {
  final values = value is List ? value : (value?.toString() ?? '').split(',');
  return values
      .map((item) => item.toString().trim())
      .where((item) => item.isNotEmpty)
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
            item.map((key, value) => MapEntry(key.toString(), value)),
            const ['name', 'label', 'tagName', 'categoryName'],
          );
          if (tag.isNotEmpty) tags.add(tag);
        } else {
          tags.addAll(_texts(item));
        }
      }
    } else {
      tags.addAll(_texts(value));
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

int _integer(Object? value) =>
    value is num ? value.toInt() : int.tryParse('$value') ?? 0;

bool _boolean(Object? value) =>
    value == true || value == 1 || '$value'.toLowerCase() == 'true';

String _money(double value) => value == value.roundToDouble()
    ? value.toStringAsFixed(0)
    : value.toStringAsFixed(2);
