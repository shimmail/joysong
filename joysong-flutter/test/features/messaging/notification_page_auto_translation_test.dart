import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_controllers.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_pages.dart';

import '../../core/translation/translation_test_fixtures.dart';

void main() {
  testWidgets(
    'NotificationPage translates all order-related target title and content fields',
    (tester) async {
      final translations = RecordingTranslationRepository();
      final autoController = _activeAutoController(translations);
      final controller = NotificationController(
        _NotificationRepository(notifications: _orderAndIdentityNotifications),
      );
      addTearDown(autoController.dispose);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          autoController,
          NotificationPage(
            controller: controller,
            enableAutoTranslation: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(_mountedRequests(tester), const {
        ('general', 'notification:n-order', 'title', '订单已取消'),
        ('general', 'notification:n-order', 'content', '请查看订单详情'),
        ('general', 'notification:n-refund', 'title', '退款已批准'),
        ('general', 'notification:n-refund', 'content', '退款申请已通过'),
        ('general', 'notification:n-service', 'title', '服务消息'),
        (
          'general',
          'notification:n-service',
          'content',
          '医生回复了订单服务会话',
        ),
      });
      expect(
        translations.calls.map((call) => call.text),
        isNot(contains('身份申请待审核')),
      );
      expect(
        translations.calls.any((call) => call.text.contains('order-1')),
        isFalse,
      );
    },
  );

  testWidgets('NotificationPage defaults off under an active translation scope',
      (tester) async {
    final translations = RecordingTranslationRepository();
    final autoController = _activeAutoController(translations);
    final controller = NotificationController(
      _NotificationRepository(notifications: _orderAndIdentityNotifications),
    );
    addTearDown(autoController.dispose);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _host(autoController, NotificationPage(controller: controller)),
    );
    await tester.pumpAndSettle();

    expect(find.text('订单已取消'), findsOneWidget);
    expect(translations.calls, isEmpty);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
  });

  testWidgets('non-order notifications make zero calls', (tester) async {
    final translations = RecordingTranslationRepository();
    final autoController = _activeAutoController(translations);
    final controller = NotificationController(
      _NotificationRepository(notifications: _nonOrderNotifications),
    );
    addTearDown(autoController.dispose);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _host(
        autoController,
        NotificationPage(
          controller: controller,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(translations.calls, isEmpty);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
  });

  testWidgets(
      'blank or duplicate normalized notification IDs remain source-only',
      (tester) async {
    final translations = RecordingTranslationRepository();
    final autoController = _activeAutoController(translations);
    final controller = NotificationController(
      _NotificationRepository(notifications: _unstableIdentityNotifications),
    );
    addTearDown(autoController.dispose);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _host(
        autoController,
        NotificationPage(
          controller: controller,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('空通知标题'), findsOneWidget);
    expect(find.text('重复通知标题甲'), findsOneWidget);
    expect(find.text('重复通知标题乙'), findsOneWidget);
    expect(translations.calls, isEmpty);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
  });

  testWidgets(
      'failed notification retries only after refresh not read-state rebuilds',
      (tester) async {
    final notificationRepository = _NotificationRepository(
      notifications: [_retryNotification(isRead: false)],
    );
    final translations = RecordingTranslationRepository()
      ..failuresRemaining = 1;
    final autoController = _activeAutoController(translations);
    final controller = NotificationController(notificationRepository);
    addTearDown(autoController.dispose);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _host(
        autoController,
        NotificationPage(
          controller: controller,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(translations.calls.map((call) => call.text), ['通知刷新重试']);
    expect(find.text('通知刷新重试'), findsOneWidget);

    await tester.tap(find.text('通知刷新重试'));
    await tester.pumpAndSettle();
    await controller.markAllRead();
    await tester.pump();
    await tester.pumpWidget(
      _host(
        autoController,
        NotificationPage(
          controller: controller,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pump();
    expect(translations.calls, hasLength(1));

    notificationRepository.notifications = [_retryNotification(isRead: true)];
    await tester
        .widget<RefreshIndicator>(find.byType(RefreshIndicator))
        .onRefresh();
    await tester.pumpAndSettle();

    expect(translations.calls.map((call) => call.text), [
      '通知刷新重试',
      '通知刷新重试',
    ]);
    expect(find.text('en-US:通知刷新重试'), findsOneWidget);
  });
}

Set<(String, String, String, String)> _mountedRequests(WidgetTester tester) =>
    tester
        .widgetList<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
        .map((widget) => (
              widget.request.contentType,
              widget.request.contentId,
              widget.request.field,
              widget.request.sourceText,
            ))
        .toSet();

AutoTranslationController _activeAutoController(
  TranslationRepository repository,
) {
  final controller = AutoTranslationController(repository: repository);
  controller.synchronize(
    enabled: true,
    authenticated: true,
    targetLanguage: 'en-US',
  );
  return controller;
}

Widget _host(AutoTranslationController controller, Widget child) =>
    AutoTranslationScope(
      controller: controller,
      enabled: true,
      targetLanguage: 'en-US',
      child: MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: child,
      ),
    );

final class _NotificationRepository extends Fake
    implements MessagingRepository {
  _NotificationRepository({required this.notifications});

  List<AppNotification> notifications;

  @override
  Future<List<AppNotification>> getNotifications({int limit = 50}) async =>
      notifications;

  @override
  Future<int> getUnreadNotificationCount() async => 0;

  @override
  Future<void> markNotificationRead(String notificationId) async {}
}

AppNotification _retryNotification({required bool isRead}) => AppNotification(
      id: 'notification-retry',
      userId: 'user-1',
      type: 'order',
      title: '通知刷新重试',
      content: 'Already English',
      targetType: 'order',
      targetId: 'order-retry',
      isRead: isRead,
      createdAt: '2026-08-25T12:00:00',
    );

const _orderAndIdentityNotifications = [
  AppNotification(
    id: 'n-order',
    userId: 'user-1',
    type: 'order',
    title: '订单已取消',
    content: '请查看订单详情',
    targetType: 'order',
    targetId: 'order-1',
    isRead: false,
    createdAt: '2026-08-25T10:00:00',
  ),
  AppNotification(
    id: 'n-refund',
    userId: 'user-1',
    type: 'order',
    title: '退款已批准',
    content: '退款申请已通过',
    targetType: 'order_refund',
    targetId: 'refund-1',
    isRead: false,
    createdAt: '2026-08-25T09:00:00',
  ),
  AppNotification(
    id: 'n-service',
    userId: 'user-1',
    type: 'order',
    title: '服务消息',
    content: '医生回复了订单服务会话',
    targetType: 'order_service_conversation',
    targetId: 'conversation-1',
    isRead: false,
    createdAt: '2026-08-25T08:00:00',
  ),
  AppNotification(
    id: 'n-identity',
    userId: 'user-1',
    type: 'system',
    title: '身份申请待审核',
    content: '请耐心等待审核结果',
    targetType: 'identity_application',
    targetId: 'identity-1',
    isRead: false,
    createdAt: '2026-08-25T07:00:00',
  ),
];

const _nonOrderNotifications = [
  AppNotification(
    id: 'n-identity',
    userId: 'user-1',
    type: 'system',
    title: '身份申请待审核',
    content: '请耐心等待审核结果',
    targetType: 'identity_application',
    targetId: 'identity-1',
    isRead: false,
    createdAt: '2026-08-25T07:00:00',
  ),
  AppNotification(
    id: 'n-review',
    userId: 'user-1',
    type: 'system',
    title: '医生认证审核',
    content: '资料已通过审核',
    targetType: 'professional_doctor_review',
    targetId: 'review-1',
    isRead: false,
    createdAt: '2026-08-25T06:00:00',
  ),
];

const _unstableIdentityNotifications = [
  AppNotification(
    id: ' ',
    userId: 'user-1',
    type: 'order',
    title: '空通知标题',
    content: '空通知内容',
    targetType: 'order',
    targetId: 'order-empty',
    isRead: false,
    createdAt: '2026-08-25T05:00:00',
  ),
  AppNotification(
    id: 'n-duplicate',
    userId: 'user-1',
    type: 'order',
    title: '重复通知标题甲',
    content: '重复通知内容甲',
    targetType: 'order',
    targetId: 'order-duplicate-a',
    isRead: false,
    createdAt: '2026-08-25T04:00:00',
  ),
  AppNotification(
    id: ' n-duplicate ',
    userId: 'user-1',
    type: 'order',
    title: '重复通知标题乙',
    content: '重复通知内容乙',
    targetType: 'order',
    targetId: 'order-duplicate-b',
    isRead: false,
    createdAt: '2026-08-25T03:00:00',
  ),
];
