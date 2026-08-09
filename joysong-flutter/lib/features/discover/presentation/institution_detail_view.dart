import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';

/// Institution-specific detail body used by [DiscoverDetailPage].
///
/// The discover API keeps the institution payload in `institution` and the
/// related collections at the response root. [DiscoverItem.raw] currently
/// contains both, so this view deliberately reads either representation.
class InstitutionDetailView extends StatelessWidget {
  const InstitutionDetailView({
    required this.item,
    this.onProjectTap,
    this.onDoctorTap,
    super.key,
  });

  final DiscoverItem item;
  final void Function(String institutionId, String projectId)? onProjectTap;
  final ValueChanged<String>? onDoctorTap;

  @override
  Widget build(BuildContext context) {
    final data = _institutionData(item.raw);
    final images = _images(data, item.imageUrl);
    final tags = <String>{
      ..._tokens(data['specialties']),
      ..._tokens(data['tags']),
    }.toList(growable: false);
    final address = [
      _text(data, const ['city']),
      _text(data, const ['address']),
    ].where((value) => value.isNotEmpty).join(' · ');
    final description = _text(data, const ['description', 'introduction']);
    final verified = _bool(data['isVerified']) || _bool(data['verified']);
    final rating = _number(data['rating']);
    final reviewCount = _integer(data['reviewCount']);
    final institutionId = _text(data, const ['id', 'institutionId']);
    final projects = _maps(item.raw['projects']);
    final doctors = _maps(item.raw['doctors']);

    return ListView(
      padding: EdgeInsets.zero,
      children: [
        _Gallery(images: images, title: item.title),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Text(
                      item.title,
                      style: Theme.of(context)
                          .textTheme
                          .headlineSmall
                          ?.copyWith(fontWeight: FontWeight.w700),
                    ),
                  ),
                  if (verified) ...[
                    const SizedBox(width: 8),
                    _VerifiedBadge(
                      label: context.localized('官方认证', 'Verified'),
                    ),
                  ],
                ],
              ),
              if (rating > 0 || reviewCount > 0) ...[
                const SizedBox(height: 10),
                Row(
                  children: [
                    const Icon(
                      Icons.star_rounded,
                      size: 20,
                      color: Color(0xffffa000),
                    ),
                    const SizedBox(width: 4),
                    Text(
                      rating > 0 ? rating.toStringAsFixed(1) : '—',
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                    if (reviewCount > 0)
                      Text(
                        context.isEnglish
                            ? '  ($reviewCount reviews)'
                            : '  ($reviewCount条评价)',
                      ),
                  ],
                ),
              ],
              if (address.isNotEmpty) ...[
                const SizedBox(height: 12),
                _InfoLine(icon: Icons.location_on_outlined, text: address),
              ],
              if (tags.isNotEmpty) ...[
                const SizedBox(height: 16),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: tags.map((tag) => Chip(label: Text(tag))).toList(),
                ),
              ],
              const SizedBox(height: 20),
              _FeatureHighlights(context),
              const SizedBox(height: 24),
              _Stats(data: data),
              if (projects.isNotEmpty) ...[
                const SizedBox(height: 28),
                _RelatedProjects(
                  projects: projects,
                  onTap: institutionId.isEmpty || onProjectTap == null
                      ? null
                      : (projectId) => onProjectTap!(institutionId, projectId),
                ),
              ],
              if (doctors.isNotEmpty) ...[
                const SizedBox(height: 28),
                _RelatedDoctors(doctors: doctors, onTap: onDoctorTap),
              ],
              if (description.isNotEmpty) ...[
                const SizedBox(height: 28),
                _Section(
                  title: context.localized('机构简介', 'About'),
                  child: Text(
                    description,
                    style: const TextStyle(height: 1.65),
                  ),
                ),
              ],
              if (_hasQualifications(data)) ...[
                const SizedBox(height: 28),
                _Qualifications(data: data),
              ],
              if (_hasContact(data)) ...[
                const SizedBox(height: 28),
                _Contact(data: data),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _Gallery extends StatefulWidget {
  const _Gallery({required this.images, required this.title});

  final List<String> images;
  final String title;

  @override
  State<_Gallery> createState() => _GalleryState();
}

class _GalleryState extends State<_Gallery> {
  int _page = 0;

  @override
  Widget build(BuildContext context) {
    if (widget.images.isEmpty) {
      return Container(
        height: 220,
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        alignment: Alignment.center,
        child: const Icon(Icons.apartment_rounded, size: 64),
      );
    }
    return Stack(
      alignment: Alignment.bottomCenter,
      children: [
        SizedBox(
          height: 260,
          child: PageView.builder(
            itemCount: widget.images.length,
            onPageChanged: (value) => setState(() => _page = value),
            itemBuilder: (context, index) => GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => Navigator.of(context).push<void>(
                MaterialPageRoute(
                  builder: (_) => FullscreenImagePager(
                    images: widget.images,
                    contentDescription: context.localized(
                      '机构图片',
                      'Institution image',
                    ),
                    initialPage: index,
                  ),
                ),
              ),
              child: OptimizedNetworkImage(
                url: widget.images[index],
                width: double.infinity,
                height: 260,
                errorBuilder: (_, __, ___) => Container(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
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
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 4,
                ),
                child: Text(
                  '${_page + 1}/${widget.images.length}',
                  style: const TextStyle(color: Colors.white, fontSize: 12),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _VerifiedBadge extends StatelessWidget {
  const _VerifiedBadge({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.primaryContainer,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.verified_rounded,
                size: 16,
                color: Theme.of(context).colorScheme.onPrimaryContainer,
              ),
              const SizedBox(width: 4),
              Text(label, style: const TextStyle(fontSize: 12)),
            ],
          ),
        ),
      );
}

class _InfoLine extends StatelessWidget {
  const _InfoLine({required this.icon, required this.text});
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 19, color: Theme.of(context).colorScheme.primary),
          const SizedBox(width: 8),
          Expanded(child: Text(text, style: const TextStyle(height: 1.4))),
        ],
      );
}

class _FeatureHighlights extends StatelessWidget {
  const _FeatureHighlights(this.context);
  final BuildContext context;

  @override
  Widget build(BuildContext _) => Row(
        children: [
          Expanded(
            child: _FeatureCard(
              icon: Icons.school_outlined,
              title: context.localized('专业医生', 'Expert doctors'),
              subtitle: context.localized('经验丰富', 'Experienced team'),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: _FeatureCard(
              icon: Icons.biotech_outlined,
              title: context.localized('先进设备', 'Advanced equipment'),
              subtitle: context.localized('规范服务', 'Quality standards'),
            ),
          ),
        ],
      );
}

class _FeatureCard extends StatelessWidget {
  const _FeatureCard({
    required this.icon,
    required this.title,
    required this.subtitle,
  });
  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) => Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, color: Theme.of(context).colorScheme.primary),
              const SizedBox(height: 8),
              Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 3),
              Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
        ),
      );
}

