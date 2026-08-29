import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../api';
import IdentityManagementPage from './IdentityManagementPage';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    default: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
  };
});

const mockGet = vi.mocked(api.get);
const mockPost = vi.mocked(api.post);

beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: false, media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});

beforeEach(() => {
  mockGet.mockResolvedValue({ data: { code: 200, message: 'OK', data: [] } });
});

afterEach(cleanup);

describe('IdentityManagementPage', () => {
  it('keeps document actions inside a constrained, horizontally scrollable material table', async () => {
    mockGet.mockResolvedValue({
      data: {
        code: 200,
        message: 'OK',
        data: [{
          id: 'application-1',
          userId: 'user-1',
          userName: '法人测试甲',
          roleCode: 'INSTITUTION_LEGAL_REPRESENTATIVE',
          status: 'PENDING',
          applicationData: {},
          reviewNote: '',
          documents: [{
            fileId: 'document-1',
            documentType: 'BUSINESS_LICENSE',
            originalName: '闵侯-重要天气预警报告-2026032_27247_260828_174615(1).pdf',
            contentType: 'application/pdf',
            sizeBytes: 328704,
            status: 'ACTIVE',
          }],
        }],
      },
    });
    render(<IdentityManagementPage />);

    const materialsButton = (await screen.findByText('材料')).closest('button');
    if (!materialsButton) throw new Error('材料按钮未渲染');
    fireEvent.click(materialsButton);
    const dialog = (await screen.findByText('申请材料')).closest('.ant-modal');
    if (!(dialog instanceof HTMLElement)) throw new Error('申请材料弹窗未渲染');
    const materialTable = within(dialog).getByText('认证材料').parentElement?.querySelector('.ant-table-wrapper');
    if (!materialTable) throw new Error('认证材料表格未渲染');

    expect(dialog).toHaveStyle({ width: '960px' });
    expect(materialTable).toHaveStyle({ maxWidth: '100%' });
    expect(materialTable.querySelector('table')).toHaveStyle({ tableLayout: 'fixed' });
    expect(materialTable.querySelector('.ant-table-cell-fix-end')).toHaveTextContent('操作');
    expect(within(dialog).getByText('安全查看').closest('button')).toBeInTheDocument();
  });

  it('keeps generic creation while removing the direct consultant binding entry', async () => {
    const user = userEvent.setup();
    render(<IdentityManagementPage />);

    await user.click(screen.getByRole('tab', { name: '机构成员' }));

    expect(screen.getByRole('button', { name: /新增任职关系/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /添加咨询师/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /添加医美顾问/ })).not.toBeInTheDocument();
    await waitFor(() => expect(mockGet).toHaveBeenCalledWith(
      '/admin/identity/memberships',
      expect.anything(),
    ));
    expect(mockGet).not.toHaveBeenCalledWith('/admin/users');
    expect(mockPost).not.toHaveBeenCalledWith('/admin/identity/consultants', expect.anything());
  });

  it('states that generic consultant creation becomes effective immediately', async () => {
    const user = userEvent.setup();
    render(<IdentityManagementPage />);

    await user.click(screen.getByRole('tab', { name: '机构成员' }));
    await user.click(screen.getByRole('button', { name: /新增任职关系/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('新增机构任职关系')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('combobox', { name: '任职身份' }));
    await user.click(await screen.findByText('医美顾问'));

    expect(within(dialog).getByRole('button', { name: '创建并立即生效' })).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: '创建并进入待审核' })).not.toBeInTheDocument();
  });
});
