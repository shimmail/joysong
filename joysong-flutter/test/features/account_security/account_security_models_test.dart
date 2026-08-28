import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';

void main() {
  test('maps account profile and masks bound phone', () {
    final profile = AccountSecurityProfile.fromJson({
      'id': 'user-1',
      'phone': '+8613800000000',
      'email': null,
      'hasPassword': true,
    });

    expect(profile.id, 'user-1');
    expect(profile.hasBoundPhone, isTrue);
    expect(profile.maskedPhone, '+8613******00');
    expect(profile.maskedPhone, isNot(contains('13800000000')));
  });

  test('requires protocol-critical hasPassword capability', () {
    expect(
      () => AccountSecurityProfile.fromJson({
        'id': 'user-1',
        'phone': '+8613800000000',
      }),
      throwsFormatException,
    );
  });

  test('validates server password, code, and E.164 constraints', () {
    expect(() => validateNewPassword('short'), throwsArgumentError);
    expect(() => validateVerificationCode('12345'), throwsArgumentError);
    expect(() => validateE164Phone('13800000000'), throwsArgumentError);
    expect(validateE164Phone('+86 138-0000-0000'), '+8613800000000');
  });

  test('parses deletion preflight without requiring sensitive identifiers',
      () {
    final preflight = AccountDeletionPreflight.fromJson({
      'requestId': 'request-1',
      'eligible': false,
      'stepUpMethod': 'GOOGLE',
      'maskedCredential': 'u***@example.com',
      'policyVersion': 'dev-v1',
      'blockers': [
        {'type': 'IDENTITY_APPLICATION', 'count': 1, 'action': '/identity'},
      ],
    });

    expect(preflight.stepUpMethod, AccountDeletionStepUpMethod.google);
    expect(preflight.blockers.single.type, 'IDENTITY_APPLICATION');
    expect(preflight.blockers.single.count, 1);
  });

  test('blocked preflight accepts a missing step-up method', () {
    final preflight = AccountDeletionPreflight.fromJson({
      'requestId': 'request-blocked',
      'eligible': false,
      'stepUpMethod': null,
      'maskedCredential': null,
      'policyVersion': 'dev-v1',
      'blockers': [
        {
          'type': 'NO_STEP_UP_CREDENTIAL',
          'count': 1,
          'action': 'CONTACT_SUPPORT',
        },
      ],
    });

    expect(preflight.stepUpMethod, AccountDeletionStepUpMethod.none);
    expect(preflight.maskedCredential, isEmpty);
  });

  test('only known Identity Center deletion actions are supported', () {
    expect(
      isSupportedAccountDeletionIdentityAction('VIEW_IDENTITY_APPLICATION'),
      isTrue,
    );
    expect(
      isSupportedAccountDeletionIdentityAction(
        'MANAGE_INSTITUTION_RELATIONSHIP',
      ),
      isTrue,
    );
    expect(
      isSupportedAccountDeletionIdentityAction(
        'RESOLVE_PENDING_RELATIONSHIP',
      ),
      isTrue,
    );
    expect(
      isSupportedAccountDeletionIdentityAction('CONTACT_SUPPORT'),
      isFalse,
    );
  });
}
