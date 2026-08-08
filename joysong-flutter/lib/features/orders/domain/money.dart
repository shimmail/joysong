import 'package:flutter/foundation.dart';

/// Decimal value backed by an integer and a scale.
///
/// It deliberately avoids binary floating-point arithmetic. JSON numbers are
/// converted through their decimal representation as soon as they enter the
/// data layer; new request values are always emitted as decimal strings.
@immutable
final class Money implements Comparable<Money> {
  const Money._(this.unscaled, this.scale);

  static final zero = Money._(BigInt.zero, 0);

  final BigInt unscaled;
  final int scale;

  factory Money.parse(Object? value, {String field = '金额'}) {
    if (value == null) {
      throw FormatException('$field为空');
    }
    final source = value.toString().trim();
    final match = RegExp(r'^([+-]?)(\d+)(?:\.(\d+))?$').firstMatch(source);
    if (match == null) {
      throw FormatException('$field不是有效十进制数');
    }
    final negative = match.group(1) == '-';
    final integer = match.group(2)!;
    var fraction = match.group(3) ?? '';
    while (fraction.endsWith('0')) {
      fraction = fraction.substring(0, fraction.length - 1);
    }
    final digits = BigInt.parse('$integer$fraction');
    return Money._(negative ? -digits : digits, fraction.length);
  }

  factory Money.fromJsonOrZero(Object? value, {String field = '金额'}) =>
      value == null || value.toString().trim().isEmpty
          ? zero
          : Money.parse(value, field: field);

  Money times(int multiplier) {
    if (multiplier < 0) {
      throw ArgumentError.value(multiplier, 'multiplier', '不能为负数');
    }
    return Money._(unscaled * BigInt.from(multiplier), scale)._normalized();
  }

  Money subtract(Money other) {
    final targetScale = scale > other.scale ? scale : other.scale;
    final left = unscaled * _pow10(targetScale - scale);
    final right = other.unscaled * _pow10(targetScale - other.scale);
    return Money._(left - right, targetScale)._normalized();
  }

  Money clampToZero() => isNegative ? zero : this;

  bool get isNegative => unscaled.isNegative;

  bool get isZero => unscaled == BigInt.zero;

  String toDecimalString({int? minimumFractionDigits}) {
    final negative = unscaled.isNegative;
    var digits = unscaled.abs().toString();
    final effectiveScale = scale;
    if (effectiveScale > 0 && digits.length <= effectiveScale) {
      digits = digits.padLeft(effectiveScale + 1, '0');
    }
    var result = effectiveScale == 0
        ? digits
        : '${digits.substring(0, digits.length - effectiveScale)}.'
            '${digits.substring(digits.length - effectiveScale)}';
    final minimum = minimumFractionDigits ?? 0;
    final dot = result.indexOf('.');
    final currentFraction = dot < 0 ? 0 : result.length - dot - 1;
    if (currentFraction < minimum) {
      if (dot < 0) result = '$result.';
      result = result.padRight(result.length + minimum - currentFraction, '0');
    }
    return negative && unscaled != BigInt.zero ? '-$result' : result;
  }

  String get formatted => '\$${toDecimalString(minimumFractionDigits: 2)}';

  Money _normalized() {
    var value = unscaled;
    var resultScale = scale;
    while (resultScale > 0 && value.remainder(BigInt.from(10)) == BigInt.zero) {
      value ~/= BigInt.from(10);
      resultScale -= 1;
    }
    return Money._(value, resultScale);
  }

  @override
  int compareTo(Money other) {
    final targetScale = scale > other.scale ? scale : other.scale;
    return (unscaled * _pow10(targetScale - scale)).compareTo(
      other.unscaled * _pow10(targetScale - other.scale),
    );
  }

  @override
  bool operator ==(Object other) => other is Money && compareTo(other) == 0;

  @override
  int get hashCode => _normalized().toDecimalString().hashCode;

  @override
  String toString() => toDecimalString();
}

BigInt _pow10(int exponent) {
  var result = BigInt.one;
  for (var index = 0; index < exponent; index += 1) {
    result *= BigInt.from(10);
  }
  return result;
}
