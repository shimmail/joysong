import { describe, expect, it } from 'vitest';
import golden from '../../../test-fixtures/doctor-project-change-v2.json';
import {
  adaptCreationRequestPreview,
  adaptLegacyProjectRequestPreview,
  adaptV2ProposedProjectPreview,
  isCompleteProfessionalProjectRequest,
  parseDoctorProjectChangeRequest,
  parseDoctorProjectChangeTarget,
  parseDoctorProjectChangeRequests,
  type ProfessionalProjectRequestResponse,
} from './projectRequests';

const creationRequest: ProfessionalProjectRequestResponse = {
  id: 'creation-1', requestType: 'INSTITUTION', doctorId: 'doctor-1', doctorName: 'Doctor One',
  institutionId: 'inst-1', institutionName: 'Joysong Clinic', projectId: 'pp-1', projectName: 'Face Lift',
  name: 'Clinic Face Lift', category: 'Surgery', description: 'Creation description', tags: ['clinic'],
  slogan: 'Natural result', detailContent: '<p>Safe detail</p>', currency: 'CNY',
  coverImage: 'creation-cover.jpg', images: ['creation-1.jpg'], salesCount: 0,
  referencePrice: null, categoryTags: null, price: 900, originalPrice: 1200, isActive: true,
  institutionSplit: { consultationFee: 20, commissionRate: 10, institutionRate: 20, platformRate: 40, doctorRate: 30 },
  notes: null, status: 'PENDING', reviewNote: null, reviewedBy: null, reviewedAt: null,
  resultingProjectId: null, resultingInstitutionProjectId: null,
  submittedAt: '2026-08-24T01:02:03', updatedAt: '2026-08-24T01:02:03',
};

describe('doctor project request wire parsing', () => {
  it('parses the golden v2 edit target with its exact nested snapshot types', () => {
    const target = parseDoctorProjectChangeTarget(golden.targetV2);

    expect(target).not.toBeNull();
    expect(target?.payloadVersion).toBe(2);
    expect(target?.currentProject.association).toEqual({
      institutionProjectId: 'ip-1', institutionId: 'inst-1', platformProjectId: 'pp-1',
    });
    expect(target?.currentProject.rawOverrides.description).toBeNull();
    expect(target?.currentProject.effective).toEqual(expect.objectContaining({
      name: 'Local Face Lift', salesCount: 12, images: ['https://cdn.example/local.jpg'],
    }));
    expect(target?.currentProject.source).toEqual({
      institutionProjectVersion: 7,
      platformInheritanceHash: 'f4168ca8ed8e9d41b62aef5f215a78cd2c018e1a3e8b7384f4311216fdeaea81',
    });
  });

  it('dispatches the golden flat v1 row to the legacy parser and keeps its schedule', () => {
    const request = parseDoctorProjectChangeRequest(golden.requestV1);

    expect(request.kind).toBe('V1');
    expect(request.reviewable).toBe(true);
    expect(request.parseIssue).toBeNull();
    if (request.kind !== 'V1') throw new Error('expected v1 request');
    expect(request.scheduleNote).toBe('Weekdays');
    expect(request.currentScheduleNote).toBe('Current weekdays');
  });

  it('dispatches the golden v2 row and exposes the proposed state without a legacy schedule', () => {
    const request = parseDoctorProjectChangeRequest(golden.requestV2);

    expect(request.kind).toBe('V2');
    expect(request.reviewable).toBe(true);
    expect(request.parseIssue).toBeNull();
    if (request.kind !== 'V2') throw new Error('expected v2 request');
    expect(request.proposedProject?.effective.name).toBe('Updated Face Lift');
    expect(request.proposedDoctorPrice).toBe(1100);
    expect(request).not.toHaveProperty('scheduleNote');
  });

  it('keeps the golden damaged snapshot visible but disables review with its parse issue', () => {
    const rows = parseDoctorProjectChangeRequests([golden.requestV2InvalidSnapshot]);

    expect(rows).toHaveLength(1);
    expect(rows[0]).toEqual(expect.objectContaining({
      id: 'request-v2-invalid', kind: 'V2', reviewable: false,
      parseIssue: 'REQUEST_SNAPSHOT_INVALID',
    }));
  });

  it('never falls a malformed or unknown v2 payload back to the v1 parser', () => {
    const malformedV2 = structuredClone(golden.requestV2) as Record<string, unknown>;
    const proposed = malformedV2.proposedProject as Record<string, unknown>;
    proposed.effective = { ...(proposed.effective as Record<string, unknown>), salesCount: '12' };

    expect(parseDoctorProjectChangeRequest(malformedV2)).toEqual(expect.objectContaining({
      kind: 'DAMAGED', payloadVersion: 2, id: 'request-v2', reviewable: false,
      parseIssue: 'MALFORMED_V2_PAYLOAD',
    }));
    expect(parseDoctorProjectChangeRequest({ ...golden.requestV1, payloadVersion: 3 })).toEqual(expect.objectContaining({
      kind: 'DAMAGED', payloadVersion: 3, id: 'request-v1', reviewable: false,
      parseIssue: 'UNSUPPORTED_PAYLOAD_VERSION',
    }));
  });
});

describe('institution project preview adapters', () => {
  it('keeps the creation request validator strict when moved out of the page', () => {
    expect(isCompleteProfessionalProjectRequest(creationRequest)).toBe(true);
    expect(isCompleteProfessionalProjectRequest({ ...creationRequest, price: '900' })).toBe(false);
  });

  it('adapts the v2 proposed state using USD and only the server-derived service fee', () => {
    const request = parseDoctorProjectChangeRequest(golden.requestV2);
    if (request.kind !== 'V2') throw new Error('expected v2 request');

    const preview = adaptV2ProposedProjectPreview(request);

    expect(preview).toEqual(expect.objectContaining({
      name: 'Updated Face Lift', tags: ['local', 'updated'], currency: 'USD',
      price: 1100, active: true, travelGroundServiceFee: 440,
    }));
    expect(preview).not.toHaveProperty('scheduleNote');
  });

  it('adapts legacy flat history with schedule only and never derives a service fee', () => {
    const request = parseDoctorProjectChangeRequest(golden.requestV1);
    if (request.kind !== 'V1') throw new Error('expected v1 request');

    expect(adaptLegacyProjectRequestPreview(request)).toEqual(expect.objectContaining({
      name: 'Face Lift', description: 'Legacy description', tags: ['legacy'],
      scheduleNote: 'Weekdays', currency: 'USD', price: 1000,
      travelGroundServiceFee: null,
    }));
  });

  it('adapts an institution creation request without inventing doctor-only values', () => {
    const preview = adaptCreationRequestPreview(creationRequest);

    expect(preview).toEqual(expect.objectContaining({
      name: 'Clinic Face Lift', currency: 'CNY', price: 900, active: true,
      travelGroundServiceFee: null,
    }));
    expect(preview).not.toHaveProperty('scheduleNote');
  });
});
