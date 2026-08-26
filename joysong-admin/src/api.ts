import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';

const ADMIN_TOKEN_KEY = 'admin_token';
const ADMIN_REFRESH_TOKEN_KEY = 'admin_refresh_token';
const MANAGEMENT_CONTEXT_KEY = 'management_context';

let sessionRevision = 0;
let confirmedRouteSession: {
  accessToken: string;
  context: ManagementContext;
  revision: number;
} | null = null;

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
  canApplyToInstitutions: boolean;
  canReviewInstitutionRequests: boolean;
  canSubmitPlatformProjectRequests: boolean;
  canSubmitInstitutionProjectRequests: boolean;
  canReviewInstitutionProjectRequests: boolean;
  canViewAffiliations: boolean;
};

export type ApiEnvelope<T = unknown> = {
  code: number;
  message: string;
  errorCode?: string | null;
  data: T | null;
};

type JwtPayload = { exp?: number; role?: string; sub?: string };

type AdminLoginResult = {
  token: string;
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: { id: string; role: string };
};

type RefreshResult = {
  accessToken: string;
  refreshToken: string;
  user: { id: string; role: string };
};

class AdminSessionRequestError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.status = status;
  }
}

class StaleAdminSessionOperationError extends Error {}

type AdminAuthRequestConfig = InternalAxiosRequestConfig & {
  _authRetried?: boolean;
  _authRevision?: number;
  _authToken?: string;
};

function decodeJwtPayload(token: string): JwtPayload | null {
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
  sessionRevision += 1;
  confirmedRouteSession = null;
  [ADMIN_TOKEN_KEY, ADMIN_REFRESH_TOKEN_KEY, MANAGEMENT_CONTEXT_KEY].forEach((key) => {
    sessionStorage.removeItem(key);
    // 清除旧版本遗留的长期凭证，减少 XSS 发生时可窃取的持久数据。
    localStorage.removeItem(key);
  });
}

export function setAdminToken(token: string, context: ManagementContext, refreshToken?: string) {
  clearAdminToken();
  writeAdminSession(token, context, refreshToken);
}

function writeAdminSession(token: string, context: ManagementContext, refreshToken?: string) {
  sessionStorage.setItem(ADMIN_TOKEN_KEY, token);
  if (refreshToken) sessionStorage.setItem(ADMIN_REFRESH_TOKEN_KEY, refreshToken);
  sessionStorage.setItem(MANAGEMENT_CONTEXT_KEY, JSON.stringify(context));
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
  const validRole = payload.role === 'ADMIN';
  const validContext = context.userId === payload.sub && context.platformRole === 'ADMIN';
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
  if (context.canReviewInstitutionProjectRequests) return '/project-requests';
  if (context.canSubmitInstitutionProjectRequests) return '/project-collaboration';
  if (context.canManageDoctors) return '/doctors';
  if (context.canManageInstitutions || context.visibleInstitutionIds.length > 0) return '/institutions';
  if (context.canManageArticles) return '/articles';
  if (context.canManageSplitConfigs) return '/split-proposals';
  if (context.canManageOrders) return '/orders';
  return '/login';
}

function isLoginRequest(url?: string) {
  return url?.endsWith('/admin/login') === true;
}

