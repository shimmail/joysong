import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/presentation/order_detail_page.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

import 'order_test_fixtures.dart';

void main() {
  testWidgets('shows settlement pending and retries a support-data error',
      (tester) async {
    final repository = FakeOrdersRepository()
      ..orders = [sampleOrder(status: OrderStatus.completed)]
      ..settlementError = const ApiException(
        message: 'SETTLEMENT_NOT_GENERATED',
        httpStatus: 409,
        businessCode: 409,
      );
    final controller = OrderDetailController(repository, orderId: 'order-1');

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: OrderDetailPage(controller: controller),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('结算信息生成中。'), findsOneWidget);

    repository.settlementError = const FormatException('结算详情不是 JSON 对象');
    await controller.retrySettlement();
    await tester.pumpAndSettle();
    expect(find.text('结算详情不是 JSON 对象'), findsOneWidget);

    repository.settlementError = null;
    final retryButton = find.byKey(const Key('settlement-retry-button'));
    await tester.ensureVisible(retryButton);
    await tester.pumpAndSettle();
    await tester.tap(retryButton);
    await tester.pumpAndSettle();
    expect(find.text(r'$1,280.50'), findsAtLeastNWidgets(2));
  });

  testWidgets('leaves order details after deleting an order', (tester) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = FakeOrdersRepository()
      ..orders = [sampleOrder(status: OrderStatus.cancelled)];
    final controller = OrderDetailController(repository, orderId: 'order-1');

    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: FilledButton(
                onPressed: () => Navigator.of(context).push<void>(
                  MaterialPageRoute(
                    builder: (_) => OrderDetailPage(controller: controller),
                  ),
                ),
                child: const Text('Open order'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open order'));
    await tester.pumpAndSettle();

    final deleteButton = find.byKey(const Key('delete-order-button'));
    await tester.scrollUntilVisible(deleteButton, 300);
    await tester.tap(deleteButton);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(repository.actionCalls, 1);
    expect(find.text('Open order'), findsOneWidget);
    expect(find.byType(OrderDetailPage), findsNothing);
  });

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
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: OrderDetailPage(controller: controller),
      ),
    );
    await tester.pumpAndSettle();

    final verificationCode = find.byKey(
      const Key('order-verification-code'),
      skipOffstage: false,
    );
    await tester.scrollUntilVisible(verificationCode, 300);
    expect(verificationCode, findsOneWidget);
    expect(tester.widget<Text>(verificationCode).data, '123456');
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -120));
    await tester.pumpAndSettle();
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

    final payBalanceButton = find.byKey(
      const Key('pay-balance-button'),
      skipOffstage: false,
    );
    await tester.scrollUntilVisible(payBalanceButton, 300);
    expect(payBalanceButton, findsOneWidget);
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
    expect(find.text('Write a review'), findsOneWidget);
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

  testWidgets('loads and updates an existing review from order details', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final ordersRepository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          status: OrderStatus.pendingSettlement,
          hasReview: true,
        ),
      ];
    final socialRepository = _FakeSocialRepository()
      ..existingReview = Review(
        id: 'review-1',
        orderId: 'order-1',
        userId: 'user-1',
        rating: 4,
        content: 'Original review',
        tags: const ['clean'],
        images: const ['https://cdn.example/review.jpg'],
      );
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

    final editButton = find.byKey(const Key('edit-review-button'));
    await tester.scrollUntilVisible(editButton, 300);
    await tester.tap(editButton);
    await tester.pumpAndSettle();

    expect(find.text('Edit review'), findsOneWidget);
    expect(find.text('Original review'), findsOneWidget);
    expect(find.text('clean'), findsOneWidget);
    expect(find.byKey(const Key('review-image-preview-0')), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('review-content-field')),
      'Updated review',
    );
    await tester.tap(find.byKey(const Key('review-rating-5')));
    await tester.tap(find.byKey(const Key('review-submit-button')));
    await tester.pumpAndSettle();

    expect(socialRepository.updatedReviewId, 'review-1');
    expect(socialRepository.updatedReviewDraft?.content, 'Updated review');
    expect(socialRepository.updatedReviewDraft?.rating, 5);
    expect(socialRepository.reviewOrderId, isNull);
    expect(find.text('Review updated'), findsOneWidget);
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
    await tester.tap(find.byKey(const Key('refund-reason-0')));
    await tester.tap(find.text('Add optional evidence'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Evidence attached'), findsOneWidget);
    await tester.tap(find.byKey(const Key('refund-submit-button')));
    await tester.pumpAndSettle();

    expect(ordersRepository.lastRefundReason, 'Changed my mind');
    expect(
      ordersRepository.lastRefundEvidenceUrl,
      'https://cdn.example/evidence.jpg',
    );
  });
}

class _FakeSocialRepository implements SocialRepository {
  Review? existingReview;
  String? reviewOrderId;
  ReviewDraft? reviewDraft;
  String? updatedReviewId;
  ReviewDraft? updatedReviewDraft;

  @override
  Future<Review?> getOrderReview(String orderId) async => existingReview;

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
  Future<Review> updateReview(String reviewId, ReviewDraft draft) async {
    updatedReviewId = reviewId;
    updatedReviewDraft = draft;
    final current = existingReview!;
    return existingReview = Review(
      id: current.id,
      orderId: current.orderId,
      userId: current.userId,
      userName: current.userName,
      doctorId: current.doctorId,
      rating: draft.rating,
      content: draft.content,
      tags: draft.tags,
      images: draft.images,
      createdAt: current.createdAt,
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
