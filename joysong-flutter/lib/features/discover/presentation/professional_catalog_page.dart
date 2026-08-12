import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';

enum ProfessionalCatalogScope { doctor, legalRepresentative }

class ProfessionalCatalogPage extends StatefulWidget {
  const ProfessionalCatalogPage({
    required this.repository,
    required this.scope,
    super.key,
  });

  final ProfessionalCatalogRepository repository;
  final ProfessionalCatalogScope scope;

  @override
  State<ProfessionalCatalogPage> createState() =>
      _ProfessionalCatalogPageState();
}

class _ProfessionalCatalogPageState extends State<ProfessionalCatalogPage> {
  final _search = TextEditingController();
  List<DiscoverItem> _institutions = const [], _projects = const [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final values = await Future.wait([
        widget.repository.loadVisibleInstitutions(),
        widget.repository.loadVisibleProjects(),
        widget.repository.loadVisibleInstitutionProjects(),
      ]);
      if (!mounted) return;
      setState(() {
        _institutions = values[0];
        _projects = [...values[1], ...values[2]];
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = context.localized(
            '目录暂不可用，请重试',
            'Catalog unavailable. Please retry.',
          );
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final query = _search.text.trim().toLowerCase();
    bool matches(DiscoverItem item) =>
        '${item.title} ${item.subtitle} ${item.meta}'.toLowerCase().contains(
              query,
            );
    final institutions = _institutions.where(matches).toList();
    final projects = _projects.where(matches).toList();
    return Scaffold(
      appBar: AppBar(
        title: Text(context.localized('专业目录', 'Professional catalog')),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(_error!),
                      TextButton(
                        onPressed: _load,
                        child: Text(context.localized('重试', 'Retry')),
                      ),
                    ],
                  ),
                )
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      TextField(
                        controller: _search,
                        onChanged: (_) => setState(() {}),
                        decoration: InputDecoration(
                          prefixIcon: const Icon(Icons.search),
                          hintText: context.localized(
                            '搜索机构、医生或项目',
                            'Search institutions, doctors, or projects',
                          ),
                        ),
                      ),
                      const SizedBox(height: 16),
                      if (_institutions.isEmpty && _projects.isEmpty)
                        Text(
                          context.localized(
                            '暂无可见目录内容',
                            'No visible catalog content.',
                          ),
                        )
                      else if (institutions.isEmpty && projects.isEmpty)
                        Text(context.localized(
                            '没有匹配结果', 'No matching results.')),
                      for (final item in institutions)
                        _itemTile(item, () => _openInstitution(item)),
                      for (final item in projects)
                        _itemTile(item, () => _openItem(item)),
                    ],
                  ),
                ),
    );
  }

  Widget _itemTile(DiscoverItem item, VoidCallback open) => Card(
        child: ListTile(
          key: Key('catalog-${item.type.name}-${item.id}'),
          title: Text(item.title),
          subtitle: item.subtitle.isEmpty ? null : Text(item.subtitle),
          trailing: const Icon(Icons.chevron_right),
          onTap: open,
        ),
      );

  Future<void> _openInstitution(DiscoverItem institution) async {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => _InstitutionCatalogPage(
          repository: widget.repository,
          institution: institution,
        ),
      ),
    );
  }

  void _openItem(DiscoverItem item) => Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => Scaffold(
            appBar: AppBar(title: Text(item.title)),
            body: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(item.subtitle.isEmpty ? item.title : item.subtitle),
            ),
          ),
        ),
      );
}

class _InstitutionCatalogPage extends StatefulWidget {
  const _InstitutionCatalogPage({
    required this.repository,
    required this.institution,
  });
  final ProfessionalCatalogRepository repository;
  final DiscoverItem institution;
  @override
  State<_InstitutionCatalogPage> createState() =>
      _InstitutionCatalogPageState();
}

class _InstitutionCatalogPageState extends State<_InstitutionCatalogPage> {
  List<DiscoverItem> _doctors = const [];
  bool _loading = true;
  String? _error;
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
      await widget.repository.loadVisibleInstitution(widget.institution.id);
      final doctors = await widget.repository.loadVisibleInstitutionDoctors(
        widget.institution.id,
      );
      if (mounted) {
        setState(() {
          _doctors = doctors;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = context.localized(
            '内容暂不可用，请重试',
            'Content unavailable. Please retry.',
          );
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(widget.institution.title)),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(_error!),
                        TextButton(
                          onPressed: _load,
                          child: Text(context.localized('重试', 'Retry')),
                        ),
                      ],
                    ),
                  )
                : ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      if (_doctors.isEmpty)
                        Text(
                            context.localized('暂无可见医生', 'No visible doctors.')),
                      for (final doctor in _doctors)
                        Card(
                          child: ListTile(
                            title: Text(doctor.title),
                            subtitle: Text(doctor.subtitle),
                            trailing: const Icon(Icons.chevron_right),
                            onTap: () => Navigator.of(context).push(
                              MaterialPageRoute(
                                builder: (_) => _DoctorProjectsPage(
                                  repository: widget.repository,
                                  institutionId: widget.institution.id,
                                  doctor: doctor,
                                ),
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
      );
}

class _DoctorProjectsPage extends StatefulWidget {
  const _DoctorProjectsPage({
    required this.repository,
    required this.institutionId,
    required this.doctor,
  });
  final ProfessionalCatalogRepository repository;
  final String institutionId;
  final DiscoverItem doctor;
  @override
  State<_DoctorProjectsPage> createState() => _DoctorProjectsPageState();
}

class _DoctorProjectsPageState extends State<_DoctorProjectsPage> {
  List<DiscoverItem>? _items;
  String? _error;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final items = await widget.repository.loadVisibleDoctorProjects(
        widget.institutionId,
        widget.doctor.id,
      );
      if (mounted) {
        setState(() => _items = items);
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = context.localized(
            '内容暂不可用，请重试',
            'Content unavailable. Please retry.',
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(widget.doctor.title)),
        body: _items == null && _error == null
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(_error!),
                        TextButton(
                          onPressed: _load,
                          child: Text(context.localized('重试', 'Retry')),
                        ),
                      ],
                    ),
                  )
                : ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      if (_items!.isEmpty)
                        Text(context.localized(
                            '暂无可见项目', 'No visible projects.')),
                      for (final item in _items!)
                        Card(
                          child: ListTile(
                            title: Text(item.title),
                            subtitle: Text(item.subtitle),
                          ),
                        ),
                    ],
                  ),
      );
}
