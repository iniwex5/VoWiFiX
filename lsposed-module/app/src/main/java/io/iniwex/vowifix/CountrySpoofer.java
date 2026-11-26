package io.iniwex.vowifix;

import android.location.Address;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

/**
 * 归属国国家码解析与全球合法地理坐标生成工具（双卡按槽自适应）。
 * 支持任意海外/本土运营商按卡槽独立对比 SIM MCC 与当前驻网 MCC，仅在跨国漫游不匹配时触发伪装。
 */
public final class CountrySpoofer {

    public static final class GeoProfile {
        public final String isoLower;
        public final String isoUpper;
        public final String countryName;
        public final String adminArea;
        public final String locality;
        public final String postalCode;
        public final String thoroughfare;
        public final String subThoroughfare;
        public final double lat;
        public final double lon;
        public final Locale locale;
        public final boolean hasGeo;

        GeoProfile(String isoLower, String countryName, String adminArea, String locality,
                   String postalCode, String thoroughfare, String subThoroughfare,
                   double lat, double lon, Locale locale, boolean hasGeo) {
            this.isoLower = isoLower.toLowerCase(Locale.US);
            this.isoUpper = isoLower.toUpperCase(Locale.US);
            this.countryName = countryName;
            this.adminArea = adminArea;
            this.locality = locality;
            this.postalCode = postalCode;
            this.thoroughfare = thoroughfare;
            this.subThoroughfare = subThoroughfare;
            this.lat = lat;
            this.lon = lon;
            this.locale = locale;
            this.hasGeo = hasGeo;
        }
    }

    private static final Map<String, GeoProfile> GEO_PROFILES;
    private static final Map<String, String> MCC_ISO_FALLBACK;

    private static void addProfile(Map<String, GeoProfile> map, String iso, String countryName,
                                   String adminArea, String locality, String postalCode,
                                   String thoroughfare, String subThoroughfare,
                                   double lat, double lon, Locale locale) {
        map.put(iso, new GeoProfile(iso, countryName, adminArea, locality, postalCode,
                thoroughfare, subThoroughfare, lat, lon, locale, true));
    }

    private static void addMccs(Map<String, String> map, String iso, String... mccs) {
        for (String mcc : mccs) {
            map.put(mcc, iso);
        }
    }

