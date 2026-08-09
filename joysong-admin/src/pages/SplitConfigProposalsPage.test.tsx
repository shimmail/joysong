import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../api';
import SplitConfigProposalsPage from './SplitConfigProposalsPage';

const { policyState, managementContext } = vi.hoisted(() => ({
  policyState: { current: {
    policy: { platformRate: 40 } as { platformRate: number } | undefined,
    loading: false,
    error: undefined as string | undefined,
  } },
  managementContext: {
    userId: 'user-1', platformRole: 'USER' as const, activeRoles: ['DOCTOR'], doctorId: 'doctor-1',
    managedInstitutionIds: [] as string[], visibleInstitutionIds: ['inst-1'], canManageDoctors: false,
    canManageInstitutions: false, canManageInstitutionProjects: false, canManageArticles: false,
    canManageSplitConfigs: true, canManageOrders: false,
  },
}));

vi.mock('../hooks/useOrderSplitPolicy', () => ({ useOrderSplitPolicy: () => policyState.current }));
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    getManagementContext: () => managementContext,
    default: { get: vi.fn(), post: vi.fn() },
  };
});

const mockGet = vi.mocked(api.get);
const mockPost = vi.mocked(api.post);
const institution = { id: 'inst-1', name: '机构 A' };
const doctor = { id: 'doctor-1', name: '医生 A', institutionId: 'inst-1' };
const project = { id: 'project-1', institutionId: 'inst-1', effectiveName: '项目 A', doctors: [doctor] };
const activeConfig = {
  id: 'config-1',
  doctorId: doctor.id,
  institutionProjectId: project.id,
  consultationFee: 100,
  commissionRate: 20,
  institutionRate: 40,
  createdAt: '2026-08-01T09:00:00',
  updatedAt: '2026-08-01T09:00:00',
  deletedAt: null,
};
const historicalProposal = {
  id: 'proposal-1',
  configId: activeConfig.id,
  doctorId: doctor.id,
  doctorName: doctor.name,
  institutionProjectId: project.id,
  institutionId: institution.id,
  institutionName: institution.name,
  projectName: project.effectiveName,
  consultationFee: 120,
  commissionRate: 20.01,
  institutionRate: 40,
  proposerUserId: 'institution-user-1',
  proposerName: '机构管理员 A',
  proposerSide: 'INSTITUTION',
  status: 'REJECTED',
  doctorConfirmedAt: null,
  institutionConfirmedAt: '2026-08-08T10:00:00',
  decidedBy: 'user-1',
  deciderName: '医生 A',
  decisionNote: '比例合计无效',
  submittedAt: '2026-08-08T09:00:00',
  decidedAt: '2026-08-08T11:00:00',
  updatedAt: '2026-08-08T11:00:00',
};

beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: false, media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});

beforeEach(() => {
  policyState.current = { policy: { platformRate: 40 }, loading: false, error: undefined };
  mockPost.mockResolvedValue({ data: { code: 200, message: 'OK', data: null } });
  mockGet.mockImplementation((url) => {
    const data = url === '/admin/doctor-institution-project-configs' ? [activeConfig]
      : url === '/admin/doctor-institution-project-config-proposals' ? [historicalProposal]
        : url === '/admin/institutions' ? [institution]
          : url === '/admin/doctors' ? [doctor]
            : url === '/admin/institution-projects' ? [project]
              : [];
    return Promise.resolve({ data: { code: 200, message: 'OK', data } });
  });
});

afterEach(cleanup);

async function selectOption(label: string, option: string) {
  const combobox = screen.getByRole('combobox', { name: label });
  fireEvent.mouseDown(combobox);
  await screen.findByRole('option', { name: option });
  const optionLabels = screen.getAllByText(option);
  fireEvent.click(optionLabels[optionLabels.length - 1]);
}

async function openAndCompleteForm() {
  const user = userEvent.setup();
  render(<SplitConfigProposalsPage />);
  await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(5));
  await user.click(screen.getByRole('button', { name: /发起分账提案/ }));
  const dialog = screen.getByRole('dialog', { name: '发起分账提案' });
  expect(within(dialog).queryByText('医生/项目发布者比例')).not.toBeInTheDocument();
  expect(within(dialog).getByText('平台分账比例')).toBeInTheDocument();
  expect(within(dialog).getByText('医美顾问分账比例')).toBeInTheDocument();
  expect(within(dialog).getByText('医生分账比例')).toBeInTheDocument();
  await selectOption('机构', '机构 A');
  await selectOption('机构项目', '项目 A');
  const feeInput = screen.getByRole('spinbutton', { name: '面诊金' });
  const institutionInput = screen.getByRole('spinbutton', { name: '合作医疗机构分成比例' });
  const consultantInput = screen.getByRole('spinbutton', { name: '医美顾问分账比例' });
  await user.clear(feeInput);
  await user.type(feeInput, '100');
  await user.clear(institutionInput);
  await user.type(institutionInput, '35');
  await user.clear(consultantInput);
  await user.type(consultantInput, '10');
  await waitFor(() => expect(screen.getByRole('spinbutton', { name: '医生分账比例' })).toHaveValue('15'));
  return user;
}

