import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

final class InstitutionProjectLegacyReviewPreview {
  const InstitutionProjectLegacyReviewPreview({
    required this.model,
    required this.scheduleNote,
  });

  final InstitutionProjectPreviewModel model;
  final String scheduleNote;
}

abstract final class InstitutionProjectPreviewAdapters {
  static InstitutionProjectPreviewModel fromV2(
      DoctorProjectChangeRequest request) {
    if (request.payloadVersion != 2 || request.proposedProject == null) {
      throw ArgumentError.value(request, 'request', '需要有效的 v2 项目快照');
    }
    final project = request.proposedProject!;
    return InstitutionProjectPreviewModel(
      name: project.name,
      institutionName: request.institutionName,
      price: request.proposedDoctorPrice!.toDouble(),
      currency: 'USD',
      salesCount: project.salesCount,
      tags: project.tags,
      slogan: _blankToNull(project.slogan),
      description: _blankToNull(project.description),
      detailContent: project.detailContent,
      coverImage: _blankToNull(project.coverImage),
      images: institutionProjectPreviewImages(
        coverImage: project.coverImage,
        gallery: project.images,
      ),
    );
  }

  static InstitutionProjectPreviewModel fromCreation(
    InstitutionProjectRequestDraft request, {
    required String institutionName,
  }) =>
      InstitutionProjectPreviewModel(
        name: _blankToNull(request.name) ?? request.projectId,
        institutionName: institutionName,
        price: request.price.toDouble(),
        currency: request.currency.trim().toUpperCase(),
        salesCount: request.salesCount,
        tags: request.tags ?? const [],
        slogan: _blankToNull(request.slogan),
        description: _blankToNull(request.description),
        detailContent: _blankToNull(request.detailContent),
        coverImage: _blankToNull(request.coverImage),
        images: institutionProjectPreviewImages(
          coverImage: request.coverImage,
          gallery: request.images ?? const [],
        ),
      );

  static InstitutionProjectLegacyReviewPreview fromV1(
    DoctorProjectChangeRequest request,
  ) =>
      InstitutionProjectLegacyReviewPreview(
        model: InstitutionProjectPreviewModel(
          name: request.projectName,
          institutionName: request.institutionName,
          price: request.priceSuggestion?.toDouble() ?? 0,
          currency: 'USD',
          salesCount: 0,
          tags: request.serviceTags,
          slogan: null,
          description: _blankToNull(request.serviceDescription),
          detailContent: null,
          coverImage: _blankToNull(request.coverImage),
          images: institutionProjectPreviewImages(
            coverImage: request.coverImage,
            gallery: request.images,
          ),
        ),
        scheduleNote: request.scheduleNote,
      );
}

String? _blankToNull(String? value) {
  final text = value?.trim() ?? '';
  return text.isEmpty ? null : text;
}
