import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

abstract interface class BookingRepository {
  Future<List<InstitutionProject>> getInstitutionProjects(String institutionId);

  Future<InstitutionProject> getInstitutionProject(
    String institutionId,
    String projectId,
  );

  Future<List<BookingDoctor>> getDoctors(String institutionProjectId);

  Future<Money> getConsultationFee({
    required String doctorId,
    required String institutionProjectId,
  });

  Future<List<UserCoupon>> getAvailableCoupons();

  Future<DiscountQuote> calculateDiscount({
    required int couponId,
    required Money originalPrice,
  });

  Future<Order> createOrder(CreateOrderCommand command);
}
