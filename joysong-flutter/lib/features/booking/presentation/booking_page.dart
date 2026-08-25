import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/translation/auto_translation_builder.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_controller.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

typedef BookingCompletedCallback = void Function(Order order);

class BookingPage extends StatefulWidget {
  const BookingPage({
    required this.controller,
    required this.onOrderCreated,
    this.enableAutoTranslation = false,
    super.key,
  });

  final BookingController controller;
  final BookingCompletedCallback onOrderCreated;
  final bool enableAutoTranslation;

  @override
  State<BookingPage> createState() => _BookingPageState();
}

class _BookingPageState extends State<BookingPage> {
  final _remarkController = TextEditingController();

  @override
  void initState() {
    super.initState();
    widget.controller.load();
  }

  @override
  void dispose() {
    _remarkController.dispose();
    super.dispose();
  }

  Future<void> _pickAppointment() async {
    final now = widget.controller.currentBeijingWallClock;
    final currentSelection = widget.controller.appointmentTime;
    final fallbackTime = now.add(const Duration(hours: 1));
    final initial = currentSelection != null && currentSelection.isAfter(now)
        ? currentSelection
        : fallbackTime;
    final today = DateUtils.dateOnly(now);
    final lastDate = DateUtils.dateOnly(now.add(const Duration(days: 365)));
    final initialDate = DateUtils.dateOnly(initial).isAfter(lastDate)
        ? lastDate
        : DateUtils.dateOnly(initial).isBefore(today)
            ? today
            : DateUtils.dateOnly(initial);
    final date = await showDatePicker(
      context: context,
      initialDate: initialDate,
      firstDate: today,
      lastDate: lastDate,
      currentDate: today,
      initialEntryMode: DatePickerEntryMode.calendarOnly,
      helpText: context.localized('选择预约日期（北京时间）', 'Select date (Beijing time)'),
      cancelText: context.localized('取消', 'Cancel'),
      confirmText: context.localized('下一步', 'Next'),
    );
    if (date == null || !mounted) return;
    final time = await _showScrollingTimePicker(
      context,
      initial: TimeOfDay.fromDateTime(
        currentSelection != null && DateUtils.isSameDay(currentSelection, date)
            ? currentSelection
            : initial,
      ),
    );
    if (time == null || !mounted) return;
    final appointment =
        DateTime(date.year, date.month, date.day, time.hour, time.minute);
    if (!appointment.isAfter(widget.controller.currentBeijingWallClock)) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(context.localized(
            '预约时间必须晚于当前北京时间',
            'Appointment time must be later than Beijing time',
          )),
        ),
      );
      return;
    }
    widget.controller.selectAppointmentTime(appointment);
  }

  Future<void> _submit() async {
    final order = await widget.controller.submit();
    if (order != null && mounted) widget.onOrderCreated(order);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final controller = widget.controller;
        return Scaffold(
          appBar: AppBar(title: Text(context.localized('确认预约', 'Confirm booking'))),
          bottomNavigationBar: controller.project == null
              ? null
              : SafeArea(
                  minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                  child: FilledButton(
                    key: const Key('booking-submit-button'),
                    onPressed: controller.canSubmit ? _submit : null,
                    child: controller.isSubmitting
                        ? const SizedBox.square(
                            dimension: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : Text(context.localized('提交预约', 'Submit booking')),
                  ),
                ),
          body: _buildBody(controller),
        );
      },
    );
  }

  Widget _buildBody(BookingController controller) {
    if (controller.isLoading && controller.project == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (controller.project == null) {
      return _MessageState(
        message: controller.errorMessage ??
            context.localized('暂无可预约项目信息', 'No booking information available'),
        onRetry: () => controller.load(force: true),
      );
    }

    final project = controller.project!;
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
      children: [
        if (controller.errorMessage != null)
          _InlineMessage(message: controller.errorMessage!),
        if (controller.canRetryTravelGroundServiceQuote)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              key: const Key('booking-quote-retry'),
              onPressed: controller.retryTravelGroundServiceQuote,
              icon: const Icon(Icons.refresh),
              label: Text(
                context.localized('重新获取服务费', 'Retry service fee quote'),
              ),
            ),
          ),
        _ProjectCard(
          project: project,
          enableAutoTranslation: widget.enableAutoTranslation,
        ),
        const SizedBox(height: 20),
        _SectionTitle(
          context.localized(
            '选择医美顾问',
            'Select a medical aesthetics consultant',
          ),
        ),
        const SizedBox(height: 8),
        if (controller.consultants.isEmpty)
          _SoftPanel(
            child: Text(
              context.localized(
                '该机构暂时没有可预约的医美顾问',
                'No medical aesthetics consultants are currently available for this institution',
              ),
            ),
          )
        else
          DropdownButtonFormField<BookingConsultant>(
            key: const Key('booking-consultant-select'),
            initialValue: controller.selectedConsultant,
            isExpanded: true,
            borderRadius: BorderRadius.circular(12),
            menuMaxHeight: 320,
            dropdownColor: Theme.of(context).colorScheme.surface,
            decoration: InputDecoration(
              prefixIcon: const Icon(Icons.support_agent_outlined),
              hintText: context.localized(
                '请选择医美顾问',
                'Select a medical aesthetics consultant',
              ),
            ),
            items: controller.consultants
                .map(
                  (consultant) => DropdownMenuItem(
                    value: consultant,
                    child: Text(
                      consultant.name,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                )
                .toList(),
            onChanged: controller.isSubmitting
                ? null
                : (consultant) => controller.selectConsultant(consultant),
          ),
        const SizedBox(height: 20),
        _SectionTitle(context.localized('选择医生', 'Select a doctor')),
        const SizedBox(height: 8),
        if (controller.doctors.isEmpty)
          _SoftPanel(child: Text(context.localized('该项目暂时没有可预约医生', 'No doctors are currently available for this service')))
        else
          DropdownButtonFormField<BookingDoctor>(
            key: ValueKey(controller.selectedDoctor?.id),
            initialValue: controller.selectedDoctor,
            isExpanded: true,
            borderRadius: BorderRadius.circular(12),
            menuMaxHeight: 320,
            dropdownColor: Theme.of(context).colorScheme.surface,
            decoration: InputDecoration(
              prefixIcon: const Icon(Icons.medical_services_outlined),
              hintText: context.localized('请选择医生', 'Select a doctor'),
            ),
            items: _doctorItems(controller.doctors),
            onChanged: controller.isSubmitting
                ? null
                : (doctor) => controller.selectDoctor(doctor),
          ),
        const SizedBox(height: 20),
        _SectionTitle(
          context.localized('预约时间（北京时间 UTC+8）', 'Appointment time (Beijing UTC+8)'),
        ),
        const SizedBox(height: 8),
        _SoftPanel(
          child: ListTile(
            key: const Key('booking-appointment-tile'),
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.calendar_month_outlined),
            title: Text(
              controller.appointmentTime == null
                  ? context.localized('请选择到店时间', 'Select an arrival time')
                  : _formatDateTime(controller.appointmentTime!),
            ),
            trailing: const Icon(Icons.chevron_right_rounded),
            onTap: controller.isSubmitting ? null : _pickAppointment,
          ),
        ),
        const SizedBox(height: 20),
        _SectionTitle(context.localized('备注', 'Notes')),
        const SizedBox(height: 8),
        TextField(
          key: const Key('booking-remark-field'),
          controller: _remarkController,
          enabled: !controller.isSubmitting,
          maxLength: 500,
          maxLines: 3,
          decoration: InputDecoration(
            hintText: context.localized('可填写需要提前沟通的事项', 'Add anything you would like to discuss in advance'),
          ),
          onChanged: controller.updateRemark,
        ),
        const SizedBox(height: 12),
        _PriceSummary(controller: controller),
        const SizedBox(height: 10),
        Text(
          key: const Key('booking-price-disclaimer'),
          context.localized(
            '医疗费到院后直接向医院支付',
            'Pay medical fees directly to the hospital after arrival.',
          ),
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
        ),
      ],
    );
  }

  List<DropdownMenuItem<BookingDoctor>> _doctorItems(
    List<BookingDoctor> doctors,
  ) {
    final doctorIdCounts = <String, int>{};
    for (final doctor in doctors) {
      final doctorId = doctor.id.trim();
      if (doctorId.isNotEmpty) {
        doctorIdCounts.update(doctorId, (count) => count + 1, ifAbsent: () => 1);
      }
    }
    return doctors.map((doctor) {
      final doctorId = doctor.id.trim();
      final doctorIdentityStable =
          doctorId.isNotEmpty && doctorIdCounts[doctorId] == 1;
      return DropdownMenuItem(
        key: doctorIdentityStable
            ? ValueKey('booking-doctor:$doctorId')
            : ObjectKey(doctor),
        value: doctor,
        child: Row(
          children: [
            _DoctorAvatar(doctor: doctor, radius: 18),
            const SizedBox(width: 10),
            Expanded(
              child: StableAutoTranslationBuilder(
                enabled: widget.enableAutoTranslation && doctorIdentityStable,
                contentType: 'doctor',
                contentId: 'doctor:$doctorId',
                field: 'title',
                sourceText: doctor.title,
                builder: (context, visibleTitle) => Text(
                  '${doctor.name}${visibleTitle.isEmpty ? '' : ' · $visibleTitle'}',
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ),
          ],
        ),
      );
    }).toList();
  }
}

class _DoctorAvatar extends StatelessWidget {
  const _DoctorAvatar({required this.doctor, this.radius = 20});

  final BookingDoctor doctor;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final avatar = doctor.avatar.trim();
    return CircleAvatar(
      radius: radius,
      foregroundImage: avatar.isEmpty ? null : NetworkImage(avatar),
      onForegroundImageError: avatar.isEmpty ? null : (_, __) {},
      child: avatar.isEmpty
          ? const Icon(Icons.person_outline_rounded)
          : null,
    );
  }
}

