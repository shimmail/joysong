import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import api, { type ManagementContext, setAdminToken } from '../api';
import ProjectRequestsPage, { isAdminForceEligible, REVIEW_ERROR_FORCE_ELIGIBILITY } from './ProjectRequestsPage';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return { ...actual, default: { get: vi.fn(), post: vi.fn() } };
});

const adminContext: ManagementContext = {
  userId: 'admin-1', platformRole: 'ADMIN', activeRoles: ['ADMIN'], managedInstitutionIds: [], visibleInstitutionIds: [],
  canManageDoctors: true, canManageInstitutions: true, canManageInstitutionProjects: true, canManageArticles: true,
  canManageSplitConfigs: true, canManageOrders: true, canApplyToInstitutions: false, canReviewInstitutionRequests: true,
  canSubmitPlatformProjectRequests: false, canSubmitInstitutionProjectRequests: false,
  canReviewInstitutionProjectRequests: true, canViewAffiliations: true,
};

const representativeContext: ManagementContext = {
  ...adminContext, userId: 'legal-1', platformRole: 'USER', activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
  managedInstitutionIds: ['institution-1'], visibleInstitutionIds: ['institution-1'],
  canManageDoctors: false, canManageSplitConfigs: false, canManageOrders: false,
};

const doctorContext: ManagementContext = {
  ...representativeContext, userId: 'doctor-user-2', activeRoles: ['DOCTOR'], doctorId: 'doctor-2',
  managedInstitutionIds: [], visibleInstitutionIds: [], canManageInstitutions: false,
  canManageInstitutionProjects: false, canReviewInstitutionProjectRequests: false,
};

const platformRequest = {
  id: 'platform-request', requestType: 'PLATFORM', doctorId: 'doctor-1', doctorName: '张医生',
  institutionId: null, institutionName: null, projectId: null, projectName: null,
  name: '光子焕肤', category: '皮肤', description: '完整平台项目说明', tags: ['舒适', '午休'],
  slogan: '午休也能焕新', detailContent: '<p>完整的平台详情正文</p>', currency: 'USD',
  coverImage: 'https://img.test/platform-cover.jpg', images: ['https://img.test/platform-1.jpg'], salesCount: 12,
  referencePrice: 399.5, categoryTags: ['光电'], price: null, originalPrice: null, isActive: null,
  institutionSplit: null, notes: '平台申请备注', status: 'PENDING', reviewNote: null, reviewedBy: null,
  reviewedAt: null, resultingProjectId: null, resultingInstitutionProjectId: null,
  submittedAt: '2026-08-16T09:10:00', updatedAt: '2026-08-16T09:10:00',
};

const institutionRequest = {
  id: 'institution-request', requestType: 'INSTITUTION', doctorId: 'doctor-1', doctorName: '张医生',
  institutionId: 'institution-1', institutionName: '示例机构', projectId: 'project-1', projectName: '基础光子',
  name: '机构定制光子', category: null, description: null, tags: null, slogan: null, detailContent: null,
  currency: 'USD', coverImage: null, images: null, salesCount: 8, referencePrice: null, categoryTags: null,
  price: 1200, originalPrice: 1680, isActive: true,
  institutionSplit: { consultationFee: 100, commissionRate: 12.5, institutionRate: 32.5, platformRate: 10, doctorRate: 45 },
  notes: null, status: 'PENDING', reviewNote: null, reviewedBy: null, reviewedAt: null,
  resultingProjectId: null, resultingInstitutionProjectId: null,
  submittedAt: '2026-08-16T10:20:00', updatedAt: '2026-08-16T10:20:00',
};

function snapshot(name: string, version = 7) {
  return {
    schemaVersion: 2,
    association: { institutionProjectId: 'ip-1', institutionId: 'institution-1', platformProjectId: 'project-1' },
    rawOverrides: {
      name, category: null, description: '机构共享说明', tags: ['共享'], slogan: null,
      detailContent: '<p>安全详情</p>', coverImage: 'https://img.test/cover.jpg', images: ['https://img.test/one.jpg'],
    },
    effective: {
      name, category: '光电', description: '机构共享说明', tags: ['共享'], slogan: null,
      detailContent: '<p>安全详情</p>', salesCount: 12, coverImage: 'https://img.test/cover.jpg', images: ['https://img.test/one.jpg'],
    },
    source: { institutionProjectVersion: version, platformInheritanceHash: `inheritance-${version}` },
  };
}

