import 'package:flutter/material.dart';

/// Displays a source-quality cover inside a fixed box; BoxFit controls cropping.
class OptimizedNetworkImage extends StatelessWidget {
  const OptimizedNetworkImage({
    required this.url,
    required this.width,
    required this.height,
    this.fit = BoxFit.cover,
    this.alignment = Alignment.center,
    this.errorBuilder,
    super.key,
  });

  final String url;
  final double width;
  final double height;
  final BoxFit fit;
  final Alignment alignment;
  final Widget Function(BuildContext, Object, StackTrace?)? errorBuilder;

  @override
  Widget build(BuildContext context) => Image.network(
        url,
        width: width,
        height: height,
        fit: fit,
        alignment: alignment,
        filterQuality: FilterQuality.medium,
        gaplessPlayback: true,
        errorBuilder: errorBuilder,
      );
}

ImageProvider<Object> optimizedNetworkImageProvider(
  BuildContext context,
  String url, {
  required double width,
  required double height,
}) =>
    ResizeImage.resizeIfNeeded(
      _physicalPixels(context, width),
      _physicalPixels(context, height),
      NetworkImage(url),
    );

// Cover images keep their source resolution. The fixed box only controls
// layout cropping through BoxFit and must not rewrite uploaded image bytes.
int _physicalPixels(BuildContext context, double logicalPixels) =>
    (logicalPixels * MediaQuery.devicePixelRatioOf(context)).ceil();
