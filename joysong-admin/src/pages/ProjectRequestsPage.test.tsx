import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import api, { type ManagementContext, setAdminToken } from '../api';
import ProjectRequestsPage, { type ProfessionalProjectRequestResponse } from './ProjectRequestsPage';

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
} satisfies ProfessionalProjectRequestResponse;

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
} satisfies ProfessionalProjectRequestResponse;

const representativeRequest = {
  ...institutionRequest,
  name: null,
  category: null,
  description: null,
  tags: [],
  slogan: null,
  detailContent: null,
  coverImage: null,
  images: [],
  originalPrice: null,
  notes: null,
} satisfies ProfessionalProjectRequestResponse;

const joinRequest = {
  id: 'join-request', requestType: 'JOIN', doctorId: 'doctor-2', doctorName: '王医生',
  institutionId: 'institution-1', institutionName: '示例机构', institutionProjectId: 'ip-1',
  projectName: '已有机构项目', serviceDescription: '个性化治疗', priceSuggestion: 1500,
  notes: '周二出诊', serviceTags: ['精细'], scheduleNote: '每周二', coverImage: '', images: [],
  status: 'PENDING', reviewNote: '', submittedAt: '2026-08-15T09:00:00',
};

const malformedProfessionalCases: [string, unknown][] = [
  ['missing common doctor ID', withoutKey(platformRequest, 'doctorId')],
  ['missing common status', withoutKey(platformRequest, 'status')],
  ['missing common submitted time', withoutKey(platformRequest, 'submittedAt')],
  ['missing common currency', withoutKey(platformRequest, 'currency')],
  ['missing common sales count', withoutKey(platformRequest, 'salesCount')],
  ['missing platform name', withoutKey(platformRequest, 'name')],
  ['missing platform category', withoutKey(platformRequest, 'category')],
  ['missing platform description', withoutKey(platformRequest, 'description')],
  ['missing platform reference price', withoutKey(platformRequest, 'referencePrice')],
  ['missing institution ID', withoutKey(institutionRequest, 'institutionId')],
  ['missing platform-project ID', withoutKey(institutionRequest, 'projectId')],
  ['missing institution price', withoutKey(institutionRequest, 'price')],
  ['missing institution active flag', withoutKey(institutionRequest, 'isActive')],
  ['missing institution split', withoutKey(institutionRequest, 'institutionSplit')],
  ['missing derived doctor rate', {
    ...institutionRequest,
    institutionSplit: withoutKey(institutionRequest.institutionSplit, 'doctorRate'),
  }],
];

function setContext(context: ManagementContext) {
  setAdminToken('header.payload.signature', context);
}

type ProfessionalListPath = '/admin/project-requests' | '/management/project-requests';

function mockLists(
  professionalPath: ProfessionalListPath,
  professional: unknown[] = [],
  joins: unknown[] = [],
) {
  vi.mocked(api.get).mockImplementation(async (url) => {
    let data: unknown[];
    switch (url) {
      case professionalPath:
        data = professional;
        break;
      case '/admin/institution-project-requests':
        data = joins;
        break;
      default:
        throw new Error(`Unexpected GET ${url}`);
    }
    return { data: { code: 200, message: 'OK', data } };
  });
  vi.mocked(api.post).mockResolvedValue({ data: { code: 200, message: 'OK', data: null } });
}

async function expectListPair(professionalPath: ProfessionalListPath) {
  await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
  expect(api.get).toHaveBeenNthCalledWith(1, professionalPath);
  expect(api.get).toHaveBeenNthCalledWith(2, '/admin/institution-project-requests');
}

function rowFor(text: string) {
  const row = screen.getAllByText(text)
    .map(element => element.closest('tr'))
    .find(element => element?.classList.contains('ant-table-row'));
  if (!row) throw new Error(`row not found for ${text}`);
  return row;
}

function expandRow(text: string) {
  const row = rowFor(text);
  const button = row.querySelector<HTMLButtonElement>('button.ant-table-row-expand-icon');
  if (!button) throw new Error(`expand button not found for ${text}`);
  fireEvent.click(button);
  const expandedRow = row.nextElementSibling;
  if (!(expandedRow instanceof HTMLElement)) throw new Error(`expanded detail not found for ${text}`);
  return within(expandedRow);
}