const v2Request = {
  payloadVersion: 2, id: 'v2-request', requestType: 'PROFILE_UPDATE', doctorId: 'doctor-2', doctorName: '王医生',
  institutionId: 'institution-1', institutionName: '示例机构', institutionProjectId: 'ip-1',
  institutionProjectName: '机构焕肤', platformProjectId: 'project-1', platformProjectName: '基础光子',
  baseRevision: 'base-7', currentProject: snapshot('机构焕肤'), proposedProject: snapshot('机构焕肤升级版'),
  latestProject: null, latestRevision: null, sharedChanged: true,
  currentDoctorPrice: 1200, proposedDoctorPrice: 1350, latestDoctorPrice: null,
  currentDoctorActive: true, proposedDoctorActive: false, latestDoctorActive: null,
  platformRate: 10, pricingPolicyRevision: 'pricing-3', travelGroundServiceFee: 88,
  requestStatus: 'PENDING', notes: '更新资料', forceProcessed: false, submittedBy: 'doctor-user-2',
  submittedAt: '2026-08-24T10:00:00Z', reviewedBy: null, reviewerName: null, reviewNote: null,
  reviewedAt: null, updatedAt: '2026-08-24T10:00:00Z', snapshotState: 'VALID', snapshotError: null, reviewable: true,
};

const refreshedV2Request = {
  ...v2Request, latestProject: snapshot('服务器最新项目', 8), latestRevision: 'latest-revision-8',
  latestDoctorPrice: 1275, latestDoctorActive: true,
};

const legacyRequest = {
  payloadVersion: 1, id: 'legacy-request', requestType: 'JOIN', doctorId: 'doctor-2', doctorName: '王医生',
  institutionId: 'institution-1', institutionName: '示例机构', institutionProjectId: 'ip-1', projectName: '已有机构项目',
  serviceDescription: '个性化治疗', priceSuggestion: 1500, notes: '周二出诊', serviceTags: ['精细'],
  scheduleNote: '每周二', coverImage: '', images: [], status: 'PENDING', reviewNote: '', submittedAt: '2026-08-15T09:00:00',
};

const invalidSnapshotRequest = {
  ...v2Request, id: 'invalid-snapshot', institutionProjectName: '损坏快照项目', currentProject: null, proposedProject: null,
  snapshotState: 'INVALID', snapshotError: 'REQUEST_SNAPSHOT_INVALID', reviewable: false,
};
const malformedRequest = { ...v2Request, id: 'malformed-request', institutionProjectName: '载荷损坏项目' } as Record<string, unknown>;
delete malformedRequest.baseRevision;

function setContext(context: ManagementContext) {
  setAdminToken('header.payload.signature', context);
}

function response(data: unknown) {
  return { data: { code: 200, message: 'OK', data } };
}

function mockQueues(creations: unknown[] = [], changes: unknown[] = []) {
  vi.mocked(api.get).mockImplementation(async (url) => {
    if (url === '/admin/project-requests' || url === '/management/project-requests') return response(creations);
    if (url === '/v2/admin/institution-project-requests') return response(changes);
    throw new Error(`Unexpected GET ${url}`);
  });
  vi.mocked(api.post).mockResolvedValue(response(null));
}

function apiError(errorCode: string, errorMessage = '审核冲突') {
  return { isAxiosError: true, response: { status: 409, data: { errorCode, message: errorMessage } } };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(resolvePromise => { resolve = resolvePromise; });
  return { promise, resolve };
}

beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: false, media: query, onchange: null, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});

afterEach(() => {
  cleanup();
  message.destroy();
  sessionStorage.clear();
  vi.mocked(api.get).mockReset();
  vi.mocked(api.post).mockReset();
});

