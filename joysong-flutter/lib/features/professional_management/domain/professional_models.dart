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
  const DoctorOrder({required this.id, required this.orderNo, required this.projectName, required this.institutionName, required this.status, required this.amount, required this.createdAt, required this.canVerify, required this.canRequestCompletion});
  final String id, orderNo, projectName, institutionName, status, amount;
  final DateTime createdAt;
  final bool canVerify, canRequestCompletion;
  factory DoctorOrder.fromJson(Object? value) {
    final map = (value as Map).cast<String, dynamic>();
    return DoctorOrder(id: '${map['id'] ?? ''}', orderNo: '${map['orderNo'] ?? ''}', projectName: '${map['projectName'] ?? ''}', institutionName: '${map['institutionName'] ?? ''}', status: '${map['status'] ?? ''}', amount: '${map['amount'] ?? ''}', createdAt: DateTime.parse('${map['createdAt']}'), canVerify: map['canVerify'] == true, canRequestCompletion: map['canRequestCompletion'] == true);
  }
}
