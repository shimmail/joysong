import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/identity/data/identity_repository_impl.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

void main() {
  const prohibitedKeys = {
    'doctorId',
    'doctorIds',
    'doctorBindings',
    'rating',
    'reviewCount',
    'platformRate',
    'doctorRate',
  };

  test('platform draft emits the exact 13-key USD creation snapshot', () {
    const draft = PlatformProjectRequestDraft(
      name: ' Hydrating Facial ',
      category: ' Skin ',
      description: ' Deep hydration ',
      referencePrice: 899.25,
      slogan: ' Glow naturally ',
      salesCount: 12,
      coverImage: ' cover.jpg ',
      images: [' first.jpg ', 'second.jpg'],
      detailContent: ' Multi-line detail ',
      tags: [' hydration ', 'gentle'],
      categoryTags: [' facial '],
      notes: ' Review this ',
    );

    expect(draft.toJson(), {
      'name': 'Hydrating Facial',
      'category': 'Skin',
      'description': 'Deep hydration',
      'referencePrice': 899.25,
      'currency': 'USD',
      'slogan': 'Glow naturally',
      'salesCount': 12,
      'coverImage': 'cover.jpg',
      'images': ['first.jpg', 'second.jpg'],
      'detailContent': 'Multi-line detail',
      'tags': ['hydration', 'gentle'],
      'categoryTags': ['facial'],
      'notes': 'Review this',
    });
    expect(draft.toJson(), hasLength(13));
    expect(draft.toJson().keys.toSet().intersection(prohibitedKeys), isEmpty);
  });

  test('platform detail content keeps its key and normalizes blank to null',
      () {
    const blank = PlatformProjectRequestDraft(
      name: 'Name',
      category: 'Skin',
      description: 'Description',
      detailContent: '   ',
    );
    const filled = PlatformProjectRequestDraft(
      name: 'Name',
      category: 'Skin',
      description: 'Description',
      detailContent: '  Plain detail  ',
    );

    expect(blank.toJson(), hasLength(13));
    expect(blank.toJson()['detailContent'], isNull);
    expect(filled.toJson()['detailContent'], 'Plain detail');
  },);

  test(
      'institution draft emits 18 body keys and keeps institution id path-only',
      () {
    const draft = InstitutionProjectRequestDraft(
      institutionId: ' institution-1 ',
      projectId: ' project-1 ',
      name: ' Clinic Facial ',
      category: ' Clinic Skin ',
      description: ' Clinic description ',
      tags: [' clinic ', 'signature'],
      slogan: ' Clinic glow ',
      detailContent: ' Clinic details ',
      price: 799.5,
      originalPrice: 999.99,
      currency: ' usd ',
      coverImage: ' clinic-cover.jpg ',
      images: [' clinic-1.jpg ', 'clinic-2.jpg'],
      salesCount: 5,
      isActive: false,
      consultationFee: 0,
      commissionRate: 0,
      institutionRate: 0,
      platformRate: 40,
      notes: ' Clinic note ',
    );

    expect(draft.toJson(), {
      'projectId': 'project-1',
      'name': 'Clinic Facial',
      'category': 'Clinic Skin',
      'description': 'Clinic description',
      'tags': ['clinic', 'signature'],
      'slogan': 'Clinic glow',
      'detailContent': 'Clinic details',
      'price': 799.5,
      'originalPrice': 999.99,
      'currency': 'USD',
      'coverImage': 'clinic-cover.jpg',
      'images': ['clinic-1.jpg', 'clinic-2.jpg'],
      'salesCount': 5,
      'isActive': false,
      'consultationFee': 0,
      'commissionRate': 0,
      'institutionRate': 0,
      'notes': 'Clinic note',
    });
    expect(draft.toJson(), hasLength(18));
    expect(draft.toJson(), isNot(contains('institutionId')));
    expect(draft.toJson().keys.toSet().intersection(prohibitedKeys), isEmpty);
    expect(draft.doctorRate, 60);
    },
  );

  test('institution project uses one payable USD doctor price', () {
    InstitutionProjectRequestDraft draft({
      required num price,
      String currency = 'USD',
    }) =>
        InstitutionProjectRequestDraft(
          institutionId: 'institution-1',
          projectId: 'project-1',
          price: price,
          currency: currency,
          platformRate: 40,
        );

    expect(draft(price: 0.01).toJson, throwsArgumentError);
    expect(draft(price: 1.001).toJson, throwsArgumentError);
    expect(draft(price: 0.02, currency: 'CNY').toJson, throwsArgumentError);

    final body = draft(price: 0.02).toJson();
    expect(body['price'], 0.02);
    expect(body['currency'], 'USD');
    expect(body['consultationFee'], 0);
    expect(body['commissionRate'], 0);
    expect(body['institutionRate'], 0);
  });

  test('institution draft keeps all nullable inheritance overrides explicit',
      () {
    const draft = InstitutionProjectRequestDraft(
      institutionId: ' institution-1 ',
      projectId: ' project-1 ',
      name: null,
      category: null,
      description: null,
      tags: null,
      slogan: null,
      detailContent: null,
      price: 0.02,
      originalPrice: null,
      currency: ' usd ',
      coverImage: null,
      images: null,
      salesCount: 0,
      isActive: true,
      consultationFee: 0,
      commissionRate: 0,
      institutionRate: 0,
      platformRate: 40,
      notes: '   ',
    );

    expect(draft.toJson(), {
      'projectId': 'project-1',
      'name': null,
      'category': null,
      'description': null,
      'tags': null,
      'slogan': null,
      'detailContent': null,
      'price': 0.02,
      'originalPrice': null,
      'currency': 'USD',
      'coverImage': null,
      'images': null,
      'salesCount': 0,
      'isActive': true,
      'consultationFee': 0,
      'commissionRate': 0,
      'institutionRate': 0,
      'notes': '',
    });
    expect(draft.toJson(), hasLength(18));
    expect(draft.toJson(), isNot(contains('institutionId')));
  },);

  test('draft validation mirrors backend amount count currency and rate limits',
      () {
    expect(
      () => const PlatformProjectRequestDraft(
        name: ' ',
        category: 'Skin',
        description: 'Description',
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => const PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        referencePrice: 0.001,
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => const PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        salesCount: -1,
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        tags: List.filled(21, 'tag'),
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => const PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        images: [' '],
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => const InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        price: 100000000,
          currency: 'USD',
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 0,
        platformRate: 40,
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => const InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        price: 0.02,
          currency: 'USD',
        consultationFee: 0,
        commissionRate: 45.001,
        institutionRate: 0,
        platformRate: 40,
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => const InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        price: 0.02,
          currency: 'USD',
        consultationFee: 0,
        commissionRate: 45.01,
        institutionRate: 15,
        platformRate: 40,
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        description: List.filled(5001, 'x').join(),
        price: 0.02,
          currency: 'USD',
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 0,
        platformRate: 40,
      ).validate(),
      throwsArgumentError,
    );

    expect(
      () => const InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        price: 99999999.99,
        originalPrice: 0,
          currency: 'USD',
        consultationFee: 99999999.99,
        commissionRate: 0,
        institutionRate: 0,
        platformRate: 40,
      ).validate(),
      returnsNormally,
    );
  },);

  test('draft target lists include commas in backend length boundaries', () {
    final tagBoundary = <String>[
      List.filled(100, 'a').join(),
      List.filled(100, 'b').join(),
      List.filled(100, 'c').join(),
      List.filled(100, 'd').join(),
      List.filled(96, 'e').join(),
    ];
    final tagOverBoundary = <String>[
      ...tagBoundary.take(4),
      List.filled(97, 'e').join(),
    ];
    final imageBoundary = <String>[
      List.filled(500, 'a').join(),
      List.filled(500, 'b').join(),
      List.filled(500, 'c').join(),
      List.filled(497, 'd').join(),
    ];
    final imageOverBoundary = <String>[
      ...imageBoundary.take(3),
      List.filled(498, 'd').join(),
    ];

    expect(tagBoundary.join(',').length, 500);
    expect(tagOverBoundary.join(',').length, 501);
    expect(imageBoundary.join(',').length, 2000);
    expect(imageOverBoundary.join(',').length, 2001);

    expect(
      () => PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        tags: tagBoundary,
        categoryTags: tagBoundary,
        images: imageBoundary,
      ).validate(),
      returnsNormally,
    );
    for (final draft in [
      PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        tags: tagOverBoundary,
      ),
      PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        categoryTags: tagOverBoundary,
      ),
      PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        images: imageOverBoundary,
      ),
    ]) {
      expect(draft.validate, throwsArgumentError);
    }

    expect(
      () => InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        tags: tagBoundary,
        images: imageBoundary,
        price: 0.02,
        currency: 'USD',
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 0,
        platformRate: 40,
      ).validate(),
      returnsNormally,
    );
    for (final draft in [
      InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        tags: tagOverBoundary,
        price: 0.02,
        currency: 'USD',
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 0,
        platformRate: 40,
      ),
      InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        images: imageOverBoundary,
        price: 0.02,
        currency: 'USD',
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 0,
        platformRate: 40,
      ),
    ]) {
      expect(draft.validate, throwsArgumentError);
    }
  });

  test('draft validation rejects hidden JSON precision without float rounding',
      () {
    expect(
      () => const PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        referencePrice: 1.2300000001,
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => const PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        referencePrice: 1e-7,
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        price: 0.1 + 0.2,
          currency: 'USD',
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 0,
        platformRate: 40,
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        price: 0.02,
          currency: 'USD',
        consultationFee: 0,
        commissionRate: 0.1 + 0.2,
        institutionRate: 0,
        platformRate: 40,
      ).validate(),
      throwsArgumentError,
    );
  },);

  test('draft validation sums exact hundredths without binary float drift', () {
    expect(
      () => const InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        price: 0.02,
        currency: 'USD',
        consultationFee: 0,
        commissionRate: 35.95,
        institutionRate: 24.05,
        platformRate: 40,
      ).validate(),
      returnsNormally,
    );
  });

  test('draft sales count accepts Int32 max and rejects values outside it', () {
    expect(
      () => const PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        salesCount: 2147483647,
      ).validate(),
      returnsNormally,
    );
    expect(
      () => const PlatformProjectRequestDraft(
        name: 'Name',
        category: 'Skin',
        description: 'Description',
        salesCount: 2147483648,
      ).validate(),
      throwsArgumentError,
    );
    expect(
      () => const InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        price: 0.02,
        currency: 'USD',
        salesCount: 2147483647,
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 0,
        platformRate: 40,
      ).validate(),
      returnsNormally,
    );
    expect(
      () => const InstitutionProjectRequestDraft(
        institutionId: 'institution-1',
        projectId: 'project-1',
        price: 0.02,
        currency: 'USD',
        salesCount: 2147483648,
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 0,
        platformRate: 40,
      ).validate(),
      throwsArgumentError,
    );
  });

  test('parses complete immutable review snapshot and nested split', () {
    final request = ProfessionalProjectRequest.fromJson(_requestSnapshot);

    expect(request.id, 'request-1');
    expect(request.requestType, 'INSTITUTION');
    expect(request.doctorId, 'doctor-1');
    expect(request.doctorName, 'Dr. Chen');
    expect(request.institutionId, 'institution-1');
    expect(request.institutionName, 'Joysong Clinic');
    expect(request.projectId, 'project-1');
    expect(request.projectName, 'Hydrating Facial');
    expect(request.name, 'Clinic Hydrating Facial');
    expect(request.category, 'Skin');
    expect(request.description, 'Clinic description');
    expect(request.tags, ['hydration', 'signature']);
    expect(request.slogan, 'Clinic glow');
    expect(request.detailContent, 'Complete immutable detail');
    expect(request.currency, 'CNY');
    expect(request.coverImage, 'cover.jpg');
    expect(request.images, ['one.jpg', 'two.jpg']);
    expect(request.salesCount, 7);
    expect(request.referencePrice, isNull);
    expect(request.categoryTags, isNull);
    expect(request.price, 799.5);
    expect(request.originalPrice, 999.99);
    expect(request.isActive, isTrue);
    expect(request.institutionSplit?.consultationFee, 80.25);
    expect(request.institutionSplit?.commissionRate, 12.5);
    expect(request.institutionSplit?.institutionRate, 42.25);
    expect(request.institutionSplit?.platformRate, 10);
    expect(request.institutionSplit?.doctorRate, 35.25);
    expect(request.notes, 'Clinic note');
    expect(request.status, 'PENDING');
    expect(request.reviewNote, isNull);
    expect(request.reviewedBy, isNull);
    expect(request.reviewedAt, isNull);
    expect(request.resultingProjectId, isNull);
    expect(request.resultingInstitutionProjectId, isNull);
    expect(request.submittedAt, DateTime.parse('2026-08-16T08:00:00'));
    expect(request.updatedAt, DateTime.parse('2026-08-16T08:05:00'));
    expect(request.isCreationReviewable, isTrue);

    final legacy = ProfessionalProjectRequest.fromJson({
      ..._requestSnapshot,
      'status': 'CHANGES_REQUESTED',
    });
    expect(legacy.status, 'CHANGES_REQUESTED');
    expect(legacy.isCreationReviewable, isFalse);

    final platform =
        ProfessionalProjectRequest.fromJson(_platformRequestSnapshot(),);
    expect(platform.slogan, '');
    expect(platform.coverImage, '');
    expect(platform.institutionId, isNull);
    expect(platform.price, isNull);
    expect(platform.originalPrice, isNull);
    expect(platform.isActive, isNull);
    expect(platform.institutionSplit, isNull);

    final institutionWithoutOverrides = ProfessionalProjectRequest.fromJson({
      ..._requestSnapshot,
      'slogan': null,
      'coverImage': null,
    });
    expect(institutionWithoutOverrides.slogan, isNull);
    expect(institutionWithoutOverrides.coverImage, isNull);
  });

  test(
      'current platform drift keeps an exact negative doctor rate structurally complete but not approvable',
      () {
    final request = ProfessionalProjectRequest.fromJson({
      ..._requestSnapshot,
      'institutionSplit': {
        'consultationFee': 80.25,
        'commissionRate': 50,
        'institutionRate': 50,
        'platformRate': 100,
        'doctorRate': -100,
      },
    });

    expect(request.hasCompleteReviewSnapshot, isTrue);
    expect(request.isCurrentlyApprovable, isFalse);
    expect(request.institutionSplit?.doctorRate, -100);

    for (final invalidSplit in [
      {
        'consultationFee': 80.25,
        'commissionRate': 50,
        'institutionRate': 50,
        'platformRate': 100,
        'doctorRate': -100.001,
      },
      {
        'consultationFee': 80.25,
        'commissionRate': 60,
        'institutionRate': 60,
        'platformRate': 0,
        'doctorRate': -20,
      },
    ]) {
      expect(
        () => ProfessionalProjectRequest.fromJson({
          ..._requestSnapshot,
          'institutionSplit': invalidSplit,
        }),
        throwsFormatException,
      );
    }
  },);

  test('review snapshots enforce the V28 request-type-exclusive shape', () {
    for (final contradiction in <String, Object?>{
      'institutionId': 'institution-1',
      'institutionName': 'Current clinic',
      'projectId': 'project-1',
      'projectName': 'Current project',
      'price': 1,
      'originalPrice': 2,
      'isActive': true,
      'institutionSplit': const {
        'consultationFee': 0,
        'commissionRate': 0,
        'institutionRate': 0,
        'platformRate': 0,
        'doctorRate': 100,
      },
    }.entries) {
      expect(
        () => ProfessionalProjectRequest.fromJson({
          ..._platformRequestSnapshot(),
          contradiction.key: contradiction.value,
        }),
        throwsFormatException,
        reason: 'PLATFORM must reject ${contradiction.key}',
      );
    }

    for (final contradiction in <String, Object?>{
      'referencePrice': 1,
      'categoryTags': const ['skin'],
    }.entries) {
      expect(
        () => ProfessionalProjectRequest.fromJson({
          ..._requestSnapshot,
          contradiction.key: contradiction.value,
        }),
        throwsFormatException,
        reason: 'INSTITUTION must reject ${contradiction.key}',
      );
    }
  });

  test('review snapshot parsing fails closed for missing or invalid invariants',
      () {
    for (final field in [
      'currency',
      'salesCount',
      'reviewedAt',
      'submittedAt',
      'updatedAt',
    ]) {
      final snapshot = <String, Object?>{..._requestSnapshot}..remove(field);
      expect(
        () => ProfessionalProjectRequest.fromJson(snapshot),
        throwsFormatException,
        reason: 'missing common response key $field must fail closed',
      );
    }

    for (final field in [
      'name',
      'category',
      'description',
      'referencePrice',
      'tags',
      'categoryTags',
      'images',
    ]) {
      final snapshot = _platformRequestSnapshot()..remove(field);
      expect(
        () => ProfessionalProjectRequest.fromJson(snapshot),
        throwsFormatException,
        reason: 'missing platform snapshot field $field must fail closed',
      );
    }

    for (final field in [
      'institutionId',
      'projectId',
      'price',
      'isActive',
      'institutionSplit',
    ]) {
      final snapshot = <String, Object?>{..._requestSnapshot}..remove(field);
      expect(
        () => ProfessionalProjectRequest.fromJson(snapshot),
        throwsFormatException,
        reason: 'missing institution snapshot field $field must fail closed',
      );
    }

    final incompleteSplit = <String, Object?>{
      ..._requestSnapshot,
      'institutionSplit': <String, Object?>{
        ...(_requestSnapshot['institutionSplit']! as Map<String, Object?>),
      }..remove('doctorRate'),
    };
    expect(
      () => ProfessionalProjectRequest.fromJson(incompleteSplit),
      throwsFormatException,
    );
    expect(
      () => ProfessionalProjectRequest.fromJson({
        ..._requestSnapshot,
        'requestType': 'UNKNOWN',
      }),
      throwsFormatException,
    );

    final parsed = ProfessionalProjectRequest.fromJson(_requestSnapshot);
    expect((parsed as dynamic).hasCompleteReviewSnapshot, isTrue);
  },);

  test(
      'project response decoder rejects numeric coercion, unnormalized text, malformed lists, limits, and invalid ISO times',
      () {
    String repeated(int length) => List.filled(length, 'x').join();
    final split = _requestSnapshot['institutionSplit']! as Map<String, Object?>;

    final malformed = <({Map<String, Object?> snapshot, String reason})>[
      (
        snapshot: {..._platformRequestSnapshot(), 'referencePrice': '899.25'},
        reason: 'money numeric string',
      ),
      (
        snapshot: {..._requestSnapshot, 'price': '799.5'},
        reason: 'institution money numeric string',
      ),
      (
        snapshot: {
          ..._requestSnapshot,
          'institutionSplit': {...split, 'doctorRate': '35.25'},
        },
        reason: 'rate numeric string',
      ),
      (
        snapshot: {..._requestSnapshot, 'salesCount': '7'},
        reason: 'integer numeric string',
      ),
      (
        snapshot: {..._platformRequestSnapshot(), 'name': ' Name '},
        reason: 'unnormalized required text',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'detailContent': '   '
        },
        reason: 'blank optional text',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'tags': const ['hydration', '   '],
        },
        reason: 'blank list item',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'tags': const <Object>['hydration', 1],
        },
        reason: 'non-string list item',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'tags': List<String>.filled(21, 'tag'),
        },
        reason: 'too many tag items',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'tags': [repeated(101)],
        },
        reason: 'overlong tag item',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'tags': [
            repeated(100),
            repeated(100),
            repeated(100),
            repeated(100),
            repeated(97),
          ],
        },
        reason: '501-character target tag list',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'images': [
            repeated(500),
            repeated(500),
            repeated(500),
            repeated(498),
          ],
        },
        reason: '2001-character target image list',
      ),
      (
        snapshot: {..._platformRequestSnapshot(), 'name': repeated(201)},
        reason: 'overlong name',
      ),
      (
        snapshot: {..._platformRequestSnapshot(), 'category': repeated(101)},
        reason: 'overlong category',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'description': repeated(5001),
        },
        reason: 'overlong description',
      ),
      (
        snapshot: {..._platformRequestSnapshot(), 'slogan': repeated(501)},
        reason: 'overlong slogan',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'detailContent': repeated(20001),
        },
        reason: 'overlong detail',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'coverImage': repeated(501),
        },
        reason: 'overlong cover image',
      ),
      (
        snapshot: {..._platformRequestSnapshot(), 'notes': repeated(2001)},
        reason: 'overlong notes',
      ),
      (
        snapshot: {..._requestSnapshot, 'name': repeated(201)},
        reason: 'overlong nullable institution override',
      ),
      (
        snapshot: {..._requestSnapshot, 'salesCount': 2147483648},
        reason: 'sales count above Int32',
      ),
      (
        snapshot: {..._platformRequestSnapshot(), 'currency': 'cny'},
        reason: 'non-canonical currency',
      ),
      (
        snapshot: {..._platformRequestSnapshot(), 'status': 'UNKNOWN'},
        reason: 'unknown status',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'submittedAt': '2026-02-30T08:00:00',
        },
        reason: 'normalized invalid calendar date',
      ),
      (
        snapshot: {
          ..._platformRequestSnapshot(),
          'updatedAt': ' 2026-08-16T08:05:00 ',
        },
        reason: 'non-canonical ISO time',
      ),
    ];

    for (final entry in malformed) {
      expect(
        () => ProfessionalProjectRequest.fromJson(entry.snapshot),
        throwsFormatException,
        reason: entry.reason,
      );
    }
  },);

  test(
      'project response decoder accepts exact text list money and rate boundaries',
      () {
    String repeated(int length, String value) =>
        List.filled(length, value).join();
    final tagBoundary = [
      repeated(100, 'a'),
      repeated(100, 'b'),
      repeated(100, 'c'),
      repeated(100, 'd'),
      repeated(96, 'e'),
    ];
    final imageBoundary = [
      repeated(500, 'a'),
      repeated(500, 'b'),
      repeated(500, 'c'),
      repeated(497, 'd'),
    ];
    expect(tagBoundary.join(',').length, 500);
    expect(imageBoundary.join(',').length, 2000);

    final platform = ProfessionalProjectRequest.fromJson({
      ..._platformRequestSnapshot(),
      'name': repeated(200, 'n'),
      'category': repeated(100, 'c'),
      'description': repeated(5000, 'd'),
      'slogan': repeated(500, 's'),
      'detailContent': repeated(20000, 't'),
      'coverImage': repeated(500, 'u'),
      'notes': repeated(2000, 'o'),
      'tags': tagBoundary,
      'categoryTags': tagBoundary,
      'images': imageBoundary,
      'salesCount': 2147483647,
      'referencePrice': 99999999.99,
      'submittedAt': '2024-02-29T23:59:59.123456789',
      'updatedAt': '2024-03-01T00:00:00',
    });
    expect(platform.hasCompleteReviewSnapshot, isTrue);
    expect(platform.tags, tagBoundary,
        reason: 'strict response decoding must not rewrite valid list items',);

    final institution = ProfessionalProjectRequest.fromJson({
      ..._requestSnapshot,
      'name': repeated(200, 'n'),
      'category': repeated(100, 'c'),
      'description': repeated(5000, 'd'),
      'slogan': repeated(500, 's'),
      'detailContent': repeated(20000, 't'),
      'coverImage': repeated(500, 'u'),
      'notes': repeated(2000, 'o'),
      'tags': tagBoundary,
      'images': imageBoundary,
      'salesCount': 2147483647,
      'price': 99999999.99,
      'originalPrice': 99999999.99,
      'institutionSplit': const {
        'consultationFee': 99999999.99,
        'commissionRate': 50,
        'institutionRate': 50,
        'platformRate': 100,
        'doctorRate': -100,
      },
    });
    expect(institution.hasCompleteReviewSnapshot, isTrue);
    expect(institution.isCurrentlyApprovable, isFalse);
    expect(institution.tags, tagBoundary);
    expect(institution.images, imageBoundary);

    final legacy = ProfessionalProjectRequest.fromJson({
      ..._requestSnapshot,
      'status': 'CHANGES_REQUESTED',
      'tags': const <String>[],
      'images': const <String>[],
    });
    expect(legacy.status, 'CHANGES_REQUESTED');
    expect(legacy.hasCompleteReviewSnapshot, isTrue);
  },);

  test('parses form config and all 13 management inheritance values', () {
    final config = InstitutionProjectApplicationFormConfig.fromJson({
      'platformRate': 10.25,
    });
    final project = ManagementProjectOption.fromJson({
      'id': 'project-1',
      'name': 'Hydrating Facial',
      'category': 'Skin',
      'description': 'Description',
      'tags': 'hydration,gentle',
      'categoryTags': 'facial,skin',
      'coverImage': 'cover.jpg',
      'referencePrice': 899.25,
      'currency': 'CNY',
      'slogan': 'Glow naturally',
      'detailContent': 'Details',
      'images': '["one.jpg","two.jpg"]',
      'salesCount': 18,
    });

    expect(config.platformRate, 10.25);
    expect(project.id, 'project-1');
    expect(project.name, 'Hydrating Facial');
    expect(project.category, 'Skin');
    expect(project.description, 'Description');
    expect(project.tags, 'hydration,gentle');
    expect(project.categoryTags, 'facial,skin');
    expect(project.coverImage, 'cover.jpg');
    expect(project.referencePrice, 899.25);
    expect(project.currency, 'CNY');
    expect(project.slogan, 'Glow naturally');
    expect(project.detailContent, 'Details');
    expect(project.images, ['one.jpg', 'two.jpg']);
    expect(project.salesCount, 18);
  });

  test(
      'repository project request list rejects missing or malformed data instead of defaulting to empty',
      () async {
    for (final client in [
      _ProjectRequestListApiClient(data: const <String, Object?>{}),
      _ProjectRequestListApiClient(data: 'not-a-list'),
      _ProjectRequestListApiClient(data: null),
      _ProjectRequestListApiClient.missing(),
      _ProjectRequestListApiClient(data: const [
        <String, Object?>{'id': 'incomplete-request'},
      ],),
    ]) {
      final repository = ApiIdentityRepository(client);
      await expectLater(
        repository.listProfessionalProjectRequests(),
        throwsFormatException,
      );
    }

    final empty = await ApiIdentityRepository(
      _ProjectRequestListApiClient(data: const <Object?>[]),
    ).listProfessionalProjectRequests();
    expect(empty, isEmpty);
  },);

  test('repository records distinct submission config and review routes',
      () async {
    final client = _RecordingApiClient();
    final repository = ApiIdentityRepository(client);
    final config =
        await repository.loadInstitutionProjectApplicationFormConfig();
    const platformDraft = PlatformProjectRequestDraft(
      name: 'Name',
      category: 'Category',
      description: 'Description',
    );
    const institutionDraft = InstitutionProjectRequestDraft(
      institutionId: ' institution-1 ',
      projectId: 'project-1',
      price: 100,
      consultationFee: 5,
      commissionRate: 10,
      institutionRate: 40,
      platformRate: 10,
    );

    await repository.submitPlatformProjectRequest(platformDraft);
    await repository.submitInstitutionProjectRequest(institutionDraft);
    await repository.reviewInstitutionProjectRequest(
      id: ' request-1 ',
      decision: ' approved ',
      reviewNote: ' Looks good ',
    );
    await repository.reviewPlatformProjectRequest(
      id: ' request-2 ',
      decision: ' rejected ',
      reviewNote: ' Needs evidence ',
    );

    expect(config.platformRate, 10);
    expect(client.requests, [
      const _Request(
        'GET',
        '/management/project-requests/institution-form-config',
      ),
      _Request(
        'POST',
        '/management/project-requests/platform',
        body: platformDraft.toJson(),
      ),
      _Request(
        'POST',
        '/management/project-requests/institutions/institution-1',
        body: institutionDraft.toJson(),
      ),
      const _Request(
        'POST',
        '/management/project-requests/request-1/review',
        body: {'decision': 'APPROVED', 'reviewNote': 'Looks good'},
      ),
      const _Request(
        'POST',
        '/admin/project-requests/request-2/review',
        body: {'decision': 'REJECTED', 'reviewNote': 'Needs evidence'},
      ),
    ]);
  },);
}

