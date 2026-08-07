import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_controller.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_strings.dart';

class PaymentPage extends StatefulWidget {
  const PaymentPage({required this.controller, super.key});

  final PaymentController controller;

  @override
  State<PaymentPage> createState() => _PaymentPageState();
}

class _PaymentPageState extends State<PaymentPage> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(widget.controller.load());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed &&
        const {
          PaymentFlowStage.processing,
          PaymentFlowStage.requiresAction,
        }.contains(widget.controller.stage)) {
      unawaited(widget.controller.resumeAfterExternalAction());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: widget.controller,
        builder: (context, _) {
          final strings = PaymentStrings(
            isEnglish: Localizations.localeOf(context).languageCode == 'en',
          );
          final controller = widget.controller;
          return Scaffold(
            appBar: AppBar(title: Text(strings.paymentTitle)),
            body: _PaymentBody(controller: controller, strings: strings),
            bottomNavigationBar: _PaymentBottomBar(
              controller: controller,
              strings: strings,
              onCompleted: () => Navigator.of(context).pop(true),
            ),
          );
        },
      );
}

class _PaymentBody extends StatelessWidget {
  const _PaymentBody({required this.controller, required this.strings});

  final PaymentController controller;
  final PaymentStrings strings;

  @override
  Widget build(BuildContext context) {
    final order = controller.order;
    final fallbackAmount = controller.paymentType == PaymentType.balance
        ? order.remainingAmount
        : order.consultationFee;
    final amount = _paymentAmount(controller.payment, fallbackAmount.formatted);
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
      children: [
        _OrderSummary(order: order),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              children: [
                Text(strings.amountDue),
                const SizedBox(height: 6),
                Text(
                  amount,
                  key: const Key('payment-amount'),
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                ),
                const SizedBox(height: 6),
                Text(
                  strings.paymentTypeLabel(controller.paymentType.wireValue),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 18),
        Text(
          strings.selectPaymentMethod,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        for (final provider in controller.providers)
          _ProviderTile(
            provider: provider,
            selected: provider == controller.selectedProvider,
            enabled: !controller.isBusy,
            label: strings.providerLabel(provider.wireValue),
            onTap: () => controller.selectProvider(provider),
          ),
        const SizedBox(height: 14),
        _PaymentStatusCard(controller: controller, strings: strings),
        const SizedBox(height: 12),
        ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          leading: Icon(
            Icons.verified_user_outlined,
            color: Theme.of(context).colorScheme.primary,
          ),
          title: Text(strings.doNotClose),
          subtitle: Text(strings.processingHint),
        ),
      ],
    );
  }
}

class _ProviderTile extends StatelessWidget {
  const _ProviderTile({
    required this.provider,
    required this.selected,
    required this.enabled,
    required this.label,
    required this.onTap,
  });

  final PaymentProvider provider;
  final bool selected;
  final bool enabled;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      color: selected ? colors.primaryContainer.withValues(alpha: 0.45) : null,
      child: ListTile(
        key: Key('payment-provider-${provider.wireValue.toLowerCase()}'),
        enabled: enabled,
        selected: selected,
        onTap: enabled ? onTap : null,
        leading: Icon(_providerIcon(provider)),
        title: Text(label),
        trailing: Icon(
          selected ? Icons.radio_button_checked : Icons.radio_button_unchecked,
          color: selected ? colors.primary : null,
        ),
      ),
    );
  }
}

class _PaymentStatusCard extends StatelessWidget {
  const _PaymentStatusCard({required this.controller, required this.strings});

  final PaymentController controller;
  final PaymentStrings strings;

