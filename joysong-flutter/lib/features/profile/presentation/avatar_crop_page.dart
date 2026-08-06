import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

class AvatarCropPage extends StatefulWidget {
  const AvatarCropPage({required this.bytes, super.key});
  final Uint8List bytes;

  @override
  State<AvatarCropPage> createState() => _AvatarCropPageState();
}

class _AvatarCropPageState extends State<AvatarCropPage> {
  double _zoom = 1;
  double _x = 0;
  double _y = 0;
  bool _saving = false;

  bool get _english => Localizations.localeOf(context).languageCode == 'en';

  Future<void> _save() async {
    setState(() => _saving = true);
    final codec = await ui.instantiateImageCodec(widget.bytes);
    final frame = await codec.getNextFrame();
    final image = frame.image;
    final base = image.width < image.height ? image.width : image.height;
    final side = (base / _zoom).round().clamp(1, base);
    final maxX = image.width - side;
    final maxY = image.height - side;
    final left = ((maxX / 2) + _x * maxX / 2).round().clamp(0, maxX);
    final top = ((maxY / 2) + _y * maxY / 2).round().clamp(0, maxY);
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawImageRect(
      image,
      Rect.fromLTWH(
          left.toDouble(), top.toDouble(), side.toDouble(), side.toDouble()),
      const Rect.fromLTWH(0, 0, 512, 512),
      Paint()..filterQuality = FilterQuality.high,
    );
    final cropped = await recorder.endRecording().toImage(512, 512);
    final data = await cropped.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    cropped.dispose();
    if (!mounted) return;
    setState(() => _saving = false);
    Navigator.pop(context, data!.buffer.asUint8List());
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(_english ? 'Crop avatar' : '裁剪头像')),
        body: Column(children: [
          Expanded(
            child: Container(
              color: Colors.black,
              alignment: Alignment.center,
              child: ClipOval(
                child: SizedBox.square(
                  dimension: 300,
                  child: Transform.translate(
                    offset: Offset(-_x * 60, -_y * 60),
                    child: Transform.scale(
                      scale: _zoom,
                      child: Image.memory(widget.bytes, fit: BoxFit.cover),
                    ),
                  ),
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(20),
            child: Column(children: [
              Text(_english ? 'Zoom' : '缩放'),
              Slider(
                  value: _zoom,
                  min: 1,
                  max: 3,
                  onChanged: (v) => setState(() => _zoom = v)),
              Text(_english ? 'Horizontal position' : '水平位置'),
              Slider(
                  value: _x,
                  min: -1,
                  max: 1,
                  onChanged: (v) => setState(() => _x = v)),
              Text(_english ? 'Vertical position' : '垂直位置'),
              Slider(
                  value: _y,
                  min: -1,
                  max: 1,
                  onChanged: (v) => setState(() => _y = v)),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: _saving ? null : _save,
                  child: _saving
                      ? const SizedBox.square(
                          dimension: 20,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(_english ? 'Use avatar' : '使用头像'),
                ),
              ),
            ]),
          ),
        ]),
      );
}
