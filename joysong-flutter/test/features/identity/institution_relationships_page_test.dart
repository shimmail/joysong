import 'dart:ui' show Tristate;

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/institution_relationships_page.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_page.dart';

void main() {
  final applicantCases = [
    const _ApplicantCase(
      scope: InstitutionRelationshipScope.doctor,
      type: InstitutionMembershipRequestType.doctor,
      currentId: 'doctor-current-uuid',
      currentName: 'Doctor Current Clinic',
      otherCurrentName: 'Consultant Current Clinic',
    ),
    const _ApplicantCase(
      scope: InstitutionRelationshipScope.consultant,
      type: InstitutionMembershipRequestType.consultant,
      currentId: 'consultant-current-uuid',
      currentName: 'Consultant Current Clinic',
      otherCurrentName: 'Doctor Current Clinic',
    ),
  ];

  for (final testCase in applicantCases) {
    testWidgets(
      '${testCase.type.code} scope uses its context ids and filters mixed owned rows',
      (tester) async {
        final repository = _FakeIdentityRepository(
          contexts: [_dualContext()],
          ownedResponses: [_mixedOwnedRequests()],
        );

        await _mount(
          tester,
          InstitutionRelationshipsPage(
            repository: repository,
            scope: testCase.scope,
          ),
        );

        expect(
          find.descendant(
            of: find
                .byKey(ValueKey('current-institution-${testCase.currentId}')),
            matching: find.text(testCase.currentName),
          ),
          findsOneWidget,
        );
        expect(find.text(testCase.otherCurrentName), findsNothing);
        expect(find.textContaining(testCase.currentId), findsNothing);
        expect(find.textContaining('applicant-uuid'), findsNothing);
        expect(find.text('${testCase.type.code} pending note'), findsOneWidget);
        expect(
          find.text(
            testCase.type == InstitutionMembershipRequestType.doctor
                ? 'CONSULTANT pending note'
                : 'DOCTOR pending note',
          ),
          findsNothing,
        );

        final semantics = tester.getSemantics(
          find.byKey(const Key('relationship-history-toggle')),
        );
        expect(semantics.flagsCollection.isExpanded, Tristate.isFalse);
        expect(find.text('${testCase.type.code} rejected note'), findsNothing);

        await tester.tap(
          find.byKey(const Key('relationship-history-toggle')),
        );
        await tester.pumpAndSettle();

        expect(
            find.text('${testCase.type.code} rejected note'), findsOneWidget);
        expect(find.textContaining('Rejected'), findsWidgets);
        expect(find.textContaining('Withdrawn'), findsWidgets);
        expect(find.textContaining('Approved'), findsWidgets);
        expect(find.textContaining('Join institution'), findsWidgets);
        expect(find.textContaining('Leave institution'), findsWidgets);
        expect(find.textContaining('approved-only-uuid'), findsNothing);
        expect(
          tester
              .getSemantics(
                find.byKey(const Key('relationship-history-toggle')),
              )
              .flagsCollection
              .isExpanded,
          Tristate.isTrue,
        );
        expect(repository.ownedCalls, 1);
        expect(repository.reviewableCalls, 0);
        expect(repository.candidateCalls.single.requestType, testCase.type);
        expect(repository.candidateCalls.single.action,
            InstitutionMembershipAction.join);
      },
    );

    for (final action in InstitutionMembershipAction.values) {
      testWidgets(
        '${testCase.type.code} submits explicit ${action.code} and filters picker candidates',
        (tester) async {
          final currentId = testCase.currentId;
          final pendingId = '${testCase.type.code.toLowerCase()}-pending-uuid';
          final repository = _FakeIdentityRepository(
            contexts: [_dualContext(), _dualContext()],
            ownedResponses: [
              [
                _request(
                  id: 'pending-request',
                  type: testCase.type,
                  institutionId: pendingId,
                  institutionName: 'Pending Clinic',
                  action: InstitutionMembershipAction.join,
                  status: InstitutionMembershipRequestStatus.pending,
                  note: 'Pending application',
                ),
                _request(
                  id: 'current-name',
                  type: testCase.type,
                  institutionId: currentId,
                  institutionName: testCase.currentName,
                  action: InstitutionMembershipAction.leave,
                  status: InstitutionMembershipRequestStatus.rejected,
                  relationshipStatus: InstitutionRelationshipStatus.approved,
                ),
              ],
              [
                _request(
                  id: 'submitted-visible',
                  type: testCase.type,
                  institutionId: action == InstitutionMembershipAction.join
                      ? 'eligible-uuid'
                      : currentId,
                  institutionName: action == InstitutionMembershipAction.join
                      ? 'Eligible Clinic'
                      : testCase.currentName,
                  action: action,
                  status: InstitutionMembershipRequestStatus.pending,
                  note: 'Visible after submit',
                  relationshipStatus:
                      action == InstitutionMembershipAction.leave
                          ? InstitutionRelationshipStatus.approved
                          : InstitutionRelationshipStatus.none,
                ),
              ],
            ],
            candidateHandler: (call) async => _candidatePage([
              InstitutionMembershipCandidate(
                id: currentId,
                name: testCase.currentName,
              ),
              const InstitutionMembershipCandidate(
                id: 'unrelated-current-uuid',
                name: 'Unrelated Clinic',
              ),
              InstitutionMembershipCandidate(
                id: pendingId,
                name: 'Pending Clinic',
              ),
              const InstitutionMembershipCandidate(
                id: 'eligible-uuid',
                name: 'Eligible Clinic',
              ),
            ]),
          );

          await _mount(
            tester,
            InstitutionRelationshipsPage(
              repository: repository,
              scope: testCase.scope,
            ),
          );
          if (action == InstitutionMembershipAction.leave) {
            await tester.tap(find.text('Leave institution').first);
            await tester.pumpAndSettle();
          }
          await tester.tap(
            find.byKey(const Key('relationship-institution-picker')),
          );
          await tester.pumpAndSettle();

          if (action == InstitutionMembershipAction.join) {
            expect(find.text(testCase.currentName), findsNothing);
            expect(find.text('Pending Clinic'), findsNothing);
            expect(find.text('Eligible Clinic'), findsOneWidget);
            await tester.tap(find.text('Eligible Clinic'));
          } else {
            expect(find.text(testCase.currentName), findsOneWidget);
            expect(find.text('Unrelated Clinic'), findsNothing);
            expect(find.text('Pending Clinic'), findsNothing);
            expect(find.text('Eligible Clinic'), findsNothing);
            await tester.tap(find.text(testCase.currentName));
          }
          await tester.pumpAndSettle();
          await tester.enterText(
            find.byKey(const Key('relationship-request-note')),
            '  exact typed request  ',
          );
          await tester.tap(find.byKey(const Key('relationship-submit')));
          await tester.pumpAndSettle();

          final draft = repository.submitted.single;
          expect(draft.requestType, testCase.type);
          expect(draft.action, action);
          expect(
            draft.institutionId,
            action == InstitutionMembershipAction.join
                ? 'eligible-uuid'
                : currentId,
          );
          expect(draft.requestNote, '  exact typed request  ');
          expect(find.text('Visible after submit'), findsOneWidget);
          expect(repository.contextCalls, 2);
          expect(repository.ownedCalls, 2);
          expect(
            repository.candidateCalls
                .where((call) => call.action == action)
                .length,
            greaterThanOrEqualTo(2),
          );
          expect(
            repository.candidateCalls.every(
              (call) => call.requestType == testCase.type,
            ),
            isTrue,
          );
        },
      );
    }

    testWidgets(
      '${testCase.type.code} withdraws its own pending row and visibly refreshes',
      (tester) async {
        final currentAfterId = '${testCase.type.code.toLowerCase()}-after-uuid';
        final contextBefore = _dualContext();
        final contextAfter = _dualContext(
          doctorIds: testCase.type == InstitutionMembershipRequestType.doctor
              ? [currentAfterId]
              : null,
          consultantIds:
              testCase.type == InstitutionMembershipRequestType.consultant
                  ? [currentAfterId]
                  : null,
        );
        final repository = _FakeIdentityRepository(
          contexts: [contextBefore, contextAfter],
          ownedResponses: [
            [
              _request(
                id: 'withdraw-me',
                type: testCase.type,
                institutionId: 'pending-withdraw-uuid',
                institutionName: 'Pending Withdraw Clinic',
                action: InstitutionMembershipAction.join,
                status: InstitutionMembershipRequestStatus.pending,
                note: 'Withdraw me now',
              ),
            ],
            [
              _request(
                id: 'withdraw-me',
                type: testCase.type,
                institutionId: 'pending-withdraw-uuid',
                institutionName: 'Pending Withdraw Clinic',
                action: InstitutionMembershipAction.join,
                status: InstitutionMembershipRequestStatus.withdrawn,
                note: 'Withdrawn history row',
              ),
              _request(
                id: 'new-current-name',
                type: testCase.type,
                institutionId: currentAfterId,
                institutionName: 'Current After Refresh',
                action: InstitutionMembershipAction.join,
                status: InstitutionMembershipRequestStatus.rejected,
              ),
            ],
          ],
        );

        await _mount(
          tester,
          InstitutionRelationshipsPage(
            repository: repository,
            scope: testCase.scope,
          ),
        );
        await tester.tap(find.byKey(const Key('withdraw-withdraw-me')));
        await tester.pumpAndSettle();

        expect(repository.withdrawn.single.requestType, testCase.type);
        expect(repository.withdrawn.single.id, 'withdraw-me');
        expect(find.text('Withdraw me now'), findsNothing);
        expect(find.text('Current After Refresh'), findsOneWidget);
        expect(repository.contextCalls, 2);
        expect(repository.ownedCalls, 2);
        expect(repository.candidateCalls.length, 2);
        await tester.tap(
          find.byKey(const Key('relationship-history-toggle')),
        );
        await tester.pumpAndSettle();
        expect(find.text('Withdrawn history row'), findsOneWidget);
        expect(find.textContaining('Withdrawn'), findsWidgets);
      },
    );
  }

  testWidgets(
    'canApply false keeps current pending and history but hides every mutation',
    (tester) async {
      final repository = _FakeIdentityRepository(
        contexts: [
          _dualContext(canApply: false),
          _dualContext(canApply: false),
        ],
        ownedResponses: [
          [
            _request(
              id: 'pending-leave',
              type: InstitutionMembershipRequestType.doctor,
              institutionId: 'doctor-current-uuid',
              institutionName: 'Doctor Current Clinic',
              action: InstitutionMembershipAction.leave,
              status: InstitutionMembershipRequestStatus.pending,
              relationshipStatus: InstitutionRelationshipStatus.approved,
              note: 'Pending leave is visible',
            ),
            _request(
              id: 'history-rejected',
              type: InstitutionMembershipRequestType.doctor,
              institutionId: 'history-uuid',
              institutionName: 'History Clinic',
              action: InstitutionMembershipAction.join,
              status: InstitutionMembershipRequestStatus.rejected,
              note: 'History before refresh',
            ),
          ],
          [
            _request(
              id: 'pending-leave',
              type: InstitutionMembershipRequestType.doctor,
              institutionId: 'doctor-current-uuid',
              institutionName: 'Doctor Current Clinic',
              action: InstitutionMembershipAction.leave,
              status: InstitutionMembershipRequestStatus.pending,
              relationshipStatus: InstitutionRelationshipStatus.approved,
              note: 'Pending leave is visible',
            ),
            _request(
              id: 'history-rejected',
              type: InstitutionMembershipRequestType.doctor,
              institutionId: 'history-uuid',
              institutionName: 'History Clinic',
              action: InstitutionMembershipAction.join,
              status: InstitutionMembershipRequestStatus.withdrawn,
              note: 'History after refresh',
            ),
          ],
        ],
      );

      await _mount(
        tester,
        InstitutionRelationshipsPage(
          repository: repository,
          scope: InstitutionRelationshipScope.doctor,
        ),
      );

      expect(find.text('Doctor Current Clinic'), findsWidgets);
      expect(find.text('Awaiting exit review'), findsOneWidget);
      expect(find.text('Pending leave is visible'), findsOneWidget);
      expect(find.byKey(const Key('relationship-institution-picker')),
          findsNothing);
      expect(find.byKey(const Key('relationship-request-note')), findsNothing);
      expect(find.byKey(const Key('relationship-submit')), findsNothing);
      expect(find.byKey(const Key('withdraw-pending-leave')), findsNothing);
      expect(
        find.byType(SegmentedButton<InstitutionMembershipAction>),
        findsNothing,
      );
      expect(repository.candidateCalls, isEmpty);

      await tester.tap(find.byKey(const Key('relationship-history-toggle')));
      await tester.pumpAndSettle();
      expect(find.text('History before refresh'), findsOneWidget);
      await tester
          .widget<RefreshIndicator>(find.byType(RefreshIndicator))
          .onRefresh();
      await tester.pumpAndSettle();
      expect(find.text('History after refresh'), findsOneWidget);
      expect(
        tester
            .getSemantics(find.byKey(const Key('relationship-history-toggle')))
            .flagsCollection
            .isExpanded,
        Tristate.isTrue,
      );
      expect(repository.candidateCalls, isEmpty);
    },
  );

  testWidgets(
    'legal scope filters managed reviewable rows and approve moves pending to history',
    (tester) async {
      final pending = _request(
        id: 'managed-pending',
        type: InstitutionMembershipRequestType.doctor,
        applicantId: 'doctor-applicant-uuid',
        applicantName: 'Dr Lin',
        institutionId: 'managed-1',
        institutionName: 'Managed Clinic',
        action: InstitutionMembershipAction.leave,
        status: InstitutionMembershipRequestStatus.pending,
        relationshipStatus: InstitutionRelationshipStatus.approved,
        note: 'Managed pending',
      );
      final history = _request(
        id: 'managed-history',
        type: InstitutionMembershipRequestType.consultant,
        applicantId: 'consultant-applicant-uuid',
        applicantName: 'Alex Chen',
        institutionId: 'managed-1',
        institutionName: 'Managed Clinic',
        action: InstitutionMembershipAction.join,
        status: InstitutionMembershipRequestStatus.rejected,
        note: 'Managed history',
      );
      final unmanaged = _request(
        id: 'unmanaged-pending',
        type: InstitutionMembershipRequestType.consultant,
        institutionId: 'unmanaged-uuid',
        institutionName: 'Unmanaged Clinic',
        action: InstitutionMembershipAction.join,
        status: InstitutionMembershipRequestStatus.pending,
        note: 'Must be filtered',
      );
      final repository = _FakeIdentityRepository(
        contexts: [_legalContext(), _legalContext()],
        reviewableResponses: [
          [pending, history, unmanaged],
          [
            _copyRequest(
              pending,
              status: InstitutionMembershipRequestStatus.approved,
            ),
            history,
            unmanaged,
          ],
        ],
      );

      await _mount(
        tester,
        InstitutionRelationshipsPage(
          repository: repository,
          scope: InstitutionRelationshipScope.legalRepresentative,
        ),
      );

      expect(find.text('Managed pending'), findsOneWidget);
      expect(find.text('Must be filtered'), findsNothing);
      expect(find.text('Doctor'), findsOneWidget);
      expect(find.text('Leave institution'), findsOneWidget);
      expect(find.text('Managed history'), findsNothing);
      expect(
        tester
            .getSemantics(
              find.byKey(const Key('relationship-history-toggle')),
            )
            .label,
        'Review history',
      );
      await tester.tap(find.byKey(const Key('relationship-history-toggle')));
      await tester.pumpAndSettle();
      expect(find.text('Managed history'), findsOneWidget);
      expect(find.text('Consultant'), findsOneWidget);
      expect(find.text('Join institution'), findsOneWidget);

      await tester.tap(find.byKey(const Key('approve-managed-pending')));
      await tester.pumpAndSettle();

      final review = repository.reviewed.single;
      expect(review.requestType, InstitutionMembershipRequestType.doctor);
      expect(review.id, 'managed-pending');
      expect(review.decision, InstitutionMembershipDecision.approved);
      expect(find.byKey(const Key('approve-managed-pending')), findsNothing);
      expect(find.text('Managed pending'), findsOneWidget);
      expect(find.textContaining('Approved'), findsWidgets);
      expect(
        tester
            .getSemantics(find.byKey(const Key('relationship-history-toggle')))
            .flagsCollection
            .isExpanded,
        Tristate.isTrue,
      );
      expect(repository.contextCalls, 2);
      expect(repository.reviewableCalls, 2);
      expect(repository.ownedCalls, 0);
      expect(repository.candidateCalls, isEmpty);
      expect(find.textContaining('doctor-applicant-uuid'), findsNothing);
      expect(find.textContaining('unmanaged-uuid'), findsNothing);
    },
  );

  testWidgets('legal reject requires a reason then sends exact row type and id',
      (tester) async {
    final pending = _request(
      id: 'consultant-leave',
      type: InstitutionMembershipRequestType.consultant,
      applicantName: 'Consultant Lee',
      institutionId: 'managed-1',
      institutionName: 'Managed Clinic',
      action: InstitutionMembershipAction.leave,
      status: InstitutionMembershipRequestStatus.pending,
      relationshipStatus: InstitutionRelationshipStatus.approved,
      note: 'Consultant leave request',
    );
    final repository = _FakeIdentityRepository(
      contexts: [_legalContext(), _legalContext()],
      reviewableResponses: [
        [pending],
        [
          _copyRequest(
            pending,
            status: InstitutionMembershipRequestStatus.rejected,
            reviewNote: 'Documents are incomplete',
          ),
        ],
      ],
    );

    await _mount(
      tester,
      InstitutionRelationshipsPage(
        repository: repository,
        scope: InstitutionRelationshipScope.legalRepresentative,
      ),
    );
    await tester.tap(find.byKey(const Key('reject-consultant-leave')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('review-confirm')));
    await tester.pumpAndSettle();

    expect(repository.reviewed, isEmpty);
    expect(find.text('Rejection reason is required'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('review-reason')),
      '  Documents are incomplete  ',
    );
    await tester.tap(find.byKey(const Key('review-confirm')));
    await tester.pumpAndSettle();

    final review = repository.reviewed.single;
    expect(review.requestType, InstitutionMembershipRequestType.consultant);
    expect(review.id, 'consultant-leave');
    expect(review.decision, InstitutionMembershipDecision.rejected);
    expect(review.reviewNote, 'Documents are incomplete');
    expect(find.text('No pending requests'), findsOneWidget);
    await tester.tap(find.byKey(const Key('relationship-history-toggle')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Documents are incomplete'), findsWidgets);
  });

  testWidgets('legal empty and API error states are localized', (tester) async {
    await _mount(
      tester,
      InstitutionRelationshipsPage(
        repository: _FakeIdentityRepository(
          contexts: [_legalContext()],
          reviewableResponses: const [[]],
        ),
        scope: InstitutionRelationshipScope.legalRepresentative,
      ),
      locale: const Locale('zh'),
    );
    expect(find.text('暂无待审核申请'), findsOneWidget);
    expect(find.text('暂无审核历史'), findsOneWidget);

    await _mount(
      tester,
      InstitutionRelationshipsPage(
        key: const ValueKey('legal-error-page'),
        repository: _FakeIdentityRepository(
          contexts: [_legalContext()],
          reviewableError: StateError('backend detail must stay hidden'),
        ),
        scope: InstitutionRelationshipScope.legalRepresentative,
      ),
    );
    expect(find.text('Unable to load institution requests'), findsOneWidget);
    expect(find.byKey(const Key('relationship-retry')), findsOneWidget);
    expect(find.textContaining('backend detail'), findsNothing);
  });

  testWidgets(
    'profile relationship shortcut ignores missing discover repository and opens the sole consultant scope',
    (tester) async {
      final repository = _FakeIdentityRepository(
        contexts: const [
          ManagementContext(
            userId: 'consultant-profile-user',
            platformRole: 'USER',
            activeRoles: ['CONSULTANT'],
            managedInstitutionIds: [],
            visibleInstitutionIds: [],
            consultantInstitutionIds: ['consultant-current-uuid'],
            canApplyToInstitutions: true,
          ),
        ],
      );

      await _mount(
        tester,
        ProfilePage(identityRepository: repository),
      );

      final shortcut = find.text('Institution relationships');
      expect(shortcut, findsOneWidget);
      await tester.ensureVisible(shortcut);
      await tester.tap(shortcut);
      await tester.pumpAndSettle();

      expect(
        tester
            .widget<InstitutionRelationshipsPage>(
              find.byType(InstitutionRelationshipsPage),
            )
            .scope,
        InstitutionRelationshipScope.consultant,
      );
    },
  );

  testWidgets(
    'profile asks a dual doctor consultant user to choose an explicit relationship scope',
    (tester) async {
      final repository = _FakeIdentityRepository(
        contexts: [_dualContext()],
      );

      await _mount(
        tester,
        ProfilePage(identityRepository: repository),
      );

      final shortcut = find.text('Institution relationships');
      await tester.ensureVisible(shortcut);
      await tester.tap(shortcut);
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('institution-relationship-role-picker')),
        findsOneWidget,
      );
      expect(find.text('Choose professional role'), findsOneWidget);
      await tester.tap(
        find.byKey(const Key('institution-relationship-role-consultant')),
      );
      await tester.pumpAndSettle();

      expect(
        tester
            .widget<InstitutionRelationshipsPage>(
              find.byType(InstitutionRelationshipsPage),
            )
            .scope,
        InstitutionRelationshipScope.consultant,
      );
    },
  );
}

