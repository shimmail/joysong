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

  test('maps every frozen project error code without reading server text', () {
    const cases = <(
      String,
      IdentityProjectErrorAction,
      String,
    )>[
      (
        'EDIT_BASE_STALE',
        IdentityProjectErrorAction.resubmit,
        'The edit baseline changed. Refresh and submit again.',
      ),
      (
        'APPROVAL_BASE_STALE',
        IdentityProjectErrorAction.offerForce,
        'The approval baseline changed. The latest review details were refreshed.',
      ),
      (
        'INHERITANCE_SOURCE_STALE',
        IdentityProjectErrorAction.resubmit,
        'The inherited platform project changed. Ask the doctor to submit again.',
      ),
      (
        'PRICING_POLICY_STALE',
        IdentityProjectErrorAction.resubmit,
        'The pricing policy changed. Ask the doctor to submit again.',
      ),
      (
        'FORCE_BASE_STALE',
        IdentityProjectErrorAction.refresh,
        'The force-approval baseline changed again. Refresh and review again.',
      ),
      (
        'REQUEST_ALREADY_PENDING',
        IdentityProjectErrorAction.refresh,
        'A request is already pending. The request list was refreshed.',
      ),
      (
        'REQUEST_ALREADY_HANDLED',
        IdentityProjectErrorAction.refresh,
        'This request was already handled. The request list was refreshed.',
      ),
      (
        'CLIENT_UPGRADE_REQUIRED',
        IdentityProjectErrorAction.upgrade,
        'Update the app before continuing.',
      ),
      (
        'FORCE_NOT_APPLICABLE',
        IdentityProjectErrorAction.refresh,
        'Force approval is no longer available. Refresh and review again.',
      ),
      (
        'PROJECT_PAYLOAD_INVALID',
        IdentityProjectErrorAction.invalid,
        'The project request data is invalid and cannot be reviewed.',
      ),
      (
        'REQUEST_SNAPSHOT_INVALID',
        IdentityProjectErrorAction.invalid,
        'The request snapshot is invalid and cannot be reviewed.',
      ),
      (
        'INSTITUTION_PROJECT_VERSION_STALE',
        IdentityProjectErrorAction.refresh,
        'The institution project changed. Refresh and try again.',
      ),
    ];

    for (final (code, action, message) in cases) {
      final error = ApiException(
        message: 'server text must not select the client branch',
        httpStatus: code == 'CLIENT_UPGRADE_REQUIRED' ? 426 : 409,
        errorCode: code,
      );
      expect(identityProjectErrorAction(error), action, reason: code);
      expect(
        identityErrorMessage(
          error,
          operation: IdentityErrorOperation.submit,
          resolve: english,
        ),
        message,
        reason: code,
      );
    }
  });

  test('maps permission revocation from status rather than response text', () {
    const error = ApiException(
      message: 'arbitrary forbidden response',
      httpStatus: 403,
    );

    expect(
      identityProjectErrorAction(error),
      IdentityProjectErrorAction.permissionExit,
    );
    expect(
      identityErrorMessage(
        error,
        operation: IdentityErrorOperation.review,
        resolve: english,
      ),
      'Review access changed. Refreshing permissions.',
    );
    expect(
      identityErrorMessage(
        error,
        operation: IdentityErrorOperation.submit,
        resolve: english,
      ),
      'Unable to submit the identity application. Please retry.',
    );
  });
}