const _requestSnapshot = <String, Object?>{
  'id': 'request-1',
  'requestType': 'INSTITUTION',
  'doctorId': 'doctor-1',
  'doctorName': 'Dr. Chen',
  'institutionId': 'institution-1',
  'institutionName': 'Joysong Clinic',
  'projectId': 'project-1',
  'projectName': 'Hydrating Facial',
  'name': 'Clinic Hydrating Facial',
  'category': 'Skin',
  'description': 'Clinic description',
  'tags': ['hydration', 'signature'],
  'slogan': 'Clinic glow',
  'detailContent': 'Complete immutable detail',
  'currency': 'CNY',
  'coverImage': 'cover.jpg',
  'images': ['one.jpg', 'two.jpg'],
  'salesCount': 7,
  'referencePrice': null,
  'categoryTags': null,
  'price': 799.5,
  'originalPrice': 999.99,
  'isActive': true,
  'institutionSplit': {
    'consultationFee': 80.25,
    'commissionRate': 12.5,
    'institutionRate': 42.25,
    'platformRate': 10,
    'doctorRate': 35.25,
  },
  'notes': 'Clinic note',
  'status': 'PENDING',
  'reviewNote': null,
  'reviewedBy': null,
  'reviewedAt': null,
  'resultingProjectId': null,
  'resultingInstitutionProjectId': null,
  'submittedAt': '2026-08-16T08:00:00',
  'updatedAt': '2026-08-16T08:05:00',
};

