export const identityRoleOptions = [
  { label: '医生', value: 'DOCTOR' },
  { label: '医美顾问', value: 'CONSULTANT' },
  { label: '机构法定代表人', value: 'INSTITUTION_LEGAL_REPRESENTATIVE' },
  { label: '机构客服', value: 'INSTITUTION_CUSTOMER_SERVICE' },
];

export const institutionMemberRoleOptions = identityRoleOptions.filter(
  (option) => option.value !== 'DOCTOR',
);

export const relationStatusOptions = [
  { label: '待审核', value: 'PENDING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已撤销', value: 'REVOKED' },
];

export function identityRoleLabel(roleCode: string) {
  return identityRoleOptions.find((option) => option.value === roleCode)?.label || roleCode;
}

export function identityStatusLabel(status: string) {
  const labels: Record<string, string> = {
    PENDING: '待审核',
    APPROVED: '已通过',
    REJECTED: '已驳回',
    CHANGES_REQUESTED: '待修改',
    ACTIVE: '有效',
    REVOKED: '已撤销',
    WITHDRAWN: '已撤回',
  };
  return labels[status] || status;
}

export function identityStatusColor(status: string) {
  const colors: Record<string, string> = {
    PENDING: 'gold',
    APPROVED: 'green',
    ACTIVE: 'green',
    REJECTED: 'red',
    CHANGES_REQUESTED: 'orange',
    REVOKED: 'default',
    WITHDRAWN: 'default',
  };
  return colors[status] || 'default';
}
