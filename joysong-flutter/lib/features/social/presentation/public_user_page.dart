import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/diary_detail_page.dart';
import 'package:joysong_flutter/features/social/presentation/diary_media_grid.dart';
import 'package:joysong_flutter/features/social/presentation/report_action_button.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

final class PublicUserPage extends StatefulWidget {
  const PublicUserPage({
    required this.userId,
    required this.repository,
    required this.socialController,
    this.currentUserId = '',
    this.onMessage,
    this.onOpenProject,
    this.onOpenDoctor,
    this.onOpenInstitution,
    super.key,
  });

  final String userId;
  final SocialRepository repository;
  final SocialController socialController;
  final String currentUserId;
  final ValueChanged<PublicUserProfile>? onMessage;
  final void Function(String institutionId, String projectId)? onOpenProject;
  final ValueChanged<String>? onOpenDoctor;
  final ValueChanged<String>? onOpenInstitution;

  @override
  State<PublicUserPage> createState() => _PublicUserPageState();
}

final class _PublicUserPageState extends State<PublicUserPage> {
  PublicUserProfile? _profile;
  List<Diary> _diaries = const [];
  Object? _error;
  bool _loading = true;

  bool get _english => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await Future.wait<Object>([
        widget.repository.getPublicUserProfile(widget.userId),
        widget.repository.getUserDiaries(widget.userId),
      ]);
      if (!mounted) return;
      setState(() {
        _profile = results[0] as PublicUserProfile;
        _diaries = results[1] as List<Diary>;
      });
      unawaited(widget.socialController.loadReportedStatus(
        ReportTargetType.user,
        widget.userId,
      ));
    } catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final profile = _profile;
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.transparent,
        title: Text(_english ? 'Profile' : '用户主页'),
        actions: [
          if (profile != null)
            ReportActionButton(
              key: const Key('user-report'),
              controller: widget.socialController,
              type: ReportTargetType.user,
              targetId: widget.userId,
            ),
        ],
      ),
      body: _loading && profile == null
          ? const Center(child: CircularProgressIndicator())
          : _error != null && profile == null
              ? _ErrorState(english: _english, onRetry: _load)
              : RefreshIndicator(
                  onRefresh: _load,
                  child: CustomScrollView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    slivers: [
                      SliverToBoxAdapter(
                          child: _ProfileHeader(
                        profile: profile!,
                        english: _english,
                        onMessage: widget.userId == widget.currentUserId ||
                                widget.onMessage == null
                            ? null
                            : () => widget.onMessage!(profile),
                      )),
                      SliverPadding(
                        padding: const EdgeInsets.fromLTRB(16, 20, 16, 10),
                        sliver: SliverToBoxAdapter(
                          child: Text(
                            _english ? 'Published diaries' : '公开日记',
                            style: Theme.of(context).textTheme.titleLarge,
                          ),
                        ),
                      ),
                      if (_diaries.isEmpty)
                        SliverFillRemaining(
                          hasScrollBody: false,
                          child: _EmptyState(english: _english),
                        )
                      else
                        SliverPadding(
                          padding: const EdgeInsets.fromLTRB(16, 0, 16, 28),
                          sliver: SliverList.separated(
                            itemCount: _diaries.length,
                            separatorBuilder: (_, __) =>
                                const SizedBox(height: 12),
                            itemBuilder: (_, index) => _PublicDiaryCard(
                              diary: _diaries[index],
                              onTap: () => _openDiary(_diaries[index]),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
    );
  }

  void _openDiary(Diary diary) {
    Navigator.of(context).push<void>(MaterialPageRoute(
      builder: (_) => DiaryDetailPage(
        controller: widget.socialController,
        diary: diary,
        onProjectTap: widget.onOpenProject,
        onDoctorTap: widget.onOpenDoctor,
        onInstitutionTap: widget.onOpenInstitution,
      ),
    ));
  }
}

final class _ProfileHeader extends StatelessWidget {
  const _ProfileHeader({
    required this.profile,
    required this.english,
    this.onMessage,
  });

  final PublicUserProfile profile;
  final bool english;
  final VoidCallback? onMessage;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
      color: Colors.white,
      child: Column(children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          CircleAvatar(
            radius: 42,
            foregroundImage:
                profile.avatar.isEmpty ? null : NetworkImage(profile.avatar),
            child: Text(profile.nickname.isEmpty
                ? '?'
                : profile.nickname.characters.first),
          ),
          const SizedBox(width: 16),
          Expanded(
              child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(profile.nickname,
                  style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 6),
              Text(profile.bio.isEmpty
                  ? (english ? 'No bio yet' : '这个用户还没有填写简介')
                  : profile.bio),
              if (profile.city.isNotEmpty) ...[
                const SizedBox(height: 8),
                Row(children: [
                  const Icon(Icons.location_on_outlined, size: 17),
                  const SizedBox(width: 4),
                  Text(profile.city),
                ]),
              ],
            ],
          )),
        ]),
        const SizedBox(height: 22),
        Row(children: [
          _Stat(value: profile.diaryCount, label: english ? 'Diaries' : '日记'),
          _Stat(
              value: profile.followingCount,
              label: english ? 'Following' : '关注'),
          _Stat(
              value: profile.followerCount,
              label: english ? 'Followers' : '粉丝'),
        ]),
        if (onMessage != null) ...[
          const SizedBox(height: 18),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              style: OutlinedButton.styleFrom(
                foregroundColor: colors.onSurface,
                side: BorderSide(color: colors.outline),
                textStyle: const TextStyle(fontWeight: FontWeight.w600),
              ),
              onPressed: onMessage,
              icon: const Icon(Icons.chat_bubble_outline),
              label: Text(english ? 'Send message' : '发私信'),
            ),
          ),
        ],
      ]),
    );
  }
}