Map<String, Object?> _platformRequestSnapshot() => <String, Object?>{
      ..._requestSnapshot,
      'requestType': 'PLATFORM',
      'institutionId': null,
      'institutionName': null,
      'projectId': null,
      'projectName': null,
      'slogan': '',
      'coverImage': '',
      'referencePrice': 899.25,
      'categoryTags': ['facial'],
      'price': null,
      'originalPrice': null,
      'isActive': null,
      'institutionSplit': null,
    };

final class _RecordingApiClient extends ApiClient {
  _RecordingApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  final List<_Request> requests = [];

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    requests.add(_Request('GET', path));
    return decodeData({'platformRate': 10});
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    requests.add(_Request('POST', path, body: body));
    return decodeData(null);
  }
}

final class _ProjectRequestListApiClient extends ApiClient {
  _ProjectRequestListApiClient({required this.data})
      : hasData = true,
        super(apiRoot: Uri.parse('http://localhost/api/'));

  _ProjectRequestListApiClient.missing()
      : data = null,
        hasData = false,
        super(apiRoot: Uri.parse('http://localhost/api/'));

  final Object? data;
  final bool hasData;

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    if (!hasData) return null;
    return decodeData(data);
  }
}

final class _Request {
  const _Request(this.method, this.path, {this.body});

  final String method;
  final String path;
  final Object? body;

  @override
  bool operator ==(Object other) =>
      other is _Request &&
      other.method == method &&
      other.path == path &&
      _deepEquals(other.body, body);

  @override
  int get hashCode => Object.hash(method, path, body);

  @override
  String toString() => '$method $path body=$body';
}

bool _deepEquals(Object? left, Object? right) {
  if (left is Map && right is Map) {
    return left.length == right.length &&
        left.entries.every(
          (entry) =>
              right.containsKey(entry.key) &&
              _deepEquals(entry.value, right[entry.key]),
        );
  }
  if (left is List && right is List) {
    return left.length == right.length &&
        Iterable.generate(left.length,)
            .every((index) => _deepEquals(left[index], right[index]));
  }
  return left == right;
}
