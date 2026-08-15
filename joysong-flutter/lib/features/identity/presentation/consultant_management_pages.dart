import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/institution_relationships_page.dart';

class ConsultantMembershipPage extends StatelessWidget {
  const ConsultantMembershipPage({
    required this.repository,
    required this.discoverRepository,
    this.canApply = true,
    super.key,
  });

  final IdentityRepository repository;

  // Retained only so older route call sites continue to compile.
  final DiscoverRepository discoverRepository;
  final bool canApply;

  @override
  Widget build(BuildContext context) => InstitutionRelationshipsPage(
        repository: repository,
        scope: InstitutionRelationshipScope.consultant,
      );
}

class ConsultantProjectCatalogPage extends StatefulWidget {
  const ConsultantProjectCatalogPage({required this.repository, super.key});

  final IdentityRepository repository;

  @override
  State<ConsultantProjectCatalogPage> createState() =>
      _ConsultantProjectCatalogPageState();
}

class _ConsultantProjectCatalogPageState
    extends State<ConsultantProjectCatalogPage> {
  List<ManagementProjectOption> _items = const [];
  Object? _error;
  var _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final items = await widget.repository.listManagementProjects();
      if (!mounted) return;
      setState(() {
        _items = items;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized('项目目录', 'Project catalog')),
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? _RetryState(
                    message: context.localized(
                        '项目目录加载失败', 'Unable to load the project catalog'),
                    onRetry: _load,
                  )
                : RefreshIndicator(
                    onRefresh: _load,
                    child: ListView.builder(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.all(16),
                      itemCount: _items.isEmpty ? 1 : _items.length,
                      itemBuilder: (context, index) {
                        if (_items.isEmpty) {
                          return Padding(
                            padding: const EdgeInsets.only(top: 48),
                            child: Center(
                              child: Text(context.localized(
                                  '暂无可用项目', 'No projects available')),
                            ),
                          );
                        }
                        final item = _items[index];
                        return Card(
                          child: ListTile(
                            leading: const Icon(Icons.spa_outlined),
                            title: Text(item.name),
                            subtitle: Text([
                              if (item.category.isNotEmpty) item.category,
                              if (item.description.isNotEmpty) item.description,
                              if (item.tags.isNotEmpty) item.tags,
                              if (item.categoryTags.isNotEmpty)
                                item.categoryTags,
                              '${item.referencePrice.toStringAsFixed(2)} ${item.currency}',
                            ].join('\n')),
                          ),
                        );
                      },
                    ),
                  ),
      );
}

class _RetryState extends StatelessWidget {
  const _RetryState({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(message),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: onRetry,
              child: Text(context.localized('重试', 'Retry')),
            ),
          ],
        ),
      );
}