  @override
  Widget build(BuildContext context) {
    final stage = controller.stage;
    if (stage == PaymentFlowStage.ready) return const SizedBox.shrink();
    final (icon, color, title, detail) = switch (stage) {
      PaymentFlowStage.loading => (
          Icons.sync,
          Theme.of(context).colorScheme.primary,
          strings.processing,
          strings.processingHint,
        ),
      PaymentFlowStage.creating => (
          Icons.lock_clock_outlined,
          Theme.of(context).colorScheme.primary,
          strings.openingPayment,
          strings.doNotClose,
        ),
      PaymentFlowStage.requiresAction => (
          Icons.open_in_new,
          Theme.of(context).colorScheme.primary,
          strings.confirming,
          strings.doNotClose,
        ),
      PaymentFlowStage.processing => (
          Icons.hourglass_top,
          Theme.of(context).colorScheme.primary,
          strings.processing,
          controller.errorCode == null && controller.errorMessage == null
              ? strings.processingHint
              : strings.errorMessage(
                  controller.errorCode ?? controller.errorMessage,
                ),
        ),
      PaymentFlowStage.succeeded => (
          Icons.check_circle,
          Colors.green,
          strings.paymentSuccessful,
          strings.paymentSuccessHint,
        ),
      PaymentFlowStage.failed => (
          Icons.error_outline,
          Theme.of(context).colorScheme.error,
          strings.paymentFailed,
          strings.errorMessage(
            controller.errorCode ?? controller.errorMessage,
          ),
        ),
      PaymentFlowStage.cancelled => (
          Icons.cancel_outlined,
          Theme.of(context).colorScheme.error,
          strings.paymentCancelled,
          strings.paymentCancelledHint,
        ),
      PaymentFlowStage.expired => (
          Icons.timer_off_outlined,
          Theme.of(context).colorScheme.error,
          strings.paymentExpired,
          strings.paymentExpiredHint,
        ),
      PaymentFlowStage.partiallyRefunded => (
          Icons.currency_exchange,
          Theme.of(context).colorScheme.primary,
          strings.partiallyRefunded,
          strings.backToOrder,
        ),
      PaymentFlowStage.refunded => (
          Icons.currency_exchange,
          Theme.of(context).colorScheme.primary,
          strings.refunded,
          strings.backToOrder,
        ),
      PaymentFlowStage.ready => throw StateError('ready is handled above'),
    };
    return Card(
      key: const Key('payment-status-card'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: color, size: 30),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 4),
                  Text(detail),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PaymentBottomBar extends StatelessWidget {
  const _PaymentBottomBar({
    required this.controller,
    required this.strings,
    required this.onCompleted,
  });

  final PaymentController controller;
  final PaymentStrings strings;
  final VoidCallback onCompleted;

  @override
  Widget build(BuildContext context) {
    final stage = controller.stage;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
        child: switch (stage) {
          PaymentFlowStage.succeeded => FilledButton.icon(
              key: const Key('payment-back-to-order'),
              onPressed: onCompleted,
              icon: const Icon(Icons.arrow_back),
              label: Text(strings.backToOrder),
            ),
          PaymentFlowStage.processing => OutlinedButton.icon(
              key: const Key('payment-refresh'),
              onPressed: () => controller.refresh(),
              icon: const Icon(Icons.refresh),
              label: Text(strings.refreshStatus),
            ),
          PaymentFlowStage.failed ||
          PaymentFlowStage.cancelled ||
          PaymentFlowStage.expired =>
            FilledButton.icon(
              key: const Key('payment-retry'),
              onPressed: () {
                controller.retryWithNewAttempt();
                unawaited(controller.submit());
              },
              icon: const Icon(Icons.refresh),
              label: Text(strings.retry),
            ),
          PaymentFlowStage.partiallyRefunded ||
          PaymentFlowStage.refunded =>
            OutlinedButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(strings.backToOrder),
            ),
          PaymentFlowStage.requiresAction => FilledButton.icon(
              key: const Key('payment-continue'),
              onPressed: () => controller.continueCurrent(),
              icon: const Icon(Icons.open_in_new),
              label: Text(strings.continuePayment),
            ),
          PaymentFlowStage.loading || PaymentFlowStage.creating => FilledButton(
              onPressed: null,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const SizedBox.square(
                    dimension: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  const SizedBox(width: 10),
                  Text(strings.processing),
                ],
              ),
            ),
          PaymentFlowStage.ready => FilledButton(
              key: const Key('payment-submit'),
              onPressed: () => controller.submit(),
              child: Text(strings.payNow),
            ),
        },
      ),
    );
  }
}

class _OrderSummary extends StatelessWidget {
  const _OrderSummary({required this.order});

  final Order order;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              CircleAvatar(
                radius: 28,
                backgroundImage: order.coverImage.isEmpty
                    ? null
                    : NetworkImage(order.coverImage),
                child: order.coverImage.isEmpty
                    ? const Icon(Icons.spa_outlined)
                    : null,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      order.projectName,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    if (order.institutionName.isNotEmpty)
                      Text(
                        order.institutionName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    if (order.orderNo.isNotEmpty)
                      Text(
                        order.orderNo,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
}

IconData _providerIcon(PaymentProvider provider) => switch (provider) {
      PaymentProvider.stripe => Icons.credit_card,
      PaymentProvider.paypal => Icons.account_balance_wallet_outlined,
      PaymentProvider.wechatPay => Icons.chat_bubble_outline,
      PaymentProvider.alipay => Icons.qr_code_2,
      PaymentProvider.unknown => Icons.payment,
    };

String _paymentAmount(PaymentAttempt? payment, String fallback) {
  final minor = payment?.amountMinor;
  final currency = payment?.currency.trim().toUpperCase();
  if (minor == null || currency == null || currency.length != 3) {
    return fallback;
  }
  final fractionDigits = const {
        'BHD': 3,
        'JOD': 3,
        'KWD': 3,
        'OMR': 3,
        'TND': 3,
        'JPY': 0,
        'KRW': 0,
      }[currency] ??
      2;
  final negative = minor < 0;
  final digits = minor.abs().toString().padLeft(fractionDigits + 1, '0');
  final number = fractionDigits == 0
      ? digits
      : '${digits.substring(0, digits.length - fractionDigits)}.'
          '${digits.substring(digits.length - fractionDigits)}';
  final symbol = const {
        'CNY': '¥',
        'USD': r'$',
        'EUR': '€',
        'GBP': '£',
        'JPY': '¥',
        'HKD': r'HK$',
        'SGD': r'S$',
        'AUD': r'A$',
        'CAD': r'C$',
      }[currency] ??
      '$currency ';
  return '${negative ? '-' : ''}$symbol$number';
}

List<PaymentProvider> enabledPaymentProviders() {
  const configured = String.fromEnvironment('PAYMENT_PROVIDERS');
  if (configured.trim().isEmpty) return const [];
  final values = <PaymentProvider>[];
  for (final item in configured.split(',')) {
    final provider = PaymentProvider.fromWire(item);
    if (provider != PaymentProvider.unknown &&
        !values.contains(provider)) {
      values.add(provider);
    }
  }
  return values;
}