class _Stats extends StatelessWidget {
  const _Stats({required this.data});
  final Map<String, Object?> data;

  @override
  Widget build(BuildContext context) {
    final stats = [
      (_integer(data['projectCount']), context.localized('项目', 'Projects')),
      (_integer(data['doctorCount']), context.localized('医生', 'Doctors')),
      (_integer(data['caseCount']), context.localized('案例', 'Cases')),
      (
        _integer(data['consultationCount']),
        context.localized('咨询', 'Consultations'),
      ),
    ].where((item) => item.$1 > 0).toList();
    if (stats.isEmpty) return const SizedBox.shrink();
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 18),
        child: Row(
          children: stats
              .map(
                (stat) => Expanded(
                  child: Column(
                    children: [
                      Text(
                        '${stat.$1}',
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                              fontWeight: FontWeight.w700,
                            ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        stat.$2,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              )
              .toList(),
        ),
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child});
  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: Theme.of(
              context,
            ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 12),
          child,
        ],
      );
}

class _RelatedProjects extends StatefulWidget {
  const _RelatedProjects({required this.projects, this.onTap});

  final List<Map<String, Object?>> projects;
  final ValueChanged<String>? onTap;

  @override
  State<_RelatedProjects> createState() => _RelatedProjectsState();
}

class _RelatedProjectsState extends State<_RelatedProjects> {
  String _selectedTag = '';

