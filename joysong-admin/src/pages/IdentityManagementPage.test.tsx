import { cleanup, render, screen, waitFor } from '@testing-library/react';
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

describe('IdentityManagementPage institution memberships', () => {
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
});
