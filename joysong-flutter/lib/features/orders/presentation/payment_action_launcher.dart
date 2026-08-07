import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_controller.dart';
import 'package:url_launcher/url_launcher.dart';

/// Cross-platform action launcher shared by Android and iOS.
///
/// Redirect payments are supported now. Native SDK actions remain fail-closed
/// until the matching provider SDK and merchant configuration are installed.
final class MobilePaymentActionLauncher implements PaymentActionLauncher {
  const MobilePaymentActionLauncher();

  @override
  Future<PaymentActionResult> launch(PaymentAttempt payment) async {
    final action = payment.nextAction;
    if (action == null) {
      return const PaymentActionResult(
        PaymentActionOutcome.unavailable,
        errorCode: 'PAYMENT_NEXT_ACTION_MISSING',
      );
    }
    if (action.type == PaymentNextActionType.redirect) {
      final uri = Uri.tryParse(action.url ?? '');
      if (uri == null || !isAllowedPaymentRedirect(uri)) {
        return const PaymentActionResult(
          PaymentActionOutcome.unavailable,
          errorCode: 'PAYMENT_REDIRECT_INVALID',
        );
      }
      final launched = await launchUrl(
        uri,
        mode: LaunchMode.externalApplication,
      );
      return launched
          ? const PaymentActionResult(PaymentActionOutcome.launched)
          : const PaymentActionResult(
              PaymentActionOutcome.unavailable,
              errorCode: 'PAYMENT_REDIRECT_UNAVAILABLE',
            );
    }
    return const PaymentActionResult(
      PaymentActionOutcome.unavailable,
      errorCode: 'PAYMENT_SDK_NOT_CONFIGURED',
    );
  }
}

@visibleForTesting
bool isAllowedPaymentRedirect(Uri uri) {
  const configured = String.fromEnvironment('PAYMENT_REDIRECT_HOSTS');
  final scheme = uri.scheme.toLowerCase();
  if (uri.host.isEmpty || scheme != 'https') {
    return false;
  }
  final hosts = <String>{
    'checkout.stripe.com',
    ...configured
      .split(',')
      .map((value) => value.trim().toLowerCase())
      .where((value) => value.isNotEmpty),
  };
  final host = uri.host.toLowerCase();
  return hosts.any((allowed) => host == allowed || host.endsWith('.$allowed'));
}
