import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/favorite_action_button.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

void main() {
  testWidgets(
    'loading favorite action remains layout-safe as ListTile trailing',
    (tester) async {
      final repository = _PendingFavoriteRepository();
      final controller = SocialController(repository);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          home: Scaffold(
            body: SizedBox(
              width: 360,
              child: ListTile(
                title: const Text('Doctor'),
                trailing: FavoriteActionButton(
                  controller: controller,
                  type: FavoriteTargetType.doctor,
                  targetId: 'doctor-1',
                  targetName: 'Doctor',
                ),
              ),
            ),
          ),
        ),
      );

      expect(tester.takeException(), isNull);
      expect(
        tester.getSize(find.byType(FavoriteActionButton)).width,
        lessThan(tester.getSize(find.byType(ListTile)).width),
      );

      repository.complete();
      await tester.pump();
    },
  );
}

final class _PendingFavoriteRepository implements SocialRepository {
  final Completer<EngagementStatus> _status = Completer<EngagementStatus>();

  @override
  Future<EngagementStatus> getFavoriteStatus(
    FavoriteTargetType type,
    String targetId,
  ) =>
      _status.future;

  void complete() {
    if (!_status.isCompleted) {
      _status.complete(const EngagementStatus(active: false, count: 0));
    }
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
