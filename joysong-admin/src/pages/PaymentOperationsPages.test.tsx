import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../api';
import { formatMoney } from '../utils/money';
import OrdersPage from './OrdersPage';
import PaymentsPage from './PaymentsPage';
import RefundsPage from './RefundsPage';

vi.mock('antd', async (importOriginal) => {
  const actual = await importOriginal<typeof import('antd')>();
  return {
    ...actual,
    Select: ({ value, options = [], onChange, virtual: _virtual, ...props }: any) => (
      <select
        {...props}
        value={value || '__all__'}
        onChange={(event) => onChange(event.target.value === '__all__' ? '' : event.target.value)}
      >
        {options.map((option: { label: string; value: string }) => (
          <option key={option.value} value={option.value || '__all__'}>{option.label}</option>
        ))}
      </select>
    ),
  };
});

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    default: {
      get: vi.fn(),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
    },
  };
});

const mockGet = vi.mocked(api.get);
const mockPost = vi.mocked(api.post);
const mockPut = vi.mocked(api.put);

const adminContext = {
  userId: 'admin-1',
  platformRole: 'ADMIN',
  activeRoles: ['ADMIN'],
  managedInstitutionIds: [],
  visibleInstitutionIds: [],
  canManageDoctors: true,
  canManageInstitutions: true,
  canManageInstitutionProjects: true,
  canManageArticles: true,
  canManageSplitConfigs: true,
  canManageOrders: true,
  canApplyToInstitutions: false,
  canReviewInstitutionRequests: true,
  canSubmitPlatformProjectRequests: true,
  canSubmitInstitutionProjectRequests: false,
  canReviewInstitutionProjectRequests: true,
  canViewAffiliations: true,
};

const serviceOrder = {
  id: 'service-order-processing',
  orderNo: 'SO-001',
  projectName: '地接项目',
  institutionName: '机构 A',
  paymentFlow: 'TRAVEL_GROUND_SERVICE_ONLY',
  currency: 'USD',
  travelGroundServiceFeeMinor: 12345,
  price: 123.45,
  consultationFee: 99,
  remainingAmount: 901,
  discountAmount: 50,
  status: 'REFUND_PROCESSING',
  refundStatus: 'REFUND_PROCESSING',
  verifiedAt: 'service-verified-at',
  settlementAt: 'service-settlement-at',
  createdAt: '2026-08-21T10:00:00',
};

const legacyOrder = {
  id: 'legacy-order',
  orderNo: 'LO-001',
  projectName: '历史医疗项目',
  institutionName: '机构 B',
  paymentFlow: 'LEGACY_MEDICAL',
  currency: 'USD',
  price: 1000,
  consultationFee: 100,
  remainingAmount: 900,
  discountAmount: 0,
  status: 'CONSULTATION_PAID',
  refundStatus: 'NONE',
  verifiedAt: 'legacy-verified-at',
  settlementAt: 'legacy-settlement-at',
  createdAt: '2026-08-20T10:00:00',
};

const serviceRefund = {
  id: 'refund-service-1',
  orderId: 'service-order-processing',
  orderNo: 'SO-001',
  userId: 'user-1',
  projectName: '地接项目',
  institutionName: '机构 A',
  doctorName: '医生 A',
  paymentFlow: 'TRAVEL_GROUND_SERVICE_ONLY',
  currency: 'USD',
  requestedAmountMinor: 12345,
  refundedAmountMinor: 4500,
  amount: 12345,
  paymentAmount: 12345,
  refundType: 'FULL',
  reason: '行程取消',
  description: '无法来华',
  status: 'PENDING',
  createdAt: '2026-08-21T11:00:00',
};

const legacyPayment = {
  id: 'legacy-payment',
  orderId: 'legacy-order',
  userId: 'legacy-user',
  paymentType: 'CONSULTATION_FEE',
  provider: 'STRIPE',
  method: 'CARD',
  currency: 'USD',
  amount: 12.3,
  status: 'SUCCEEDED',
  transactionId: 'legacy-transaction',
  createdAt: '2026-08-20T10:00:00',
};

const legacyRefund = {
  id: 'legacy-refund',
  orderId: 'legacy-order',
  orderNo: 'LO-001',
  userId: 'legacy-user',
  projectName: '历史医疗项目',
  paymentFlow: 'LEGACY_MEDICAL',
  currency: 'USD',
  amount: 12.3,
  paymentAmount: 99.99,
  refundType: 'PARTIAL',
  reason: '历史退款',
  status: 'PENDING',
  createdAt: '2026-08-20T11:00:00',
};

