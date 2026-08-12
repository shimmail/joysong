import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/professional_management/domain/professional_models.dart';

void main() {
  test('doctor order consumes action flags without exposing verification code', () {
    final order = DoctorOrder.fromJson({'id':'o1','orderNo':'N1','projectName':'P','institutionName':'I','status':'CONSULTATION_PAID','amount':'99.00','createdAt':'2026-08-12T10:00:00','canVerify':true,'canRequestCompletion':false,'verifyCode':'123456'});
    expect(order.canVerify, isTrue);
    expect(order.toString(), isNot(contains('123456')));
  });

  test('article draft emits the compact management contract', () {
    final json = DoctorArticleDraft(title:' T ',summary:' S ',coverImage:' C ',publishDate:DateTime(2026,8,12),content:' B ').toJson();
    expect(json, {'title':'T','summary':'S','coverImage':'C','publishDate':'2026-08-12','content':'B'});
  });
}
