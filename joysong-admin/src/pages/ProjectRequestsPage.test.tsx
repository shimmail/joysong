import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import api, { setAdminToken } from '../api';
import ProjectRequestsPage from './ProjectRequestsPage';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return { ...actual, default: { get: vi.fn(), post: vi.fn() } };
});

beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: false, media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(),
    removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

describe('ProjectRequestsPage', () => {
  it('shows pending doctor platform project requests for admin review', async () => {
    vi.mocked(api.get).mockImplementation(async (url) => ({ data: {
      code: 200, message: 'OK', data: url === '/admin/project-requests' ? [{
        id: 'request-1', requestType: 'PLATFORM', doctorName: '张医生',
        name: '新项目', category: '皮肤', description: '项目说明', status: 'PENDING',
      }] : [],
    } }));

    render(<ProjectRequestsPage />);

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/admin/project-requests'));
    expect(await screen.findByText('新项目')).toBeInTheDocument();
    expect(screen.getByText('张医生')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '通过' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '要求修改' })).toBeInTheDocument();
  });

  it('loads institution project requests from the legal representative endpoint', async () => {
    setAdminToken('header.payload.signature', {
      userId: 'legal-1', platformRole: 'USER', activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
      managedInstitutionIds: ['institution-1'], visibleInstitutionIds: ['institution-1'],
      canManageDoctors: false, canManageInstitutions: true, canManageInstitutionProjects: true,
      canManageArticles: false, canManageSplitConfigs: false, canManageOrders: false,
      canApplyToInstitutions: false, canReviewInstitutionRequests: true,
      canSubmitPlatformProjectRequests: false, canSubmitInstitutionProjectRequests: false,
      canReviewInstitutionProjectRequests: true, canViewAffiliations: false,
    });
    vi.mocked(api.get).mockImplementation(async (url) => ({ data: {
      code: 200, message: 'OK', data: url === '/management/project-requests' ? [{
        id: 'request-2', requestType: 'INSTITUTION', doctorName: '李医生',
        institutionName: '示例机构', projectName: '基础项目', serviceContent: '面诊与术后随访',
        priceSuggestion: 1200, status: 'PENDING',
      }] : [{
        id: 'join-1', requestType: 'JOIN', doctorName: '王医生', institutionName: '示例机构',
        projectName: '已有机构项目', serviceDescription: '个性化治疗', priceSuggestion: 1500,
        notes: '周二出诊', status: 'PENDING',
      }],
    } }));

    render(<ProjectRequestsPage />);

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/management/project-requests'));
    expect(api.get).toHaveBeenCalledWith('/admin/institution-project-requests');
    expect((await screen.findAllByText('示例机构')).length).toBe(2);
    expect(screen.getByText('面诊与术后随访')).toBeInTheDocument();
    expect(screen.getByText('¥1200')).toBeInTheDocument();
    expect(screen.getByText('已有机构项目')).toBeInTheDocument();
    expect(screen.getByText('个性化治疗')).toBeInTheDocument();
    expect(screen.getByText('¥1500')).toBeInTheDocument();
  });
});
