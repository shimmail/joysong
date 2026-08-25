import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
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
  id: 'ip-1', version: 7, currency: 'USD', institutionId: 'institution-1', projectId: 'project-1', projectName: '公共项目', baseProjectName: '公共项目',
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
  it('loads persisted doctor prices, previews the policy fee, and sends the exact CAS update body', async () => {
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

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/admin/institution-projects/ip-1', {
      baseVersion: 7,
      name: null,
      category: null,
      description: null,
      rating: null,
      reviewCount: null,
      tags: null,
      slogan: null,
      detailContent: null,
      price: 4999,
      originalPrice: null,
      currency: 'USD',
      coverImage: '',
      images: '',
      salesCount: 0,
      isActive: true,
      doctorBindings: [{ doctorId: 'doctor-a', price: 4999 }, { doctorId: 'doctor-b', price: 4299 }],
    }));
    expect(Object.keys(vi.mocked(api.put).mock.calls[0][1] as object).sort()).toEqual([
      'baseVersion', 'category', 'coverImage', 'currency', 'description', 'detailContent',
      'doctorBindings', 'images', 'isActive', 'name', 'originalPrice', 'price', 'rating',
      'reviewCount', 'salesCount', 'slogan', 'tags',
    ]);
  }, 10_000);

  it('keeps a stale draft and base version while refresh only updates the side-by-side latest values', async () => {
    const latestRecord = { ...record, version: 8, name: '服务端最新名称', effectiveName: '服务端最新名称', price: 5399 };
    const refreshedRecord = { ...latestRecord, version: 9, name: '再次更新的名称', effectiveName: '再次更新的名称', price: 5599 };
    let projectReadCount = 0;
    vi.mocked(api.get).mockImplementation(async (url) => {
      const data = url === '/admin/institution-projects'
        ? [[record], [latestRecord], [refreshedRecord]][Math.min(projectReadCount++, 2)]
        : url === '/admin/institutions' ? [{ id: 'institution-1', name: '机构 A' }]
          : url === '/admin/projects' ? [{ id: 'project-1', name: '公共项目' }]
            : url === '/admin/doctors' ? record.doctors
              : url === '/admin/order-split-policy' ? { platformRate: 40 } : [];
      return { data: { code: 200, message: 'OK', data } };
    });
    vi.mocked(api.put).mockRejectedValueOnce({
      isAxiosError: true,
      response: { data: { code: 409, errorCode: 'INSTITUTION_PROJECT_VERSION_STALE', message: '本地化消息不得作为分支依据' } },
    });

    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTitle('编辑'));
    const name = screen.getByRole('textbox', { name: /独立名称/ });
    await user.type(name, '我的陈旧草稿');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    expect(await screen.findByText('编辑基线版本：7')).toBeInTheDocument();
    expect(screen.getByText('最新版本：8')).toBeInTheDocument();
    expect(name).toHaveValue('我的陈旧草稿');
    const comparison = screen.getByRole('table', { name: '版本冲突对比' });
    expect(within(comparison).getByText('我的陈旧草稿')).toBeInTheDocument();
    expect(within(comparison).getByText('服务端最新名称')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeDisabled();
    expect(api.put).toHaveBeenCalledTimes(1);
    expect(vi.mocked(api.put).mock.calls[0][1]).toEqual(expect.objectContaining({ baseVersion: 7 }));

    await user.click(screen.getByRole('button', { name: '刷新最新版本' }));
    await waitFor(() => expect(screen.getByText('最新版本：9')).toBeInTheDocument());
    expect(screen.getByText('编辑基线版本：7')).toBeInTheDocument();
    expect(name).toHaveValue('我的陈旧草稿');
    expect(within(screen.getByRole('table', { name: '版本冲突对比' })).getByText('再次更新的名称')).toBeInTheDocument();
    expect(api.put).toHaveBeenCalledTimes(1);
  }, 10_000);

  it('only binds the latest version after explicit rebase resets the form and the user reapplies edits', async () => {
    const latestRecord = {
      ...record,
      version: 8,
      name: '服务端最新名称',
      effectiveName: '服务端最新名称',
      price: 5399,
      doctors: [{ ...record.doctors[0], price: 4599 }, record.doctors[1]],
    };
    let projectReadCount = 0;
    vi.mocked(api.get).mockImplementation(async (url) => {
      const data = url === '/admin/institution-projects'
        ? [projectReadCount++ === 0 ? record : latestRecord]
        : url === '/admin/institutions' ? [{ id: 'institution-1', name: '机构 A' }]
          : url === '/admin/projects' ? [{ id: 'project-1', name: '公共项目' }]
            : url === '/admin/doctors' ? record.doctors
              : url === '/admin/order-split-policy' ? { platformRate: 40 } : [];
      return { data: { code: 200, message: 'OK', data } };
    });
    vi.mocked(api.put)
      .mockRejectedValueOnce({
        isAxiosError: true,
        response: { data: { code: 409, errorCode: 'INSTITUTION_PROJECT_VERSION_STALE', message: '任意本地化消息' } },
      })
      .mockResolvedValueOnce({ data: { code: 200, message: 'OK', data: null } });

    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTitle('编辑'));
    const name = screen.getByRole('textbox', { name: /独立名称/ });
    await user.type(name, '不应自动重放的草稿');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));
    expect(await screen.findByText('最新版本：8')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '基于最新版本重新编辑' }));
    expect(api.put).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('编辑基线版本：7')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('textbox', { name: /独立名称/ })).toHaveValue('服务端最新名称'));
    const rebasedName = screen.getByRole('textbox', { name: /独立名称/ });
    expect(screen.getByRole('spinbutton', { name: '价格' })).toHaveValue('5399');
    expect((screen.getAllByRole('spinbutton', { name: '医生项目价格（USD）' }))[0]).toHaveValue('4599.00');
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeEnabled();

    await user.clear(rebasedName);
    await user.type(rebasedName, '重新应用后的名称');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(api.put).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.put).mock.calls[1]).toEqual([
      '/admin/institution-projects/ip-1',
      expect.objectContaining({ baseVersion: 8, name: '重新应用后的名称', price: 5399 }),
    ]);
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
