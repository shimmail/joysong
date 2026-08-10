import { beforeEach, describe, expect, it } from 'vitest';
import { getDefaultManagementPath, setAdminToken, type ManagementContext } from './api';

describe('professional role default routes', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  it('routes a legal representative to request review instead of direct institution project editing', () => {
    const context: ManagementContext = {
      userId: 'legal-1',
      platformRole: 'USER',
      activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
      managedInstitutionIds: ['institution-1'],
      visibleInstitutionIds: ['institution-1'],
      canManageDoctors: false,
      canManageInstitutions: true,
      canManageInstitutionProjects: true,
      canManageArticles: false,
      canManageSplitConfigs: false,
      canManageOrders: false,
      canApplyToInstitutions: false,
      canReviewInstitutionRequests: true,
      canSubmitPlatformProjectRequests: false,
      canSubmitInstitutionProjectRequests: false,
      canReviewInstitutionProjectRequests: true,
      canViewAffiliations: false,
    };
    setAdminToken('header.payload.signature', context);

    expect(getDefaultManagementPath()).toBe('/project-requests');
  });
});
