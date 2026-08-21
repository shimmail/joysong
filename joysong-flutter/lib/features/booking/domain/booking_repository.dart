import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

abstract interface class BookingRepository {
  Future<List<InstitutionProject>> getInstitutionProjects(String institutionId);

  Future<InstitutionProject> getInstitutionProject(
    String institutionId,
    String projectId,
  );

  Future<List<BookingDoctor>> getDoctors(String institutionProjectId);

  Future<List<BookingConsultant>> getConsultants(String institutionId);

  Future<TravelGroundServiceQuote> getTravelGroundServiceQuote({
    required String doctorId,
    required String institutionProjectId,
  });

  Future<Order> createOrder(CreateOrderCommand command);
}