  @override
  Widget build(BuildContext context) {
    final tags = _allProjectTags(widget.projects);
    final projects = _selectedTag.isEmpty
        ? widget.projects
        : widget.projects
            .where((project) => _projectTags(project).contains(_selectedTag))
            .toList(growable: false);
    return _Section(
      title: context.localized('可预约项目', 'Available services'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (tags.isNotEmpty) ...[
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  _ProjectFilterChip(
                    label: context.localized('全部', 'All'),
                    selected: _selectedTag.isEmpty,
                    onSelected: () => setState(() => _selectedTag = ''),
                  ),
                  for (final tag in tags) ...[
                    const SizedBox(width: 8),
                    _ProjectFilterChip(
                      label: tag,
                      selected: _selectedTag == tag,
                      onSelected: () => setState(() => _selectedTag = tag),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 12),
          ],
          SizedBox(
            height: 174,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: projects.length,
              separatorBuilder: (_, __) => const SizedBox(width: 10),
              itemBuilder: (context, index) {
                final project = projects[index];
                final projectId = _text(project, const ['projectId', 'id']);
                final image = _text(project, const ['coverImage']);
                final name = _text(project, const ['projectName', 'name']);
                final price = _text(project, const ['price']);
                final projectTags = _projectTags(project);
                return SizedBox(
                  width: 230,
                  child: Card(
                    margin: EdgeInsets.zero,
                    clipBehavior: Clip.antiAlias,
                    child: InkWell(
                      onTap: projectId.isEmpty || widget.onTap == null
                          ? null
                          : () => widget.onTap!(projectId),
                      child: Row(
                        children: [
                          image.isEmpty
                              ? Container(
                                  width: 82,
                                  color: Theme.of(
                                    context,
                                  ).colorScheme.surfaceContainerHighest,
                                  child: const Icon(Icons.spa_outlined),
                                )
                              : OptimizedNetworkImage(
                                  url: image,
                                  width: 82,
                                  height: 82,
                                  errorBuilder: (_, __, ___) =>
                                      const SizedBox(width: 82),
                                ),
                          Expanded(
                            child: Padding(
                              padding: const EdgeInsets.all(10),
                              child: Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    name,
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                  if (projectTags.isNotEmpty) ...[
                                    const SizedBox(height: 7),
                                    Wrap(
                                      spacing: 5,
                                      runSpacing: 4,
                                      children: projectTags
                                          .take(3)
                                          .map((tag) => _ProjectTag(label: tag))
                                          .toList(growable: false),
                                    ),
                                  ],
                                  if (price.isNotEmpty) ...[
                                    const SizedBox(height: 8),
                                    Text(
                                      '\$$price',
                                      style: TextStyle(
                                        color: Theme.of(
                                          context,
                                        ).colorScheme.primary,
                                        fontWeight: FontWeight.w700,
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _ProjectFilterChip extends StatelessWidget {
  const _ProjectFilterChip({
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

class _RelatedDoctors extends StatelessWidget {
  const _RelatedDoctors({required this.doctors, this.onTap});

  final List<Map<String, Object?>> doctors;
  final ValueChanged<String>? onTap;

  @override
  Widget build(BuildContext context) {
    return _Section(
      title: context.localized('坐诊医生', 'Doctors'),
      child: SizedBox(
        height: 142,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: doctors.length,
          separatorBuilder: (_, __) => const SizedBox(width: 10),
          itemBuilder: (context, index) {
            final doctor = doctors[index];
            final id = _text(doctor, const ['id', 'doctorId']);
            final name = _text(doctor, const ['name']);
            final title = _text(doctor, const ['title']);
            final avatar = _text(doctor, const ['avatar']);
            return SizedBox(
              width: 126,
              child: Card(
                margin: EdgeInsets.zero,
                child: InkWell(
                  onTap: id.isEmpty || onTap == null ? null : () => onTap!(id),
                  borderRadius: BorderRadius.circular(12),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      children: [
                        CircleAvatar(
                          radius: 28,
                          foregroundImage: avatar.isEmpty
                              ? null
                              : optimizedNetworkImageProvider(
                                  context,
                                  avatar,
                                  width: 56,
                                  height: 56,
                                ),
                          child: avatar.isEmpty
                              ? const Icon(Icons.person_outline_rounded)
                              : null,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        const SizedBox(height: 3),
                        Text(
                          title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.labelSmall,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

class _Qualifications extends StatelessWidget {
  const _Qualifications({required this.data});
  final Map<String, Object?> data;

  @override
  Widget build(BuildContext context) {
    final establishedYear = _integer(data['establishedYear']);
    final certificationTime = _text(data, const ['certificationTime']);
    final credentials = _text(data, const ['credentials']);
    final credentialImages = _tokens(data['credentialImages']);
    return _Section(
      title: context.localized('机构资质', 'Qualifications'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (establishedYear > 0)
            _InfoLine(
              icon: Icons.flag_outlined,
              text: context.isEnglish
                  ? 'Established in $establishedYear'
                  : '$establishedYear年成立',
            ),
          if (certificationTime.isNotEmpty) ...[
            const SizedBox(height: 10),
            _InfoLine(
              icon: Icons.verified_user_outlined,
              text: context.isEnglish
                  ? 'Certified: $certificationTime'
                  : '认证时间：$certificationTime',
            ),
          ],
          if (credentials.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(credentials, style: const TextStyle(height: 1.6)),
          ],
          if (credentialImages.isNotEmpty) ...[
            const SizedBox(height: 14),
            SizedBox(
              height: 112,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: credentialImages.length,
                separatorBuilder: (_, __) => const SizedBox(width: 10),
                itemBuilder: (context, index) => GestureDetector(
                  onTap: () => Navigator.of(context).push<void>(
                    MaterialPageRoute(
                      builder: (_) => FullscreenImagePager(
                        images: credentialImages,
                        contentDescription: context.localized(
                          '机构资质图片',
                          'Institution credential',
                        ),
                        initialPage: index,
                      ),
                    ),
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: Image.network(
                      credentialImages[index],
                      width: 140,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _Contact extends StatelessWidget {
  const _Contact({required this.data});
  final Map<String, Object?> data;

  @override
  Widget build(BuildContext context) {
    final phone = _text(data, const ['contactPhone', 'phone']);
    final hours = _text(data, const ['businessHours', 'openingHours']);
    final address = [
      _text(data, const ['city']),
      _text(data, const ['address']),
    ].where((value) => value.isNotEmpty).join(' · ');
    return _Section(
      title: context.localized('联系与营业信息', 'Contact & hours'),
      child: Column(
        children: [
          if (phone.isNotEmpty)
            _InfoLine(icon: Icons.phone_outlined, text: phone),
          if (hours.isNotEmpty) ...[
            if (phone.isNotEmpty) const SizedBox(height: 12),
            _InfoLine(icon: Icons.access_time_rounded, text: hours),
          ],
          if (address.isNotEmpty) ...[
            if (phone.isNotEmpty || hours.isNotEmpty)
              const SizedBox(height: 12),
            _InfoLine(icon: Icons.location_on_outlined, text: address),
          ],
        ],
      ),
    );
  }
}

Map<String, Object?> _institutionData(Map<String, Object?> raw) {
  final nested = raw['institution'];
  if (nested is Map) {
    return <String, Object?>{
      ...nested.map((key, value) => MapEntry(key.toString(), value)),
      ...raw,
    };
  }
  return raw;
}

List<Map<String, Object?>> _maps(Object? value) {
  if (value is! List) return const [];
  return value
      .whereType<Map>()
      .map((item) => item.map((key, value) => MapEntry(key.toString(), value)))
      .toList(growable: false);
}

List<String> _images(Map<String, Object?> data, String fallback) {
  final result = <String>{..._tokens(data['images'])};
  final cover = _text(data, const ['coverImage', 'imageUrl']);
  if (cover.isNotEmpty) result.add(cover);
  if (fallback.isNotEmpty) result.add(fallback);
  return result.toList(growable: false);
}

List<String> _tokens(Object? value) {
  if (value is List) {
    return value
        .map((item) => item.toString().trim())
        .where((item) => item.isNotEmpty)
        .toList(growable: false);
  }
  return (value?.toString() ?? '')
      .split(RegExp(r'[,;\n]'))
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toList(growable: false);
}

List<String> _projectTags(Map<String, Object?> project) {
  final tags = <String>{};
  final nested = project['project'];
  if (nested is Map) {
    tags.addAll(
      _projectTags(nested.map((key, value) => MapEntry(key.toString(), value))),
    );
  }
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
          tags.addAll(_tokens(item));
        }
      }
    } else {
      tags.addAll(_tokens(value));
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

String _text(Map<String, Object?> data, List<String> keys) {
  for (final key in keys) {
    final value = data[key]?.toString().trim() ?? '';
    if (value.isNotEmpty && value.toLowerCase() != 'null') return value;
  }
  return '';
}

bool _bool(Object? value) =>
    value == true ||
    value?.toString().toLowerCase() == 'true' ||
    value?.toString() == '1';

double _number(Object? value) =>
    value is num ? value.toDouble() : double.tryParse('$value') ?? 0;

int _integer(Object? value) =>
    value is num ? value.toInt() : int.tryParse('$value') ?? 0;

bool _hasQualifications(Map<String, Object?> data) =>
    _integer(data['establishedYear']) > 0 ||
    _text(data, const ['certificationTime', 'credentials']).isNotEmpty ||
    _tokens(data['credentialImages']).isNotEmpty;

bool _hasContact(Map<String, Object?> data) => _text(data, const [
      'contactPhone',
      'phone',
      'businessHours',
      'openingHours',
      'address',
    ]).isNotEmpty;
