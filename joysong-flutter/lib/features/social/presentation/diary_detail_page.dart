import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:joysong_flutter/features/social/data/comment_collapse_store.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/presentation/diary_media_grid.dart';
import 'package:joysong_flutter/features/social/presentation/diary_share.dart';
import 'package:joysong_flutter/features/social/presentation/favorite_action_button.dart';
import 'package:joysong_flutter/features/social/presentation/report_action_button.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

final class DiaryDetailPage extends StatefulWidget {
  const DiaryDetailPage({
    required this.controller,
    required this.diary,
    this.onAuthorTap,
    this.onProjectTap,
    this.onDoctorTap,
    this.onInstitutionTap,
    this.collapseStore,
    super.key,
  });

  final SocialController controller;
  final Diary diary;
  final VoidCallback? onAuthorTap;
  final void Function(String institutionId, String projectId)? onProjectTap;
  final ValueChanged<String>? onDoctorTap;
  final ValueChanged<String>? onInstitutionTap;
  final CommentCollapseStore? collapseStore;

  @override
  State<DiaryDetailPage> createState() => _DiaryDetailPageState();
}

final class _DiaryDetailPageState extends State<DiaryDetailPage> {
  final _commentController = TextEditingController();
  final _commentFocusNode = FocusNode();
  final _scrollController = ScrollController();
  final _expandedComments = <String>{};
  final _collapsedComments = <String>{};
  late final CommentCollapseStore _collapseStore;
  Comment? _replyTarget;
  int _replyTrigger = 0;

