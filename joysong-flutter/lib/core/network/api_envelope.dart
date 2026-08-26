class ApiEnvelope<T> {
  const ApiEnvelope({
    required this.code,
    required this.message,
    required this.data,
    required this.hasData,
    this.errorCode,
  });

  final int code;
  final String message;
  final T? data;
  final bool hasData;
  final String? errorCode;

  factory ApiEnvelope.fromJson(
    Map<String, dynamic> json,
    T Function(Object? json) decodeData,
  ) {
    final rawCode = json['code'];
    if (rawCode is! num) {
      throw const FormatException('响应缺少有效的 code');
    }
    return ApiEnvelope<T>(
      code: rawCode.toInt(),
      message: json['message']?.toString() ?? '',
      data: json.containsKey('data') ? decodeData(json['data']) : null,
      hasData: json.containsKey('data'),
      errorCode:
          json['errorCode'] is String ? json['errorCode'] as String : null,
    );
  }
}
