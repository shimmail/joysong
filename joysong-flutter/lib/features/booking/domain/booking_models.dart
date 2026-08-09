import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

@immutable
final class InstitutionProject {
  const InstitutionProject({
    required this.id,
    required this.projectId,
    required this.institutionId,
    required this.name,
    required this.institutionName,
    required this.description,
    required this.coverImage,
    required this.price,
    this.originalPrice,
  });

  final String id;
  final String projectId;
  final String institutionId;
  final String name;
  final String institutionName;
  final String description;
  final String coverImage;
  final Money price;
  final Money? originalPrice;

  factory InstitutionProject.fromJson(Object? json) {
    final root = jsonMap(json, '机构项目');
    final project = _optionalMap(root['project']);
    final institution = _optionalMap(root['institution']);
    final nested = _optionalMap(root['institutionProject']);
    final source = nested.isEmpty ? root : nested;
    return InstitutionProject(
      id: requiredString(source, 'id', '机构项目'),
      projectId: requiredString(source, 'projectId', '机构项目'),
      institutionId: requiredString(source, 'institutionId', '机构项目'),
      name: stringValue(
        source['name'] ?? root['projectName'] ?? project['name'],
        fallback: '预约项目',
      ),
      institutionName: stringValue(
        root['institutionName'] ?? institution['name'],
      ),
      description: stringValue(
        source['description'] ?? root['description'] ?? project['description'],
      ),
      coverImage: stringValue(
        source['coverImage'] ?? root['coverImage'] ?? project['coverImage'],
      ),
      price: Money.parse(source['price'] ?? root['price'], field: '项目价格'),
      originalPrice: _optionalMoney(
        source['originalPrice'] ?? root['originalPrice'],
      ),
    );
  }
}

@immutable
final class BookingDoctor {
  const BookingDoctor({
    required this.id,
    required this.name,
    required this.title,
    required this.avatar,
    required this.specialties,
    required this.isVerified,
  });

  final String id;
  final String name;
  final String title;
  final String avatar;
  final String specialties;
  final bool isVerified;

  factory BookingDoctor.fromJson(Object? json) {
    final map = jsonMap(json, '医生');
    return BookingDoctor(
      id: requiredString(map, 'id', '医生'),
      name: stringValue(map['name'], fallback: '医生'),
      title: stringValue(map['title']),
      avatar: stringValue(map['avatar']),
      specialties: stringValue(map['specialties']),
      isVerified: map['isVerified'] == true,
    );
  }
}

@immutable
final class BookingConsultant {
  const BookingConsultant({
    required this.id,
    required this.name,
  });

  final String id;
  final String name;

  factory BookingConsultant.fromJson(Object? json) {
    final map = jsonMap(json, '机构医美顾问');
    return BookingConsultant(
      id: requiredString(map, 'id', '机构医美顾问'),
      name: stringValue(map['name'], fallback: '医美顾问'),
    );
  }
}

@immutable
final class UserCoupon {
  const UserCoupon({
    required this.id,
    required this.couponId,
    required this.name,
    required this.type,
    required this.discountValue,
    required this.minimumAmount,
    required this.status,
    required this.expireAt,
  });

  final int id;
  final int couponId;
  final String name;
  final String type;
  final Money discountValue;
  final Money minimumAmount;
  final String status;
  final DateTime expireAt;

  bool get isAvailable => status == 'UNUSED';

  factory UserCoupon.fromJson(Object? json) {
    final map = jsonMap(json, '优惠券');
    final id = intValue(map['id'], fallback: -1);
    final couponId = intValue(map['couponId'], fallback: -1);
    if (id < 0 || couponId < 0) throw const FormatException('优惠券缺少有效 ID');
    return UserCoupon(
      id: id,
      couponId: couponId,
      name: stringValue(map['couponName'], fallback: '优惠券'),
      type: stringValue(map['couponType'], fallback: 'UNKNOWN'),
      discountValue: Money.parse(map['discountValue'], field: '优惠值'),
      minimumAmount: Money.fromJsonOrZero(map['minAmount'], field: '使用门槛'),
      status: stringValue(map['status'], fallback: 'UNKNOWN').toUpperCase(),
      expireAt: requiredLocalDateTime(map['expireAt'], '优惠券有效期'),
    );
  }
}

@immutable
final class DiscountQuote {
  const DiscountQuote({
    required this.couponId,
    required this.originalPrice,
    required this.discountAmount,
  });

  final int couponId;
  final Money originalPrice;
  final Money discountAmount;

  factory DiscountQuote.fromJson(Object? json) {
    final map = jsonMap(json, '优惠计算');
    return DiscountQuote(
      couponId: intValue(map['couponId']),
      originalPrice: Money.parse(map['originalPrice'], field: '原价'),
      discountAmount: Money.parse(map['discountAmount'], field: '优惠金额'),
    );
  }
}

@immutable
final class CreateOrderCommand {
  const CreateOrderCommand({
    required this.projectId,
    required this.institutionProjectId,
    required this.consultantId,
    required this.doctorId,
    required this.appointmentTime,
    this.quantity = 1,
    this.remark = '',
    this.userCouponId,
  });

  final String projectId;
  final String institutionProjectId;
  final String consultantId;
  final String doctorId;
  final int quantity;
  final String remark;
  final int? userCouponId;
  final DateTime appointmentTime;

  Map<String, Object?> toJson() => {
        'projectId': projectId,
        'institutionProjectId': institutionProjectId,
        'consultantId': consultantId,
        'doctorId': doctorId,
        'quantity': quantity,
        'remark': remark,
        'userCouponId': userCouponId,
        'appointmentTime': _localIso8601(appointmentTime),
      };
}

String _localIso8601(DateTime value) {
  final local = value.isUtc ? value.toLocal() : value;
  String two(int number) => number.toString().padLeft(2, '0');
  return '${local.year.toString().padLeft(4, '0')}-'
      '${two(local.month)}-${two(local.day)}T'
      '${two(local.hour)}:${two(local.minute)}:${two(local.second)}';
}

Map<String, dynamic> _optionalMap(Object? value) {
  if (value is! Map) return const {};
  try {
    return value.cast<String, dynamic>();
  } on TypeError {
    return const {};
  }
}

Money? _optionalMoney(Object? value) {
  if (value == null || value.toString().trim().isEmpty) return null;
  return Money.parse(value);
}