  @override
  void initState() {
    super.initState();
    _collapseStore = widget.collapseStore ?? const SecureCommentCollapseStore();
    widget.controller.seedLikeStatus(
      LikeTargetType.diary,
      widget.diary.id,
      EngagementStatus(
        active: widget.diary.isLiked,
        count: widget.diary.likeCount,
      ),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      unawaited(_loadCommentThread());
      unawaited(_restoreCollapsedComments());
      unawaited(
        widget.controller.loadReportedStatus(
          ReportTargetType.diary,
          widget.diary.id,
        ),
      );
    });
  }

  @override
  void dispose() {
    _commentController.dispose();
    _commentFocusNode.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  bool get _english => Localizations.localeOf(context).languageCode == 'en';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_english ? 'Diary details' : '日记详情'),
        actions: [
          IconButton(
            key: const Key('diary-share'),
            tooltip: _english ? 'Share diary' : '分享日记',
            onPressed: () => shareDiary(
              context,
              widget.diary,
              repository: widget.controller.repository,
            ),
            icon: const Icon(Icons.share_outlined),
          ),
          ReportActionButton(
            key: const Key('diary-report'),
            controller: widget.controller,
            type: ReportTargetType.diary,
            targetId: widget.diary.id,
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: AnimatedBuilder(
                animation: widget.controller,
                builder: (context, _) => RefreshIndicator(
                  onRefresh: () async {
                    await _loadCommentThread();
                  },
                  child: _buildContent(),
                ),
              ),
            ),
            _buildComposer(),
          ],
        ),
      ),
    );
  }

  Widget _buildContent() {
    final theme = Theme.of(context);
    final comments = widget.controller.commentsFor(widget.diary.id);
    final loading = widget.controller.isBusy(
      'comments:load:${widget.diary.id}',
    );
    return ListView(
      controller: _scrollController,
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 24),
      children: [
        _DiaryHeader(
          diary: widget.diary,
          controller: widget.controller,
          english: _english,
          onAuthorTap: widget.onAuthorTap,
        ),
        if (_hasAssociations) ...[
          const SizedBox(height: 18),
          _DiaryAssociations(
            diary: widget.diary,
            english: _english,
            onProjectTap: widget.onProjectTap,
            onDoctorTap: widget.onDoctorTap,
            onInstitutionTap: widget.onInstitutionTap,
          ),
        ],
        const SizedBox(height: 16),
        _buildDiaryActions(),
        const SizedBox(height: 24),
        Text(
          _english ? 'Comments' : '评论',
          style: theme.textTheme.titleMedium,
        ),
        const SizedBox(height: 12),
        if (loading && comments.isEmpty)
          const Padding(
            padding: EdgeInsets.all(28),
            child: Center(child: CircularProgressIndicator()),
          )
        else if (comments.isEmpty)
          _EmptyComments(
            english: _english,
            failed: widget.controller.errorMessage != null,
            onRetry: () => widget.controller.loadComments(
              widget.diary.id,
              refresh: true,
            ),
          )
        else
          for (final comment in comments) ...[
            _buildComment(comment),
            const Divider(height: 24),
          ],
        if (loading && comments.isNotEmpty)
          const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      ],
    );
  }

  bool get _hasAssociations {
    final diary = widget.diary;
    return diary.projectName.trim().isNotEmpty ||
        diary.projectId.trim().isNotEmpty ||
        diary.doctorName.trim().isNotEmpty ||
        diary.doctorId.trim().isNotEmpty ||
        diary.institutionName.trim().isNotEmpty ||
        diary.institutionId.trim().isNotEmpty;
  }

  Widget _buildDiaryActions() {
    final initial = EngagementStatus(
      active: widget.diary.isLiked,
      count: widget.diary.likeCount,
    );
    final status = widget.controller.likeStatus(
          LikeTargetType.diary,
          widget.diary.id,
        ) ??
        initial;
    final busy = widget.controller.isBusy(
      'engagement:diary:${widget.diary.id}',
    );
    final inactiveColor = Theme.of(context).colorScheme.onSurfaceVariant;
    const likedColor = Color(0xffe53935);
    const savedColor = Color(0xffffc107);
    final loadedCommentCount =
        widget.controller.commentsFor(widget.diary.id).length;
    final commentCount = loadedCommentCount > widget.diary.commentCount
        ? loadedCommentCount
        : widget.diary.commentCount;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Semantics(
          button: true,
          label: status.active
              ? (_english ? 'Unlike' : '取消点赞')
              : (_english ? 'Like' : '点赞'),
          child: InkWell(
            key: const Key('diary-like'),
            borderRadius: BorderRadius.circular(20),
            onTap: busy
                ? null
                : () async {
                    final result = await widget.controller.toggleLike(
                      LikeTargetType.diary,
                      widget.diary.id,
                      initial: initial,
                    );
                    if (!result.succeeded && mounted) {
                      _showFailure(
                        _english
                            ? 'Unable to update the like. Please try again.'
                            : '点赞失败，请重试',
                      );
                    }
                  },
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  AnimatedScale(
                    scale: status.active ? 1.15 : 1,
                    duration: const Duration(milliseconds: 180),
                    child: Icon(
                      status.active
                          ? Icons.favorite
                          : Icons.favorite_border,
                      size: 24,
                      color: status.active ? likedColor : inactiveColor,
                    ),
                  ),
                  if (status.count > 0) ...[
                    const SizedBox(width: 4),
                    Text(
                      '${status.count}',
                      style: TextStyle(
                        fontSize: 13,
                        color: status.active ? likedColor : inactiveColor,
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
        const SizedBox(width: 16),
        FavoriteActionButton(
          controller: widget.controller,
          type: FavoriteTargetType.diary,
          targetId: widget.diary.id,
          targetName: widget.diary.title,
          targetImage: widget.diary.coverImage.isNotEmpty
              ? widget.diary.coverImage
              : (widget.diary.images.isEmpty
                  ? ''
                  : widget.diary.images.first),
          compact: true,
          showCount: true,
          activeColor: savedColor,
        ),
        const SizedBox(width: 16),
        Semantics(
          button: true,
          label: _english ? 'Write a comment' : '发表评论',
          child: InkWell(
            key: const Key('diary-comment'),
            borderRadius: BorderRadius.circular(20),
            onTap: _startComment,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.chat_bubble_outline,
                    size: 24,
                    color: inactiveColor,
                  ),
                  if (commentCount > 0) ...[
                    const SizedBox(width: 4),
                    Text(
                      '$commentCount',
                      style: TextStyle(fontSize: 13, color: inactiveColor),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }

  void _startComment() {
    _replyTrigger++;
    if (_replyTarget != null) {
      setState(() => _replyTarget = null);
    }
    unawaited(_focusComposer(_replyTrigger));
  }

  Widget _buildComment(Comment comment) {
    final initial = EngagementStatus(
      active: comment.isLiked,
      count: comment.likeCount,
    );
    final like = widget.controller.likeStatus(
          LikeTargetType.comment,
          comment.id,
        ) ??
        initial;
    final expanded = _expandedComments.contains(comment.id);
    final replies = widget.controller.repliesFor(comment.id);
    final loadingReplies = widget.controller.isBusy(
      'replies:load:${comment.id}',
    );
    final collapsed = _collapsedComments.contains(comment.id);
    final visibleLike =
        collapsed ? EngagementStatus(active: false, count: like.count) : like;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _CommentBody(
          comment: comment,
          english: _english,
          collapsed: collapsed,
          isAuthor: comment.userId == widget.diary.userId,
          displayContent: _displayContent(comment),
          translating: widget.controller.isBusy(
            'translation:comment:${comment.id}',
          ),
          translationActive: widget.controller.isShowingTranslation(comment.id),
          likeStatus: visibleLike,
          onReply: () => _startReply(comment),
          onTranslate: () => _translateComment(comment),
          onLike: () => _toggleCommentLike(comment, like),
          onDislike: () => _toggleCommentDislike(comment, like),
          onLongPress: () => _showCommentMenu(comment, like),
        ),
        if (!collapsed)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              key: Key('comment-replies-${comment.id}'),
              onPressed: loadingReplies ? null : () => _toggleReplies(comment),
              child: Text(
                expanded
                    ? (_english ? 'Hide replies' : '收起回复')
                    : (_english ? 'View replies' : '查看回复'),
              ),
            ),
          ),
        if (expanded) ...[
          if (loadingReplies && replies.isEmpty)
            const Padding(
              padding: EdgeInsets.only(left: 36, top: 8),
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          else if (replies.isEmpty)
            Padding(
              padding: const EdgeInsets.only(left: 36, top: 4),
              child: Text(
                _english ? 'No replies yet.' : '暂无回复',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            )
          else
            for (final reply in replies)
              Padding(
                padding: const EdgeInsets.only(left: 32, top: 10),
                child: _buildReply(reply),
              ),
        ],
      ],
    );
  }

  Widget _buildReply(Comment reply) {
    final initial = EngagementStatus(
      active: reply.isLiked,
      count: reply.likeCount,
    );
    final like = widget.controller.likeStatus(
          LikeTargetType.comment,
          reply.id,
        ) ??
        initial;
    final collapsed = _collapsedComments.contains(reply.id);
    final visibleLike =
        collapsed ? EngagementStatus(active: false, count: like.count) : like;
    return _CommentBody(
      comment: reply,
      english: _english,
      collapsed: collapsed,
      isAuthor: reply.userId == widget.diary.userId,
      displayContent: _displayContent(reply),
      translating: widget.controller.isBusy(
        'translation:comment:${reply.id}',
      ),
      translationActive: widget.controller.isShowingTranslation(reply.id),
      likeStatus: visibleLike,
      compact: true,
      onReply: () => _startReply(reply),
      onTranslate: () => _translateComment(reply),
      onLike: () => _toggleCommentLike(reply, like),
      onDislike: () => _toggleCommentDislike(reply, like),
      onLongPress: () => _showCommentMenu(reply, like),
    );
  }

  String _displayContent(Comment comment) {
    if (!widget.controller.isShowingTranslation(comment.id)) {
      return comment.content;
    }
    return widget.controller.translationFor(comment.id)?.translatedText ??
        comment.content;
  }

  Future<void> _translateComment(Comment comment) async {
    if (widget.controller.translationFor(comment.id) != null) {
      widget.controller.toggleTranslationVisibility(comment.id);
      return;
    }
    final result = await widget.controller.translateComment(
      commentId: comment.id,
      text: comment.content,
      targetLanguage: _english ? 'en-US' : 'zh-CN',
    );
    if (!result.succeeded && mounted) {
      _showFailure(
        _english
            ? 'AI translation is temporarily unavailable. Please try again.'
            : 'AI翻译暂时不可用，请稍后重试',
      );
    }
  }

  Future<void> _toggleCommentLike(
    Comment comment,
    EngagementStatus current,
  ) async {
    if (_collapsedComments.contains(comment.id)) {
      await _toggleCollapse(comment.id);
      if (!mounted) return;
      if (current.active) return;
    }
    final result = await widget.controller.toggleLike(
      LikeTargetType.comment,
      comment.id,
      initial: current,
    );
    if (!result.succeeded && mounted) {
      _showFailure(_english ? 'Unable to update the like.' : '点赞失败，请重试');
    }
  }

  Future<void> _toggleCommentDislike(
    Comment comment,
    EngagementStatus current,
  ) async {
    final selectingDislike = !_collapsedComments.contains(comment.id);
    if (selectingDislike && current.active) {
      final result = await widget.controller.toggleLike(
        LikeTargetType.comment,
        comment.id,
        initial: current,
      );
      if (!result.succeeded) {
        if (mounted) {
          _showFailure(
            _english ? 'Unable to update this preference.' : '操作失败，请重试',
          );
        }
        return;
      }
    }
    await _toggleCollapse(comment.id);
  }

  Future<void> _showCommentMenu(
    Comment comment,
    EngagementStatus like,
  ) async {
    final isOwner = widget.controller.currentUserId.isNotEmpty &&
        widget.controller.currentUserId == comment.userId;
    final disliked = _collapsedComments.contains(comment.id);
    final visiblyLiked = like.active && !disliked;
    final action = await showModalBottomSheet<_CommentMenuActionType>(
      context: context,
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 18),
          child: Row(
            children: [
              _CommentMenuAction(
                icon: Icons.content_copy_outlined,
                label: _english ? 'Copy' : '复制',
                onTap: () => Navigator.pop(
                  sheetContext,
                  _CommentMenuActionType.copy,
                ),
              ),
              _CommentMenuAction(
                icon: Icons.chat_bubble_outline,
                label: _english ? 'Reply' : '回复',
                onTap: () => Navigator.pop(
                  sheetContext,
                  _CommentMenuActionType.reply,
                ),
              ),
              _CommentMenuAction(
                icon: visiblyLiked ? Icons.favorite : Icons.favorite_border,
                label: _english ? 'Like' : '喜欢',
                active: visiblyLiked,
                activeColor: Colors.red,
                onTap: () => Navigator.pop(
                  sheetContext,
                  _CommentMenuActionType.like,
                ),
              ),
              _CommentMenuAction(
                icon: disliked
                    ? Icons.sentiment_dissatisfied
                    : Icons.sentiment_neutral_outlined,
                label: _english ? 'Dislike' : '不喜欢',
                active: disliked,
                activeColor: Colors.amber,
                onTap: () => Navigator.pop(
                  sheetContext,
                  _CommentMenuActionType.dislike,
                ),
              ),
              _CommentMenuAction(
                icon: Icons.warning_amber_rounded,
                label: _english ? 'Report' : '举报',
                onTap: () => Navigator.pop(
                  sheetContext,
                  _CommentMenuActionType.report,
                ),
              ),
              if (isOwner)
                _CommentMenuAction(
                  icon: Icons.delete_outline,
                  label: _english ? 'Delete' : '删除',
                  destructive: true,
                  onTap: () => Navigator.pop(
                    sheetContext,
                    _CommentMenuActionType.delete,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
    if (action == null || !mounted) return;
    switch (action) {
      case _CommentMenuActionType.copy:
        await Clipboard.setData(ClipboardData(text: comment.content));
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(_english ? 'Copied' : '已复制')),
          );
        }
        break;
      case _CommentMenuActionType.reply:
        _startReply(comment);
        break;
      case _CommentMenuActionType.like:
        await _toggleCommentLike(comment, like);
        break;
      case _CommentMenuActionType.dislike:
        await _toggleCommentDislike(comment, like);
        break;
      case _CommentMenuActionType.report:
        await showReportFlow(
          context: context,
          controller: widget.controller,
          type: ReportTargetType.comment,
          targetId: comment.id,
        );
        break;
      case _CommentMenuActionType.delete:
        await _deleteComment(comment);
        break;
    }
  }

  Widget _buildComposer() {
    final target = _replyTarget;
    final busy = widget.controller.isBusy(
      'comment:publish:${widget.diary.id}',
    );
    return Material(
      elevation: 8,
      color: Theme.of(context).colorScheme.surface,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 10),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (target != null)
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        _english
                            ? 'Replying to ${target.userName}'
                            : '回复 ${target.userName}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ),
                    IconButton(
                      key: const Key('cancel-reply'),
                      tooltip: _english ? 'Cancel reply' : '取消回复',
                      onPressed: () => setState(() => _replyTarget = null),
                      icon: const Icon(Icons.close, size: 18),
                    ),
                  ],
                ),
              Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: TextField(
                      key: const Key('comment-input'),
                      controller: _commentController,
                      focusNode: _commentFocusNode,
                      minLines: 1,
                      maxLines: 4,
                      maxLength: 1000,
                      decoration: InputDecoration(
                        counterText: '',
                        hintText: target == null
                            ? (_english ? 'Write a comment…' : '写下你的评论…')
                            : (_english ? 'Write a reply…' : '写下你的回复…'),
                        border: const OutlineInputBorder(),
                        isDense: true,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filled(
                    key: const Key('comment-send'),
                    tooltip: _english ? 'Send' : '发送',
                    onPressed: busy ? null : _submitComment,
                    icon: busy
                        ? const SizedBox.square(
                            dimension: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.send_rounded),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _toggleReplies(Comment comment) async {
    if (_expandedComments.remove(comment.id)) {
      setState(() {});
      return;
    }
    setState(() => _expandedComments.add(comment.id));
    if (widget.controller.repliesFor(comment.id).isNotEmpty) return;
    final result =
        await widget.controller.loadReplies(comment.id, refresh: true);
    if (!result.succeeded && mounted) {
      _showFailure(
        _english ? 'Unable to load replies. Please try again.' : '回复加载失败，请重试',
      );
    }
  }

  void _startReply(Comment comment) {
    _replyTrigger++;
    setState(() => _replyTarget = comment);
    _commentController.clear();
    unawaited(_focusComposer(_replyTrigger));
  }

  Future<void> _focusComposer(int trigger) async {
    final keyboardVisible = MediaQuery.viewInsetsOf(context).bottom > 0;
    if (!keyboardVisible) {
      _commentFocusNode.unfocus();
      await Future<void>.delayed(const Duration(milliseconds: 50));
      if (!mounted || trigger != _replyTrigger) return;
      _commentFocusNode.requestFocus();
      await Future<void>.delayed(const Duration(milliseconds: 250));
    }
    if (!mounted || !_scrollController.hasClients) return;
    await _scrollController.animateTo(
      _scrollController.position.maxScrollExtent,
      duration: const Duration(milliseconds: 240),
      curve: Curves.easeOut,
    );
  }

  Future<void> _loadCommentThread() async {
    final result = await widget.controller.loadCommentThread(widget.diary.id);
    if (!mounted || !result.succeeded) return;
    setState(() {
      for (final comment in result.value ?? const <Comment>[]) {
        if (widget.controller.repliesFor(comment.id).isNotEmpty) {
          _expandedComments.add(comment.id);
        }
      }
    });
  }

  Future<void> _restoreCollapsedComments() async {
    try {
      final ids = await _collapseStore.readCollapsedCommentIds();
      if (!mounted) return;
      setState(() {
        _collapsedComments
          ..clear()
          ..addAll(ids);
      });
    } on Object {
      // Local persistence is optional; comment browsing must remain available.
    }
  }

  Future<void> _toggleCollapse(String commentId) async {
    setState(() {
      if (!_collapsedComments.remove(commentId)) {
        _collapsedComments.add(commentId);
      }
    });
    try {
      await _collapseStore.writeCollapsedCommentIds(_collapsedComments);
    } on Object {
      // Keep the in-memory preference when platform storage is unavailable.
    }
  }

  Future<void> _deleteComment(Comment comment) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(_english ? 'Delete comment?' : '删除评论？'),
        content: Text(
          _english
              ? 'This action cannot be undone. Replies will also be removed when deleting a top-level comment.'
              : '此操作无法撤销。删除顶级评论时，其回复也会一并删除。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(_english ? 'Cancel' : '取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(_english ? 'Delete' : '删除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final result = await widget.controller.deleteComment(comment);
    if (!mounted) return;
    if (!result.succeeded) {
      _showFailure(
        _english
            ? 'Unable to delete this comment. You can only delete your own comments.'
            : '删除失败，只能删除自己发布的评论',
      );
    }
  }

  Future<void> _submitComment() async {
    final content = _commentController.text.trim();
    if (content.isEmpty) {
      _showFailure(_english ? 'Please enter a comment.' : '请输入评论内容');
      return;
    }
    final target = _replyTarget;
    final parentId = target == null ? null : target.parentId ?? target.id;
    final result = await widget.controller.publishComment(
      CommentDraft(
        diaryId: widget.diary.id,
        content: content,
        parentId: parentId,
        replyToUserId: target?.userId,
      ),
    );
    if (!mounted) return;
    if (!result.succeeded) {
      _showFailure(
        _english
            ? 'Unable to send the comment. Please try again.'
            : '评论发送失败，请重试',
      );
      return;
    }
    _commentController.clear();
    if (parentId != null) {
      _expandedComments.add(parentId);
    }
    setState(() => _replyTarget = null);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(_english ? 'Sent' : '已发送')),
    );
  }

  void _showFailure(String message) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }
}

final class _DiaryHeader extends StatelessWidget {
  const _DiaryHeader({
    required this.diary,
    required this.controller,
    required this.english,
    this.onAuthorTap,
  });

  final Diary diary;
  final SocialController controller;
  final bool english;
  final VoidCallback? onAuthorTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        InkWell(
          onTap: onAuthorTap,
          borderRadius: BorderRadius.circular(12),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(
              children: [
                CircleAvatar(
                  foregroundImage: diary.authorAvatar.isEmpty
                      ? null
                      : NetworkImage(diary.authorAvatar),
                  child: diary.authorAvatar.isEmpty
                      ? Text(
                          diary.authorName.trim().isEmpty
                              ? '?'
                              : diary.authorName.trim().characters.first,
                        )
                      : null,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(diary.authorName),
                      if (diary.publishDate.isNotEmpty)
                        Text(
                          diary.publishDate,
                          style: theme.textTheme.bodySmall,
                        ),
                    ],
                  ),
                ),
                if (diary.rating > 0)
                  Semantics(
                    label: Localizations.localeOf(context).languageCode == 'en'
                        ? 'Rating ${diary.rating} out of 5'
                        : '评分 ${diary.rating} 分，满分 5 分',
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(
                          Icons.star_rounded,
                          size: 18,
                          color: Color(0xffffa000),
                        ),
                        const SizedBox(width: 3),
                        Text(
                          '${diary.rating}.0',
                          style: theme.textTheme.titleSmall,
                        ),
                      ],
                    ),
                  ),
                if (onAuthorTap != null) ...[
                  const SizedBox(width: 4),
                  const Icon(Icons.chevron_right, size: 20),
                ],
              ],
            ),
          ),
        ),
        if (diary.images.isNotEmpty ||
            diary.beforeImages.isNotEmpty ||
            diary.afterImages.isNotEmpty) ...[
          const SizedBox(height: 14),
          DiaryMediaGrid(
            images: diary.images,
            beforeImages: diary.beforeImages,
            afterImages: diary.afterImages,
            height: 300,
            borderRadius: BorderRadius.circular(12),
            enableViewer: true,
          ),
        ],
        const SizedBox(height: 18),
        _TranslatedDiarySection(
          controller: controller,
          titleContentId: 'translation:diary-title:${diary.id}',
          title: diary.title,
          contentContentId: 'translation:diary-content:${diary.id}',
          content: diary.content,
          targetLanguage: english ? 'en' : 'zh-CN',
          titleStyle: theme.textTheme.headlineSmall,
        ),
        if (diary.tags.isNotEmpty) ...[
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              for (final tag in diary.tags) Chip(label: Text('#$tag'))
            ],
          ),
        ],
      ],
    );
  }
}

