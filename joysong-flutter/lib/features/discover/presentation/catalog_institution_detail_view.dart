import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_review_section.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_content_card.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/presentation/favorite_action_button.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

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
    this.onConsultInstitution,
    this.onAiChat,
    this.socialController,
    this.enableAutoTranslation = false,
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
  final ValueChanged<String>? onConsultInstitution;
  final VoidCallback? onAiChat;
  final SocialController? socialController;
  final bool enableAutoTranslation;

  @override
  Widget build(BuildContext context) {
    final nested = _map(item.raw['institution']);
    final data = {...nested, ...item.raw};
    final institutionId = _text(data, const ['id', 'institutionId']);
    final translationOwnerId = institutionId.isEmpty ? item.id : institutionId;
    final translationContentId = 'institution:$translationOwnerId';
    final projects = _maps(item.raw['projects']);
    final diaries = _maps(item.raw['diaries']);
    final reviews = _maps(item.raw['reviews']);
    final doctors = _maps(item.raw['doctors']);
    final images = <String>{
      ..._split(data['images']),
      ..._split(data['coverImage']),
      if (item.imageUrl.isNotEmpty) item.imageUrl,
    }.toList(growable: false);
    final nameSource = _firstTextSource(data, const ['name', 'title']);
    final name = nameSource?.value ?? item.title;
    final address = [
      _text(data, const ['city']),
      _text(data, const ['address']),
    ].where((value) => value.isNotEmpty).join(' · ');
    final phone = _text(data, const ['contactPhone', 'phone', 'telephone']);
    final rating = _firstNumber(data, const ['rating', 'averageRating']);
    final reviewCount = _count(data, const ['reviewCount', 'ratingCount']);
    final caseCount = _count(data, const ['caseCount', 'casesCount']);
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
            child: _InstitutionHero(
              images: images,
              name: name,
              nameField: nameSource?.field ?? '',
              eyebrow: _bool(data['isVerified'])
                  ? context.localized('安颜认证', 'Verified institution')
                  : '',
              address: address,
              contentId: translationContentId,
              enableAutoTranslation: enableAutoTranslation,
            ),
          ),
          SliverToBoxAdapter(
            child: _InstitutionFacts(
              rating: rating,
              reviewCount: reviewCount,
              caseCount: caseCount,
              address: address,
              phone: phone,
              contentId: translationContentId,
              enableAutoTranslation: enableAutoTranslation,
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
              child: _Credentials(
                data,
                contentId: translationContentId,
                enableAutoTranslation: enableAutoTranslation,
              ),
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
                      socialController: socialController,
                      enableAutoTranslation: enableAutoTranslation,
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
                  : DiaryPreviewRail(
                      diaries: diaries,
                      onDiaryTap: onDiaryTap,
                      maxItems: 5,
                      enableAutoTranslation: enableAutoTranslation,
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
              child: CatalogReviewPreview(
                reviews: reviews,
                socialController: socialController,
                onViewAll: onViewAllReviews,
                showHeader: false,
                enableAutoTranslation: enableAutoTranslation,
                ownerType: 'institution',
                ownerId: translationOwnerId,
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
                          _DoctorRow(
                            doctor,
                            onDoctorTap,
                            socialController,
                          ),
                      ],
                    ),
            ),
          ),
          const SliverToBoxAdapter(child: SizedBox(height: 18)),
        ]),
      ),
      CatalogBottomBar(
        primaryLabel: context.localized('咨询机构', 'Consult institution'),
        onPrimary: institutionId.isEmpty || onConsultInstitution == null
            ? null
            : () => onConsultInstitution!(institutionId),
        secondaryLabel: context.localized('与AI聊聊', 'Chat with AI'),
        onSecondary: onAiChat,
      ),
    ]);
  }
}

class _InstitutionHero extends StatefulWidget {
  const _InstitutionHero({
    required this.images,
    required this.name,
    required this.nameField,
    required this.eyebrow,
    required this.address,
    required this.contentId,
    required this.enableAutoTranslation,
  });

  final List<String> images;
  final String name;
  final String nameField;
  final String eyebrow;
  final String address;
  final String contentId;
  final bool enableAutoTranslation;

  @override
  State<_InstitutionHero> createState() => _InstitutionHeroState();
}

class _InstitutionHeroState extends State<_InstitutionHero> {
  AutoTranslationRequest? _nameRequest;
  AutoTranslationRequest? _addressRequest;

  @override
  void initState() {
    super.initState();
    _updateRequests();
  }

  @override
  void didUpdateWidget(_InstitutionHero oldWidget) {
    super.didUpdateWidget(oldWidget);
    _updateRequests();
  }

