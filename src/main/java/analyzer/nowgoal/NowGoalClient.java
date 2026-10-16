package analyzer.nowgoal;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * NowGoal üçün ortaq HTTP / domen / liqa ID köməkçisi.
 * ────────────────────────────────────────────────────────────────────────────
 * PROBLEM (niyə nəticə boş gəlirdi):
 *   NowGoal domeni dəyişib: football.nowgoal26.com → football.nowgoal50.com,
 *   live8.nowgoal26.com → live.nowgoal50.com. Köhnə ünvanlar 302 qaytarır.
 *   java.net.http.HttpClient isə DEFAULT olaraq redirect-ləri İZLƏMİR
 *   (Redirect.NEVER), ona görə hər JSON sorğusu "HTTP 302" xətası atırdı,
 *   loadSeasons/loadLeague səssizcə boş/null qaytarırdı və heç bir matç çıxmırdı.
 *
 * HƏLL:
 *   1) HttpClient redirect-ləri izləyir.
 *   2) Proqram açılanda giriş ünvanından son (redirect olunmuş) domen tapılır və
 *      bütün sorğular birbaşa ona gedir — domen yenə dəyişsə, kod özü uyğunlaşır.
 *   3) Liqa ID-ləri həm render olunmuş DOM-dakı [sclassid]-dən, həm də səhifədəki
 *      /league/ID linklərindən yığılır; Selenium heç nə tapmasa statik HTML-dən.
 * ────────────────────────────────────────────────────────────────────────────
 */
final class NowGoalClient {

    /** Giriş ünvanları — sayt bunları hazırkı domenə yönləndirir. */
    static final String FOOTBALL_ENTRY = "https://football.nowgoal26.com";
    static final String LIVE_ENTRY     = "https://live8.nowgoal26.com";

    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/129.0 Safari/537.36";

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    private static volatile String base;
    private static volatile String liveBase;

