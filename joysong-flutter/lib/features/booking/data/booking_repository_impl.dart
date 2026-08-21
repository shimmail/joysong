import 'package:joysong_flutter/features/booking/data/booking_remote_data_source.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/booking/domain/booking_repository.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

final class BookingRepositoryImpl implements BookingRepository {
  const BookingRepositoryImpl(this._remote);

  final BookingRemoteDataSource _remote;

  @override
  Future<List<InstitutionProject>> getInstitutionProjects(
    String institutionId,
  ) =>
      _remote.getInstitutionProjects(institutionId);

  @override
  Future<InstitutionProject> getInstitutionProject(
    String institutionId,
    String projectId,
  ) =>
      _remote.getInstitutionProject(institutionId, projectId);

  @override
  Future<List<BookingDoctor>> getDoctors(String institutionProjectId) =>
      _remote.getDoctors(institutionProjectId);

  @override
  Future<List<BookingConsultant>> getConsultants(String institutionId) =>
      _remote.getConsultants(institutionId);

  @override
  Future<TravelGroundServiceQuote> getTravelGroundServiceQuote({
    required String doctorId,
    required String institutionProjectId,
  }) =>
      _remote.getTravelGroundServiceQuote(
        doctorId: doctorId,
        institutionProjectId: institutionProjectId,
      );

  @override
  Future<Order> createOrder(CreateOrderCommand command) =>
      _remote.createOrder(command);
}
