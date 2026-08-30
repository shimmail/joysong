import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/institution_project_review_widgets.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/professional_management/presentation/professional_pages.dart';
import 'package:joysong_flutter/features/shell/presentation/app_shell.dart';

void main() {
  testWidgets(
    'review notification focuses the visible requested application',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 3000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      const requestId = 'review-request';
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          home: InstitutionProjectRequestsPage(
            repository: _ProjectRequestsRepository(
              requests: [_request(id: requestId, doctorId: 'doctor-2')],
            ),
            context: _legalRepresentativeContext,
            reviewMode: true,
            initialRequestId: requestId,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Review details'), findsOneWidget);
      final requestPage = tester.widget<InstitutionProjectRequestsPage>(
        find.byType(InstitutionProjectRequestsPage, skipOffstage: false),
      );
      expect(requestPage.reviewMode, isTrue);
      final detail = tester.widget<InstitutionProjectReviewDetailPage>(
        find.byType(InstitutionProjectReviewDetailPage),
      );
      expect(detail.item.id, requestId);
      expect(detail.actions, isNull);
    },
  );

  testWidgets(
    "application notification shows the doctor's requested review result",
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 3000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      const requestId = 'application-request';
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          home: InstitutionProjectRequestsPage(
            repository: _ProjectRequestsRepository(
              requests: [
                _request(
                  id: requestId,
                  doctorId: 'doctor-1',
                  status: 'REJECTED',
                ),
              ],
            ),
            context: _doctorContext,
            initialRequestId: requestId,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Review details'), findsOneWidget);
      final requestPage = tester.widget<InstitutionProjectRequestsPage>(
        find.byType(InstitutionProjectRequestsPage, skipOffstage: false),
      );
      expect(requestPage.reviewMode, isFalse);
      final detail = tester.widget<InstitutionProjectReviewDetailPage>(
        find.byType(InstitutionProjectReviewDetailPage),
      );
      expect(detail.item.id, requestId);
      expect(detail.actions, isNull);
    },
  );

  testWidgets(
    'stale application notification keeps the new-request form available',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 3000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          home: InstitutionProjectRequestsPage(
            repository: _ProjectRequestsRepository(
              requests: [_request(id: 'another-request', doctorId: 'doctor-1')],
            ),
            context: _doctorContext,
            initialRequestId: 'missing-request',
          ),
        ),
      );
      await tester.pumpAndSettle();

      final submit = find.byKey(const Key('institution-submit'));
      expect(submit, findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'system review notification preserves request id and review mode',
    (tester) async {
      const requestId = 'review-route-target';
      await _openSystemNotification(
        tester,
        targetType: 'institution_project_review',
        requestId: requestId,
        context: _legalRepresentativeContext,
        requests: [
          _request(
            id: 'review-route-decoy',
            doctorId: 'doctor-3',
            status: 'PENDING',
          ),
          _request(
            id: requestId,
            doctorId: 'doctor-2',
            status: 'PENDING',
          ),
        ],
      );

      final requestPageFinder = find.byType(
        InstitutionProjectRequestsPage,
        skipOffstage: false,
      );
      expect(requestPageFinder, findsOneWidget);
      final requestPage =
          tester.widget<InstitutionProjectRequestsPage>(requestPageFinder);
      expect(requestPage.initialRequestId, requestId);
      expect(requestPage.reviewMode, isTrue);
      final detail = tester.widget<InstitutionProjectReviewDetailPage>(
        find.byType(InstitutionProjectReviewDetailPage),
      );
      expect(detail.item.id, requestId);
    },
  );

  testWidgets(
    'system application notification opens the doctor result exactly once',
    (tester) async {
      const requestId = 'application-route-target';
      await _openSystemNotification(
        tester,
        targetType: 'institution_project_application',
        requestId: requestId,
        context: _doctorContext,
        requests: [
          _request(id: 'application-route-decoy', doctorId: 'doctor-1'),
          _request(id: requestId, doctorId: 'doctor-1'),
        ],
      );

      final requestPageFinder = find.byType(
        InstitutionProjectRequestsPage,
        skipOffstage: false,
      );
      expect(requestPageFinder, findsOneWidget);
      final requestPage =
          tester.widget<InstitutionProjectRequestsPage>(requestPageFinder);
      expect(requestPage.initialRequestId, requestId);
      expect(requestPage.reviewMode, isFalse);
      final detail = tester.widget<InstitutionProjectReviewDetailPage>(
        find.byType(InstitutionProjectReviewDetailPage),
      );
      expect(detail.item.id, requestId);
      expect(detail.actions, isNull);
      expect(
        find.byType(InstitutionProjectReviewDetailPage, skipOffstage: false),
        findsOneWidget,
      );
      await tester.pageBack();
      await tester.pumpAndSettle();
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.byType(ManagementCenterPage), findsOneWidget);
      expect(find.byType(InstitutionProjectRequestsPage), findsNothing);
      expect(find.byType(InstitutionProjectReviewDetailPage), findsNothing);
      await tester.tap(find.byTooltip('Refresh access'));
      await tester.pumpAndSettle();
      expect(find.byType(InstitutionProjectRequestsPage), findsNothing);
      expect(find.byType(InstitutionProjectReviewDetailPage), findsNothing);
    },
  );

  testWidgets(
    'doctor booking notification opens the matching professional order detail',
    (tester) async {
      const orderId = 'professional-order-route-target';
      final apiClient = _ProfessionalOrderApiClient(orderId);
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          home: AppShell(
            agentConfig: const AgentConfig(),
            apiClient: apiClient,
            allowPreviewData: true,
            currentUserId: _doctorContext.userId,
            dependencies: AppShellDependencies(
              identityRepository: const _ProjectRequestsRepository(
                requests: [],
              ),
              discoverRepository: const _UnusedDiscoverRepository(),
              messagingRepository: _NotificationMessagingRepository(
                const AppNotification(
                  id: 'notification-professional-order',
                  userId: 'doctor-user',
                  type: 'ORDER_SERVICE_ACTIVATED',
                  title: 'Project booking',
                  content: 'Project: Thermage',
                  targetType: 'professional_doctor_orders',
                  targetId: orderId,
                  isRead: false,
                  createdAt: '2026-08-30T00:00:00Z',
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.forum_outlined));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('message-center-system')));
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(
          const ValueKey<String>(
            'notification-row:notification-professional-order',
          ),
        ),
      );
      await tester.pumpAndSettle();

      final page = tester.widget<DoctorOrderDetailPage>(
        find.byType(DoctorOrderDetailPage),
      );
      expect(page.id, orderId);
      expect(apiClient.requestedPaths, ['/management/orders/$orderId']);
      expect(find.text('Thermage appointment'), findsAtLeastNWidgets(1));
    },
  );
}

