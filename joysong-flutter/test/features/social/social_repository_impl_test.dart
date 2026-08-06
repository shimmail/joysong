import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/social/data/public_media_upload.dart';
import 'package:joysong_flutter/features/social/data/social_remote_data_source.dart';
import 'package:joysong_flutter/features/social/data/social_repository_impl.dart';
import 'package:joysong_flutter/features/social/domain/content_safety.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';

void main() {
  test('rejects private identity material before compression and upload',
      () async {
    final preprocessor = _FakePreprocessor();
    final uploader = _FakeUploader();
    final repository = SocialRepositoryImpl(
      remoteDataSource: _UnusedRemote(),
      imagePreprocessor: preprocessor,
      mediaUploader: uploader,
    );
    final media = PublicMediaDraft(
      bytes: _pngBytes(),
      fileName: 'id-card.png',
      mimeType: 'image/png',
      purpose: PublicMediaPurpose.diary,
      privacy: MediaPrivacy.privateIdentityMaterial,
    );

    await expectLater(
      repository.uploadPublicMedia(media),
      emitsError(isA<PrivateMaterialUploadException>()),
    );
    expect(preprocessor.calls, 0);
    expect(uploader.calls, 0);
  });

  test('preprocesses public image and exposes determinate upload progress',
      () async {
    final preprocessor = _FakePreprocessor();
    final uploader = _FakeUploader();
    final repository = SocialRepositoryImpl(
      remoteDataSource: _UnusedRemote(),
      imagePreprocessor: preprocessor,
      mediaUploader: uploader,
    );
    final media = PublicMediaDraft(
      bytes: _pngBytes(),
      fileName: 'diary.png',
      mimeType: 'image/png',
      purpose: PublicMediaPurpose.diary,
    );

    final progress = await repository.uploadPublicMedia(media).toList();

    expect(progress.map((item) => item.stage), [
      UploadStage.queued,
      UploadStage.compressing,
      UploadStage.uploading,
      UploadStage.complete,
    ]);
    expect(progress.last.url, 'https://img/diary.png');
    expect(uploader.request?.folder, 'diaries');
  });

  test('validates server-aligned diary and review limits locally', () {
    expect(
      () => const DiaryDraft(title: '', content: '正文').validate(),
      throwsArgumentError,
    );
    expect(
      () => const ReviewDraft(rating: 0, content: '评价').validate(),
      throwsArgumentError,
    );
    expect(
      () => const DiaryDraft(
        title: '标题',
        content: '正文',
        images: ['https://img/a,b.jpg'],
      ).validate(),
      throwsArgumentError,
    );
  });

  test('content safety warns without claiming automated moderation', () {
    const safety = SocialContentSafety();
    final result = safety.assess('联系我 13800000000，保证治愈');

    expect(result.needsConfirmation, isTrue);
    expect(result.notices, hasLength(2));
  });
}

final class _UnusedRemote implements SocialRemoteDataSource {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _FakePreprocessor implements PublicImagePreprocessor {
  int calls = 0;

  @override
  Future<PublicMediaDraft> prepare(
    PublicMediaDraft source,
    PublicImageCompressionPolicy policy,
  ) async {
    calls += 1;
    return source;
  }
}

final class _FakeUploader implements PublicMediaUploader {
  int calls = 0;
  PublicUploadRequest? request;

  @override
  Stream<PublicUploadProgress> upload(PublicUploadRequest request) async* {
    calls += 1;
    this.request = request;
    yield PublicUploadProgress(
      stage: UploadStage.uploading,
      bytesSent: request.bytes.length ~/ 2,
      totalBytes: request.bytes.length,
    );
    yield PublicUploadProgress(
      stage: UploadStage.complete,
      bytesSent: request.bytes.length,
      totalBytes: request.bytes.length,
      url: 'https://img/diary.png',
    );
  }
}

Uint8List _pngBytes() => Uint8List.fromList([
      0x89,
      0x50,
      0x4e,
      0x47,
      0x0d,
      0x0a,
      0x1a,
      0x0a,
      0,
      0,
      0,
      0,
    ]);
