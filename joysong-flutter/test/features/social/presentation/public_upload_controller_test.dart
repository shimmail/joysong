import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/public_upload_controller.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

void main() {
  test('dispose cancels the active upload subscription', () async {
    final repository = _CancellationRepository();
    final controller = PublicUploadController(repository);
    const draft = PublicMediaDraft(
      uploadId: 'upload-1',
      localPath: 'photo.jpg',
      byteLength: 12,
      fileName: 'upload-1.jpg',
      mimeType: 'image/jpeg',
      purpose: PublicMediaPurpose.diary,
    );

    expect(controller.enqueue(draft), 'upload-1');
    await Future<void>.delayed(Duration.zero);
    controller.dispose();

    await repository.cancelled.future.timeout(const Duration(seconds: 1));
  });

  test('SocialController dispose cancels its active upload', () async {
    final repository = _CancellationRepository();
    final controller = SocialController(repository);
    const draft = PublicMediaDraft(
      uploadId: 'upload-2',
      localPath: 'photo.jpg',
      byteLength: 12,
      fileName: 'upload-2.jpg',
      mimeType: 'image/jpeg',
      purpose: PublicMediaPurpose.review,
    );

    final result = controller.uploadPublicMedia(draft);
    await Future<void>.delayed(Duration.zero);
    controller.dispose();

    expect((await result).succeeded, isFalse);
    await repository.cancelled.future.timeout(const Duration(seconds: 1));
  });

  test('cancelActive keeps an upload scope reusable', () async {
    final repository = _ReusableCancellationRepository();
    final scope = PublicMediaUploadScope();
    const draft = PublicMediaDraft(
      uploadId: 'upload-3',
      localPath: 'photo.jpg',
      byteLength: 12,
      fileName: 'upload-3.jpg',
      mimeType: 'image/jpeg',
      purpose: PublicMediaPurpose.doctorProfile,
    );

    final first = scope.upload(repository, draft);
    await Future<void>.delayed(Duration.zero);
    await scope.cancelActive();

    expect((await first).stage, UploadStage.cancelled);
    final second = await scope.upload(repository, draft);
    expect(second.stage, UploadStage.complete);
    expect(second.url, 'https://example.test/upload-3.jpg');
    scope.dispose();
  });

  test('disposed scope prevents a late upload from starting', () async {
    final repository = _ReusableCancellationRepository();
    final scope = PublicMediaUploadScope()..dispose();
    const draft = PublicMediaDraft(
      uploadId: 'upload-4',
      localPath: 'photo.jpg',
      byteLength: 12,
      fileName: 'upload-4.jpg',
      mimeType: 'image/jpeg',
      purpose: PublicMediaPurpose.directMessage,
    );

    final progress = await scope.upload(repository, draft);

    expect(progress.stage, UploadStage.cancelled);
    expect(repository.calls, 0);
  });
}

final class _CancellationRepository implements SocialRepository {
  final Completer<void> cancelled = Completer<void>();

  @override
  Stream<PublicUploadProgress> uploadPublicMedia(PublicMediaDraft media) {
    late final StreamController<PublicUploadProgress> controller;
    controller = StreamController<PublicUploadProgress>(
      onListen: () => controller.add(
        PublicUploadProgress(
          stage: UploadStage.uploading,
          totalBytes: media.byteLength,
        ),
      ),
      onCancel: () {
        if (!cancelled.isCompleted) cancelled.complete();
      },
    );
    return controller.stream;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _ReusableCancellationRepository implements SocialRepository {
  var calls = 0;

  @override
  Stream<PublicUploadProgress> uploadPublicMedia(PublicMediaDraft media) {
    calls += 1;
    if (calls > 1) {
      return Stream<PublicUploadProgress>.value(
        PublicUploadProgress(
          stage: UploadStage.complete,
          totalBytes: media.byteLength,
          bytesSent: media.byteLength,
          url: 'https://example.test/upload-3.jpg',
        ),
      );
    }
    late final StreamController<PublicUploadProgress> controller;
    controller = StreamController<PublicUploadProgress>(
      onListen: () => controller.add(
        PublicUploadProgress(
          stage: UploadStage.uploading,
          totalBytes: media.byteLength,
        ),
      ),
    );
    return controller.stream;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
