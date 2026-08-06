final class PhoneCountry {
  const PhoneCountry({
    required this.isoCode,
    required this.name,
    required this.flag,
    required this.dialCode,
  });

  final String isoCode;
  final String name;
  final String flag;
  final String dialCode;
}

const supportedPhoneCountries = <PhoneCountry>[
  PhoneCountry(isoCode: 'CN', name: '中国大陆', flag: '🇨🇳', dialCode: '+86'),
  PhoneCountry(isoCode: 'HK', name: '中国香港', flag: '🇭🇰', dialCode: '+852'),
  PhoneCountry(isoCode: 'MO', name: '中国澳门', flag: '🇲🇴', dialCode: '+853'),
  PhoneCountry(isoCode: 'TW', name: '中国台湾', flag: '🇹🇼', dialCode: '+886'),
  PhoneCountry(isoCode: 'US', name: '美国', flag: '🇺🇸', dialCode: '+1'),
  PhoneCountry(isoCode: 'CA', name: '加拿大', flag: '🇨🇦', dialCode: '+1'),
  PhoneCountry(isoCode: 'JP', name: '日本', flag: '🇯🇵', dialCode: '+81'),
  PhoneCountry(isoCode: 'KR', name: '韩国', flag: '🇰🇷', dialCode: '+82'),
  PhoneCountry(isoCode: 'SG', name: '新加坡', flag: '🇸🇬', dialCode: '+65'),
  PhoneCountry(isoCode: 'MY', name: '马来西亚', flag: '🇲🇾', dialCode: '+60'),
  PhoneCountry(isoCode: 'TH', name: '泰国', flag: '🇹🇭', dialCode: '+66'),
  PhoneCountry(isoCode: 'VN', name: '越南', flag: '🇻🇳', dialCode: '+84'),
  PhoneCountry(isoCode: 'PH', name: '菲律宾', flag: '🇵🇭', dialCode: '+63'),
  PhoneCountry(isoCode: 'ID', name: '印度尼西亚', flag: '🇮🇩', dialCode: '+62'),
  PhoneCountry(isoCode: 'IN', name: '印度', flag: '🇮🇳', dialCode: '+91'),
  PhoneCountry(isoCode: 'AE', name: '阿联酋', flag: '🇦🇪', dialCode: '+971'),
  PhoneCountry(isoCode: 'GB', name: '英国', flag: '🇬🇧', dialCode: '+44'),
  PhoneCountry(isoCode: 'FR', name: '法国', flag: '🇫🇷', dialCode: '+33'),
  PhoneCountry(isoCode: 'DE', name: '德国', flag: '🇩🇪', dialCode: '+49'),
  PhoneCountry(isoCode: 'IT', name: '意大利', flag: '🇮🇹', dialCode: '+39'),
  PhoneCountry(isoCode: 'ES', name: '西班牙', flag: '🇪🇸', dialCode: '+34'),
  PhoneCountry(isoCode: 'RU', name: '俄罗斯', flag: '🇷🇺', dialCode: '+7'),
  PhoneCountry(isoCode: 'AU', name: '澳大利亚', flag: '🇦🇺', dialCode: '+61'),
  PhoneCountry(isoCode: 'NZ', name: '新西兰', flag: '🇳🇿', dialCode: '+64'),
];

PhoneCountry phoneCountryForDialCode(String dialCode) {
  final normalized = dialCode.startsWith('+') ? dialCode : '+$dialCode';
  for (final country in supportedPhoneCountries) {
    if (country.dialCode == normalized) {
      return country;
    }
  }
  return PhoneCountry(
    isoCode: 'ZZ',
    name: '其他地区',
    flag: '🌐',
    dialCode: normalized,
  );
}

({PhoneCountry country, String nationalNumber}) splitInternationalPhone(
  String phone, {
  required PhoneCountry fallbackCountry,
}) {
  final compact = phone.trim().replaceAll(RegExp(r'[\s()-]'), '');
  if (!compact.startsWith('+')) {
    return (
      country: fallbackCountry,
      nationalNumber: compact.replaceAll(RegExp(r'\D'), ''),
    );
  }

  final candidates = <PhoneCountry>[
    ...supportedPhoneCountries,
    if (!supportedPhoneCountries.any(
      (country) => country.dialCode == fallbackCountry.dialCode,
    ))
      fallbackCountry,
  ];
  final matches = candidates
      .where((country) => compact.startsWith(country.dialCode))
      .toList()
    ..sort(
        (left, right) => right.dialCode.length.compareTo(left.dialCode.length));
  final country = matches.isEmpty ? fallbackCountry : matches.first;
  final nationalNumber =
      compact.substring(country.dialCode.length).replaceAll(RegExp(r'\D'), '');
  return (country: country, nationalNumber: nationalNumber);
}

String normalizeInternationalPhone(
  String nationalNumber,
  PhoneCountry country,
) {
  final digits = nationalNumber.replaceAll(RegExp(r'\D'), '');
  return '${country.dialCode}$digits';
}