describe('ProjectRequestsPage', () => {
  it('loads changes only from v2 and keeps them visible when the independent creation list fails', async () => {
    setContext(adminContext);
    vi.mocked(api.get).mockImplementation(async (url) => {
      if (url === '/admin/project-requests') throw new Error('creation offline');
      if (url === '/v2/admin/institution-project-requests') return response([v2Request]);
      throw new Error(`Unexpected GET ${url}`);
    });

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('机构焕肤')).toBeInTheDocument();
    expect(screen.getByText('创建申请加载失败')).toBeInTheDocument();
    expect(api.get).toHaveBeenCalledWith('/v2/admin/institution-project-requests');
    expect(api.get).not.toHaveBeenCalledWith('/admin/institution-project-requests');
  });

  it('keeps platform creation visible when the independent doctor change queue fails', async () => {
    setContext(adminContext);
    vi.mocked(api.get).mockImplementation(async (url) => {
      if (url === '/admin/project-requests') return response([platformRequest]);
      if (url === '/v2/admin/institution-project-requests') throw new Error('change offline');
      throw new Error(`Unexpected GET ${url}`);
    });

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('光子焕肤')).toBeInTheDocument();
    expect(screen.getByText('医生项目变更申请加载失败')).toBeInTheDocument();
  });

  it('keeps the shared creation validator boundary and shows applicant history without review actions', async () => {
    setContext(doctorContext);
    mockQueues([{ ...platformRequest, id: 'x'.repeat(37), name: '损坏创建申请' }, platformRequest], [v2Request]);
    render(<ProjectRequestsPage />);

    expect(await screen.findByText('创建申请快照数据不完整，已禁止审核')).toBeInTheDocument();
    expect(screen.queryByText('损坏创建申请')).not.toBeInTheDocument();
    expect(screen.getByText('光子焕肤')).toBeInTheDocument();
    expect(screen.getByText('机构焕肤')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^通过 / })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^要求修改 / })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^驳回 / })).not.toBeInTheDocument();
  });

  it('groups institution creation and mixed v1/v2 history while keeping platform creation separate', async () => {
    setContext(adminContext);
    mockQueues([platformRequest, institutionRequest], [legacyRequest, v2Request]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByRole('heading', { name: '平台项目创建申请' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '按机构审核' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    const group = screen.getByRole('region', { name: '示例机构审核组' });
    expect(within(group).getByText('机构定制光子')).toBeInTheDocument();
    expect(within(group).getByText('已有机构项目')).toBeInTheDocument();
    expect(within(group).getByText('机构焕肤')).toBeInTheDocument();
    expect(within(group).getAllByText('平台项目：基础光子')).toHaveLength(2);
    expect(within(group).getAllByText('医生：王医生')).toHaveLength(2);
    expect(within(group).getByText('医生价格：USD 1350.00')).toBeInTheDocument();
    expect(within(group).getAllByText('申请状态：待审核')).toHaveLength(3);
    expect(within(group).getByText('提议医生状态：停用')).toBeInTheDocument();
    expect(screen.queryByText(/¥|https:\/\//)).not.toBeInTheDocument();
  });

  it('keeps a negative derived doctor rate reject-only for institution creation', async () => {
    setContext(adminContext);
    mockQueues([{ ...institutionRequest, institutionSplit: {
      ...institutionRequest.institutionSplit, commissionRate: 60, institutionRate: 30, platformRate: 15, doctorRate: -5,
    } }], []);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('当前分成比例冲突：该创建申请仅可驳回。')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '通过 机构定制光子，申请ID institution-request' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '驳回 机构定制光子，申请ID institution-request' })).toBeInTheDocument();
  });

  it('keeps both professional creation review routes and their legacy two-key approval body', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockQueues([platformRequest, institutionRequest], []);
    render(<ProjectRequestsPage />);

    await user.click(await screen.findByRole('button', { name: '通过 光子焕肤，申请ID platform-request' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/project-requests/platform-request/review', {
      decision: 'APPROVED', reviewNote: '',
    }));
    await user.click(screen.getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/management/project-requests/institution-request/review', {
      decision: 'APPROVED', reviewNote: '',
    }));
  });

  it('uses shared safe previews for creation, v1 schedule, and v2 current/proposed/latest comparisons', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockQueues([platformRequest], [legacyRequest, refreshedV2Request]);
    render(<ProjectRequestsPage />);

    await user.click(await screen.findByRole('button', { name: '查看详情 光子焕肤，申请ID platform-request' }));
    expect(screen.getByRole('region', { name: '机构项目预览' })).toBeInTheDocument();
    expect(screen.getByText('USD 399.50')).toBeInTheDocument();
    expect(screen.queryByText('https://img.test/platform-cover.jpg')).not.toBeInTheDocument();
    fireEvent.click(document.querySelector('.ant-drawer-close')!);

    await user.click(screen.getByRole('button', { name: '查看详情 已有机构项目，申请ID legacy-request' }));
    expect(screen.getAllByText('每周二').length).toBeGreaterThan(0);
    fireEvent.click(document.querySelector('.ant-drawer-close')!);

    await user.click(screen.getByRole('button', { name: '查看详情 机构焕肤，申请ID v2-request' }));
    expect(screen.getByText('本次申请修改机构共享项目资料，请同时核对其他医生受到的影响。')).toBeInTheDocument();
    expect(screen.getAllByText('机构焕肤升级版').length).toBeGreaterThan(0);
    expect(screen.getByText('服务器最新项目')).toBeInTheDocument();
    expect(screen.getByText('USD 1200.00')).toBeInTheDocument();
    expect(screen.getAllByText('USD 1350.00').length).toBeGreaterThan(0);
    expect(screen.getByText('USD 1275.00')).toBeInTheDocument();
    expect(screen.getAllByText('USD 88.00').length).toBeGreaterThan(0);
    expect(screen.queryByText(/¥|https:\/\//)).not.toBeInTheDocument();
  });

  it('keeps invalid and malformed snapshots visible with an error badge and no review actions', async () => {
    setContext(adminContext);
    mockQueues([], [invalidSnapshotRequest, malformedRequest]);
    render(<ProjectRequestsPage />);

    expect(await screen.findByText('损坏快照项目')).toBeInTheDocument();
    expect(screen.getByText('载荷损坏项目')).toBeInTheDocument();
    expect(screen.getByText('数据损坏：REQUEST_SNAPSHOT_INVALID')).toBeInTheDocument();
    expect(screen.getByText('数据损坏：MALFORMED_V2_PAYLOAD')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /通过 .*invalid-snapshot|通过 .*malformed-request/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /要求修改 .*invalid-snapshot|要求修改 .*malformed-request/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /驳回 .*invalid-snapshot|驳回 .*malformed-request/ })).not.toBeInTheDocument();
  });

  it('posts the exact four-key normal body and synchronously blocks every action while it is in flight', async () => {
    const user = userEvent.setup();
    const pending = deferred<ReturnType<typeof response>>();
    setContext(adminContext);
    mockQueues([], [v2Request, legacyRequest]);
    vi.mocked(api.post).mockReturnValueOnce(pending.promise as never);
    render(<ProjectRequestsPage />);

    await user.click(await screen.findByRole('button', { name: '通过 机构焕肤，申请ID v2-request' }));

    expect(api.post).toHaveBeenCalledWith('/v2/admin/institution-project-requests/v2-request/review', {
      decision: 'APPROVED', reviewNote: '', force: false, forceBaseRevision: null,
    });
    screen.getAllByRole('button', { name: /^(查看详情|通过|要求修改|驳回) / }).forEach(button => expect(button).toBeDisabled());
    pending.resolve(response(null));
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(3));
  });

  it('requires a nonblank note for changes requested and posts the exact four-key body', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockQueues([], [v2Request]);
    render(<ProjectRequestsPage />);

    await user.click(await screen.findByRole('button', { name: '要求修改 机构焕肤，申请ID v2-request' }));
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));
    expect(await screen.findByText('请填写审核意见')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
    await user.type(screen.getByLabelText('审核意见'), '请补充术后说明');
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/v2/admin/institution-project-requests/v2-request/review', {
      decision: 'CHANGES_REQUESTED', reviewNote: '请补充术后说明', force: false, forceBaseRevision: null,
    }));
  });

  it('keeps legacy v1 history reviewable through the v2 route with the four-key body', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockQueues([], [legacyRequest]);
    render(<ProjectRequestsPage />);

    await user.click(await screen.findByRole('button', { name: '通过 已有机构项目，申请ID legacy-request' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/v2/admin/institution-project-requests/legacy-request/review', {
      decision: 'APPROVED', reviewNote: '', force: false, forceBaseRevision: null,
    }));
  });

  it('limits review actions by role and managed institution scope', async () => {
    setContext(representativeContext);
    const outside = { ...v2Request, id: 'outside-request', institutionId: 'institution-2', institutionName: '其他机构' };
    mockQueues([platformRequest, institutionRequest], [v2Request, outside]);
    render(<ProjectRequestsPage />);

    expect(await screen.findByRole('button', { name: '通过 机构焕肤，申请ID v2-request' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '通过 光子焕肤，申请ID platform-request' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '通过 机构焕肤，申请ID outside-request' })).not.toBeInTheDocument();
  });

  it('refreshes after approval-base stale, retains the conflict, requires a reason, and force-posts latestRevision for admin only', async () => {
    const user = userEvent.setup();
    let changeLoads = 0;
    setContext(adminContext);
    vi.mocked(api.get).mockImplementation(async (url) => {
      if (url === '/admin/project-requests') return response([]);
      if (url === '/v2/admin/institution-project-requests') return response([changeLoads++ === 0 ? v2Request : refreshedV2Request]);
      throw new Error(`Unexpected GET ${url}`);
    });
    vi.mocked(api.post).mockRejectedValueOnce(apiError('APPROVAL_BASE_STALE', '批准基线已变化')).mockResolvedValueOnce(response(null));
    render(<ProjectRequestsPage />);

    await user.click(await screen.findByRole('button', { name: '查看详情 机构焕肤，申请ID v2-request' }));
    await user.click(screen.getByRole('button', { name: '通过 机构焕肤，申请ID v2-request' }));
    expect(await screen.findByText('审核冲突（APPROVAL_BASE_STALE）')).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: '机构焕肤审核详情' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '查看最新差异并强制通过' }));
    expect(screen.getByText('服务器最新项目')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '强制通过' }));
    expect(await screen.findByText('请填写强制通过原因')).toBeInTheDocument();
    await user.type(screen.getByLabelText('强制通过原因'), '已核对并接受最新差异');
    await user.click(screen.getByRole('button', { name: '强制通过' }));
    await waitFor(() => expect(api.post).toHaveBeenLastCalledWith('/v2/admin/institution-project-requests/v2-request/review', {
      decision: 'APPROVED', reviewNote: '已核对并接受最新差异', force: true, forceBaseRevision: 'latest-revision-8',
    }));
  });

  it('never offers force to a legal representative after approval-base stale', async () => {
    const user = userEvent.setup();
    let changeLoads = 0;
    setContext(representativeContext);
    vi.mocked(api.get).mockImplementation(async (url) => url === '/management/project-requests'
      ? response([])
      : response([changeLoads++ === 0 ? v2Request : refreshedV2Request]));
    vi.mocked(api.post).mockRejectedValueOnce(apiError('APPROVAL_BASE_STALE'));
    render(<ProjectRequestsPage />);

    await user.click(await screen.findByRole('button', { name: '通过 机构焕肤，申请ID v2-request' }));
    expect(await screen.findByText('审核冲突（APPROVAL_BASE_STALE）')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '查看最新差异并强制通过' })).not.toBeInTheDocument();
  });

  it('closes stale detail, refreshes, and retains a non-force conflict for other handled stable codes', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockQueues([], [v2Request]);
    vi.mocked(api.post).mockRejectedValueOnce(apiError('PRICING_POLICY_STALE', '定价策略已变化'));
    render(<ProjectRequestsPage />);

    await user.click(await screen.findByRole('button', { name: '查看详情 机构焕肤，申请ID v2-request' }));
    await user.click(screen.getByRole('button', { name: '通过 机构焕肤，申请ID v2-request' }));
    expect(await screen.findByText('审核冲突（PRICING_POLICY_STALE）')).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: '机构焕肤审核详情' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '查看最新差异并强制通过' })).not.toBeInTheDocument();
    expect(api.get).toHaveBeenCalledTimes(3);
  });

  it.each(Object.entries(REVIEW_ERROR_FORCE_ELIGIBILITY))(
    'stable error-code matrix prevents %s from entering force unless it is admin approval stale',
    (errorCode, expectedForAdmin) => {
      expect(isAdminForceEligible(errorCode, true)).toBe(expectedForAdmin);
      expect(isAdminForceEligible(errorCode, false)).toBe(false);
    },
  );
});
