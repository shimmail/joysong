import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import api, { setAdminToken, type ManagementContext } from '../api';
import ProjectCollaborationPage from './ProjectCollaborationPage';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return { ...actual, default: { get: vi.fn(), post: vi.fn() } };
});

vi.mock('../components/ImageUpload', () => ({
  default: ({ value, onChange }: { value?: string; onChange?: (value: string) => void }) => (
    <input aria-label="个人项目封面" value={value || ''} onChange={event => onChange?.(event.target.value)} />
  ),
}));

vi.mock('../components/MultiImageUpload', () => ({
  default: ({ value, onChange }: { value?: string; onChange?: (value: string) => void }) => (
    <input aria-label="个人案例图集" value={value || ''} onChange={event => onChange?.(event.target.value)} />
  ),
}));

const doctorContext: ManagementContext = {
  userId: 'doctor-user-1', platformRole: 'USER', activeRoles: ['DOCTOR'], doctorId: 'doctor-1',
  managedInstitutionIds: [], visibleInstitutionIds: ['institution-1'],
  canManageDoctors: false, canManageInstitutions: false, canManageInstitutionProjects: false,
  canManageArticles: false, canManageSplitConfigs: false, canManageOrders: false,
  canApplyToInstitutions: true, canReviewInstitutionRequests: false,
  canSubmitPlatformProjectRequests: true, canSubmitInstitutionProjectRequests: true,
  canReviewInstitutionProjectRequests: false, canViewAffiliations: true,
};

const adminContext: ManagementContext = {
  ...doctorContext,
  userId: 'admin-1', platformRole: 'ADMIN', activeRoles: ['ADMIN'], doctorId: undefined,
  managedInstitutionIds: [], visibleInstitutionIds: [],
  canManageDoctors: true, canManageInstitutions: true, canManageInstitutionProjects: true,
  canManageArticles: true, canManageSplitConfigs: true, canManageOrders: true,
  canApplyToInstitutions: false, canReviewInstitutionRequests: true,
  canSubmitPlatformProjectRequests: false, canReviewInstitutionProjectRequests: true,
};

const project = {
  id: 'ip-1', institutionId: 'institution-1', effectiveName: '项目 A', price: 900, isActive: true,
  doctors: [{ id: 'doctor-1', name: '医生 A' }],
};

const joinProject = {
  ...project,
  id: 'ip-join',
  effectiveName: '项目 B',
  doctors: [],
};

const profileTarget = {
  institutionProjectId: 'ip-1', projectName: '项目 A',
  institutionId: 'institution-1', institutionName: '机构 A', currentPrice: 900,
  serviceDescription: '原服务介绍', serviceTags: ['精细', '自然'], scheduleNote: '每周二',
  coverImage: 'https://img.test/cover.jpg', images: ['https://img.test/1.jpg', 'https://img.test/2.jpg'],
  consultationFee: 100, commissionRate: 10, institutionRate: 35,
  medicalListPrice: 1000, platformRate: 40, doctorRate: 15,
};

const pendingJoin = {
  id: 'request-1', doctorId: 'doctor-2', doctorName: '医生 B',
  institutionId: 'institution-1', institutionName: '机构 A',
  institutionProjectId: 'ip-1', projectName: '项目 A', requestType: 'JOIN',
  serviceDescription: '申请加入', serviceTags: [], scheduleNote: '', coverImage: '', images: [],
  status: 'PENDING', submittedBy: 'doctor-user-2', reviewNote: '', submittedAt: '2026-08-21T10:00:00',
};

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
  vi.mocked(api.post).mockResolvedValue({ data: { code: 200, message: 'OK', data: null } });
});

afterEach(cleanup);

function mockPageData(requests: unknown[] = [], projects = [project]) {
  vi.mocked(api.get).mockImplementation(async (url) => {
    const data = url === '/admin/institution-projects' ? projects
      : url === '/admin/institution-project-requests' ? requests
        : url === '/admin/institutions' ? [{ id: 'institution-1', name: '机构 A' }]
          : url === '/admin/institution-project-requests/profile-update-targets' ? [profileTarget]
            : [];
    return { data: { code: 200, message: 'OK', data } };
  });
}

