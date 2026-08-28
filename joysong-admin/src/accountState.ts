export type AccountState = 'ACTIVE' | 'ADMIN_SUSPENDED' | 'ERASED';

export type AccountStateSource = {
  accountState?: string | null;
  deletedAt?: string | null;
};

export const accountStateView: Record<AccountState, { label: string; color: string }> = {
  ACTIVE: { label: '正常 / Active', color: 'green' },
  ADMIN_SUSPENDED: { label: '已暂停 / Suspended', color: 'orange' },
  ERASED: { label: '已注销 / Erased', color: 'red' },
};

export function resolveAccountState(source: AccountStateSource): AccountState {
  if (source.accountState === 'ACTIVE' || source.accountState === 'ADMIN_SUSPENDED' || source.accountState === 'ERASED') {
    return source.accountState;
  }
  return source.deletedAt ? 'ERASED' : 'ACTIVE';
}
