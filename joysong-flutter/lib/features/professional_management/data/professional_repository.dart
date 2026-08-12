import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/professional_management/domain/professional_models.dart';

final class ProfessionalRepository {
  ProfessionalRepository(this._client);
  final ApiClient _client;
  Future<List<DoctorArticle>> listArticles() async => await _client.get<List<DoctorArticle>>('/management/doctor-articles', decodeData: (v) => (v as List).map(DoctorArticle.fromJson).toList()) ?? const [];
  Future<DoctorArticle> saveArticle(DoctorArticleDraft draft, {String? id}) async {
    final decode = (Object? v) => DoctorArticle.fromJson(v);
    final value = id == null ? await _client.post<DoctorArticle>('/management/doctor-articles', body: draft.toJson(), decodeData: decode) : await _client.put<DoctorArticle>('/management/doctor-articles/$id', body: draft.toJson(), decodeData: decode);
    if (value == null) throw const FormatException('文章响应为空');
    return value;
  }
  Future<void> deleteArticle(String id) => _client.delete<void>('/management/doctor-articles/$id', decodeData: (_) {}).then((_) {});
  Future<List<DoctorOrder>> listOrders() async => await _client.get<List<DoctorOrder>>('/management/orders', decodeData: (v) => (v as List).map(DoctorOrder.fromJson).toList()) ?? const [];
  Future<DoctorOrder> getOrder(String id) => _required(_client.get<DoctorOrder>('/management/orders/$id', decodeData: DoctorOrder.fromJson));
  Future<DoctorOrder> actOnOrder(String id, String code, {required bool completion}) => _required(_client.post<DoctorOrder>('/management/orders/$id/${completion ? 'request-completion' : 'verify'}', body: {'verificationCode': code}, decodeData: DoctorOrder.fromJson));
  Future<DoctorOrder> _required(Future<DoctorOrder?> value) async => await value ?? (throw const FormatException('订单响应为空'));
}