  void _updateRequests() {
    _nameRequest = _updatedInstitutionRequest(
      _nameRequest,
      enabled: widget.enableAutoTranslation,
      contentId: widget.contentId,
      field: widget.nameField,
      source: widget.name,
    );
    _addressRequest = _updatedInstitutionRequest(
      _addressRequest,
      enabled: widget.enableAutoTranslation,
      contentId: widget.contentId,
      field: 'address',
      source: widget.address,
    );
  }

  @override
  Widget build(BuildContext context) {
    Widget hero(String name, String address) => CatalogHero(
          images: widget.images,
          title: name,
          eyebrow: widget.eyebrow,
          subtitle: address,
        );

    final nameRequest = _nameRequest;
    final addressRequest = _addressRequest;
    Widget withAddress(String name) {
      if (addressRequest == null) return hero(name, widget.address);
      return AutoTranslationBuilder(
        request: addressRequest,
        builder: (_, address) => hero(name, address),
      );
    }

    if (nameRequest == null) return withAddress(widget.name);
    return AutoTranslationBuilder(
      request: nameRequest,
      builder: (_, name) => withAddress(name),
    );
  }
}

class _InstitutionFacts extends StatelessWidget {
  const _InstitutionFacts({
    required this.rating,
    required this.reviewCount,
    required this.caseCount,
    required this.address,
    required this.phone,
    required this.contentId,
    required this.enableAutoTranslation,
  });
  final double rating;
  final int reviewCount;
  final int caseCount;
  final String address;
  final String phone;
  final String contentId;
  final bool enableAutoTranslation;

  @override
  Widget build(BuildContext context) => Container(
        color: Colors.white,
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 6),
        child: Column(children: [
          Row(children: [
            Expanded(
              child: _FactStat(
                value: rating > 0 ? rating.toStringAsFixed(1) : '—',
                label: context.localized('评分', 'Rating'),
                icon: Icons.star_rounded,
              ),
            ),
            Expanded(
              child: _FactStat(
                value: '$reviewCount',
                label: context.localized('评价', 'Reviews'),
              ),
            ),
            Expanded(
              child: _FactStat(
                value: '$caseCount',
                label: context.localized('案例', 'Cases'),
              ),
            ),
          ]),
          if (address.isNotEmpty)
            _CopyableFact(
              icon: Icons.location_on_outlined,
              label: context.localized('机构位置', 'Location'),
              value: address,
              request: enableAutoTranslation
                  ? _institutionRequest(
                      contentId: contentId,
                      field: 'address',
                      source: address,
                    )
                  : null,
            ),
          if (phone.isNotEmpty)
            _CopyableFact(
              icon: Icons.phone_outlined,
              label: context.localized('机构电话', 'Institution phone'),
              value: phone,
            ),
        ]),
      );
}

class _FactStat extends StatelessWidget {
  const _FactStat({required this.value, required this.label, this.icon});
  final String value;
  final String label;
  final IconData? icon;
  @override
  Widget build(BuildContext context) => Column(children: [
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          if (icon != null) ...[
            Icon(icon, size: 18, color: const Color(0xffffa000)),
            const SizedBox(width: 3),
          ],
          Text(value,
              style:
                  const TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
        ]),
        const SizedBox(height: 3),
        Text(label,
            style: const TextStyle(fontSize: 12, color: Color(0xff777777))),
      ]);
}