final class _TranslatedDiarySection extends StatelessWidget {
  const _TranslatedDiarySection({
    required this.controller,
    required this.titleContentId,
    required this.title,
    required this.contentContentId,
    required this.content,
    required this.targetLanguage,
    this.titleStyle,
  });

  final SocialController controller;
  final String titleContentId;
  final String title;
  final String contentContentId;
  final String content;
  final String targetLanguage;
  final TextStyle? titleStyle;

  @override
  Widget build(BuildContext context) {
    final titleTranslation = controller.translationFor(titleContentId);
    final contentTranslation = controller.translationFor(contentContentId);
    final showingTitle = controller.isShowingTranslation(titleContentId);
    final showingContent = controller.isShowingTranslation(contentContentId);
    final busy = controller.isBusy('translation:diary:$titleContentId') ||
        controller.isBusy('translation:diary:$contentContentId');
    final hasTranslation = titleTranslation != null || contentTranslation != null;
    final showing = showingTitle || showingContent;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SelectableText(
          showingTitle && titleTranslation != null
              ? titleTranslation.translatedText
              : title,
          style: titleStyle,
        ),
        const SizedBox(height: 10),
        SelectableText(
          showingContent && contentTranslation != null
              ? contentTranslation.translatedText
              : content,
        ),
        if (title.trim().isNotEmpty || content.trim().isNotEmpty)
          Align(
            alignment: Alignment.centerLeft,
            child: IconButton(
              key: Key('$titleContentId-button'),
              tooltip: hasTranslation && showing ? '显示原文' : '翻译',
              onPressed: busy
                  ? null
                  : () async {
                      if (hasTranslation) {
                        controller.toggleTranslationVisibility(titleContentId);
                        controller.toggleTranslationVisibility(contentContentId);
                        return;
                      }
                      final requests = <Future<SocialActionResult<ContentTranslation>>>[
                        if (title.trim().isNotEmpty)
                          controller.translateContent(
                            contentId: titleContentId,
                            text: title,
                            targetLanguage: targetLanguage,
                            contentType: 'diary',
                          ),
                        if (content.trim().isNotEmpty)
                          controller.translateContent(
                            contentId: contentContentId,
                            text: content,
                            targetLanguage: targetLanguage,
                            contentType: 'diary',
                          ),
                      ];
                      final results = await Future.wait(requests);
                      if (!context.mounted || results.every((r) => r.succeeded)) return;
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text(results.firstWhere((r) => !r.succeeded).message ?? '翻译失败，请稍后重试')),
                      );
                    },
              icon: busy
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.translate, size: 17),
            ),
          ),
      ],
    );
  }
}

