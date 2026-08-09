import { act, render, screen } from '@testing-library/react';
import { Form, type FormInstance } from 'antd';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { SplitRateFields } from './SplitRateFields';

let formInstance: FormInstance;

beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
});

function Harness({
  initialValues = { institutionRate: 35, commissionRate: 10 },
  policy,
  loading = false,
  error,
}: {
  initialValues?: Record<string, number>;
  policy?: { platformRate: number };
  loading?: boolean;
  error?: string;
}) {
  const [form] = Form.useForm();
  formInstance = form;
  return <Form form={form} initialValues={initialValues}>
    <SplitRateFields form={form} policy={error ? policy : policy ?? { platformRate: 40 }} loading={loading} error={error} />
  </Form>;
}

describe('SplitRateFields', () => {
  it('shows server platform share and derives a read-only doctor share', async () => {
    render(<Harness />);

    expect(screen.getByText('平台分账比例')).toBeInTheDocument();
    expect(screen.getByText('医美顾问分账比例')).toBeInTheDocument();
    expect(screen.getByText('医生分账比例')).toBeInTheDocument();
    expect(screen.getByRole('spinbutton', { name: '平台分账比例' })).toHaveValue('40');
    expect(screen.getByRole('spinbutton', { name: '医生分账比例' })).toHaveValue('15');

    await expect(formInstance.validateFields()).resolves.toEqual({
      institutionRate: 35,
      commissionRate: 10,
    });
  });

  it('rejects a total over 100 percent with the exact shared validation error', async () => {
    render(<Harness />);
    act(() => formInstance.setFieldsValue({ institutionRate: 40, commissionRate: 20.01 }));

    await expect(formInstance.validateFields()).rejects.toMatchObject({ errorFields: expect.any(Array) });
    expect(await screen.findAllByText('平台、合作医疗机构和医美顾问分账比例合计不能超过 100%')).toHaveLength(2);
  });

  it('fails closed when the platform policy is unavailable', async () => {
    render(<Harness policy={undefined} error="分账策略加载失败" />);

    expect(screen.getByRole('alert')).toHaveTextContent('分账策略加载失败');
    await expect(formInstance.validateFields()).rejects.toMatchObject({ errorFields: expect.any(Array) });
    expect(await screen.findAllByText('分账策略尚未加载')).toHaveLength(2);
  });
});
