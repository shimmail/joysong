/**
 * 本地后端历史数据可能保存了局域网 IP 的绝对图片地址。
 * 管理后台统一通过同源 /images 代理加载，避免地址变动及 CSP 拦截。
 */
export function getPreviewImageUrl(value?: string): string | undefined {
  const url = value?.trim();
  if (!url) return undefined;

  try {
    const parsed = new URL(url);
    if (parsed.port === '8080' && parsed.pathname.startsWith('/images/')) {
      return `${parsed.pathname}${parsed.search}${parsed.hash}`;
    }
  } catch {
    // 相对路径或非标准 URL 直接交给浏览器处理。
  }

  return url;
}
