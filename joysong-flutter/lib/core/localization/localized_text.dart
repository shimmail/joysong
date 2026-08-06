import 'package:flutter/widgets.dart';

extension LocalizedTextContext on BuildContext {
  bool get isEnglish => Localizations.localeOf(this).languageCode == 'en';

  String localized(String chinese, String english) =>
      isEnglish ? english : chinese;
}
