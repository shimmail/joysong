import 'package:flutter/services.dart';

final class AppPickedFile {
  AppPickedFile({
    required Uint8List bytes,
    required this.fileName,
    required this.mimeType,
  }) : bytes = Uint8List.fromList(bytes);

  final Uint8List bytes;
  final String fileName;
  final String mimeType;
}

final class AppFilePicker {
  const AppFilePicker();

  static const _channel = MethodChannel('com.joysong.app/file_picker');

  Future<AppPickedFile?> pickImage() => _pick(
        method: 'pickImage',
        allowedExtensions: const ['jpg', 'jpeg', 'png', 'webp'],
      );

  Future<AppPickedFile?> pickIdentityDocument() => _pick(
        allowedExtensions: const ['jpg', 'jpeg', 'png', 'webp', 'pdf'],
      );

  Future<AppPickedFile?> pickRefundEvidence() => _pick(
        allowedExtensions: const ['jpg', 'jpeg', 'png', 'webp', 'pdf'],
      );

  Future<AppPickedFile?> _pick({
    String method = 'pickFile',
    required List<String> allowedExtensions,
  }) async {
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      method,
      {'extensions': allowedExtensions},
    );
    if (result == null) return null;
    final bytes = result['bytes'];
    if (bytes is! Uint8List || bytes.isEmpty) {
      throw const AppFileSelectionException('无法读取所选文件');
    }
    final fileName = result['fileName']?.toString() ?? '';
    return AppPickedFile(
      bytes: bytes,
      fileName: fileName,
      mimeType: result['mimeType']?.toString() ?? mimeTypeForFileName(fileName),
    );
  }
}

String mimeTypeForFileName(String fileName) {
  final extension = fileName.split('.').last.toLowerCase();
  return switch (extension) {
    'jpg' || 'jpeg' => 'image/jpeg',
    'png' => 'image/png',
    'webp' => 'image/webp',
    'pdf' => 'application/pdf',
    _ => 'application/octet-stream',
  };
}

final class AppFileSelectionException implements Exception {
  const AppFileSelectionException(this.message);

  final String message;

  @override
  String toString() => message;
}
