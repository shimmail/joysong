import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';

void main() {
  test('maps allowed upload extensions to stable MIME types', () {
    expect(mimeTypeForFileName('photo.JPG'), 'image/jpeg');
    expect(mimeTypeForFileName('photo.png'), 'image/png');
    expect(mimeTypeForFileName('photo.webp'), 'image/webp');
    expect(mimeTypeForFileName('identity.pdf'), 'application/pdf');
    expect(mimeTypeForFileName('unknown.bin'), 'application/octet-stream');
  });
}