    static {
        Map<String, GeoProfile> map = new HashMap<>();
        // 欧洲与英联邦主流 VoWiFi 国家/地区
        addProfile(map, "gb", "United Kingdom", "England", "London", "SW1A 1AA", "Downing Street", "10", 51.5034, -0.1276, Locale.UK);
        addProfile(map, "uk", "United Kingdom", "England", "London", "SW1A 1AA", "Downing Street", "10", 51.5034, -0.1276, Locale.UK);
        addProfile(map, "ie", "Ireland", "Leinster", "Dublin", "D02 X285", "Merrion Street Upper", "1", 53.3382, -6.2535, new Locale("en", "IE"));
        addProfile(map, "fr", "France", "Ile-de-France", "Paris", "75008", "Rue du Faubourg Saint-Honore", "55", 48.8704, 2.3168, Locale.FRANCE);
        addProfile(map, "de", "Germany", "Berlin", "Berlin", "10557", "Willy-Brandt-Strasse", "1", 52.5200, 13.3692, Locale.GERMANY);
        addProfile(map, "it", "Italy", "Lazio", "Rome", "00187", "Piazza Colonna", "370", 41.9012, 12.4801, Locale.ITALY);
        addProfile(map, "es", "Spain", "Madrid", "Madrid", "28014", "Carrera de San Jeronimo", "36", 40.4165, -3.6967, new Locale("es", "ES"));
        addProfile(map, "pt", "Portugal", "Lisbon", "Lisbon", "1100-148", "Praca do Comercio", "1", 38.7075, -9.1364, new Locale("pt", "PT"));
        addProfile(map, "nl", "Netherlands", "North Holland", "Amsterdam", "1012 RJ", "Dam", "1", 52.3731, 4.8926, new Locale("nl", "NL"));
        addProfile(map, "be", "Belgium", "Brussels", "Brussels", "1000", "Rue de la Loi", "16", 50.8466, 4.3647, new Locale("fr", "BE"));
        addProfile(map, "ch", "Switzerland", "Bern", "Bern", "3003", "Bundesplatz", "3", 46.9467, 7.4442, new Locale("de", "CH"));
        addProfile(map, "at", "Austria", "Vienna", "Vienna", "1010", "Ballhausplatz", "2", 48.2084, 16.3638, new Locale("de", "AT"));
        addProfile(map, "pl", "Poland", "Masovian", "Warsaw", "00-583", "Aleje Ujazdowskie", "1", 52.2194, 21.0235, new Locale("pl", "PL"));
        addProfile(map, "cz", "Czechia", "Prague", "Prague", "118 00", "Nabrezi Edvarda Benese", "4", 50.0933, 14.4150, new Locale("cs", "CZ"));
        addProfile(map, "ro", "Romania", "Bucharest", "Bucharest", "011832", "Piata Victoriei", "1", 44.4526, 26.0861, new Locale("ro", "RO"));
        addProfile(map, "hu", "Hungary", "Budapest", "Budapest", "1055", "Kossuth Lajos ter", "1", 47.5071, 19.0457, new Locale("hu", "HU"));
        addProfile(map, "gr", "Greece", "Attica", "Athens", "105 57", "Syntagma Square", "1", 37.9755, 23.7357, new Locale("el", "GR"));
        addProfile(map, "se", "Sweden", "Stockholm", "Stockholm", "103 33", "Fredsgatan", "8", 59.3289, 18.0650, new Locale("sv", "SE"));
        addProfile(map, "no", "Norway", "Oslo", "Oslo", "0032", "Glacisgata", "1", 59.9102, 10.7429, new Locale("nb", "NO"));
        addProfile(map, "dk", "Denmark", "Capital Region", "Copenhagen", "1218", "Christiansborg Slotsplads", "1", 55.6761, 12.5799, new Locale("da", "DK"));
        addProfile(map, "fi", "Finland", "Uusimaa", "Helsinki", "00170", "Snellmaninkatu", "1", 60.1695, 24.9532, new Locale("fi", "FI"));
        addProfile(map, "tr", "Turkey", "Ankara", "Ankara", "06560", "Bestepe", "1", 39.9311, 32.7995, new Locale("tr", "TR"));

        // 北美与拉美主流 VoWiFi 国家/地区
        addProfile(map, "us", "United States", "District of Columbia", "Washington", "20500", "Pennsylvania Ave NW", "1600", 38.8977, -77.0365, Locale.US);
        addProfile(map, "ca", "Canada", "Ontario", "Ottawa", "K1A 0A9", "Wellington St", "111", 45.4236, -75.7009, Locale.CANADA);
        addProfile(map, "mx", "Mexico", "Ciudad de Mexico", "Mexico City", "06000", "Plaza de la Constitucion", "1", 19.4326, -99.1332, new Locale("es", "MX"));
        addProfile(map, "br", "Brazil", "Distrito Federal", "Brasilia", "70150-900", "Praca dos Tres Poderes", "1", -15.7998, -47.8645, new Locale("pt", "BR"));

        // 亚太主流 VoWiFi 国家/地区
        addProfile(map, "cn", "China", "Beijing", "Beijing", "100017", "Chang'an Ave", "1", 39.9042, 116.4074, Locale.CHINA);
        addProfile(map, "hk", "Hong Kong", "Hong Kong", "Central", "999077", "Connaught Road Central", "8", 22.2819, 114.1588, new Locale("en", "HK"));
        addProfile(map, "mo", "Macau", "Macau", "Macau", "999078", "Avenida da Praia Grande", "100", 22.1987, 113.5439, new Locale("en", "MO"));
        addProfile(map, "tw", "Taiwan", "Taipei City", "Taipei", "100", "Chongqing S Rd", "122", 25.0400, 121.5119, Locale.TAIWAN);
        addProfile(map, "jp", "Japan", "Tokyo", "Chiyoda-ku", "100-0001", "Chiyoda", "1-1", 35.6852, 139.7528, Locale.JAPAN);
        addProfile(map, "kr", "South Korea", "Seoul", "Jongno-gu", "03054", "Cheongwadae-ro", "1", 37.5866, 126.9748, Locale.KOREA);
        addProfile(map, "sg", "Singapore", "Central Singapore", "Singapore", "178957", "St Andrew's Rd", "1", 1.2903, 103.8519, new Locale("en", "SG"));
        addProfile(map, "my", "Malaysia", "Putrajaya", "Putrajaya", "62502", "Dataran Putra", "1", 2.9383, 101.6919, new Locale("ms", "MY"));
        addProfile(map, "th", "Thailand", "Bangkok", "Dusit", "10300", "Phitsanulok Rd", "1", 13.7634, 100.5122, new Locale("th", "TH"));
        addProfile(map, "ph", "Philippines", "Metro Manila", "Manila", "1005", "Jose Laurel St", "1000", 14.5936, 120.9939, new Locale("en", "PH"));
        addProfile(map, "id", "Indonesia", "Jakarta", "Central Jakarta", "10110", "Medan Merdeka Utara", "16", -6.1702, 106.8240, new Locale("id", "ID"));
        addProfile(map, "vn", "Vietnam", "Hanoi", "Ba Dinh", "100000", "Hung Vuong", "2", 21.0369, 105.8347, new Locale("vi", "VN"));
        addProfile(map, "in", "India", "Delhi", "New Delhi", "110004", "Raisina Hill", "1", 28.6143, 77.1995, new Locale("en", "IN"));
        addProfile(map, "au", "Australia", "ACT", "Canberra", "2600", "Parliament Dr", "1", -35.3081, 149.1244, new Locale("en", "AU"));
        addProfile(map, "nz", "New Zealand", "Wellington", "Wellington", "6011", "Molesworth St", "1", -41.2784, 174.7767, new Locale("en", "NZ"));

        // 中东与非洲主流 VoWiFi 国家/地区
        addProfile(map, "ae", "United Arab Emirates", "Abu Dhabi", "Abu Dhabi", "00000", "Corniche Rd", "1", 24.4672, 54.3547, new Locale("en", "AE"));
        addProfile(map, "sa", "Saudi Arabia", "Riyadh", "Riyadh", "11564", "Al Yamamah St", "1", 24.6645, 46.6634, new Locale("ar", "SA"));
        addProfile(map, "il", "Israel", "Jerusalem", "Jerusalem", "91950", "Kaplan St", "3", 31.7824, 35.2039, new Locale("he", "IL"));
        addProfile(map, "za", "South Africa", "Gauteng", "Pretoria", "0001", "Government Ave", "1", -25.7406, 28.2116, new Locale("en", "ZA"));

        GEO_PROFILES = Collections.unmodifiableMap(map);

        // 内置静态 ITU E.212 MCC -> ISO 兜底表，供无 MccTable 的进程（如 system_server）快速解析所在国
        Map<String, String> mccMap = new HashMap<>();
        addMccs(mccMap, "gb", "234", "235");
        addMccs(mccMap, "ie", "272");
        addMccs(mccMap, "fr", "208");
        addMccs(mccMap, "de", "262");
        addMccs(mccMap, "it", "222");
        addMccs(mccMap, "es", "214");
        addMccs(mccMap, "pt", "268");
        addMccs(mccMap, "nl", "204");
        addMccs(mccMap, "be", "206");
        addMccs(mccMap, "ch", "228");
        addMccs(mccMap, "at", "232");
        addMccs(mccMap, "pl", "260");
        addMccs(mccMap, "cz", "230");
        addMccs(mccMap, "ro", "226");
        addMccs(mccMap, "hu", "216");
        addMccs(mccMap, "gr", "202");
        addMccs(mccMap, "se", "240");
        addMccs(mccMap, "no", "242");
        addMccs(mccMap, "dk", "238");
        addMccs(mccMap, "fi", "244");
        addMccs(mccMap, "tr", "286");
        addMccs(mccMap, "ru", "250");
        addMccs(mccMap, "us", "310", "311", "312", "313", "314", "315", "316");
        addMccs(mccMap, "ca", "302");
        addMccs(mccMap, "mx", "334");
        addMccs(mccMap, "br", "724");
        addMccs(mccMap, "cn", "460", "461");
        addMccs(mccMap, "hk", "454");
        addMccs(mccMap, "mo", "455");
        addMccs(mccMap, "tw", "466");
        addMccs(mccMap, "jp", "440", "441");
        addMccs(mccMap, "kr", "450");
        addMccs(mccMap, "sg", "525");
        addMccs(mccMap, "my", "502");
        addMccs(mccMap, "th", "520");
        addMccs(mccMap, "ph", "515");
        addMccs(mccMap, "id", "510");
        addMccs(mccMap, "vn", "452");
        addMccs(mccMap, "in", "404", "405", "406");
        addMccs(mccMap, "au", "505");
        addMccs(mccMap, "nz", "530");
        addMccs(mccMap, "ae", "424", "430", "431");
        addMccs(mccMap, "sa", "420");
        addMccs(mccMap, "il", "425");
        addMccs(mccMap, "za", "655");
        MCC_ISO_FALLBACK = Collections.unmodifiableMap(mccMap);
    }

