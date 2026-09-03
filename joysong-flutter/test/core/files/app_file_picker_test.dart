import 'dart:io';

import 'package:flutter_image_compress/flutter_image_compress.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';

void main() {
  late Directory temporaryDirectory;

  setUp(() async {
    temporaryDirectory =
        await Directory.systemTemp.createTemp('joysong-picker-test-');
  });

  tearDown(() async {
    if (await temporaryDirectory.exists()) {
      await temporaryDirectory.delete(recursive: true);
    }
  });

  test('JPEG selection is resized and compressed to a file-backed draft',
      () async {
    final source = File('${temporaryDirectory.path}/source.jpg');
    await source.writeAsBytes(_jpegBytes);
    final picker = AppFilePicker(
      temporaryDirectoryProvider: () => temporaryDirectory,
      publicImagePicker: ({
        required maxWidth,
        required maxHeight,
        required imageQuality,
        required requestFullMetadata,
      }) async {
        expect(maxWidth, isNull);
        expect(maxHeight, isNull);
        expect(imageQuality, isNull);
        expect(requestFullMetadata, isFalse);
        return XFile(source.path);
      },
      publicImageCompressor: ({
        required sourcePath,
        required targetPath,
        required minWidth,
        required minHeight,
        required quality,
        required format,
        required keepExif,
      }) async {
        expect(sourcePath, source.path);
        expect(minWidth, 2048);
        expect(minHeight, 2048);
        expect(quality, 85);
        expect(format, CompressFormat.jpeg);
        expect(keepExif, isFalse);
        await File(targetPath).writeAsBytes(_jpegBytes);
        return XFile(targetPath);
      },
    );

    final selected = await picker.pickImage();

    expect(selected, isNotNull);
    expect(selected!.uploadId, matches(_uuidV4));
    expect(selected.localPath, isNotNull);
    expect(selected.byteLength, _jpegBytes.length);
    expect(selected.mimeType, 'image/jpeg');
    expect(selected.fileName, '${selected.uploadId}.jpg');
    expect(await File(selected.localPath!).readAsBytes(), _jpegBytes);
    await selected.deleteLocalFile();
    expect(await File(selected.localPath!).exists(), isFalse);
  });

  test('transparent PNG stays PNG and does not preserve metadata', () async {
    final source = File('${temporaryDirectory.path}/source.png');
    await source.writeAsBytes(_pngBytes);
    final picker = AppFilePicker(
      temporaryDirectoryProvider: () => temporaryDirectory,
      publicImagePicker: ({
        required maxWidth,
        required maxHeight,
        required imageQuality,
        required requestFullMetadata,
      }) async =>
          XFile(source.path),
      publicImageCompressor: ({
        required sourcePath,
        required targetPath,
        required minWidth,
        required minHeight,
        required quality,
        required format,
        required keepExif,
      }) async {
        expect(quality, 100);
        expect(format, CompressFormat.png);
        expect(keepExif, isFalse);
        await File(targetPath).writeAsBytes(_pngBytes);
        return XFile(targetPath);
      },
    );

    final selected = await picker.pickImage();

    expect(selected!.mimeType, 'image/png');
    expect(selected.fileName, '${selected.uploadId}.png');
    await selected.deleteLocalFile();
  });

  test('HEIC and WebP sources are normalized to JPEG', () async {
    for (final sourceBytes in [_heicBytes, _webpBytes]) {
      final source = File(
        '${temporaryDirectory.path}/source-${sourceBytes[0]}.image',
      );
      await source.writeAsBytes(sourceBytes);
      final picker = AppFilePicker(
        temporaryDirectoryProvider: () => temporaryDirectory,
        publicImagePicker: ({
          required maxWidth,
          required maxHeight,
          required imageQuality,
          required requestFullMetadata,
        }) async =>
            XFile(source.path),
        publicImageCompressor: ({
          required sourcePath,
          required targetPath,
          required minWidth,
          required minHeight,
          required quality,
          required format,
          required keepExif,
        }) async {
          expect(format, CompressFormat.jpeg);
          expect(quality, 85);
          await File(targetPath).writeAsBytes(_jpegBytes);
          return XFile(targetPath);
        },
      );

      final selected = await picker.pickImage();

      expect(selected!.mimeType, 'image/jpeg');
      await selected.deleteLocalFile();
    }
  });

  test('corrupt input is rejected before compression', () async {
    final source = File('${temporaryDirectory.path}/corrupt.jpg');
    await source.writeAsBytes(List<int>.filled(32, 7));
    var compressed = false;
    final picker = AppFilePicker(
      temporaryDirectoryProvider: () => temporaryDirectory,
      publicImagePicker: ({
        required maxWidth,
        required maxHeight,
        required imageQuality,
        required requestFullMetadata,
      }) async =>
          XFile(source.path),
      publicImageCompressor: ({
        required sourcePath,
        required targetPath,
        required minWidth,
        required minHeight,
        required quality,
        required format,
        required keepExif,
      }) async {
        compressed = true;
        return null;
      },
    );

    await expectLater(
        picker.pickImage(), throwsA(isA<AppFileSelectionException>()));
    expect(compressed, isFalse);
  });

  test('output larger than 10 MB is deleted and rejected', () async {
    final source = File('${temporaryDirectory.path}/source-large.jpg');
    await source.writeAsBytes(_jpegBytes);
    String? outputPath;
    final picker = AppFilePicker(
      temporaryDirectoryProvider: () => temporaryDirectory,
      publicImagePicker: ({
        required maxWidth,
        required maxHeight,
        required imageQuality,
        required requestFullMetadata,
      }) async =>
          XFile(source.path),
      publicImageCompressor: ({
        required sourcePath,
        required targetPath,
        required minWidth,
        required minHeight,
        required quality,
        required format,
        required keepExif,
      }) async {
        outputPath = targetPath;
        final output = await File(targetPath).open(mode: FileMode.write);
        await output.writeFrom(_jpegBytes);
        await output.setPosition(AppFilePicker.maxPublicImageBytes);
        await output.writeByte(0);
        await output.close();
        return XFile(targetPath);
      },
    );

    await expectLater(
      picker.pickImage(),
      throwsA(
        isA<AppFileSelectionException>().having(
          (error) => error.message,
          'message',
          contains('10 MB'),
        ),
      ),
    );
    expect(outputPath, isNotNull);
    expect(await File(outputPath!).exists(), isFalse);
  });
}

final _uuidV4 = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
);

const _jpegBytes = <int>[
  0xff,
  0xd8,
  0xff,
  0xe0,
  0x00,
  0x10,
  0x4a,
  0x46,
  0x49,
  0x46,
  0x00,
  0x01,
];
const _pngBytes = <int>[
  0x89,
  0x50,
  0x4e,
  0x47,
  0x0d,
  0x0a,
  0x1a,
  0x0a,
  0x00,
  0x00,
  0x00,
  0x0d,
];
const _webpBytes = <int>[
  0x52,
  0x49,
  0x46,
  0x46,
  0x04,
  0x00,
  0x00,
  0x00,
  0x57,
  0x45,
  0x42,
  0x50,
];
const _heicBytes = <int>[
  0x00,
  0x00,
  0x00,
  0x18,
  0x66,
  0x74,
  0x79,
  0x70,
  0x68,
  0x65,
  0x69,
  0x63,
];
