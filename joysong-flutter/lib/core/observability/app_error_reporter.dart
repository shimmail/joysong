import 'package:flutter/foundation.dart';

abstract interface class AppErrorReporter {
  void record(Object error, StackTrace stackTrace, {String context = ''});
}

final class SafeDebugErrorReporter implements AppErrorReporter {
  const SafeDebugErrorReporter();

  @override
  void record(Object error, StackTrace stackTrace, {String context = ''}) {
    final safeError = redactSensitiveText(error.toString());
    final safeContext = redactSensitiveText(context);
    debugPrint(
      '[JoysongError]${safeContext.isEmpty ? '' : ' $safeContext'} '
      '$safeError\n$stackTrace',
    );
  }
}

String redactSensitiveText(String input) {
  var result = input;
  final patterns = <RegExp>[
    RegExp(r'Bearer\s+[A-Za-z0-9._~+/=-]+', caseSensitive: false),
    RegExp(
      r'''(accessToken|refreshToken|password|verificationCode|idNumber)\s*[:=]\s*["']?[^,"'\s}]+''',
      caseSensitive: false,
    ),
  ];
  for (final pattern in patterns) {
    result = result.replaceAllMapped(pattern, (match) {
      final text = match.group(0)!;
      final separator = text.indexOf(RegExp(r'[:=\s]'));
      return separator < 0
          ? '[REDACTED]'
          : '${text.substring(0, separator)}=[REDACTED]';
    });
  }
  return result;
}
