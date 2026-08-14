import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';

Future<BookingConsultant?> showInstitutionConsultantPicker({
  required BuildContext context,
  required Future<List<BookingConsultant>> Function() loadConsultants,
}) =>
    showModalBottomSheet<BookingConsultant>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (_) => _InstitutionConsultantPicker(
        loadConsultants: loadConsultants,
      ),
    );

class _InstitutionConsultantPicker extends StatefulWidget {
  const _InstitutionConsultantPicker({required this.loadConsultants});

  final Future<List<BookingConsultant>> Function() loadConsultants;

  @override
  State<_InstitutionConsultantPicker> createState() =>
      _InstitutionConsultantPickerState();
}

class _InstitutionConsultantPickerState
    extends State<_InstitutionConsultantPicker> {
  late Future<List<BookingConsultant>> _consultants;

  @override
  void initState() {
    super.initState();
    _consultants = widget.loadConsultants();
  }

  void _retry() {
    setState(() {
      _consultants = widget.loadConsultants();
    });
  }

  @override
  Widget build(BuildContext context) => FractionallySizedBox(
        heightFactor: .72,
        child: Column(
          children: [
            Text(
              context.localized('选择机构咨询师', 'Choose a consultant'),
              style: Theme.of(context).textTheme.titleLarge,
            ),
            Expanded(
              child: FutureBuilder<List<BookingConsultant>>(
                future: _consultants,
                builder: (context, snapshot) {
                  if (snapshot.connectionState != ConnectionState.done) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(context.localized(
                            '加载咨询师失败，请重试',
                            'Unable to load consultants. Try again.',
                          )),
                          const SizedBox(height: 12),
                          FilledButton(
                            onPressed: _retry,
                            child: Text(context.localized('重试', 'Retry')),
                          ),
                        ],
                      ),
                    );
                  }
                  final consultants = snapshot.data!;
                  if (consultants.isEmpty) {
                    return Center(
                      child: Text(context.localized(
                        '该机构暂无可咨询的咨询师',
                        'No consultants are currently available.',
                      )),
                    );
                  }
                  return ListView.builder(
                    key: const Key('institution-consultant-list'),
                    itemCount: consultants.length,
                    itemBuilder: (context, index) {
                      final consultant = consultants[index];
                      return ListTile(
                        key: ValueKey('institution-consultant-${consultant.id}'),
                        leading: const CircleAvatar(
                          child: Icon(Icons.support_agent_rounded),
                        ),
                        title: Text(consultant.name),
                        trailing: const Icon(Icons.chevron_right_rounded),
                        onTap: () => Navigator.of(context).pop(consultant),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      );
}
