package com.fastcurrency.app

class Currency(
    @JvmField val code: String,
    private val country: String,
    @JvmField val name: String,
    @JvmField val nameEn: String,
    @JvmField val color: Int,
    private val symbol: String
) {
    @JvmField val crypto: Boolean = color != 0

    val flag: String
        get() {
            if (crypto) return symbol
            if (country.length != 2) return "\uD83C\uDFF3"
            val sb = StringBuilder(4)
            sb.appendCodePoint(0x1F1E6 + (country[0] - 'A'))
            sb.appendCodePoint(0x1F1E6 + (country[1] - 'A'))
            return sb.toString()
        }
}

object Currencies {

    @JvmField
    val DEFAULT_CODES: Array<String> = arrayOf("KRW", "RUB", "USD", "EUR")

    private val cache = HashMap<String, Currency>(16)
    private var fiatAll: List<Currency>? = null
    private var cryptoAll: List<Currency>? = null

    fun get(code: String): Currency {
        cache[code]?.let { return it }
        val found = lookup(FIAT, code, false) ?: lookup(CRYPTO, code, true) ?: Currency(code, "", code, code, 0, "")
        cache[code] = found
        return found
    }

    fun isCrypto(code: String): Boolean = get(code).crypto

    fun fiat(): List<Currency> = fiatAll ?: parseAll(FIAT, false).also { fiatAll = it }

    fun crypto(): List<Currency> = cryptoAll ?: parseAll(CRYPTO, true).also { cryptoAll = it }

    fun cryptoCodes(): List<String> {
        val l = crypto()
        val out = ArrayList<String>(l.size)
        for (c in l) out.add(c.code)
        return out
    }

    private fun parseAll(table: String, crypto: Boolean): List<Currency> {
        val list = ArrayList<Currency>(if (crypto) 32 else 180)
        var start = 0
        val n = table.length
        while (start < n) {
            var end = table.indexOf('\n', start)
            if (end < 0) end = n
            val c = parseLine(table, start, end, crypto)
            if (c != null) list.add(cache[c.code] ?: c.also { cache[c.code] = it })
            start = end + 1
        }
        return list
    }

    private fun lookup(table: String, code: String, crypto: Boolean): Currency? {
        val start = if (table.startsWith("$code|")) 0 else {
            val i = table.indexOf("\n$code|")
            if (i < 0) return null
            i + 1
        }
        var end = table.indexOf('\n', start)
        if (end < 0) end = table.length
        return parseLine(table, start, end, crypto)
    }

    // fiat:   CODE|COUNTRY|Название|Name
    // crypto: CODE|RRGGBB символ|Название|Name
    private fun parseLine(t: String, start: Int, end: Int, crypto: Boolean): Currency? {
        val p1 = t.indexOf('|', start)
        val p2 = t.indexOf('|', p1 + 1)
        val p3 = t.indexOf('|', p2 + 1)
        if (p1 < 0 || p3 < 0 || p3 >= end) return null
        val code = t.substring(start, p1)
        val mid = t.substring(p1 + 1, p2)
        val name = t.substring(p2 + 1, p3)
        val nameEn = t.substring(p3 + 1, end)
        return if (crypto) {
            Currency(code, "", name, nameEn, 0xFF000000.toInt() or mid.substring(0, 6).toInt(16), mid.substring(7))
        } else {
            Currency(code, mid, name, nameEn, 0, "")
        }
    }

