import 'dart:async';

import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/booking/domain/booking_repository.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

import '../orders/order_test_fixtures.dart';

InstitutionProject sampleInstitutionProject() => InstitutionProject(
      id: 'ip-1',
      projectId: 'project-1',
      institutionId: 'institution-1',
      name: '光子嫩肤',
      institutionName: '娇颜颂医疗美容',
      description: '舒缓护理',
      coverImage: '',
      price: Money.parse('1280.50'),
    );

BookingDoctor sampleDoctor() => const BookingDoctor(
      id: 'doctor-1',
      name: '张医生',
      title: '主任医师',
      avatar: '',
      specialties: '皮肤美容',
      isVerified: true,
    );

BookingConsultant sampleConsultant() => const BookingConsultant(
      id: 'consultant-1',
      name: '李咨询师',
    );

TravelGroundServiceQuote sampleTravelGroundServiceQuote() =>
    const TravelGroundServiceQuote(
      currency: 'USD',
      medicalListPriceMinor: 100000,
      platformServiceRateBps: 4000,
      travelGroundServiceFeeMinor: 40000,
    );

class FakeBookingRepository implements BookingRepository {
  int createCalls = 0;
  CreateOrderCommand? lastCommand;
  Completer<Order>? createCompleter;
  List<BookingConsultant> consultantResults = [sampleConsultant()];
  String? quoteDoctorId;
  String? quoteInstitutionProjectId;
  Completer<TravelGroundServiceQuote>? quoteCompleter;
  Object? quoteError;

  @override
  Future<List<InstitutionProject>> getInstitutionProjects(
    String institutionId,
  ) async =>
      [sampleInstitutionProject()];

  @override
  Future<InstitutionProject> getInstitutionProject(
    String institutionId,
    String projectId,
  ) async =>
      sampleInstitutionProject();

  @override
  Future<List<BookingDoctor>> getDoctors(String institutionProjectId) async =>
      [sampleDoctor()];

  @override
  Future<List<BookingConsultant>> getConsultants(String institutionId) async =>
      consultantResults;

  @override
  Future<TravelGroundServiceQuote> getTravelGroundServiceQuote({
    required String doctorId,
    required String institutionProjectId,
  }) {
    quoteDoctorId = doctorId;
    quoteInstitutionProjectId = institutionProjectId;
    final error = quoteError;
    if (error != null) return Future.error(error);
    final completer = quoteCompleter;
    if (completer != null) return completer.future;
    return Future.value(sampleTravelGroundServiceQuote());
  }

  @override
  Future<Order> createOrder(CreateOrderCommand command) {
    createCalls += 1;
    lastCommand = command;
    return createCompleter?.future ?? Future.value(sampleOrder());
  }
}
