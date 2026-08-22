import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
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
      ? [{
        id: 'config-1', doctorId: doctor.id, institutionProjectId: project.id,
        medicalListPrice: 1000, consultationFee: 100, institutionRate: 35, commissionRate: 10,
      }]
      : url === '/admin/institutions' ? [institution]
        : url === '/admin/doctors' ? [doctor]
          : url.startsWith('/admin/institution-projects') ? [project]
            : [];
    return Promise.resolve({ data: { code: 200, message: 'OK', data } });
  });
});

afterEach(cleanup);

describe('DoctorProjectConfigsPage medical list price', () => {
  it('edits the USD list price, shows the platform rate read only, and submits exactly three fields', async () => {
    const user = userEvent.setup();
    render(<DoctorProjectConfigsPage />);

    const table = await screen.findByRole('table');
    const row = within(table).getByRole('row', { name: /医生 A.*项目 A/ });
    expect(within(row).getByText('USD 1000')).toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: /编辑/ }));

    const dialog = await screen.findByRole('dialog', { name: '编辑配置' });
    const listPrice = within(dialog).getByRole('spinbutton', { name: '医疗套餐优惠前金额（USD）' });
    await waitFor(() => expect(listPrice).toHaveValue('1000.00'));
    expect(within(dialog).getByRole('spinbutton', { name: '平台服务比例' })).toBeDisabled();
    expect(within(dialog).queryByText('面诊金')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('尾款')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('医美顾问分账比例')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('医生分账比例')).not.toBeInTheDocument();

    await user.clear(listPrice);
    await user.type(listPrice, '1200');
    await user.click(within(dialog).getByRole('button', { name: '保存修改' }));

    await waitFor(() => expect(mockPost).toHaveBeenCalledWith(
      '/admin/doctor-institution-project-configs',
      {
        doctorId: 'doctor-1',
        institutionProjectId: 'project-1',
        medicalListPrice: 1200,
      },
    ));
    expect(Object.keys(mockPost.mock.calls[0][1] as object).sort()).toEqual([
      'doctorId', 'institutionProjectId', 'medicalListPrice',
    ]);
  });

  it('requires a positive medical list price', async () => {
    const user = userEvent.setup();
    render(<DoctorProjectConfigsPage />);

    const table = await screen.findByRole('table');
    await user.click(within(table).getByRole('button', { name: /编辑/ }));
    const dialog = await screen.findByRole('dialog', { name: '编辑配置' });
    const listPrice = within(dialog).getByRole('spinbutton', { name: '医疗套餐优惠前金额（USD）' });
    await waitFor(() => expect(listPrice).toHaveValue('1000.00'));
    await user.clear(listPrice);
    await user.type(listPrice, '0');
    await user.click(within(dialog).getByRole('button', { name: '保存修改' }));

    expect(await within(dialog).findByText('医疗套餐优惠前金额必须大于 0')).toBeInTheDocument();
    expect(mockPost).not.toHaveBeenCalled();
  });
});
