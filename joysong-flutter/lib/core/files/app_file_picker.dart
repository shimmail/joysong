import 'dart:io';
import 'dart:math';

import 'package:flutter_image_compress/flutter_image_compress.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

typedef PublicImagePickDelegate = Future<XFile?> Function({
  required double? maxWidth,
  required double? maxHeight,
  required int? imageQuality,
  required bool requestFullMetadata,
});

typedef PublicImageCompressDelegate = Future<XFile?> Function({
  required String sourcePath,
  required String targetPath,
  required int minWidth,
  required int minHeight,
  required int quality,
  required CompressFormat format,
  required bool keepExif,
});

typedef TemporaryDirectoryProvider = Directory Function();

final class AppPickedFile {
  AppPickedFile({
    required Uint8List bytes,
    required this.fileName,
    required this.mimeType,
  })  : _bytes = bytes,
        uploadId = '',
        localPath = null,
        byteLength = bytes.length;

  const AppPickedFile.publicImage({
    required this.uploadId,
    required this.localPath,
    required this.byteLength,
    required this.fileName,
    required this.mimeType,
  })  : assert(localPath != null && localPath != ''),
        _bytes = null;

  final Uint8List? _bytes;
  final String uploadId;
  final String? localPath;
  final int byteLength;
  final String fileName;
  final String mimeType;

  /// Synchronous bytes are intentionally available only for private files.
  Uint8List get bytes {
    final value = _bytes;
    if (value == null) {
      throw StateError('公共图片使用文件路径传输，请调用 readBytes() 读取预览');
    }
    return value;
  }

  Future<Uint8List> readBytes() async {
    final value = _bytes;
    if (value != null) return value;
    return File(localPath!).readAsBytes();
  }

  Future<void> deleteLocalFile() async {
    final path = localPath;
    if (path != null) await _deleteIfExists(File(path));
  }
}

final class AppFilePicker {
  const AppFilePicker({
    PublicImagePickDelegate? publicImagePicker,
    PublicImageCompressDelegate? publicImageCompressor,
    TemporaryDirectoryProvider? temporaryDirectoryProvider,
  })  : _publicImagePicker = publicImagePicker,
        _publicImageCompressor = publicImageCompressor,
        _temporaryDirectoryProvider = temporaryDirectoryProvider;

  static const maxPublicImageBytes = 10 * 1024 * 1024;
  static const maxPublicImageEdge = 2048;
  static const publicJpegQuality = 85;
  static const _channel = MethodChannel('com.joysong.app/file_picker');

  final PublicImagePickDelegate? _publicImagePicker;
  final PublicImageCompressDelegate? _publicImageCompressor;
  final TemporaryDirectoryProvider? _temporaryDirectoryProvider;

  Future<AppPickedFile?> pickImage() async {
    final picker = _publicImagePicker ?? _pickPublicImage;
    final selected = await picker(
      // Preserve the selected cache file here. Supplying resize options would
      // make image_picker decode/encode once before the compressor does it.
      maxWidth: null,
      maxHeight: null,
      imageQuality: null,
      requestFullMetadata: false,
    );
    if (selected == null) return null;
    return _preparePublicImage(
      sourcePath: selected.path,
    );
  }

  /// Turns an in-memory edit result (for example, an avatar crop) back into the
  /// same file-backed public upload format used by [pickImage].
  Future<AppPickedFile> preparePublicImageBytes(
    Uint8List bytes, {
    required String fileName,
  }) async {
    if (bytes.isEmpty) {
      throw const AppFileSelectionException('图片内容不能为空');
    }
    final directory = await _ensureTemporaryDirectory();
    final uploadId = _newUploadId();
    final extension = _safeSourceExtension(fileName);
    final source = File(
      '${directory.path}${Platform.pathSeparator}.$uploadId-source.$extension',
    );
    await source.writeAsBytes(bytes, flush: true);
    try {
      return await _preparePublicImage(
        sourcePath: source.path,
        uploadId: uploadId,
      );
    } finally {
      await _deleteIfExists(source);
    }
  }

  Future<AppPickedFile?> pickIdentityDocument() => _pickPrivateFile(
        allowedExtensions: const ['jpg', 'jpeg', 'png', 'webp', 'pdf'],
      );

  Future<AppPickedFile?> pickRefundEvidence() => _pickPrivateFile(
        allowedExtensions: const ['jpg', 'jpeg', 'png', 'webp', 'pdf'],
      );

