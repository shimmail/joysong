import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/core/translation/rich_translation_validator.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_review_section.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_content_card.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/presentation/favorite_action_button.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

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
    this.onViewAllReviews,
    this.onAiChat,
    this.socialController,
    this.enableAutoTranslation = false,
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
  final VoidCallback? onViewAllReviews;
  final VoidCallback? onAiChat;
  final SocialController? socialController;
  final bool enableAutoTranslation;

  @override
  Widget build(BuildContext context) {
    final raw = item.raw;
    final project = _map(raw['project']);
    final ip = _map(raw['institutionProject']);
    final institution = _map(raw['institution']);
    final institutionProjects = _maps(raw['institutionProjects']);
    final diaries = _maps(raw['diaries']);
    final doctors = _maps(raw['doctors']);
    final reviews = _maps(raw['reviews']);
    final isInstitutionProject = ip.isNotEmpty;
    final sources = [ip, project, raw];
    final name = _text(sources, const ['name', 'projectName'], item.title);
    final slogan = _text(sources, const ['slogan'], '');
    final eligibleDescription =
        _text(sources, const ['description', 'summary'], '');
    final description =
        eligibleDescription.isEmpty ? item.subtitle : eligibleDescription;
    final detailContent =
        _text(sources, const ['detailContent', 'content'], '');
    final price = _number(sources, const ['price', 'referencePrice']);
    final originalPrice = _number(sources, const ['originalPrice']);
    final rating = _number(sources, const ['rating', 'averageRating']);
    final reviewCount = _integer(sources, const ['reviewCount', 'ratingCount']);
    final caseCount = _integer(sources, const ['caseCount', 'casesCount']);
    final institutionSources = [institution, ip, raw];
    final institutionName = _text([institution], const ['name'], '');
    final institutionAddress = [
      _text(institutionSources, const ['city', 'institutionCity'], ''),
      _text(institutionSources, const ['address', 'institutionAddress'], ''),
    ].where((value) => value.isNotEmpty).join(' · ');
    final institutionPhone = _text(
      institutionSources,
      const ['contactPhone', 'institutionPhone', 'phone', 'telephone'],
      '',
    );
    final tags = <(String, String)>[
      for (final value in _tokens(sources, const ['categoryTags']))
        ('category', value),
      for (final value in _tokens(sources, const ['tags'])) ('tags', value),
    ];
    final seenTags = <String>{};
    final visibleTags =
        tags.where((entry) => seenTags.add(entry.$2)).toList(growable: false);
    final images = <String>{
      ..._tokens(sources, const ['images']),
      ..._tokens(sources, const ['coverImage']),
      if (item.imageUrl.isNotEmpty) item.imageUrl,
    }.toList(growable: false);
    final keys = List.generate(5, (_) => GlobalKey());
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
            ? const ['Guide', 'Doctors', 'Diaries', 'Reviews', 'Institution']
            : const ['项目百科', '可预约医生', '用户日记', '用户评价', '所属机构'])
        : (context.isEnglish
            ? const ['Guide', 'Institutions', 'Diaries', 'Reviews']
            : const ['项目攻略', '认证机构', '用户日记', '用户评价']);

    return Column(children: [
      Expanded(
        child: CustomScrollView(slivers: [
          SliverToBoxAdapter(
            child: _ProjectHero(
              images: images,
              name: name,
              slogan: slogan,
              subtitle: isInstitutionProject
                  ? institutionName
                  : context.localized('参考均价', 'Reference price'),
              price: price,
              originalPrice: originalPrice,
              contentId: 'project:${item.id}',
              translateSubtitle: isInstitutionProject,
              enableAutoTranslation: enableAutoTranslation,
            ),
          ),
          if (isInstitutionProject)
            SliverToBoxAdapter(
              child: _ProjectFacts(
                rating: rating,
                reviewCount: reviewCount,
                caseCount: caseCount,
                address: institutionAddress,
                phone: institutionPhone,
                contentId: 'project:${item.id}',
                enableAutoTranslation: enableAutoTranslation,
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
                  if (visibleTags.isNotEmpty) ...[
                    Wrap(
                      spacing: 7,
                      runSpacing: 7,
                      children: [
                        for (final tag in visibleTags)
                          _TinyTag(
                            tag.$2,
                            field: tag.$1,
                            contentId: 'project:${item.id}',
                            enableAutoTranslation: enableAutoTranslation,
                          ),
                      ],
                    ),
                    const SizedBox(height: 12),
                  ],
                  if (enableAutoTranslation && eligibleDescription.isNotEmpty)
                    AutoTranslatedText(
                      request: _projectRequest(
                        contentId: 'project:${item.id}',
                        field: 'description',
                        source: description,
                      ),
                      style: const TextStyle(
                        height: 1.55,
                        color: Color(0xff666666),
                      ),
                    )
                  else
                    Text(
                      description.isEmpty
                          ? context.localized('暂无项目介绍', 'No project overview')
                          : description,
                      style: const TextStyle(
                        height: 1.55,
                        color: Color(0xff666666),
                      ),
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
                                socialController,
                                enableAutoTranslation,
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
                ownerType: 'project',
                ownerId: item.id,
              ),
            ),
          ),
          if (isInstitutionProject)
            SliverToBoxAdapter(
              child: CatalogSection(
                key: keys[4],
                title: context.localized('所属机构', 'Institution'),
                child: _InstitutionRow(
                  institution,
                  onInstitutionTap,
                  socialController: socialController,
                  enableAutoTranslation: enableAutoTranslation,
                  autoTranslationContentId: 'project:${item.id}',
                ),
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
        onTertiary: onAiChat,
      ),
    ]);
  }

  void _showDetails(BuildContext context, String name, String content) {
    final contentId = 'project:${item.id}';
    final contentIsHtml = _looksLikeHtml(content);
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => FractionallySizedBox(
        heightFactor: .82,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
          children: [
            if (enableAutoTranslation)
              AutoTranslatedText(
                request: _projectRequest(
                  contentId: contentId,
                  field: 'name',
                  source: name,
                ),
                style: Theme.of(context)
                    .textTheme
                    .titleLarge
                    ?.copyWith(fontWeight: FontWeight.w800),
              )
            else
              Text(name,
                  style: Theme.of(context)
                      .textTheme
                      .titleLarge
                      ?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 16),
            if (enableAutoTranslation)
              AutoTranslationBuilder(
                request: _projectRequest(
                  contentId: contentId,
                  field: 'content',
                  source: content,
                  contentType: contentIsHtml ? 'project_html' : 'project',
                  validator:
                      contentIsHtml ? preservesRichContentStructure : null,
                ),
                builder: (_, visibleContent) => RichContentView(
                  content: visibleContent,
                  textStyle: const TextStyle(height: 1.65),
                ),
              )
            else
              RichContentView(
                content: content,
                textStyle: const TextStyle(height: 1.65),
              ),
          ],
        ),
      ),
    );
  }
}

