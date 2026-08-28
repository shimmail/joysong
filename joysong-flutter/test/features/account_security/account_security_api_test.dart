import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_api.dart';

void main() {
  test('uses current user profile and password contract', () async {
    final client = _FakeApiClient()
      ..responseData = {
        'id': 'user-1',
        'phone': '+8613800000000',
        'hasPassword': true,
      };
    final remote = ApiAccountSecurityRemoteDataSource(client);

    final profile = await remote.getProfile();
    expect(profile.id, 'user-1');
    expect(client.lastMethod, 'GET');
    expect(client.lastPath, 'user/profile');

    await remote.changePassword(
        oldPassword: 'old-secret', newPassword: 'new-secret');
    expect(client.lastMethod, 'PUT');
    expect(client.lastPath, 'user/password');
    expect(client.lastBody, {
      'oldPassword': 'old-secret',
      'newPassword': 'new-secret',
    });
  });

  test('uses supported reset and set-password paths', () async {
    final client = _FakeApiClient();
    final remote = ApiAccountSecurityRemoteDataSource(client);

    await remote.sendPasswordCode('+8613800000000');
    expect(client.lastMethod, 'POST');
    expect(client.lastPath, 'auth/send-code');

    await remote.resetPassword(
      phone: '+8613800000000',
      code: '123456',
      newPassword: 'new-secret',
    );
    expect(client.lastPath, 'auth/reset-password');

    await remote.setPassword(
      phone: '+8613800000000',
      code: '123456',
      newPassword: 'new-secret',
    );
    expect(client.lastMethod, 'PUT');
    expect(client.lastPath, 'user/password/set');

  });

  test('uses the four-stage deletion contract and protected headers',
      () async {
    final client = _FakeApiClient();
    final remote = ApiAccountSecurityRemoteDataSource(client);
    client.responseData = {
      'requestId': 'request-1',
      'eligible': true,
      'stepUpMethod': 'SMS',
      'maskedCredential': '+8613******00',
      'policyVersion': 'dev-v1',
      'blockers': <Object?>[],
    };

    final preflight = await remote.preflightAccountDeletion();
    expect(preflight.requestId, 'request-1');
    expect(client.lastMethod, 'POST');
    expect(client.lastPath, 'user/account-deletion/preflight');

    client.responseData = null;
    await remote.sendAccountDeletionSmsCode('request-1');
    expect(client.lastPath, 'user/account-deletion/send-sms-code');
    expect(client.lastBody, {'requestId': 'request-1'});

    client.responseData = {'deletionAuthorization': 'delete-auth-1'};
    await remote.stepUpAccountDeletionWithSms(
      requestId: 'request-1',
      code: '123456',
    );
    expect(client.lastPath, 'user/account-deletion/step-up');
    expect(client.lastBody, {'requestId': 'request-1', 'code': '123456'});

    client.responseData = {
      'requestId': 'request-1',
      'outcome': 'ERASED',
      'completedAt': '2026-08-28T10:00:00',
      'blockers': <Object?>[],
    };
    await remote.confirmAccountDeletion(
      const PendingAccountDeletion(
        requestId: 'request-1',
        idempotencyKey: 'delete-key-1',
        deletionAuthorization: 'delete-auth-1',
        policyVersion: 'dev-v1',
        userId: 'user-1',
      ),
    );
    expect(client.lastPath, 'user/account-deletion/confirm');
    expect(client.lastIdempotencyKey, 'delete-key-1');
    expect(client.lastHeaders, {
      'X-Account-Deletion-Authorization': 'delete-auth-1',
    });
    expect(client.lastBody, {
      'requestId': 'request-1',
      'policyVersion': 'dev-v1',
      'confirmation': 'DELETE',
    });
  });

  test('queries the same terminal confirmation without Bearer after erasure',
      () async {
    final client = _FakeApiClient()
      ..responseData = {
        'requestId': 'request-1',
        'outcome': 'ERASED',
        'completedAt': '2026-08-28T10:00:00',
        'blockers': <Object?>[],
      }
      ..rejectBearerConfirmation = true;
    final remote = ApiAccountSecurityRemoteDataSource(client);

    await remote.confirmAccountDeletion(
      const PendingAccountDeletion(
        requestId: 'request-1',
        idempotencyKey: 'delete-key-1',
        deletionAuthorization: 'delete-auth-1',
        policyVersion: 'dev-v1',
        userId: 'user-1',
      ),
    );

    expect(client.confirmationBearerModes, [true, false]);
    expect(client.lastIdempotencyKey, 'delete-key-1');
  });

  test('rejects ambiguous or mismatched terminal confirmation payloads',
      () async {
    final client = _FakeApiClient();
    final remote = ApiAccountSecurityRemoteDataSource(client);
    const pending = PendingAccountDeletion(
      requestId: 'request-1',
      idempotencyKey: 'delete-key-1',
      deletionAuthorization: 'delete-auth-1',
      policyVersion: 'dev-v1',
      userId: 'user-1',
    );
    final invalidPayloads = <Object?>[
      null,
      {
        'requestId': 'another-request',
        'outcome': 'ERASED',
        'completedAt': '2026-08-28T10:00:00',
        'blockers': <Object?>[],
      },
      {
        'requestId': 'request-1',
        'outcome': 'BLOCKED',
        'completedAt': '2026-08-28T10:00:00',
        'blockers': <Object?>[],
      },
      {
        'requestId': 'request-1',
        'completedAt': '2026-08-28T10:00:00',
        'blockers': <Object?>[],
      },
    ];

    for (final payload in invalidPayloads) {
      client.responseData = payload;
      await expectLater(
        remote.confirmAccountDeletion(pending),
        throwsA(isA<FormatException>()),
      );
    }
  });

  test('uses two-stage phone change and first-bind contracts', () async {
    final client = _FakeApiClient();
    final remote = ApiAccountSecurityRemoteDataSource(client);

    await remote.sendCurrentPhoneChangeCode();
    expect(client.lastPath, 'user/phone-change/send-current-code');

    await remote.verifyCurrentPhoneChangeCode('123456');
    expect(client.lastPath, 'user/phone-change/verify-current-code');
    expect(client.lastBody, {'code': '123456'});

    await remote.sendNewPhoneChangeCode('+85251234567');
    expect(client.lastPath, 'user/phone-change/send-new-code');
    expect(client.lastBody, {'phone': '+85251234567'});

    await remote.changePhone(phone: '+85251234567', code: '654321');
    expect(client.lastMethod, 'PUT');
    expect(client.lastPath, 'user/phone-change');
    expect(client.lastBody, {'phone': '+85251234567', 'code': '654321'});

    await remote.sendBindingPhoneCode('+8613900000000');
    expect(client.lastMethod, 'POST');
    expect(client.lastPath, 'auth/send-code');

    await remote.bindPhone(phone: '+8613900000000', code: '123456');
    expect(client.lastPath, 'user/bind-phone');
    expect(client.lastBody, {'phone': '+8613900000000', 'code': '123456'});
  });
}