    private const val CRYPTO = """BTC|F7931A ₿|Биткоин|Bitcoin
ETH|627EEA Ξ|Эфириум|Ethereum
USDT|26A17B ₮|Tether|Tether
BNB|F0B90B ◆|BNB|BNB
SOL|9945FF ◎|Солана|Solana
XRP|23292F ✕|XRP|XRP
USDC|2775CA $|USD Coin|USD Coin
DOGE|C2A633 Ð|Догикоин|Dogecoin
TRX|EB0029 T|Трон|TRON
ADA|0033AD ₳|Кардано|Cardano
TON|0098EA ◇|Тонкоин|Toncoin
LINK|2A5ADA L|Chainlink|Chainlink
AVAX|E84142 A|Avalanche|Avalanche
SUI|4DA2FF S|Sui|Sui
XLM|14B6E7 *|Stellar|Stellar
BCH|8DC351 Ƀ|Биткоин кэш|Bitcoin Cash
DOT|E6007A ●|Полкадот|Polkadot
LTC|345D9D Ł|Лайткоин|Litecoin
SHIB|FFA409 S|Shiba Inu|Shiba Inu
NEAR|00C08B N|NEAR|NEAR Protocol
UNI|FF007A U|Uniswap|Uniswap
APT|1C1C1C A|Aptos|Aptos
ETC|328332 ξ|Эфириум классик|Ethereum Classic
ATOM|2E3148 C|Cosmos|Cosmos
PEPE|3D8130 P|Pepe|Pepe
ARB|28A0F0 A|Arbitrum|Arbitrum
OP|FF0420 O|Optimism|Optimism
FIL|0090FF F|Filecoin|Filecoin
ICP|29ABE2 ∞|Internet Computer|Internet Computer
HBAR|222222 ℏ|Hedera|Hedera"""

