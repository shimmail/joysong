import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';
import 'package:joysong_flutter/features/social/presentation/social_page.dart';

void main() {
  test('public media batch limits active uploads to two', () async {
    final repository = _ControlledSocialRepository();
    final controller = SocialController(repository);
    addTearDown(controller.dispose);
    final uploaded = <String>[];

    final operation = controller.uploadPublicMediaBatch(
      List.generate(5, (index) => _draft('upload-$index')),
      onUploaded: (media, _) => uploaded.add(media.uploadId),
    );

    await _waitUntil(() => repository.uploadCalls.length == 2);
    expect(repository.maxActiveUploads, 2);

    repository.succeed('upload-1');
    await _waitUntil(() => repository.uploadCalls.length == 3);
    repository.succeed('upload-2');
    await _waitUntil(() => repository.uploadCalls.length == 4);
    repository.succeed('upload-0');
    await _waitUntil(() => repository.uploadCalls.length == 5);
    repository.succeed('upload-3');
    repository.succeed('upload-4');

    final result = await operation.result;
    expect(result.succeeded, isTrue);
    expect(repository.maxActiveUploads, 2);
    expect(
      uploaded,
      containsAll(<String>[
        'upload-0',
        'upload-1',
        'upload-2',
        'upload-3',
        'upload-4',
      ]),
    );
  });

  testWidgets(
    'partial failure keeps successes, preserves order, and delays next group',
    (tester) async {
      final drafts = List.generate(3, (index) => _draft('upload-$index'));
      final repository = _ControlledSocialRepository();
      final controller = SocialController(repository);
      addTearDown(controller.dispose);
      final picker = _QueuedPicker(drafts);

      await _pumpEditor(tester, controller, picker.call);
      await _fillRequiredFields(tester);
      await _addImage(tester, const Key('diary-add-image'));
      await _addImage(tester, const Key('diary-add-image'));
      await _addImage(tester, const Key('diary-add-before-image'));

      await _submit(tester);
      await _pumpUntil(tester, () => repository.uploadCalls.length == 2);
      expect(
        repository.uploadCalls.map((call) => call.media.uploadId),
        <String>['upload-0', 'upload-1'],
      );
      expect(repository.maxActiveUploads, 2);

      repository.succeed('upload-1');
      repository.fail('upload-0', '网络中断');
      await _pumpUntil(tester, () => find.text('网络中断').evaluate().isNotEmpty);
      expect(repository.uploadCalls, hasLength(2));
      expect(repository.publishCalls, 0);

      await _submit(tester);
      await _pumpUntil(tester, () => repository.uploadCalls.length == 3);
      expect(repository.uploadCalls.last.media.uploadId, 'upload-0');
      repository.succeed('upload-0');
      await _pumpUntil(tester, () => repository.uploadCalls.length == 4);
      expect(repository.uploadCalls.last.media.uploadId, 'upload-2');
      repository.succeed('upload-2');
      await _pumpUntil(tester, () => repository.publishCalls == 1);

      expect(repository.publishedDrafts.single.images, <String>[
        'https://img.example/upload-0.jpg',
        'https://img.example/upload-1.jpg',
      ]);
      expect(repository.publishedDrafts.single.beforeImages, <String>[
        'https://img.example/upload-2.jpg',
      ]);
      expect(repository.publishedDrafts.single.afterImages, isEmpty);
      expect(
        repository.uploadCalls.where(
          (call) => call.media.uploadId == 'upload-1',
        ),
        hasLength(1),
      );
    },
  );

  testWidgets('diary retry does not upload a successful image again', (
    tester,
  ) async {
    final drafts = [_draft('upload-0')];
    final repository = _ControlledSocialRepository(publishFailures: 1);
    final controller = SocialController(repository);
    addTearDown(controller.dispose);
    final picker = _QueuedPicker(drafts);

    await _pumpEditor(tester, controller, picker.call);
    await _fillRequiredFields(tester);
    await _addImage(tester, const Key('diary-add-image'));
    await _submit(tester);
    await _pumpUntil(tester, () => repository.uploadCalls.length == 1);
    repository.succeed('upload-0');
    await _pumpUntil(tester, () => repository.publishCalls == 1);
    await _pumpUntil(
      tester,
      () => find.text('日记发布失败，请重试').evaluate().isNotEmpty,
    );

    await _submit(tester);
    await _pumpUntil(tester, () => repository.publishCalls == 2);

    expect(repository.uploadCalls, hasLength(1));
    expect(repository.publishedDrafts.last.images, <String>[
      'https://img.example/upload-0.jpg',
    ]);
  });

  testWidgets('disposing editor cancels active uploads and stops scheduling', (
    tester,
  ) async {
    final drafts = List.generate(3, (index) => _draft('upload-$index'));
    final repository = _CancellationSocialRepository();
    final controller = SocialController(repository);
    addTearDown(controller.dispose);
    final picker = _QueuedPicker(drafts);

    await _pumpEditor(tester, controller, picker.call);
    await _fillRequiredFields(tester);
    await _addImage(tester, const Key('diary-add-image'));
    await _addImage(tester, const Key('diary-add-image'));
    await _addImage(tester, const Key('diary-add-image'));
    await _submit(tester);
    await _pumpUntil(tester, () => repository.startedUploads == 2);

    await tester.pumpWidget(const SizedBox.shrink());
    await _pumpUntil(tester, () => repository.cancelledUploads == 2);

    expect(repository.startedUploads, 2);
  });
}

