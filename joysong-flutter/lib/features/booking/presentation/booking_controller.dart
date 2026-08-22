import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/booking/domain/booking_repository.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

DateTime beijingWallClockNow([DateTime? instant]) {
  final beijingInstant =
      (instant ?? DateTime.now()).toUtc().add(const Duration(hours: 8));
  return DateTime(
    beijingInstant.year,
    beijingInstant.month,
    beijingInstant.day,
    beijingInstant.hour,
    beijingInstant.minute,
    beijingInstant.second,
    beijingInstant.millisecond,
    beijingInstant.microsecond,
  );
}

final class BookingController extends ChangeNotifier {
  BookingController({
    required BookingRepository repository,
    required this.institutionId,
    required this.projectId,
    DateTime Function()? beijingClock,
  })  : _repository = repository,
        _beijingClock = beijingClock ?? beijingWallClockNow;

  final BookingRepository _repository;
  final DateTime Function() _beijingClock;
  final String institutionId;
  final String projectId;

  InstitutionProject? _project;
  List<BookingDoctor> _doctors = const [];
  List<BookingConsultant> _consultants = const [];
  BookingDoctor? _selectedDoctor;
  BookingConsultant? _selectedConsultant;
  TravelGroundServiceQuote? _travelGroundServiceQuote;
  DateTime? _appointmentTime;
  String _remark = '';
  String? _errorMessage;
  bool _isLoading = false;
  bool _isQuoteLoading = false;
  bool _isSubmitting = false;
  bool _hasLoaded = false;
  int _quoteRequestVersion = 0;

  InstitutionProject? get project => _project;
  List<BookingDoctor> get doctors => _doctors;
  List<BookingConsultant> get consultants => _consultants;
  BookingDoctor? get selectedDoctor => _selectedDoctor;
  BookingConsultant? get selectedConsultant => _selectedConsultant;
  TravelGroundServiceQuote? get travelGroundServiceQuote =>
      _travelGroundServiceQuote;
  DateTime? get appointmentTime => _appointmentTime;
  String get remark => _remark;
  String? get errorMessage => _errorMessage;
  bool get isLoading => _isLoading;
  bool get isQuoteLoading => _isQuoteLoading;
  bool get isSubmitting => _isSubmitting;
  bool get hasLoaded => _hasLoaded;
  DateTime get currentBeijingWallClock => _beijingClock();
  bool get canRetryTravelGroundServiceQuote =>
      !_isLoading &&
      !_isQuoteLoading &&
      !_isSubmitting &&
      _project != null &&
      _selectedDoctor != null &&
      _travelGroundServiceQuote == null;

  bool get canSubmit =>
      !_isSubmitting &&
      !_isLoading &&
      !_isQuoteLoading &&
      _project != null &&
      _selectedConsultant != null &&
      _selectedDoctor != null &&
      _travelGroundServiceQuote != null &&
      _appointmentTime != null;

  Future<void> load({bool force = false}) async {
    if (_isLoading || (_hasLoaded && !force)) return;
    if (institutionId.trim().isEmpty || projectId.trim().isEmpty) {
      _errorMessage = '预约参数不完整';
      _hasLoaded = true;
      notifyListeners();
      return;
    }
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final detail = await _repository.getInstitutionProject(
        institutionId,
        projectId,
      );
      _project = detail;
      _doctors = await _repository.getDoctors(detail.id);
      _consultants = await _repository.getConsultants(institutionId);
      _hasLoaded = true;
    } catch (error) {
      _project = null;
      _doctors = const [];
      _consultants = const [];
      _selectedDoctor = null;
      _selectedConsultant = null;
      _travelGroundServiceQuote = null;
      _errorMessage = _messageFor(error, '预约信息加载失败');
      _hasLoaded = true;
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<void> selectDoctor(BookingDoctor? doctor) async {
    if (_selectedDoctor?.id == doctor?.id) return;
    _selectedDoctor = doctor;
    _travelGroundServiceQuote = null;
    _errorMessage = null;
    if (doctor == null || _project == null) {
      ++_quoteRequestVersion;
      _isQuoteLoading = false;
      notifyListeners();
      return;
    }
    await _loadTravelGroundServiceQuote(doctor);
  }

  Future<void> retryTravelGroundServiceQuote() async {
    final doctor = _selectedDoctor;
    if (!canRetryTravelGroundServiceQuote || doctor == null) return;
    _errorMessage = null;
    await _loadTravelGroundServiceQuote(doctor);
  }

  Future<void> _loadTravelGroundServiceQuote(BookingDoctor doctor) async {
    final version = ++_quoteRequestVersion;
    _isQuoteLoading = true;
    notifyListeners();
    try {
      final quote = await _repository.getTravelGroundServiceQuote(
        doctorId: doctor.id,
        institutionProjectId: _project!.id,
      );
      if (version == _quoteRequestVersion) {
        _travelGroundServiceQuote = quote;
      }
    } catch (error) {
      if (version == _quoteRequestVersion) {
        _errorMessage = _messageFor(error, '旅游地接服务费加载失败');
      }
    } finally {
      if (version == _quoteRequestVersion) {
        _isQuoteLoading = false;
        notifyListeners();
      }
    }
  }

  Future<void> selectConsultant(BookingConsultant? consultant) async {
    if (_selectedConsultant?.id == consultant?.id) return;
    _selectedConsultant = consultant;
    _errorMessage = null;
    notifyListeners();
  }

  void selectAppointmentTime(DateTime value) {
    _appointmentTime = value;
    _errorMessage = null;
    notifyListeners();
  }

  void updateRemark(String value) {
    _remark = value;
    if (_errorMessage != null) _errorMessage = null;
    notifyListeners();
  }

  Future<Order?> submit() async {
    if (_isSubmitting) return null;
    final project = _project;
    final consultant = _selectedConsultant;
    final doctor = _selectedDoctor;
    final time = _appointmentTime;
    if (project == null) return _fail('预约项目尚未加载');
    if (consultant == null) return _fail('请选择医美顾问');
    if (doctor == null) return _fail('请选择医生');
    if (_isQuoteLoading || _travelGroundServiceQuote == null) {
      return _fail('旅游地接服务费尚未加载');
    }
    if (time == null) return _fail('请选择预约时间');
    if (!time.isAfter(currentBeijingWallClock)) {
      return _fail('预约时间必须晚于当前北京时间');
    }
    if (_remark.length > 500) return _fail('订单备注不能超过 500 字');

    _isSubmitting = true;
    _errorMessage = null;
    notifyListeners();
    try {
      return await _repository.createOrder(
        CreateOrderCommand(
          projectId: project.projectId,
          institutionProjectId: project.id,
          consultantId: consultant.id,
          doctorId: doctor.id,
          appointmentTime: time,
          remark: _remark.trim(),
        ),
      );
    } catch (error) {
      _errorMessage = _messageFor(error, '创建订单失败');
      return null;
    } finally {
      _isSubmitting = false;
      notifyListeners();
    }
  }

  Order? _fail(String message) {
    _errorMessage = message;
    notifyListeners();
    return null;
  }
}

String _messageFor(Object error, String fallback) {
  if (error is ApiException && error.message.isNotEmpty) return error.message;
  if (error is FormatException && error.message.isNotEmpty) {
    return error.message;
  }
  return fallback;
}
