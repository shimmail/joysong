import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import api, { type ManagementContext, setAdminToken } from '../api';
import InstitutionProjectsPage from './InstitutionProjectsPage';
import ProjectsPage from './ProjectsPage';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  };
});

const adminContext: ManagementContext = {
  userId: 'admin-1', platformRole: 'ADMIN', activeRoles: ['ADMIN'],
  managedInstitutionIds: [], visibleInstitutionIds: [],
  canManageDoctors: true, canManageInstitutions: true, canManageInstitutionProjects: true,
  canManageArticles: true, canManageSplitConfigs: true, canManageOrders: true,
  canApplyToInstitutions: false, canReviewInstitutionRequests: true,
  canSubmitPlatformProjectRequests: false, canSubmitInstitutionProjectRequests: false,
  canReviewInstitutionProjectRequests: true, canViewAffiliations: true,
};

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
  vi.mocked(api.get).mockReset();
  vi.mocked(api.post).mockReset();
});

describe('direct project creation regressions', () => {
  it('keeps platform rating and review-count controls and posts to the direct admin create route', async () => {
    const user = userEvent.setup();
    vi.mocked(api.get).mockResolvedValue({ data: { code: 200, message: 'OK', data: [] } });
    vi.mocked(api.post).mockResolvedValue({ data: { code: 200, message: 'OK', data: null } });

    render(<ProjectsPage />);
    await user.click(screen.getByRole('button', { name: /新增项目/ }));

    expect(screen.getByLabelText('评分')).toBeInTheDocument();
    expect(screen.getByLabelText('评价数')).toBeInTheDocument();
    await user.type(screen.getByLabelText('项目名称'), '后台直建项目');
    await user.type(screen.getByLabelText('评分'), '4.8');
    await user.type(screen.getByLabelText('评价数'), '27');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/projects', expect.objectContaining({
      name: '后台直建项目',
      rating: 4.8,
      reviewCount: 27,
    })));
  });

  it('keeps institution rating, review-count, priced doctor bindings and the direct create route', async () => {
    const user = userEvent.setup();
    setAdminToken('header.payload.signature', adminContext);
    vi.mocked(api.get).mockImplementation(async (url) => ({ data: {
      code: 200,
      message: 'OK',
      data: url === '/admin/institutions'
        ? [{ id: 'institution-1', name: '机构甲' }]
        : url === '/admin/projects'
          ? [{ id: 'project-1', name: '基础项目', rating: 4.6, reviewCount: 18 }]
            : url === '/admin/doctors'
            ? [
              { id: 'doctor-1', name: '张医生', title: '主任', institutionId: 'institution-1' },
              { id: 'doctor-2', name: '李医生', title: '副主任', institutionId: 'institution-1' },
            ]
            : url === '/admin/order-split-policy'
              ? { platformRate: 40 }
            : [],
    } }));
    vi.mocked(api.post).mockResolvedValue({ data: { code: 200, message: 'OK', data: null } });

    render(<MemoryRouter><InstitutionProjectsPage /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: /新增机构项目/ }));

    expect(screen.getByLabelText(/评分/)).toBeInTheDocument();
    expect(screen.getByLabelText(/评价数/)).toBeInTheDocument();
    expect(screen.getByText('医生项目价格')).toBeInTheDocument();

    await user.click(screen.getByLabelText('所属机构'));
    await user.click(await screen.findByText('机构甲'));
    await user.click(screen.getByLabelText('关联项目'));
    await user.click(await screen.findByText('基础项目'));
    await user.type(screen.getByLabelText(/评分/), '4.9');
    await user.type(screen.getByLabelText(/评价数/), '31');
    await user.click(screen.getByRole('button', { name: '添加医生' }));
    await user.click(screen.getByRole('button', { name: '添加医生' }));
    const doctorSelects = screen.getAllByRole('combobox', { name: '医生' });
    await user.click(doctorSelects[0]);
    await user.click(await screen.findByText('张医生（主任）'));
    await user.click(doctorSelects[1]);
    await user.click((await screen.findAllByText('李医生（副主任）'))[1]);
    const doctorPrices = screen.getAllByRole('spinbutton', { name: '医生项目价格（USD）' });
    await user.type(doctorPrices[0], '3999');
    await user.type(doctorPrices[1], '4299');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/institution-projects', expect.objectContaining({
      institutionId: 'institution-1',
      projectId: 'project-1',
      rating: 4.9,
      reviewCount: 31,
      doctorBindings: [
        { doctorId: 'doctor-1', price: 3999 },
        { doctorId: 'doctor-2', price: 4299 },
      ],
    })));
    const payload = vi.mocked(api.post).mock.calls[0][1];
    expect(payload).not.toHaveProperty('doctorIds');
    expect(payload).not.toHaveProperty('baseVersion');
    expect(payload).not.toHaveProperty('version');
    expect(payload).not.toHaveProperty('id');
  }, 15_000);
});
