import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';

final class PublicUploadTask {
  const PublicUploadTask({
    required this.id,
    required this.fileName,
    required this.purpose,
    required this.progress,
    required this.attempts,
  });

  final String id;
  final String fileName;
  final PublicMediaPurpose purpose;
  final PublicUploadProgress progress;
  final int attempts;

  bool get canRetry =>
      progress.stage == UploadStage.failed && progress.retryable;
}

/// Owns one or more foreground public-media uploads for a UI lifecycle.
///
/// Cancelling an active iterator propagates to the repository stream's
/// `onCancel`, which aborts the underlying multipart request.
final class PublicMediaUploadScope {
  final Set<_ScopedPublicMediaUpload> _active = <_ScopedPublicMediaUpload>{};
  bool _disposed = false;

  Future<PublicUploadProgress> upload(
    SocialRepository repository,
    PublicMediaDraft draft,
  ) async {
    if (_disposed) return _cancelledProgress(draft.byteLength);

    final operation = _ScopedPublicMediaUpload(
      repository.uploadPublicMedia(draft),
      totalBytes: draft.byteLength,
    );
    _active.add(operation);
    if (_disposed) await operation.cancel();
    try {
      return await operation.result;
    } finally {
      _active.remove(operation);
    }
  }

  Future<void> cancelActive() async {
    final active = List<_ScopedPublicMediaUpload>.of(_active);
    await Future.wait(active.map((operation) => operation.cancel()));
  }

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    unawaited(cancelActive());
  }
}

final class _ScopedPublicMediaUpload {
  _ScopedPublicMediaUpload(
    Stream<PublicUploadProgress> progress, {
    required this.totalBytes,
  }) : _iterator = StreamIterator<PublicUploadProgress>(progress) {
    result = _run();
  }

  final int totalBytes;
  final StreamIterator<PublicUploadProgress> _iterator;
  late final Future<PublicUploadProgress> result;
  Future<void>? _closeFuture;
  bool _cancelled = false;

  Future<void> cancel() async {
    _cancelled = true;
    await _close();
  }

  Future<PublicUploadProgress> _run() async {
    try {
      while (!_cancelled && await _iterator.moveNext()) {
        if (_cancelled) break;
        final progress = _iterator.current;
        if (progress.isTerminal) return progress;
      }
      return _cancelled
          ? _cancelledProgress(totalBytes)
          : PublicUploadProgress(
              stage: UploadStage.failed,
              totalBytes: totalBytes,
              message: '上传连接已结束，请手动重试',
              retryable: true,
            );
    } catch (_) {
      if (_cancelled) return _cancelledProgress(totalBytes);
      rethrow;
    } finally {
      await _close();
    }
  }

  Future<void> _close() => _closeFuture ??= _cancelIterator();

  Future<void> _cancelIterator() async {
    try {
      await _iterator.cancel();
    } catch (_) {
      // Cancellation is best-effort; the upload result remains authoritative.
    }
  }
}

PublicUploadProgress _cancelledProgress(int totalBytes) => PublicUploadProgress(
      stage: UploadStage.cancelled,
      totalBytes: totalBytes,
      message: '图片上传已取消',
    );

final class PublicUploadController extends ChangeNotifier {
  PublicUploadController(this._repository);

  final SocialRepository _repository;
  final Map<String, PublicMediaDraft> _drafts = <String, PublicMediaDraft>{};
  final Map<String, PublicUploadTask> _tasks = <String, PublicUploadTask>{};
  final Set<String> _running = <String>{};
  final Map<String, StreamSubscription<PublicUploadProgress>> _subscriptions =
      <String, StreamSubscription<PublicUploadProgress>>{};
  final Map<String, Completer<void>> _completions = <String, Completer<void>>{};
  bool _disposed = false;

  List<PublicUploadTask> get tasks => List.unmodifiable(_tasks.values);

  PublicUploadTask? task(String id) => _tasks[id];