Future<TimeOfDay?> _showScrollingTimePicker(
  BuildContext context, {
  required TimeOfDay initial,
}) {
  return showModalBottomSheet<TimeOfDay>(
    context: context,
    showDragHandle: true,
    builder: (_) => _ScrollingTimePicker(initial: initial),
  );
}

class _ScrollingTimePicker extends StatefulWidget {
  const _ScrollingTimePicker({required this.initial});

  final TimeOfDay initial;

  @override
  State<_ScrollingTimePicker> createState() => _ScrollingTimePickerState();
}

class _ScrollingTimePickerState extends State<_ScrollingTimePicker> {
  // A multiple of 2, 12 and 60 keeps every looping column aligned.
  static const _baseIndex = 1200;
  late int _period;
  late int _hour;
  late int _minute;
  late final FixedExtentScrollController _periodController;
  late final FixedExtentScrollController _hourController;
  late final FixedExtentScrollController _minuteController;

  @override
  void initState() {
    super.initState();
    _period = widget.initial.period == DayPeriod.am ? 0 : 1;
    _hour = widget.initial.hourOfPeriod == 0
        ? 12
        : widget.initial.hourOfPeriod;
    _minute = widget.initial.minute;
    _periodController = FixedExtentScrollController(initialItem: _period);
    _hourController = FixedExtentScrollController(initialItem: _baseIndex + _hour - 1);
    _minuteController = FixedExtentScrollController(initialItem: _baseIndex + _minute);
  }

