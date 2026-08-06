import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';

void main() {
  test('decodes the access token and complete user session', () {
    final session = AuthSession.fromJson(_sessionJson());

    expect(session.tokens.accessToken, 'access-new');
    expect(session.tokens.refreshToken, 'refresh-new');
    expect(session.tokens.expiresIn, 86400);
    expect(session.user.id, 'user-1');
    expect(session.user.phone, '+8613800000000');
    expect(session.user.birthday, DateTime(1990, 1, 2));
  });

  test('does not accept the legacy token when accessToken is absent', () {
    final json = _sessionJson()..remove('accessToken');

    expect(() => AuthSession.fromJson(json), throwsFormatException);
  });

  test('rejects a session without a refresh token', () {
    final json = _sessionJson()..['refreshToken'] = '';

    expect(() => AuthSession.fromJson(json), throwsFormatException);
  });
}

Map<String, Object?> _sessionJson() => {
      'token': 'legacy-access',
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
        'city': '上海',
        'bio': '',
        'birthday': '1990-01-02',
        'role': 'USER',
        'hasPassword': true,
      },
    };
