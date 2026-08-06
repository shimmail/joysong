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

final class PublicUploadController extends ChangeNotifier {
  PublicUploadController(this._repository);

  final SocialRepository _repository;
  final Map<String, PublicMediaDraft> _drafts = <String, PublicMediaDraft>{};
  final Map<String, PublicUploadTask> _tasks = <String, PublicUploadTask>{};
  final Set<String> _running = <String>{};
  int _sequence = 0;
  bool _disposed = false;

  List<PublicUploadTask> get tasks => List.unmodifiable(_tasks.values);

  PublicUploadTask? task(String id) => _tasks[id];

  String enqueue(PublicMediaDraft draft) {
    final id = 'upload-${DateTime.now().microsecondsSinceEpoch}-${_sequence++}';
    _drafts[id] = draft;
    _tasks[id] = PublicUploadTask(
      id: id,
      fileName: draft.fileName,
      purpose: draft.purpose,
      progress: PublicUploadProgress(
        stage: UploadStage.queued,
        totalBytes: draft.bytes.length,
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
    if (_running.contains(id)) {
      return;
    }
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
        totalBytes: draft.bytes.length,
      ),
      attempts: attempts,
    );
    _notify();
    try {
      await for (final progress in _repository.uploadPublicMedia(draft)) {
        _tasks[id] = PublicUploadTask(
          id: id,
          fileName: current.fileName,
          purpose: current.purpose,
          progress: progress,
          attempts: attempts,
        );
        _notify();
      }
    } on PrivateMaterialUploadException {
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
    super.dispose();
  }
}