const failedProcessingRefund = {
  ...serviceRefund,
  id: 'refund-processing-failed',
  orderNo: 'RETRY-001',
  status: 'REFUND_PROCESSING',
  reviewedBy: 'admin-reviewer',
  reviewedAt: '2026-08-22T09:30:00',
  rejectReason: '渠道暂时不可用',
  items: [{
    id: 'refund-item-failed',
    paymentId: 'payment-service-1',
    provider: 'ALIPAY_PLUS',
    currency: 'USD',
    amountMinor: 12345,
    providerRefundId: 'provider-refund-001',
    status: 'FAILED',
    failureCode: 'GATEWAY_TIMEOUT',
    failureMessage: '渠道响应超时',
    requestedAt: '2026-08-22T09:31:00',
    completedAt: undefined,
    updatedAt: '2026-08-22T09:32:00',
  }],
};

function response(data: unknown) {
  return Promise.resolve({ data: { code: 200, message: 'OK', data } });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

function renderOrders() {
  return render(<MemoryRouter><OrdersPage /></MemoryRouter>);
}

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
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});

beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.setItem('management_context', JSON.stringify(adminContext));
  mockPut.mockResolvedValue({ data: { code: 200, message: 'OK', data: null } });
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('minor-unit money formatting', () => {
  it('keeps minor units, zero and currency precision explicit while preserving the legacy fallback', () => {
    expect(formatMoney(1, 'usd', 999)).toBe('USD 0.01');
    expect(formatMoney(0, 'USD', 999)).toBe('USD 0.00');
    expect(formatMoney(123, 'JPY', 999)).toBe('JPY 123');
    expect(formatMoney(undefined, 'USD', 12.3)).toBe('USD 12.30');
    expect(formatMoney(1, undefined, 12.3)).toBe('-');
  });
});

describe('OrdersPage travel ground service operations', () => {
  it('uses service-fee status and minor-unit wording while blocking legacy actions only on the new flow', async () => {
    mockGet.mockImplementation((url) => url === '/admin/orders'
      ? response([
        { ...serviceOrder, id: 'service-order-pending', status: 'PENDING_SERVICE_FEE', refundStatus: 'NONE' },
        { ...serviceOrder, id: 'service-order-active', status: 'SERVICE_ACTIVE', refundStatus: 'NONE' },
        { ...serviceOrder, id: 'service-order-review', status: 'REFUND_REVIEW', refundStatus: 'PENDING' },
        serviceOrder,
        { ...serviceOrder, id: 'service-order-refunded', status: 'REFUNDED', refundStatus: 'APPROVED' },
        {
          ...serviceOrder,
          id: 'service-order-rejected',
          status: 'SERVICE_ACTIVE',
          refundStatus: 'REJECTED',
          travelGroundServiceFeeMinor: 22222,
        },
        legacyOrder,
      ])
      : response([]));

    renderOrders();

    expect(await screen.findByText('待支付旅游地接服务费')).toBeInTheDocument();
    expect(screen.getAllByText('旅游地接服务已激活')).toHaveLength(2);
    expect(screen.getByText('旅游地接服务退款审核中')).toBeInTheDocument();
    expect(screen.getByText('旅游地接服务退款渠道处理中')).toBeInTheDocument();
    expect(screen.getByText('旅游地接服务已退款')).toBeInTheDocument();

    const serviceRow = screen.getByRole('row', { name: /service-order-processing/ });
    expect(within(serviceRow).getByText('旅游地接服务费')).toBeInTheDocument();
    expect(within(serviceRow).getAllByText('USD 123.45')).toHaveLength(2);
    expect(within(serviceRow).queryByRole('button', { name: /变更状态/ })).not.toBeInTheDocument();
    expect(within(serviceRow).queryByRole('button', { name: /手动核销/ })).not.toBeInTheDocument();
    expect(within(serviceRow).queryByText('$99')).not.toBeInTheDocument();
    expect(within(serviceRow).queryByText('$901')).not.toBeInTheDocument();
    expect(within(serviceRow).queryByText('service-verified-at')).not.toBeInTheDocument();
    expect(within(serviceRow).queryByText('service-settlement-at')).not.toBeInTheDocument();

    const rejectedRow = screen.getByRole('row', { name: /service-order-rejected/ });
    expect(within(rejectedRow).getAllByText('USD 222.22')).toHaveLength(1);
    const activeRow = screen.getByRole('row', { name: /service-order-active/ });
    expect(within(activeRow).getAllByText('USD 123.45')).toHaveLength(1);

    const legacyRow = screen.getByRole('row', { name: /legacy-order/ });
    expect(within(legacyRow).getByRole('button', { name: /手动核销/ })).toBeInTheDocument();
    expect(within(legacyRow).getByRole('button', { name: /变更状态/ })).toBeInTheDocument();
    expect(within(legacyRow).getByText('legacy-verified-at')).toBeInTheDocument();
    expect(within(legacyRow).getByText('legacy-settlement-at')).toBeInTheDocument();
  }, 30_000);
});