Future<void> _pumpEditor(
  WidgetTester tester,
  SocialController controller,
  DiaryImagePicker picker,
) async {
  await tester.binding.setSurfaceSize(const Size(900, 1400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      locale: const Locale('zh'),
      home: DiaryEditorPage(controller: controller, imagePicker: picker),
    ),
  );
}

Future<void> _fillRequiredFields(WidgetTester tester) async {
  await tester.enterText(find.byType(TextFormField).at(0), '测试日记');
  await tester.enterText(find.byType(TextFormField).at(1), '真实体验记录');
}

Future<void> _addImage(WidgetTester tester, Key key) async {
  final finder = find.byKey(key);
  await tester.ensureVisible(finder);
  await tester.tap(finder);
  await tester.pump();
}

Future<void> _submit(WidgetTester tester) async {
  final finder = find.byKey(const Key('diary-submit'));
  for (var attempt = 0;
      finder.evaluate().isEmpty && attempt < 10;
      attempt += 1) {
    await tester.drag(find.byType(ListView), const Offset(0, -300));
    await tester.pump();
  }
  expect(finder, findsOneWidget);
  await tester.ensureVisible(finder);
  await tester.tap(finder);
  await tester.pump();
}

Future<void> _pumpUntil(WidgetTester tester, bool Function() condition) async {
  for (var attempt = 0; attempt < 100; attempt += 1) {
    if (condition()) return;
    await tester.pump(const Duration(milliseconds: 10));
  }
  fail('Condition was not met before the widget-test deadline.');
}

Future<void> _waitUntil(bool Function() condition) async {
  for (var attempt = 0; attempt < 100; attempt += 1) {
    if (condition()) return;
    await Future<void>.delayed(Duration.zero);
  }
  fail('Condition was not met before the unit-test deadline.');
}

PublicMediaDraft _draft(String uploadId) => PublicMediaDraft(
      uploadId: uploadId,
      localPath: 'missing-$uploadId.png',
      byteLength: 68,
      fileName: '$uploadId.png',
      mimeType: 'image/png',
      purpose: PublicMediaPurpose.diary,
    );

final class _QueuedPicker {
  _QueuedPicker(this._drafts);

  final List<PublicMediaDraft> _drafts;
  var _index = 0;

  Future<PublicMediaDraft?> call() async => _drafts[_index++];
}

final class _UploadOutcome {
  const _UploadOutcome.success(this.url) : error = null;
  const _UploadOutcome.failure(this.error) : url = null;

  final String? url;
  final String? error;
}

final class _CancellationSocialRepository implements SocialRepository {
  int startedUploads = 0;
  int cancelledUploads = 0;

  @override
  Stream<PublicUploadProgress> uploadPublicMedia(PublicMediaDraft media) {
    late final StreamController<PublicUploadProgress> controller;
    controller = StreamController<PublicUploadProgress>(
      onListen: () {
        startedUploads += 1;
        controller.add(
          PublicUploadProgress(
            stage: UploadStage.uploading,
            totalBytes: media.byteLength,
          ),
        );
      },
      onCancel: () {
        cancelledUploads += 1;
      },
    );
    return controller.stream;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _UploadCall {
  _UploadCall(this.media);

  final PublicMediaDraft media;
  final Completer<_UploadOutcome> outcome = Completer<_UploadOutcome>();
}

final class _ControlledSocialRepository implements SocialRepository {
  _ControlledSocialRepository({this.publishFailures = 0});

  final List<_UploadCall> uploadCalls = [];
  final List<DiaryDraft> publishedDrafts = [];
  int publishFailures;
  int publishCalls = 0;
  int activeUploads = 0;
  int maxActiveUploads = 0;

  @override
  Stream<PublicUploadProgress> uploadPublicMedia(
    PublicMediaDraft media,
  ) async* {
    final call = _UploadCall(media);
    uploadCalls.add(call);
    activeUploads += 1;
    if (activeUploads > maxActiveUploads) maxActiveUploads = activeUploads;
    final outcome = await call.outcome.future;
    activeUploads -= 1;
    final error = outcome.error;
    if (error != null) {
      yield PublicUploadProgress(
        stage: UploadStage.failed,
        totalBytes: media.byteLength,
        message: error,
        retryable: true,
      );
      return;
    }
    yield PublicUploadProgress(
      stage: UploadStage.complete,
      bytesSent: media.byteLength,
      totalBytes: media.byteLength,
      url: outcome.url,
    );
  }

  void succeed(String uploadId) {
    _pendingCall(uploadId).outcome.complete(
          _UploadOutcome.success('https://img.example/$uploadId.jpg'),
        );
  }

  void fail(String uploadId, String message) {
    _pendingCall(uploadId).outcome.complete(_UploadOutcome.failure(message));
  }

  _UploadCall _pendingCall(String uploadId) => uploadCalls.lastWhere(
        (call) => call.media.uploadId == uploadId && !call.outcome.isCompleted,
      );

  @override
  Future<Diary> publishDiary(DiaryDraft draft) async {
    publishCalls += 1;
    publishedDrafts.add(draft);
    if (publishFailures > 0) {
      publishFailures -= 1;
      throw StateError('publish failed');
    }
    return Diary(
      id: 'diary-$publishCalls',
      title: draft.title,
      userId: 'user-1',
      authorName: 'Tester',
      content: draft.content,
      images: draft.images,
      tags: draft.tags,
      likeCount: 0,
      commentCount: 0,
      favoriteCount: 0,
      isLiked: false,
      status: draft.status.wireValue,
      beforeImages: draft.beforeImages,
      afterImages: draft.afterImages,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