function currentOrExpandedDetail(text: string) {
  const row = rowFor(text);
  const expandedRow = row.nextElementSibling;
  return expandedRow instanceof HTMLElement && expandedRow.classList.contains('ant-table-expanded-row')
    ? within(expandedRow)
    : expandRow(text);
}

function descriptionItem(detail: ReturnType<typeof within>, label: string) {
  const item = detail.getByText(label).closest('.ant-descriptions-item') as HTMLElement | null;
  if (!item) throw new Error(`description item not found for ${label}`);
  return within(item);
}

function expectDescriptionValue(detail: ReturnType<typeof within>, label: string, value: string) {
  expect(descriptionItem(detail, label).getByText(value)).toBeInTheDocument();
}

function withoutKey<T extends object, K extends keyof T>(value: T, key: K): Omit<T, K> {
  const copy = { ...value };
  delete copy[key];
  return copy;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
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
    mockLists('/admin/project-requests', [platformRequest, institutionRequest]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('光子焕肤')).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    expect(within(rowFor('光子焕肤')).getByText('USD 399.5')).toBeInTheDocument();
    expect(within(rowFor('机构定制光子')).getByText('CNY 1200')).toBeInTheDocument();
    const platformDetail = expandRow('光子焕肤');
    const institutionDetail = expandRow('机构定制光子');

    expectDescriptionValue(platformDetail, '申请医生 ID（不可变）', 'doctor-1');
    expectDescriptionValue(platformDetail, '当前医生名称', '张医生');
    expectDescriptionValue(platformDetail, '项目说明', '完整平台项目说明');
    expectDescriptionValue(platformDetail, '标签', '舒适、午休');
    expectDescriptionValue(platformDetail, '宣传语', '午休也能焕新');
    expectDescriptionValue(platformDetail, '详情内容', '完整的平台详情正文');
    expectDescriptionValue(platformDetail, '封面图', 'https://img.test/platform-cover.jpg');
    expect(descriptionItem(platformDetail, '项目图集').getByText('https://img.test/platform-1.jpg')).toBeInTheDocument();
    expect(descriptionItem(platformDetail, '项目图集').getByText('https://img.test/platform-2.jpg')).toBeInTheDocument();
    expectDescriptionValue(platformDetail, '销量', '12');
    expectDescriptionValue(platformDetail, '分类标签', '光电、面部');
    expectDescriptionValue(platformDetail, '补充说明', '平台申请备注');
    expectDescriptionValue(platformDetail, '提交时间', '2026-08-16T09:10:00');

    expectDescriptionValue(institutionDetail, '申请医生 ID（不可变）', 'doctor-1');
    expectDescriptionValue(institutionDetail, '当前医生名称', '张医生');
    expectDescriptionValue(institutionDetail, '目标机构 ID（不可变）', 'institution-1');
    expectDescriptionValue(institutionDetail, '当前机构名称', '示例机构');
    expectDescriptionValue(institutionDetail, '目标平台项目 ID（不可变）', 'project-1');
    expectDescriptionValue(institutionDetail, '当前平台项目名称', '基础光子');
    expectDescriptionValue(institutionDetail, '项目说明', '完整机构项目说明');
    expectDescriptionValue(institutionDetail, '标签', '机构专享');
    expectDescriptionValue(institutionDetail, '宣传语', '定制焕肤');
    expectDescriptionValue(institutionDetail, '详情内容', '完整的机构项目详情');
    expectDescriptionValue(institutionDetail, '封面图', 'https://img.test/institution-cover.jpg');
    expect(descriptionItem(institutionDetail, '项目图集').getByText('https://img.test/institution-1.jpg')).toBeInTheDocument();
    expect(descriptionItem(institutionDetail, '项目图集').getByText('https://img.test/institution-2.jpg')).toBeInTheDocument();
    expectDescriptionValue(institutionDetail, '销量', '8');
    expectDescriptionValue(institutionDetail, '原价', 'CNY 1680');
    expectDescriptionValue(institutionDetail, '是否上架', '是');
    expectDescriptionValue(institutionDetail, '面诊费', 'CNY 100');
    expectDescriptionValue(institutionDetail, '顾问分成', '12.5%');
    expectDescriptionValue(institutionDetail, '机构分成', '32.5%');
    expectDescriptionValue(institutionDetail, '当前平台比例', '10%');
    expectDescriptionValue(institutionDetail, '按当前平台比例推导的医生净比例', '45%');
    expectDescriptionValue(institutionDetail, '补充说明', '机构申请备注');
    expectDescriptionValue(institutionDetail, '提交时间', '2026-08-16T10:20:00');

    expect(platformDetail.queryByText(/评分|评价数|选择医生/)).not.toBeInTheDocument();
    expect(institutionDetail.queryByText(/评分|评价数|选择医生/)).not.toBeInTheDocument();
  });

  it.each(malformedProfessionalCases)('fails closed for malformed professional snapshot: %s', async (_caseName, malformed) => {
    setContext(adminContext);
    mockLists('/admin/project-requests', [malformed]);

    render(<ProjectRequestsPage />);

    await expectListPair('/admin/project-requests');
    expect(await screen.findByText('申请快照数据不完整，已禁止审核')).toBeInTheDocument();
    expect(screen.getByText(/请刷新页面；若问题持续存在，请联系技术人员/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^通过 / })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^驳回 / })).not.toBeInTheDocument();
  });

  it('offers only approve and reject for professional creation requests and uses the two exact review routes', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockLists('/admin/project-requests', [platformRequest, institutionRequest]);

    render(<ProjectRequestsPage />);
    await screen.findByText('光子焕肤');
    await expectListPair('/admin/project-requests');

    expect(screen.getByRole('button', { name: '通过 光子焕肤，申请ID platform-request' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '驳回 光子焕肤，申请ID platform-request' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '驳回 机构定制光子，申请ID institution-request' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^要求修改 / })).not.toBeInTheDocument();

    await user.click(within(rowFor('光子焕肤')).getByRole('button', { name: '通过 光子焕肤，申请ID platform-request' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/admin/project-requests/platform-request/review',
      { decision: 'APPROVED', reviewNote: '' },
    ));

    await user.click(within(rowFor('机构定制光子')).getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/management/project-requests/institution-request/review',
      { decision: 'APPROVED', reviewNote: '' },
    ));
  });

  it('requires a nonblank rejection note and sends exactly the professional reject body', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockLists('/admin/project-requests', [platformRequest]);

    render(<ProjectRequestsPage />);
    await expectListPair('/admin/project-requests');
    await user.click(await screen.findByRole('button', { name: '驳回 光子焕肤，申请ID platform-request' }));
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

  it('synchronously blocks approve-then-reject re-entry and disables every creation review action while pending', async () => {
    setContext(adminContext);
    mockLists('/admin/project-requests', [platformRequest, institutionRequest]);
    const pendingPost = deferred<unknown>();
    vi.mocked(api.post).mockReturnValueOnce(pendingPost.promise as any);

    render(<ProjectRequestsPage />);
    await screen.findByText('光子焕肤');
    await expectListPair('/admin/project-requests');
    const approve = screen.getByRole('button', { name: '通过 光子焕肤，申请ID platform-request' });
    const reject = screen.getByRole('button', { name: '驳回 光子焕肤，申请ID platform-request' });

    act(() => {
      approve.click();
      reject.click();
    });

    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post).toHaveBeenCalledWith('/admin/project-requests/platform-request/review', {
      decision: 'APPROVED', reviewNote: '',
    });
    expect(screen.queryByLabelText('审核意见')).not.toBeInTheDocument();
    for (const button of screen.getAllByRole('button', { name: /^(通过|驳回) / })) {
      expect(button).toBeDisabled();
    }

    await act(async () => {
      pendingPost.resolve({ data: { code: 200, message: 'OK', data: null } });
      await pendingPost.promise;
    });
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(4));
    await waitFor(() => expect(approve).not.toBeDisabled());
    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it('refreshes both scoped lists on a real 409 and displays updated current split values without reposting', async () => {
    const user = userEvent.setup();
    const updatedInstitutionRequest = {
      ...institutionRequest,
      institutionSplit: {
        ...institutionRequest.institutionSplit,
        platformRate: 12,
        doctorRate: 43,
      },
    } satisfies ProfessionalProjectRequestResponse;
    setContext(adminContext);
    let professionalReadCount = 0;
    vi.mocked(api.get).mockImplementation(async (url) => {
      let data: unknown[];
      switch (url) {
        case '/admin/project-requests':
          professionalReadCount += 1;
          data = [professionalReadCount === 1 ? institutionRequest : updatedInstitutionRequest];
          break;
        case '/admin/institution-project-requests':
          data = [];
          break;
        default:
          throw new Error(`Unexpected GET ${url}`);
      }
      return { data: { code: 200, message: 'OK', data } };
    });
    const conflict = Object.assign(new Error('申请已被其他审核人处理'), {
      response: { status: 409, data: { message: '申请已被其他审核人处理' } },
    });
    vi.mocked(api.post).mockRejectedValueOnce(conflict);

    render(<ProjectRequestsPage />);
    expect(await screen.findByText('机构定制光子')).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    const initialDetail = expandRow('机构定制光子');
    expectDescriptionValue(initialDetail, '当前平台比例', '10%');
    expectDescriptionValue(initialDetail, '按当前平台比例推导的医生净比例', '45%');

    await user.click(screen.getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' }));

    expect(await screen.findByText('审核状态或审批基线已变化，已刷新最新数据，请核对当前比例和申请状态后重试')).toBeInTheDocument();
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(4));
    expect(api.get).toHaveBeenNthCalledWith(3, '/admin/project-requests');
    expect(api.get).toHaveBeenNthCalledWith(4, '/admin/institution-project-requests');
    const refreshedDetail = currentOrExpandedDetail('机构定制光子');
    expectDescriptionValue(refreshedDetail, '当前平台比例', '12%');
    expectDescriptionValue(refreshedDetail, '按当前平台比例推导的医生净比例', '43%');
    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it('renders supplied applicant rows without review actions', async () => {
    setContext(doctorContext);
    mockLists('/management/project-requests', [platformRequest, institutionRequest]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('光子焕肤')).toBeInTheDocument();
    await expectListPair('/management/project-requests');
    expect(screen.getByText('机构定制光子')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^通过 / })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^驳回 / })).not.toBeInTheDocument();
  });

  it('lets a target legal representative review only its scoped institution row through management', async () => {
    const user = userEvent.setup();
    setContext(representativeContext);
    mockLists('/management/project-requests', [representativeRequest]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('基础光子')).toBeInTheDocument();
    await expectListPair('/management/project-requests');
    expect(screen.queryByText('光子焕肤')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^要求修改 / })).not.toBeInTheDocument();
    const representativeDetail = expandRow('基础光子');
    expectDescriptionValue(representativeDetail, '项目名称', '-');
    expectDescriptionValue(representativeDetail, '分类', '-');
    expectDescriptionValue(representativeDetail, '项目说明', '-');
    expectDescriptionValue(representativeDetail, '标签', '-');
    expectDescriptionValue(representativeDetail, '宣传语', '-');
    expectDescriptionValue(representativeDetail, '详情内容', '-');
    expectDescriptionValue(representativeDetail, '封面图', '-');
    expectDescriptionValue(representativeDetail, '项目图集', '-');
    expectDescriptionValue(representativeDetail, '原价', '-');
    expectDescriptionValue(representativeDetail, '补充说明', '-');
    await user.click(screen.getByRole('button', { name: '通过 项目名称留空，使用继承值，申请ID institution-request' }));
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
    mockLists('/management/project-requests');

    render(<ProjectRequestsPage />);

    await expectListPair('/management/project-requests');
    expect(screen.queryByText('机构定制光子')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^通过 / })).not.toBeInTheDocument();
  });

  it('preserves legacy JOIN rendering, three-decision review, and route', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockLists('/admin/project-requests', [], [joinRequest]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('已有机构项目')).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    expect(screen.getByText('个性化治疗')).toBeInTheDocument();
    expect(screen.getByText('¥1500')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '通过 已有机构项目，申请ID join-request' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '要求修改 已有机构项目，申请ID join-request' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '驳回 已有机构项目，申请ID join-request' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '要求修改 已有机构项目，申请ID join-request' }));
    await user.type(screen.getByLabelText('审核意见'), '请补充排班');
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/admin/institution-project-requests/join-request/review',
      { decision: 'CHANGES_REQUESTED', reviewNote: '请补充排班' },
    ));
  });
});
