import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/messaging/presentation/time_cursor_pager.dart';

void main() {
  test('uses earliest timestamp cursor and deduplicates by message id',
      () async {
    final beforeValues = <String?>[];
    var calls = 0;
    final pager = TimeCursorPager<_Item>(
      pageSize: 2,
      loader: ({required limit, before}) async {
        beforeValues.add(before);
        calls++;
        return switch (calls) {
          1 => const [
              _Item('m2', '2026-08-06T10:02:00'),
              _Item('m3', '2026-08-06T10:03:00'),
            ],
          2 => const [
              _Item('m1', '2026-08-06T10:01:00'),
              _Item('m2', '2026-08-06T10:02:00'),
            ],
          _ => const [
              _Item('m1', '2026-08-06T10:01:00'),
              _Item('m2', '2026-08-06T10:02:00'),
            ],
        };
      },
      idOf: (item) => item.id,
      createdAtOf: (item) => item.createdAt,
    );

    await pager.loadInitial();
    await pager.loadOlder();
    await pager.loadOlder();

    expect(beforeValues, [null, '2026-08-06T10:02:00', '2026-08-06T10:01:00']);
    expect(pager.items.map((item) => item.id), ['m1', 'm2', 'm3']);
    expect(pager.hasMore, isFalse, reason: '重复页不能导致无限翻页');
  });
}

class _Item {
  const _Item(this.id, this.createdAt);
  final String id;
  final String createdAt;
}
