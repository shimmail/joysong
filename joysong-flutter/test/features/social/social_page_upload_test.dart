import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';
import 'package:joysong_flutter/features/social/presentation/social_page.dart';

void main() {
  testWidgets('selected image uploads before diary is sent to the API',
      (tester) async {
    final repository = _UploadSocialRepository();
    final controller = SocialController(repository);
    await tester.pumpWidget(
      MaterialApp(
        initialRoute: '/editor',
        routes: {
          '/': (_) => const Scaffold(
                body: SizedBox(key: Key('test-home')),
              ),
          '/editor': (_) => DiaryEditorPage(
                controller: controller,
                imagePicker: () async => PublicMediaDraft(
                  bytes: Uint8List.fromList([0xff, 0xd8, 0xff, 0xd9]),
                  fileName: 'diary.jpg',
                  mimeType: 'image/jpeg',
                  purpose: PublicMediaPurpose.diary,
                ),
              ),
        },
      ),
    );

    await tester.enterText(find.byType(TextFormField).at(0), '真实日记');
    await tester.enterText(find.byType(TextFormField).at(1), '真实体验内容');
    await tester.tap(find.byKey(const Key('diary-add-image')));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView).last, const Offset(0, -600));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('diary-submit')).first);
    await tester.pumpAndSettle();

    expect(repository.uploadedFileName, 'diary.jpg');
    expect(repository.publishedDraft?.images, ['https://cdn/diary.jpg']);
    expect(find.byKey(const Key('test-home')), findsOneWidget);
    controller.dispose();
  });
}

final class _UploadSocialRepository implements SocialRepository {
  String? uploadedFileName;
  DiaryDraft? publishedDraft;

  @override
  Stream<PublicUploadProgress> uploadPublicMedia(
    PublicMediaDraft media,
  ) async* {
    uploadedFileName = media.fileName;
    yield const PublicUploadProgress(
      stage: UploadStage.complete,
      url: 'https://cdn/diary.jpg',
    );
  }

  @override
  Future<Diary> publishDiary(DiaryDraft draft) async {
    publishedDraft = draft;
    return Diary(
      id: 'diary-1',
      title: draft.title,
      userId: 'user-1',
      authorName: 'User',
      content: draft.content,
      images: draft.images,
      tags: draft.tags,
      likeCount: 0,
      commentCount: 0,
      favoriteCount: 0,
      isLiked: false,
      status: draft.status.wireValue,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