final class _FakeApiClient extends ApiClient {
  _FakeApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  String? lastMethod;
  String? lastPath;
  Object? lastBody;
  Object? responseData;
  String? lastIdempotencyKey;
  Map<String, String>? lastHeaders;
  bool rejectBearerConfirmation = false;
  final confirmationBearerModes = <bool>[];

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'GET';
    lastPath = path;
    return decodeData(responseData);
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'POST';
    lastPath = path;
    lastBody = body;
    return responseData == null ? null : decodeData(responseData);
  }

  @override
  Future<T?> postIdempotentWithHeaders<T>(
    String path, {
    required String idempotencyKey,
    required Map<String, String> headers,
    Object? body,
    required T Function(Object? json) decodeData,
    bool includeAccessToken = true,
  }) async {
    lastMethod = 'POST';
    lastPath = path;
    lastBody = body;
    lastIdempotencyKey = idempotencyKey;
    lastHeaders = headers;
    confirmationBearerModes.add(includeAccessToken);
    if (rejectBearerConfirmation && includeAccessToken) {
      throw const ApiException(message: 'erased', httpStatus: 401);
    }
    return responseData == null ? null : decodeData(responseData);
  }

  @override
  Future<T?> put<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'PUT';
    lastPath = path;
    lastBody = body;
    return null;
  }

  @override
  Future<T?> delete<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'DELETE';
    lastPath = path;
    lastBody = body;
    return null;
  }
}
