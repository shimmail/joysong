import 'package:flutter/foundation.dart';

@immutable
final class DoctorArticle {
  const DoctorArticle({required this.id, required this.title, required this.authorName, required this.summary, required this.coverImage, required this.publishDate, required this.content, required this.readCount});
  final String id, title, authorName, summary, coverImage, content;
  final DateTime publishDate;
  final int readCount;
  factory DoctorArticle.fromJson(Object? value) {
    final map = (value as Map).cast<String, dynamic>();
    return DoctorArticle(id: '${map['id'] ?? ''}', title: '${map['title'] ?? ''}', authorName: '${map['authorName'] ?? ''}', summary: '${map['summary'] ?? ''}', coverImage: '${map['coverImage'] ?? ''}', publishDate: DateTime.parse('${map['publishDate']}'), content: '${map['content'] ?? ''}', readCount: (map['readCount'] as num?)?.toInt() ?? 0);
  }
}

final class DoctorArticleDraft {
  const DoctorArticleDraft({required this.title, required this.summary, required this.coverImage, required this.publishDate, required this.content});
  final String title, summary, coverImage, content;
  final DateTime publishDate;
  Map<String, Object?> toJson() => {'title': title.trim(), 'summary': summary.trim(), 'coverImage': coverImage.trim(), 'publishDate': '${publishDate.year}-${publishDate.month.toString().padLeft(2, '0')}-${publishDate.day.toString().padLeft(2, '0')}', 'content': content.trim()};
}

@immutable
final class DoctorOrder {
  const DoctorOrder({
    required this.id,
    required this.orderNo,
    required this.userId,
    required this.projectName,
    required this.institutionName,
    required this.status,
    required this.amount,
    required this.currency,
    required this.quantity,
    required this.remark,
    required this.userPhone,
    required this.createdAt,
    required this.appointmentTime,
    required this.canVerify,
    required this.canRequestCompletion,
  });

  final String id;
  final String orderNo;
  final String userId;
  final String projectName;
  final String institutionName;
  final String status;
  final String amount;
  final String currency;
  final int quantity;
  final String remark;
  final String userPhone;
  final DateTime createdAt;
  final DateTime? appointmentTime;
  final bool canVerify;
  final bool canRequestCompletion;

  factory DoctorOrder.fromJson(Object? value) {
    final map = (value as Map).cast<String, dynamic>();
    final appointmentTime = '${map['appointmentTime'] ?? ''}'.trim();
    return DoctorOrder(
      id: '${map['id'] ?? ''}',
      orderNo: '${map['orderNo'] ?? ''}',
      userId: '${map['userId'] ?? ''}',
      projectName: '${map['projectName'] ?? ''}',
      institutionName: '${map['institutionName'] ?? ''}',
      status: '${map['status'] ?? ''}',
      amount: '${map['amount'] ?? ''}',
      currency: '${map['currency'] ?? 'USD'}'.toUpperCase(),
      quantity: (map['quantity'] as num?)?.toInt() ?? 1,
      remark: '${map['remark'] ?? ''}',
      userPhone: '${map['userPhone'] ?? ''}',
      createdAt: DateTime.parse('${map['createdAt']}'),
      appointmentTime:
          appointmentTime.isEmpty ? null : DateTime.tryParse(appointmentTime),
      canVerify: map['canVerify'] == true,
      canRequestCompletion: map['canRequestCompletion'] == true,
    );
  }
}
