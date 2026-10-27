package analyzer.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mackolik arxiv hostu — bütün mackolik analizörlərinin ortaq URL mənbəyi.
 * ────────────────────────────────────────────────────────────────────────────
 * PROBLEM (2026-10-19-dan bəri):
 *   arsiv.mackolik.com GitHub Actions runner-lərinin (Microsoft/Azure, AS8075)
 *   IP-lərini bloklayır: Team / FixtureHandler / Puan-Durumu / Head2Head — hamısı
 *   boş gövdəli "HTTP 403" qaytarır (origin nginx). User-Agent / header dəyişmək
 *   kömək etmir, headless Chrome da 403 alır. Nəticədə bütün mackolik analizləri
 *   "0 tapıldı" yazıb 10 saniyəyə bitirdi.
 *
 * HƏLL:
 *   Eyni arxivin ikinci hostu arsiv2.mackolik.com runner-dən 200 qaytarır və
 *   eyni HTML-i verir (tblFixture, eyni ölçü). Bütün URL-lər buradan qurulur.
 *   MackolikHttpFetcher bir host 403 verəndə avtomatik növbəti hosta keçir.
 *
 * Konfiqurasiya (istəyə bağlı):
 *   MACKOLIK_HOSTS mühit dəyişəni və ya -Dmackolik.hosts — vergüllə ayrılmış
 *   host siyahısı, sıra ilə sınanır. Default: arsiv2.mackolik.com,arsiv.mackolik.com
 * ────────────────────────────────────────────────────────────────────────────
 */
public final class MackolikHost {

    /** Kodda və önbellek açarlarında istifadə olunan "kanonik" host. */
    public static final String CANONICAL_HOST = "arsiv.mackolik.com";

    private static final List<String> HOSTS = resolveHosts();

    /** 403 ilə bloklandığı görülən hostlar (bütün thread-lər üçün ortaq). */
    private static final Set<String> BLOCKED = ConcurrentHashMap.newKeySet();

    /** Birinci (üstünlük verilən) host üzrə baza URL, məs. "https://arsiv2.mackolik.com". */
    public static final String BASE = "https://" + HOSTS.get(0);

    private MackolikHost() {}

    private static List<String> resolveHosts() {
        String raw = System.getProperty("mackolik.hosts");
        if (raw == null || raw.isBlank()) raw = System.getenv("MACKOLIK_HOSTS");
        if (raw == null || raw.isBlank()) raw = "arsiv2.mackolik.com," + CANONICAL_HOST;
        List<String> out = new ArrayList<>();
        for (String h : raw.split(",")) {
            h = h.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
            if (!h.isEmpty() && !out.contains(h)) out.add(h);
        }
        if (out.isEmpty()) out.add(CANONICAL_HOST);
        return Collections.unmodifiableList(out);
    }

    public static List<String> hosts() { return HOSTS; }

    /** URL mackolik arxivinə aiddirmi (hansı host olursa olsun)? */
    public static boolean isArchiveUrl(String url) {
        String h = hostOf(url);
        return h != null && (h.equals(CANONICAL_HOST) || HOSTS.contains(h));
    }

    /** URL-i kanonik hosta çevirir (önbellek açarı üçün — host dəyişsə də önbellek etibarlı qalır). */
    public static String canonical(String url) {
        return isArchiveUrl(url) ? withHost(url, CANONICAL_HOST) : url;
    }

    /**
     * Sınanacaq URL variantları: bloklanmamış hostlar əvvəl, bloklananlar ən sonda
     * (hamısı bloklanıbsa yenə də sınanır — blok müvəqqəti ola bilər).
     */
    public static List<String> candidates(String url) {
        if (!isArchiveUrl(url)) return List.of(url);
        List<String> ok = new ArrayList<>(), blocked = new ArrayList<>();
        for (String h : HOSTS) (BLOCKED.contains(h) ? blocked : ok).add(withHost(url, h));
        ok.addAll(blocked);
        return ok;
    }

    /** Host 403 verdi → sonrakı sorğular əvvəlcə digər hostlara getsin. */
    public static void markBlocked(String url) {
        String h = hostOf(url);
        if (h != null && BLOCKED.add(h) && HOSTS.size() > 1) {
            System.err.println("⚠️ " + h + " 403 qaytarır — digər mackolik hostuna keçilir "
                    + "(sıra: " + String.join(", ", candidates("https://" + h + "/")
                    .stream().map(MackolikHost::hostOf).toList()) + ")");
        }
    }

    static String hostOf(String url) {
        if (url == null) return null;
        int s = url.indexOf("://");
        if (s < 0) return null;
        int start = s + 3;
        int end = start;
        while (end < url.length() && "/?#:".indexOf(url.charAt(end)) < 0) end++;
        return url.substring(start, end).toLowerCase();
    }

    static String withHost(String url, String host) {
        int s = url.indexOf("://");
        int start = s + 3;
        int end = start;
        while (end < url.length() && "/?#:".indexOf(url.charAt(end)) < 0) end++;
        return "https://" + host + url.substring(end);
    }

    /** Diaqnostika üçün: "arsiv2.mackolik.com → arsiv.mackolik.com". */
    public static String describe() {
        return String.join(" → ", HOSTS);
    }

    // Kiçik öz-test: java analyzer.util.MackolikHost
    public static void main(String[] args) {
        String u = "https://arsiv.mackolik.com/Team/Default.aspx?id=451&season=2026/2027";
        System.out.println(describe());
        System.out.println(candidates(u));
        System.out.println(canonical(candidates(u).get(0)));
        markBlocked(candidates(u).get(0));
        System.out.println(candidates(u));
        System.out.println(candidates("https://vd.mackolik.com/livedata?group=all"));
        System.out.println(Arrays.asList(BASE));
    }
}
