class ApiException implements Exception {
  const ApiException({
    required this.message,
    this.httpStatus,
    this.businessCode,
    this.cause,
  });

  final String message;
  final int? httpStatus;
  final int? businessCode;
  final Object? cause;

  bool get isUnauthorized => httpStatus == 401 || businessCode == 401;

  bool get isForbidden => httpStatus == 403 || businessCode == 403;

  @override
  String toString() => 'ApiException($message)';
}
