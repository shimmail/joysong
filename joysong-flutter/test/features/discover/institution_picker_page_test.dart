import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/institution_picker_page.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

void main() {
  testWidgets('picker trims search and requests institutions only',
      (tester) async {
    final repository = _FakeDiscoverRepository();

    await tester.pumpWidget(MaterialApp(
      home: InstitutionPickerPage(
        repository: repository,
        role: IdentityRoleType.doctor,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('institution-picker-search')),
      '  Joysong  ',
    );
    await tester.pump(const Duration(milliseconds: 351));
    await tester.pumpAndSettle();

    expect(repository.lastType, DiscoverContentType.institution);
    expect(repository.lastQuery, 'Joysong');
  });

  testWidgets('tapping an institution returns id and name without detail load',
      (tester) async {
    final repository = _FakeDiscoverRepository();
    InstitutionPickerSelection? selection;

    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () async {
            selection = await Navigator.of(context)
                .push<InstitutionPickerSelection>(MaterialPageRoute(
              builder: (_) => InstitutionPickerPage(
                repository: repository,
                role: IdentityRoleType.doctor,
              ),
            ));
          },
          child: const Text('open'),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Joysong Clinic'));
    await tester.pumpAndSettle();

    expect(selection?.id, 'inst-1');
    expect(selection?.name, 'Joysong Clinic');
    expect(repository.detailCalls, 0);
  });
}

final class _FakeDiscoverRepository implements DiscoverRepository {
  DiscoverContentType? lastType;
  String? lastQuery;
  var detailCalls = 0;

  @override
  Future<DiscoverPageResult> loadPage({
    required DiscoverContentType type,
    required int offset,
    required int limit,
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
  }) async {
    lastType = type;
    lastQuery = query;
    return const DiscoverPageResult(
      items: [
        DiscoverItem(
          id: 'inst-1',
          type: DiscoverContentType.institution,
          title: 'Joysong Clinic',
          subtitle: 'Shanghai',
        ),
      ],
      hasMore: false,
    );
  }

  @override
  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  }) async {
    detailCalls += 1;
    throw UnimplementedError();
  }

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() async =>
      const DiscoverFilterOptions();
}