class _CopyableFact extends StatelessWidget {
  const _CopyableFact(
      {required this.icon,
      required this.label,
      required this.value,
      this.request});
  final IconData icon;
  final String label;
  final String value;
  final AutoTranslationRequest? request;
  @override
  Widget build(BuildContext context) => InkWell(
        onTap: () async {
          await Clipboard.setData(ClipboardData(text: value));
          if (!context.mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(context.localized('已复制：$value', 'Copied: $value')),
          ));
        },
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Row(children: [
            Icon(icon, size: 20, color: const Color(0xff777777)),
            const SizedBox(width: 8),
            Text('$label：', style: const TextStyle(color: Color(0xff777777))),
            Expanded(
              child: request == null
                  ? Text(
                      value,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    )
                  : AutoTranslatedText(
                      request: request!,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
            ),
            Icon(
              Icons.copy_rounded,
              size: 17,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ]),
        ),
      );
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
                  Icons.precision_manufacturing_outlined,
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
  const _Credentials(
    this.data, {
    required this.contentId,
    required this.enableAutoTranslation,
  });
  final Map<String, Object?> data;
  final String contentId;
  final bool enableAutoTranslation;
  @override
  Widget build(BuildContext context) {
    final source = _firstTextSource(data, const ['credentials', 'description']);
    final text = source?.value ?? '';
    final images = _split(data['credentialImages']);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (text.isEmpty)
        Text(
          context.localized(
            '资质信息正在完善中',
            'Credentials are being updated',
          ),
          maxLines: 6,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(height: 1.5, color: Color(0xff666666)),
        )
      else if (enableAutoTranslation)
        AutoTranslatedText(
          request: _institutionRequest(
            contentId: contentId,
            field: source!.field,
            source: text,
          ),
          maxLines: 6,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(height: 1.5, color: Color(0xff666666)),
        )
      else
        Text(
          text,
          maxLines: 6,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(height: 1.5, color: Color(0xff666666)),
        ),
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
    this.translationEnabled = false,
    this.projectId = '',
    this.field = '',
  });
  final String label;
  final bool selected;
  final VoidCallback onTap;
  final bool translationEnabled;
  final String projectId;
  final String field;
  @override
  Widget build(BuildContext context) => ChoiceChip(
        label: _InstitutionProjectText(
          enabled: translationEnabled,
          projectId: projectId,
          field: field,
          source: label,
        ),
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
    required this.socialController,
    required this.enableAutoTranslation,
  });

  final List<Map<String, Object?>> projects;
  final String institutionId;
  final void Function(String institutionId, String projectId)? onTap;
  final SocialController? socialController;
  final bool enableAutoTranslation;

  @override
  State<_FilterableProjectList> createState() => _FilterableProjectListState();
}

class _FilterableProjectListState extends State<_FilterableProjectList> {
  String _selectedTag = '';

  @override
  Widget build(BuildContext context) {
    final tagSources = _allProjectTagSources(widget.projects);
    final tags =
        tagSources.map((source) => source.value).toList(growable: false);
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
              for (final tagSource in tagSources) ...[
                const SizedBox(width: 8),
                _FilterChip(
                  tagSource.value,
                  selected: _selectedTag == tagSource.value,
                  onTap: () => setState(() => _selectedTag = tagSource.value),
                  translationEnabled: widget.enableAutoTranslation,
                  projectId: tagSource.projectId,
                  field: tagSource.field,
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
            widget.socialController,
            widget.enableAutoTranslation,
            key: _projectRowKey(project),
          ),
      ],
    );
  }
}

class _ProjectRow extends StatelessWidget {
  const _ProjectRow(
    this.data,
    this.institutionId,
    this.onTap,
    this.socialController,
    this.enableAutoTranslation, {
    super.key,
  });
  final Map<String, Object?> data;
  final String institutionId;
  final void Function(String institutionId, String projectId)? onTap;
  final SocialController? socialController;
  final bool enableAutoTranslation;
  @override
  Widget build(BuildContext context) {
    final id = _text(data, const ['projectId', 'id']);
    final price = _number(data['price']);
    final original = _number(data['originalPrice']);
    final tagSources = _projectTagSources(data);
    final tags =
        tagSources.map((source) => source.value).toList(growable: false);
    final projectId = _projectId(data);
    final name = _firstTextSource(data, const ['projectName', 'name']);
    final description = _firstTextSource(data, const ['description']);
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
                _InstitutionProjectText(
                  enabled: enableAutoTranslation,
                  projectId: projectId,
                  field: name?.field ?? '',
                  source: name?.value ?? '',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 5),
                _InstitutionProjectText(
                  enabled: enableAutoTranslation,
                  projectId: projectId,
                  field: description?.field ?? '',
                  source: description?.value ?? '',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: Color(0xff888888)),
                ),
                if (tags.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 5,
                    runSpacing: 4,
                    children: tagSources
                        .take(3)
                        .map((tag) => _ProjectTag(
                              tag.value,
                              enabled: enableAutoTranslation,
                              projectId: projectId,
                              field: tag.field,
                            ))
                        .toList(growable: false),
                  ),
                ],
                if (price > 0) ...[
                  const SizedBox(height: 6),
                  Row(children: [
                    Text('\$${catalogMoney(price)}',
                        style: const TextStyle(fontWeight: FontWeight.w700)),
                    if (original > price) ...[
                      const SizedBox(width: 6),
                      Text('\$${catalogMoney(original)}',
                          style: TextStyle(
                              color: Theme.of(context)
                                  .colorScheme
                                  .onSurfaceVariant,
                              decoration: TextDecoration.lineThrough))
                    ]
                  ])
                ],
              ])),
          if (id.isNotEmpty && socialController != null)
            FavoriteActionButton(
              controller: socialController!,
              type: FavoriteTargetType.project,
              targetId: id,
              targetName: _text(data, const ['projectName', 'name']),
              targetImage: _text(data, const ['coverImage']),
            ),
        ]),
      ),
    );
  }
}

