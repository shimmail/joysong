import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/messaging/data/messaging_remote_data_source.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';

void main() {
  test('creates service conversation by order id without target user id',
      () async {
    final client = _RecordingApiClient()..responseData = _orderConversationJson;
    final dataSource = ApiMessagingRemoteDataSource(client);

    final conversation =
        await dataSource.createOrderServiceConversation('order-1');

    expect(client.lastMethod, 'POST');
    expect(client.lastPath, 'orders/order-1/service-conversation');
    expect(client.lastBody, isNull);
    expect(conversation.conversationType, DmConversationType.orderService);
    expect(conversation.orderId, 'order-1');
  });

  test('legacy conversations default to DIRECT', () {
    final conversation = DmConversation.fromJson(
      Map<String, Object?>.from(_orderConversationJson)
        ..remove('conversationType')
        ..remove('orderId'),
    );

    expect(conversation.conversationType, DmConversationType.direct);
    expect(conversation.orderId, isNull);
  });

  test('ORDER_SERVICE without an order id fails closed', () {
    expect(
      () => DmConversation.fromJson(
        Map<String, Object?>.from(_orderConversationJson)..['orderId'] = ' ',
      ),
      throwsFormatException,
    );
  });

  test('order endpoint rejects a DIRECT conversation', () async {
    final client = _RecordingApiClient()
      ..responseData = (Map<String, Object?>.from(_orderConversationJson)
        ..['conversationType'] = 'DIRECT'
        ..remove('orderId'));
    final dataSource = ApiMessagingRemoteDataSource(client);

    await expectLater(
      dataSource.createOrderServiceConversation('order-1'),
      throwsFormatException,
    );
  });

  test('order endpoint rejects a conversation for another order', () async {
    final client = _RecordingApiClient()
      ..responseData = (Map<String, Object?>.from(_orderConversationJson)
        ..['orderId'] = 'order-2');
    final dataSource = ApiMessagingRemoteDataSource(client);

    await expectLater(
      dataSource.createOrderServiceConversation('order-1'),
      throwsFormatException,
    );
  });
}

final class _RecordingApiClient extends ApiClient {
  _RecordingApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  String? lastMethod;
  String? lastPath;
  Object? lastBody;
  Object? responseData;

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'POST';
    lastPath = path;
    lastBody = body;
    return decodeData(responseData);
  }
}

const _orderConversationJson = <String, Object?>{
  'id': 'conversation-order-1',
  'conversationType': 'ORDER_SERVICE',
  'orderId': 'order-1',
  'userAId': 'consultant-1',
  'userBId': 'user-1',
  'lastMessage': 'Welcome',
  'lastMessageAt': '2026-08-21T10:01:00',
  'userAUnread': 0,
  'userBUnread': 1,
  'createdAt': '2026-08-21T10:00:00',
  'updatedAt': '2026-08-21T10:01:00',
  'firstMessageLimitApplies': false,
  'waitingForReply': false,
};