    private static final Pattern LEAGUE_LINK =
            Pattern.compile("/(?:sub)?league/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCLASS_ATTR =
            Pattern.compile("sclassid\\s*=\\s*['\"]?(\\d+)", Pattern.CASE_INSENSITIVE);

    private NowGoalClient() {}

    /** Hazırkı football domeni (məs. https://football.nowgoal50.com). */
    static String base() {
        if (base == null) init();
        return base;
    }

    /** Hazırkı canlı nəticələr domeni (məs. https://live.nowgoal50.com). */
    static String liveBase() {
        if (liveBase == null) init();
        return liveBase;
    }

    static synchronized void init() {
        if (base != null && liveBase != null) return;
        base     = resolveOrigin(FOOTBALL_ENTRY);
        liveBase = resolveOrigin(LIVE_ENTRY);
        System.out.println(">>> NowGoal domeni: " + base + "  |  canlı: " + liveBase);
    }

    /** Redirect-ləri izləyib son ünvanın "scheme://host"-unu qaytarır. Alınmasa girişi. */
    static String resolveOrigin(String entry) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(entry + "/"))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            URI u = resp.uri();
            if (u != null && u.getHost() != null) return u.getScheme() + "://" + u.getHost();
        } catch (Exception e) {
            System.out.println("[XƏBƏRDARLIQ] domen tapılmadı (" + entry + "): " + e.getMessage());
        }
        return entry;
    }

    /** HTTP GET: redirect + gzip + BOM; müvəqqəti xətalarda 3 dəfəyə qədər yenidən cəhd. */
    static String get(String url) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", UA)
                        .header("Referer",    base() + "/")
                        .header("Accept",     "text/html,application/json,*/*")
                        .GET().build();

                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int sc = resp.statusCode();
                if (sc == 404) throw new NotFound(url);
                if (sc != 200) throw new RuntimeException("HTTP " + sc + " " + url);

                byte[] b = resp.body();
                if (b.length >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) {
                    try (var gis = new GZIPInputStream(new ByteArrayInputStream(b))) {
                        b = gis.readAllBytes();
                    }
                }
                if (b.length >= 3 && b[0] == (byte) 0xEF && b[1] == (byte) 0xBB && b[2] == (byte) 0xBF)
                    b = Arrays.copyOfRange(b, 3, b.length);
                return new String(b, StandardCharsets.UTF_8);
            } catch (NotFound nf) {
                throw nf;                         // 404 — yenidən cəhdin mənası yoxdur
            } catch (Exception e) {
                last = e;
                Thread.sleep(400L * attempt);
            }
        }
        throw last;
    }

    /** 404 — məs. liqanın həmin sezon JSON-u yoxdur. */
    static final class NotFound extends RuntimeException {
        NotFound(String url) { super("HTTP 404 " + url); }
    }

    // ── LİQA ID-LƏRİ ──────────────────────────────────────────
    /**
     * Canlı səhifədən bugünkü liqaların ID-lərini yığır.
     * 1) Selenium: render olunmuş DOM-da [sclassid] + /league/ID linkləri
     *    (səhifə JS ilə yükləndiyi üçün 25 saniyəyə qədər gözləyir).
     * 2) Heç nə tapılmasa: statik HTML-dən /league/ID linkləri.
     */
    static List<Integer> fetchLeagueIds() {
        Set<Integer> ids = new TreeSet<>();
        String live = liveBase();

        WebDriver driver = null;
        try {
            WebDriverManager.chromedriver().setup();
            ChromeOptions opts = new ChromeOptions();
            opts.addArguments("--headless=new", "--disable-gpu", "--no-sandbox",
                    "--disable-dev-shm-usage", "--blink-settings=imagesEnabled=false",
                    "--log-level=3", "--silent", "--user-agent=" + UA);
            driver = new ChromeDriver(opts);
            driver.get(live + "/");

            // Matç cədvəli JS ilə gəlir — sabit 5 san. əvəzinə sayı sabitləşənə qədər gözlə
            int prev = -1, stable = 0;
            long deadline = System.currentTimeMillis() + 25_000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1500);
                int n = driver.findElements(By.cssSelector("[sclassid]")).size();
                if (n > 0 && n == prev) { if (++stable >= 2) break; } else stable = 0;
                prev = n;
            }

            for (WebElement el : driver.findElements(By.cssSelector("[sclassid]"))) {
                try {
                    String v = el.getAttribute("sclassid");
                    if (v != null && !v.isBlank()) ids.add(Integer.parseInt(v.trim()));
                } catch (Exception ignored) {}
            }
            collect(driver.getPageSource(), ids);
        } catch (Exception e) {
            System.out.println("Selenium xetasi: " + e.getMessage());
        } finally {
            if (driver != null) try { driver.quit(); } catch (Exception ignored) {}
        }
        System.out.println("DOM-dan tapilan ID sayi: " + ids.size());

        if (ids.isEmpty()) {
            try {
                collect(get(live + "/"), ids);
                System.out.println("Statik HTML-dən tapılan ID sayı: " + ids.size());
            } catch (Exception e) {
                System.out.println("Canlı səhifə HTTP xətası: " + e.getMessage());
            }
        }
        return new ArrayList<>(ids);
    }

    private static void collect(String html, Set<Integer> ids) {
        if (html == null) return;
        for (Pattern p : new Pattern[]{SCLASS_ATTR, LEAGUE_LINK}) {
            Matcher m = p.matcher(html);
            while (m.find()) {
                try { ids.add(Integer.parseInt(m.group(1))); } catch (Exception ignored) {}
            }
        }
    }

    // ── SEZON EHTİYAT SİYAHISI ────────────────────────────────
    /**
     * Sezon endpoint-i cavab verməyəndə sınanacaq cari sezon açarları —
     * il kodda sabit deyil, bugünkü tarixdən hesablanır.
     */
    static String[] seasonCandidates() {
        int y = java.time.Year.now().getValue();
        return new String[]{
                y + "-" + (y + 1), String.valueOf(y), y + "/" + String.format("%02d", (y + 1) % 100),
                (y - 1) + "-" + y, String.valueOf(y - 1), (y - 1) + "/" + String.format("%02d", y % 100)
        };
    }
}
