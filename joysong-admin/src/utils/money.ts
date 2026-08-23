export function formatMoney(amountMinor: unknown, currency: unknown, legacyAmount?: unknown) {
  const currencyCode = typeof currency === 'string' ? currency.trim().toUpperCase() : '';
  if (!currencyCode) return '-';

  if (typeof amountMinor === 'number' && Number.isSafeInteger(amountMinor) && amountMinor >= 0) {
    let fractionDigits = 2;
    try {
      fractionDigits = new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: currencyCode,
      }).resolvedOptions().maximumFractionDigits ?? 2;
    } catch {
      // Unknown legacy currencies retain the conventional two-decimal display.
    }
    return `${currencyCode} ${(amountMinor / (10 ** fractionDigits)).toFixed(fractionDigits)}`;
  }

  return typeof legacyAmount === 'number' && Number.isFinite(legacyAmount)
    ? `${currencyCode} ${legacyAmount.toFixed(2)}`
    : '-';
}

export function calculatePercentageFeeMinor(price: unknown, ratePercent: unknown): number | null {
  if (typeof price !== 'number' || !Number.isFinite(price) || price <= 0) return null;
  if (typeof ratePercent !== 'number' || !Number.isFinite(ratePercent) || ratePercent <= 0) return null;
  const priceMinor = Math.round((price + Number.EPSILON) * 100);
  const rateBps = Math.round((ratePercent + Number.EPSILON) * 100);
  if (!Number.isSafeInteger(priceMinor) || !Number.isSafeInteger(rateBps)) return null;
  const feeNumerator = priceMinor * rateBps;
  if (!Number.isSafeInteger(feeNumerator)) return null;
  const feeMinor = Math.round(feeNumerator / 10_000);
  return Number.isSafeInteger(feeMinor) && feeMinor > 0 ? feeMinor : null;
}