describe('SplitConfigProposalsPage split rates', () => {
  it('shows consultant and derived doctor rates in current and historical groups', async () => {
    render(<SplitConfigProposalsPage />);

    const tables = await screen.findAllByRole('table');
    expect(tables).toHaveLength(2);
    const [currentTable, historyTable] = tables;
    expect(within(currentTable).getByRole('columnheader', { name: '医美顾问分账比例' })).toBeInTheDocument();
    expect(within(historyTable).getByRole('columnheader', { name: '医美顾问分账比例' })).toBeInTheDocument();
    expect(within(currentTable).getByRole('columnheader', { name: '医生分账比例' })).toBeInTheDocument();
    expect(within(historyTable).getByRole('columnheader', { name: '医生分账比例' })).toBeInTheDocument();

    const currentRow = within(currentTable).getByRole('row', { name: /医生 A.*项目 A/ });
    expect(within(currentRow).getByText('20%')).toBeInTheDocument();
    expect(within(currentRow).getByText('0%')).toBeInTheDocument();

    const historyRow = within(historyTable).getByRole('row', { name: /机构 A.*医生 A.*项目 A/ });
    expect(within(historyRow).getByText('20.01%')).toBeInTheDocument();
    const invalidTag = within(historyRow).getByText('配置无效');
    expect(invalidTag).toBeInTheDocument();
    expect({ color: getComputedStyle(invalidTag).color, background: getComputedStyle(invalidTag).backgroundColor })
      .toEqual({ color: 'var(--ant-red-7)', background: 'var(--ant-red-1)' });
  });

  it('submits only compatible editable rate fields', async () => {
    const user = await openAndCompleteForm();
    await user.click(screen.getByRole('button', { name: '提交提案' }));

    await waitFor(() => expect(mockPost).toHaveBeenCalledWith(
      '/admin/doctor-institution-project-config-proposals',
      expect.objectContaining({ institutionRate: 35, commissionRate: 10 }),
    ));
    const payload = mockPost.mock.calls[0][1] as Record<string, unknown>;
    expect(payload).not.toHaveProperty('doctorRate');
    expect(payload).not.toHaveProperty('platformRate');
  });

  it('does not post when the rate total is invalid', async () => {
    const user = await openAndCompleteForm();
    const institutionInput = screen.getByRole('spinbutton', { name: '合作医疗机构分成比例' });
    const consultantInput = screen.getByRole('spinbutton', { name: '医美顾问分账比例' });
    await user.clear(institutionInput);
    await user.type(institutionInput, '40');
    await user.clear(consultantInput);
    await user.type(consultantInput, '20.01');
    await user.click(screen.getByRole('button', { name: '提交提案' }));

    expect(await screen.findAllByText('平台、合作医疗机构和医美顾问分账比例合计不能超过 100%')).not.toHaveLength(0);
    expect(mockPost).not.toHaveBeenCalled();
  });

  it('disables confirmation when the policy failed to load', async () => {
    policyState.current = { policy: undefined, loading: false, error: '分账策略加载失败' };
    const user = userEvent.setup();
    render(<SplitConfigProposalsPage />);
    await user.click(await screen.findByRole('button', { name: /发起分账提案/ }));

    const dialog = screen.getByRole('dialog', { name: '发起分账提案' });
    expect(within(dialog).getByRole('alert')).toHaveTextContent('分账策略加载失败');
    expect(within(dialog).getByRole('button', { name: '提交提案' })).toBeDisabled();
    expect(mockPost).not.toHaveBeenCalled();
  });

  it('disables confirmation while the policy is loading', async () => {
    policyState.current = { policy: undefined, loading: true, error: undefined };
    const user = userEvent.setup();
    render(<SplitConfigProposalsPage />);
    await user.click(await screen.findByRole('button', { name: /发起分账提案/ }));

    const dialog = screen.getByRole('dialog', { name: '发起分账提案' });
    expect(within(dialog).getByRole('button', { name: '提交提案' })).toBeDisabled();
    expect(mockPost).not.toHaveBeenCalled();
  });

  it('renders unavailable derived rates and disables confirmation when policy is silently absent', async () => {
    policyState.current = { policy: undefined, loading: false, error: undefined };
    const user = userEvent.setup();
    render(<SplitConfigProposalsPage />);

    const [currentTable, historyTable] = await screen.findAllByRole('table');
    const currentRow = within(currentTable).getByRole('row', { name: /医生 A.*项目 A/ });
    const historyRow = within(historyTable).getByRole('row', { name: /机构 A.*医生 A.*项目 A/ });
    expect(within(currentRow).getByText('-')).toBeInTheDocument();
    expect(within(historyRow).getByText('-')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /发起分账提案/ }));
    const dialog = screen.getByRole('dialog', { name: '发起分账提案' });
    expect(within(dialog).getByRole('button', { name: '提交提案' })).toBeDisabled();
    expect(mockPost).not.toHaveBeenCalled();
  });
});
