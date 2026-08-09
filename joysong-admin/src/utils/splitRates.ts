type OptionalRate = number | null | undefined;

const toBasisPoints = (rate: number) => Math.round((rate + Number.EPSILON) * 100);
const fromBasisPoints = (basisPoints: number) => basisPoints / 100;
const present = (rate: OptionalRate): rate is number => rate != null && Number.isFinite(rate);

export function calculateDoctorRate(
  platformRate: OptionalRate,
  institutionRate: OptionalRate,
  consultantRate: OptionalRate,
): number | null {
  if (!present(platformRate) || !present(institutionRate) || !present(consultantRate)) return null;
  return fromBasisPoints(
    10_000 - toBasisPoints(platformRate) - toBasisPoints(institutionRate) - toBasisPoints(consultantRate),
  );
}

export function validateSplitRates(
  platformRate: OptionalRate,
  institutionRate: OptionalRate,
  consultantRate: OptionalRate,
): string | undefined {
  if (!present(platformRate)) return '分账策略尚未加载';
  if (!present(institutionRate)) return '请输入合作医疗机构分成比例';
  if (!present(consultantRate)) return '请输入医美顾问分账比例';
  const entries: Array<[string, number]> = [
    ['平台分账比例', platformRate],
    ['合作医疗机构分成比例', institutionRate],
    ['医美顾问分账比例', consultantRate],
  ];
  for (const [label, rate] of entries) {
    if (rate < 0 || rate > 100) return `${label}须在 0～100 之间`;
    if (Math.abs(rate - fromBasisPoints(toBasisPoints(rate))) > 1e-9) return `${label}最多保留两位小数`;
  }
  return calculateDoctorRate(platformRate, institutionRate, consultantRate)! < 0
    ? '平台、合作医疗机构和医美顾问分账比例合计不能超过 100%'
    : undefined;
}
