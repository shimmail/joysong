import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

/// Shared favorite action for project, institution, doctor, diary, and article
/// details. It hydrates its state from the server and keeps the UI in sync with
/// the controller's optimistic update/rollback behavior.
class FavoriteActionButton extends StatefulWidget {
  const FavoriteActionButton({
    required this.controller,
    required this.type,
    required this.targetId,
    required this.targetName,
    this.targetImage = '',
    this.compact = false,
    this.showCount = false,
    this.activeColor = const Color(0xffff9800),
    super.key,
  });

  final SocialController controller;
  final FavoriteTargetType type;
  final String targetId;
  final String targetName;
  final String targetImage;
  final bool compact;
  final bool showCount;
  final Color activeColor;

  @override
  State<FavoriteActionButton> createState() => _FavoriteActionButtonState();
}

class _FavoriteActionButtonState extends State<FavoriteActionButton> {
  bool _loading = true;
  bool _writing = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(_loadStatus());
    });
  }

  @override
  void didUpdateWidget(covariant FavoriteActionButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller ||
        oldWidget.type != widget.type ||
        oldWidget.targetId != widget.targetId) {
      setState(() => _loading = true);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) unawaited(_loadStatus());
      });
    }
  }

  Future<void> _loadStatus() async {
    final result = await widget.controller.loadFavoriteStatus(
      widget.type,
      widget.targetId,
    );
    if (!mounted) return;
    setState(() => _loading = false);
    if (!result.succeeded &&
        widget.controller.favoriteStatus(widget.type, widget.targetId) ==
            null) {
      // The add endpoint is idempotent, so the action remains available even
      // when a transient status request fails.
      widget.controller.seedFavoriteStatus(
        widget.type,
        widget.targetId,
        const EngagementStatus(active: false, count: 0),
      );
    }
  }

  Future<void> _toggle() async {
    if (_writing) return;
    final initial = widget.controller.favoriteStatus(
          widget.type,
          widget.targetId,
        ) ??
        const EngagementStatus(active: false, count: 0);
    setState(() => _writing = true);
    final result = await widget.controller.toggleFavorite(
      widget.type,
      widget.targetId,
      targetName: widget.targetName,
      targetImage: widget.targetImage,
      initial: initial,
    );
    if (!mounted) return;
    setState(() => _writing = false);
    final status = widget.controller.favoriteStatus(
          widget.type,
          widget.targetId,
        ) ??
        initial;
    showTransientMessage(
      context,
      result.succeeded
          ? (status.active
              ? context.localized('已收藏', 'Added to favorites')
              : context.localized('已取消收藏', 'Removed from favorites'))
          : (result.message ??
                  context.localized('收藏操作失败，请重试',
                  'Unable to update favorites. Please try again.')),
    );
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final status = widget.controller.favoriteStatus(
          widget.type,
          widget.targetId,
        );
        final active = status?.active ?? false;
        final count = status?.count ?? 0;
        if (_loading || _writing) {
          if (widget.compact) {
            return const SizedBox(
              width: 24,
              height: 40,
              child: Center(
                child: SizedBox.square(
                  dimension: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
            );
          }
          return const Padding(
            padding: EdgeInsets.symmetric(horizontal: 14),
            child: Center(
              child: SizedBox.square(
                dimension: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
          );
        }
        if (widget.compact) {
          final inactiveColor = Theme.of(context).colorScheme.onSurfaceVariant;
          final color = active ? widget.activeColor : inactiveColor;
          return Semantics(
            button: true,
            label: active
                ? context.localized('取消收藏', 'Remove from favorites')
                : context.localized('收藏', 'Add to favorites'),
            child: InkWell(
              borderRadius: BorderRadius.circular(20),
              onTap: _toggle,
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    AnimatedSwitcher(
                      duration: const Duration(milliseconds: 180),
                      child: Icon(
                        active
                            ? Icons.bookmark_rounded
                            : Icons.bookmark_border_rounded,
                        key: ValueKey(active),
                        size: 24,
                        color: color,
                      ),
                    ),
                    if (widget.showCount && count > 0) ...[
                      const SizedBox(width: 4),
                      Text(
                        '$count',
                        style: TextStyle(fontSize: 13, color: color),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          );
        }
        return IconButton(
          key: Key('favorite-${widget.type.wireValue}-${widget.targetId}'),
          tooltip: active
              ? context.localized('取消收藏', 'Remove from favorites')
              : context.localized('收藏', 'Add to favorites'),
          onPressed: _toggle,
          icon: AnimatedSwitcher(
            duration: const Duration(milliseconds: 180),
            transitionBuilder: (child, animation) =>
                ScaleTransition(scale: animation, child: child),
            child: Icon(
              active ? Icons.bookmark_rounded : Icons.bookmark_border_rounded,
              key: ValueKey(active),
              color: active ? widget.activeColor : null,
            ),
          ),
        );
      },
    );
  }
}
