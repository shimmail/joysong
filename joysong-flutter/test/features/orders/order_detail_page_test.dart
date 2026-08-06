import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/presentation/order_detail_page.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

import 'order_test_fixtures.dart';

void main() {
  testWidgets('shows user verification code without professional verify action',
      (
    tester,
  ) async {
    final repository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          status: OrderStatus.consultationPaid,
          verifyCode: '123456',
        ),
      ];
    final controller = OrderDetailController(
      repository,
      orderId: 'order-1',
    );

    await tester.pumpWidget(
      MaterialApp(home: OrderDetailPage(controller: controller)),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('order-verification-code')), findsOneWidget);
    expect(find.text('123456'), findsOneWidget);
    expect(find.textContaining('用户端不会自行核销'), findsOneWidget);
    expect(find.textContaining('专业端核销'), findsNothing);
  });

  testWidgets('only exposes actions allowed by current order status', (
    tester,
  ) async {
    final repository = FakeOrdersRepository()
      ..orders = [sampleOrder(status: OrderStatus.verified)];
    final controller = OrderDetailController(
      repository,
      orderId: 'order-1',
    );

    await tester.pumpWidget(
      MaterialApp(home: OrderDetailPage(controller: controller)),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('pay-balance-button')), findsOneWidget);
    expect(find.byKey(const Key('pay-consultation-button')), findsNothing);
    expect(find.byKey(const Key('confirm-completion-button')), findsNothing);
  });

  testWidgets('submits a real order review through SocialController', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final ordersRepository = FakeOrdersRepository()
      ..orders = [sampleOrder(status: OrderStatus.completed)];
    final socialRepository = _FakeSocialRepository();
    final orderController = OrderDetailController(
      ordersRepository,
      orderId: 'order-1',
    );
    final socialController = SocialController(socialRepository);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        supportedLocales: const [Locale('en')],
        home: OrderDetailPage(
          controller: orderController,
          socialController: socialController,
        ),
      ),
    );
    await tester.pumpAndSettle();

    final reviewButton = find.byKey(const Key('submit-review-button'));
    expect(reviewButton, findsOneWidget);
    await tester.scrollUntilVisible(reviewButton, 300);
    await tester.tap(reviewButton);
    await tester.pumpAndSettle();
    expect(find.text('Write a review'), findsNWidgets(2));
    await tester.enterText(
      find.byKey(const Key('review-content-field')),
      'Excellent service',
    );
    await tester.tap(find.byKey(const Key('review-rating-4')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('review-submit-button')));
    await tester.pumpAndSettle();

    expect(socialRepository.reviewOrderId, 'order-1');
    expect(socialRepository.reviewDraft?.rating, 4);
    expect(socialRepository.reviewDraft?.content, 'Excellent service');
    expect(find.text('Review submitted'), findsOneWidget);
  });

  testWidgets('uploads and passes optional refund evidence URL', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final ordersRepository = FakeOrdersRepository()
      ..orders = [sampleOrder(status: OrderStatus.consultationPaid)];
    final socialController = SocialController(_FakeSocialRepository());
    final orderController = OrderDetailController(
      ordersRepository,
      orderId: 'order-1',
    );
    await tester.pumpWidget(
      MaterialApp(
        home: OrderDetailPage(
          controller: orderController,
          socialController: socialController,
          uploadImage: (_) async => 'https://cdn.example/evidence.jpg',
        ),
      ),
    );
    await tester.pumpAndSettle();
    final refundButton = find.byKey(const Key('request-refund-button'));
    await tester.scrollUntilVisible(refundButton, 300);
    await tester.tap(refundButton);
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('refund-reason-field')),
      '行程变化',
    );
    await tester.tap(find.text('Add optional evidence'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Evidence attached'), findsOneWidget);
    await tester.tap(find.byKey(const Key('refund-submit-button')));
    await tester.pumpAndSettle();

    expect(ordersRepository.lastRefundReason, '行程变化');
    expect(
      ordersRepository.lastRefundEvidenceUrl,
      'https://cdn.example/evidence.jpg',
    );
  });
}

class _FakeSocialRepository implements SocialRepository {
  String? reviewOrderId;
  ReviewDraft? reviewDraft;

  @override
  Future<Review> submitOrderReview(String orderId, ReviewDraft draft) async {
    reviewOrderId = orderId;
    reviewDraft = draft;
    return Review(
      id: 'review-1',
      orderId: orderId,
      userId: 'user-1',
      rating: draft.rating,
      content: draft.content,
      tags: draft.tags,
      images: draft.images,
    );
  }

  @override
  Stream<PublicUploadProgress> uploadPublicMedia(PublicMediaDraft media) =>
      Stream<PublicUploadProgress>.value(
        const PublicUploadProgress(
          stage: UploadStage.complete,
          url: 'https://cdn.example/evidence.jpg',
        ),
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