describe('ProjectCollaborationPage profile update', () => {
  it('loads the server target and submits one doctor project price through both compatibility fields', async () => {
    const user = userEvent.setup();
    setAdminToken('header.payload.signature', doctorContext);
    mockPageData();

    render(<ProjectCollaborationPage />);
    await user.click(await screen.findByRole('button', { name: /修改我的资料/ }));

    const dialog = await screen.findByRole('dialog', { name: '申请修改个人项目资料' });
    const doctorPrice = within(dialog).getByRole('spinbutton', { name: '医生项目价格（USD）' });
    expect(doctorPrice).toHaveValue('900.00');
    expect(within(dialog).getByRole('spinbutton', { name: '平台服务比例' })).toBeDisabled();
    expect(api.get).toHaveBeenCalledWith('/admin/institution-project-requests/profile-update-targets');

    const tags = within(dialog).getByRole('textbox', { name: '个人擅长标签' });
    await user.clear(tags);
    await user.type(tags, '轮廓, 年轻化');
    await user.clear(doctorPrice);
    await user.type(doctorPrice, '4299');
    await user.click(within(dialog).getByRole('button', { name: '提交机构审核' }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    const [path, payload] = vi.mocked(api.post).mock.calls[0] as [string, Record<string, unknown>];
    expect(path).toBe('/admin/institution-project-requests');
    expect(payload).toEqual({
      institutionProjectId: 'ip-1',
      requestType: 'PROFILE_UPDATE',
      serviceDescription: '原服务介绍',
      priceSuggestion: 4299,
      notes: '',
      serviceTags: ['轮廓', '年轻化'],
      scheduleNote: '每周二',
      coverImage: 'https://img.test/cover.jpg',
      images: ['https://img.test/1.jpg', 'https://img.test/2.jpg'],
      consultationFee: 100,
      commissionRate: 10,
      institutionRate: 35,
      medicalListPrice: 4299,
    });
    expect(Object.keys(payload).sort()).toEqual([
      'commissionRate', 'consultationFee', 'coverImage', 'images', 'institutionProjectId',
      'institutionRate', 'medicalListPrice', 'notes', 'priceSuggestion', 'requestType',
      'scheduleNote', 'serviceDescription', 'serviceTags',
    ]);
    expect(payload).not.toHaveProperty('platformRate');
    expect(payload).not.toHaveProperty('doctorRate');
    expect(payload).not.toHaveProperty('doctorId');
  }, 10_000);

  it('reviews an institution project change with an explicit non-forced body', async () => {
    const user = userEvent.setup();
    setAdminToken('header.payload.signature', adminContext);
    mockPageData([pendingJoin]);

    render(<ProjectCollaborationPage />);
    await user.click(await screen.findByRole('button', { name: /通过/ }));
    const dialog = await screen.findByRole('dialog', { name: '通过项目申请' });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认通过' }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/admin/institution-project-requests/request-1/review',
      { decision: 'APPROVED', reviewNote: '', force: false },
    ));
  });

  it('shows only JOIN-compatible fields and rejects a negative price suggestion', async () => {
    const user = userEvent.setup();
    setAdminToken('header.payload.signature', doctorContext);
    mockPageData([], [joinProject]);

    render(<ProjectCollaborationPage />);
    await user.click(await screen.findByRole('button', { name: /申请加入/ }));

    const dialog = await screen.findByRole('dialog', { name: '申请加入机构项目' });
    expect(within(dialog).getByRole('textbox', { name: '个人服务介绍' })).toBeInTheDocument();
    const priceSuggestion = within(dialog).getByRole('spinbutton', { name: '项目价格建议' });
    expect(within(dialog).getByRole('textbox', { name: '补充说明' })).toBeInTheDocument();
    expect(within(dialog).queryByText('个人擅长标签')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('出诊与排班说明')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('个人项目封面')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('个人案例图集')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('医疗套餐优惠前金额（USD）')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('平台服务比例')).not.toBeInTheDocument();

    await user.type(priceSuggestion, '-1');
    await user.click(within(dialog).getByRole('button', { name: '提交机构审核' }));

    expect(await within(dialog).findByText('价格建议不能为负数')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  }, 10_000);

  it('submits JOIN using exactly the five backend-compatible fields', async () => {
    const user = userEvent.setup();
    setAdminToken('header.payload.signature', doctorContext);
    mockPageData([], [joinProject]);

    render(<ProjectCollaborationPage />);
    await user.click(await screen.findByRole('button', { name: /申请加入/ }));

    const dialog = await screen.findByRole('dialog', { name: '申请加入机构项目' });
    await user.type(within(dialog).getByRole('textbox', { name: '个人服务介绍' }), '擅长精细操作');
    await user.type(within(dialog).getByRole('spinbutton', { name: '项目价格建议' }), '1500');
    await user.type(within(dialog).getByRole('textbox', { name: '补充说明' }), '希望加入该项目');
    await user.click(within(dialog).getByRole('button', { name: '提交机构审核' }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/admin/institution-project-requests',
      {
        institutionProjectId: 'ip-join',
        requestType: 'JOIN',
        serviceDescription: '擅长精细操作',
        priceSuggestion: 1500,
        notes: '希望加入该项目',
      },
    ));
    expect(Object.keys(vi.mocked(api.post).mock.calls[0][1] as object).sort()).toEqual([
      'institutionProjectId', 'notes', 'priceSuggestion', 'requestType', 'serviceDescription',
    ]);
  }, 10_000);
});
