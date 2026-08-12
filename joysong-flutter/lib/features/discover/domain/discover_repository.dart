import 'package:joysong_flutter/features/discover/domain/discover_models.dart';

abstract interface class DiscoverRepository {
  Future<DiscoverFilterOptions> loadFilterOptions();

  Future<DiscoverPageResult> loadPage({
    required DiscoverContentType type,
    required int offset,
    required int limit,
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
  });

  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  });
}

abstract interface class InstitutionProjectDetailRepository {
  Future<DiscoverItem> loadInstitutionProjectDetail({
    required String institutionId,
    required String projectId,
  });
}

abstract interface class ProfessionalCatalogRepository {
  Future<List<DiscoverItem>> loadVisibleInstitutions();
  Future<DiscoverItem> loadVisibleInstitution(String id);
  Future<List<DiscoverItem>> loadVisibleInstitutionDoctors(String id);
  Future<List<DiscoverItem>> loadVisibleDoctorProjects(
    String institutionId,
    String doctorId,
  );
  Future<List<DiscoverItem>> loadVisibleInstitutionProjects();
  Future<List<DiscoverItem>> loadVisibleProjects();
}