  String enqueue(PublicMediaDraft draft) {
    final id = draft.uploadId;
    _drafts[id] = draft;
    _tasks[id] = PublicUploadTask(
      id: id,
      fileName: draft.fileName,
      purpose: draft.purpose,
      progress: PublicUploadProgress(
        stage: UploadStage.queued,
        totalBytes: draft.byteLength,
      ),
      attempts: 0,
    );
    _notify();
    unawaited(_run(id));
    return id;
  }

  Future<bool> retry(String id) async {
    final current = _tasks[id];
    if (current == null || !current.canRetry || _running.contains(id)) {
      return false;
    }
    await _run(id);
    return true;
  }

  void remove(String id) {
    final subscription = _subscriptions.remove(id);
    if (subscription != null) unawaited(subscription.cancel());
    final completion = _completions.remove(id);
    if (completion != null && !completion.isCompleted) completion.complete();
    _running.remove(id);
    _tasks.remove(id);
    _drafts.remove(id);
    _notify();
  }

  Future<void> _run(String id) async {
    final draft = _drafts[id];
    final current = _tasks[id];
    if (draft == null || current == null || !_running.add(id)) {
      return;
    }
    final attempts = current.attempts + 1;
    _tasks[id] = PublicUploadTask(
      id: id,
      fileName: current.fileName,
      purpose: current.purpose,
      progress: PublicUploadProgress(
        stage: UploadStage.queued,
        totalBytes: draft.byteLength,
      ),
      attempts: attempts,
    );
    _notify();
    try {
      final completion = Completer<void>();
      _completions[id] = completion;
      late final StreamSubscription<PublicUploadProgress> subscription;
      subscription = _repository.uploadPublicMedia(draft).listen(
        (progress) {
          if (_disposed || !_tasks.containsKey(id)) return;
          _tasks[id] = PublicUploadTask(
            id: id,
            fileName: current.fileName,
            purpose: current.purpose,
            progress: progress,
            attempts: attempts,
          );
          _notify();
        },
        onError: (Object error, StackTrace stackTrace) {
          if (!completion.isCompleted) {
            completion.completeError(error, stackTrace);
          }
        },
        onDone: () {
          if (!completion.isCompleted) completion.complete();
        },
        cancelOnError: true,
      );
      _subscriptions[id] = subscription;
      await completion.future;
      if (!_disposed && _tasks.containsKey(id)) {
        final progress = _tasks[id]!.progress;
        if (!progress.isTerminal) {
          _tasks[id] = PublicUploadTask(
            id: id,
            fileName: current.fileName,
            purpose: current.purpose,
            progress: PublicUploadProgress(
              stage: UploadStage.failed,
              totalBytes: draft.byteLength,
              message: '上传连接已结束，请手动重试',
              retryable: true,
            ),
            attempts: attempts,
          );
          _notify();
        }
      }
    } on PrivateMaterialUploadException {
      if (_disposed || !_tasks.containsKey(id)) return;
      _tasks[id] = PublicUploadTask(
        id: id,
        fileName: current.fileName,
        purpose: current.purpose,
        progress: const PublicUploadProgress(
          stage: UploadStage.failed,
          message: '私有身份材料只能通过身份认证入口上传',
        ),
        attempts: attempts,
      );
      _notify();
    } catch (_) {
      if (_disposed || !_tasks.containsKey(id)) return;
      _tasks[id] = PublicUploadTask(
        id: id,
        fileName: current.fileName,
        purpose: current.purpose,
        progress: const PublicUploadProgress(
          stage: UploadStage.failed,
          message: '图片上传失败，请检查网络后手动重试',
          retryable: true,
        ),
        attempts: attempts,
      );
      _notify();
    } finally {
      _subscriptions.remove(id);
      _completions.remove(id);
      _running.remove(id);
    }
  }

  void _notify() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    for (final subscription in _subscriptions.values) {
      unawaited(subscription.cancel());
    }
    for (final completion in _completions.values) {
      if (!completion.isCompleted) completion.complete();
    }
    _subscriptions.clear();
    _completions.clear();
    _running.clear();
    super.dispose();
  }
}
