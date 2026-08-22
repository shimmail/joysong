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