class _ProjectTag extends StatelessWidget {
  const _ProjectTag(
    this.label, {
    required this.enabled,
    required this.projectId,
    required this.field,
  });
  final String label;
  final bool enabled;
  final String projectId;
  final String field;

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.primaryContainer,
          borderRadius: BorderRadius.circular(5),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
          child: _InstitutionProjectText(
            enabled: enabled,
            projectId: projectId,
            field: field,
            source: label,
            style: TextStyle(
              fontSize: 10,
              color: Theme.of(context).colorScheme.onPrimaryContainer,
            ),
          ),
        ),
      );
}

class _InstitutionProjectText extends StatefulWidget {
  const _InstitutionProjectText({
    required this.enabled,
    required this.projectId,
    required this.field,
    required this.source,
    this.maxLines,
    this.overflow,
    this.style,
  });

  final bool enabled;
  final String projectId;
  final String field;
  final String source;
  final int? maxLines;
  final TextOverflow? overflow;
  final TextStyle? style;

  @override
  State<_InstitutionProjectText> createState() =>
      _InstitutionProjectTextState();
}

class _InstitutionProjectTextState extends State<_InstitutionProjectText> {
  AutoTranslationRequest? _request;

  @override
  void initState() {
    super.initState();
    _updateRequest();
  }

  @override
  void didUpdateWidget(_InstitutionProjectText oldWidget) {
    super.didUpdateWidget(oldWidget);
    _updateRequest();
  }

  void _updateRequest() {
    _request = _updatedProjectRequest(
      _request,
      enabled: widget.enabled,
      projectId: widget.projectId,
      field: widget.field,
      source: widget.source,
    );
  }

  @override
  Widget build(BuildContext context) {
    final request = _request;
    if (request == null) {
      return Text(
        widget.source,
        maxLines: widget.maxLines,
        overflow: widget.overflow,
        style: widget.style,
      );
    }
    return AutoTranslatedText(
      request: request,
      maxLines: widget.maxLines,
      overflow: widget.overflow,
      style: widget.style,
    );
  }
}

class _DoctorRow extends StatelessWidget {
  const _DoctorRow(this.data, this.onTap, this.socialController);
  final Map<String, Object?> data;
  final ValueChanged<String>? onTap;
  final SocialController? socialController;
  @override
  Widget build(BuildContext context) {
    final id = _text(data, const ['id', 'doctorId']);
    final avatar = _text(data, const ['avatar']);
    final phone = _text(data, const ['phone', 'contactPhone', 'telephone']);
    return ListTile(
        contentPadding: const EdgeInsets.symmetric(vertical: 6),
        onTap: id.isEmpty || onTap == null ? null : () => onTap!(id),
        leading: CircleAvatar(
            radius: 30,
            foregroundImage: avatar.isEmpty
                ? null
                : optimizedNetworkImageProvider(
                    context,
                    avatar,
                    width: 60,
                    height: 60,
                  ),
            child: avatar.isEmpty ? const Icon(Icons.person_outline) : null),
        title: Text(_text(data, const ['name']),
            style: const TextStyle(fontWeight: FontWeight.w800)),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_text(data, const ['title', 'bio']),
                maxLines: 2, overflow: TextOverflow.ellipsis),
            if (phone.isNotEmpty)
              GestureDetector(
                onTap: () async {
                  await Clipboard.setData(ClipboardData(text: phone));
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                    content: Text(
                        context.localized('已复制医生电话', 'Doctor phone copied')),
                  ));
                },
                child: Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Row(mainAxisSize: MainAxisSize.min, children: [
                    const Icon(Icons.phone_outlined, size: 15),
                    const SizedBox(width: 4),
                    Flexible(child: Text(phone)),
                    const SizedBox(width: 4),
                    const Icon(Icons.copy_rounded, size: 14),
                  ]),
                ),
              ),
          ],
        ),
        trailing: id.isEmpty || socialController == null
            ? null
            : FavoriteActionButton(
                controller: socialController!,
                type: FavoriteTargetType.doctor,
                targetId: id,
                targetName: _text(data, const ['name']),
                targetImage: avatar,
              ));
  }
}

