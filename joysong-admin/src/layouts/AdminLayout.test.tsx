import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import AdminLayout from './AdminLayout';

const context = {
  userId: 'admin', platformRole: 'ADMIN', activeRoles: ['ADMIN'], managedInstitutionIds: [], visibleInstitutionIds: [],
  canManageDoctors: true, canManageInstitutions: true, canManageInstitutionProjects: true, canManageArticles: true,
  canManageSplitConfigs: true, canManageOrders: true, canApplyToInstitutions: false, canReviewInstitutionRequests: true,
  canSubmitPlatformProjectRequests: true, canSubmitInstitutionProjectRequests: false, canReviewInstitutionProjectRequests: true,
  canViewAffiliations: true,
};

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

describe('AdminLayout administrator navigation', () => {
  it('keeps administrator core navigation and removes professional-role entry points', async () => {
    const user = userEvent.setup();
    sessionStorage.setItem('management_context', JSON.stringify(context));
    render(<MemoryRouter><AdminLayout /></MemoryRouter>);

    expect(screen.getByText('管理系统')).toBeInTheDocument();
    await user.click(screen.getByText('业务管理'));
    expect(screen.getByText('项目管理')).toBeInTheDocument();
    expect(screen.getByText('项目申请审核')).toBeInTheDocument();
    expect(screen.getByText('机构项目管理')).toBeInTheDocument();
    expect(screen.getByText('订单管理')).toBeInTheDocument();
    expect(screen.queryByText('项目协作')).not.toBeInTheDocument();

    await user.click(screen.getByText('服务与风控'));
    expect(screen.getByText('协议与隐私')).toBeInTheDocument();
  });

  it('does not derive a professional-role menu from a tampered cached context', async () => {
    const user = userEvent.setup();
    sessionStorage.setItem('management_context', JSON.stringify({ ...context, platformRole: 'DOCTOR' }));
    render(<MemoryRouter><AdminLayout /></MemoryRouter>);

    expect(screen.getByText('管理系统')).toBeInTheDocument();
    await user.click(screen.getByText('业务管理'));
    expect(screen.queryByText('项目协作')).not.toBeInTheDocument();
    expect(screen.getByText('项目管理')).toBeInTheDocument();
  });
});
