import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_controllers.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_pages.dart';

void main() {
  testWidgets(
      'read-only order history hides composer image send and removal actions',
      (tester) async {
    final repository = _ThreadRepository();
    final controller = DmThreadController(
      repository: repository,
      conversationId: 'conversation-order-1',
      currentUserId: 'user-1',
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        home: DmThreadPage(
          controller: controller,
          currentUserId: 'user-1',
          myPeer: _me,
          otherPeer: _consultant,
          conversationType: DmConversationType.orderService,
          sendEnabled: false,
          onPickImage: () async => 'https://cdn.example/image.jpg',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Retained service history'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    expect(find.byIcon(Icons.add_photo_alternate_outlined), findsNothing);
    expect(find.byIcon(Icons.send), findsNothing);

    await tester.longPress(find.text('Retained service history'));
    await tester.pumpAndSettle();

    expect(find.text('Copy'), findsOneWidget);
    expect(find.text('Unsend'), findsNothing);
    expect(find.text('Delete'), findsNothing);
  });

  testWidgets('DIRECT thread keeps the default composer and removal actions',
      (tester) async {
    final repository = _ThreadRepository();
    final controller = DmThreadController(
      repository: repository,
      conversationId: 'conversation-direct-1',
      currentUserId: 'user-1',
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        home: DmThreadPage(
          controller: controller,
          currentUserId: 'user-1',
          myPeer: _me,
          otherPeer: _consultant,
          onPickImage: () async => 'https://cdn.example/image.jpg',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(TextField), findsOneWidget);
    expect(find.byIcon(Icons.add_photo_alternate_outlined), findsOneWidget);
    expect(find.byIcon(Icons.send), findsOneWidget);

    await tester.longPress(find.text('Retained service history'));
    await tester.pumpAndSettle();

    expect(find.text('Unsend'), findsOneWidget);
    expect(find.text('Delete'), findsOneWidget);
  });

  testWidgets(
    'incoming direct TEXT auto translates while outgoing media peer and time are excluded',
    (tester) async {
      await _useTallSurface(tester);
      final repository = _ThreadRepository(dmMessages: _mixedMessages);
      final controller = DmThreadController(
        repository: repository,
        conversationId: 'conversation-direct-1',
        currentUserId: 'user-1',
      );
      final translations = _AutoRepository(
        translations: const {'请按时复诊': 'Please attend your follow-up on time'},
        holdResponses: true,
      );
      final autoController = _activeAutoController(translations);
      final manualCalls = <String>[];
      addTearDown(controller.dispose);
      addTearDown(autoController.dispose);

      await tester.pumpWidget(
        _autoHost(
          controller: autoController,
          child: DmThreadPage(
            controller: controller,
            currentUserId: 'user-1',
            myPeer: _me,
            otherPeer: _chineseConsultant,
            onTranslate: (text) async {
              manualCalls.add(text);
              return 'manual:$text';
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('请按时复诊'), findsOneWidget);
      expect(find.text('我的中文消息'), findsOneWidget);
      expect(find.text('王顾问'), findsOneWidget);
      expect(
        tester
            .widgetList<AutoTranslationBuilder>(
              find.byType(AutoTranslationBuilder),
            )
            .map((widget) => widget.request)
            .map(_requestRecord)
            .toSet(),
        const {
          ('message', 'message:incoming-1', 'content', '请按时复诊'),
        },
      );
      expect(translations.sources, const ['请按时复诊']);
      expect(translations.sources, isNot(contains('我的中文消息')));
      expect(translations.sources, isNot(contains('王顾问')));
      expect(
        translations.sources,
        isNot(contains('https://cdn.example/message.jpg')),
      );

      translations.completeSource('请按时复诊');
      await tester.pump();
      await tester.pump();
      expect(find.text('Please attend your follow-up on time'), findsOneWidget);

      await tester.longPress(find.text('Please attend your follow-up on time'));
      await tester.pumpAndSettle();
      expect(find.text('Show original'), findsOneWidget);
      await tester.tap(find.text('Show original'));
      await tester.pumpAndSettle();
      expect(find.text('请按时复诊'), findsOneWidget);

      await tester.longPress(find.text('请按时复诊'));
      await tester.pumpAndSettle();
      expect(find.text('Translate'), findsOneWidget);
      await tester.tap(find.text('Translate'));
      await tester.pumpAndSettle();
      expect(find.text('Please attend your follow-up on time'), findsOneWidget);
      expect(translations.sources, const ['请按时复诊']);
      expect(manualCalls, isEmpty);

      final image = find.byType(Image);
      expect(image, findsOneWidget);
      await tester.longPress(image);
      await tester.pumpAndSettle();
      expect(find.text('Copy'), findsOneWidget);
      expect(find.text('Translate'), findsNothing);
    },
  );

  testWidgets(
    'failed incoming message does not retry when Load earlier prepends a page',
    (tester) async {
      await _useTallSurface(tester);
      final repository = _ThreadRepository(
        dmLoader: (before) async => before == null
            ? const [_failedPagedMessage]
            : const [_olderPagedMessage],
      );
      final controller = DmThreadController(
        repository: repository,
        conversationId: 'conversation-direct-1',
        currentUserId: 'user-1',
        pageSize: 1,
      );
      final translations = _AutoRepository(
        translations: const {},
        failedSources: const {'分页前失败消息'},
      );
      final autoController = _activeAutoController(translations);
      addTearDown(controller.dispose);
      addTearDown(autoController.dispose);

      await tester.pumpWidget(
        _autoHost(
          controller: autoController,
          child: DmThreadPage(
            controller: controller,
            currentUserId: 'user-1',
            myPeer: _me,
            otherPeer: _consultant,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        translations.sources.where((source) => source == '分页前失败消息'),
        hasLength(1),
      );
      await tester.tap(find.text('Load earlier messages'));
      await tester.pumpAndSettle();

      expect(find.text('Earlier Latin message'), findsOneWidget);
      expect(find.text('分页前失败消息'), findsOneWidget);
      expect(
        translations.sources.where((source) => source == '分页前失败消息'),
        hasLength(1),
      );
    },
  );

  testWidgets(
    'Translate menu intent survives automatic completion while the sheet is held',
    (tester) async {
      final repository = _ThreadRepository(dmMessages: const [_heldMessage]);
      final controller = DmThreadController(
        repository: repository,
        conversationId: 'conversation-direct-1',
        currentUserId: 'user-1',
      );
      final translations = _AutoRepository(
        translations: const {'菜单等待自动完成': 'Automatic result while held'},
        holdResponses: true,
      );
      final autoController = _activeAutoController(translations);
      final manualCalls = <String>[];
      addTearDown(controller.dispose);
      addTearDown(autoController.dispose);

      await tester.pumpWidget(
        _autoHost(
          controller: autoController,
          child: DmThreadPage(
            controller: controller,
            currentUserId: 'user-1',
            myPeer: _me,
            otherPeer: _consultant,
            onTranslate: (text) async {
              manualCalls.add(text);
              return 'manual:$text';
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.longPress(find.text('菜单等待自动完成'));
      await tester.pumpAndSettle();
      expect(find.text('Translate'), findsOneWidget);

      translations.completeSource('菜单等待自动完成');
      await tester.pump();
      await tester.pump();
      await tester.tap(find.text('Translate'));
      await tester.pumpAndSettle();

      expect(find.text('Automatic result while held'), findsOneWidget);
      expect(find.text('菜单等待自动完成'), findsNothing);
      expect(manualCalls, isEmpty);
      expect(translations.sources, const ['菜单等待自动完成']);
    },
  );

  testWidgets(
    'Show original menu intent survives scope deactivation and reactivation',
    (tester) async {
      final repository = _ThreadRepository(dmMessages: const [_heldMessage]);
      final controller = DmThreadController(
        repository: repository,
        conversationId: 'conversation-direct-1',
        currentUserId: 'user-1',
      );
      final translations = _AutoRepository(
        translations: const {'菜单等待自动完成': 'Automatic result while held'},
      );
      final autoController = _activeAutoController(translations);
      final enabled = ValueNotifier<bool>(true);
      final manualCalls = <String>[];
      addTearDown(controller.dispose);
      addTearDown(autoController.dispose);
      addTearDown(enabled.dispose);

      await tester.pumpWidget(
        _toggleAutoHost(
          controller: autoController,
          enabled: enabled,
          child: DmThreadPage(
            controller: controller,
            currentUserId: 'user-1',
            myPeer: _me,
            otherPeer: _consultant,
            onTranslate: (text) async {
              manualCalls.add(text);
              return 'manual:$text';
            },
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Automatic result while held'), findsOneWidget);

      await tester.longPress(find.text('Automatic result while held'));
      await tester.pumpAndSettle();
      expect(find.text('Show original'), findsOneWidget);

      enabled.value = false;
      await tester.pump();
      await tester.pump();
      expect(find.text('菜单等待自动完成'), findsOneWidget);
      await tester.tap(find.text('Show original'));
      await tester.pumpAndSettle();

      enabled.value = true;
      await tester.pump();
      await tester.pump();
      expect(find.text('菜单等待自动完成'), findsOneWidget);
      expect(find.text('Automatic result while held'), findsNothing);
      expect(manualCalls, isEmpty);
    },
  );

  testWidgets('read-only order-service DmThreadPage also auto translates',
      (tester) async {
    final repository = _ThreadRepository(
      dmMessages: const [
        DmMessage(
          id: 'order-message-1',
          conversationId: 'conversation-order-1',
          senderId: 'consultant-1',
          content: '订单服务记录',
          messageType: 'TEXT',
          isRead: true,
          createdAt: '2026-08-21T10:05:00',
        ),
      ],
    );
    final controller = DmThreadController(
      repository: repository,
      conversationId: 'conversation-order-1',
      currentUserId: 'user-1',
    );
    final translations = _AutoRepository(
      translations: const {'订单服务记录': 'Order service history'},
    );
    final autoController = _activeAutoController(translations);
    addTearDown(controller.dispose);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _autoHost(
        controller: autoController,
        child: DmThreadPage(
          controller: controller,
          currentUserId: 'user-1',
          myPeer: _me,
          otherPeer: _consultant,
          conversationType: DmConversationType.orderService,
          sendEnabled: false,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Order service history'), findsOneWidget);
    expect(translations.calls, const [('订单服务记录', 'message')]);
  });

  testWidgets(
    'automatic failures preserve manual callback success empty feedback and stable requests',
    (tester) async {
      await _useTallSurface(tester);
      final repository = _ThreadRepository(dmMessages: _failedMessages);
      final controller = DmThreadController(
        repository: repository,
        conversationId: 'conversation-direct-1',
        currentUserId: 'user-1',
      );
      final translations = _AutoRepository(
        translations: const {},
        failedSources: const {'自动失败后手动成功', '自动失败后手动为空'},
      );
      final autoController = _activeAutoController(translations);
      final manualCalls = <String>[];
      addTearDown(controller.dispose);
      addTearDown(autoController.dispose);

      await tester.pumpWidget(
        _autoHost(
          controller: autoController,
          child: DmThreadPage(
            controller: controller,
            currentUserId: 'user-1',
            myPeer: _me,
            otherPeer: _consultant,
            onTranslate: (text) async {
              manualCalls.add(text);
              return text == '自动失败后手动成功' ? 'Manual success' : null;
            },
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(translations.sources, hasLength(2));

      await tester.longPress(find.text('自动失败后手动成功'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Translate'));
      await tester.pumpAndSettle();
      expect(find.text('Manual success'), findsOneWidget);

      await tester.longPress(find.text('自动失败后手动为空'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Translate'));
      await tester.pumpAndSettle();
      expect(
        find.text('Translation failed. Try again later.'),
        findsOneWidget,
      );
      expect(
        manualCalls,
        const ['自动失败后手动成功', '自动失败后手动为空'],
      );
      expect(translations.sources, hasLength(2));
    },
  );

  testWidgets('same-as-source automatic result remains manually translatable',
      (tester) async {
    final repository = _ThreadRepository(dmMessages: const [_sameMessage]);
    final controller = DmThreadController(
      repository: repository,
      conversationId: 'conversation-direct-1',
      currentUserId: 'user-1',
    );
    final translations = _AutoRepository(
      translations: const {'无需变化中文': '无需变化中文'},
    );
    final autoController = _activeAutoController(translations);
    final manualCalls = <String>[];
    addTearDown(controller.dispose);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _autoHost(
        controller: autoController,
        child: DmThreadPage(
          controller: controller,
          currentUserId: 'user-1',
          myPeer: _me,
          otherPeer: _consultant,
          onTranslate: (text) async {
            manualCalls.add(text);
            return 'Manual replacement';
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.longPress(find.text('无需变化中文'));
    await tester.pumpAndSettle();
    expect(find.text('Translate'), findsOneWidget);
    await tester.tap(find.text('Translate'));
    await tester.pumpAndSettle();
    expect(find.text('Manual replacement'), findsOneWidget);
    expect(manualCalls, const ['无需变化中文']);
  });

  testWidgets('manual translation wins when automatic completion arrives later',
      (tester) async {
    final repository = _ThreadRepository(dmMessages: const [_lateMessage]);
    final controller = DmThreadController(
      repository: repository,
      conversationId: 'conversation-direct-1',
      currentUserId: 'user-1',
    );
    final translations = _AutoRepository(
      translations: const {'自动稍后完成': 'Late automatic translation'},
      holdResponses: true,
    );
    final autoController = _activeAutoController(translations);
    addTearDown(controller.dispose);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _autoHost(
        controller: autoController,
        child: DmThreadPage(
          controller: controller,
          currentUserId: 'user-1',
          myPeer: _me,
          otherPeer: _consultant,
          onTranslate: (_) async => 'Manual translation first',
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.longPress(find.text('自动稍后完成'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Translate'));
    await tester.pumpAndSettle();
    expect(find.text('Manual translation first'), findsOneWidget);

    translations.completeSource('自动稍后完成');
    await tester.pump();
    await tester.pump();
    expect(find.text('Manual translation first'), findsOneWidget);
    expect(find.text('Late automatic translation'), findsNothing);
  });

  testWidgets('automatic-off DmThreadPage retains the manual action',
      (tester) async {
    final repository = _ThreadRepository(dmMessages: const [_manualOnly]);
    final controller = DmThreadController(
      repository: repository,
      conversationId: 'conversation-direct-1',
      currentUserId: 'user-1',
    );
    final translations = _AutoRepository(translations: const {});
    final autoController = _activeAutoController(translations);
    final manualCalls = <String>[];
    addTearDown(controller.dispose);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _autoHost(
        controller: autoController,
        enabled: false,
        child: DmThreadPage(
          controller: controller,
          currentUserId: 'user-1',
          myPeer: _me,
          otherPeer: _consultant,
          onTranslate: (text) async {
            manualCalls.add(text);
            return 'Manual only translation';
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(translations.sources, isEmpty);

    await tester.longPress(find.text('仅手动翻译'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Translate'));
    await tester.pumpAndSettle();
    expect(find.text('Manual only translation'), findsOneWidget);
    expect(manualCalls, const ['仅手动翻译']);
    expect(translations.sources, isEmpty);
  });

  testWidgets('CustomerServiceThreadPage stays default-off under active scope',
      (tester) async {
    final repository = _ThreadRepository(
      customerMessages: const [_customerMessage],
    );
    final controller = CustomerServiceThreadController(
      repository: repository,
      conversationId: 'customer-conversation-1',
    );
    final translations = _AutoRepository(translations: const {});
    final autoController = _activeAutoController(translations);
    addTearDown(controller.dispose);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _autoHost(
        controller: autoController,
        child: CustomerServiceThreadPage(
          controller: controller,
          currentUserId: 'user-1',
          myPeer: _me,
          otherPeer: const MessagingPeer(id: 'CS_ADMIN', name: '客服人员'),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('客服中文消息'), findsOneWidget);
    expect(translations.sources, isEmpty);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
  });
}

typedef _RequestRecord = (String, String, String, String);

_RequestRecord _requestRecord(AutoTranslationRequest request) => (
      request.contentType,
      request.contentId,
      request.field,
      request.sourceText,
    );

final class _ThreadRepository extends Fake implements MessagingRepository {
  _ThreadRepository({
    this.dmMessages = const [_message],
    this.customerMessages = const [],
    this.dmLoader,
  });

  final List<DmMessage> dmMessages;
  final List<CustomerServiceMessage> customerMessages;
  final Future<List<DmMessage>> Function(String? before)? dmLoader;

  @override
  Future<List<DmMessage>> getDmMessages(
    String conversationId, {
    int limit = 30,
    String? before,
  }) async {
    final loader = dmLoader;
    if (loader != null) return loader(before);
    return dmMessages;
  }

  @override
  Future<void> markDmConversationRead(String conversationId) async {}

  @override
  Future<void> deleteDmMessage(String messageId) async {}

  @override
  Future<List<CustomerServiceMessage>> getCustomerServiceMessages(
    String conversationId, {
    int limit = 50,
    String? before,
  }) async =>
      customerMessages;

  @override
  Future<void> markCustomerServiceConversationRead(
    String conversationId,
  ) async {}
}

final class _PendingTranslation {
  const _PendingTranslation(this.source, this.completer);

  final String source;
  final Completer<ContentTranslation> completer;
}

final class _AutoRepository implements TranslationRepository {
  _AutoRepository({
    required this.translations,
    this.failedSources = const {},
    this.holdResponses = false,
  });

  final Map<String, String> translations;
  final Set<String> failedSources;
  final bool holdResponses;
  final calls = <(String, String)>[];
  final _pending = <_PendingTranslation>[];

  List<String> get sources =>
      calls.map((call) => call.$1).toList(growable: false);

  @override
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  }) async {
    calls.add((text, contentType));
    if (failedSources.contains(text)) {
      throw StateError('automatic translation failed for $text');
    }
    if (holdResponses) {
      final completer = Completer<ContentTranslation>();
      _pending.add(_PendingTranslation(text, completer));
      return completer.future;
    }
    return _response(text, targetLanguage);
  }

  void completeSource(String source) {
    final index = _pending.indexWhere((pending) => pending.source == source);
    if (index < 0) throw StateError('No pending translation for $source');
    final pending = _pending.removeAt(index);
    pending.completer.complete(_response(source, 'en-US'));
  }

  ContentTranslation _response(String source, String targetLanguage) =>
      ContentTranslation(
        translatedText: translations[source]!,
        detectedLanguage: 'zh',
        targetLanguage: targetLanguage,
        provider: 'auto-test',
        cached: false,
      );
}

AutoTranslationController _activeAutoController(
  TranslationRepository repository,
) {
  final controller = AutoTranslationController(
    repository: repository,
    maxConcurrent: 10,
  );
  controller.synchronize(
    enabled: true,
    authenticated: true,
    targetLanguage: 'en-US',
  );
  return controller;
}

Widget _autoHost({
  required AutoTranslationController controller,
  required Widget child,
  bool enabled = true,
}) =>
    MaterialApp(
      locale: const Locale('en'),
      home: AutoTranslationScope(
        controller: controller,
        enabled: enabled,
        targetLanguage: 'en-US',
        child: child,
      ),
    );

Widget _toggleAutoHost({
  required AutoTranslationController controller,
  required ValueNotifier<bool> enabled,
  required Widget child,
}) =>
    MaterialApp(
      locale: const Locale('en'),
      home: ValueListenableBuilder<bool>(
        valueListenable: enabled,
        child: child,
        builder: (context, scopeEnabled, child) => AutoTranslationScope(
          controller: controller,
          enabled: scopeEnabled,
          targetLanguage: 'en-US',
          child: child!,
        ),
      ),
    );

Future<void> _useTallSurface(WidgetTester tester) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1200, 1800);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
}

const _me = MessagingPeer(id: 'user-1', name: 'Me');
const _consultant = MessagingPeer(id: 'consultant-1', name: 'Consultant');
const _chineseConsultant = MessagingPeer(id: 'consultant-1', name: '王顾问');
const _message = DmMessage(
  id: 'message-1',
  conversationId: 'conversation-order-1',
  senderId: 'user-1',
  content: 'Retained service history',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-21T10:05:00',
);

const _mixedMessages = [
  DmMessage(
    id: 'incoming-1',
    conversationId: 'conversation-direct-1',
    senderId: 'consultant-1',
    content: '请按时复诊',
    messageType: 'TEXT',
    isRead: true,
    createdAt: '2026-08-21T10:05:00',
  ),
  DmMessage(
    id: 'outgoing-1',
    conversationId: 'conversation-direct-1',
    senderId: 'user-1',
    content: '我的中文消息',
    messageType: 'TEXT',
    isRead: true,
    createdAt: '2026-08-21T10:06:00',
  ),
  DmMessage(
    id: 'image-1',
    conversationId: 'conversation-direct-1',
    senderId: 'consultant-1',
    content: 'https://cdn.example/message.jpg',
    messageType: 'IMAGE',
    isRead: true,
    createdAt: '2026-08-21T10:07:00',
  ),
];

const _failedMessages = [
  DmMessage(
    id: 'failed-success',
    conversationId: 'conversation-direct-1',
    senderId: 'consultant-1',
    content: '自动失败后手动成功',
    messageType: 'TEXT',
    isRead: true,
    createdAt: '2026-08-21T10:05:00',
  ),
  DmMessage(
    id: 'failed-empty',
    conversationId: 'conversation-direct-1',
    senderId: 'consultant-1',
    content: '自动失败后手动为空',
    messageType: 'TEXT',
    isRead: true,
    createdAt: '2026-08-21T10:06:00',
  ),
];

const _sameMessage = DmMessage(
  id: 'same-message',
  conversationId: 'conversation-direct-1',
  senderId: 'consultant-1',
  content: '无需变化中文',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-21T10:05:00',
);

const _manualOnly = DmMessage(
  id: 'manual-only',
  conversationId: 'conversation-direct-1',
  senderId: 'consultant-1',
  content: '仅手动翻译',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-21T10:05:00',
);

const _lateMessage = DmMessage(
  id: 'late-message',
  conversationId: 'conversation-direct-1',
  senderId: 'consultant-1',
  content: '自动稍后完成',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-21T10:05:00',
);

const _failedPagedMessage = DmMessage(
  id: 'failed-paged-message',
  conversationId: 'conversation-direct-1',
  senderId: 'consultant-1',
  content: '分页前失败消息',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-21T10:05:00',
);

const _olderPagedMessage = DmMessage(
  id: 'older-paged-message',
  conversationId: 'conversation-direct-1',
  senderId: 'user-1',
  content: 'Earlier Latin message',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-21T09:05:00',
);

const _heldMessage = DmMessage(
  id: 'held-message',
  conversationId: 'conversation-direct-1',
  senderId: 'consultant-1',
  content: '菜单等待自动完成',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-21T10:05:00',
);

const _customerMessage = CustomerServiceMessage(
  id: 'customer-message-1',
  senderId: 'CS_ADMIN',
  senderName: '客服人员',
  content: '客服中文消息',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-21T10:05:00',
);
