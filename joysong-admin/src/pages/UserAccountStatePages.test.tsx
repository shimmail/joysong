import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../api';
import UserDetailPage from './UserDetailPage';
import UsersPage from './UsersPage';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    default: { get: vi.fn(), put: vi.fn() },
  };
});

const mockGet = vi.mocked(api.get);
const mockPut = vi.mocked(api.put);

const response = <T,>(data: T) => ({ data: { code: 200, message: 'OK', data } });

const users = [
  { id: 'active-user', nickname: '活跃用户', role: 'USER', accountState: 'ACTIVE', createdAt: '2026-08-01' },
  { id: 'suspended-user', nickname: '暂停用户', role: 'USER', accountState: 'ADMIN_SUSPENDED', createdAt: '2026-08-02' },
  { id: 'admin-user', nickname: '管理员用户', role: 'ADMIN', accountState: 'ACTIVE', createdAt: '2026-08-02' },
  { id: 'erased-user', nickname: '注销用户', role: 'USER', accountState: 'ERASED', createdAt: '2026-08-03' },
  { id: 'state-wins-user', nickname: '新状态优先用户', role: 'USER', accountState: 'ACTIVE', deletedAt: '2026-08-04' },
  { id: 'legacy-erased-user', nickname: '旧响应注销用户', role: 'USER', deletedAt: '2026-08-05' },
];

beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(query => ({
    matches: false, media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
});

beforeEach(() => {
  mockGet.mockImplementation(async (url) => {
    if (url === '/admin/users') return response(users) as never;
    if (url === '/admin/identity/roles') return response([]) as never;
    if (url === '/admin/users/erased-user') return response(users[3]) as never;
    if (url === '/admin/users/suspended-user') return response(users[1]) as never;
    if (url === '/admin/users/admin-user') return response(users[2]) as never;
    if (url.endsWith('/orders') || url.endsWith('/diaries')) return response([]) as never;
    throw new Error(`Unexpected GET ${url}`);
  });
  mockPut.mockResolvedValue(response(null) as never);
});

afterEach(cleanup);

describe('UsersPage account lifecycle', () => {
  it('uses accountState as the primary value and exposes only valid transitions', async () => {
    render(<MemoryRouter><UsersPage /></MemoryRouter>);

    const activeRow = (await screen.findByText('活跃用户')).closest('tr')!;
    const suspendedRow = screen.getByText('暂停用户').closest('tr')!;
    const erasedRow = screen.getByText('注销用户').closest('tr')!;

    expect(within(activeRow).getByText('正常 / Active')).toBeInTheDocument();
    expect(within(activeRow).getByRole('button', { name: /暂停$/ })).toBeInTheDocument();
    expect(within(activeRow).queryByRole('button', { name: /恢复$/ })).not.toBeInTheDocument();

    expect(within(suspendedRow).getByText('已暂停 / Suspended')).toBeInTheDocument();
    expect(within(suspendedRow).getByRole('button', { name: /恢复$/ })).toBeInTheDocument();
    expect(within(suspendedRow).queryByRole('button', { name: /暂停$/ })).not.toBeInTheDocument();

    expect(within(erasedRow).getByText('已注销 / Erased')).toBeInTheDocument();
    expect(within(erasedRow).queryByRole('button', { name: /暂停$|恢复$|永久删除/ })).not.toBeInTheDocument();
  });

  it('renders role as read-only and hides lifecycle actions for ADMIN accounts', async () => {
    render(<MemoryRouter><UsersPage /></MemoryRouter>);

    const adminRow = (await screen.findByText('管理员用户')).closest('tr')!;

    expect(within(adminRow).getByText('管理员账号')).toBeInTheDocument();
    expect(within(adminRow).queryByRole('combobox')).not.toBeInTheDocument();
    expect(within(adminRow).queryByRole('button', { name: /暂停$|恢复$/ })).not.toBeInTheDocument();
  });

  it('keeps the existing suspend and restore endpoints behind the new state actions', async () => {
    const actor = userEvent.setup();
    render(<MemoryRouter><UsersPage /></MemoryRouter>);

    const activeRow = (await screen.findByText('活跃用户')).closest('tr')!;
    await actor.click(within(activeRow).getByRole('button', { name: /暂停$/ }));
    await actor.click(await screen.findByRole('button', { name: /确认暂停/ }));
    await waitFor(() => expect(mockPut).toHaveBeenCalledWith('/admin/users/active-user/deactivate'));

    const suspendedRow = screen.getByText('暂停用户').closest('tr')!;
    await actor.click(within(suspendedRow).getByRole('button', { name: /恢复$/ }));
    await waitFor(() => expect(mockPut).toHaveBeenCalledWith('/admin/users/suspended-user/reactivate'));
  });

  it('uses deletedAt only when an old response has no accountState', async () => {
    render(<MemoryRouter><UsersPage /></MemoryRouter>);

    const primaryStateRow = (await screen.findByText('新状态优先用户')).closest('tr')!;
    const legacyErasedRow = screen.getByText('旧响应注销用户').closest('tr')!;

    expect(within(primaryStateRow).getByText('正常 / Active')).toBeInTheDocument();
    expect(within(primaryStateRow).getByRole('button', { name: /暂停$/ })).toBeInTheDocument();
    expect(within(legacyErasedRow).getByText('已注销 / Erased')).toBeInTheDocument();
    expect(within(legacyErasedRow).queryByRole('button', { name: /暂停$|恢复$|永久删除/ })).not.toBeInTheDocument();
  });
});

describe('UserDetailPage account lifecycle', () => {
  it('renders ERASED as immutable without restore, suspend, or permanent-delete actions', async () => {
    render(
      <MemoryRouter initialEntries={['/users/erased-user']}>
        <Routes><Route path="/users/:id" element={<UserDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText('已注销 / Erased')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /暂停用户$|恢复用户$|永久删除/ })).not.toBeInTheDocument();
  });

  it('lets an ADMIN_SUSPENDED account return only to ACTIVE', async () => {
    const actor = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/users/suspended-user']}>
        <Routes><Route path="/users/:id" element={<UserDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText('已暂停 / Suspended')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /暂停用户$/ })).not.toBeInTheDocument();
    await actor.click(screen.getByRole('button', { name: /恢复用户$/ }));
    await waitFor(() => expect(mockPut).toHaveBeenCalledWith('/admin/users/suspended-user/reactivate'));
  });

  it('renders an ADMIN role as read-only without suspend or restore controls', async () => {
    render(
      <MemoryRouter initialEntries={['/users/admin-user']}>
        <Routes><Route path="/users/:id" element={<UserDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText('管理员账号')).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /暂停用户$|恢复用户$/ })).not.toBeInTheDocument();
  });
});
