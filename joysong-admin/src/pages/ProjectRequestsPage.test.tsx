import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import api, { type ManagementContext, setAdminToken } from '../api';
import ProjectRequestsPage from './ProjectRequestsPage';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return { ...actual, default: { get: vi.fn(), post: vi.fn() } };
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

const doctorContext: ManagementContext = {
  userId: 'doctor-user-1', platformRole: 'USER', activeRoles: ['DOCTOR'], doctorId: 'doctor-1',
  managedInstitutionIds: [], visibleInstitutionIds: [],
  canManageDoctors: false, canManageInstitutions: false, canManageInstitutionProjects: false,
  canManageArticles: false, canManageSplitConfigs: false, canManageOrders: false,
  canApplyToInstitutions: true, canReviewInstitutionRequests: false,
  canSubmitPlatformProjectRequests: true, canSubmitInstitutionProjectRequests: true,
  canReviewInstitutionProjectRequests: false, canViewAffiliations: true,
};

const representativeContext: ManagementContext = {
  userId: 'legal-1', platformRole: 'USER', activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
  managedInstitutionIds: ['institution-1'], visibleInstitutionIds: ['institution-1'],
  canManageDoctors: false, canManageInstitutions: true, canManageInstitutionProjects: true,
  canManageArticles: false, canManageSplitConfigs: false, canManageOrders: false,
  canApplyToInstitutions: false, canReviewInstitutionRequests: true,
  canSubmitPlatformProjectRequests: false, canSubmitInstitutionProjectRequests: false,
  canReviewInstitutionProjectRequests: true, canViewAffiliations: false,
};

const platformRequest = {
  id: 'platform-request', requestType: 'PLATFORM', doctorId: 'doctor-1', doctorName: '张医生',
  institutionId: null, institutionName: null, projectId: null, projectName: null,
  name: '光子焕肤', category: '皮肤', description: '完整平台项目说明',
  tags: ['舒适', '午休'], slogan: '午休也能焕新', detailContent: '完整的平台详情正文',
  currency: 'USD', coverImage: 'https://img.test/platform-cover.jpg',
  images: ['https://img.test/platform-1.jpg', 'https://img.test/platform-2.jpg'], salesCount: 12,
  referencePrice: 399.5, categoryTags: ['光电', '面部'], price: null, originalPrice: null,
  isActive: null, institutionSplit: null, notes: '平台申请备注', status: 'PENDING',
  reviewNote: null, reviewedBy: null, reviewedAt: null,
  resultingProjectId: null, resultingInstitutionProjectId: null,
  submittedAt: '2026-08-16T09:10:00', updatedAt: '2026-08-16T09:10:00',
};

const institutionRequest = {
  id: 'institution-request', requestType: 'INSTITUTION', doctorId: 'doctor-1', doctorName: '张医生',
  institutionId: 'institution-1', institutionName: '示例机构',
  projectId: 'project-1', projectName: '基础光子', name: '机构定制光子', category: '皮肤管理',
  description: '完整机构项目说明', tags: ['机构专享'], slogan: '定制焕肤',
  detailContent: '完整的机构项目详情', currency: 'CNY',
  coverImage: 'https://img.test/institution-cover.jpg',
  images: ['https://img.test/institution-1.jpg', 'https://img.test/institution-2.jpg'], salesCount: 8,
  referencePrice: null, categoryTags: null, price: 1200, originalPrice: 1680, isActive: true,
  institutionSplit: {
    consultationFee: 100, commissionRate: 12.5, institutionRate: 32.5,
    platformRate: 10, doctorRate: 45,
  },
  notes: '机构申请备注', status: 'PENDING', reviewNote: null, reviewedBy: null, reviewedAt: null,
  resultingProjectId: null, resultingInstitutionProjectId: null,
  submittedAt: '2026-08-16T10:20:00', updatedAt: '2026-08-16T10:20:00',
};

const joinRequest = {
  id: 'join-request', requestType: 'JOIN', doctorId: 'doctor-2', doctorName: '王医生',
  institutionId: 'institution-1', institutionName: '示例机构', institutionProjectId: 'ip-1',
  projectName: '已有机构项目', serviceDescription: '个性化治疗', priceSuggestion: 1500,
  notes: '周二出诊', serviceTags: ['精细'], scheduleNote: '每周二', coverImage: '', images: [],
  status: 'PENDING', reviewNote: '', submittedAt: '2026-08-15T09:00:00',
};

function setContext(context: ManagementContext) {
  setAdminToken('header.payload.signature', context);
}

function mockLists(professional: unknown[] = [], joins: unknown[] = []) {
  vi.mocked(api.get).mockImplementation(async (url) => ({ data: {
    code: 200,
    message: 'OK',
    data: url === '/admin/project-requests' || url === '/management/project-requests'
      ? professional
      : joins,
  } }));
  vi.mocked(api.post).mockResolvedValue({ data: { code: 200, message: 'OK', data: null } });
}

