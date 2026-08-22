import { cleanup, render, screen } from '@testing-library/react';
import { createElement } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { getDefaultManagementPath, setAdminToken, type ManagementContext } from './api';

vi.mock('./pages/ProjectCollaborationPage', () => ({
  default: () => '医生项目协作页面',
}));

vi.mock('./pages/DoctorsPage', () => ({
  default: () => '医生管理页面',
}));

const doctorContext: ManagementContext = {
  userId: 'doctor-user-1',
  platformRole: 'USER',
  activeRoles: ['DOCTOR'],
  doctorId: 'doctor-1',
  managedInstitutionIds: [],
  visibleInstitutionIds: [],
  canManageDoctors: true,
  canManageInstitutions: false,
  canManageInstitutionProjects: false,
  canManageArticles: false,
  canManageSplitConfigs: false,
  canManageOrders: false,
  canApplyToInstitutions: true,
  canReviewInstitutionRequests: false,
  canSubmitPlatformProjectRequests: true,
  canSubmitInstitutionProjectRequests: true,
  canReviewInstitutionProjectRequests: false,
  canViewAffiliations: true,
};

function managementToken(context: ManagementContext) {
  const payload = window.btoa(JSON.stringify({
    exp: Math.floor(Date.now() / 1000) + 3600,
    role: context.platformRole,
    sub: context.userId,
  })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `e30.${payload}.signature`;
}

describe('professional role default routes', () => {
  beforeAll(() => {
    window.matchMedia = vi.fn().mockImplementation(query => ({
      matches: false, media: query, onchange: null,
      addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
    }));
    globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
  });

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    window.history.pushState({}, '', '/');
  });

  afterEach(cleanup);

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

  it('routes a capable doctor to project collaboration before unrelated management pages', () => {
    setAdminToken('header.payload.signature', doctorContext);

    expect(getDefaultManagementPath()).toBe('/project-collaboration');
  });

  it('allows a capable doctor to open project collaboration and see its menu entry', async () => {
    setAdminToken(managementToken(doctorContext), doctorContext);
    window.history.pushState({}, '', '/project-collaboration');

    render(createElement(App));

    expect(await screen.findByText('医生项目协作页面')).toBeInTheDocument();
    expect(screen.getByText('项目协作与审核')).toBeInTheDocument();
  });
});