    private CountrySpoofer() {
    }

    /**
     * 安全读取 Android 系统属性（避免编译期依赖隐藏 SystemProperties 类）。
     */
    public static String getSystemProperty(String key, String def) {
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", null);
            Object val = XposedHelpers.callStaticMethod(sp, "get", key, def);
            return val != null ? val.toString() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 读取可选的手动覆盖国家码（/data/local/tmp/vowifi_target_iso），未配置则返回 null。
     */
    private static String readOverrideIso() {
        File f = new File("/data/local/tmp/vowifi_target_iso");
        if (!f.exists() || !f.canRead()) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            if (line != null) {
                String trimmed = line.trim().toLowerCase(Locale.US);
                if (isValidTwoLetterIso(trimmed)) {
                    return trimmed;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isValidTwoLetterIso(String iso) {
        return iso != null && iso.length() == 2
                && Character.isLetter(iso.charAt(0))
                && Character.isLetter(iso.charAt(1));
    }

    private static boolean isEmptyStr(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * 按卡槽索引切分逗号分隔的 Telephony 属性。
     */
    private static String getSlotPropPart(String propName, int slotIndex) {
        return getPartAt(getSystemProperty(propName, ""), slotIndex);
    }

    private static String getPartAt(String raw, int slotIndex) {
        if (slotIndex < 0 || isEmptyStr(raw)) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        if (slotIndex >= parts.length) {
            return null;
        }
        String val = parts[slotIndex].trim();
        return val.isEmpty() ? null : val;
    }

    /**
     * 判断指定网络制式是否代表真实有效的蜂窝无线驻网（排除 Unknown、空串与非蜂窝 IWLAN）。
     */
    public static boolean hasActiveCellularRadioTech(String netType) {
        if (netType == null) {
            return false;
        }
        String trimmed = netType.trim();
        return !trimmed.isEmpty()
                && !"UNKNOWN".equalsIgnoreCase(trimmed)
                && !"IWLAN".equalsIgnoreCase(trimmed);
    }

    /**
     * 判断指定物理卡槽当前是否处于有效蜂窝无线制式下。
     */
    public static boolean hasActiveCellularRadioTechForSlot(int slotIndex) {
        return hasActiveCellularRadioTech(getSlotPropPart("gsm.network.type", slotIndex));
    }

    /**
     * 校验 3 位 MCC（或 PLMN 前 3 位）是否属于 ITU E.212 有效国家号段（200~799），
     * 用于过滤 000/001 等空网锁或无效占位 PLMN。
     */
    public static boolean isValidCountryMcc(String mccOrPlmn) {
        if (mccOrPlmn == null || mccOrPlmn.length() < 3) {
            return false;
        }
        char c0 = mccOrPlmn.charAt(0);
        char c1 = mccOrPlmn.charAt(1);
        char c2 = mccOrPlmn.charAt(2);
        if (!Character.isDigit(c0) || !Character.isDigit(c1) || !Character.isDigit(c2)) {
            return false;
        }
        int mcc = (c0 - '0') * 100 + (c1 - '0') * 10 + (c2 - '0');
        return mcc >= 200 && mcc <= 799;
    }

    /**
     * 通过内置静态映射表将 3 位 MCC（或 PLMN）兜底解析为小写 2 位 ISO 国家码，未命中返回空串。
     */
    public static String getFallbackIsoForMcc(String mccOrPlmn) {
        if (!isValidCountryMcc(mccOrPlmn)) {
            return "";
        }
        String iso = MCC_ISO_FALLBACK.get(mccOrPlmn.substring(0, 3));
        return iso != null ? iso : "";
    }

    /**
     * 从 5~6 位 PLMN 提取合法 ITU 国家 3 位 MCC（200~799），无效或测试空网返回 null。
     */
    private static String extractMcc(String plmn) {
        if (!isValidCountryMcc(plmn)) {
            return null;
        }
        return plmn.substring(0, 3);
    }

    private static String extractIsoFromProp(String rawSimIsos, int slotIndex) {
        String iso = getPartAt(rawSimIsos, slotIndex);
        if (iso != null) {
            String lower = iso.toLowerCase(Locale.US);
            if (isValidTwoLetterIso(lower)) {
                return lower;
            }
        }
        return null;
    }

    /**
     * 判断是否设置了手动目标国家码覆盖文件。
     */
    public static boolean hasManualOverrideIso() {
        return readOverrideIso() != null;
    }

    /**
     * 获取指定卡槽 SIM 卡原生声明的归属国 ISO（小写 2 位字母，不受手动覆盖文件影响）。
     */
    public static String getNativeSimIsoForSlot(int slotIndex) {
        return extractIsoFromProp(getSystemProperty("gsm.sim.operator.iso-country", ""), slotIndex);
    }

    /**
     * 获取指定卡槽最终目标国家码 ISO（优先返回手动覆盖配置，其次回退 SIM 原生归属国）。
     */
    public static String getTargetIsoForSlot(int slotIndex) {
        String override = readOverrideIso();
        if (override != null) {
            return override;
        }
        return getNativeSimIsoForSlot(slotIndex);
    }

    /**
     * 获取指定卡槽 SIM 卡声明的归属运营商名称（依次回退 gsm.sim.operator.alpha 与 numeric）。
     */
    public static String getHomeOperatorNameForSlot(int slotIndex) {
        String alpha = getSlotPropPart("gsm.sim.operator.alpha", slotIndex);
        if (!isEmptyStr(alpha)) {
            return alpha;
        }
        String numeric = getSlotPropPart("gsm.sim.operator.numeric", slotIndex);
        return !isEmptyStr(numeric) ? numeric : null;
    }

    /**
     * 解析整机当前真实物理所在国 MCC：
     * 优先采信具备有效蜂窝无线制式（非 Unknown/空/IWLAN）的槽位驻网 MCC；
     * 若整机无有效蜂窝制式，则采信存在明确跨国漫游差异的驻网 MCC。
     */
    public static String resolvePhysicalDeviceMcc() {
        return resolvePhysicalDeviceMcc(
                getSystemProperty("gsm.sim.operator.numeric", ""),
                getSystemProperty("gsm.operator.numeric", ""),
                getSystemProperty("gsm.network.type", "")
        );
    }

    static String resolvePhysicalDeviceMcc(String rawSimNumerics, String rawNetNumerics, String rawNetTypes) {
        String[] simParts = isEmptyStr(rawSimNumerics) ? new String[0] : rawSimNumerics.split(",", -1);
        String[] netParts = isEmptyStr(rawNetNumerics) ? new String[0] : rawNetNumerics.split(",", -1);
        String[] typeParts = isEmptyStr(rawNetTypes) ? new String[0] : rawNetTypes.split(",", -1);
        int count = Math.max(netParts.length, Math.max(simParts.length, typeParts.length));

        // Tier 1：真实蜂窝无线制式下的驻网 MCC 作为最强物理所在国证据
        for (int i = 0; i < count; i++) {
            String netMcc = extractMcc(getPartAt(rawNetNumerics, i));
            String netType = getPartAt(rawNetTypes, i);
            if (netMcc != null && hasActiveCellularRadioTech(netType)) {
                return netMcc;
            }
        }

        // Tier 2：双卡均无有效蜂窝制式时，采信存在跨国漫游差异（netMcc != simMcc）的驻网 MCC
        for (int i = 0; i < count; i++) {
            String netMcc = extractMcc(getPartAt(rawNetNumerics, i));
            String simMcc = extractMcc(getPartAt(rawSimNumerics, i));
            if (netMcc != null && simMcc != null && !netMcc.equals(simMcc)) {
                return netMcc;
            }
        }
        return null;
    }

    /**
     * 判断指定卡槽是否处于“跨国漫游/需归属国伪装”态，识破无蜂窝或 IWLAN 下的假归属 PLMN。
     */
    public static boolean isSlotRoamingMismatch(int slotIndex) {
        return isSlotRoamingMismatch(
                slotIndex,
                getSystemProperty("gsm.sim.operator.numeric", ""),
                getSystemProperty("gsm.operator.numeric", ""),
                getSystemProperty("gsm.network.type", ""),
                getSystemProperty("gsm.sim.operator.iso-country", ""),
                readOverrideIso()
        );
    }

    static boolean isSlotRoamingMismatch(
            int slotIndex,
            String rawSimNumerics,
            String rawNetNumerics,
            String rawNetTypes,
            String rawSimIsos,
            String overrideIso) {
        if (overrideIso != null) {
            return true;
        }
        if (extractIsoFromProp(rawSimIsos, slotIndex) == null) {
            return false;
        }
        String simMcc = extractMcc(getPartAt(rawSimNumerics, slotIndex));
        String netMcc = extractMcc(getPartAt(rawNetNumerics, slotIndex));
        String netType = getPartAt(rawNetTypes, slotIndex);

        if (simMcc != null && netMcc != null) {
            if (!simMcc.equals(netMcc)) {
                return true;
            }
            if (hasActiveCellularRadioTech(netType)) {
                return false;
            }
        }

        // 本槽无 netMcc 或仅在 Unknown/空/IWLAN 下出现同国假 PLMN 时，依赖全机真实物理 MCC 交叉校验
        String physicalMcc = resolvePhysicalDeviceMcc(rawSimNumerics, rawNetNumerics, rawNetTypes);
        if (physicalMcc != null && simMcc != null) {
            return !simMcc.equals(physicalMcc);
        }
        return true;
    }

    /**
     * 判断给定卡槽是否需要执行归属国欺骗（存在有效 SIM 归属国且处于跨国漫游或无真实本地驻网）。
     */
    public static boolean shouldSpoofSlot(int slotIndex) {
        return getTargetIsoForSlot(slotIndex) != null && isSlotRoamingMismatch(slotIndex);
    }

    /**
     * 获取全局默认目标国家码（结合物理驻网 MCC 与有效蜂窝制式识别无蜂窝/被假 PLMN 污染的跨国卡）。
     */
    public static String getGlobalTargetIso() {
        return getGlobalTargetIso(
                getSystemProperty("gsm.sim.operator.numeric", ""),
                getSystemProperty("gsm.operator.numeric", ""),
                getSystemProperty("gsm.network.type", ""),
                getSystemProperty("gsm.sim.operator.iso-country", ""),
                readOverrideIso()
        );
    }

    static String getGlobalTargetIso(
            String rawSimNumerics,
            String rawNetNumerics,
            String rawNetTypes,
            String rawSimIsos,
            String overrideIso) {
        if (overrideIso != null) {
            return overrideIso;
        }
        if (isEmptyStr(rawSimIsos)) {
            return null;
        }
        String[] isoParts = rawSimIsos.split(",", -1);
        String physicalMcc = resolvePhysicalDeviceMcc(rawSimNumerics, rawNetNumerics, rawNetTypes);

        String firstFallbackIso = null;
        String nonCnFallbackIso = null;
        for (int i = 0; i < isoParts.length; i++) {
            String iso = extractIsoFromProp(rawSimIsos, i);
            if (iso == null) {
                continue;
            }
            if (firstFallbackIso == null) {
                firstFallbackIso = iso;
            }
            if (nonCnFallbackIso == null && !"cn".equals(iso)) {
                nonCnFallbackIso = iso;
            }
            if (physicalMcc != null) {
                String simMcc = extractMcc(getPartAt(rawSimNumerics, i));
                if (simMcc != null && !simMcc.equals(physicalMcc)) {
                    return iso;
                }
            }
        }

        if (physicalMcc != null) {
            return null;
        }
        // 整机无可靠蜂窝驻网（飞行模式或全脱网）时，优先返回首个非本土 CN 归属国
        return nonCnFallbackIso != null ? nonCnFallbackIso : firstFallbackIso;
    }

    /**
     * 根据目标国家码获取对应的合法地理档案；未收录国家标记 hasGeo=false，绝不伪造矛盾经纬度。
     */
    public static GeoProfile getGeoProfile(String iso) {
        String lower = iso != null ? iso.toLowerCase(Locale.US) : "";
        GeoProfile profile = GEO_PROFILES.get(lower);
        if (profile != null) {
            return profile;
        }
        String upper = lower.toUpperCase(Locale.US);
        Locale locale = isValidTwoLetterIso(lower) ? new Locale("en", upper) : Locale.US;
        return new GeoProfile(lower, upper, "", "", "", "", "", 0.0, 0.0, locale, false);
    }

    /**
     * 构造或原地覆写 android.location.Address，消除真实漫游地与街道信息。
     */
    public static Address sanitizeAddress(Address existing, String iso) {
        GeoProfile profile = getGeoProfile(iso);
        Address addr = existing != null ? existing : new Address(profile.locale);
        addr.setCountryCode(profile.isoUpper);
        addr.setCountryName(profile.countryName);
        if (profile.hasGeo) {
            addr.setAdminArea(profile.adminArea);
            addr.setSubAdminArea(null);
            addr.setLocality(profile.locality);
            addr.setSubLocality(null);
            addr.setPostalCode(profile.postalCode);
            addr.setThoroughfare(profile.thoroughfare);
            addr.setSubThoroughfare(profile.subThoroughfare);
            addr.setFeatureName(profile.subThoroughfare + " " + profile.thoroughfare);
            addr.setLatitude(profile.lat);
            addr.setLongitude(profile.lon);
            addr.setAddressLine(0, profile.subThoroughfare + " " + profile.thoroughfare + ", " + profile.locality + ", " + profile.postalCode);
        } else {
            addr.setAdminArea(null);
            addr.setSubAdminArea(null);
            addr.setLocality(null);
            addr.setSubLocality(null);
            addr.setPostalCode(null);
            addr.setThoroughfare(null);
            addr.setSubThoroughfare(null);
            addr.setFeatureName(null);
            addr.clearLatitude();
            addr.clearLongitude();
            addr.setAddressLine(0, null);
        }
        for (int i = 1; i < 6; i++) {
            addr.setAddressLine(i, null);
        }
        return addr;
    }
}
