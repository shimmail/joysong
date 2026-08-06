import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
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

  test('uses supported reset, set-password and account deletion paths',
      () async {
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

    await remote.deleteAccount();
    expect(client.lastMethod, 'DELETE');
    expect(client.lastPath, 'user/account');
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
    return null;
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
