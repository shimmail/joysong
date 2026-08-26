import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axios, { type InternalAxiosRequestConfig } from 'axios';
import { createElement } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import api, {
  clearAdminToken,
  getApiErrorCode,
  getDefaultManagementPath,
  loginAdminSession,
  logoutManagementSession,
  restoreAdminSession,
  setAdminToken,
  type ManagementContext,
} from './api';

vi.mock('./pages/DashboardPage', () => ({
  default: () => '管理员首页',
}));

vi.mock('./pages/ProjectsPage', () => ({
  default: () => '管理员项目页面',
}));

vi.mock('./pages/ProjectCollaborationPage', () => ({
  default: () => '医生项目协作页面',
}));

vi.mock('./pages/DoctorsPage', () => ({
  default: () => '医生管理页面',
}));

const adminContext: ManagementContext = {
  userId: 'admin-user-1',
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

const doctorContext: ManagementContext = {
  ...adminContext,
  userId: 'doctor-user-1',
  platformRole: 'USER',
  activeRoles: ['DOCTOR'],
  doctorId: 'doctor-1',
  canManageInstitutions: false,
  canManageInstitutionProjects: false,
  canManageArticles: false,
  canManageSplitConfigs: false,
  canManageOrders: false,
  canApplyToInstitutions: true,
  canReviewInstitutionRequests: false,
  canSubmitInstitutionProjectRequests: true,
  canReviewInstitutionProjectRequests: false,
};

function managementToken(
  context: ManagementContext,
  expiresAt = Math.floor(Date.now() / 1000) + 3600,
) {
  const payload = window.btoa(JSON.stringify({
    exp: expiresAt,
    role: context.platformRole,
    sub: context.userId,
  })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `e30.${payload}.signature`;
}

function loginResult(context = adminContext, refreshToken = 'admin-refresh-token') {
  const accessToken = managementToken(context);
  return {
    token: accessToken,
    accessToken,
    refreshToken,
    tokenType: 'Bearer',
    expiresIn: 3600,
    user: { id: context.userId, role: context.platformRole },
  };
}

function success<T>(data: T) {
  return { data: { code: 200, message: 'success', data } };
}

function httpError(status: number) {
  return {
    isAxiosError: true,
    response: { status, data: { code: status, message: '请求失败', data: null } },
  };
}

describe('admin authentication session', () => {
  beforeAll(() => {
    window.matchMedia = vi.fn().mockImplementation(query => ({
      matches: false, media: query, onchange: null,
      addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
    }));
    globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
  });

  beforeEach(() => {
    clearAdminToken();
    sessionStorage.clear();
    localStorage.clear();
    window.history.pushState({}, '', '/');
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('logs in through the admin endpoint and persists only after server context confirms ADMIN', async () => {
    const result = loginResult();
    const post = vi.spyOn(api, 'post').mockResolvedValue(success(result));
    const get = vi.spyOn(axios, 'get').mockResolvedValue(success(adminContext));

    await expect(loginAdminSession('13800138000', 'Admin-password1!')).resolves.toEqual(adminContext);

    expect(post).toHaveBeenCalledWith('/admin/login', {
      phone: '13800138000',
      password: 'Admin-password1!',
    });
    expect(get).toHaveBeenCalledWith('/api/management/context', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: `Bearer ${result.accessToken}` }),
    }));
    expect(sessionStorage.getItem('admin_token')).toBe(result.accessToken);
    expect(sessionStorage.getItem('admin_refresh_token')).toBe('admin-refresh-token');
    expect(JSON.parse(sessionStorage.getItem('management_context') || '{}')).toEqual(adminContext);
  });

  it('rejects a non-admin login response without requesting context or saving credentials', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(success(loginResult(doctorContext)));
    const get = vi.spyOn(axios, 'get');

    await expect(loginAdminSession('13800138000', 'correct-password')).rejects.toThrow('管理员身份验证失败');

    expect(post).toHaveBeenCalledWith('/admin/login', expect.any(Object));
    expect(get).not.toHaveBeenCalled();
    expect(sessionStorage.getItem('admin_token')).toBeNull();
  });

  it('rejects a login when the shared context is not ADMIN', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(success(loginResult()));
    vi.spyOn(axios, 'get').mockResolvedValue(success({ ...doctorContext, userId: adminContext.userId }));

    await expect(loginAdminSession('13800138000', 'Admin-password1!')).rejects.toThrow('管理员身份验证失败');

    expect(sessionStorage.getItem('admin_token')).toBeNull();
    expect(sessionStorage.getItem('management_context')).toBeNull();
  });

  it('rejects a login when token, user, and context identities do not match', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(success(loginResult()));
    vi.spyOn(axios, 'get').mockResolvedValue(success({ ...adminContext, userId: 'another-admin' }));

    await expect(loginAdminSession('13800138000', 'Admin-password1!')).rejects.toThrow('管理员身份验证失败');

    expect(sessionStorage.getItem('admin_token')).toBeNull();
  });

  it('does not render a protected deep link before the server confirms the cached session', async () => {
    setAdminToken(managementToken(adminContext), adminContext, 'admin-refresh-token');
    window.history.pushState({}, '', '/projects');
    let resolveContext!: (value: ReturnType<typeof success<ManagementContext>>) => void;
    vi.spyOn(axios, 'get').mockImplementation(() => new Promise((resolve) => {
      resolveContext = resolve;
    }));

    render(createElement(App));

    expect(screen.getByText('正在验证管理员身份')).toBeInTheDocument();
    expect(screen.queryByText('管理员项目页面')).not.toBeInTheDocument();
    expect(screen.queryByText('功能导航')).not.toBeInTheDocument();

    resolveContext(success(adminContext));
    expect(await screen.findByText('管理员项目页面')).toBeInTheDocument();
  });

  it('rejects a forged professional-role cache and direct protected URL', async () => {
    setAdminToken(managementToken(doctorContext), doctorContext, 'doctor-refresh-token');
    window.history.pushState({}, '', '/project-collaboration');
    const get = vi.spyOn(axios, 'get');

    render(createElement(App));

    expect(await screen.findByText('娇颜颂管理系统')).toBeInTheDocument();
    expect(screen.queryByText('医生项目协作页面')).not.toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
    expect(sessionStorage.getItem('admin_token')).toBeNull();
  });

  it('rejects cached ADMIN state when the server context no longer confirms ADMIN', async () => {
    setAdminToken(managementToken(adminContext), adminContext, 'admin-refresh-token');
    window.history.pushState({}, '', '/projects');
    vi.spyOn(axios, 'get').mockResolvedValue(success({ ...doctorContext, userId: adminContext.userId }));

    render(createElement(App));

    expect(await screen.findByText('娇颜颂管理系统')).toBeInTheDocument();
    expect(screen.queryByText('管理员项目页面')).not.toBeInTheDocument();
    expect(sessionStorage.getItem('admin_token')).toBeNull();
  });

  it('refreshes a 401 only once and re-confirms ADMIN before restoring the session', async () => {
    setAdminToken(managementToken(adminContext), adminContext, 'old-refresh-token');
    const refreshed = loginResult(adminContext, 'new-refresh-token');
    const get = vi.spyOn(axios, 'get')
      .mockRejectedValueOnce(httpError(401))
      .mockResolvedValueOnce(success(adminContext));
    const post = vi.spyOn(axios, 'post').mockResolvedValue(success(refreshed));

    await expect(restoreAdminSession()).resolves.toEqual(adminContext);

    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/auth/refresh', { refreshToken: 'old-refresh-token' }, expect.any(Object));
    expect(get).toHaveBeenCalledTimes(2);
    expect(sessionStorage.getItem('admin_token')).toBe(refreshed.accessToken);
    expect(sessionStorage.getItem('admin_refresh_token')).toBe('new-refresh-token');
  });

  it('clears the session when a retried business request is still unauthorized', async () => {
    setAdminToken(managementToken(adminContext), adminContext, 'old-refresh-token');
    window.history.pushState({}, '', '/login');
    const originalAdapter = api.defaults.adapter;
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({
      data: { code: 401, message: '登录状态已失效', data: null },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }));
    api.defaults.adapter = adapter as typeof api.defaults.adapter;
    const post = vi.spyOn(axios, 'post').mockResolvedValue(success(loginResult(adminContext, 'new-refresh-token')));
    vi.spyOn(axios, 'get').mockResolvedValue(success(adminContext));

    try {
      await expect(api.get('/protected-action')).rejects.toMatchObject({ code: '401' });
      expect(adapter).toHaveBeenCalledTimes(2);
      expect(post).toHaveBeenCalledTimes(1);
      expect(sessionStorage.getItem('admin_token')).toBeNull();
      expect(sessionStorage.getItem('admin_refresh_token')).toBeNull();
    } finally {
      api.defaults.adapter = originalAdapter;
    }
  });

  it('replays two concurrent unauthorized requests with one token refresh', async () => {
    const oldAccessToken = managementToken(adminContext);
    const refreshed = loginResult(adminContext, 'replacement-refresh-token');
    refreshed.accessToken = managementToken(adminContext, Math.floor(Date.now() / 1000) + 7200);
    refreshed.token = refreshed.accessToken;
    setAdminToken(oldAccessToken, adminContext, 'old-refresh-token');
    const originalAdapter = api.defaults.adapter;
    let releaseSecondRequest!: () => void;
    const secondRequestGate = new Promise<void>((resolve) => {
      releaseSecondRequest = resolve;
    });
    const seenRequests: Array<{ url?: string; retried: boolean; authorization?: unknown }> = [];
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig & { _authRetried?: boolean }) => {
      seenRequests.push({
        url: config.url,
        retried: config._authRetried === true,
        authorization: config.headers.Authorization,
      });
      if (!config._authRetried && config.url === '/second-action') await secondRequestGate;
      return {
        data: config._authRetried
          ? { code: 200, message: 'success', data: { path: config.url } }
          : { code: 401, message: '登录状态已失效', data: null },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    });
    api.defaults.adapter = adapter as typeof api.defaults.adapter;
    const post = vi.spyOn(axios, 'post').mockResolvedValue(success(refreshed));
    vi.spyOn(axios, 'get').mockResolvedValue(success(adminContext));

    try {
      const first = api.get('/first-action');
      const second = api.get('/second-action');
      await waitFor(() => expect(sessionStorage.getItem('admin_token')).toBe(refreshed.accessToken));
      releaseSecondRequest();

      const [firstResponse, secondResponse] = await Promise.all([first, second]);
      expect(firstResponse.data.data).toEqual({ path: '/first-action' });
      expect(secondResponse.data.data).toEqual({ path: '/second-action' });
      expect(post.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1);
      const retriedRequests = seenRequests.filter((config) => config.retried);
      expect(retriedRequests).toHaveLength(2);
      expect(retriedRequests.every((config) =>
        config.authorization === `Bearer ${refreshed.accessToken}`)).toBe(true);
    } finally {
      releaseSecondRequest();
      api.defaults.adapter = originalAdapter;
    }
  });

  it('does not refresh a 403 and clears a session that cannot confirm administrator access', async () => {
    setAdminToken(managementToken(adminContext), adminContext, 'admin-refresh-token');
    vi.spyOn(axios, 'get').mockRejectedValue(httpError(403));
    const post = vi.spyOn(axios, 'post');

    await expect(restoreAdminSession()).resolves.toBeNull();

    expect(post).not.toHaveBeenCalled();
    expect(sessionStorage.getItem('admin_token')).toBeNull();
  });

  it('clears the session when refresh succeeds but the server context is no longer ADMIN', async () => {
    setAdminToken(managementToken(adminContext), adminContext, 'old-refresh-token');
    const get = vi.spyOn(axios, 'get')
      .mockRejectedValueOnce(httpError(401))
      .mockResolvedValueOnce(success({ ...doctorContext, userId: adminContext.userId }));
    const post = vi.spyOn(axios, 'post').mockResolvedValue(success(loginResult(adminContext, 'new-refresh-token')));

    await expect(restoreAdminSession()).resolves.toBeNull();

    expect(post.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1);
    expect(post).toHaveBeenCalledWith(
      '/api/auth/logout',
      { refreshToken: 'new-refresh-token' },
      expect.any(Object),
    );
    expect(get).toHaveBeenCalledTimes(2);
    expect(sessionStorage.getItem('admin_token')).toBeNull();
    expect(sessionStorage.getItem('admin_refresh_token')).toBeNull();
  });

  it('rejects a business 403 without refreshing or clearing the verified session', async () => {
    const accessToken = managementToken(adminContext);
    setAdminToken(accessToken, adminContext, 'admin-refresh-token');
    const originalAdapter = api.defaults.adapter;
    const post = vi.spyOn(axios, 'post');
    api.defaults.adapter = vi.fn(async (config) => ({
      data: { code: 403, message: '无权执行此操作', data: null },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    })) as typeof api.defaults.adapter;

    try {
      await expect(api.get('/governance-action')).rejects.toMatchObject({ code: '403' });
      expect(post).not.toHaveBeenCalled();
      expect(sessionStorage.getItem('admin_token')).toBe(accessToken);
    } finally {
      api.defaults.adapter = originalAdapter;
    }
  });

  it('clears local credentials and resolves even when remote logout fails', async () => {
    setAdminToken(managementToken(adminContext), adminContext, 'admin-refresh-token');
    const post = vi.spyOn(axios, 'post').mockRejectedValue(new Error('network unavailable'));

    await expect(logoutManagementSession()).resolves.toBeUndefined();

    expect(post).toHaveBeenCalledWith('/api/auth/logout', { refreshToken: 'admin-refresh-token' }, expect.any(Object));
    expect(sessionStorage.getItem('admin_token')).toBeNull();
    expect(sessionStorage.getItem('admin_refresh_token')).toBeNull();
    expect(sessionStorage.getItem('management_context')).toBeNull();
  });

  it('leaves protected content immediately while remote logout is still pending', async () => {
    const user = userEvent.setup();
    setAdminToken(managementToken(adminContext), adminContext, 'admin-refresh-token');
    vi.spyOn(axios, 'get').mockResolvedValue(success(adminContext));
    let releaseLogout!: () => void;
    const post = vi.spyOn(axios, 'post').mockImplementation(async () => {
      await new Promise<void>((resolve) => {
        releaseLogout = resolve;
      });
      return success(null);
    });

    render(createElement(App));
    expect(await screen.findByText('管理员首页')).toBeInTheDocument();

    try {
      await user.click(screen.getByRole('button', { name: /退出登录/ }));
      await waitFor(() => expect(window.location.pathname).toBe('/login'), { timeout: 500 });
      expect(await screen.findByText('仅限平台管理员登录')).toBeInTheDocument();
      expect(screen.queryByText('管理员首页')).not.toBeInTheDocument();
      expect(post).toHaveBeenCalledWith(
        '/api/auth/logout',
        { refreshToken: 'admin-refresh-token' },
        expect.any(Object),
      );
    } finally {
      releaseLogout();
    }
  });

  it('does not let a pending refresh restore credentials after logout', async () => {
    const expiredToken = managementToken(adminContext, Math.floor(Date.now() / 1000) - 60);
    setAdminToken(expiredToken, adminContext, 'old-refresh-token');
    const refreshed = loginResult(adminContext, 'replacement-refresh-token');
    let resolveContext!: (value: ReturnType<typeof success<ManagementContext>>) => void;
    const get = vi.spyOn(axios, 'get').mockImplementation(() => new Promise((resolve) => {
      resolveContext = resolve;
    }));
    const post = vi.spyOn(axios, 'post').mockImplementation(async (url) => {
      if (url === '/api/auth/refresh') return success(refreshed);
      return success(null);
    });

    const restoring = restoreAdminSession();
    await waitFor(() => expect(get).toHaveBeenCalledTimes(1));
    expect(sessionStorage.getItem('admin_token')).toBe(expiredToken);

    await logoutManagementSession();
    resolveContext(success(adminContext));
    await expect(restoring).resolves.toBeNull();

    expect(post).toHaveBeenCalledWith('/api/auth/logout', { refreshToken: 'old-refresh-token' }, expect.any(Object));
    expect(sessionStorage.getItem('admin_token')).toBeNull();
    expect(sessionStorage.getItem('admin_refresh_token')).toBeNull();
  });

  it('does not let an older refresh overwrite a newer administrator login', async () => {
    const expiredToken = managementToken(adminContext, Math.floor(Date.now() / 1000) - 60);
    setAdminToken(expiredToken, adminContext, 'old-refresh-token');
    const oldRefreshed = loginResult(adminContext, 'old-replacement-refresh-token');
    const newContext = { ...adminContext, userId: 'admin-user-2' };
    const newLogin = loginResult(newContext, 'new-login-refresh-token');
    let resolveOldContext!: (value: ReturnType<typeof success<ManagementContext>>) => void;
    const get = vi.spyOn(axios, 'get').mockImplementation(() => {
      if (get.mock.calls.length === 1) {
        return new Promise((resolve) => {
          resolveOldContext = resolve;
        });
      }
      return Promise.resolve(success(newContext));
    });
    vi.spyOn(axios, 'post').mockImplementation(async (url) => {
      if (url === '/api/auth/refresh') return success(oldRefreshed);
      return success(null);
    });
    vi.spyOn(api, 'post').mockResolvedValue(success(newLogin));

    const oldRestore = restoreAdminSession();
    await waitFor(() => expect(get).toHaveBeenCalledTimes(1));
    await expect(loginAdminSession('13900139000', 'New-admin-password1!')).resolves.toEqual(newContext);

    resolveOldContext(success(adminContext));
    await expect(oldRestore).resolves.toBeNull();

    expect(sessionStorage.getItem('admin_token')).toBe(newLogin.accessToken);
    expect(sessionStorage.getItem('admin_refresh_token')).toBe('new-login-refresh-token');
    expect(JSON.parse(sessionStorage.getItem('management_context') || '{}')).toEqual(newContext);
  });

  it('does not attach an old token or refresh when the administrator login request fails', async () => {
    setAdminToken(managementToken(adminContext), adminContext, 'old-refresh-token');
    const originalAdapter = api.defaults.adapter;
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({
      data: { code: 401, message: '管理员账号或密码错误', data: null },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }));
    api.defaults.adapter = adapter as typeof api.defaults.adapter;
    const refresh = vi.spyOn(axios, 'post');

    try {
      await expect(loginAdminSession('13800138000', 'wrong-password')).rejects.toMatchObject({ code: '401' });
      expect(adapter).toHaveBeenCalledTimes(1);
      expect(adapter.mock.calls[0]?.[0].url).toBe('/admin/login');
      expect(adapter.mock.calls[0]?.[0].headers.Authorization).toBeUndefined();
      expect(refresh).not.toHaveBeenCalled();
      expect(sessionStorage.getItem('admin_token')).toBeNull();
    } finally {
      api.defaults.adapter = originalAdapter;
    }
  });

  it('keeps an existing session when the administrator opens the login page directly', async () => {
    const accessToken = managementToken(adminContext);
    setAdminToken(accessToken, adminContext, 'admin-refresh-token');
    window.history.pushState({}, '', '/login');

    render(createElement(App));
    expect(await screen.findByText('仅限平台管理员登录')).toBeInTheDocument();

    expect(sessionStorage.getItem('admin_token')).toBe(accessToken);
    expect(sessionStorage.getItem('admin_refresh_token')).toBe('admin-refresh-token');
  });

  it('submits through /admin/login, returns to the protected deep link, and reuses the verified context once', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/projects?tab=active#results');
    const post = vi.spyOn(api, 'post').mockResolvedValue(success(loginResult()));
    const get = vi.spyOn(axios, 'get').mockResolvedValue(success(adminContext));

    render(createElement(App));
    await user.type(await screen.findByLabelText('手机号'), '13800138000');
    await user.type(screen.getByLabelText('密码'), 'Admin-password1!');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => expect(post).toHaveBeenCalledWith('/admin/login', expect.any(Object)));
    expect(await screen.findByText('管理员项目页面')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/projects');
    expect(window.location.search).toBe('?tab=active');
    expect(window.location.hash).toBe('#results');
    expect(get).toHaveBeenCalledTimes(1);
  });
});

describe('professional helper compatibility', () => {
  beforeEach(() => sessionStorage.clear());

  it('keeps existing professional default-path helpers for Flutter-adjacent dead code', () => {
    setAdminToken('header.payload.signature', doctorContext);
    expect(getDefaultManagementPath()).toBe('/project-collaboration');
  });
});

describe('getApiErrorCode', () => {
  it('extracts the stable envelope errorCode instead of the localized server message', () => {
    expect(getApiErrorCode({
      isAxiosError: true,
      response: { data: { code: 409, message: '当前中文提示会变化', errorCode: 'PROJECT_REVISION_CONFLICT', data: null } },
    })).toBe('PROJECT_REVISION_CONFLICT');
  });

  it('returns null for message-only, blank, or non-error envelopes', () => {
    expect(getApiErrorCode({ isAxiosError: true, response: { data: { code: 400, message: '旧提示', data: null } } })).toBeNull();
    expect(getApiErrorCode({ isAxiosError: true, response: { data: { errorCode: '   ' } } })).toBeNull();
    expect(getApiErrorCode(new Error('PROJECT_REVISION_CONFLICT'))).toBeNull();
  });
});
