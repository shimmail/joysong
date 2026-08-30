import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

void main() {
  test('missing consultant order capability defaults to false', () {
    final context = ManagementContext.fromJson(oldServerContextJson);

    expect(context.canAccessConsultantOrderWorkbench, isFalse);
  });

  test('consultant order capability participates in hasAnyCapability', () {
    final context = ManagementContext.fromJson({
      ...oldServerContextJson,
      'activeRoles': ['CONSULTANT'],
      'canAccessConsultantOrderWorkbench': true,
    });

    expect(context.hasAnyCapability, isTrue);
  });
}

const oldServerContextJson = <String, Object?>{
  'userId': 'user-1',
  'platformRole': 'USER',
  'activeRoles': <String>[],
  'managedInstitutionIds': <String>[],
  'visibleInstitutionIds': <String>[],
};
