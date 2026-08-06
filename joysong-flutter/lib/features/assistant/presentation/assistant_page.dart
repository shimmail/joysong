import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/agent/data/agent_remote_data_source.dart';
import 'package:joysong_flutter/features/agent/data/agent_repository_impl.dart';
import 'package:joysong_flutter/features/agent/data/chat_sse_transport.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_page.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_controller.dart';
import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';
import 'package:joysong_flutter/features/auth/domain/token_store.dart';

class AssistantPage extends StatefulWidget {
  const AssistantPage({
    this.chatController,
    this.planController,
    super.key,
  }) : assert(
          (chatController == null) == (planController == null),
          '测试注入时必须同时提供 chatController 与 planController',
        );

  final AgentChatController? chatController;
  final AgentPlanController? planController;

  @override
  State<AssistantPage> createState() => _AssistantPageState();
}

class _AssistantPageState extends State<AssistantPage> {
  late final AgentChatController _chatController;
  late final AgentPlanController _planController;
  ApiClient? _ownedApiClient;
  bool _ownsControllers = false;

  @override
  void initState() {
    super.initState();
    if (widget.chatController case final controller?) {
      _chatController = controller;
      _planController = widget.planController!;
      return;
    }
    _ownsControllers = true;
    final environment = AppEnvironment.fromBuildDefines();
    final TokenStore tokenStore = SecureTokenStore();
    final apiClient = ApiClient(
      apiRoot: environment.apiRoot,
      accessTokenProvider: () async => (await tokenStore.read())?.accessToken,
    );
    _ownedApiClient = apiClient;
    final transport = HttpChatStreamTransport(
      apiRoot: environment.apiRoot,
      accessTokenProvider: () async => (await tokenStore.read())?.accessToken,
    );
    final AgentRepository repository = AgentRepositoryImpl(
      ApiAgentRemoteDataSource(
        apiClient: apiClient,
        streamTransport: transport,
      ),
    );
    _chatController = AgentChatController(
      repository: repository,
      streamingEnabled: const bool.fromEnvironment(
        'AI_STREAM_ENABLED',
        defaultValue: true,
      ),
    );
    _planController = AgentPlanController(repository);
  }

  @override
  void dispose() {
    if (_ownsControllers) {
      _chatController.dispose();
      _planController.dispose();
      _ownedApiClient?.close();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AgentChatPage(
        chatController: _chatController,
        planController: _planController,
      );
}