Future<void> _openSystemNotification(
  WidgetTester tester, {
  required String targetType,
  required String requestId,
  required ManagementContext context,
  required List<ProfessionalProjectRequest> requests,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      locale: const Locale('en'),
      home: AppShell(
        agentConfig: const AgentConfig(),
        allowPreviewData: true,
        currentUserId: context.userId,
        dependencies: AppShellDependencies(
          identityRepository: _ProjectRequestsRepository(
            requests: requests,
            context: context,
          ),
          discoverRepository: const _UnusedDiscoverRepository(),
          messagingRepository: _NotificationMessagingRepository(
            AppNotification(
              id: 'notification-$requestId',
              userId: context.userId,
              type: targetType == 'institution_project_review'
                  ? 'INSTITUTION_PROJECT_APPLICATION_SUBMITTED'
                  : 'INSTITUTION_PROJECT_APPLICATION_APPROVED',
              title: 'Project application update',
              content: 'Open the project request',
              targetType: targetType,
              targetId: requestId,
              isRead: false,
              createdAt: '2026-08-30T00:00:00Z',
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byIcon(Icons.forum_outlined));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('message-center-system')));
  await tester.pumpAndSettle();
  await tester.tap(
    find.byKey(
      ValueKey<String>('notification-row:notification-$requestId'),
    ),
  );
  await tester.pumpAndSettle();
}

const _legalRepresentativeContext = ManagementContext(
  userId: 'legal-user',
  platformRole: 'USER',
  activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
  managedInstitutionIds: ['institution-1'],
  visibleInstitutionIds: ['institution-1'],
  canReviewInstitutionProjectRequests: true,
);

const _doctorContext = ManagementContext(
  userId: 'doctor-user',
  platformRole: 'USER',
  activeRoles: ['DOCTOR'],
  doctorId: 'doctor-1',
  doctorInstitutionIds: ['institution-1'],
  managedInstitutionIds: [],
  visibleInstitutionIds: ['institution-1'],
  canSubmitInstitutionProjectRequests: true,
);

ProfessionalProjectRequest _request({
  required String id,
  required String doctorId,
  String status = 'APPROVED',
}) =>
    ProfessionalProjectRequest(
      id: id,
      requestType: 'INSTITUTION',
      doctorId: doctorId,
      doctorName: 'Dr. Request',
      institutionId: 'institution-1',
      institutionName: 'Institution One',
      projectId: 'project-1',
      projectName: 'Platform Project',
      name: 'Requested Project',
      category: 'Skin',
      description: 'Request description',
      tags: const ['skin'],
      slogan: 'Fresh skin',
      detailContent: 'Detailed description',
      currency: 'USD',
      coverImage: '',
      images: const [],
      salesCount: 1,
      price: 100,
      originalPrice: 120,
      isActive: true,
      institutionSplit: const InstitutionProjectSplit(
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 20,
        platformRate: 10,
        doctorRate: 70,
      ),
      notes: 'Application note',
      status: status,
      reviewNote: 'reviewed request',
      reviewedBy: 'legal-user',
      reviewedAt: DateTime(2026, 8, 30),
      submittedAt: DateTime(2026, 8, 29),
      updatedAt: DateTime(2026, 8, 30),
    );

final class _ProjectRequestsRepository implements IdentityRepository {
  const _ProjectRequestsRepository({
    required this.requests,
    this.context = _doctorContext,
  });

  final List<ProfessionalProjectRequest> requests;
  final ManagementContext context;

  @override
  Future<ManagementContext> loadManagementContext() async => context;

  @override
  Future<List<ProfessionalProjectRequest>>
      listProfessionalProjectRequests() async => requests;

  @override
  Future<List<InstitutionOption>> listInstitutionOptions() async => const [
        InstitutionOption(id: 'institution-1', name: 'Institution One'),
      ];

  @override
  Future<List<ManagementProjectOption>> listManagementProjects() async =>
      const [
        ManagementProjectOption(
          id: 'project-1',
          name: 'Platform Project',
          category: 'Skin',
          description: 'Project description',
          tags: 'skin',
          slogan: 'Fresh skin',
          coverImage: '',
          images: [],
          salesCount: 1,
        ),
      ];

  @override
  Future<InstitutionProjectApplicationFormConfig>
      loadInstitutionProjectApplicationFormConfig() async =>
          const InstitutionProjectApplicationFormConfig(platformRate: 10);

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnsupportedError(invocation.memberName.toString());
}

final class _UnusedDiscoverRepository implements DiscoverRepository {
  const _UnusedDiscoverRepository();

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() async =>
      const DiscoverFilterOptions();

  @override
  Future<DiscoverPageResult> loadPage({
    required DiscoverContentType type,
    required int offset,
    required int limit,
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
  }) async =>
      const DiscoverPageResult(items: [], hasMore: false);

  @override
  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  }) =>
      throw UnsupportedError(id);
}