describe('PaymentsPage service-fee records', () => {
  it('labels the fixed Alipay+ contract and formats all actual statuses from integer minor units', async () => {
    const statuses = [
      'CREATED',
      'REQUIRES_ACTION',
      'PROCESSING',
      'SUCCEEDED',
      'FAILED',
      'CANCELLED',
      'EXPIRED',
      'PARTIALLY_REFUNDED',
      'REFUNDED',
    ];
    mockGet.mockImplementation((url) => url === '/admin/payments'
      ? response(statuses.map((status, index) => ({
        id: `payment-${status}`,
        orderId: `order-${index}`,
        userId: 'user-1',
        paymentType: 'TRAVEL_GROUND_SERVICE_FEE',
        provider: 'ALIPAY_PLUS',
        paymentMethod: 'ALIPAY_PLUS_CASHIER',
        method: 'ALIPAY_PLUS_CASHIER',
        currency: 'USD',
        amountMinor: 12345,
        refundedAmountMinor: index === statuses.length - 1 ? 12345 : 0,
        amount: 12345,
        status,
        transactionId: `transaction-${index}`,
        createdAt: '2026-08-21T10:00:00',
      })))
      : response([]));

    render(<PaymentsPage />);

    const createdRow = await screen.findByRole('row', { name: /payment-CREATED/ });
    expect(within(createdRow).getByText('旅游地接服务费')).toBeInTheDocument();
    expect(within(createdRow).getByText('Alipay+')).toBeInTheDocument();
    expect(within(createdRow).getByText('Alipay+ 托管收银台')).toBeInTheDocument();
    expect(within(createdRow).getByText('USD 123.45')).toBeInTheDocument();
    expect(within(createdRow).getByText('已创建（CREATED）')).toBeInTheDocument();

    for (const status of statuses.slice(1)) {
      expect(screen.getByRole('row', { name: new RegExp(`payment-${status}.*${status}`) })).toBeInTheDocument();
    }
    const refundedRow = screen.getByRole('row', { name: /payment-REFUNDED/ });
    expect(within(refundedRow).getAllByText('USD 123.45')).toHaveLength(2);
  });

  it('preserves legacy decimal amount fallback and legacy payment labels', async () => {
    mockGet.mockImplementation((url) => url === '/admin/payments'
      ? response([legacyPayment])
      : response([]));

    render(<PaymentsPage />);

    const row = await screen.findByRole('row', { name: /legacy-payment/ });
    expect(within(row).getByText('面诊金（历史）')).toBeInTheDocument();
    expect(within(row).getByText('USD 12.30')).toBeInTheDocument();
    expect(within(row).getByText('STRIPE')).toBeInTheDocument();
    expect(within(row).getByText('银行卡')).toBeInTheDocument();
    expect(within(row).getByText('支付成功（SUCCEEDED）')).toBeInTheDocument();
  });
});