function isAuthenticationRequest(url?: string) {
  return isLoginRequest(url) || url?.endsWith('/auth/refresh') === true ||
    url?.endsWith('/auth/logout') === true;
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

function responseData<T>(response: { data: ApiEnvelope<T> }, fallback: string): T {
  const envelope = response.data;
  if (envelope?.code !== 200 || !envelope.data) {
    throw new AdminSessionRequestError(envelope?.message || fallback, envelope?.code);
  }
  return envelope.data;
}

function requireAdminContext(
  accessToken: string,
  context: ManagementContext,
  expectedUserId?: string,
) {
  const payload = decodeJwtPayload(accessToken);
  const valid = payload?.role === 'ADMIN' &&
    typeof payload.sub === 'string' && payload.sub.length > 0 &&
    typeof payload.exp === 'number' && payload.exp * 1000 > Date.now() &&
    context.platformRole === 'ADMIN' &&
    context.userId === payload.sub &&
    (!expectedUserId || expectedUserId === payload.sub);
  if (!valid) throw new Error('管理员身份验证失败');
}

async function requestManagementContext(accessToken: string) {
  const response = await axios.get<ApiEnvelope<ManagementContext>>('/api/management/context', {
    timeout: 15_000,
    headers: { Accept: 'application/json', Authorization: `Bearer ${accessToken}` },
  });
  return responseData(response, '管理权限已失效');
}

function requireCurrentSessionRevision(expectedRevision: number) {
  if (sessionRevision !== expectedRevision) throw new StaleAdminSessionOperationError();
}

function commitAdminSession(
  accessToken: string,
  refreshToken: string,
  context: ManagementContext,
  expectedRevision: number,
) {
  requireCurrentSessionRevision(expectedRevision);
  writeAdminSession(accessToken, context, refreshToken);
  return sessionRevision;
}

export async function loginAdminSession(phone: string, password: string): Promise<ManagementContext> {
  clearAdminToken();
  const operationRevision = sessionRevision;
  try {
    const response = await api.post<ApiEnvelope<AdminLoginResult>>('/admin/login', {
      phone: phone.trim(),
      password,
    });
    const result = responseData(response, '管理员登录失败');
    const accessToken = result.accessToken || result.token;
    if (!accessToken || !result.refreshToken || result.user?.role !== 'ADMIN' || !result.user.id) {
      throw new Error('管理员身份验证失败');
    }

    const context = await requestManagementContext(accessToken);
    requireAdminContext(accessToken, context, result.user.id);
    const committedRevision = commitAdminSession(
      accessToken,
      result.refreshToken,
      context,
      operationRevision,
    );
    confirmedRouteSession = { accessToken, context, revision: committedRevision };
    return context;
  } catch (error) {
    if (sessionRevision === operationRevision) clearAdminToken();
    throw error;
  }
}

let refreshPromise: Promise<string> | null = null;

async function revokeRefreshTokenQuietly(refreshToken: string) {
  try {
    await axios.post('/api/auth/logout', { refreshToken }, {
      timeout: 10_000,
      headers: { Accept: 'application/json' },
    });
  } catch {
    // 本地世代检查仍会阻止旧会话写回；远端撤销是尽力清理已轮换出的孤立令牌。
  }
}

async function refreshAdminAccessToken(): Promise<string> {
  if (refreshPromise) return refreshPromise;
  const refreshToken = getAdminRefreshToken();
  if (!refreshToken) throw new Error('刷新令牌不存在');
  const operationRevision = sessionRevision;
  let replacementRefreshToken: string | null = null;

  refreshPromise = (async () => {
    const response = await axios.post<ApiEnvelope<RefreshResult>>('/api/auth/refresh', { refreshToken }, {
      timeout: 15_000,
      headers: { Accept: 'application/json' },
    });
    const result = responseData(response, '登录状态已失效');
    replacementRefreshToken = result.refreshToken;
    if (!result.accessToken || !result.refreshToken || result.user?.role !== 'ADMIN' || !result.user.id) {
      throw new Error('管理员身份验证失败');
    }
    requireCurrentSessionRevision(operationRevision);

    const context = await requestManagementContext(result.accessToken);
    requireAdminContext(result.accessToken, context, result.user.id);
    commitAdminSession(result.accessToken, result.refreshToken, context, operationRevision);
    replacementRefreshToken = null;
    return result.accessToken;
  })().catch(async (error) => {
    if (replacementRefreshToken) await revokeRefreshTokenQuietly(replacementRefreshToken);
    if (sessionRevision === operationRevision) clearAdminToken();
    throw error;
  }).finally(() => {
    refreshPromise = null;
  });
  return refreshPromise;
}

function isTokenExpired(token: string) {
  const payload = decodeJwtPayload(token);
  return !payload?.exp || payload.exp * 1000 <= Date.now();
}

function responseStatus(error: unknown) {
  if (error instanceof AdminSessionRequestError) return error.status;
  return axios.isAxiosError(error) ? error.response?.status : undefined;
}

async function restoreStoredAdminSession(): Promise<ManagementContext | null> {
  const operationRevision = sessionRevision;
  const accessToken = sessionStorage.getItem(ADMIN_TOKEN_KEY);
  const payload = accessToken ? decodeJwtPayload(accessToken) : null;
  if (!accessToken || payload?.role !== 'ADMIN' || !payload.sub) {
    clearAdminToken();
    return null;
  }

  try {
    if (isTokenExpired(accessToken)) {
      await refreshAdminAccessToken();
      return getManagementContext();
    }

    try {
      const context = await requestManagementContext(accessToken);
      requireAdminContext(accessToken, context);
      commitAdminSession(
        accessToken,
        getAdminRefreshToken() || '',
        context,
        operationRevision,
      );
      return context;
    } catch (error) {
      requireCurrentSessionRevision(operationRevision);
      if (responseStatus(error) !== 401 || !getAdminRefreshToken()) throw error;
      await refreshAdminAccessToken();
      return getManagementContext();
    }
  } catch {
    if (sessionRevision === operationRevision) clearAdminToken();
    return null;
  }
}

let restorePromise: Promise<ManagementContext | null> | null = null;

export function restoreAdminSession(): Promise<ManagementContext | null> {
  const confirmed = confirmedRouteSession;
  confirmedRouteSession = null;
  if (confirmed &&
    confirmed.revision === sessionRevision &&
    sessionStorage.getItem(ADMIN_TOKEN_KEY) === confirmed.accessToken) {
    try {
      requireAdminContext(confirmed.accessToken, confirmed.context);
      return Promise.resolve(confirmed.context);
    } catch {
      clearAdminToken();
      return Promise.resolve(null);
    }
  }
  if (restorePromise) return restorePromise;
  restorePromise = restoreStoredAdminSession().finally(() => {
    restorePromise = null;
  });
  return restorePromise;
}

api.interceptors.request.use(async (config) => {
  if (isAuthenticationRequest(config.url)) return config;
  const authConfig = config as AdminAuthRequestConfig;
  let token = getAdminToken();
  authConfig._authRevision = sessionRevision;
  if (token && isTokenExpired(token)) {
    try {
      token = await refreshAdminAccessToken();
      authConfig._authRevision = sessionRevision;
    } catch (error) {
      if (!(error instanceof StaleAdminSessionOperationError)) redirectToLogin(config.url);
      return Promise.reject(error);
    }
  }
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
    authConfig._authToken = token;
  }
  return config;
});

