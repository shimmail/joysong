import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_content_card.dart';

void main() {
  testWidgets('long project name keeps the diary author visible',
      (tester) async {
    const authorName = 'Visible username';

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 300,
              child: DiaryPreviewCard(
                title: 'Diary title',
                content: 'Diary content',
                authorName: authorName,
                authorAvatar: '',
                publishDate: '',
                projectName:
                    'I-01 Photon Skin Rejuvenation Test With A Very Long English Name',
                images: const [],
                beforeImages: const [],
                afterImages: const [],
                likeCount: 0,
                favoriteCount: 0,
                commentCount: 0,
                onTap: () {},
              ),
            ),
          ),
        ),
      ),
    );

    expect(tester.takeException(), isNull);
    expect(tester.getSize(find.text(authorName)).width, greaterThan(0));
  });
}