describe('RefundsPage manual review operations', () => {
  it('shows the service flow, real statuses, and requested/refunded minor-unit amounts without COMPLETED', async () => {
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([serviceRefund])
      : response([]));

    render(<RefundsPage />);

    const row = await screen.findByRole('row', { name: /SO-001/ });
    expect(within(row).getByText('旅游地接服务费流程')).toBeInTheDocument();
    expect(within(row).getAllByText('USD 123.45')).toHaveLength(2);
    expect(within(row).getByText('USD 45.00')).toBeInTheDocument();

    fireEvent.mouseDown(screen.getByRole('combobox', { name: '退款状态' }));
    expect(await screen.findByRole('option', { name: '待人工审核' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '渠道处理中' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '退款成功' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '已拒绝' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '已取消' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '已完成' })).not.toBeInTheDocument();
  });

  it('preserves legacy decimal fallbacks and legacy refund labels', async () => {
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([legacyRefund])
      : response([]));

    render(<RefundsPage />);

    const row = await screen.findByRole('row', { name: /LO-001/ });
    expect(within(row).getByText('历史医疗支付流程')).toBeInTheDocument();
    expect(within(row).getByText('USD 12.30')).toBeInTheDocument();
    expect(within(row).getByText('USD 99.99')).toBeInTheDocument();
    expect(within(row).getByText('部分退款')).toBeInTheDocument();
  });

  it('refreshes after an approval error because the provider state may already be processing', async () => {
    const user = userEvent.setup();
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([serviceRefund])
      : response([]));
    mockPut.mockRejectedValueOnce(new Error('provider result unknown'));

    render(<RefundsPage />);
    const row = await screen.findByRole('row', { name: /SO-001/ });
    fireEvent.click(within(row).getByRole('button', { name: /批准/ }));
    const dialog = (await screen.findByText('确认批准退款')).closest('.ant-modal') as HTMLElement;
    await user.click(within(dialog).getByRole('button', { name: '确认批准' }));

    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(2));
  });

  it('refreshes after a rejection error because the review may already have been persisted', async () => {
    const user = userEvent.setup();
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([serviceRefund])
      : response([]));
    mockPut.mockRejectedValueOnce(new Error('response lost'));

    render(<RefundsPage />);
    const row = await screen.findByRole('row', { name: /SO-001/ });
    fireEvent.click(within(row).getByRole('button', { name: /拒绝/ }));
    const dialog = (await screen.findByText('拒绝退款申请')).closest('.ant-modal') as HTMLElement;
    await user.type(within(dialog).getByPlaceholderText('请填写拒绝原因（必填）'), '资料不足');
    await user.click(within(dialog).getByRole('button', { name: '确认拒绝' }));

    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(2));
  });

  it('reports approval as submitted rather than falsely claiming provider completion', async () => {
    const user = userEvent.setup();
    const successSpy = vi.spyOn(message, 'success');
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([serviceRefund])
      : response([]));
    mockPut.mockResolvedValueOnce({
      data: { code: 200, message: 'OK', data: { ...serviceRefund, status: 'REFUND_PROCESSING' } },
    });

    render(<RefundsPage />);
    const row = await screen.findByRole('row', { name: /SO-001/ });
    fireEvent.click(within(row).getByRole('button', { name: /批准/ }));
    const dialog = (await screen.findByText('确认批准退款')).closest('.ant-modal') as HTMLElement;
    await user.click(within(dialog).getByRole('button', { name: '确认批准' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('退款审核已提交，请查看渠道处理状态'));
  });

  it('shows review metadata and provider failure diagnostics in the refund details', async () => {
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([{ ...failedProcessingRefund, status: 'PENDING' }])
      : response([]));

    render(<RefundsPage />);
    const row = await screen.findByRole('row', { name: /RETRY-001/ });
    await userEvent.setup().click(within(row).getByRole('button', { name: /详情/ }));

    const dialog = (await screen.findByText('退款详情')).closest('.ant-modal') as HTMLElement;
    expect(within(dialog).getByText('admin-reviewer')).toBeInTheDocument();
    expect(within(dialog).getByText('2026-08-22T09:30:00')).toBeInTheDocument();
    expect(within(dialog).getByText('渠道暂时不可用')).toBeInTheDocument();
    const itemRow = within(dialog).getByRole('row', { name: /payment-service-1/ });
    expect(within(itemRow).getByText('ALIPAY_PLUS')).toBeInTheDocument();
    expect(within(itemRow).getByText('USD 123.45')).toBeInTheDocument();
    expect(within(itemRow).getByText('FAILED')).toBeInTheDocument();
    expect(within(itemRow).getByText('provider-refund-001')).toBeInTheDocument();
    expect(within(itemRow).getByText('GATEWAY_TIMEOUT')).toBeInTheDocument();
    expect(within(itemRow).getByText('渠道响应超时')).toBeInTheDocument();
    expect(within(itemRow).getByText('2026-08-22T09:31:00')).toBeInTheDocument();
    expect(within(itemRow).getByText('-')).toBeInTheDocument();
    expect(within(itemRow).getByText('2026-08-22T09:32:00')).toBeInTheDocument();
  });

  it('shows the retry button only on processing refunds with a failed item', async () => {
    const user = userEvent.setup();
    const records = [
      ...['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'refund_processing'].map((status) => ({
        ...failedProcessingRefund,
        id: `retry-${status.toLowerCase()}`,
        orderNo: `RETRY-${status}`,
        status,
      })),
      {
        ...failedProcessingRefund,
        id: 'retry-processing-without-failure',
        orderNo: 'RETRY-PROCESSING-WITHOUT-FAILURE',
        items: [{ ...failedProcessingRefund.items[0], status: 'PROCESSING' }],
      },
      failedProcessingRefund,
    ];
    mockGet.mockImplementation((url) => url === '/admin/refunds' ? response(records) : response([]));

    render(<RefundsPage />);
    await user.selectOptions(await screen.findByRole('combobox', { name: '退款状态' }), '__all__');
    await screen.findByText('RETRY-APPROVED');

    for (const orderNo of records.filter(record => record.id !== failedProcessingRefund.id).map(record => record.orderNo)) {
      const row = screen.getByText(orderNo).closest('tr') as HTMLTableRowElement;
      expect(within(row).queryByRole('button', { name: '重试失败项' })).not.toBeInTheDocument();
    }
    const retryableRow = screen.getByText('RETRY-001').closest('tr') as HTMLTableRowElement;
    expect(within(retryableRow).getByRole('button', { name: '重试失败项' })).toBeEnabled();
  });

  it('submits only one pending detail retry and refreshes its diagnostics after success', async () => {
    const user = userEvent.setup();
    const successSpy = vi.spyOn(message, 'success');
    const retry = deferred<any>();
    const refreshedRefund = {
      ...failedProcessingRefund,
      items: [{
        ...failedProcessingRefund.items[0],
        status: 'PROCESSING',
        failureCode: 'RETRY_QUEUED',
        failureMessage: '已重新提交渠道',
        updatedAt: '2026-08-22T10:00:00',
      }],
    };
    mockGet
      .mockImplementationOnce(() => response([failedProcessingRefund]))
      .mockImplementationOnce(() => response([refreshedRefund]));
    mockPost.mockImplementationOnce(() => retry.promise);

    render(<RefundsPage />);
    await user.selectOptions(await screen.findByRole('combobox', { name: '退款状态' }), '__all__');
    const row = await screen.findByRole('row', { name: /RETRY-001/ });
    fireEvent.click(within(row).getByRole('button', { name: /详情/ }));
    const dialog = (await screen.findByText('退款详情')).closest('.ant-modal') as HTMLElement;
    const retryButton = within(dialog).getByRole('button', { name: '重试失败项' });

    fireEvent.click(retryButton);
    fireEvent.click(retryButton);

    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(1));
    expect(within(dialog).getByRole('button', { name: /重试失败项/ })).toBeDisabled();

    retry.resolve({ data: { code: 200, message: 'OK', data: refreshedRefund } });

    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('失败退款项已重新提交，请查看渠道处理状态'));
    const itemRow = within(dialog).getByRole('row', { name: /payment-service-1/ });
    expect(within(itemRow).getByText('PROCESSING')).toBeInTheDocument();
    expect(within(itemRow).getByText('RETRY_QUEUED')).toBeInTheDocument();
    expect(within(itemRow).getByText('已重新提交渠道')).toBeInTheDocument();
  });

  it('refreshes open retry details and reports the retry error after a failed request', async () => {
    const user = userEvent.setup();
    const errorSpy = vi.spyOn(message, 'error');
    const refreshedRefund = {
      ...failedProcessingRefund,
      items: [{
        ...failedProcessingRefund.items[0],
        failureCode: 'PROVIDER_UNAVAILABLE',
        failureMessage: '渠道仍不可用',
        updatedAt: '2026-08-22T10:05:00',
      }],
    };
    mockGet
      .mockImplementationOnce(() => response([failedProcessingRefund]))
      .mockImplementationOnce(() => response([refreshedRefund]));
    mockPost.mockRejectedValueOnce(new Error('渠道仍不可用'));

    render(<RefundsPage />);
    await user.selectOptions(await screen.findByRole('combobox', { name: '退款状态' }), '__all__');
    const row = await screen.findByRole('row', { name: /RETRY-001/ });
    fireEvent.click(within(row).getByRole('button', { name: /详情/ }));
    const dialog = (await screen.findByText('退款详情')).closest('.ant-modal') as HTMLElement;

    fireEvent.click(within(dialog).getByRole('button', { name: '重试失败项' }));

    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(2));
    expect(errorSpy).toHaveBeenCalledWith('重试失败: 渠道仍不可用');
    const itemRow = within(dialog).getByRole('row', { name: /payment-service-1/ });
    expect(within(itemRow).getByText('PROVIDER_UNAVAILABLE')).toBeInTheDocument();
    expect(within(itemRow).getByText('渠道仍不可用')).toBeInTheDocument();
  });
});
