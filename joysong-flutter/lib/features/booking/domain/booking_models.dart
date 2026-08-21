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
final class TravelGroundServiceQuote {
  const TravelGroundServiceQuote({
    required this.currency,
    required this.medicalListPriceMinor,
    required this.platformServiceRateBps,
    required this.travelGroundServiceFeeMinor,
  });

  final String currency;
  final int medicalListPriceMinor;
  final int platformServiceRateBps;
  final int travelGroundServiceFeeMinor;

  String get medicalListPriceFormatted =>
      _formatUsdMinor(medicalListPriceMinor);

  String get travelGroundServiceFeeFormatted =>
      _formatUsdMinor(travelGroundServiceFeeMinor);

  factory TravelGroundServiceQuote.fromJson(Object? json) {
    final map = jsonMap(json, '旅游地接服务费报价');
    final currency = requiredString(map, 'currency', '旅游地接服务费报价');
    final medicalListPriceMinor = requiredInt(
      map,
      'medicalListPriceMinor',
      '旅游地接服务费报价',
    );
    final platformServiceRateBps = requiredInt(
      map,
      'platformServiceRateBps',
      '旅游地接服务费报价',
    );
    final travelGroundServiceFeeMinor = requiredInt(
      map,
      'travelGroundServiceFeeMinor',
      '旅游地接服务费报价',
    );
    if (currency != 'USD') {
      throw const FormatException('旅游地接服务费报价币种必须为 USD');
    }
    if (medicalListPriceMinor < 0 || travelGroundServiceFeeMinor < 0) {
      throw const FormatException('旅游地接服务费报价金额不能为负数');
    }
    return TravelGroundServiceQuote(
      currency: currency,
      medicalListPriceMinor: medicalListPriceMinor,
      platformServiceRateBps: platformServiceRateBps,
      travelGroundServiceFeeMinor: travelGroundServiceFeeMinor,
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
    this.remark = '',
  });

  final String projectId;
  final String institutionProjectId;
  final String consultantId;
  final String doctorId;
  final String remark;
  final DateTime appointmentTime;

  Map<String, Object?> toJson() => {
        'projectId': projectId,
        'institutionProjectId': institutionProjectId,
        'consultantId': consultantId,
        'doctorId': doctorId,
        'remark': remark,
        'appointmentTime': _localIso8601(appointmentTime),
      };
}

String _formatUsdMinor(int amountMinor) {
  final digits = amountMinor.toString().padLeft(3, '0');
  return '\$${digits.substring(0, digits.length - 2)}.'
      '${digits.substring(digits.length - 2)}';
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