    private const val FIAT = """AED|AE|Дирхам ОАЭ|UAE Dirham
AFN|AF|Афганский афгани|Afghan Afghani
ALL|AL|Албанский лек|Albanian Lek
AMD|AM|Армянский драм|Armenian Dram
ANG|CW|Нидерландский антильский гульден|Netherlands Antillean Guilder
AOA|AO|Ангольская кванза|Angolan Kwanza
ARS|AR|Аргентинское песо|Argentine Peso
AUD|AU|Австралийский доллар|Australian Dollar
AWG|AW|Арубанский флорин|Aruban Florin
AZN|AZ|Азербайджанский манат|Azerbaijani Manat
BAM|BA|Конвертируемая марка БиГ|Bosnia Convertible Mark
BBD|BB|Барбадосский доллар|Barbadian Dollar
BDT|BD|Бангладешская така|Bangladeshi Taka
BGN|BG|Болгарский лев|Bulgarian Lev
BHD|BH|Бахрейнский динар|Bahraini Dinar
BIF|BI|Бурундийский франк|Burundian Franc
BMD|BM|Бермудский доллар|Bermudian Dollar
BND|BN|Брунейский доллар|Brunei Dollar
BOB|BO|Боливийский боливиано|Bolivian Boliviano
BRL|BR|Бразильский реал|Brazilian Real
BSD|BS|Багамский доллар|Bahamian Dollar
BTN|BT|Бутанский нгултрум|Bhutanese Ngultrum
BWP|BW|Ботсванская пула|Botswana Pula
BYN|BY|Белорусский рубль|Belarusian Ruble
BZD|BZ|Белизский доллар|Belize Dollar
CAD|CA|Канадский доллар|Canadian Dollar
CDF|CD|Конголезский франк|Congolese Franc
CHF|CH|Швейцарский франк|Swiss Franc
CLF|CL|Чилийская расчётная единица UF|Chilean Unidad de Fomento
CLP|CL|Чилийское песо|Chilean Peso
CNH|CN|Офшорный юань|Offshore Chinese Yuan
CNY|CN|Китайский юань|Chinese Yuan
COP|CO|Колумбийское песо|Colombian Peso
CRC|CR|Костариканский колон|Costa Rican Colon
CUP|CU|Кубинское песо|Cuban Peso
CVE|CV|Эскудо Кабо-Верде|Cape Verdean Escudo
CZK|CZ|Чешская крона|Czech Koruna
DJF|DJ|Джибутийский франк|Djiboutian Franc
DKK|DK|Датская крона|Danish Krone
DOP|DO|Доминиканское песо|Dominican Peso
DZD|DZ|Алжирский динар|Algerian Dinar
EGP|EG|Египетский фунт|Egyptian Pound
ERN|ER|Эритрейская накфа|Eritrean Nakfa
ETB|ET|Эфиопский быр|Ethiopian Birr
EUR|EU|Евро|Euro
FJD|FJ|Фиджийский доллар|Fijian Dollar
FKP|FK|Фунт Фолклендских островов|Falkland Islands Pound
FOK|FO|Фарерская крона|Faroese Krona
GBP|GB|Фунт стерлингов|Pound Sterling
GEL|GE|Грузинский лари|Georgian Lari
GGP|GG|Гернсийский фунт|Guernsey Pound
GHS|GH|Ганский седи|Ghanaian Cedi
GIP|GI|Гибралтарский фунт|Gibraltar Pound
GMD|GM|Гамбийский даласи|Gambian Dalasi
GNF|GN|Гвинейский франк|Guinean Franc
GTQ|GT|Гватемальский кетсаль|Guatemalan Quetzal
GYD|GY|Гайанский доллар|Guyanese Dollar
HKD|HK|Гонконгский доллар|Hong Kong Dollar
HNL|HN|Гондурасская лемпира|Honduran Lempira
HRK|HR|Хорватская куна|Croatian Kuna
HTG|HT|Гаитянский гурд|Haitian Gourde
HUF|HU|Венгерский форинт|Hungarian Forint
IDR|ID|Индонезийская рупия|Indonesian Rupiah
ILS|IL|Израильский шекель|Israeli New Shekel
IMP|IM|Мэнский фунт|Manx Pound
INR|IN|Индийская рупия|Indian Rupee
IQD|IQ|Иракский динар|Iraqi Dinar
IRR|IR|Иранский риал|Iranian Rial
ISK|IS|Исландская крона|Icelandic Krona
JEP|JE|Джерсийский фунт|Jersey Pound
JMD|JM|Ямайский доллар|Jamaican Dollar
JOD|JO|Иорданский динар|Jordanian Dinar
JPY|JP|Японская иена|Japanese Yen
KES|KE|Кенийский шиллинг|Kenyan Shilling
KGS|KG|Киргизский сом|Kyrgyzstani Som
KHR|KH|Камбоджийский риель|Cambodian Riel
KID|KI|Доллар Кирибати|Kiribati Dollar
KMF|KM|Коморский франк|Comorian Franc
KRW|KR|Южнокорейская вона|South Korean Won
KWD|KW|Кувейтский динар|Kuwaiti Dinar
KYD|KY|Доллар Каймановых островов|Cayman Islands Dollar
KZT|KZ|Казахстанский тенге|Kazakhstani Tenge
LAK|LA|Лаосский кип|Lao Kip
LBP|LB|Ливанский фунт|Lebanese Pound
LKR|LK|Шри-ланкийская рупия|Sri Lankan Rupee
LRD|LR|Либерийский доллар|Liberian Dollar
LSL|LS|Лоти Лесото|Lesotho Loti
LYD|LY|Ливийский динар|Libyan Dinar
MAD|MA|Марокканский дирхам|Moroccan Dirham
MDL|MD|Молдавский лей|Moldovan Leu
MGA|MG|Малагасийский ариари|Malagasy Ariary
MKD|MK|Македонский денар|Macedonian Denar
MMK|MM|Мьянманский кьят|Myanmar Kyat
MNT|MN|Монгольский тугрик|Mongolian Tugrik
MOP|MO|Патака Макао|Macanese Pataca
MRU|MR|Мавританская угия|Mauritanian Ouguiya
MUR|MU|Маврикийская рупия|Mauritian Rupee
MVR|MV|Мальдивская руфия|Maldivian Rufiyaa
MWK|MW|Малавийская квача|Malawian Kwacha
MXN|MX|Мексиканское песо|Mexican Peso
MYR|MY|Малайзийский ринггит|Malaysian Ringgit
MZN|MZ|Мозамбикский метикал|Mozambican Metical
NAD|NA|Намибийский доллар|Namibian Dollar
NGN|NG|Нигерийская найра|Nigerian Naira
NIO|NI|Никарагуанская кордоба|Nicaraguan Cordoba
NOK|NO|Норвежская крона|Norwegian Krone
NPR|NP|Непальская рупия|Nepalese Rupee
NZD|NZ|Новозеландский доллар|New Zealand Dollar
OMR|OM|Оманский риал|Omani Rial
PAB|PA|Панамский бальбоа|Panamanian Balboa
PEN|PE|Перуанский соль|Peruvian Sol
PGK|PG|Кина Папуа — Новой Гвинеи|Papua New Guinean Kina
PHP|PH|Филиппинское песо|Philippine Peso
PKR|PK|Пакистанская рупия|Pakistani Rupee
PLN|PL|Польский злотый|Polish Zloty
PYG|PY|Парагвайский гуарани|Paraguayan Guarani
QAR|QA|Катарский риал|Qatari Riyal
RON|RO|Румынский лей|Romanian Leu
RSD|RS|Сербский динар|Serbian Dinar
RUB|RU|Российский рубль|Russian Ruble
RWF|RW|Руандийский франк|Rwandan Franc
SAR|SA|Саудовский риял|Saudi Riyal
SBD|SB|Доллар Соломоновых Островов|Solomon Islands Dollar
SCR|SC|Сейшельская рупия|Seychellois Rupee
SDG|SD|Суданский фунт|Sudanese Pound
SEK|SE|Шведская крона|Swedish Krona
SGD|SG|Сингапурский доллар|Singapore Dollar
SHP|SH|Фунт Святой Елены|Saint Helena Pound
SLE|SL|Сьерра-леонский леоне|Sierra Leonean Leone
SLL|SL|Сьерра-леонский леоне (старый)|Old Sierra Leonean Leone
SOS|SO|Сомалийский шиллинг|Somali Shilling
SRD|SR|Суринамский доллар|Surinamese Dollar
SSP|SS|Южносуданский фунт|South Sudanese Pound
STN|ST|Добра Сан-Томе и Принсипи|Sao Tome Dobra
SYP|SY|Сирийский фунт|Syrian Pound
SZL|SZ|Свазилендский лилангени|Eswatini Lilangeni
THB|TH|Тайский бат|Thai Baht
TJS|TJ|Таджикский сомони|Tajikistani Somoni
TMT|TM|Туркменский манат|Turkmenistani Manat
TND|TN|Тунисский динар|Tunisian Dinar
TOP|TO|Тонганская паанга|Tongan Paanga
TRY|TR|Турецкая лира|Turkish Lira
TTD|TT|Доллар Тринидада и Тобаго|Trinidad and Tobago Dollar
TVD|TV|Доллар Тувалу|Tuvaluan Dollar
TWD|TW|Новый тайваньский доллар|New Taiwan Dollar
TZS|TZ|Танзанийский шиллинг|Tanzanian Shilling
UAH|UA|Украинская гривна|Ukrainian Hryvnia
UGX|UG|Угандийский шиллинг|Ugandan Shilling
USD|US|Доллар США|US Dollar
UYU|UY|Уругвайское песо|Uruguayan Peso
UZS|UZ|Узбекский сум|Uzbekistani Som
VES|VE|Венесуэльский боливар|Venezuelan Bolivar
VND|VN|Вьетнамский донг|Vietnamese Dong
VUV|VU|Вануатский вату|Vanuatu Vatu
WST|WS|Самоанская тала|Samoan Tala
XAF|CM|Франк КФА BEAC|Central African CFA Franc
XCD|AG|Восточнокарибский доллар|East Caribbean Dollar
XCG|CW|Карибский гульден|Caribbean Guilder
XDR||СДР (МВФ)|IMF Special Drawing Rights
XOF|SN|Франк КФА BCEAO|West African CFA Franc
XPF|PF|Французский тихоокеанский франк|CFP Franc
YER|YE|Йеменский риал|Yemeni Rial
ZAR|ZA|Южноафриканский рэнд|South African Rand
ZMW|ZM|Замбийская квача|Zambian Kwacha
ZWG|ZW|Зимбабвийский голд|Zimbabwe Gold
ZWL|ZW|Зимбабвийский доллар|Zimbabwean Dollar"""
}
