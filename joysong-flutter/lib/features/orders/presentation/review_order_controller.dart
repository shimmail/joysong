import 'dart:collection';

import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';

class ReviewOrderController extends ChangeNotifier {
  ReviewOrderController({Review? initialReview}) {
    if (initialReview == null) return;
    _rating = initialReview.rating.clamp(1, 5);
    _content = initialReview.content;
    _tags = initialReview.tags.join(' ');
    _imageUrls.addAll(initialReview.images.take(ReviewDraft.maxImageCount));
  }

  int _rating = 5;
  String _content = '';
  String _tags = '';
  final List<String> _imageUrls = [];
  bool _uploading = false;
  bool _uploadFailed = false;
  bool _disposed = false;

  int get rating => _rating;
  String get content => _content;
  String get tagsText => _tags;
  UnmodifiableListView<String> get imageUrls =>
      UnmodifiableListView(_imageUrls);
  bool get uploading => _uploading;
  bool get uploadFailed => _uploadFailed;
  bool get canAddImage =>
      !_uploading && _imageUrls.length < ReviewDraft.maxImageCount;
  bool get canSubmit => !_uploading && _content.trim().isNotEmpty;

  void setRating(int value) {
    if (value == _rating || value < 1 || value > 5) return;
    _rating = value;
    _notify();
  }

  void setContent(String value) {
    if (value == _content) return;
    _content = value;
    _notify();
  }

  void setTags(String value) {
    _tags = value;
  }

  Future<void> pickAndUploadImage(
    Future<String?> Function() upload,
  ) async {
    if (!canAddImage) return;
    _uploading = true;
    _uploadFailed = false;
    _notify();
    try {
      final url = (await upload())?.trim();
      if (url != null && url.isNotEmpty && !_imageUrls.contains(url)) {
        final candidate = [..._imageUrls, url];
        if (candidate.join(',').length <= ReviewDraft.maxImagesEncodedLength) {
          _imageUrls.add(url);
        } else {
          _uploadFailed = true;
        }
      }
    } on Object {
      _uploadFailed = true;
    } finally {
      _uploading = false;
      _notify();
    }
  }

  void removeImage(String url) {
    if (_uploading || !_imageUrls.remove(url)) return;
    _uploadFailed = false;
    _notify();
  }

  ReviewDraft createDraft() {
    final draft = ReviewDraft(
      rating: _rating,
      content: _content.trim(),
      tags: _tags
          .trim()
          .split(RegExp(r'\s+'))
          .where((tag) => tag.isNotEmpty)
          .toList(growable: false),
      images: List.unmodifiable(_imageUrls),
    );
    draft.validate();
    return draft;
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
