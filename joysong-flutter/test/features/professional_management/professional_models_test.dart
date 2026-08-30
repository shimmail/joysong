import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/professional_management/domain/professional_models.dart';

void main() {
  test('doctor order parses the customer and appointment details', () {
    final order = DoctorOrder.fromJson({
      'id': 'order-1',
      'orderNo': 'JS202608290001',
      'userId': 'user-1',
      'projectName': '面部护理',
      'institutionName': '娇颜颂医疗中心',
      'status': 'CONSULTATION_PAID',
      'amount': 1280.00,
      'currency': 'CNY',
      'quantity': 2,
      'remark': '皮肤敏感，请提前准备舒缓用品',
      'userPhone': '+8613900000001',
      'appointmentTime': '2026-09-01T14:30:00',
      'createdAt': '2026-08-29T10:00:00',
      'canVerify': true,
      'canRequestCompletion': false,
    });

    expect(order.userId, 'user-1');
    expect(order.userPhone, '+8613900000001');
    expect(order.remark, '皮肤敏感，请提前准备舒缓用品');
    expect(order.quantity, 2);
    expect(order.currency, 'CNY');
    expect(order.appointmentTime, DateTime(2026, 9, 1, 14, 30));
  });

  test('doctor order accepts an appointment that is not confirmed yet', () {
    final order = DoctorOrder.fromJson({
      'id': 'order-2',
      'orderNo': 'JS202608290002',
      'userId': 'user-2',
      'projectName': '术后复诊',
      'institutionName': '娇颜颂医疗中心',
      'status': 'PENDING_PAYMENT',
      'amount': 0,
      'currency': 'CNY',
      'quantity': 1,
      'remark': '',
      'userPhone': '',
      'appointmentTime': null,
      'createdAt': '2026-08-29T11:00:00',
      'canVerify': false,
      'canRequestCompletion': false,
    });

    expect(order.appointmentTime, isNull);
  });
}
