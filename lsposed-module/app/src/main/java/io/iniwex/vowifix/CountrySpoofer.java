package io.iniwex.vowifix;

import android.location.Address;
import android.text.TextUtils;

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

    private static void addProfile(Map<String, GeoProfile> map, String iso, String countryName,
                                   String adminArea, String locality, String postalCode,
                                   String thoroughfare, String subThoroughfare,
                                   double lat, double lon, Locale locale) {
        map.put(iso, new GeoProfile(iso, countryName, adminArea, locality, postalCode,
                thoroughfare, subThoroughfare, lat, lon, locale, true));
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

    /**
     * 按卡槽索引切分逗号分隔的 Telephony 属性。
     */
    private static String getSlotPropPart(String propName, int slotIndex) {
        if (slotIndex < 0) {
            return null;
        }
        String raw = getSystemProperty(propName, "");
        if (TextUtils.isEmpty(raw)) {
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
     * 从 5~6 位全数字 PLMN 提取 3 位 MCC，无效返回 null。
     */
    private static String extractMcc(String plmn) {
        if (plmn == null || plmn.length() < 3) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            if (!Character.isDigit(plmn.charAt(i))) {
                return null;
            }
        }
        return plmn.substring(0, 3);
    }

    /**
     * 获取指定卡槽 SIM 卡声明的归属国 ISO（小写 2 位字母）。
     */
    public static String getTargetIsoForSlot(int slotIndex) {
        String override = readOverrideIso();
        if (override != null) {
            return override;
        }
        String iso = getSlotPropPart("gsm.sim.operator.iso-country", slotIndex);
        if (iso != null) {
            String lower = iso.toLowerCase(Locale.US);
            if (isValidTwoLetterIso(lower)) {
                return lower;
            }
        }
        return null;
    }

    /**
     * 判断指定卡槽是否处于“SIM 归属 MCC 与当前蜂窝驻网 MCC 不匹配”（跨国漫游）状态。
     */
    public static boolean isSlotRoamingMismatch(int slotIndex) {
        if (readOverrideIso() != null) {
            return true;
        }
        if (getTargetIsoForSlot(slotIndex) == null) {
            return false;
        }
        String simMcc = extractMcc(getSlotPropPart("gsm.sim.operator.numeric", slotIndex));
        String netMcc = extractMcc(getSlotPropPart("gsm.operator.numeric", slotIndex));
        if (simMcc != null && netMcc != null) {
            return !simMcc.equals(netMcc);
        }
        return true;
    }

    /**
     * 判断给定卡槽是否需要执行归属国欺骗（存在有效 SIM 归属国且跨国漫游/无驻网）。
     */
    public static boolean shouldSpoofSlot(int slotIndex) {
        return getTargetIsoForSlot(slotIndex) != null && isSlotRoamingMismatch(slotIndex);
    }

    /**
     * 获取全局默认目标国家码（兼容副卡无蜂窝驻网或飞行模式等跨国双卡场景）。
     */
    public static String getGlobalTargetIso() {
        String override = readOverrideIso();
        if (override != null) {
            return override;
        }
        String rawIso = getSystemProperty("gsm.sim.operator.iso-country", "");
        if (TextUtils.isEmpty(rawIso)) {
            return null;
        }
        String[] isoParts = rawIso.split(",", -1);
        String firstFallbackIso = null;
        String nonCnFallbackIso = null;
        String knownDeviceNetMcc = null;

        // 第 1 遍：记录兜底归属国与设备已确认驻网 MCC，同时优先识别自包含跨国漫游的卡槽
        for (int i = 0; i < isoParts.length; i++) {
            String iso = getTargetIsoForSlot(i);
            if (iso == null) {
                continue;
            }
            if (firstFallbackIso == null) {
                firstFallbackIso = iso;
            }
            if (nonCnFallbackIso == null && !"cn".equals(iso)) {
                nonCnFallbackIso = iso;
            }
            String simMcc = extractMcc(getSlotPropPart("gsm.sim.operator.numeric", i));
            String netMcc = extractMcc(getSlotPropPart("gsm.operator.numeric", i));
            if (netMcc != null) {
                if (knownDeviceNetMcc == null) {
                    knownDeviceNetMcc = netMcc;
                }
                if (simMcc != null && !simMcc.equals(netMcc)) {
                    return iso;
                }
            }
        }

        // 第 2 遍：另一张卡已证实设备位于异国时，识别无蜂窝服务的海外跨国副卡；纯本土双卡返回 null
        if (knownDeviceNetMcc != null) {
            for (int i = 0; i < isoParts.length; i++) {
                String iso = getTargetIsoForSlot(i);
                if (iso == null) {
                    continue;
                }
                String netMcc = extractMcc(getSlotPropPart("gsm.operator.numeric", i));
                if (netMcc == null) {
                    String simMcc = extractMcc(getSlotPropPart("gsm.sim.operator.numeric", i));
                    if (simMcc != null && !simMcc.equals(knownDeviceNetMcc)) {
                        return iso;
                    }
                }
            }
            return null;
        }

        // 全双卡无蜂窝驻网或处于飞行模式时，优先返回首个非本土 CN 归属国
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
