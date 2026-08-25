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

describe('AdminLayout legal documents navigation', () => {
  it('shows 协议与隐私 under 内容运营 only for admins', async () => {
    const user = userEvent.setup();
    sessionStorage.setItem('management_context', JSON.stringify(context));
    render(<MemoryRouter><AdminLayout /></MemoryRouter>);
    await user.click(screen.getByText('内容运营'));
    expect(screen.getByText('协议与隐私')).toBeInTheDocument();
  });

  it('hides 协议与隐私 for non-admin management roles', () => {
    sessionStorage.setItem('management_context', JSON.stringify({ ...context, platformRole: 'DOCTOR' }));
    render(<MemoryRouter><AdminLayout /></MemoryRouter>);
    expect(screen.queryByText('协议与隐私')).not.toBeInTheDocument();
  });
});