class _Empty extends StatelessWidget {
  const _Empty(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => SizedBox(
      height: 110,
      child: Center(
          child: Text(
        text,
        style: TextStyle(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      )));
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
  return _projectTagSources(project)
      .map((source) => source.value)
      .toList(growable: false);
}

typedef _TextSource = ({String field, String value});
typedef _ProjectTagSource = ({String projectId, String field, String value});

_TextSource? _firstTextSource(
  Map<String, Object?> data,
  List<String> keys,
) {
  for (final key in keys) {
    final value = data[key]?.toString().trim() ?? '';
    if (value.isNotEmpty && value.toLowerCase() != 'null') {
      return (field: key, value: value);
    }
  }
  return null;
}

String _projectId(Map<String, Object?> project) {
  final direct = _text(project, const ['projectId', 'id']);
  if (direct.isNotEmpty) return direct;
  return _text(_map(project['project']), const ['projectId', 'id']);
}

Key _projectRowKey(Map<String, Object?> project) {
  final id = _projectId(project);
  return id.isEmpty
      ? ObjectKey(project)
      : ValueKey<String>('institution-project:$id');
}

List<_ProjectTagSource> _projectTagSources(
  Map<String, Object?> project,
) {
  final projectId = _projectId(project);
  final tags = <_ProjectTagSource>[];
  final seen = <String>{};
  void collect(Map<String, Object?> source) {
    for (final key in const [
      'tags',
      'projectTags',
      'tagNames',
      'categories',
      'category',
    ]) {
      final value = source[key];
      final values = <String>[];
      if (value is List) {
        for (final item in value) {
          if (item is Map) {
            final tag = _text(
              _map(item),
              const ['name', 'label', 'tagName', 'categoryName'],
            );
            if (tag.isNotEmpty) values.add(tag);
          } else {
            values.addAll(_split(item));
          }
        }
      } else {
        values.addAll(_split(value));
      }
      for (final tag in values) {
        if (tag.isNotEmpty && seen.add(tag)) {
          tags.add((projectId: projectId, field: key, value: tag));
        }
      }
    }
  }

  final nested = _map(project['project']);
  if (nested.isNotEmpty) collect(nested);
  collect(project);
  return tags;
}

List<_ProjectTagSource> _allProjectTagSources(
  List<Map<String, Object?>> projects,
) {
  final seen = <String>{};
  return [
    for (final project in projects)
      for (final source in _projectTagSources(project))
        if (seen.add(source.value)) source,
  ];
}

AutoTranslationRequest? _projectRequest({
  required String projectId,
  required String field,
  required String source,
}) {
  if (projectId.isEmpty || field.isEmpty || source.isEmpty) return null;
  return AutoTranslationRequest(
    contentType: 'project',
    contentId: 'project:$projectId',
    field: field,
    sourceText: source,
  );
}

AutoTranslationRequest? _updatedProjectRequest(
  AutoTranslationRequest? previous, {
  required bool enabled,
  required String projectId,
  required String field,
  required String source,
}) {
  if (!enabled || projectId.isEmpty || field.isEmpty || source.isEmpty) {
    return null;
  }
  final contentId = 'project:$projectId';
  if (previous != null &&
      previous.contentId == contentId &&
      previous.field == field &&
      previous.sourceText == source) {
    return previous;
  }
  return _projectRequest(
    projectId: projectId,
    field: field,
    source: source,
  );
}

AutoTranslationRequest _institutionRequest({
  required String contentId,
  required String field,
  required String source,
}) =>
    AutoTranslationRequest(
      contentType: 'institution',
      contentId: contentId,
      field: field,
      sourceText: source,
    );

AutoTranslationRequest? _updatedInstitutionRequest(
  AutoTranslationRequest? previous, {
  required bool enabled,
  required String contentId,
  required String field,
  required String source,
}) {
  if (!enabled || contentId.isEmpty || field.isEmpty || source.isEmpty) {
    return null;
  }
  if (previous != null &&
      previous.contentId == contentId &&
      previous.field == field &&
      previous.sourceText == source) {
    return previous;
  }
  return _institutionRequest(
    contentId: contentId,
    field: field,
    source: source,
  );
}

double _number(Object? value) =>
    value is num ? value.toDouble() : double.tryParse('$value') ?? 0;
double _firstNumber(Map<String, Object?> data, List<String> keys) {
  for (final key in keys) {
    final value = data[key];
    final parsed = value is num ? value.toDouble() : double.tryParse('$value');
    if (parsed != null) return parsed;
  }
  return 0;
}

int _count(Map<String, Object?> data, List<String> keys) {
  for (final key in keys) {
    final value = data[key];
    final parsed = value is num ? value.toInt() : int.tryParse('$value');
    if (parsed != null) return parsed;
  }
  return 0;
}

bool _bool(Object? value) =>
    value == true || value == 1 || '$value'.toLowerCase() == 'true';
