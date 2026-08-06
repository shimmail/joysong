import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/auth/data/auth_remote_data_source.dart';
import 'package:joysong_flutter/features/auth/data/auth_requests.dart';

void main() {
  late _FakeApiClient client;
  late ApiAuthRemoteDataSource dataSource;

  setUp(() {
    client = _FakeApiClient();
    dataSource = ApiAuthRemoteDataSource(client);
  });

  tearDown(() => client.close());

  test('posts password login request and decodes accessToken', () async {
    client.responseData = _sessionJson();

    final session = await dataSource.loginWithPassword(
      PasswordLoginRequest(
        phone: '+8613800000000',
        password: 'password8',
      ),
    );

    expect(client.lastMethod, 'POST');
    expect(client.lastPath, 'auth/login');
    expect(client.lastBody, {
      'phone': '+8613800000000',
      'password': 'password8',
    });
    expect(session.tokens.accessToken, 'access-new');
  });

  test('maps phone registration check query', () async {
    client.responseData = {'registered': true};

    final registered = await dataSource.checkPhoneRegistered(
      '+86 138-0000-0000',
    );

    expect(registered, isTrue);
    expect(client.lastMethod, 'GET');
    expect(client.lastPath, 'auth/check-phone-registered');
    expect(client.lastQuery, {'phone': '+8613800000000'});
  });
}

final class _FakeApiClient extends ApiClient {
  _FakeApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  String? lastMethod;
  String? lastPath;
  Object? lastBody;
  Map<String, Object?>? lastQuery;
  Object? responseData;

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'GET';
    lastPath = path;
    lastQuery = query;
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
    return decodeData(responseData);
  }
}

Map<String, Object?> _sessionJson() => {
      'token': 'legacy-token',
      'accessToken': 'access-new',
      'refreshToken': 'refresh-new',
      'tokenType': 'Bearer',
      'expiresIn': 86400,
      'user': {
        'id': 'user-1',
        'phone': '+8613800000000',
        'email': null,
        'nickname': '用户',
        'avatar': '',
        'gender': '',
        'city': '',
        'bio': '',
        'birthday': null,
        'role': 'USER',
        'hasPassword': true,
      },
    };
