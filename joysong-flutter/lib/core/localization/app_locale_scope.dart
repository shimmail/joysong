import 'package:flutter/widgets.dart';
import 'package:joysong_flutter/core/localization/app_locale_controller.dart';

/// Makes the application locale controller available to descendant widgets.
final class AppLocaleScope extends InheritedNotifier<AppLocaleController> {
  const AppLocaleScope({
    required AppLocaleController controller,
    required super.child,
    super.key,
  }) : super(notifier: controller);

  static AppLocaleController? maybeOf(BuildContext context) {
    return context
        .dependOnInheritedWidgetOfExactType<AppLocaleScope>()
        ?.notifier;
  }
}
