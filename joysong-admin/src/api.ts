import axios, { AxiosError } from 'axios';

const ADMIN_TOKEN_KEY = 'admin_token';
const ADMIN_REFRESH_TOKEN_KEY = 'admin_refresh_token';
const MANAGEMENT_CONTEXT_KEY = 'management_context';

export type ManagementContext = {
  userId: string;
  platformRole: 'ADMIN' | 'USER';
  activeRoles: string[];
  doctorId?: string;
  managedInstitutionIds: string[];
  visibleInstitutionIds: string[];
  canManageDoctors: boolean;
  canManageInstitutions: boolean;
  canManageInstitutionProjects: boolean;
  canManageArticles: boolean;
  canManageSplitConfigs: boolean;
  canManageOrders: boolean;
};

type ApiEnvelope<T = unknown> = {
  code: number;
  message: string;
  data: T | null;
};

function decodeJwtPayload(token: string): { exp?: number; role?: string; sub?: string } | null {
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
    const decoded = decodeURIComponent(
      window.atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '='))
        .split('')
        .map((char) => `%${char.charCodeAt(0).toString(16).padStart(2, '0')}`)
        .join(''),
    );
    return JSON.parse(decoded);
  } catch {
    return null;
  }
}

export function clearAdminToken() {
  sessionStorage.removeItem(ADMIN_TOKEN_KEY);
  sessionStorage.removeItem(ADMIN_REFRESH_TOKEN_KEY);
  sessionStorage.removeItem(MANAGEMENT_CONTEXT_KEY);
  // 清除旧版本遗留的长期令牌，减少 XSS 发生时可窃取的持久凭证。
  localStorage.removeItem(ADMIN_TOKEN_KEY);
}

export function setAdminToken(token: string, context: ManagementContext, refreshToken?: string) {
  clearAdminToken();
  sessionStorage.setItem(ADMIN_TOKEN_KEY, token);
  if (refreshToken) sessionStorage.setItem(ADMIN_REFRESH_TOKEN_KEY, refreshToken);
  sessionStorage.setItem(MANAGEMENT_CONTEXT_KEY, JSON.stringify(context));
}

function updateAdminTokens(accessToken: string, refreshToken: string) {
  sessionStorage.setItem(ADMIN_TOKEN_KEY, accessToken);
  sessionStorage.setItem(ADMIN_REFRESH_TOKEN_KEY, refreshToken);
}

function getAdminRefreshToken() {
  return sessionStorage.getItem(ADMIN_REFRESH_TOKEN_KEY);
}

export function getManagementContext(): ManagementContext | null {
  try {
    const value = sessionStorage.getItem(MANAGEMENT_CONTEXT_KEY);
    return value ? JSON.parse(value) as ManagementContext : null;
  } catch {
    return null;
  }
}

export function getAdminToken(): string | null {
  const token = sessionStorage.getItem(ADMIN_TOKEN_KEY);
  if (!token) {
    localStorage.removeItem(ADMIN_TOKEN_KEY);
    return null;
  }

  const payload = decodeJwtPayload(token);
  const context = getManagementContext();
  if (!payload || !context) {
    clearAdminToken();
    return null;
  }
  const expired = !payload.exp || payload.exp * 1000 <= Date.now();
  const validRole = payload.role === 'ADMIN' || payload.role === 'USER';
  const validContext = context.userId === payload.sub && context.platformRole === payload.role;
  if (!validRole || !validContext || (expired && !getAdminRefreshToken())) {
    clearAdminToken();
    return null;
  }
  return token;
}

export function hasValidAdminToken() {
  return getAdminToken() !== null;
}

export function isAdminSession() {
  return getManagementContext()?.platformRole === 'ADMIN';
}

export function getDefaultManagementPath() {
  const context = getManagementContext();
  if (!context || context.platformRole === 'ADMIN') return '/';
  if (context.canManageInstitutions) return '/institution-projects';
  if (context.canManageInstitutionProjects) return '/project-collaboration';
  if (context.canManageDoctors) return '/doctors';
  if (context.canManageInstitutions || context.visibleInstitutionIds.length > 0) return '/institutions';
  if (context.canManageArticles) return '/articles';
  if (context.canManageSplitConfigs) return '/doctor-project-configs';
  if (context.canManageOrders) return '/orders';
  return '/login';
}

function isLoginRequest(url?: string) {
  return url?.endsWith('/admin/login') === true;
}

function isAuthenticationRequest(url?: string) {
  return isLoginRequest(url) || url?.endsWith('/management/login') === true ||
    url?.endsWith('/auth/refresh') === true || url?.endsWith('/auth/logout') === true;
}

function redirectToLogin(url?: string) {
  clearAdminToken();
  if (!isLoginRequest(url) && window.location.pathname !== '/login') {
    window.location.assign('/login');
  }
}

