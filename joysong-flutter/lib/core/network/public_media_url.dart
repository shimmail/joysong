abstract interface class PublicMediaUrlResolver {
  String resolve(String value);
}

const publicMediaJsonKeys = {
  'imageUrl',
  'coverImage',
  'avatar',
  'logo',
  'images',
  'imageUrls',
  'beforeImages',
  'beforeImageUrls',
  'afterImages',
  'afterImageUrls',
  'credentialImages',
};

Object? resolvePublicMediaUrlsInJson(
  Object? value, {
  required PublicMediaUrlResolver resolver,
  String parentKey = '',
}) {
  if (value is Map) {
    return value.map(
      (key, child) => MapEntry(
        key.toString(),
        resolvePublicMediaUrlsInJson(
          child,
          resolver: resolver,
          parentKey: key.toString(),
        ),
      ),
    );
  }
  if (value is List) {
    return [
      for (final child in value)
        resolvePublicMediaUrlsInJson(
          child,
          resolver: resolver,
          parentKey: parentKey,
        ),
    ];
  }
  if (value is String && publicMediaJsonKeys.contains(parentKey)) {
    return value
        .split(',')
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .map(resolver.resolve)
        .join(',');
  }
  return value;
}

final class ApiPublicMediaUrlResolver implements PublicMediaUrlResolver {
  const ApiPublicMediaUrlResolver(this.apiRoot);

  final Uri apiRoot;

  @override
  String resolve(String value) =>
      resolvePublicMediaUrl(value, apiRoot: apiRoot);
}

String resolvePublicMediaUrl(String value, {required Uri apiRoot}) {
  final raw = value.trim().replaceAll('\\', '/');
  if (raw.isEmpty) return '';

  final parsed = Uri.tryParse(raw);
  if (parsed == null) return '';

  final origin = apiRoot.replace(path: '/', query: null, fragment: null);
  if (!parsed.hasScheme || parsed.host.isEmpty) {
    final relativePath = raw.startsWith('/') ? raw.substring(1) : raw;
    return origin.resolve(relativePath).toString();
  }

  final sourceIsLocal = _isLocalDevelopmentHost(parsed.host);
  final targetIsDifferent = parsed.host.toLowerCase() != apiRoot.host.toLowerCase() ||
      _effectivePort(parsed) != _effectivePort(apiRoot);
  if (sourceIsLocal && targetIsDifferent) {
    return origin
        .replace(
          path: parsed.path,
          query: parsed.hasQuery ? parsed.query : null,
          fragment: parsed.hasFragment ? parsed.fragment : null,
        )
        .toString();
  }
  return parsed.toString();
}

int _effectivePort(Uri uri) {
  if (uri.hasPort) return uri.port;
  return uri.scheme.toLowerCase() == 'https' ? 443 : 80;
}

bool _isLocalDevelopmentHost(String host) {
  final normalized = host.toLowerCase();
  return normalized == 'localhost' ||
      normalized == '127.0.0.1' ||
      normalized == '::1' ||
      normalized == '10.0.2.2' ||
      normalized == '10.0.3.2';
}
