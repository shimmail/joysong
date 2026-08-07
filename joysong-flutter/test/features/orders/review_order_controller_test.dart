import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/presentation/review_order_controller.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';

void main() {
  test('initializes an editor from an existing review', () {
    final controller = ReviewOrderController(
      initialReview: Review(
        id: 'review-1',
        orderId: 'order-1',
        userId: 'user-1',
        rating: 4,
        content: 'Original review',
        tags: const ['clean', 'friendly'],
        images: const ['https://img/one.jpg', 'https://img/two.jpg'],
      ),
    );
    addTearDown(controller.dispose);

    expect(controller.rating, 4);
    expect(controller.content, 'Original review');
    expect(controller.tagsText, 'clean friendly');
    expect(controller.imageUrls, hasLength(2));
    expect(controller.createDraft().content, 'Original review');
  });

  test('uploads unique images and enforces the six image limit', () async {
    final controller = ReviewOrderController();
    addTearDown(controller.dispose);
    var uploadCalls = 0;

    Future<String?> upload(String url) async {
      uploadCalls += 1;
      return url;
    }

    await controller.pickAndUploadImage(() => upload('https://img/1.jpg'));
    await controller.pickAndUploadImage(() => upload('https://img/1.jpg'));
    for (var index = 2; index <= ReviewDraft.maxImageCount; index++) {
      await controller.pickAndUploadImage(
        () => upload('https://img/$index.jpg'),
      );
    }

    expect(controller.imageUrls, hasLength(ReviewDraft.maxImageCount));
    expect(controller.imageUrls.toSet(), hasLength(ReviewDraft.maxImageCount));
    expect(controller.canAddImage, isFalse);
    await controller.pickAndUploadImage(() => upload('https://img/7.jpg'));
    expect(uploadCalls, ReviewDraft.maxImageCount + 1);
    expect(controller.imageUrls, isNot(contains('https://img/7.jpg')));
  });

  test('exposes uploading and localized-error-ready failure states', () async {
    final controller = ReviewOrderController()..setContent('Great service');
    addTearDown(controller.dispose);
    final result = Completer<String?>();

    final upload = controller.pickAndUploadImage(() => result.future);
    expect(controller.uploading, isTrue);
    expect(controller.canSubmit, isFalse);

    result.completeError(StateError('network unavailable'));
    await upload;

    expect(controller.uploading, isFalse);
    expect(controller.uploadFailed, isTrue);
    expect(controller.canSubmit, isTrue);
  });

  test('creates a trimmed multi-image draft and supports removal', () async {
    final controller = ReviewOrderController()
      ..setRating(4)
      ..setContent('  Excellent service  ')
      ..setTags('clean  friendly');
    addTearDown(controller.dispose);
    await controller.pickAndUploadImage(() async => 'https://img/one.jpg');
    await controller.pickAndUploadImage(() async => 'https://img/two.jpg');

    controller.removeImage('https://img/one.jpg');
    final draft = controller.createDraft();

    expect(draft.rating, 4);
    expect(draft.content, 'Excellent service');
    expect(draft.tags, ['clean', 'friendly']);
    expect(draft.images, ['https://img/two.jpg']);
  });

  test('review draft rejects more than six images', () {
    expect(
      () => ReviewDraft(
        rating: 5,
        content: 'Great',
        images: List.generate(7, (index) => 'https://img/$index.jpg'),
      ).validate(),
      throwsArgumentError,
    );
  });

  test('rejects image URLs that exceed the database CSV capacity', () async {
    final controller = ReviewOrderController();
    addTearDown(controller.dispose);

    await controller.pickAndUploadImage(
      () async =>
          'https://img/${List.filled(ReviewDraft.maxImagesEncodedLength, 'a').join()}',
    );

    expect(controller.imageUrls, isEmpty);
    expect(controller.uploadFailed, isTrue);
  });
}