async function retryUnauthorizedRequest(config: AdminAuthRequestConfig) {
  const belongsToCurrentSession = config._authRevision === undefined ||
    config._authRevision === sessionRevision;
  if (!belongsToCurrentSession) return null;
  if (config._authRetried) {
    redirectToLogin(config.url);
    return null;
  }

  config._authRetried = true;
  try {
    const currentToken = getAdminToken();
    const token = currentToken && config._authToken && currentToken !== config._authToken
      ? currentToken
      : await refreshAdminAccessToken();
    config.headers.Authorization = `Bearer ${token}`;
    config._authToken = token;
    return api.request(config);
  } catch (error) {
    if (!(error instanceof StaleAdminSessionOperationError)) redirectToLogin(config.url);
    return null;
  }
}

api.interceptors.response.use(
  async (response) => {
    const envelope = response.data as Partial<ApiEnvelope> | undefined;
    if (typeof envelope?.code === 'number' && envelope.code !== 200) {
      const config = response.config as AdminAuthRequestConfig;
      if (envelope.code === 401 && !isAuthenticationRequest(config.url)) {
        const retriedResponse = await retryUnauthorizedRequest(config);
        if (retriedResponse) return retriedResponse;
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
    const config = error.config as AdminAuthRequestConfig | undefined;
    if (error.response?.status === 401 && config && !isAuthenticationRequest(config.url)) {
      const retriedResponse = await retryUnauthorizedRequest(config);
      if (retriedResponse) return retriedResponse;
    }
    return Promise.reject(error);
  },
);

export async function logoutManagementSession() {
  const refreshToken = getAdminRefreshToken();
  clearAdminToken();
  if (refreshToken) void revokeRefreshTokenQuietly(refreshToken);
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

export function getApiErrorCode(error: unknown): string | null {
  if (!axios.isAxiosError(error)) return null;
  const errorCode = (error.response?.data as Partial<ApiEnvelope> | undefined)?.errorCode;
  return typeof errorCode === 'string' && errorCode.trim().length > 0 ? errorCode : null;
}

export default api;
