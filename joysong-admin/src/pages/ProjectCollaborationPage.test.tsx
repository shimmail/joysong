import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
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

vi.mock('../components/RichTextEditor', () => ({
  default: ({ value, onChange }: { value?: string; onChange?: (value: string) => void }) => (
    <textarea aria-label="独立详情正文" value={value || ''} onChange={event => onChange?.(event.target.value)} />
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
  payloadVersion: 2,
  institutionProjectId: 'ip-1', institutionId: 'institution-1', institutionName: '机构 A',
  platformProjectId: 'project-1', platformProjectName: '公共项目 A',
  doctorId: 'doctor-1', doctorName: '医生 A', baseRevision: 'a'.repeat(64),
  currentProject: {
    schemaVersion: 2,
    association: {
      institutionProjectId: 'ip-1', institutionId: 'institution-1', platformProjectId: 'project-1',
    },
    rawOverrides: {
      name: null, category: '机构分类', description: '原项目简介', tags: ['精细', '自然'],
      slogan: null, detailContent: '<p>原项目正文</p>', coverImage: 'https://img.test/cover.jpg',
      images: ['https://img.test/1.jpg', 'https://img.test/2.jpg'],
    },
    effective: {
      name: '公共项目 A', category: '机构分类', description: '原项目简介', tags: ['精细', '自然'],
      slogan: '公共宣传语', detailContent: '<p>原项目正文</p>', salesCount: 12,
      coverImage: 'https://img.test/cover.jpg', images: ['https://img.test/1.jpg', 'https://img.test/2.jpg'],
    },
    source: { institutionProjectVersion: 7, platformInheritanceHash: 'inheritance-7' },
  },
  currentDoctorPrice: 900, currentDoctorActive: true, platformRate: 40,
  pricingPolicyRevision: 'travel-ground-service-rate:0.400000', travelGroundServiceFee: 360,
};

const pendingV2 = {
  payloadVersion: 2, id: 'request-v2', requestType: 'PROFILE_UPDATE',
  doctorId: 'doctor-1', doctorName: '医生 A', institutionId: 'institution-1', institutionName: '机构 A',
  institutionProjectId: 'ip-1', institutionProjectName: '项目 A v2',
  platformProjectId: 'project-1', platformProjectName: '公共项目 A', baseRevision: 'a'.repeat(64),
  currentProject: profileTarget.currentProject,
  proposedProject: {
    ...profileTarget.currentProject,
    effective: { ...profileTarget.currentProject.effective, name: '项目 A v2' },
  },
  latestProject: null, latestRevision: null, sharedChanged: true,
  currentDoctorPrice: 900, proposedDoctorPrice: 1000, latestDoctorPrice: null,
  currentDoctorActive: true, proposedDoctorActive: true, latestDoctorActive: null,
  platformRate: 40, pricingPolicyRevision: 'travel-ground-service-rate:0.400000',
  travelGroundServiceFee: 400, requestStatus: 'PENDING', notes: '完整更新', forceProcessed: false,
  submittedBy: 'doctor-user-1', submittedAt: '2026-08-24T10:00:00Z', reviewedBy: null,
  reviewerName: null, reviewNote: null, reviewedAt: null, updatedAt: '2026-08-24T10:00:00Z',
  snapshotState: 'VALID', snapshotError: null, reviewable: true,
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
      : url === '/v2/admin/institution-project-requests' ? requests
        : url === '/admin/institutions' ? [{ id: 'institution-1', name: '机构 A' }]
          : url === '/v2/admin/institution-project-requests/profile-update-targets' ? [profileTarget]
            : [];
    return { data: { code: 200, message: 'OK', data } };
  });
}

describe('ProjectCollaborationPage profile update', () => {
  it('loads the strict v2 target into the complete editor with read-only association and no forbidden controls', async () => {
    const user = userEvent.setup();
    setAdminToken('header.payload.signature', doctorContext);
    mockPageData();

    render(<ProjectCollaborationPage />);
    await user.click(await screen.findByRole('button', { name: /修改我的资料/ }));

    const dialog = await screen.findByRole('dialog', { name: '申请修改个人项目资料' });
    expect(within(dialog).getByText('机构 A')).toBeInTheDocument();
    expect(within(dialog).getByText('公共项目 A')).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: /独立名称/ })).toHaveValue('');
    expect(within(dialog).getByText('当前继承值：公共项目 A')).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: /独立分类/ })).toHaveValue('机构分类');
    expect(within(dialog).getByRole('textbox', { name: '独立简介' })).toHaveValue('原项目简介');
    expect(within(dialog).getByRole('textbox', { name: '标签' })).toHaveValue('精细, 自然');
    expect(within(dialog).getByRole('textbox', { name: /宣传语/ })).toHaveValue('');
    expect(within(dialog).getByRole('textbox', { name: '独立详情正文' })).toHaveValue('<p>原项目正文</p>');
    expect(within(dialog).getByRole('spinbutton', { name: '医生项目价格（USD）' })).toHaveValue('900.00');
    expect(within(dialog).getByRole('spinbutton', { name: '销量' })).toHaveValue('12');
    expect(within(dialog).getByRole('switch', { name: '医生项目是否上架' })).toBeChecked();
    expect(within(dialog).getByText('USD 360.00')).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: '个人项目封面' })).toHaveValue('https://img.test/cover.jpg');
    expect(within(dialog).getByRole('textbox', { name: '个人案例图集' })).toHaveValue('https://img.test/1.jpg,https://img.test/2.jpg');

    expect(api.get).toHaveBeenCalledWith('/v2/admin/institution-project-requests/profile-update-targets');
    expect(api.get).toHaveBeenCalledWith('/v2/admin/institution-project-requests');
    expect(within(dialog).queryByLabelText('所属机构')).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText('关联项目')).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText('出诊与排班说明')).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText('原价')).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText('是否上架')).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/评分/)).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/评价数/)).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/面诊费|顾问|机构比例|平台比例|医生比例/)).not.toBeInTheDocument();
  }, 10_000);

  it('submits exactly the 15-key v2 profile draft with explicit inheritance intent', async () => {
    const user = userEvent.setup();
    setAdminToken('header.payload.signature', doctorContext);
    mockPageData();

    render(<ProjectCollaborationPage />);
    await user.click(await screen.findByRole('button', { name: /修改我的资料/ }));
    const dialog = await screen.findByRole('dialog', { name: '申请修改个人项目资料' });

    await user.clear(within(dialog).getByRole('textbox', { name: /独立分类/ }));
    const description = within(dialog).getByRole('textbox', { name: '独立简介' });
    await user.clear(description);
    await user.type(description, '更新后的项目简介');
    const tags = within(dialog).getByRole('textbox', { name: '标签' });
    await user.clear(tags);
    await user.type(tags, '轮廓, 年轻化');
    await user.type(within(dialog).getByRole('textbox', { name: /宣传语/ }), '焕新宣传语');
    const detail = within(dialog).getByRole('textbox', { name: '独立详情正文' });
    await user.clear(detail);
    await user.type(detail, '更新后的项目正文');
    const price = within(dialog).getByRole('spinbutton', { name: '医生项目价格（USD）' });
    await user.clear(price);
    await user.type(price, '4299');
    const salesCount = within(dialog).getByRole('spinbutton', { name: '销量' });
    await user.clear(salesCount);
    await user.type(salesCount, '18');
    await user.click(within(dialog).getByRole('switch', { name: '医生项目是否上架' }));
    await user.clear(within(dialog).getByRole('textbox', { name: '个人项目封面' }));
    await user.clear(within(dialog).getByRole('textbox', { name: '个人案例图集' }));
    await user.type(within(dialog).getByRole('textbox', { name: '申请说明' }), '请审核完整项目资料');
    await user.click(within(dialog).getByRole('button', { name: '提交机构审核' }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    const [path, payload] = vi.mocked(api.post).mock.calls[0] as [string, Record<string, unknown>];
    expect(path).toBe('/v2/admin/institution-project-requests');
    expect(payload).toEqual({
      requestType: 'PROFILE_UPDATE',
      institutionProjectId: 'ip-1',
      baseRevision: 'a'.repeat(64),
      name: null,
      category: null,
      description: '更新后的项目简介',
      tags: ['轮廓', '年轻化'],
      slogan: '焕新宣传语',
      detailContent: '更新后的项目正文',
      price: 4299,
      salesCount: 18,
      doctorActive: false,
      coverImage: null,
      images: null,
      notes: '请审核完整项目资料',
    });
    expect(Object.keys(payload).sort()).toEqual([
      'baseRevision', 'category', 'coverImage', 'description', 'detailContent', 'doctorActive',
      'images', 'institutionProjectId', 'name', 'notes', 'price', 'requestType', 'salesCount',
      'slogan', 'tags',
    ]);
  }, 10_000);

  it('shows parsed v2 history as applicant-only collaboration with no duplicate reviewer surface', async () => {
    setAdminToken('header.payload.signature', adminContext);
    mockPageData([pendingV2]);

    render(<ProjectCollaborationPage />);
    expect(await screen.findByText('项目 A v2')).toBeInTheDocument();
    expect(screen.getByText('PENDING')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '我的申请' })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: '申请审核' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '通过' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '驳回' })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: /通过项目申请|驳回项目申请/ })).not.toBeInTheDocument();
  });

  it('shows only JOIN-compatible fields and enforces positive USD price bounds', async () => {
    const user = userEvent.setup();
    setAdminToken('header.payload.signature', doctorContext);
    mockPageData([], [joinProject]);

    render(<ProjectCollaborationPage />);
    await user.click(await screen.findByRole('button', { name: /申请加入/ }));

    const dialog = await screen.findByRole('dialog', { name: '申请加入机构项目' });
    expect(within(dialog).getByRole('textbox', { name: '个人服务介绍' })).toBeInTheDocument();
    const priceSuggestion = within(dialog).getByRole('spinbutton', { name: '项目价格建议（USD）' });
    expect(priceSuggestion).toHaveAttribute('aria-valuemin', '0.02');
    expect(priceSuggestion).toHaveAttribute('aria-valuemax', '99999999.99');
    expect(priceSuggestion).toHaveAttribute('step', '0.01');
    expect(within(dialog).getByRole('textbox', { name: '补充说明' })).toBeInTheDocument();
    expect(within(dialog).queryByText('个人擅长标签')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('出诊与排班说明')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('个人项目封面')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('个人案例图集')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('医疗套餐优惠前金额（USD）')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('平台服务比例')).not.toBeInTheDocument();

    await user.type(priceSuggestion, '0');
    await user.click(within(dialog).getByRole('textbox', { name: '补充说明' }));
    expect(priceSuggestion).toHaveValue('0.02');
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
    await user.type(within(dialog).getByRole('spinbutton', { name: '项目价格建议（USD）' }), '1500');
    await user.type(within(dialog).getByRole('textbox', { name: '补充说明' }), '希望加入该项目');
    await user.click(within(dialog).getByRole('button', { name: '提交机构审核' }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/v2/admin/institution-project-requests',
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

  it('submits LEAVE to v2 using exactly the two legacy-compatible fields', async () => {
    const user = userEvent.setup();
    setAdminToken('header.payload.signature', doctorContext);
    mockPageData();

    render(<ProjectCollaborationPage />);
    await user.click(await screen.findByRole('button', { name: /申请退出/ }));
    const dialog = await screen.findByRole('dialog', { name: '申请退出该机构项目？' });
    await user.click(within(dialog).getByRole('button', { name: '提交退出申请' }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/v2/admin/institution-project-requests',
      { institutionProjectId: 'ip-1', requestType: 'LEAVE' },
    ));
    expect(Object.keys(vi.mocked(api.post).mock.calls[0][1] as object).sort()).toEqual([
      'institutionProjectId', 'requestType',
    ]);
  });

  it('withdraws an applicant-owned pending request through the v2 route', async () => {
    const user = userEvent.setup();
    setAdminToken('header.payload.signature', doctorContext);
    mockPageData([pendingV2], [joinProject]);

    render(<ProjectCollaborationPage />);
    await user.click(await screen.findByRole('tab', { name: '我的申请' }));
    const projectName = await screen.findByText('项目 A v2');
    const requestRow = projectName.closest('tr');
    expect(requestRow).not.toBeNull();
    expect(screen.getByText('PENDING')).toBeInTheDocument();
    expect(screen.queryByText('MALFORMED_V2_PAYLOAD')).not.toBeInTheDocument();
    await user.click(await within(requestRow!).findByRole('button', { name: /撤\s*回/ }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/v2/admin/institution-project-requests/request-v2/withdraw',
    ));
  });
});
