import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_envelope.dart';

void main() {
  test('decodes a successful API envelope', () {
    final envelope = ApiEnvelope<String>.fromJson(
      {'code': 200, 'message': 'success', 'data': 'ready'},
      (json) => json! as String,
    );

    expect(envelope.code, 200);
    expect(envelope.message, 'success');
    expect(envelope.data, 'ready');
  });

  test('preserves the server error code and the absence of data', () {
    final envelope = ApiEnvelope<Object?>.fromJson(
      {
        'code': 409,
        'message': 'conflict',
        'errorCode': 'BASE_REVISION_CONFLICT'
      },
      (json) => json,
    );

    expect(envelope.errorCode, 'BASE_REVISION_CONFLICT');
    expect(envelope.hasData, isFalse);
  });

  test('rejects an envelope without a numeric code', () {
    expect(
      () => ApiEnvelope<Object?>.fromJson(
        {'message': 'invalid'},
        (json) => json,
      ),
      throwsFormatException,
    );
  });
}
