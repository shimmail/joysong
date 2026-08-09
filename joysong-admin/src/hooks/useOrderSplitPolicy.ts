import { useEffect, useState } from 'react';
import api, { getApiErrorMessage, getData } from '../api';
import type { OrderSplitPolicy } from '../types/splitRates';

export function useOrderSplitPolicy() {
  const [policy, setPolicy] = useState<OrderSplitPolicy>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  useEffect(() => {
    let active = true;
    api.get('/admin/order-split-policy')
      .then((response) => {
        if (!active) return;
        setPolicy(getData<OrderSplitPolicy>(response));
        setError(undefined);
      })
      .catch((cause) => {
        if (!active) return;
        setPolicy(undefined);
        setError(getApiErrorMessage(cause, '分账策略加载失败'));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, []);

  return { policy, loading, error };
}