const api = axios.create({
  baseURL: '/api',
  timeout: 15_000,
  headers: { Accept: 'application/json' },
});

type RefreshResult = {
  accessToken: string;
  refreshToken: string;
};

let refreshPromise: Promise<string> | null = null;

async function refreshAdminAccessToken(): Promise<string> {
  if (refreshPromise) return refreshPromise;
  const refreshToken = getAdminRefreshToken();
  if (!refreshToken) throw new Error('刷新令牌不存在');

  refreshPromise = axios.post<ApiEnvelope<RefreshResult>>('/api/auth/refresh', { refreshToken }, {
    timeout: 15_000,
    headers: { Accept: 'application/json' },
  }).then(async (response) => {
    const envelope = response.data;
    const result = envelope.data;
    if (envelope.code !== 200 || !result?.accessToken || !result.refreshToken) {
      throw new Error(envelope.message || '登录状态已失效');
    }
    updateAdminTokens(result.accessToken, result.refreshToken);
    const contextResponse = await axios.get<ApiEnvelope<ManagementContext>>('/api/management/context', {
      timeout: 15_000,
      headers: { Accept: 'application/json', Authorization: `Bearer ${result.accessToken}` },
    });
    if (contextResponse.data.code !== 200 || !contextResponse.data.data) {
      throw new Error(contextResponse.data.message || '管理权限已失效');
    }
    sessionStorage.setItem(MANAGEMENT_CONTEXT_KEY, JSON.stringify(contextResponse.data.data));
    return result.accessToken;
  }).finally(() => {
    refreshPromise = null;
  });
  return refreshPromise;
}

function isTokenExpired(token: string) {
  const payload = decodeJwtPayload(token);
  return !payload?.exp || payload.exp * 1000 <= Date.now();
}

api.interceptors.request.use(async (config) => {
  let token = getAdminToken();
  if (token && isTokenExpired(token) && !isAuthenticationRequest(config.url)) {
    try {
      token = await refreshAdminAccessToken();
    } catch (error) {
      redirectToLogin(config.url);
      return Promise.reject(error);
    }
  }
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  async (response) => {
    const envelope = response.data as Partial<ApiEnvelope> | undefined;
    if (typeof envelope?.code === 'number' && envelope.code !== 200) {
      const config = response.config as typeof response.config & { _authRetried?: boolean };
      if (envelope.code === 401 && !config._authRetried && !isAuthenticationRequest(config.url)) {
        config._authRetried = true;
        try {
          const token = await refreshAdminAccessToken();
          config.headers.Authorization = `Bearer ${token}`;
          return api.request(config);
        } catch {
          redirectToLogin(config.url);
        }
      }
      return Promise.reject(
        new AxiosError(
          envelope.message || '请求失败',
          String(envelope.code),
          response.config,
          response.request,
          response,
        ),
      );
    }
    return response;
  },
  async (error: AxiosError) => {
    const config = error.config as (typeof error.config & { _authRetried?: boolean }) | undefined;
    if (error.response?.status === 401 && config && !config._authRetried && !isAuthenticationRequest(config.url)) {
      config._authRetried = true;
      try {
        const token = await refreshAdminAccessToken();
        config.headers.Authorization = `Bearer ${token}`;
        return api.request(config);
      } catch {
        redirectToLogin(config.url);
      }
    }
    return Promise.reject(error);
  },
);

export async function logoutManagementSession() {
  const refreshToken = getAdminRefreshToken();
  try {
    if (refreshToken) {
      await axios.post('/api/auth/logout', { refreshToken }, {
        timeout: 10_000,
        headers: { Accept: 'application/json' },
      });
    }
  } finally {
    clearAdminToken();
  }
}

export function getData<T = any>(res: any): T {
  const body = res.data;
  if (body && typeof body === 'object' && 'code' in body) {
    return (body as ApiEnvelope<T>).data as T;
  }
  return body as T;
}

export function debounce<T extends (...args: any[]) => any>(
  fn: T,
  delay = 300,
): (...args: Parameters<T>) => void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  return (...args: Parameters<T>) => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      fn(...args);
      timer = null;
    }, delay);
  };
}

export function getApiErrorMessage(error: unknown, fallback = '请求失败，请稍后重试') {
  if (axios.isAxiosError(error)) {
    const message = (error.response?.data as Partial<ApiEnvelope> | undefined)?.message;
    if (message) return message;
    if (error.code === 'ECONNABORTED') return '请求超时，请检查服务是否正常运行';
    if (!error.response) return '无法连接服务器，请检查后台服务是否已启动';
  }
  return error instanceof Error && error.message ? error.message : fallback;
}

export default api;
