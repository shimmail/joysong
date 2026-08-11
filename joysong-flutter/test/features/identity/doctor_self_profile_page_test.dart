import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/doctor_detail_view.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

void main() {
  testWidgets('doctor loads, edits, clears, and saves all profile fields',
      (tester) async {
    _useTallView(tester);
    final repository = _RecordingIdentityRepository();

    await tester.pumpWidget(_profileApp(repository));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('doctor-name')), '  王医生  ');
    await tester.enterText(find.byKey(const Key('doctor-bio')), '');
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();

    final update = repository.lastUpdate!;
    expect({
      'name': update.name,
      'title': update.title,
      'bio': update.bio,
      'avatar': update.avatar,
      'contactPhone': update.contactPhone,
      'specialties': update.specialties,
      'credentials': update.credentials,
      'credentialImages': update.credentialImages,
      'certificationTags': update.certificationTags,
    }, {
      'name': '  王医生  ',
      'title': '主任医师',
      'bio': '',
      'avatar': 'https://cdn.example/doctor/avatar-old.jpg',
      'contactPhone': '13800138000',
      'specialties': '皮肤管理,微整形',
      'credentials': '执业医师资格',
      'credentialImages':
          'https://cdn.example/doctor/credential-1.jpg,https://cdn.example/doctor/credential-2.jpg',
      'certificationTags': '主任医师,十年经验',
    });
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('doctor-name')))
          .controller
          ?.text,
      '王医生',
    );
    expect(
      find.text('https://cdn.example/doctor/avatar-server.jpg'),
      findsOneWidget,
    );
    expect(
      find.text('https://cdn.example/doctor/credential-server.jpg'),
      findsOneWidget,
    );
  });

  testWidgets('doctor upload choices determine saved public image state',
      (tester) async {
    _useTallView(tester);
    final repository = _RecordingIdentityRepository();
    final uploadedUrls = [
      'https://cdn.example/doctor/avatar-new.jpg',
      'https://cdn.example/doctor/credential-3.jpg',
    ];
    var nextUpload = 0;

    await tester.pumpWidget(_profileApp(
      repository,
      pickAndUploadImage: () async => uploadedUrls[nextUpload++],
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('doctor-avatar-upload')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('doctor-credential-upload')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('doctor-credential-remove-0')));
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();

    expect(repository.lastUpdate?.avatar,
        'https://cdn.example/doctor/avatar-new.jpg');
    expect(
      repository.lastUpdate?.credentialImages,
      'https://cdn.example/doctor/credential-2.jpg,https://cdn.example/doctor/credential-3.jpg',
    );
  });

  testWidgets(
      'doctor detail labels public materials without verification claims',
      (tester) async {
    _useTallView(tester);
    await tester.pumpWidget(const MaterialApp(
      locale: Locale('zh'),
      supportedLocales: [Locale('zh')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: Scaffold(
        body: DoctorDetailView(
          item: DiscoverItem(
            id: 'doctor-1',
            type: DiscoverContentType.doctor,
            title: '林医生',
            raw: {
              'doctor': {
                'id': 'doctor-1',
                'name': '林医生',
                'title': '主任医师',
                'bio': '专注皮肤管理',
                'credentials': '执业医师资格',
                'certificationTags': '十年经验',
                'isVerified': true,
                'reviewCount': 12,
                'consultationCount': 8,
                'caseCount': 20,
              },
            },
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('医生上传的证书图片/展示材料'), findsOneWidget);
    expect(find.text('内容由医生公开上传，仅用于展示，不代表平台认证。'), findsOneWidget);
    expect(find.text('展示标签'), findsOneWidget);
    expect(find.text('十年经验'), findsOneWidget);
    expect(find.text('认证标签'), findsNothing);
    expect(find.text('资质保险箱'), findsNothing);
    expect(find.text('查资质'), findsNothing);
  });
}

void _useTallView(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Widget _profileApp(
  _RecordingIdentityRepository repository, {
  Future<String?> Function()? pickAndUploadImage,
}) =>
    MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: DoctorSelfProfilePage(
        repository: repository,
        pickAndUploadImage: pickAndUploadImage ?? () async => null,
      ),
    );

final class _RecordingIdentityRepository implements IdentityRepository {
  DoctorSelfProfileUpdate? lastUpdate;

  @override
  Future<DoctorSelfProfile> loadDoctorSelfProfile() async => _profile;

  @override
  Future<DoctorSelfProfile> updateDoctorSelfProfile(
    DoctorSelfProfileUpdate update,
  ) async {
    lastUpdate = update;
    return DoctorSelfProfile(
      id: _profile.id,
      userId: _profile.userId,
      name: update.name.trim(),
      title: update.title.trim(),
      bio: update.bio.trim(),
      avatar: 'https://cdn.example/doctor/avatar-server.jpg',
      contactPhone: update.contactPhone.trim(),
      specialties: update.specialties,
      credentials: update.credentials.trim(),
      credentialImages: 'https://cdn.example/doctor/credential-server.jpg',
      certificationTags: update.certificationTags,
      institutionId: _profile.institutionId,
      institutionName: _profile.institutionName,
      institutions: _profile.institutions,
      primaryInstitution: _profile.primaryInstitution,
      institutionCount: _profile.institutionCount,
      rating: _profile.rating,
      reviewCount: _profile.reviewCount,
      isVerified: _profile.isVerified,
      consultationCount: _profile.consultationCount,
      caseCount: _profile.caseCount,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

const _profile = DoctorSelfProfile(
  id: 'doctor-1',
  userId: 'user-1',
  name: '林医生',
  title: '主任医师',
  bio: '专注皮肤管理',
  avatar: 'https://cdn.example/doctor/avatar-old.jpg',
  contactPhone: '13800138000',
  specialties: '皮肤管理,微整形',
  credentials: '执业医师资格',
  credentialImages:
      'https://cdn.example/doctor/credential-1.jpg,https://cdn.example/doctor/credential-2.jpg',
  certificationTags: '主任医师,十年经验',
  institutionId: 'institution-1',
  institutionName: '悦美医疗美容',
  institutions: [
    DoctorInstitutionSummary(id: 'institution-1', name: '悦美医疗美容'),
  ],
  primaryInstitution:
      DoctorInstitutionSummary(id: 'institution-1', name: '悦美医疗美容'),
  institutionCount: 1,
  rating: 4.9,
  reviewCount: 12,
  isVerified: true,
  consultationCount: 8,
  caseCount: 20,
);
