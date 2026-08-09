import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import api, { getData } from '../api';
import { useOrderSplitPolicy } from './useOrderSplitPolicy';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    default: { get: vi.fn() },
    getData: vi.fn(actual.getData),
  };
});

const mockGet = vi.mocked(api.get);
const mockGetData = vi.mocked(getData);

describe('useOrderSplitPolicy', () => {
  afterEach(() => vi.clearAllMocks());

  it('loads the platform rate from the server policy', async () => {
    mockGet.mockResolvedValue({ data: { code: 200, message: 'OK', data: { platformRate: 40 } } });

    const { result } = renderHook(() => useOrderSplitPolicy());

    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(mockGet).toHaveBeenCalledWith('/admin/order-split-policy');
    expect(result.current).toEqual({ policy: { platformRate: 40 }, loading: false, error: undefined });
  });

  it('fails closed and exposes the API error', async () => {
    mockGet.mockRejectedValue(new Error('策略服务不可用'));

    const { result } = renderHook(() => useOrderSplitPolicy());

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.policy).toBeUndefined();
    expect(result.current.error).toBe('策略服务不可用');
  });

  it('ignores a deferred response after unmount', async () => {
    let resolveRequest!: (value: { data: { platformRate: number } }) => void;
    mockGet.mockReturnValue(new Promise(resolve => { resolveRequest = resolve; }));
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const { result, unmount } = renderHook(() => useOrderSplitPolicy());
    const stateBeforeUnmount = result.current;

    unmount();
    await act(async () => resolveRequest({ data: { platformRate: 40 } }));

    expect(result.current).toBe(stateBeforeUnmount);
    expect(mockGetData).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
    consoleError.mockRestore();
  });
});
