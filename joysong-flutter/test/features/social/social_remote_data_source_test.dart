import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/core/translation/content_translation.dart'
    as core_translation;
import 'package:joysong_flutter/features/social/data/social_remote_data_source.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';

void main() {
  late _FakeApiClient client;
  late ApiSocialRemoteDataSource dataSource;

  setUp(() {
    client = _FakeApiClient();
    dataSource = ApiSocialRemoteDataSource(client);
  });

  tearDown(() => client.close());

  test('publishes diary with comma fields only at the data boundary', () async {
    client.responseData = _diaryJson();

    await dataSource.publishDiary(
      const DiaryDraft(
        title: '恢复记录',
        content: '真实体验',
        images: ['https://img/a.jpg', 'https://img/b.jpg'],
        tags: ['恢复期', '护理'],
        status: DiaryStatus.draft,
      ),
    );

    expect(client.lastMethod, 'POST');
    expect(client.lastPath, 'diaries');
    expect(client.lastBody,
        containsPair('images', 'https://img/a.jpg,https://img/b.jpg'));
    expect(client.lastBody, containsPair('tags', '恢复期,护理'));
    expect(client.lastBody, containsPair('status', 'draft'));
  });

  test('uses uppercase favorite type required by current server', () async {
    client.responseData = {'favorited': true, 'count': 3};

    final status = await dataSource.getFavoriteStatus(
      FavoriteTargetType.diary,
      'diary/1',
    );

    expect(status.active, isTrue);
    expect(client.lastPath, 'favorites/DIARY/diary%2F1');
  });

  test('submits a new review through the order endpoint', () async {
    client.responseData = _reviewJson();

    await dataSource.submitOrderReview(
      'order-1',
      const ReviewDraft(
        rating: 5,
        content: '服务很好',
        tags: ['耐心'],
        images: ['https://img/review.jpg'],
      ),
    );

    expect(client.lastMethod, 'POST');
    expect(client.lastPath, 'orders/order-1/review');
    expect(client.lastBody, {
      'rating': 5,
      'content': '服务很好',
      'tags': '耐心',
      'images': 'https://img/review.jpg',
    });
  });

  test('maps order review 404 to no existing review', () async {
    client.error = const ApiException(
      message: '评价不存在',
      businessCode: 404,
      httpStatus: 200,
    );

    expect(await dataSource.getOrderReview('order-1'), isNull);
  });

  test('updates a review with the complete editable payload', () async {
    client.responseData = _reviewJson();

    await dataSource.updateReview(
      'review-1',
      const ReviewDraft(
        rating: 4,
        content: '修改后的评价',
        tags: ['专业', '耐心'],
        images: ['https://img/one.jpg', 'https://img/two.jpg'],
      ),
    );

    expect(client.lastMethod, 'PUT');
    expect(client.lastPath, 'reviews/review-1');
    expect(client.lastBody, {
      'rating': 4,
      'content': '修改后的评价',
      'tags': '专业,耐心',
      'images': 'https://img/one.jpg,https://img/two.jpg',
    });
  });

  test('returns the shared translation model through the social API', () async {
    client.responseData = {
      'translatedText': 'Recovery is progressing well',
      'detectedLanguage': 'zh',
      'targetLanguage': 'en-US',
      'provider': 'qwen',
      'cached': true,
    };

    final result = await dataSource.translateText(
      text: ' 恢复得很好 ',
      targetLanguage: ' en-US ',
      contentType: ' diary ',
    );

    expect(result, isA<core_translation.ContentTranslation>());
    expect(client.lastMethod, 'POST');
    expect(client.lastPath, 'translations');
    expect(client.lastBody, {
      'text': '恢复得很好',
      'targetLanguage': 'en-US',
      'contentType': 'diary',
    });
  });
}

final class _FakeApiClient extends ApiClient {
  _FakeApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  String? lastMethod;
  String? lastPath;
  Object? lastBody;
  Map<String, Object?>? lastQuery;
  Object? responseData;
  Object? error;

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'GET';
    lastPath = path;
    lastQuery = query;
    _throwIfNeeded();
    return decodeData(responseData);
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'POST';
    lastPath = path;
    lastBody = body;
    _throwIfNeeded();
    return decodeData(responseData);
  }

  @override
  Future<T?> put<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'PUT';
    lastPath = path;
    lastBody = body;
    _throwIfNeeded();
    return decodeData(responseData);
  }

  @override
  Future<T?> delete<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'DELETE';
    lastPath = path;
    lastBody = body;
    _throwIfNeeded();
    return responseData == null ? null : decodeData(responseData);
  }

  void _throwIfNeeded() {
    final failure = error;
    if (failure != null) {
      throw failure;
    }
  }
}

Map<String, Object?> _diaryJson() => {
      'id': 'diary-1',
      'title': '恢复记录',
      'userId': 'user-1',
      'authorName': '用户',
      'content': '真实体验',
      'images': 'https://img/a.jpg,https://img/b.jpg',
      'tags': '恢复期,护理',
      'likeCount': 0,
      'commentCount': 0,
      'favoriteCount': 0,
      'isLiked': false,
      'status': 'draft',
    };

Map<String, Object?> _reviewJson() => {
      'id': 'review-1',
      'orderId': 'order-1',
      'userId': 'user-1',
      'rating': 5,
      'content': '服务很好',
      'tags': '耐心',
      'images': 'https://img/review.jpg',
      'createdAt': '2026-08-06T10:00:00',
    };
