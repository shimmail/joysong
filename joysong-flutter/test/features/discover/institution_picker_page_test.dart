import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/presentation/institution_picker_page.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

void main() {
  testWidgets('picker debounces once and trims the latest search',
      (tester) async {
    final calls = <_LoadCall>[];
    await _mount(
      tester,
      InstitutionPickerPage(
        loadPage: ({required query, required offset, required limit}) async {
          calls.add(_LoadCall(query, offset, limit));
          return _page(
            [InstitutionMembershipCandidate(id: query, name: 'Result $query')],
            offset: offset,
            limit: limit,
          );
        },
      ),
    );
    expect(calls.single.query, '');

    await tester.enterText(
      find.byKey(const Key('institution-picker-search')),
      'first',
    );
    await tester.pump(const Duration(milliseconds: 200));
    await tester.enterText(
      find.byKey(const Key('institution-picker-search')),
      '  Joysong  ',
    );
    await tester.pump(const Duration(milliseconds: 349));
    expect(calls.length, 1);
    await tester.pump(const Duration(milliseconds: 1));
    await tester.pumpAndSettle();

    expect(calls.length, 2);
    expect(calls.last.query, 'Joysong');
    expect(calls.last.offset, 0);
  });

  testWidgets(
    'picker paginates from response offset and raw length then dedupes ids',
    (tester) async {
      final calls = <_LoadCall>[];
      var loadMoreAttempts = 0;
      await _mount(
        tester,
        InstitutionPickerPage(
          loadPage: ({required query, required offset, required limit}) async {
            calls.add(_LoadCall(query, offset, limit));
            if (calls.length == 1) {
              return _page(
                const [
                  InstitutionMembershipCandidate(id: 'one', name: 'Clinic 1'),
                  InstitutionMembershipCandidate(id: 'two', name: 'Clinic 2'),
                ],
                offset: 7,
                limit: 2,
                hasMore: true,
              );
            }
            loadMoreAttempts += 1;
            if (loadMoreAttempts == 1) {
              throw StateError('load more failed');
            }
            return _page(
              const [
                InstitutionMembershipCandidate(id: 'two', name: 'Clinic 2'),
                InstitutionMembershipCandidate(id: 'three', name: 'Clinic 3'),
              ],
              offset: offset,
              limit: 3,
            );
          },
        ),
        size: const Size(500, 300),
        locale: const Locale('zh'),
      );

      expect(find.text('Clinic 1'), findsOneWidget);
      expect(find.text('Clinic 2'), findsOneWidget);
      await tester.fling(find.byType(ListView), const Offset(0, -400), 1000);
      await tester.pumpAndSettle();

      expect(calls[1].offset, 9);
      expect(calls[1].limit, 2);
      expect(find.text('Clinic 1'), findsOneWidget);
      expect(find.text('加载失败，点击重试'), findsOneWidget);
      await tester.tap(
        find.byKey(const Key('institution-picker-load-more-retry')),
      );
      await tester.pumpAndSettle();

      expect(calls[2].offset, 9);
      expect(calls[2].limit, 2);
      expect(find.text('Clinic 1'), findsOneWidget);
      expect(find.text('Clinic 2'), findsOneWidget);
      expect(find.text('Clinic 3'), findsOneWidget);
      expect(find.text('Clinic 2'), findsOneWidget);
    },
  );

  testWidgets('picker continues after a fully filtered page with more results',
      (tester) async {
    final calls = <_LoadCall>[];
    await _mount(
      tester,
      InstitutionPickerPage(
        loadPage: ({required query, required offset, required limit}) async {
          calls.add(_LoadCall(query, offset, limit));
          if (offset == 0) {
            return _page(
              const [],
              offset: 2,
              limit: 3,
              hasMore: true,
            );
          }
          return _page(
            const [
              InstitutionMembershipCandidate(
                id: 'later',
                name: 'Later Eligible Clinic',
              ),
            ],
            offset: offset,
            limit: limit,
          );
        },
      ),
    );

    expect(calls, hasLength(2));
    expect(calls.last.offset, 2);
    expect(calls.last.limit, 3);
    expect(find.text('Later Eligible Clinic'), findsOneWidget);
  });

  testWidgets('picker localizes first-page failure and retries the same query',
      (tester) async {
    final calls = <_LoadCall>[];
    var attempt = 0;
    await _mount(
      tester,
      InstitutionPickerPage(
        loadPage: ({required query, required offset, required limit}) async {
          calls.add(_LoadCall(query, offset, limit));
          attempt += 1;
          if (attempt == 1) throw StateError('secret backend detail');
          return _page(
            const [
              InstitutionMembershipCandidate(
                id: 'recovered',
                name: 'Recovered Clinic',
              ),
            ],
            offset: offset,
            limit: limit,
          );
        },
      ),
    );

    expect(find.text('Unable to load institutions'), findsOneWidget);
    expect(find.textContaining('secret backend detail'), findsNothing);
    expect(find.byKey(const Key('institution-picker-retry')), findsOneWidget);
    await tester.tap(find.byKey(const Key('institution-picker-retry')));
    await tester.pumpAndSettle();

    expect(find.text('Recovered Clinic'), findsOneWidget);
    expect(calls.length, 2);
    expect(calls[0].query, calls[1].query);
    expect(calls[1].offset, 0);
  });

  testWidgets('stale search response cannot replace the newer results',
      (tester) async {
    final oldResult = Completer<InstitutionMembershipCandidatePage>();
    final newResult = Completer<InstitutionMembershipCandidatePage>();
    await _mount(
      tester,
      InstitutionPickerPage(
        loadPage: ({required query, required offset, required limit}) {
          if (query == 'old') return oldResult.future;
          if (query == 'new') return newResult.future;
          return Future.value(_page(const [], offset: 0, limit: limit));
        },
      ),
    );

    await tester.enterText(
      find.byKey(const Key('institution-picker-search')),
      'old',
    );
    await tester.pump(const Duration(milliseconds: 350));
    await tester.enterText(
      find.byKey(const Key('institution-picker-search')),
      'new',
    );
    oldResult.complete(_page(
      const [
        InstitutionMembershipCandidate(id: 'old', name: 'Old Clinic'),
      ],
    ));
    await tester.pump();
    expect(find.text('Old Clinic'), findsNothing);
    await tester.pump(const Duration(milliseconds: 350));

    newResult.complete(_page(
      const [
        InstitutionMembershipCandidate(id: 'new', name: 'New Clinic'),
      ],
    ));
    await tester.pumpAndSettle();
    expect(find.text('New Clinic'), findsOneWidget);
    expect(find.text('Old Clinic'), findsNothing);
  });

  testWidgets('tapping a candidate returns only its id and display name',
      (tester) async {
    var loaderCalls = 0;
    InstitutionPickerSelection? selection;
    await _mount(
      tester,
      Builder(
        builder: (context) => TextButton(
          onPressed: () async {
            selection = await Navigator.of(context)
                .push<InstitutionPickerSelection>(MaterialPageRoute(
              builder: (_) => InstitutionPickerPage(
                loadPage: ({required query, required offset, required limit}) {
                  loaderCalls += 1;
                  return Future.value(_page(
                    const [
                      InstitutionMembershipCandidate(
                        id: 'inst-1',
                        name: 'Joysong Clinic',
                      ),
                    ],
                  ));
                },
              ),
            ));
          },
          child: const Text('open'),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Joysong Clinic'));
    await tester.pumpAndSettle();

    expect(selection?.id, 'inst-1');
    expect(selection?.name, 'Joysong Clinic');
    expect(loaderCalls, 1);
  });
}

Future<void> _mount(
  WidgetTester tester,
  Widget child, {
  Size size = const Size(700, 800),
  Locale locale = const Locale('en'),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(
    locale: locale,
    supportedLocales: const [Locale('en'), Locale('zh')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: child,
  ));
  await tester.pumpAndSettle();
}

InstitutionMembershipCandidatePage _page(
  List<InstitutionMembershipCandidate> items, {
  int offset = 0,
  int limit = 20,
  bool hasMore = false,
}) =>
    InstitutionMembershipCandidatePage(
      items: items,
      offset: offset,
      limit: limit,
      hasMore: hasMore,
    );

final class _LoadCall {
  const _LoadCall(this.query, this.offset, this.limit);

  final String query;
  final int offset;
  final int limit;
}
