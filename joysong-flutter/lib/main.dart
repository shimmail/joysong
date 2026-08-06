import 'dart:ui';

import 'package:flutter/widgets.dart';
import 'package:joysong_flutter/app/app.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/observability/app_error_reporter.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  const reporter = SafeDebugErrorReporter();
  FlutterError.onError = (details) {
    reporter.record(
      details.exception,
      details.stack ?? StackTrace.current,
      context: details.context?.toDescription() ?? 'Flutter framework',
    );
    FlutterError.presentError(details);
  };
  PlatformDispatcher.instance.onError = (error, stackTrace) {
    reporter.record(error, stackTrace, context: 'Uncaught asynchronous error');
    return true;
  };
  final environment = AppEnvironment.fromBuildDefines();
  runApp(JoysongApp(environment: environment));
}
