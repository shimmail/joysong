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
  List<UserCoupon> _coupons = const [];
  BookingDoctor? _selectedDoctor;
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
  List<UserCoupon> get coupons => _coupons;
  BookingDoctor? get selectedDoctor => _selectedDoctor;
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
      final results = await Future.wait<Object>([
        _repository.getDoctors(detail.id),
        _repository.getAvailableCoupons(),
      ]);
      _project = detail;
      _doctors = results[0] as List<BookingDoctor>;
      _coupons = results[1] as List<UserCoupon>;
      _hasLoaded = true;
    } catch (error) {
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
    final doctor = _selectedDoctor;
    final time = _appointmentTime;
    if (project == null) return _fail('预约项目尚未加载');
    if (doctor == null) return _fail('请选择医生');
    if (time == null) return _fail('请选择预约时间');
    if (!time.isAfter(DateTime.now())) return _fail('预约时间必须晚于当前时间');
    if (_remark.length > 500) return _fail('订单备注不能超过 500 字');

    _isSubmitting = true;
    _errorMessage = null;
    notifyListeners();
    try {
      return await _repository.createOrder(
        CreateOrderCommand(
          projectId: project.projectId,
          institutionProjectId: project.id,
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
