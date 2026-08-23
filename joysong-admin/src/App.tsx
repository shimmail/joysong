import React, { Component, Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Spin, Button, Result } from 'antd';
import AdminLayout from './layouts/AdminLayout';
import { getDefaultManagementPath, getManagementContext, hasValidAdminToken } from './api';
import type { ManagementContext } from './api';

// 全局错误边界：捕获子组件渲染错误，防止白屏
class ErrorBoundary extends Component<
  { children: React.ReactNode },
  { hasError: boolean; error: Error | null }
> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error('ErrorBoundary caught:', error, info);
  }

  render() {
    if (this.state.hasError) {
      return (
        <Result
          status="error"
          title="页面渲染出错"
          subTitle={this.state.error?.message || '未知错误'}
          extra={
            <Button type="primary" onClick={() => window.location.reload()}>
              刷新页面
            </Button>
          }
        />
      );
    }
    return this.props.children;
  }
}

const LoginPage = lazy(() => import('./pages/LoginPage'));
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const ProjectsPage = lazy(() => import('./pages/ProjectsPage'));
const ProjectRequestsPage = lazy(() => import('./pages/ProjectRequestsPage'));
const OrdersPage = lazy(() => import('./pages/OrdersPage'));
const UsersPage = lazy(() => import('./pages/UsersPage'));
const BannersPage = lazy(() => import('./pages/BannersPage'));
const DoctorsPage = lazy(() => import('./pages/DoctorsPage'));
const InstitutionsPage = lazy(() => import('./pages/InstitutionsPage'));
const InstitutionDetailPage = lazy(() => import('./pages/InstitutionDetailPage'));
const ArticlesPage = lazy(() => import('./pages/ArticlesPage'));
const DiariesPage = lazy(() => import('./pages/DiariesPage'));
const UserDetailPage = lazy(() => import('./pages/UserDetailPage'));
const ReviewsPage = lazy(() => import('./pages/ReviewsPage'));
const PaymentsPage = lazy(() => import('./pages/PaymentsPage'));
const RefundsPage = lazy(() => import('./pages/RefundsPage'));
const InstitutionProjectsPage = lazy(() => import('./pages/InstitutionProjectsPage'));
const ProjectCollaborationPage = lazy(() => import('./pages/ProjectCollaborationPage'));
const ReportsPage = lazy(() => import('./pages/ReportsPage'));
const CouponsPage = lazy(() => import('./pages/CouponsPage'));
const SettlementsPage = lazy(() => import('./pages/SettlementsPage'));
const SplitConfigProposalsPage = lazy(() => import('./pages/SplitConfigProposalsPage'));
const CustomerServicePage = lazy(() => import('./pages/CustomerServicePage'));
const IdentityManagementPage = lazy(() => import('./pages/IdentityManagementPage'));

function PrivateRoute({ children }: { children: React.ReactNode }) {
  return hasValidAdminToken() ? <>{children}</> : <Navigate to="/login" replace />;
}

function DefaultManagementRoute() {
  const context = getManagementContext();
  return context?.platformRole === 'ADMIN'
    ? <DashboardPage />
    : <Navigate to={getDefaultManagementPath()} replace />;
}

function AdminOnlyRoute({ children }: { children: React.ReactNode }) {
  return getManagementContext()?.platformRole === 'ADMIN'
    ? <>{children}</>
    : <Navigate to={getDefaultManagementPath()} replace />;
}

function CapabilityRoute({ capability, children }: {
  capability: keyof Pick<ManagementContext,
    'canManageDoctors' | 'canManageInstitutions' | 'canManageInstitutionProjects' |
    'canManageArticles' | 'canManageSplitConfigs' | 'canManageOrders' |
    'canSubmitInstitutionProjectRequests' | 'canReviewInstitutionProjectRequests'>;
  children: React.ReactNode;
}) {
  const context = getManagementContext();
  return context?.platformRole === 'ADMIN' || context?.[capability]
    ? <>{children}</>
    : <Navigate to={getDefaultManagementPath()} replace />;
}

function InstitutionVisibleRoute({ children }: { children: React.ReactNode }) {
  const context = getManagementContext();
  return context?.platformRole === 'ADMIN' || (context?.visibleInstitutionIds.length || 0) > 0
    ? <>{children}</>
    : <Navigate to={getDefaultManagementPath()} replace />;
}

function App() {
  return (
    <BrowserRouter>
      <ErrorBoundary>
      <Suspense fallback={<div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', padding: 80 }}><Spin size="large" /></div>}>
        <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<PrivateRoute><AdminLayout /></PrivateRoute>}>
          <Route index element={<DefaultManagementRoute />} />
          <Route path="projects" element={<AdminOnlyRoute><ProjectsPage /></AdminOnlyRoute>} />
          <Route path="project-requests" element={<CapabilityRoute capability="canReviewInstitutionProjectRequests"><ProjectRequestsPage /></CapabilityRoute>} />
          <Route path="orders" element={<CapabilityRoute capability="canManageOrders"><OrdersPage /></CapabilityRoute>} />
          <Route path="users" element={<AdminOnlyRoute><UsersPage /></AdminOnlyRoute>} />
          <Route path="users/:id" element={<AdminOnlyRoute><UserDetailPage /></AdminOnlyRoute>} />
          <Route path="banners" element={<AdminOnlyRoute><BannersPage /></AdminOnlyRoute>} />
          <Route path="doctors" element={<CapabilityRoute capability="canManageDoctors"><DoctorsPage /></CapabilityRoute>} />
          <Route path="institutions" element={<InstitutionVisibleRoute><InstitutionsPage /></InstitutionVisibleRoute>} />
          <Route path="institutions/:id" element={<AdminOnlyRoute><InstitutionDetailPage /></AdminOnlyRoute>} />
          <Route path="articles" element={<CapabilityRoute capability="canManageArticles"><ArticlesPage /></CapabilityRoute>} />
          <Route path="diaries" element={<AdminOnlyRoute><DiariesPage /></AdminOnlyRoute>} />
          <Route path="reviews" element={<AdminOnlyRoute><ReviewsPage /></AdminOnlyRoute>} />
          <Route path="payments" element={<AdminOnlyRoute><PaymentsPage /></AdminOnlyRoute>} />
          <Route path="institution-projects" element={<AdminOnlyRoute><InstitutionProjectsPage /></AdminOnlyRoute>} />
          <Route path="project-collaboration" element={<CapabilityRoute capability="canSubmitInstitutionProjectRequests"><ProjectCollaborationPage /></CapabilityRoute>} />
          <Route path="refunds" element={<AdminOnlyRoute><RefundsPage /></AdminOnlyRoute>} />
          <Route path="reports" element={<AdminOnlyRoute><ReportsPage /></AdminOnlyRoute>} />
          <Route path="coupons" element={<AdminOnlyRoute><CouponsPage /></AdminOnlyRoute>} />
          <Route path="settlements" element={<AdminOnlyRoute><SettlementsPage /></AdminOnlyRoute>} />
          <Route path="doctor-project-configs" element={<Navigate to="/institution-projects" replace />} />
          <Route path="split-proposals" element={<CapabilityRoute capability="canManageSplitConfigs"><SplitConfigProposalsPage /></CapabilityRoute>} />
          <Route path="cs" element={<AdminOnlyRoute><CustomerServicePage /></AdminOnlyRoute>} />
          <Route path="identity" element={<AdminOnlyRoute><IdentityManagementPage /></AdminOnlyRoute>} />
        </Route>
        </Routes>
      </Suspense>
      </ErrorBoundary>
    </BrowserRouter>
  );
}

export default App;