class _ProjectHero extends StatelessWidget {
  const _ProjectHero({
    required this.images,
    required this.name,
    required this.slogan,
    required this.subtitle,
    required this.price,
    required this.originalPrice,
    required this.contentId,
    required this.translateSubtitle,
    required this.enableAutoTranslation,
  });

  final List<String> images;
  final String name;
  final String slogan;
  final String subtitle;
  final double? price;
  final double? originalPrice;
  final String contentId;
  final bool translateSubtitle;
  final bool enableAutoTranslation;

  @override
  Widget build(BuildContext context) {
    Widget hero(
        String visibleName, String visibleSlogan, String visibleSubtitle) {
      return CatalogHero(
        images: images,
        title: visibleName,
        eyebrow: visibleSlogan,
        subtitle: visibleSubtitle,
        price: price,
        originalPrice: originalPrice,
      );
    }

    if (!enableAutoTranslation) return hero(name, slogan, subtitle);
    final nameRequest = _projectRequest(
      contentId: contentId,
      field: 'name',
      source: name,
    );
    final sloganRequest = slogan.isEmpty
        ? null
        : _projectRequest(
            contentId: contentId,
            field: 'slogan',
            source: slogan,
          );
    final subtitleRequest = !translateSubtitle || subtitle.isEmpty
        ? null
        : _projectRequest(
            contentId: contentId,
            field: 'institutionName',
            source: subtitle,
          );

    return AutoTranslationBuilder(
      request: nameRequest,
      builder: (_, visibleName) {
        Widget withSubtitle(String visibleSlogan) {
          if (subtitleRequest == null) {
            return hero(visibleName, visibleSlogan, subtitle);
          }
          return AutoTranslationBuilder(
            request: subtitleRequest,
            builder: (_, visibleSubtitle) =>
                hero(visibleName, visibleSlogan, visibleSubtitle),
          );
        }

        if (sloganRequest == null) return withSubtitle(slogan);
        return AutoTranslationBuilder(
          request: sloganRequest,
          builder: (_, visibleSlogan) => withSubtitle(visibleSlogan),
        );
      },
    );
  }
}

class _ProjectFacts extends StatelessWidget {
  const _ProjectFacts({
    required this.rating,
    required this.reviewCount,
    required this.caseCount,
    required this.address,
    required this.phone,
    required this.contentId,
    required this.enableAutoTranslation,
  });
  final double? rating;
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
                child: _ProjectStat(
              value: rating != null && rating! > 0
                  ? rating!.toStringAsFixed(1)
                  : '—',
              label: context.localized('评分', 'Rating'),
              showStar: true,
            )),
            Expanded(
                child: _ProjectStat(
                    value: '$reviewCount',
                    label: context.localized('评价', 'Reviews'))),
            Expanded(
                child: _ProjectStat(
                    value: '$caseCount',
                    label: context.localized('案例', 'Cases'))),
          ]),
          if (address.isNotEmpty)
            _ProjectCopyLine(
              icon: Icons.location_on_outlined,
              label: context.localized('机构位置', 'Location'),
              value: address,
              field: 'address',
              contentId: contentId,
              enableAutoTranslation: enableAutoTranslation,
            ),
          if (phone.isNotEmpty)
            _ProjectCopyLine(
              icon: Icons.phone_outlined,
              label: context.localized('机构电话', 'Institution phone'),
              value: phone,
            ),
        ]),
      );
}

