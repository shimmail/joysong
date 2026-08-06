import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/public_upload_controller.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

void main() {
  test('like is optimistic and rolls back after a failed write', () async {
    final repository = _FakeSocialRepository();
    final completer = Completer<void>();
    repository.likeWrite = completer.future;
    final controller = SocialController(repository)
      ..seedLikeStatus(
        LikeTargetType.diary,
        'diary-1',
        const EngagementStatus(active: false, count: 2),
      );

    final operation = controller.toggleLike(LikeTargetType.diary, 'diary-1');
    expect(
      controller.likeStatus(LikeTargetType.diary, 'diary-1'),
      isA<EngagementStatus>()
          .having((status) => status.active, 'active', isTrue)
          .having((status) => status.count, 'count', 3),
    );

    completer.completeError(const ApiException(message: '网络失败'));
    final result = await operation;

    expect(result.succeeded, isFalse);
    expect(
      controller.likeStatus(LikeTargetType.diary, 'diary-1'),
      isA<EngagementStatus>()
          .having((status) => status.active, 'active', isFalse)
          .having((status) => status.count, 'count', 2),
    );
  });

  test('optimistic diary deletion restores the original list on failure',
      () async {
    final repository = _FakeSocialRepository()
      ..diaries = [_diary('diary-1'), _diary('diary-2')]
      ..deleteDiaryError = const ApiException(message: '删除失败');
    final controller = SocialController(repository);
    await controller.loadMyDiaries(refresh: true);

    final result = await controller.deleteDiary('diary-1');

    expect(result.succeeded, isFalse);
    expect(controller.diaries.map((item) => item.id), ['diary-1', 'diary-2']);
  });

  test('failed public upload remains available for an explicit retry',
      () async {
    final repository = _FakeSocialRepository();
    final uploadController = PublicUploadController(repository);
    final id = uploadController.enqueue(
      PublicMediaDraft(
        bytes: Uint8List.fromList(List.filled(12, 1)),
        fileName: 'diary.jpg',
        mimeType: 'image/jpeg',
        purpose: PublicMediaPurpose.diary,
      ),
    );
    await _waitUntil(() => uploadController.task(id)?.canRetry ?? false);

    expect(uploadController.task(id)?.attempts, 1);
    expect(await uploadController.retry(id), isTrue);
    expect(uploadController.task(id)?.progress.stage, UploadStage.complete);
    expect(uploadController.task(id)?.attempts, 2);
    uploadController.dispose();
  });
}

final class _FakeSocialRepository implements SocialRepository {
  List<Diary> diaries = const [];
  Future<void>? likeWrite;
  Object? deleteDiaryError;
  int uploadAttempts = 0;

  @override
  Future<List<Diary>> getMyDiaries({int offset = 0, int limit = 50}) async =>
      diaries.skip(offset).take(limit).toList();

  @override
  Future<void> deleteDiary(String id) async {
    final error = deleteDiaryError;
    if (error != null) {
      throw error;
    }
  }

  @override
  Future<void> setLiked(LikeTargetType type, String targetId, bool liked) =>
      likeWrite ?? Future.value();

  @override
  Stream<PublicUploadProgress> uploadPublicMedia(
      PublicMediaDraft media) async* {
    uploadAttempts += 1;
    if (uploadAttempts == 1) {
      yield const PublicUploadProgress(
        stage: UploadStage.failed,
        message: '网络失败',
        retryable: true,
      );
      return;
    }
    yield const PublicUploadProgress(
      stage: UploadStage.complete,
      url: 'https://img/retried.jpg',
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Diary _diary(String id) => Diary(
      id: id,
      title: '日记 $id',
      userId: 'user-1',
      authorName: '用户',
      content: '内容',
      images: const [],
      tags: const [],
      likeCount: 0,
      commentCount: 0,
      favoriteCount: 0,
      isLiked: false,
      status: 'published',
    );

Future<void> _waitUntil(bool Function() condition) async {
  for (var index = 0; index < 20; index += 1) {
    if (condition()) {
      return;
    }
    await Future<void>.delayed(Duration.zero);
  }
  fail('condition was not reached');
}
