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
  vi.unstubAllGlobals();
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

    const pendingRow = await screen.findByRole('row', { name: /service-order-pending/ });
    const reviewRow = screen.getByRole('row', { name: /service-order-review/ });
    const serviceRow = screen.getByRole('row', { name: /service-order-processing/ });
    const refundedRow = screen.getByRole('row', { name: /service-order-refunded/ });
    expect(within(pendingRow).getByText('待支付旅游地接服务费')).toBeInTheDocument();
    expect(within(reviewRow).getByText('旅游地接服务退款审核中')).toBeInTheDocument();
    expect(within(serviceRow).getByText('旅游地接服务退款渠道处理中')).toBeInTheDocument();
    expect(within(refundedRow).getByText('旅游地接服务已退款')).toBeInTheDocument();

    expect(within(serviceRow).getByText('旅游地接服务费')).toBeInTheDocument();
    expect(within(serviceRow).getAllByText('USD 123.45')).toHaveLength(2);
    expect(within(serviceRow).queryByRole('button', { name: /变更状态/ })).not.toBeInTheDocument();
    expect(within(serviceRow).queryByRole('button', { name: /手动核销/ })).not.toBeInTheDocument();
    expect(within(serviceRow).queryByText('$99')).not.toBeInTheDocument();
    expect(within(serviceRow).queryByText('$901')).not.toBeInTheDocument();
    expect(within(serviceRow).queryByText('service-verified-at')).not.toBeInTheDocument();
    expect(within(serviceRow).queryByText('service-settlement-at')).not.toBeInTheDocument();

    const rejectedRow = screen.getByRole('row', { name: /service-order-rejected/ });
    expect(within(rejectedRow).getByText('旅游地接服务已激活')).toBeInTheDocument();
    expect(within(rejectedRow).getAllByText('USD 222.22')).toHaveLength(1);
    const activeRow = screen.getByRole('row', { name: /service-order-active/ });
    expect(within(activeRow).getByText('旅游地接服务已激活')).toBeInTheDocument();
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
  it('shows an explicit loading error and clears it after a successful retry', async () => {
    const user = userEvent.setup();
    mockGet
      .mockRejectedValueOnce(new Error('退款列表暂不可用'))
      .mockImplementationOnce((url) => url === '/admin/refunds'
        ? response([serviceRefund])
        : response([]));

    render(<RefundsPage />);

    const reloadButton = await screen.findByRole('button', { name: '重新加载' });
    const loadErrorAlert = reloadButton.closest('[role="alert"]') as HTMLElement;
    expect(loadErrorAlert).toHaveTextContent('退款列表加载失败: 退款列表暂不可用');
    expect(screen.queryByRole('row', { name: /SO-001/ })).not.toBeInTheDocument();

    await user.click(reloadButton);

    expect(await screen.findByRole('row', { name: /SO-001/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('button', { name: '重新加载' })).not.toBeInTheDocument());
    expect(screen.queryByText('退款列表加载失败: 退款列表暂不可用')).not.toBeInTheDocument();
  });

  it('shows both service-active and completed origins for pending refund requests', async () => {
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([
          { ...serviceRefund, id: 'refund-service-active', orderNo: 'SO-ACTIVE', originalStatus: 'SERVICE_ACTIVE' },
          { ...serviceRefund, id: 'refund-service-completed', orderNo: 'SO-COMPLETED', originalStatus: 'COMPLETED' },
        ])
      : response([]));

    render(<RefundsPage />);

    const activeRow = await screen.findByRole('row', { name: /SO-ACTIVE/ });
    expect(within(activeRow).getByText('服务进行中')).toBeInTheDocument();
    const completedRow = screen.getByRole('row', { name: /SO-COMPLETED/ });
    expect(within(completedRow).getByText('已完成')).toBeInTheDocument();
  });

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

  it('submits approval once and keeps review actions locked when the required refresh fails', async () => {
    const errorSpy = vi.spyOn(message, 'error');
    const approval = deferred<any>();
    const otherRefund = {
      ...serviceRefund,
      id: 'refund-service-2',
      orderId: 'service-order-2',
      orderNo: 'SO-002',
    };
    mockGet
      .mockImplementationOnce(() => response([serviceRefund, otherRefund]))
      .mockRejectedValueOnce(new Error('退款列表暂不可用'));
    mockPut.mockImplementationOnce(() => approval.promise);

    render(<RefundsPage />);
    const row = await screen.findByRole('row', { name: /SO-001/ });
    fireEvent.click(within(row).getByRole('button', { name: /批准/ }));
    const dialog = (await screen.findByText('确认批准退款')).closest('.ant-modal') as HTMLElement;
    const approveButton = within(dialog).getByRole('button', { name: '确认批准' });

    fireEvent.click(approveButton);
    fireEvent.click(approveButton);
    await waitFor(() => expect(mockPut).toHaveBeenCalledTimes(1));

    approval.resolve({
      data: { code: 200, message: 'OK', data: { ...serviceRefund, status: 'REFUND_PROCESSING' } },
    });

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('刷新退款列表失败: 退款列表暂不可用，请刷新页面确认状态'));
    expect(within(row).getByRole('button', { name: /批准/ })).toBeDisabled();
    expect(within(row).getByRole('button', { name: /拒绝/ })).toBeDisabled();
    const otherRow = screen.getByRole('row', { name: /SO-002/ });
    expect(within(otherRow).getByRole('button', { name: /批准/ })).toBeEnabled();
    expect(within(otherRow).getByRole('button', { name: /拒绝/ })).toBeEnabled();
  });

  it('submits rejection once and locks that row when the required refresh fails', async () => {
    const user = userEvent.setup();
    const errorSpy = vi.spyOn(message, 'error');
    const rejection = deferred<any>();
    mockGet
      .mockImplementationOnce(() => response([serviceRefund]))
      .mockRejectedValueOnce(new Error('退款列表暂不可用'));
    mockPut.mockImplementationOnce(() => rejection.promise);

    render(<RefundsPage />);
    const row = await screen.findByRole('row', { name: /SO-001/ });
    fireEvent.click(within(row).getByRole('button', { name: /拒绝/ }));
    const dialog = (await screen.findByText('拒绝退款申请')).closest('.ant-modal') as HTMLElement;
    await user.type(within(dialog).getByPlaceholderText('请填写拒绝原因（必填）'), '资料不足');
    const rejectButton = within(dialog).getByRole('button', { name: '确认拒绝' });

    fireEvent.click(rejectButton);
    fireEvent.click(rejectButton);
    await waitFor(() => expect(mockPut).toHaveBeenCalledTimes(1));

    rejection.resolve({ data: { code: 200, message: 'OK', data: null } });
    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('刷新退款列表失败: 退款列表暂不可用，请刷新页面确认状态'));
    expect(within(row).getByRole('button', { name: /批准/ })).toBeDisabled();
    expect(within(row).getByRole('button', { name: /拒绝/ })).toBeDisabled();
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

  it('releases the retry control and reports a failed refresh after retry submission', async () => {
    const user = userEvent.setup();
    const successSpy = vi.spyOn(message, 'success');
    const errorSpy = vi.spyOn(message, 'error');
    mockGet
      .mockImplementationOnce(() => response([failedProcessingRefund]))
      .mockRejectedValue(new Error('退款列表暂不可用'));
    mockPost.mockResolvedValue({ data: { code: 200, message: 'OK', data: failedProcessingRefund } });

    render(<RefundsPage />);
    await user.selectOptions(await screen.findByRole('combobox', { name: '退款状态' }), '__all__');
    const row = await screen.findByRole('row', { name: /RETRY-001/ });
    fireEvent.click(within(row).getByRole('button', { name: /详情/ }));
    const dialog = (await screen.findByText('退款详情')).closest('.ant-modal') as HTMLElement;
    const retryButton = within(dialog).getByRole('button', { name: '重试失败项' });

    fireEvent.click(retryButton);

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('失败退款项已重新提交，请查看渠道处理状态'));
    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('刷新退款列表失败: 退款列表暂不可用'));
    const releasedRetryButton = within(dialog).getByRole('button', { name: /重试失败项/ });
    expect(releasedRetryButton).toBeEnabled();
    expect(releasedRetryButton).not.toHaveClass('ant-btn-loading');

    fireEvent.click(releasedRetryButton);
    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(errorSpy).toHaveBeenCalledTimes(2));
  });

  it('refunds table and detail show new evidence count and ordered metadata', async () => {
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([{
          ...serviceRefund,
          evidenceUrl: 'https://legacy.example/receipt.pdf',
          evidenceFiles: [
            { fileId: 'file-pdf', originalName: 'later.pdf', contentType: 'application/pdf', sizeBytes: 1048576, position: 2 },
            { fileId: 'file-image', originalName: 'first.jpg', contentType: 'image/jpeg', sizeBytes: 1536, position: 1 },
            { fileId: '', originalName: 'broken.png', contentType: 'image/png', sizeBytes: 10, position: 3 },
            null,
          ],
        }])
      : response([]));

    render(<RefundsPage />);

    const row = await screen.findByRole('row', { name: /SO-001/ });
    expect(within(row).getByText('3')).toBeInTheDocument();
    await userEvent.setup().click(within(row).getByRole('button', { name: /详情/ }));

    const dialog = (await screen.findByText('退款详情')).closest('.ant-modal') as HTMLElement;
    const metadata = within(dialog).getAllByTestId('refund-evidence-metadata');
    expect(metadata).toHaveLength(2);
    expect(metadata[0]).toHaveTextContent('first.jpg');
    expect(metadata[0]).toHaveTextContent('image/jpeg');
    expect(metadata[0]).toHaveTextContent('1.5 KB');
    expect(metadata[1]).toHaveTextContent('later.pdf');
    expect(metadata[1]).toHaveTextContent('application/pdf');
    expect(metadata[1]).toHaveTextContent('1.0 MB');
  });

  it('authenticated evidence image preview requests a blob and revokes its object URL when detail closes', async () => {
    const user = userEvent.setup();
    const createObjectURL = vi.fn(() => 'blob:image-preview');
    const revokeObjectURL = vi.fn();
    class TestUrl extends URL {}
    Object.assign(TestUrl, { createObjectURL, revokeObjectURL });
    vi.stubGlobal('URL', TestUrl);
    const imageBlob = new Blob(['image'], { type: 'image/jpeg' });
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([{
          ...serviceRefund,
          evidenceFiles: [{ fileId: 'file-image', originalName: 'receipt.jpg', contentType: 'image/jpeg', sizeBytes: 12, position: 1 }],
        }])
      : Promise.resolve({ data: imageBlob }));

    render(<RefundsPage />);
    const row = await screen.findByRole('row', { name: /SO-001/ });
    await user.click(within(row).getByRole('button', { name: /详情/ }));
    const dialog = (await screen.findByText('退款详情')).closest('.ant-modal') as HTMLElement;
    await user.click(within(dialog).getByRole('button', { name: '预览 receipt.jpg' }));

    await waitFor(() => expect(mockGet).toHaveBeenCalledWith(
      '/admin/refunds/refund-service-1/evidence/file-image/content',
      { responseType: 'blob' },
    ));
    expect(createObjectURL).toHaveBeenCalledWith(imageBlob);
    expect(within(dialog).getByRole('img', { name: 'receipt.jpg' })).toHaveAttribute('src', 'blob:image-preview');

    await user.click(within(dialog).getByRole('button', { name: /关\s*闭/ }));
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:image-preview');
  });

  it('PDF preview requests a blob and opens only the generated blob URL with noopener', async () => {
    const user = userEvent.setup();
    const createObjectURL = vi.fn(() => 'blob:pdf-preview');
    class TestUrl extends URL {}
    Object.assign(TestUrl, { createObjectURL, revokeObjectURL: vi.fn() });
    vi.stubGlobal('URL', TestUrl);
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const pdfBlob = new Blob(['pdf'], { type: 'application/pdf' });
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([{
          ...serviceRefund,
          evidenceFiles: [{ fileId: 'file-pdf', originalName: 'receipt.pdf', contentType: 'application/pdf', sizeBytes: 12, position: 1 }],
        }])
      : Promise.resolve({ data: pdfBlob }));

    render(<RefundsPage />);
    const row = await screen.findByRole('row', { name: /SO-001/ });
    await user.click(within(row).getByRole('button', { name: /详情/ }));
    const dialog = (await screen.findByText('退款详情')).closest('.ant-modal') as HTMLElement;
    await user.click(within(dialog).getByRole('button', { name: '预览 receipt.pdf' }));

    await waitFor(() => expect(mockGet).toHaveBeenCalledWith(
      '/admin/refunds/refund-service-1/evidence/file-pdf/content',
      { responseType: 'blob' },
    ));
    expect(createObjectURL).toHaveBeenCalledWith(pdfBlob);
    expect(openSpy).toHaveBeenCalledWith('blob:pdf-preview', '_blank', 'noopener,noreferrer');
  });

  it('evidence download uses an authenticated blob and releases its temporary anchor', async () => {
    const user = userEvent.setup();
    const createObjectURL = vi.fn(() => 'blob:download');
    const revokeObjectURL = vi.fn();
    class TestUrl extends URL {}
    Object.assign(TestUrl, { createObjectURL, revokeObjectURL });
    vi.stubGlobal('URL', TestUrl);
    const evidenceBlob = new Blob(['evidence'], { type: 'image/jpeg' });
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([{
          ...serviceRefund,
          evidenceFiles: [{ fileId: 'file-image', originalName: 'receipt.jpg', contentType: 'image/jpeg', sizeBytes: 12, position: 1 }],
        }])
      : Promise.resolve({ data: evidenceBlob }));

    render(<RefundsPage />);
    const row = await screen.findByRole('row', { name: /SO-001/ });
    await user.click(within(row).getByRole('button', { name: /详情/ }));
    const dialog = (await screen.findByText('退款详情')).closest('.ant-modal') as HTMLElement;
    const nativeCreateElement = document.createElement.bind(document);
    let temporaryAnchor: HTMLAnchorElement | null = null;
    vi.spyOn(document, 'createElement').mockImplementation(((tagName: string) => {
      const element = nativeCreateElement(tagName);
      if (tagName === 'a') temporaryAnchor = element as HTMLAnchorElement;
      return element;
    }) as typeof document.createElement);
    const appendSpy = vi.spyOn(document.body, 'appendChild');
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    await user.click(within(dialog).getByRole('button', { name: '下载 receipt.jpg' }));

    await waitFor(() => expect(mockGet).toHaveBeenCalledWith(
      '/admin/refunds/refund-service-1/evidence/file-image/content',
      { responseType: 'blob' },
    ));
    expect(createObjectURL).toHaveBeenCalledWith(evidenceBlob);
    expect(temporaryAnchor).not.toBeNull();
    expect(temporaryAnchor?.download).toBe('receipt.jpg');
    expect(appendSpy).toHaveBeenCalledWith(temporaryAnchor);
    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(temporaryAnchor?.isConnected).toBe(false);
    expect(document.body.querySelector('a[download="receipt.jpg"]')).toBeNull();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:download');
  });

  it('evidence download failure shows an error without locking approve reject or retry actions', async () => {
    const user = userEvent.setup();
    const errorSpy = vi.spyOn(message, 'error');
    const createObjectURL = vi.fn(() => 'blob:download-failure');
    const revokeObjectURL = vi.fn();
    class TestUrl extends URL {}
    Object.assign(TestUrl, { createObjectURL, revokeObjectURL });
    vi.stubGlobal('URL', TestUrl);
    const evidenceBlob = new Blob(['evidence'], { type: 'image/jpeg' });
    const retryableRefund = {
      ...failedProcessingRefund,
      evidenceFiles: [{ fileId: 'file-retry', originalName: 'retry.png', contentType: 'image/png', sizeBytes: 12, position: 1 }],
    };
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([{
          ...serviceRefund,
          evidenceFiles: [{ fileId: 'file-image', originalName: 'broken.jpg', contentType: 'image/jpeg', sizeBytes: 12, position: 1 }],
        }, retryableRefund])
      : Promise.resolve({ data: evidenceBlob }));

    render(<RefundsPage />);
    await user.selectOptions(await screen.findByRole('combobox', { name: '退款状态' }), '__all__');
    const pendingRow = await screen.findByRole('row', { name: /SO-001/ });
    const retryRow = screen.getByRole('row', { name: /RETRY-001/ });
    await user.click(within(pendingRow).getByRole('button', { name: /详情/ }));
    const dialog = (await screen.findByText('退款详情')).closest('.ant-modal') as HTMLElement;
    const nativeCreateElement = document.createElement.bind(document);
    let temporaryAnchor: HTMLAnchorElement | null = null;
    vi.spyOn(document, 'createElement').mockImplementation(((tagName: string) => {
      const element = nativeCreateElement(tagName);
      if (tagName === 'a') temporaryAnchor = element as HTMLAnchorElement;
      return element;
    }) as typeof document.createElement);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => { throw new Error('点击失败'); });
    await user.click(within(dialog).getByRole('button', { name: '下载 broken.jpg' }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('下载凭证失败: 点击失败'));
    expect(createObjectURL).toHaveBeenCalledWith(evidenceBlob);
    expect(temporaryAnchor?.isConnected).toBe(false);
    expect(document.body.querySelector('a[download="broken.jpg"]')).toBeNull();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:download-failure');
    expect(within(pendingRow).getByRole('button', { name: /批准/ })).toBeEnabled();
    expect(within(pendingRow).getByRole('button', { name: /拒绝/ })).toBeEnabled();
    expect(within(retryRow).getByRole('button', { name: '重试失败项' })).toBeEnabled();
  });

  it('legacy evidence accepts at most five absolute http or https URLs and rejects data blob file javascript relative and protocol-relative values', async () => {
    mockGet.mockImplementation((url) => url === '/admin/refunds'
      ? response([{
          ...serviceRefund,
          evidenceUrl: [
            'https://one.example/a', 'http://two.example/b', 'data:text/plain,unsafe', 'blob:https://app.example/blob',
            'file:///tmp/unsafe', 'javascript:alert(1)', '/relative', '//protocol-relative.example/path',
            'https://three.example/c', 'https://four.example/d', 'https://five.example/e', 'https://six.example/f',
          ].join(','),
        }])
      : response([]));

    render(<RefundsPage />);
    const row = await screen.findByRole('row', { name: /SO-001/ });
    expect(within(row).getByText('5')).toBeInTheDocument();
    await userEvent.setup().click(within(row).getByRole('button', { name: /详情/ }));

    const dialog = (await screen.findByText('退款详情')).closest('.ant-modal') as HTMLElement;
    const legacyLinks = within(dialog).getAllByRole('link', { name: '旧版凭证（公开链接）' });
    expect(legacyLinks).toHaveLength(5);
    expect(legacyLinks.map(link => link.getAttribute('href'))).toEqual([
      'https://one.example/a', 'http://two.example/b', 'https://three.example/c', 'https://four.example/d', 'https://five.example/e',
    ]);
  });
});