Future<void> _mount(
  WidgetTester tester,
  Widget child, {
  Locale locale = const Locale('en'),
}) async {
  tester.view.physicalSize = const Size(1000, 6000);
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

ManagementContext _dualContext({
  bool canApply = true,
  List<String>? doctorIds,
  List<String>? consultantIds,
}) =>
    ManagementContext(
      userId: 'protocol-user-uuid',
      platformRole: 'USER',
      activeRoles: const ['CONSULTANT', 'DOCTOR'],
      doctorId: 'different-doctor-profile-uuid',
      managedInstitutionIds: const [],
      visibleInstitutionIds: const [],
      doctorInstitutionIds: doctorIds ?? const ['doctor-current-uuid'],
      consultantInstitutionIds:
          consultantIds ?? const ['consultant-current-uuid'],
      canApplyToInstitutions: canApply,
    );

ManagementContext _legalContext() => const ManagementContext(
      userId: 'legal-user-uuid',
      platformRole: 'USER',
      activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
      managedInstitutionIds: ['managed-1'],
      visibleInstitutionIds: ['managed-1'],
      canReviewInstitutionRequests: true,
    );

List<InstitutionMembershipRequest> _mixedOwnedRequests() => [
      _request(
        id: 'doctor-pending',
        type: InstitutionMembershipRequestType.doctor,
        applicantId: 'doctor-applicant-uuid',
        institutionId: 'doctor-current-uuid',
        institutionName: 'Doctor Current Clinic',
        action: InstitutionMembershipAction.leave,
        status: InstitutionMembershipRequestStatus.pending,
        relationshipStatus: InstitutionRelationshipStatus.approved,
        note: 'DOCTOR pending note',
      ),
      _request(
        id: 'doctor-rejected',
        type: InstitutionMembershipRequestType.doctor,
        applicantId: 'doctor-applicant-uuid',
        institutionId: 'doctor-history-uuid',
        institutionName: 'Doctor History Clinic',
        action: InstitutionMembershipAction.join,
        status: InstitutionMembershipRequestStatus.rejected,
        note: 'DOCTOR rejected note',
      ),
      _request(
        id: 'doctor-withdrawn',
        type: InstitutionMembershipRequestType.doctor,
        applicantId: 'doctor-applicant-uuid',
        institutionId: 'doctor-withdrawn-uuid',
        institutionName: 'Doctor Withdrawn Clinic',
        action: InstitutionMembershipAction.join,
        status: InstitutionMembershipRequestStatus.withdrawn,
        note: 'DOCTOR withdrawn note',
      ),
      _request(
        id: 'doctor-approved-leave',
        type: InstitutionMembershipRequestType.doctor,
        applicantId: 'doctor-applicant-uuid',
        institutionId: 'approved-only-uuid',
        institutionName: 'Approved Historical Clinic',
        action: InstitutionMembershipAction.leave,
        status: InstitutionMembershipRequestStatus.approved,
        note: 'DOCTOR approved note',
      ),
      _request(
        id: 'consultant-pending',
        type: InstitutionMembershipRequestType.consultant,
        applicantId: 'consultant-applicant-uuid',
        institutionId: 'consultant-pending-uuid',
        institutionName: 'Consultant Pending Clinic',
        action: InstitutionMembershipAction.join,
        status: InstitutionMembershipRequestStatus.pending,
        note: 'CONSULTANT pending note',
      ),
      _request(
        id: 'consultant-current-name',
        type: InstitutionMembershipRequestType.consultant,
        applicantId: 'consultant-applicant-uuid',
        institutionId: 'consultant-current-uuid',
        institutionName: 'Consultant Current Clinic',
        action: InstitutionMembershipAction.join,
        status: InstitutionMembershipRequestStatus.rejected,
        note: 'CONSULTANT rejected note',
      ),
      _request(
        id: 'consultant-withdrawn',
        type: InstitutionMembershipRequestType.consultant,
        applicantId: 'consultant-applicant-uuid',
        institutionId: 'consultant-withdrawn-uuid',
        institutionName: 'Consultant Withdrawn Clinic',
        action: InstitutionMembershipAction.leave,
        status: InstitutionMembershipRequestStatus.withdrawn,
        note: 'CONSULTANT withdrawn note',
      ),
      _request(
        id: 'consultant-approved',
        type: InstitutionMembershipRequestType.consultant,
        applicantId: 'consultant-applicant-uuid',
        institutionId: 'approved-only-uuid',
        institutionName: 'Approved Historical Clinic',
        action: InstitutionMembershipAction.join,
        status: InstitutionMembershipRequestStatus.approved,
        note: 'CONSULTANT approved note',
      ),
    ];

InstitutionMembershipRequest _request({
  required String id,
  required InstitutionMembershipRequestType type,
  String applicantId = 'applicant-uuid',
  String applicantName = 'Applicant Name',
  required String institutionId,
  required String institutionName,
  required InstitutionMembershipAction action,
  required InstitutionMembershipRequestStatus status,
  InstitutionRelationshipStatus relationshipStatus =
      InstitutionRelationshipStatus.none,
  String note = '',
  String reviewNote = '',
}) =>
    InstitutionMembershipRequest(
      id: id,
      requestType: type,
      applicantId: applicantId,
      applicantName: applicantName,
      institutionId: institutionId,
      institutionName: institutionName,
      action: action,
      status: status,
      relationshipStatus: relationshipStatus,
      requestNote: note,
      reviewNote: reviewNote,
      submittedBy: applicantId,
      reviewedBy: status == InstitutionMembershipRequestStatus.pending
          ? null
          : 'reviewer-uuid',
      submittedAt: DateTime.utc(2026, 8, 14, 9),
      reviewedAt: status == InstitutionMembershipRequestStatus.pending
          ? null
          : DateTime.utc(2026, 8, 14, 10),
      createdAt: DateTime.utc(2026, 8, 14, 9),
      updatedAt: DateTime.utc(2026, 8, 14, 10),
    );

InstitutionMembershipRequest _copyRequest(
  InstitutionMembershipRequest request, {
  required InstitutionMembershipRequestStatus status,
  String? reviewNote,
}) =>
    _request(
      id: request.id,
      type: request.requestType,
      applicantId: request.applicantId,
      applicantName: request.applicantName,
      institutionId: request.institutionId,
      institutionName: request.institutionName,
      action: request.action,
      status: status,
      relationshipStatus: request.relationshipStatus,
      note: request.requestNote,
      reviewNote: reviewNote ?? request.reviewNote,
    );

InstitutionMembershipCandidatePage _candidatePage(
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

final class _ApplicantCase {
  const _ApplicantCase({
    required this.scope,
    required this.type,
    required this.currentId,
    required this.currentName,
    required this.otherCurrentName,
  });

  final InstitutionRelationshipScope scope;
  final InstitutionMembershipRequestType type;
  final String currentId;
  final String currentName;
  final String otherCurrentName;
}

final class _CandidateCall {
  const _CandidateCall({
    required this.requestType,
    required this.action,
    required this.query,
    required this.offset,
    required this.limit,
  });

  final InstitutionMembershipRequestType requestType;
  final InstitutionMembershipAction action;
  final String query;
  final int offset;
  final int limit;
}

final class _TypedIdCall {
  const _TypedIdCall(this.requestType, this.id);

  final InstitutionMembershipRequestType requestType;
  final String id;
}

final class _ReviewCall {
  const _ReviewCall({
    required this.requestType,
    required this.id,
    required this.decision,
    required this.reviewNote,
  });

  final InstitutionMembershipRequestType requestType;
  final String id;
  final InstitutionMembershipDecision decision;
  final String reviewNote;
}

final class _FakeIdentityRepository implements IdentityRepository {
  _FakeIdentityRepository({
    required List<ManagementContext> contexts,
    List<List<InstitutionMembershipRequest>> ownedResponses = const [[]],
    List<List<InstitutionMembershipRequest>> reviewableResponses = const [[]],
    this.candidateHandler,
    this.reviewableError,
  })  : _contexts = List.of(contexts),
        _ownedResponses = List.of(ownedResponses),
        _reviewableResponses = List.of(reviewableResponses);

  final List<ManagementContext> _contexts;
  final List<List<InstitutionMembershipRequest>> _ownedResponses;
  final List<List<InstitutionMembershipRequest>> _reviewableResponses;
  final Future<InstitutionMembershipCandidatePage> Function(_CandidateCall)?
      candidateHandler;
  final Object? reviewableError;
  final candidateCalls = <_CandidateCall>[];
  final submitted = <InstitutionMembershipRequestDraft>[];
  final withdrawn = <_TypedIdCall>[];
  final reviewed = <_ReviewCall>[];
  var contextCalls = 0;
  var ownedCalls = 0;
  var reviewableCalls = 0;

  T _response<T>(List<T> responses, int call) =>
      responses[call < responses.length ? call : responses.length - 1];

  @override
  Future<ManagementContext> loadManagementContext() async {
    final result = _response(_contexts, contextCalls);
    contextCalls += 1;
    return result;
  }

  @override
  Future<List<InstitutionMembershipRequest>>
      listOwnedInstitutionMembershipRequests() async {
    final result = _response(_ownedResponses, ownedCalls);
    ownedCalls += 1;
    return result;
  }

  @override
  Future<List<InstitutionMembershipRequest>>
      listReviewableInstitutionMembershipRequests() async {
    reviewableCalls += 1;
    if (reviewableError != null) throw reviewableError!;
    return _response(_reviewableResponses, reviewableCalls - 1);
  }

  @override
  Future<InstitutionMembershipCandidatePage>
      listInstitutionMembershipCandidates({
    required InstitutionMembershipRequestType requestType,
    required InstitutionMembershipAction action,
    required String query,
    required int offset,
    required int limit,
  }) async {
    final call = _CandidateCall(
      requestType: requestType,
      action: action,
      query: query,
      offset: offset,
      limit: limit,
    );
    candidateCalls.add(call);
    final handler = candidateHandler;
    if (handler != null) return handler(call);
    return _candidatePage(const [
      InstitutionMembershipCandidate(
        id: 'default-candidate-uuid',
        name: 'Default Candidate Clinic',
      ),
    ]);
  }

  @override
  Future<InstitutionMembershipRequest> submitInstitutionMembershipRequest(
    InstitutionMembershipRequestDraft draft,
  ) async {
    submitted.add(draft);
    return _request(
      id: 'submitted-result',
      type: draft.requestType,
      institutionId: draft.institutionId,
      institutionName: 'Submitted Clinic',
      action: draft.action,
      status: InstitutionMembershipRequestStatus.pending,
      note: draft.requestNote,
    );
  }

  @override
  Future<InstitutionMembershipRequest> withdrawInstitutionMembershipRequest({
    required InstitutionMembershipRequestType requestType,
    required String id,
  }) async {
    withdrawn.add(_TypedIdCall(requestType, id));
    return _request(
      id: id,
      type: requestType,
      institutionId: 'withdrawn-result-uuid',
      institutionName: 'Withdrawn Result Clinic',
      action: InstitutionMembershipAction.join,
      status: InstitutionMembershipRequestStatus.withdrawn,
    );
  }

  @override
  Future<InstitutionMembershipRequest> reviewInstitutionMembershipRequest({
    required InstitutionMembershipRequestType requestType,
    required String id,
    required InstitutionMembershipDecision decision,
    required String reviewNote,
  }) async {
    reviewed.add(_ReviewCall(
      requestType: requestType,
      id: id,
      decision: decision,
      reviewNote: reviewNote,
    ));
    return _request(
      id: id,
      type: requestType,
      institutionId: 'managed-1',
      institutionName: 'Managed Clinic',
      action: InstitutionMembershipAction.join,
      status: decision == InstitutionMembershipDecision.approved
          ? InstitutionMembershipRequestStatus.approved
          : InstitutionMembershipRequestStatus.rejected,
      reviewNote: reviewNote,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
