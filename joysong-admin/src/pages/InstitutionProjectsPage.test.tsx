import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import api, { setAdminToken, type ManagementContext } from '../api';
import InstitutionProjectsPage from './InstitutionProjectsPage';
import { calculatePercentageFeeMinor } from '../utils/money';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return { ...actual, default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } };
});
vi.mock('../components/ImageUpload', () => ({ default: () => null }));
vi.mock('../components/MultiImageUpload', () => ({ default: () => null }));
vi.mock('../components/RichTextEditor', () => ({ default: () => null }));

const adminContext: ManagementContext = {
  userId: 'admin-1', platformRole: 'ADMIN', activeRoles: ['ADMIN'], managedInstitutionIds: [], visibleInstitutionIds: [],
  canManageDoctors: true, canManageInstitutions: true, canManageInstitutionProjects: true, canManageArticles: true,
  canManageSplitConfigs: true, canManageOrders: true, canApplyToInstitutions: false, canReviewInstitutionRequests: true,
  canSubmitPlatformProjectRequests: false, canSubmitInstitutionProjectRequests: false, canReviewInstitutionProjectRequests: true,
  canViewAffiliations: true,
};

const record = {
  id: 'ip-1', institutionId: 'institution-1', projectId: 'project-1', projectName: '公共项目', baseProjectName: '公共项目',
  effectiveName: '机构项目', effectiveCategory: '注射', effectiveDescription: '', effectiveRating: 5, effectiveReviewCount: 0,
  effectiveTags: '', effectiveSlogan: '', price: 4999, coverImage: '', images: '', effectiveCoverImage: '', effectiveImages: '',
  salesCount: 0, isActive: true,
  doctors: [
    { id: 'doctor-a', name: '医生 A', title: '主任', institutionId: 'institution-1', price: 3999 },
    { id: 'doctor-b', name: '医生 B', title: '医生', institutionId: 'institution-1', price: 4299 },
  ],
};

beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(query => ({ matches: false, media: query, onchange: null, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn() }));
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});

beforeEach(() => {
  sessionStorage.clear(); localStorage.clear();
  setAdminToken('header.payload.signature', adminContext);
  vi.mocked(api.put).mockResolvedValue({ data: { code: 200, message: 'OK', data: null } });
  vi.mocked(api.get).mockImplementation(async (url) => {
    const data = url === '/admin/institution-projects' ? [record]
      : url === '/admin/institutions' ? [{ id: 'institution-1', name: '机构 A' }]
        : url === '/admin/projects' ? [{ id: 'project-1', name: '公共项目' }]
          : url === '/admin/doctors' ? record.doctors
            : url === '/admin/order-split-policy' ? { platformRate: 40 } : [];
    return { data: { code: 200, message: 'OK', data } };
  });
});
afterEach(cleanup);

function renderPage() { return render(<MemoryRouter><InstitutionProjectsPage /></MemoryRouter>); }

describe('InstitutionProjectsPage doctor prices', () => {
  it('loads persisted doctor prices, previews the policy fee, and saves doctor bindings without doctorIds', async () => {
    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText(/医生 A.*旅游地接服务费 USD 1599\.60/)).toBeInTheDocument();
    await user.click(await screen.findByTitle('编辑'));
    expect(await screen.findByDisplayValue('3999.00')).toBeInTheDocument();
    expect(screen.getByDisplayValue('4299.00')).toBeInTheDocument();
    expect(screen.getByText('USD 1599.60')).toBeInTheDocument();
    expect(screen.getByText('USD 1719.60')).toBeInTheDocument();
    expect(screen.getAllByText('旅游地接服务费')).toHaveLength(2);

    const priceInputs = screen.getAllByRole('spinbutton', { name: '医生项目价格（USD）' });
    await user.clear(priceInputs[0]);
    await user.type(priceInputs[0], '4999');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/admin/institution-projects/ip-1', expect.objectContaining({
      doctorBindings: [{ doctorId: 'doctor-a', price: 4999 }, { doctorId: 'doctor-b', price: 4299 }],
    })));
    expect(vi.mocked(api.put).mock.calls[0][1]).not.toHaveProperty('doctorIds');
  }, 10_000);

  it.each([
    ['missing price', undefined], ['zero price', 0], ['zero-cent fee', 0.01],
  ])('blocks save for %s', async (_, value) => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTitle('编辑'));
    const price = (await screen.findAllByRole('spinbutton', { name: '医生项目价格（USD）' }))[0];
    await user.clear(price);
    if (value !== undefined) await user.type(price, String(value));
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(api.put).not.toHaveBeenCalled());
  });

  it('blocks duplicate doctors with the duplicate-specific error', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTitle('编辑'));
    const selects = screen.getAllByRole('combobox', { name: '医生' });
    await user.click(selects[1]);
    await user.click((await screen.findAllByText('医生 A（主任）'))[1]);
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));
    expect(await screen.findByText('同一医生只能配置一次')).toBeInTheDocument();
    expect(api.put).not.toHaveBeenCalled();
  }, 10_000);

  it('shows no fee and blocks saving when the policy is unavailable', async () => {
    vi.mocked(api.get).mockImplementation(async (url) => {
      if (url === '/admin/order-split-policy') throw new Error('policy unavailable');
      const data = url === '/admin/institution-projects' ? [record]
        : url === '/admin/institutions' ? [{ id: 'institution-1', name: '机构 A' }]
          : url === '/admin/projects' ? [{ id: 'project-1', name: '公共项目' }] : record.doctors;
      return { data: { code: 200, message: 'OK', data } };
    });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTitle('编辑'));
    expect((await screen.findAllByText('-')).length).toBeGreaterThan(0);
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));
    expect((await screen.findAllByText('分账策略不可用，暂不能保存')).length).toBeGreaterThan(0);
    expect(api.put).not.toHaveBeenCalled();
  }, 10_000);

  it('rejects fees whose intermediate minor-unit multiplication is unsafe', () => {
    expect(calculatePercentageFeeMinor(1_000_000_000, 1000)).toBeNull();
  });
});