  @override
  void dispose() {
    _periodController.dispose();
    _hourController.dispose();
    _minuteController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final english = context.isEnglish;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(context.localized('选择预约时间（北京时间 UTC+8）', 'Select time (Beijing UTC+8)'),
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            SizedBox(
              height: 220,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  _wheel(
                    _periodController,
                    2,
                    (value) => _period = value,
                    (value) => value == 0
                        ? (english ? 'AM' : '上午')
                        : (english ? 'PM' : '下午'),
                    looping: false,
                  ),
                  _wheel(_hourController, 12, (value) => _hour = value % 12 + 1,
                      (value) => '${value % 12 + 1}'),
                  _wheel(_minuteController, 60, (value) => _minute = value % 60,
                      (value) => (value % 60).toString().padLeft(2, '0')),
                ],
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(onPressed: () => Navigator.pop(context), child: Text(context.localized('取消', 'Cancel'))),
                FilledButton(onPressed: () => Navigator.pop(context, TimeOfDay(hour: (_hour % 12) + (_period == 1 ? 12 : 0), minute: _minute)), child: Text(context.localized('确定', 'Confirm'))),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _wheel(
    FixedExtentScrollController controller,
    int count,
    ValueChanged<int> onChanged,
    String Function(int) label, {
    bool looping = true,
  }) {
    return SizedBox(
      width: 82,
      child: ListWheelScrollView.useDelegate(
        controller: controller,
        itemExtent: 48,
        diameterRatio: 1.7,
        useMagnifier: true,
        magnification: 1.12,
        overAndUnderCenterOpacity: 0.45,
        physics: const FixedExtentScrollPhysics(),
        onSelectedItemChanged: onChanged,
        childDelegate: looping
            ? ListWheelChildLoopingListDelegate(
                children: [
                  for (var i = 0; i < count; i++)
                    Center(
                      child: Text(label(i),
                          style: Theme.of(context).textTheme.titleMedium),
                    ),
                ],
              )
            : ListWheelChildListDelegate(
                children: [
                  for (var i = 0; i < count; i++)
                    Center(
                      child: Text(label(i),
                          style: Theme.of(context).textTheme.titleMedium),
                    ),
                ],
              ),
      ),
    );
  }
}

class _ProjectCard extends StatelessWidget {
  const _ProjectCard({
    required this.project,
    this.enableAutoTranslation = false,
  });

  final InstitutionProject project;
  final bool enableAutoTranslation;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: SizedBox.square(
                dimension: 76,
                child: project.coverImage.isEmpty
                    ? ColoredBox(
                        color: Theme.of(context).colorScheme.surfaceContainer,
                        child: const Icon(Icons.spa_outlined),
                      )
                    : Image.network(
                        project.coverImage,
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) =>
                            const Icon(Icons.spa_outlined),
                      ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  StableAutoTranslatedText(
                    enabled:
                        enableAutoTranslation && project.id.trim().isNotEmpty,
                    contentType: 'project',
                    contentId: 'institution-project:${project.id.trim()}',
                    field: 'name',
                    sourceText: project.name,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  if (project.institutionName.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    StableAutoTranslatedText(
                      enabled: enableAutoTranslation &&
                          project.institutionId.trim().isNotEmpty,
                      contentType: 'institution',
                      contentId: 'institution:${project.institutionId.trim()}',
                      field: 'name',
                      sourceText: project.institutionName,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PriceSummary extends StatelessWidget {
  const _PriceSummary({required this.controller});

  final BookingController controller;

  @override
  Widget build(BuildContext context) {
    final quote = controller.travelGroundServiceQuote;
    return _SoftPanel(
      child: Column(
        children: [
          if (quote != null) ...[
            _PriceLine(
              label: context.localized(
                '医疗套餐优惠前金额（参考）',
                'Medical package list price (reference)',
              ),
              value: quote.medicalListPriceFormatted,
            ),
            const SizedBox(height: 8),
          ],
          _PriceLine(
            label: context.localized(
              '旅游地接服务费',
              'Travel ground service fee',
            ),
            value: controller.isQuoteLoading
                ? context.localized('加载中…', 'Loading…')
                : quote?.travelGroundServiceFeeFormatted ?? '--',
            emphasized: true,
          ),
        ],
      ),
    );
  }
}

class _PriceLine extends StatelessWidget {
  const _PriceLine({
    required this.label,
    required this.value,
    this.emphasized = false,
  });

  final String label;
  final String value;
  final bool emphasized;

  @override
  Widget build(BuildContext context) {
    final style = emphasized
        ? Theme.of(context).textTheme.titleMedium
        : Theme.of(context).textTheme.bodyMedium;
    return Row(
      children: [
        Expanded(child: Text(label, style: style)),
        Text(value, style: style),
      ],
    );
  }
}

class _SoftPanel extends StatelessWidget {
  const _SoftPanel({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => Material(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: child,
        ),
      );
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) =>
      Text(text, style: Theme.of(context).textTheme.titleMedium);
}

class _InlineMessage extends StatelessWidget {
  const _InlineMessage({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Text(
          message,
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
      );
}

class _MessageState extends StatelessWidget {
  const _MessageState({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(message, textAlign: TextAlign.center),
              const SizedBox(height: 12),
          TextButton(onPressed: onRetry, child: Text(context.localized('重试', 'Retry'))),
            ],
          ),
        ),
      );
}

String _formatDateTime(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:'
    '${value.minute.toString().padLeft(2, '0')}';
