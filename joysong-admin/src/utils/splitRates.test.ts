import { describe, expect, it } from 'vitest';
import { calculateDoctorRate, validateSplitRates } from './splitRates';

describe('split rate calculations', () => {
  it('derives the doctor remainder in basis points', () => {
    expect(calculateDoctorRate(40, 35, 10)).toBe(15);
    expect(calculateDoctorRate(40, 39.99, 10.01)).toBe(10);
  });

  it('allows a zero doctor share', () => {
    expect(calculateDoctorRate(40, 40, 20)).toBe(0);
    expect(validateSplitRates(40, 40, 20)).toBeUndefined();
  });

  it('rejects a negative doctor share', () => {
    expect(validateSplitRates(40, 40, 20.01)).toBe(
      '平台、合作医疗机构和医美顾问分账比例合计不能超过 100%',
    );
  });

  it('rejects rates with precision beyond two decimal places', () => {
    expect(validateSplitRates(1.0000000001, 40, 10)).toBe('平台分账比例最多保留两位小数');
  });

  it('fails closed when the platform policy is absent', () => {
    expect(calculateDoctorRate(undefined, 40, 10)).toBeNull();
    expect(validateSplitRates(undefined, 40, 10)).toBe('分账策略尚未加载');
  });
});