final class _NotificationMessagingRepository implements MessagingRepository {
  _NotificationMessagingRepository(this.notification);

  final AppNotification notification;
  var _read = false;

  @override
  Future<List<AppNotification>> getNotifications({int limit = 50}) async =>
      [notification.copyWith(isRead: _read)];

  @override
  Future<NotificationUnreadCounts> getUnreadNotificationCounts() async =>
      NotificationUnreadCounts(
        total: _read ? 0 : 1,
        system: _read ? 0 : 1,
        activity: 0,
      );

  @override
  Future<void> markNotificationRead(String notificationId) async {
    _read = true;
  }

  @override
  Future<List<DmConversation>> getDmConversations() async => const [];

  @override
  Future<List<CustomerServiceConversation>>
      getCustomerServiceConversations() async => const [];

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnsupportedError(invocation.memberName.toString());
}

final class _ProfessionalOrderApiClient extends ApiClient {
  _ProfessionalOrderApiClient(this.orderId)
      : super(apiRoot: Uri.parse('https://example.test/api/'));

  final String orderId;
  final List<String> requestedPaths = [];

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    requestedPaths.add(path);
    if (path != '/management/orders/$orderId') {
      throw UnsupportedError(path);
    }
    return decodeData({
      'id': orderId,
      'orderNo': 'ORDER-20260915',
      'projectName': 'Thermage appointment',
      'institutionName': 'Institution One',
      'status': 'SERVICE_ACTIVE',
      'amount': '100.00',
      'createdAt': '2026-08-30T10:00:00',
      'canVerify': false,
      'canRequestCompletion': false,
    });
  }
}
