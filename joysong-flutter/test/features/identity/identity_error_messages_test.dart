import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_error_messages.dart';

void main() {
  String english(String chinese, String english) => english;
  String chinese(String chinese, String english) => chinese;

  test('localizes doctor application errors', () {
    expect(
      identityErrorMessage(
        ArgumentError('请输入正确的真实姓名'),
        operation: IdentityErrorOperation.submit,
        resolve: english,
      ),
      'Enter a valid real name.',
    );
  });

  test('localizes legal representative application errors', () {
    expect(
      identityErrorMessage(
        const ApiException(
          message: '统一社会信用代码 不能为空',
          businessCode: 400,
        ),
        operation: IdentityErrorOperation.submit,
        resolve: english,
      ),
      'Unified social credit code is required.',
    );
  });

  test('localizes consultant application errors', () {
    expect(
      identityErrorMessage(
        const ApiException(message: '从业经历 内容过长', businessCode: 400),
        operation: IdentityErrorOperation.submit,
        resolve: english,
      ),
      'Professional experience is too long.',
    );
  });

  test('preserves known Chinese API errors', () {
    expect(
      identityErrorMessage(
        const ApiException(message: '该身份已有待审核申请，请勿重复提交'),
        operation: IdentityErrorOperation.submit,
        resolve: chinese,
      ),
      '该身份已有待审核申请，请勿重复提交',
    );
  });

  test('uses operation fallback without exposing unknown exception details', () {
    expect(
      identityErrorMessage(
        Exception('internal implementation detail'),
        operation: IdentityErrorOperation.upload,
        resolve: chinese,
      ),
      '认证材料上传失败，请重试',
    );
    expect(
      identityErrorMessage(
        const ApiException(message: 'unexpected server detail'),
        operation: IdentityErrorOperation.submit,
        resolve: english,
      ),
      'Unable to submit the identity application. Please retry.',
    );
  });
}
