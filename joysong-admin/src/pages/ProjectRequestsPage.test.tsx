import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import api, { type ManagementContext, setAdminToken } from '../api';
import ProjectRequestsPage from './ProjectRequestsPage';
import {
  type InstitutionProjectSplit,
  type ProfessionalProjectRequestResponse,
} from '../types/projectRequests';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return { ...actual, default: { get: vi.fn(), post: vi.fn() } };
});

vi.mock('../types/projectRequests', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../types/projectRequests')>();
  return {
    ...actual,
    isCompleteProfessionalProjectRequest: (value: unknown) => {
      if (value && typeof value === 'object' && 'sharedValidatorBoundary' in value) {
        return (value as { sharedValidatorBoundary?: unknown }).sharedValidatorBoundary === true;
      }
      return actual.isCompleteProfessionalProjectRequest(value);
    },
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

const profileUpdateRequest = {
  id: 'profile-request', requestType: 'PROFILE_UPDATE', doctorId: 'doctor-2', doctorName: '王医生',
  institutionId: 'institution-1', institutionName: '示例机构', institutionProjectId: 'ip-1',
  projectName: '已有机构项目', serviceDescription: '更新后的服务介绍', priceSuggestion: 1600,
  notes: '调整医疗套餐标价', serviceTags: ['精细'], scheduleNote: '每周三', coverImage: '', images: [],
  consultationFee: 100, commissionRate: 10, institutionRate: 35,
  medicalListPrice: 1200, platformRate: 40, doctorRate: 15,
  currentPrice: 1500, currentServiceDescription: '原服务介绍', currentServiceTags: ['自然'],
  currentScheduleNote: '每周二', currentCoverImage: '', currentImages: [],
  currentConsultationFee: 100, currentCommissionRate: 10, currentInstitutionRate: 35,
  currentMedicalListPrice: 1000, currentPlatformRate: 38, currentDoctorRate: 17,
  forceProcessed: false, status: 'PENDING', reviewNote: '', submittedAt: '2026-08-15T10:00:00',
};

const leaveRequest = {
  ...joinRequest,
  id: 'leave-request', requestType: 'LEAVE', projectName: '退出项目', serviceDescription: '',
  priceSuggestion: null, notes: '停止合作',
};

const maximumValidTags = [
  't'.repeat(100),
  ...Array.from({ length: 18 }, () => 't'.repeat(20)),
  't'.repeat(21),
];
const maximumValidImages = [
  'i'.repeat(500),
  ...Array.from({ length: 18 }, () => 'i'.repeat(78)),
  'i'.repeat(77),
];

const malformedProfessionalCases: [string, unknown][] = [
  ['boundary: request ID exceeds VARCHAR(36)', { ...platformRequest, id: 'i'.repeat(37) }],
  ['boundary: doctor ID exceeds VARCHAR(36)', { ...platformRequest, doctorId: 'd'.repeat(37) }],
  ['boundary: resulting project ID is whitespace', { ...platformRequest, resultingProjectId: '   ' }],
  ['boundary: current doctor name exceeds VARCHAR(100)', { ...platformRequest, doctorName: '医'.repeat(101) }],
  ['missing common doctor ID', withoutKey(platformRequest, 'doctorId')],
  ['missing common status', withoutKey(platformRequest, 'status')],
  ['boundary: unsupported status', { ...platformRequest, status: 'WITHDRAWN' }],
  ['missing common submitted time', withoutKey(platformRequest, 'submittedAt')],
  ['boundary: submitted time is not a valid calendar time', { ...platformRequest, submittedAt: '2026-02-30T25:61:00' }],
  ['boundary: submitted time includes a timezone', { ...platformRequest, submittedAt: '2026-08-16T09:10:00Z' }],
  ['boundary: updated time is not ISO local datetime', { ...platformRequest, updatedAt: '2026/08/16 09:10:00' }],
  ['boundary: reviewed time is invalid', { ...platformRequest, reviewedAt: '2026-13-01T09:10:00' }],
  ['missing common currency', withoutKey(platformRequest, 'currency')],
  ['unsupported common currency', { ...platformRequest, currency: 'EUR' }],
  ['blank common currency', { ...platformRequest, currency: '' }],
  ['non-string common currency', { ...platformRequest, currency: 123 }],
  ['missing common sales count', withoutKey(platformRequest, 'salesCount')],
  ['sales count exceeds signed Int32', { ...platformRequest, salesCount: 2_147_483_648 }],
  ['missing platform name', withoutKey(platformRequest, 'name')],
  ['boundary: platform name is whitespace', { ...platformRequest, name: '   ' }],
  ['boundary: platform name exceeds 200 characters', { ...platformRequest, name: 'n'.repeat(201) }],
  ['missing platform category', withoutKey(platformRequest, 'category')],
  ['boundary: platform category exceeds 100 characters', { ...platformRequest, category: 'c'.repeat(101) }],
  ['missing platform description', withoutKey(platformRequest, 'description')],
  ['boundary: platform description exceeds 5000 characters', { ...platformRequest, description: 'd'.repeat(5_001) }],
  ['boundary: platform slogan exceeds 500 characters', { ...platformRequest, slogan: 's'.repeat(501) }],
  ['boundary: platform detail exceeds 20000 characters', { ...platformRequest, detailContent: 'd'.repeat(20_001) }],
  ['boundary: platform cover image exceeds 500 characters', { ...platformRequest, coverImage: 'c'.repeat(501) }],
  ['boundary: notes exceed 2000 characters', { ...platformRequest, notes: 'n'.repeat(2_001) }],
  ['boundary: review note is whitespace', { ...platformRequest, reviewNote: '   ' }],
  ['boundary: review note exceeds VARCHAR(1000)', { ...platformRequest, reviewNote: 'r'.repeat(1_001) }],
  ['boundary: platform tags exceed 20 items', { ...platformRequest, tags: Array.from({ length: 21 }, (_, index) => `tag-${index}`) }],
  ['boundary: platform tag item is whitespace', { ...platformRequest, tags: ['   '] }],
  ['boundary: platform tag item exceeds 100 characters', { ...platformRequest, tags: ['t'.repeat(101)] }],
  ['boundary: platform tag join exceeds 500 characters', { ...platformRequest, tags: Array.from({ length: 6 }, () => 't'.repeat(84)) }],
  ['boundary: platform category tags exceed 20 items', { ...platformRequest, categoryTags: Array.from({ length: 21 }, (_, index) => `category-${index}`) }],
  ['boundary: platform images exceed 20 items', { ...platformRequest, images: Array.from({ length: 21 }, (_, index) => `image-${index}`) }],
  ['boundary: platform image item is whitespace', { ...platformRequest, images: ['   '] }],
  ['boundary: platform image item exceeds 500 characters', { ...platformRequest, images: ['i'.repeat(501)] }],
  ['boundary: platform image join exceeds 2000 characters', { ...platformRequest, images: Array.from({ length: 5 }, () => 'i'.repeat(400)) }],
  ['boundary: numeric string is not a JSON number', { ...platformRequest, referencePrice: '399.5' }],
  ['missing platform reference price', withoutKey(platformRequest, 'referencePrice')],
  ['negative platform reference price', { ...platformRequest, referencePrice: -0.01 }],
  ['platform reference price exceeds DECIMAL(10,2)', { ...platformRequest, referencePrice: 100_000_000 }],
  ['platform reference price exceeds two decimals', { ...platformRequest, referencePrice: 399.501 }],
  ['missing institution ID', withoutKey(institutionRequest, 'institutionId')],
  ['boundary: institution ID exceeds VARCHAR(36)', { ...institutionRequest, institutionId: 'i'.repeat(37) }],
  ['boundary: current institution name exceeds VARCHAR(200)', { ...institutionRequest, institutionName: '机'.repeat(201) }],
  ['missing platform-project ID', withoutKey(institutionRequest, 'projectId')],
  ['boundary: platform-project ID exceeds VARCHAR(36)', { ...institutionRequest, projectId: 'p'.repeat(37) }],
  ['boundary: current platform-project name exceeds VARCHAR(200)', { ...institutionRequest, projectName: '项'.repeat(201) }],
  ['boundary: nullable institution name override is whitespace', { ...institutionRequest, name: '   ' }],
  ['boundary: nullable institution description exceeds 5000 characters', { ...institutionRequest, description: 'd'.repeat(5_001) }],
  ['boundary: institution tags exceed 20 items', { ...institutionRequest, tags: Array.from({ length: 21 }, (_, index) => `tag-${index}`) }],
  ['boundary: institution image join exceeds 2000 characters', { ...institutionRequest, images: Array.from({ length: 5 }, () => 'i'.repeat(400)) }],
  ['missing institution price', withoutKey(institutionRequest, 'price')],
  ['negative institution price', { ...institutionRequest, price: -0.01 }],
  ['institution price exceeds DECIMAL(10,2)', { ...institutionRequest, price: 100_000_000 }],
  ['institution price exceeds two decimals', { ...institutionRequest, price: 1200.001 }],
  ['negative institution original price', { ...institutionRequest, originalPrice: -0.01 }],
  ['institution original price exceeds DECIMAL(10,2)', { ...institutionRequest, originalPrice: 100_000_000 }],
  ['institution original price exceeds two decimals', { ...institutionRequest, originalPrice: 1680.001 }],
  ['missing institution active flag', withoutKey(institutionRequest, 'isActive')],
  ['institution includes contradictory reference price', { ...institutionRequest, referencePrice: 1200 }],
  ['institution includes contradictory category tags', { ...institutionRequest, categoryTags: ['不应出现'] }],
  ['missing institution split', withoutKey(institutionRequest, 'institutionSplit')],
  ['missing derived doctor rate', {
    ...institutionRequest,
    institutionSplit: withoutKey(institutionRequest.institutionSplit, 'doctorRate'),
  }],
  ['negative consultation fee', institutionWithSplit({ consultationFee: -0.01 })],
  ['consultation fee exceeds DECIMAL(10,2)', institutionWithSplit({ consultationFee: 100_000_000 })],
  ['consultation fee exceeds two decimals', institutionWithSplit({ consultationFee: 100.001 })],
  ['negative commission rate', institutionWithSplit({ commissionRate: -0.01, doctorRate: 57.51 })],
  ['institution rate exceeds 100', institutionWithSplit({ institutionRate: 100.01, doctorRate: -22.51 })],
  ['platform rate exceeds 100', institutionWithSplit({ platformRate: 100.01, doctorRate: -45.01 })],
  ['doctor rate is below negative 100', institutionWithSplit({
    commissionRate: 100,
    institutionRate: 90.01,
    platformRate: 10,
    doctorRate: -100.01,
  })],
  ['rate exceeds two decimals', institutionWithSplit({ commissionRate: 12.345, doctorRate: 45.155 })],
  ['four rates do not total exactly 100', institutionWithSplit({ doctorRate: 44.99 })],
  ['submitted commission and institution rates exceed 100 despite a total of 100', institutionWithSplit({
    commissionRate: 60,
    institutionRate: 60,
    platformRate: 0,
    doctorRate: -20,
  })],
];

function institutionWithSplit(overrides: Partial<InstitutionProjectSplit>) {
  return {
    ...institutionRequest,
    institutionSplit: { ...institutionRequest.institutionSplit, ...overrides },
  } satisfies ProfessionalProjectRequestResponse;
}

const validMoneyBoundaryCases = [
  {
    caseName: 'maximum platform reference price',
    request: { ...platformRequest, referencePrice: 99_999_999.99 } satisfies ProfessionalProjectRequestResponse,
    rowName: '光子焕肤',
    detailLabel: '参考价格',
    detailValue: 'USD 99999999.99',
    approveLabel: '通过 光子焕肤，申请ID platform-request',
  },
  {
    caseName: 'maximum institution price',
    request: { ...institutionRequest, price: 99_999_999.99 } satisfies ProfessionalProjectRequestResponse,
    rowName: '机构定制光子',
    detailLabel: '价格',
    detailValue: 'CNY 99999999.99',
    approveLabel: '通过 机构定制光子，申请ID institution-request',
  },
  {
    caseName: 'maximum institution original price',
    request: { ...institutionRequest, originalPrice: 99_999_999.99 } satisfies ProfessionalProjectRequestResponse,
    rowName: '机构定制光子',
    detailLabel: '原价',
    detailValue: 'CNY 99999999.99',
    approveLabel: '通过 机构定制光子，申请ID institution-request',
  },
  {
    caseName: 'nullable institution original price',
    request: { ...institutionRequest, originalPrice: null } satisfies ProfessionalProjectRequestResponse,
    rowName: '机构定制光子',
    detailLabel: '原价',
    detailValue: '-',
    approveLabel: '通过 机构定制光子，申请ID institution-request',
  },
] as const;

const validSnapshotInvariantCases = [
  {
    caseName: 'USD currency',
    request: { ...platformRequest, currency: 'USD' } satisfies ProfessionalProjectRequestResponse,
    rowName: '光子焕肤',
    approveLabel: '通过 光子焕肤，申请ID platform-request',
  },
  {
    caseName: 'CNY currency',
    request: { ...institutionRequest, currency: 'CNY' } satisfies ProfessionalProjectRequestResponse,
    rowName: '机构定制光子',
    approveLabel: '通过 机构定制光子，申请ID institution-request',
  },
  {
    caseName: 'submitted commission and institution rates total exactly 100',
    request: institutionWithSplit({
      commissionRate: 60,
      institutionRate: 40,
      platformRate: 0,
      doctorRate: 0,
    }),
    rowName: '机构定制光子',
    approveLabel: '通过 机构定制光子，申请ID institution-request',
  },
] as const;

const maximumBoundaryPlatformRequest = {
  ...platformRequest,
  id: 'i'.repeat(36),
  doctorId: 'd'.repeat(36),
  doctorName: '医'.repeat(100),
  name: 'n'.repeat(200),
  category: 'c'.repeat(100),
  description: 'd'.repeat(5_000),
  tags: maximumValidTags,
  slogan: 's'.repeat(500),
  detailContent: 'd'.repeat(20_000),
  coverImage: 'c'.repeat(500),
  images: maximumValidImages,
  categoryTags: maximumValidTags,
  notes: 'n'.repeat(2_000),
  submittedAt: '2026-08-16T09:10:00.123456789',
  updatedAt: '2026-08-16T09:10:01',
} satisfies ProfessionalProjectRequestResponse;

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
  it('uses the shared professional request validator as its list boundary', async () => {
    setContext(adminContext);
    mockLists('/admin/project-requests', [{
      ...platformRequest,
      id: 'shared-validator-request',
      name: '共享校验器边界项目',
      currency: 'SHARED_BOUNDARY',
      sharedValidatorBoundary: true,
    }]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('共享校验器边界项目')).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    expect(screen.queryByText('申请快照数据不完整，已禁止审核')).not.toBeInTheDocument();
  });

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

  it.each(validMoneyBoundaryCases)('accepts valid snapshot money: $caseName', async (moneyCase) => {
    setContext(adminContext);
    mockLists('/admin/project-requests', [moneyCase.request]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText(moneyCase.rowName)).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    expect(screen.queryByText('申请快照数据不完整，已禁止审核')).not.toBeInTheDocument();
    expectDescriptionValue(expandRow(moneyCase.rowName), moneyCase.detailLabel, moneyCase.detailValue);
    expect(screen.getByRole('button', { name: moneyCase.approveLabel })).toBeEnabled();
  });

  it.each(validSnapshotInvariantCases)('accepts valid snapshot invariant: $caseName', async (invariantCase) => {
    setContext(adminContext);
    mockLists('/admin/project-requests', [invariantCase.request]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText(invariantCase.rowName)).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    expect(screen.queryByText('申请快照数据不完整，已禁止审核')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: invariantCase.approveLabel })).toBeEnabled();
  });

  it('accepts exact project snapshot text and array boundaries', async () => {
    setContext(adminContext);
    mockLists('/admin/project-requests', [maximumBoundaryPlatformRequest]);

    render(<ProjectRequestsPage />);

    await expectListPair('/admin/project-requests');
    expect(screen.queryByText('申请快照数据不完整，已禁止审核')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: `通过 ${'n'.repeat(200)}，申请ID ${'i'.repeat(36)}` })).toBeEnabled();
  });

  it('keeps nullable live institution and platform-project names reviewable', async () => {
    setContext(adminContext);
    const request = {
      ...institutionRequest,
      institutionName: null,
      projectName: null,
    } satisfies ProfessionalProjectRequestResponse;
    mockLists('/admin/project-requests', [request]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('机构定制光子')).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    expect(screen.queryByText('申请快照数据不完整，已禁止审核')).not.toBeInTheDocument();
    const detail = expandRow('机构定制光子');
    expectDescriptionValue(detail, '当前机构名称', '-');
    expectDescriptionValue(detail, '当前平台项目名称', '-');
    expect(screen.getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' })).toBeEnabled();
  });

  it('accepts the maximum signed Int32 sales count', async () => {
    setContext(adminContext);
    mockLists('/admin/project-requests', [{ ...platformRequest, salesCount: 2_147_483_647 }]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('光子焕肤')).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    expect(screen.queryByText('申请快照数据不完整，已禁止审核')).not.toBeInTheDocument();
    expectDescriptionValue(expandRow('光子焕肤'), '销量', '2147483647');
    expect(screen.getByRole('button', { name: '通过 光子焕肤，申请ID platform-request' })).toBeEnabled();
  });

  it('accepts exact hundredth split boundaries without floating-point sum drift', async () => {
    setContext(adminContext);
    mockLists('/admin/project-requests', [institutionWithSplit({
      consultationFee: 99_999_999.99,
      commissionRate: 33.33,
      institutionRate: 33.33,
      platformRate: 33.33,
      doctorRate: 0.01,
    })]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('机构定制光子')).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    expect(screen.queryByText('申请快照数据不完整，已禁止审核')).not.toBeInTheDocument();
    expectDescriptionValue(expandRow('机构定制光子'), '按当前平台比例推导的医生净比例', '0.01%');
    expect(screen.getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '驳回 机构定制光子，申请ID institution-request' })).toBeEnabled();
  });

  it('renders a valid negative doctor rate as a reject-only current split conflict', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockLists('/admin/project-requests', [institutionWithSplit({
      consultationFee: 99_999_999.99,
      commissionRate: 40,
      institutionRate: 40,
      platformRate: 30,
      doctorRate: -10,
    })]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('机构定制光子')).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    expect(screen.queryByText('申请快照数据不完整，已禁止审核')).not.toBeInTheDocument();
    expect(screen.getByText('当前分成比例冲突：按当前平台比例推导的医生净比例为负数，该申请仅可驳回。')).toBeInTheDocument();
    expectDescriptionValue(expandRow('机构定制光子'), '按当前平台比例推导的医生净比例', '-10%');
    const approve = screen.getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' });
    const reject = screen.getByRole('button', { name: '驳回 机构定制光子，申请ID institution-request' });
    expect(approve).toBeDisabled();
    expect(reject).toBeEnabled();

    await user.click(approve);
    expect(api.post).not.toHaveBeenCalled();
    await user.click(reject);
    await user.type(screen.getByLabelText('审核意见'), '当前分成比例已冲突');
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    expect(api.post).toHaveBeenCalledWith('/management/project-requests/institution-request/review', {
      decision: 'REJECTED',
      reviewNote: '当前分成比例已冲突',
    });
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

  it.each([
    '/admin/project-requests',
    '/admin/institution-project-requests',
  ] as const)('keeps every review action stale after a 409 when required refresh GET fails: %s', async (failedPath) => {
    const user = userEvent.setup();
    setContext(adminContext);
    const reads = new Map<string, number>();
    vi.mocked(api.get).mockImplementation(async (url) => {
      const readCount = (reads.get(url) ?? 0) + 1;
      reads.set(url, readCount);
      if (url === failedPath && readCount === 2) throw new Error(`refresh failed for ${url}`);
      switch (url) {
        case '/admin/project-requests':
          return { data: { code: 200, message: 'OK', data: [institutionRequest] } };
        case '/admin/institution-project-requests':
          return { data: { code: 200, message: 'OK', data: [joinRequest] } };
        default:
          throw new Error(`Unexpected GET ${url}`);
      }
    });
    const conflict = Object.assign(new Error('申请已被其他审核人处理'), {
      response: { status: 409, data: { message: '申请已被其他审核人处理' } },
    });
    vi.mocked(api.post).mockRejectedValueOnce(conflict);

    render(<ProjectRequestsPage />);
    expect(await screen.findByText('机构定制光子')).toBeInTheDocument();
    expect(await screen.findByText('已有机构项目')).toBeInTheDocument();
    await expectListPair('/admin/project-requests');
    const successWarning = vi.spyOn(message, 'warning');

    await user.click(screen.getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' }));

    expect(await screen.findByText('审核状态已变化，但最新项目申请刷新失败')).toBeInTheDocument();
    expect(screen.getByText('当前页面数据可能已过期，所有审核操作已禁用。请重新加载后再审核。')).toBeInTheDocument();
    expect(successWarning).not.toHaveBeenCalled();
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(4));
    expect(api.get).toHaveBeenNthCalledWith(3, '/admin/project-requests');
    expect(api.get).toHaveBeenNthCalledWith(4, '/admin/institution-project-requests');
    for (const button of screen.getAllByRole('button', { name: /^(通过|要求修改|驳回) / })) {
      expect(button).toBeDisabled();
    }
    expect(api.post).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: '重新加载申请' }));

    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(6));
    expect(api.get).toHaveBeenNthCalledWith(5, '/admin/project-requests');
    expect(api.get).toHaveBeenNthCalledWith(6, '/admin/institution-project-requests');
    await waitFor(() => expect(screen.queryByText('审核状态已变化，但最新项目申请刷新失败')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '要求修改 已有机构项目，申请ID join-request' })).toBeEnabled();
    expect(api.post).toHaveBeenCalledTimes(1);
    successWarning.mockRestore();
  });

  it.each([
    {
      latestState: 'approved',
      latestRequests: [{
        ...institutionRequest,
        status: 'APPROVED',
        reviewNote: '其他审核人已通过',
        reviewedBy: 'admin-2',
        reviewedAt: '2026-08-16T11:00:00',
        resultingInstitutionProjectId: 'created-project',
      } satisfies ProfessionalProjectRequestResponse],
    },
    { latestState: 'missing', latestRequests: [] },
  ])('keeps an open modal bound to current requests when the latest target is $latestState', async ({ latestRequests }) => {
    const user = userEvent.setup();
    setContext(adminContext);
    const reads = new Map<string, number>();
    vi.mocked(api.get).mockImplementation(async (url) => {
      const readCount = (reads.get(url) ?? 0) + 1;
      reads.set(url, readCount);
      switch (url) {
        case '/admin/project-requests':
          if (readCount === 2) throw new Error('conflict refresh failed');
          return {
            data: {
              code: 200,
              message: 'OK',
              data: readCount === 1 ? [institutionRequest] : latestRequests,
            },
          };
        case '/admin/institution-project-requests':
          return { data: { code: 200, message: 'OK', data: [] } };
        default:
          throw new Error(`Unexpected GET ${url}`);
      }
    });
    const conflict = Object.assign(new Error('申请已被其他审核人处理'), {
      response: { status: 409, data: { message: '申请已被其他审核人处理' } },
    });
    vi.mocked(api.post).mockRejectedValueOnce(conflict);

    render(<ProjectRequestsPage />);
    await screen.findByText('机构定制光子');
    await expectListPair('/admin/project-requests');
    await user.click(screen.getByRole('button', { name: '驳回 机构定制光子，申请ID institution-request' }));
    await user.type(screen.getByLabelText('审核意见'), '保留这条审核草稿');
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));

    expect(await screen.findByText('审核状态已变化，但最新项目申请刷新失败')).toBeInTheDocument();
    expect(api.post).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: '重新加载申请' }));

    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(6));
    expect(api.get).toHaveBeenNthCalledWith(5, '/admin/project-requests');
    expect(api.get).toHaveBeenNthCalledWith(6, '/admin/institution-project-requests');
    expect(await screen.findByText('审核目标已变化')).toBeInTheDocument();
    expect(screen.getByText('最新列表中该申请已不再处于待审核状态，请关闭窗口并核对最新数据。')).toBeInTheDocument();
    expect(screen.getByLabelText('审核意见')).toHaveValue('保留这条审核草稿');
    const confirm = screen.getByRole('button', { name: /确\s*认/ });
    expect(confirm).toBeDisabled();

    confirm.removeAttribute('disabled');
    await act(async () => {
      fireEvent.click(confirm);
      await Promise.resolve();
    });
    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it('rebinds an open modal to the refreshed pending request and preserves its draft', async () => {
    const user = userEvent.setup();
    const refreshedPending = {
      ...institutionRequest,
      institutionSplit: {
        ...institutionRequest.institutionSplit,
        platformRate: 65,
        doctorRate: -10,
      },
      updatedAt: '2026-08-16T11:30:00',
    } satisfies ProfessionalProjectRequestResponse;
    setContext(adminContext);
    let professionalReadCount = 0;
    vi.mocked(api.get).mockImplementation(async (url) => {
      switch (url) {
        case '/admin/project-requests':
          professionalReadCount += 1;
          if (professionalReadCount === 2) throw new Error('conflict refresh failed');
          return {
            data: {
              code: 200,
              message: 'OK',
              data: professionalReadCount === 1 ? [institutionRequest] : [refreshedPending],
            },
          };
        case '/admin/institution-project-requests':
          return { data: { code: 200, message: 'OK', data: [] } };
        default:
          throw new Error(`Unexpected GET ${url}`);
      }
    });
    const conflict = Object.assign(new Error('申请已被其他审核人处理'), {
      response: { status: 409, data: { message: '申请已被其他审核人处理' } },
    });
    vi.mocked(api.post)
      .mockRejectedValueOnce(conflict)
      .mockResolvedValueOnce({ data: { code: 200, message: 'OK', data: null } });

    render(<ProjectRequestsPage />);
    await screen.findByText('机构定制光子');
    await expectListPair('/admin/project-requests');
    await user.click(screen.getByRole('button', { name: '驳回 机构定制光子，申请ID institution-request' }));
    await user.type(screen.getByLabelText('审核意见'), '按最新负比例驳回');
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));

    expect(await screen.findByText('审核状态已变化，但最新项目申请刷新失败')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '重新加载申请' }));

    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(6));
    await waitFor(() => expect(screen.queryByText('审核状态已变化，但最新项目申请刷新失败')).not.toBeInTheDocument());
    expect(screen.queryByText('审核目标已变化')).not.toBeInTheDocument();
    expect(screen.getByLabelText('审核意见')).toHaveValue('按最新负比例驳回');
    expect(screen.getByRole('button', { name: /确\s*认/ })).toBeEnabled();
    expect(screen.getByRole('button', { name: '通过 机构定制光子，申请ID institution-request' })).toBeDisabled();
    const detail = expandRow('机构定制光子');
    expectDescriptionValue(detail, '当前平台比例', '65%');
    expectDescriptionValue(detail, '按当前平台比例推导的医生净比例', '-10%');

    await user.click(screen.getByRole('button', { name: /确\s*认/ }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(2));
    expect(api.post).toHaveBeenNthCalledWith(2, '/management/project-requests/institution-request/review', {
      decision: 'REJECTED',
      reviewNote: '按最新负比例驳回',
    });
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
      { decision: 'CHANGES_REQUESTED', reviewNote: '请补充排班', force: false },
    ));
  });

  it('includes profile updates and leave requests, compares pricing snapshots, and reviews with force false', async () => {
    const user = userEvent.setup();
    setContext(adminContext);
    mockLists('/admin/project-requests', [], [profileUpdateRequest, leaveRequest]);

    render(<ProjectRequestsPage />);

    expect(await screen.findByText('资料变更')).toBeInTheDocument();
    expect(screen.getByText('退出机构项目')).toBeInTheDocument();
    const detail = expandRow('已有机构项目');
    expectDescriptionValue(detail, '医疗套餐优惠前金额（提议）', 'USD 1200');
    expectDescriptionValue(detail, '医疗套餐优惠前金额（当前）', 'USD 1000');
    expectDescriptionValue(detail, '平台服务比例（提议，只读）', '40%');
    expectDescriptionValue(detail, '平台服务比例（当前，只读）', '38%');

    await user.click(screen.getByRole('button', { name: '通过 已有机构项目，申请ID profile-request' }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/admin/institution-project-requests/profile-request/review',
      { decision: 'APPROVED', reviewNote: '', force: false },
    ));
  });
});