final class _Stat extends StatelessWidget {
  const _Stat({required this.value, required this.label});
  final int value;
  final String label;

  @override
  Widget build(BuildContext context) => Expanded(
        child: Column(children: [
          Text('$value', style: Theme.of(context).textTheme.titleLarge),
          Text(label, style: Theme.of(context).textTheme.bodySmall),
        ]),
      );
}

final class _PublicDiaryCard extends StatelessWidget {
  const _PublicDiaryCard({required this.diary, required this.onTap});
  final Diary diary;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final hasMedia = diary.images.isNotEmpty ||
        diary.beforeImages.isNotEmpty ||
        diary.afterImages.isNotEmpty;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(children: [
          if (hasMedia)
            DiaryMediaGrid(
              images: diary.images,
              beforeImages: diary.beforeImages,
              afterImages: diary.afterImages,
              height: 220,
            ),
          Padding(
            padding: const EdgeInsets.all(14),
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(diary.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 7),
              Text(diary.content, maxLines: 2, overflow: TextOverflow.ellipsis),
              const SizedBox(height: 12),
              Row(children: [
                const Icon(Icons.favorite_border, size: 17),
                Text(' ${diary.likeCount}   '),
                const Icon(Icons.chat_bubble_outline, size: 17),
                Text(' ${diary.commentCount}'),
              ]),
            ]),
          ),
        ]),
      ),
    );
  }
}

final class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.english, required this.onRetry});
  final bool english;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) => Center(
          child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.cloud_off_outlined, size: 48),
          const SizedBox(height: 12),
          Text(english ? 'Unable to load profile' : '用户主页加载失败'),
          TextButton(onPressed: onRetry, child: Text(english ? 'Retry' : '重试')),
        ],
      ));
}

final class _EmptyState extends StatelessWidget {
  const _EmptyState({required this.english});
  final bool english;

  @override
  Widget build(BuildContext context) => Center(
          child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          const Icon(Icons.auto_stories_outlined, size: 48),
          const SizedBox(height: 12),
          Text(english ? 'No published diaries yet' : '还没有公开日记'),
        ]),
      ));
}
