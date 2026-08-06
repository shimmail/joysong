import 'package:google_sign_in/google_sign_in.dart';

final class GoogleIdentityProvider {
  GoogleIdentityProvider._();

  static final GoogleIdentityProvider instance = GoogleIdentityProvider._();

  static const _serverClientId = String.fromEnvironment(
    'GOOGLE_SERVER_CLIENT_ID',
    defaultValue:
        '1074438635273-5pqevlkgfmmb910dkjm7vvhikoui5oh9.apps.googleusercontent.com',
  );
  static const _iosClientId = String.fromEnvironment(
    'GOOGLE_IOS_CLIENT_ID',
  );

  late final GoogleSignIn _googleSignIn = GoogleSignIn(
    scopes: const ['email', 'profile'],
    clientId: _iosClientId.isEmpty ? null : _iosClientId,
    serverClientId: _serverClientId.isEmpty ? null : _serverClientId,
  );

  Future<String?> requestIdToken() async {
    // Sign out of the local Google selector first so switching accounts always
    // offers an account choice instead of silently reusing the last identity.
    await _googleSignIn.signOut();
    final account = await _googleSignIn.signIn();
    if (account == null) return null;
    final authentication = await account.authentication;
    return authentication.idToken?.trim();
  }

  Future<void> clearLocalSession() => _googleSignIn.signOut();
}
