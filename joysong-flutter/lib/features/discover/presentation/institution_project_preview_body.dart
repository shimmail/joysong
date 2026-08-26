import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';

class InstitutionProjectPreviewBody extends StatelessWidget {
  const InstitutionProjectPreviewBody({
    required this.model,
    this.guideKey,
    this.priceLabel,
    this.detailContentReplacement,
    super.key,
  });

  final InstitutionProjectPreviewModel model;
  final Key? guideKey;
  final String? priceLabel;
  final Widget? detailContentReplacement;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          CatalogHero(
            images: model.images,
            title: model.name,
            eyebrow: model.slogan?.trim() ?? '',
            subtitle: model.institutionName,
          ),
          CatalogSection(
            key: guideKey,
            title: context.localized('项目百科', 'Project guide'),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  priceLabel ?? _money(model.price, model.currency),
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w800,
                      ),
                ),
                const SizedBox(height: 6),
                Text(context.localized(
                  '已售 ${model.salesCount}',
                  '${model.salesCount} sold',
                )),
                if (model.tags.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 7,
                    runSpacing: 7,
                    children: [
                      for (final tag in model.tags) Chip(label: Text(tag))
                    ],
                  ),
                ],
                if ((model.description ?? '').trim().isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text(model.description!.trim(),
                      style: const TextStyle(
                          height: 1.55, color: Color(0xff666666))),
                ],
                if (detailContentReplacement != null) ...[
                  const SizedBox(height: 12),
                  detailContentReplacement!,
                ] else if ((model.detailContent ?? '').trim().isNotEmpty) ...[
                  const SizedBox(height: 12),
                  RichContentView(
                    content: model.detailContent!.trim(),
                    textStyle: const TextStyle(height: 1.65),
                  ),
                ],
              ],
            ),
          ),
        ],
      );
}

String _money(double value, String currency) {
  final symbol = currency.trim().toUpperCase() == 'CNY' ? '¥' : r'$';
  return '$symbol${value.toStringAsFixed(2)}';
}