  Future<AppPickedFile?> _pickPrivateFile({
    required List<String> allowedExtensions,
  }) async {
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'pickFile',
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

  Future<AppPickedFile> _preparePublicImage({
    required String sourcePath,
    String? uploadId,
  }) async {
    final source = File(sourcePath);
    if (!await source.exists()) {
      throw const AppFileSelectionException('无法读取所选图片');
    }
    final sourceFormat = await _detectImageFormat(source);
    if (sourceFormat == null) {
      throw const AppFileSelectionException('图片格式无效或不受支持');
    }

    final id = uploadId ?? _newUploadId();
    final preservePng = sourceFormat == _DetectedImageFormat.png;
    final outputExtension = preservePng ? 'png' : 'jpg';
    final outputMime = preservePng ? 'image/png' : 'image/jpeg';
    final directory = await _ensureTemporaryDirectory();
    final target = File(
      '${directory.path}${Platform.pathSeparator}$id.$outputExtension',
    );
    final compressor = _publicImageCompressor ?? _compressPublicImage;
    XFile? compressed;
    try {
      compressed = await compressor(
        sourcePath: source.path,
        targetPath: target.path,
        minWidth: maxPublicImageEdge,
        minHeight: maxPublicImageEdge,
        quality: preservePng ? 100 : publicJpegQuality,
        format: preservePng ? CompressFormat.png : CompressFormat.jpeg,
        keepExif: false,
      );
    } on Object catch (error) {
      await _deleteIfExists(target);
      throw AppFileSelectionException('图片压缩失败：$error');
    }
    if (compressed == null) {
      await _deleteIfExists(target);
      throw const AppFileSelectionException('图片压缩失败');
    }

    final output = File(compressed.path);
    final byteLength = await output.length();
    final actualFormat = await _detectImageFormat(output);
    if (byteLength <= 0 ||
        (preservePng && actualFormat != _DetectedImageFormat.png) ||
        (!preservePng && actualFormat != _DetectedImageFormat.jpeg)) {
      await _deleteIfExists(output);
      throw const AppFileSelectionException('压缩后的图片格式无效');
    }
    if (byteLength > maxPublicImageBytes) {
      await _deleteIfExists(output);
      throw const AppFileSelectionException('图片压缩后仍超过 10 MB，请选择更小的图片');
    }
    return AppPickedFile.publicImage(
      uploadId: id,
      localPath: output.path,
      byteLength: byteLength,
      fileName: '$id.$outputExtension',
      mimeType: outputMime,
    );
  }

  Future<Directory> _ensureTemporaryDirectory() async {
    final base = (_temporaryDirectoryProvider ?? _systemTemporaryDirectory)();
    final directory = Directory(
      '${base.path}${Platform.pathSeparator}joysong-public-upload',
    );
    await directory.create(recursive: true);
    return directory;
  }
}

Future<XFile?> _pickPublicImage({
  required double? maxWidth,
  required double? maxHeight,
  required int? imageQuality,
  required bool requestFullMetadata,
}) {
  return ImagePicker().pickImage(
    source: ImageSource.gallery,
    maxWidth: maxWidth,
    maxHeight: maxHeight,
    imageQuality: imageQuality,
    requestFullMetadata: requestFullMetadata,
  );
}

Future<XFile?> _compressPublicImage({
  required String sourcePath,
  required String targetPath,
  required int minWidth,
  required int minHeight,
  required int quality,
  required CompressFormat format,
  required bool keepExif,
}) {
  return FlutterImageCompress.compressAndGetFile(
    sourcePath,
    targetPath,
    minWidth: minWidth,
    minHeight: minHeight,
    quality: quality,
    format: format,
    keepExif: keepExif,
    autoCorrectionAngle: true,
  );
}

Directory _systemTemporaryDirectory() => Directory.systemTemp;

String mimeTypeForFileName(String fileName) {
  final extension = fileName.split('.').last.toLowerCase();
  return switch (extension) {
    'jpg' || 'jpeg' => 'image/jpeg',
    'png' => 'image/png',
    'webp' => 'image/webp',
    'heic' || 'heif' => 'image/heic',
    'pdf' => 'application/pdf',
    _ => 'application/octet-stream',
  };
}

enum _DetectedImageFormat { jpeg, png, webp, heic }

Future<_DetectedImageFormat?> _detectImageFormat(File file) async {
  final handle = await file.open();
  try {
    final bytes = await handle.read(12);
    if (bytes.length < 12) return null;
    if (bytes[0] == 0xff && bytes[1] == 0xd8 && bytes[2] == 0xff) {
      return _DetectedImageFormat.jpeg;
    }
    if (_matches(bytes, const [
      0x89,
      0x50,
      0x4e,
      0x47,
      0x0d,
      0x0a,
      0x1a,
      0x0a,
    ])) {
      return _DetectedImageFormat.png;
    }
    final riff = String.fromCharCodes(bytes.take(4));
    final webp = String.fromCharCodes(bytes.skip(8).take(4));
    if (riff == 'RIFF' && webp == 'WEBP') {
      return _DetectedImageFormat.webp;
    }
    final box = String.fromCharCodes(bytes.skip(4).take(4));
    final brand = String.fromCharCodes(bytes.skip(8).take(4)).toLowerCase();
    if (box == 'ftyp' &&
        const {
          'heic',
          'heix',
          'hevc',
          'hevx',
          'heim',
          'heis',
          'mif1',
          'msf1',
        }.contains(brand)) {
      return _DetectedImageFormat.heic;
    }
    return null;
  } finally {
    await handle.close();
  }
}

bool _matches(List<int> value, List<int> prefix) {
  if (value.length < prefix.length) return false;
  for (var index = 0; index < prefix.length; index += 1) {
    if (value[index] != prefix[index]) return false;
  }
  return true;
}

String _newUploadId() {
  final bytes = List<int>.generate(16, (_) => Random.secure().nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final value =
      bytes.map((item) => item.toRadixString(16).padLeft(2, '0')).join();
  return '${value.substring(0, 8)}-${value.substring(8, 12)}-'
      '${value.substring(12, 16)}-${value.substring(16, 20)}-'
      '${value.substring(20)}';
}

String _safeSourceExtension(String fileName) {
  final extension = fileName.split('.').last.toLowerCase();
  return const {'jpg', 'jpeg', 'png', 'webp', 'heic', 'heif'}
          .contains(extension)
      ? extension
      : 'img';
}

Future<void> _deleteIfExists(File file) async {
  try {
    if (await file.exists()) await file.delete();
  } on FileSystemException {
    // The OS can reclaim stale cache files if another reader still owns it.
  }
}

final class AppFileSelectionException implements Exception {
  const AppFileSelectionException(this.message);

  final String message;

  @override
  String toString() => message;
}