final class _DiaryAssociations extends StatelessWidget {
  const _DiaryAssociations({
    required this.diary,
    required this.english,
    this.onProjectTap,
    this.onDoctorTap,
    this.onInstitutionTap,
  });

  final Diary diary;
  final bool english;
  final void Function(String institutionId, String projectId)? onProjectTap;
  final ValueChanged<String>? onDoctorTap;
  final ValueChanged<String>? onInstitutionTap;

  @override
  Widget build(BuildContext context) {
    final entries = <Widget>[];
    _addEntry(
      entries,
      context: context,
      icon: Icons.spa_outlined,
      label: english ? 'Project' : '关联项目',
      name: diary.projectName,
      fallback: english ? 'Related project' : '关联项目',
      enabled: diary.projectId.trim().isNotEmpty && onProjectTap != null,
      onTap: () => onProjectTap!(
        diary.institutionId.trim(),
        diary.projectId.trim(),
      ),
    );
    _addEntry(
      entries,
      context: context,
      icon: Icons.person_outline,
      label: english ? 'Doctor' : '关联医生',
      name: diary.doctorName,
      fallback: english ? 'Related doctor' : '关联医生',
      enabled: diary.doctorId.trim().isNotEmpty && onDoctorTap != null,
      onTap: () => onDoctorTap!(diary.doctorId.trim()),
    );
    _addEntry(
      entries,
      context: context,
      icon: Icons.local_hospital_outlined,
      label: english ? 'Institution' : '关联机构',
      name: diary.institutionName,
      fallback: english ? 'Related institution' : '关联机构',
      enabled:
          diary.institutionId.trim().isNotEmpty && onInstitutionTap != null,
      onTap: () => onInstitutionTap!(diary.institutionId.trim()),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          english ? 'Related information' : '关联信息',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 10),
        DecoratedBox(
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainerLowest,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: Theme.of(context).dividerColor),
          ),
          child: Column(children: entries),
        ),
      ],
    );
  }

  void _addEntry(
    List<Widget> entries, {
    required BuildContext context,
    required IconData icon,
    required String label,
    required String name,
    required String fallback,
    required bool enabled,
    required VoidCallback onTap,
  }) {
    if (name.trim().isEmpty && !enabled) return;
    if (entries.isNotEmpty) entries.add(const Divider(height: 1));
    entries.add(
      ListTile(
        dense: true,
        leading: Icon(icon),
        title: Text(label),
        subtitle: Text(name.trim().isEmpty ? fallback : name.trim()),
        trailing: enabled ? const Icon(Icons.chevron_right) : null,
        onTap: enabled ? onTap : null,
      ),
    );
  }
}