class _ProjectStat extends StatelessWidget {
  const _ProjectStat(
      {required this.value, required this.label, this.showStar = false});
  final String value;
  final String label;
  final bool showStar;
  @override
  Widget build(BuildContext context) => Column(children: [
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          if (showStar) ...[
            const Icon(Icons.star_rounded, size: 18, color: Color(0xffffa000)),
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

class _ProjectCopyLine extends StatelessWidget {
  const _ProjectCopyLine({
    required this.icon,
    required this.label,
    required this.value,
    this.field = '',
    this.contentId = '',
    this.enableAutoTranslation = false,
  });
  final IconData icon;
  final String label;
  final String value;
  final String field;
  final String contentId;
  final bool enableAutoTranslation;
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
              child: enableAutoTranslation
                  ? AutoTranslatedText(
                      request: _projectRequest(
                        contentId: contentId,
                        field: field,
                        source: value,
                      ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    )
                  : Text(
                      value,
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
  const _TinyTag(
    this.label, {
    required this.field,
    required this.contentId,
    required this.enableAutoTranslation,
  });
  final String label;
  final String field;
  final String contentId;
  final bool enableAutoTranslation;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
        decoration: BoxDecoration(
          color: const Color(0xffeeeeee),
          borderRadius: BorderRadius.circular(5),
        ),
        child: enableAutoTranslation
            ? AutoTranslatedText(
                request: _projectRequest(
                  contentId: contentId,
                  field: field,
                  source: label,
                ),
                style: const TextStyle(fontSize: 12),
              )
            : Text(label, style: const TextStyle(fontSize: 12)),
      );
}

class _InstitutionProjectRow extends StatelessWidget {
  const _InstitutionProjectRow(
    this.data,
    this.projectId,
    this.onTap,
    this.socialController,
    this.enableAutoTranslation,
  );
  final Map<String, Object?> data;
  final String projectId;
  final void Function(String institutionId, String projectId)? onTap;
  final SocialController? socialController;
  final bool enableAutoTranslation;
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
      socialController: socialController,
      enableAutoTranslation: enableAutoTranslation,
      autoTranslationContentId: 'project:$projectId',
    );
  }
}

class _InstitutionRow extends StatelessWidget {
  const _InstitutionRow(
    this.data,
    this.onTap, {
    this.title,
    this.socialController,
    this.enableAutoTranslation = false,
    this.autoTranslationContentId = '',
  });
  final Map<String, Object?> data;
  final ValueChanged<String>? onTap;
  final String? title;
  final SocialController? socialController;
  final bool enableAutoTranslation;
  final String autoTranslationContentId;
  @override
  Widget build(BuildContext context) {
    final id = _text([data], const ['institutionId', 'id'], '');
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
              if (enableAutoTranslation)
                AutoTranslatedText(
                  request: _projectRequest(
                    contentId: autoTranslationContentId,
                    field: 'institutionName',
                    source: title ?? _text([data], const ['name'], ''),
                  ),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                  ),
                )
              else
                Text(title ?? _text([data], const ['name'], ''),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.w800)),
              const SizedBox(height: 5),
              if (enableAutoTranslation)
                AutoTranslatedText(
                  request: _projectRequest(
                    contentId: autoTranslationContentId,
                    field: 'address',
                    source: _text([data], const ['address', 'city'], ''),
                  ),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: Color(0xff777777)),
                )
              else
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
          if (id.isNotEmpty && socialController != null)
            FavoriteActionButton(
              controller: socialController!,
              type: FavoriteTargetType.institution,
              targetId: id,
              targetName: title ?? _text([data], const ['name'], ''),
              targetImage: _text([data], const ['coverImage', 'logo'], ''),
            ),
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
    final phone =
        _text([data], const ['phone', 'contactPhone', 'telephone'], '');
    return ListTile(
      onTap: id.isEmpty || onTap == null ? null : () => onTap!(id),
      leading: CircleAvatar(
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
      title: Text(_text([data], const ['name'], '')),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(_text([data], const ['title'], '')),
          if (phone.isNotEmpty)
            GestureDetector(
              onTap: () async {
                await Clipboard.setData(ClipboardData(text: phone));
                if (!context.mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                  content:
                      Text(context.localized('已复制医生电话', 'Doctor phone copied')),
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
    );
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

int _integer(List<Map<String, Object?>> sources, List<String> keys) {
  for (final source in sources) {
    for (final key in keys) {
      final value = source[key];
      final parsed = value is num ? value.toInt() : int.tryParse('$value');
      if (parsed != null) return parsed;
    }
  }
  return 0;
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

AutoTranslationRequest _projectRequest({
  required String contentId,
  required String field,
  required String source,
  String contentType = 'project',
  TranslationValidator? validator,
}) {
  return AutoTranslationRequest(
    contentType: contentType,
    contentId: contentId,
    field: field,
    sourceText: source,
    validator: validator,
  );
}

bool _looksLikeHtml(String value) =>
    RegExp(r'<\/?[a-z][^>]*>', caseSensitive: false).hasMatch(value);