function rowFor(text: string) {
  const row = screen.getByText(text).closest('tr');
  if (!row) throw new Error(`row not found for ${text}`);
  return row;
}

function expandRow(text: string) {
  const button = rowFor(text).querySelector<HTMLButtonElement>('button.ant-table-row-expand-icon');
  if (!button) throw new Error(`expand button not found for ${text}`);
  fireEvent.click(button);
}

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

describe('ProjectRequestsPage', () => {
  it('renders complete immutable platform and institution snapshots with response currencies and nested split values', async () => {
    setContext(adminContext);
    mockLists([platformRequest, institutionRequest]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('光子焕肤')).toBeInTheDocument();
    expect(screen.getByText('USD 399.5')).toBeInTheDocument();
    expect(screen.getByText('CNY 1200')).toBeInTheDocument();
    expandRow('光子焕肤');
    expandRow('机构定制光子');

    for (const text of [
      '完整平台项目说明', '舒适、午休', '午休也能焕新', '完整的平台详情正文',
      'https://img.test/platform-cover.jpg', 'https://img.test/platform-1.jpg',
      'https://img.test/platform-2.jpg', '12', '光电、面部', '平台申请备注',
      '完整机构项目说明', '机构专享', '定制焕肤', '完整的机构项目详情',
      'https://img.test/institution-cover.jpg', 'https://img.test/institution-1.jpg',
      'https://img.test/institution-2.jpg', '8', 'CNY 1680', '是',
      'CNY 100', '12.5%', '32.5%', '10%', '45%', '机构申请备注',
      '2026-08-16T09:10:00', '2026-08-16T10:20:00',
    ]) {
      expect(screen.getAllByText(text).length).toBeGreaterThan(0);
    }
    expect(screen.queryByText(/评分/)).not.toBeInTheDocument();
    expect(screen.queryByText(/评价数/)).not.toBeInTheDocument();
    expect(screen.queryByText(/选择医生/)).not.toBeInTheDocument();
  });

  it('offers only approve and reject for professional creation requests and uses the two exact review routes', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockLists([platformRequest, institutionRequest]);

    render(<ProjectRequestsPage />);
    await screen.findByText('光子焕肤');

    expect(screen.getAllByRole('button', { name: '通过' })).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: '驳回' })).toHaveLength(2);
    expect(screen.queryByRole('button', { name: '要求修改' })).not.toBeInTheDocument();

    await user.click(within(rowFor('光子焕肤')).getByRole('button', { name: '通过' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/admin/project-requests/platform-request/review',
      { decision: 'APPROVED', reviewNote: '' },
    ));

    await user.click(within(rowFor('机构定制光子')).getByRole('button', { name: '通过' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/management/project-requests/institution-request/review',
      { decision: 'APPROVED', reviewNote: '' },
    ));
  });

  it('requires a nonblank rejection note and sends exactly the professional reject body', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockLists([platformRequest]);

    render(<ProjectRequestsPage />);
    await user.click(await screen.findByRole('button', { name: '驳回' }));
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));
    expect(api.post).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText('审核意见'), ' 快照信息不完整 ');
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    expect(api.post).toHaveBeenCalledWith('/admin/project-requests/platform-request/review', {
      decision: 'REJECTED',
      reviewNote: ' 快照信息不完整 ',
    });
  });

  it('renders supplied applicant rows without review actions', async () => {
    setContext(doctorContext);
    mockLists([platformRequest, institutionRequest]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('光子焕肤')).toBeInTheDocument();
    expect(screen.getByText('机构定制光子')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '通过' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '驳回' })).not.toBeInTheDocument();
  });

  it('lets a target legal representative review only its scoped institution row through management', async () => {
    const user = userEvent.setup();
    setContext(representativeContext);
    mockLists([institutionRequest]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('机构定制光子')).toBeInTheDocument();
    expect(screen.queryByText('光子焕肤')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '要求修改' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '通过' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/management/project-requests/institution-request/review',
      { decision: 'APPROVED', reviewNote: '' },
    ));
  });

  it('renders no row or action for a non-target legal representative scoped response', async () => {
    setContext({
      ...representativeContext,
      userId: 'legal-2',
      managedInstitutionIds: ['institution-2'],
      visibleInstitutionIds: ['institution-2'],
    });
    mockLists([]);

    render(<ProjectRequestsPage />);

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/management/project-requests'));
    expect(screen.queryByText('机构定制光子')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '通过' })).not.toBeInTheDocument();
  });

  it('preserves legacy JOIN rendering, three-decision review, and route', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockLists([], [joinRequest]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('已有机构项目')).toBeInTheDocument();
    expect(screen.getByText('个性化治疗')).toBeInTheDocument();
    expect(screen.getByText('¥1500')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '通过' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '要求修改' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '驳回' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '要求修改' }));
    await user.type(screen.getByLabelText('审核意见'), '请补充排班');
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/admin/institution-project-requests/join-request/review',
      { decision: 'CHANGES_REQUESTED', reviewNote: '请补充排班' },
    ));
  });
});
