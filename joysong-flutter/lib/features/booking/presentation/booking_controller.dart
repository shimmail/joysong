import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/booking/domain/booking_repository.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

final class BookingController extends ChangeNotifier {
  BookingController({
    required BookingRepository repository,
    required this.institutionId,
    required this.projectId,
  }) : _repository = repository;

  final BookingRepository _repository;
  final String institutionId;
  final String projectId;

  InstitutionProject? _project;
  List<BookingDoctor> _doctors = const [];
  List<BookingConsultant> _consultants = const [];
  List<UserCoupon> _coupons = const [];
  BookingDoctor? _selectedDoctor;
  BookingConsultant? _selectedConsultant;
  UserCoupon? _selectedCoupon;
  DiscountQuote? _discountQuote;
  Money _consultationFee = Money.zero;
  DateTime? _appointmentTime;
  String _remark = '';
  String? _errorMessage;
  bool _isLoading = false;
  bool _isFeeLoading = false;
  bool _isDiscountLoading = false;
  bool _isSubmitting = false;
  bool _hasLoaded = false;
  int _doctorRequestVersion = 0;
  int _couponRequestVersion = 0;

  InstitutionProject? get project => _project;
  List<BookingDoctor> get doctors => _doctors;
  List<BookingConsultant> get consultants => _consultants;
  List<UserCoupon> get coupons => _coupons;
  BookingDoctor? get selectedDoctor => _selectedDoctor;
  BookingConsultant? get selectedConsultant => _selectedConsultant;
  UserCoupon? get selectedCoupon => _selectedCoupon;
  DiscountQuote? get discountQuote => _discountQuote;
  Money get consultationFee => _consultationFee;
  DateTime? get appointmentTime => _appointmentTime;
  String get remark => _remark;
  String? get errorMessage => _errorMessage;
  bool get isLoading => _isLoading;
  bool get isFeeLoading => _isFeeLoading;
  bool get isDiscountLoading => _isDiscountLoading;
  bool get isSubmitting => _isSubmitting;
  bool get hasLoaded => _hasLoaded;

  Money get originalPrice => _project?.price ?? Money.zero;

  Money get payablePreview => originalPrice
      .subtract(_discountQuote?.discountAmount ?? Money.zero)
      .clampToZero();

  bool get canSubmit =>
      !_isSubmitting &&
      !_isLoading &&
      _project != null &&
      _selectedConsultant != null &&
      _selectedDoctor != null &&
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
      try {
        _coupons = await _repository.getAvailableCoupons();
      } on Object {
        // Coupons are optional and must not prevent a valid project with
        // bookable doctors from entering the booking flow.
        _coupons = const [];
      }
      _hasLoaded = true;
    } catch (error) {
      _project = null;
      _doctors = const [];
      _consultants = const [];
      _selectedDoctor = null;
      _selectedConsultant = null;
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
    _consultationFee = Money.zero;
    _errorMessage = null;
    final version = ++_doctorRequestVersion;
    if (doctor == null || _project == null) {
      _isFeeLoading = false;
      notifyListeners();
      return;
    }
    _isFeeLoading = true;
    notifyListeners();
    try {
      final fee = await _repository.getConsultationFee(
        doctorId: doctor.id,
        institutionProjectId: _project!.id,
      );
      if (version == _doctorRequestVersion) _consultationFee = fee;
    } catch (error) {
      if (version == _doctorRequestVersion) {
        _errorMessage = _messageFor(error, '面诊费加载失败');
      }
    } finally {
      if (version == _doctorRequestVersion) {
        _isFeeLoading = false;
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

  Future<void> selectCoupon(UserCoupon? coupon) async {
    _selectedCoupon = coupon;
    _discountQuote = null;
    _errorMessage = null;
    final version = ++_couponRequestVersion;
    if (coupon == null) {
      _isDiscountLoading = false;
      notifyListeners();
      return;
    }
    _isDiscountLoading = true;
    notifyListeners();
    try {
      final quote = await _repository.calculateDiscount(
        couponId: coupon.couponId,
        originalPrice: originalPrice,
      );
      if (version == _couponRequestVersion) _discountQuote = quote;
    } catch (error) {
      if (version == _couponRequestVersion) {
        _selectedCoupon = null;
        _errorMessage = _messageFor(error, '优惠券暂不可用');
      }
    } finally {
      if (version == _couponRequestVersion) {
        _isDiscountLoading = false;
        notifyListeners();
      }
    }
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
    if (consultant == null) return _fail('请选择机构咨询师');
    if (doctor == null) return _fail('请选择医生');
    if (time == null) return _fail('请选择预约时间');
    final beijingNow = DateTime.now().toUtc().add(const Duration(hours: 8));
    if (!time.isAfter(beijingNow)) {
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
          userCouponId: _selectedCoupon?.id,
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