final class _CommentBody extends StatelessWidget {
  const _CommentBody({
    required this.comment,
    required this.english,
    required this.collapsed,
    required this.isAuthor,
    required this.displayContent,
    required this.translating,
    required this.translationActive,
    required this.likeStatus,
    required this.onReply,
    required this.onTranslate,
    required this.onLike,
    required this.onDislike,
    required this.onLongPress,
    this.compact = false,
  });

  final Comment comment;
  final bool english;
  final bool collapsed;
  final bool isAuthor;
  final String displayContent;
  final bool translating;
  final bool translationActive;
  final EngagementStatus likeStatus;
  final VoidCallback onReply;
  final VoidCallback onTranslate;
  final VoidCallback onLike;
  final VoidCallback onDislike;
  final VoidCallback onLongPress;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    final active = theme.colorScheme.primary;
    const likedColor = Colors.red;
    const dislikedColor = Colors.amber;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CircleAvatar(
          radius: compact ? 13 : 17,
          foregroundImage: comment.userAvatar.trim().isEmpty
              ? null
              : NetworkImage(comment.userAvatar),
          child: comment.userAvatar.trim().isEmpty
              ? Text(
                  comment.userName.trim().isEmpty
                      ? '?'
                      : comment.userName.trim().characters.first,
                )
              : null,
        ),
        SizedBox(width: compact ? 8 : 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Flexible(
                    child: Text(
                      comment.userName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.labelLarge,
                    ),
                  ),
                  if (isAuthor) ...[
                    const SizedBox(width: 6),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 5,
                        vertical: 1,
                      ),
                      decoration: BoxDecoration(
                        color: theme.colorScheme.primaryContainer,
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(
                        english ? 'Author' : '作者',
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: theme.colorScheme.onPrimaryContainer,
                          fontSize: 10,
                        ),
                      ),
                    ),
                  ],
                ],
              ),
              const SizedBox(height: 4),
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: onReply,
                onLongPress: onLongPress,
                child: SizedBox(
                  width: double.infinity,
                  child: collapsed
                      ? Text(
                          english ? 'This content is hidden.' : '改内容已折叠',
                          style: theme.textTheme.bodySmall,
                        )
                      : Text.rich(
                          TextSpan(
                            children: [
                              if (comment.replyToUserName?.trim().isNotEmpty ==
                                  true)
                                TextSpan(
                                  text: english
                                      ? 'Reply @${comment.replyToUserName} '
                                      : '回复 @${comment.replyToUserName} ',
                                  style: TextStyle(color: active),
                                ),
                              TextSpan(text: displayContent),
                            ],
                          ),
                          style: theme.textTheme.bodyMedium?.copyWith(
                            fontSize: compact ? 13 : 14,
                            height: 1.45,
                          ),
                        ),
                ),
              ),
              const SizedBox(height: 5),
              Row(
                children: [
                  Text(
                    _formatCommentTime(comment.createdAt),
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: muted,
                      fontSize: compact ? 11 : 12,
                    ),
                  ),
                  const SizedBox(width: 16),
                  InkWell(
                    key: Key('comment-reply-${comment.id}'),
                    onTap: onReply,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 5),
                      child: Text(
                        english ? 'Reply' : '回复',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: muted,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  if (!collapsed)
                    IconButton(
                      key: Key('comment-translate-${comment.id}'),
                      tooltip: translationActive
                          ? (english ? 'Show original' : '显示原文')
                          : (english ? 'Translate' : '翻译'),
                      onPressed: translating ? null : onTranslate,
                      visualDensity: VisualDensity.compact,
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints.tightFor(
                        width: 30,
                        height: 30,
                      ),
                      icon: translating
                          ? const SizedBox.square(
                              dimension: 14,
                              child:
                                  CircularProgressIndicator(strokeWidth: 1.5),
                            )
                          : Icon(
                              Icons.translate_rounded,
                              size: 17,
                              color: translationActive ? active : muted,
                            ),
                    ),
                  const Spacer(),
                  InkResponse(
                    key: Key('comment-like-${comment.id}'),
                    onTap: onLike,
                    radius: 14,
                    containedInkWell: true,
                    highlightShape: BoxShape.circle,
                    child: Padding(
                      padding: const EdgeInsets.all(3),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            likeStatus.active
                                ? Icons.favorite
                                : Icons.favorite_border,
                            size: 17,
                            color: likeStatus.active ? likedColor : muted,
                          ),
                          if (likeStatus.count > 0) ...[
                            const SizedBox(width: 3),
                            Text(
                              '${likeStatus.count}',
                              style: theme.textTheme.bodySmall?.copyWith(
                                color: likeStatus.active ? likedColor : muted,
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                  InkResponse(
                    key: Key('comment-dislike-${comment.id}'),
                    onTap: onDislike,
                    radius: 14,
                    containedInkWell: true,
                    highlightShape: BoxShape.circle,
                    child: Padding(
                      padding: const EdgeInsets.all(3),
                      child: Icon(
                        collapsed
                            ? Icons.sentiment_dissatisfied
                            : Icons.sentiment_neutral_outlined,
                        size: 17,
                        color: collapsed ? dislikedColor : muted,
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

enum _CommentMenuActionType { copy, reply, like, dislike, report, delete }

final class _CommentMenuAction extends StatelessWidget {
  const _CommentMenuAction({
    required this.icon,
    required this.label,
    required this.onTap,
    this.active = false,
    this.activeColor,
    this.destructive = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final bool active;
  final Color? activeColor;
  final bool destructive;

  @override
  Widget build(BuildContext context) {
    final color = destructive
        ? Theme.of(context).colorScheme.error
        : active
            ? (activeColor ?? Theme.of(context).colorScheme.primary)
            : Theme.of(context).colorScheme.onSurfaceVariant;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: SizedBox(
        width: 72,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 10),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, color: color, size: 24),
              const SizedBox(height: 5),
              Text(
                label,
                maxLines: 1,
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: color,
                    ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

String _formatCommentTime(DateTime? value) {
  if (value == null) return '';
  final local = value.toLocal();
  String two(int number) => number.toString().padLeft(2, '0');
  return '${local.year}-${two(local.month)}-${two(local.day)} '
      '${two(local.hour)}:${two(local.minute)}';
}

final class _EmptyComments extends StatelessWidget {
  const _EmptyComments({
    required this.english,
    required this.failed,
    required this.onRetry,
  });

  final bool english;
  final bool failed;
  final Future<Object?> Function() onRetry;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 28),
      child: Column(
        children: [
          Icon(
            failed ? Icons.cloud_off_outlined : Icons.chat_bubble_outline,
            size: 38,
          ),
          const SizedBox(height: 10),
          Text(
            failed
                ? (english ? 'Unable to load comments.' : '评论加载失败')
                : (english
                    ? 'No comments yet. Start the conversation.'
                    : '还没有评论，来说两句吧'),
          ),
          if (failed) ...[
            const SizedBox(height: 10),
            TextButton(
              onPressed: onRetry,
              child: Text(english ? 'Retry' : '重试'),
            ),
          ],
        ],
      ),
    );
  }
}
