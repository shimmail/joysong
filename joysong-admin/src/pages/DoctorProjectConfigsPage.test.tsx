import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../api';
import DoctorProjectConfigsPage from './DoctorProjectConfigsPage';

const { policyState } = vi.hoisted(() => ({
  policyState: { current: {
    policy: { platformRate: 40 } as { platformRate: number } | undefined,
    loading: false,
    error: undefined as string | undefined,
  } },
}));

vi.mock('../hooks/useOrderSplitPolicy', () => ({
  useOrderSplitPolicy: () => policyState.current,
}));

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
  };
});

const mockGet = vi.mocked(api.get);
const mockPost = vi.mocked(api.post);
const institution = { id: 'inst-1', name: '机构 A' };
const doctor = { id: 'doctor-1', name: '医生 A', institutionId: 'inst-1' };
const project = { id: 'project-1', institutionId: 'inst-1', effectiveName: '项目 A', doctors: [doctor] };

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
    const data = url === '/admin/doctor-institution-project-configs'
      ? [{ id: 'config-1', doctorId: doctor.id, institutionProjectId: project.id, consultationFee: 100, institutionRate: 35, commissionRate: 10 }]
      : url === '/admin/institutions' ? [institution]
        : url === '/admin/doctors' ? [doctor]
          : url.startsWith('/admin/institution-projects') ? [project]
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
  render(<DoctorProjectConfigsPage />);
  await screen.findByText('医生 A');
  await user.click(screen.getByRole('button', { name: /新增配置/ }));
  const dialog = screen.getByRole('dialog', { name: '新增配置' });
  expect(within(dialog).queryByText('医生/项目发布者佣金比例')).not.toBeInTheDocument();
  expect(within(dialog).getByText('平台分账比例')).toBeInTheDocument();
  expect(within(dialog).getByText('医美顾问分账比例')).toBeInTheDocument();
  expect(within(dialog).getByText('医生分账比例')).toBeInTheDocument();
  await selectOption('机构', '机构 A');
  await selectOption('医生', '医生 A');
  await selectOption('机构项目', '项目 A');
  const feeInput = screen.getByRole('spinbutton', { name: '面诊金' });
  const institutionInput = screen.getByRole('spinbutton', { name: '合作医疗机构分成比例' });
  const consultantInput = screen.getByRole('spinbutton', { name: '医美顾问分账比例' });
  await user.type(feeInput, '100');
  await user.clear(institutionInput);
  await user.type(institutionInput, '35');
  await user.type(consultantInput, '10');
  await waitFor(() => expect(screen.getByRole('spinbutton', { name: '医生分账比例' })).toHaveValue('15'));
  return user;
}

describe('DoctorProjectConfigsPage split rates', () => {
  it('submits only compatible editable rate fields', async () => {
    const user = await openAndCompleteForm();
    await user.click(screen.getByRole('button', { name: '创建配置' }));

    await waitFor(() => expect(mockPost).toHaveBeenCalledWith(
      '/admin/doctor-institution-project-configs',
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
    await user.click(screen.getByRole('button', { name: '创建配置' }));

    expect(await screen.findAllByText('平台、合作医疗机构和医美顾问分账比例合计不能超过 100%')).not.toHaveLength(0);
    expect(mockPost).not.toHaveBeenCalled();
  });

  it('disables confirmation when the policy failed to load', async () => {
    policyState.current = { policy: undefined, loading: false, error: '分账策略加载失败' };
    const user = userEvent.setup();
    render(<DoctorProjectConfigsPage />);
    await user.click(screen.getByRole('button', { name: /新增配置/ }));

    const dialog = screen.getByRole('dialog', { name: '新增配置' });
    expect(within(dialog).getByRole('alert')).toHaveTextContent('分账策略加载失败');
    expect(screen.getByRole('button', { name: '创建配置' })).toBeDisabled();
    expect(mockPost).not.toHaveBeenCalled();
  });

  it('marks an old config with a negative derived doctor share invalid', async () => {
    mockGet.mockImplementation((url) => {
      const data = url === '/admin/doctor-institution-project-configs'
        ? [{ id: 'config-1', doctorId: doctor.id, institutionProjectId: project.id, consultationFee: 100, institutionRate: 40, commissionRate: 20.01 }]
        : url === '/admin/institutions' ? [institution]
          : url === '/admin/doctors' ? [doctor]
            : url.startsWith('/admin/institution-projects') ? [project]
              : [];
      return Promise.resolve({ data: { code: 200, message: 'OK', data } });
    });
    render(<DoctorProjectConfigsPage />);

    expect(await screen.findByText('配置无效')).toBeInTheDocument();
  });
});
