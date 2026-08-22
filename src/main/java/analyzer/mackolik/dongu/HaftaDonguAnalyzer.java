package analyzer.mackolik.dongu;

import analyzer.util.TeamIdsFetcher;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HAFTA DÖNGÜ ANALİZÖRÜ (yıllık + haftalık döngü, döngü uzunluğu DİNAMİK)
 *
 * Fikir (Kopenhag örneği, açılış maçına ÖZEL DEĞİL — her hafta için geçerli):
 *   Bir takımın belirli bir HAFTASI (matchday), sabit bir YIL döngüsünde
 *   aynı HT/FT sürprizini tekrar edebiliyor. Döngü iki boyutlu:
 *     - HAFTALIK: aynı hafta numarası (1., 2., 3. ... hafta)
 *     - YILLIK  : eşit yıl aralığıyla (P yıl); P sabit 7 DEĞİL, keşfedilir.
 *
 * SÜRPRİZ / COMEBACK TANIMI:
 *   İlk yarıyı önde kapatan takım maçı KAZANAMAZ:
 *     - FULL COMEBACK      : HT/FT = 1/2 veya 2/1
 *     - BERABERLİK COMEBACK: HT/FT = 1/X veya 2/X
 *
 * ─────────────────────────────────────────────────────────────────────────
 * v2 — AĞ KATMANI SAĞLAMLAŞTIRILDI ("Read timed out" düzeltmeleri):
 *   1. Socket/connect timeout'ları büyütüldü + client seviyesinde tanımlandı.
 *   2. Her istek için üstel geri çekilmeli (exponential backoff + jitter) RETRY.
 *   3. 200 olmayan cevaplarda entity KESİNLİKLE tüketiliyor → bağlantı havuza
 *      geri dönüyor (bu, art arda gelen "Read timed out" hatalarının ana sebebi).
 *   4. 429/5xx durum kodları da yeniden deneniyor; 404 gibi kalıcı kodlar denenmiyor.
 *   5. Ölü/boşta bağlantılar tahliye ediliyor (evictIdle + validateAfterInactivity).
 *   6. Global istek aralığı (throttle) ile sunucu boğulmuyor.
 *   7. Tek bir sezonun indirilememesi artık takımın tamamını düşürmüyor;
 *      "veri yok" olarak işaretlenip döngü SİNYAL sayılmıyor (yanlış pozitif olmaz).
 *   8. Charset artık HTML meta'sından tespit ediliyor (Türkçe/İzlandaca isimler bozulmuyor).
 *
 * Kullanım:
 *   java ... HaftaDonguAnalyzer                    → günün başlamamış maçlarındaki takımlar
 *   java ... HaftaDonguAnalyzer 5088 2029          → yalnız verilen takım ID'leri
 *   java ... HaftaDonguAnalyzer --all 5088         → boşluklu/eksik döngüleri de yazdır
 *   java ... HaftaDonguAnalyzer --threads=6        → eşzamanlılığı düşür (timeout alıyorsan)
 *   java ... HaftaDonguAnalyzer --gap=50           → istekler arası min. ms (nazik tarama)
 */
public class HaftaDonguAnalyzer {

    // ─── AYARLAR ────────────────────────────────────────────────────────────
    private static final String BASE_URL = "https://arsiv.mackolik.com/Team/Default.aspx?id=%d&season=%s";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";

    /** Yeni futbol sezonunun başladığı ay (Temmuz). */
    private static final int SEASON_START_MONTH = 7;

    private static final int CURRENT_SEASON_START_YEAR = computeCurrentSeasonStartYear();
    private static final String CURRENT_SEASON = CURRENT_SEASON_START_YEAR + "/" + (CURRENT_SEASON_START_YEAR + 1);

    /** Kaç yıl geriye bakılır. */
    private static final int LOOKBACK_YEARS = 20;
    /** Denenecek en küçük döngü uzunluğu (yıl). */
    private static final int MIN_CYCLE_YEARS = 2;
    /** Bir döngünün sinyal sayılması için gereken en az adım sayısı. */
    private static final int MIN_CYCLE = 2;
    /** Denenecek en büyük döngü uzunluğu. */
    private static final int MAX_CYCLE_YEARS = LOOKBACK_YEARS / MIN_CYCLE;

    // ─── AĞ AYARLARI (timeout fix) ──────────────────────────────────────────
    /** Eşzamanlı iş parçacığı sayısı (--threads=N ile değiştirilebilir). */
    private static int numThreads = 8;
    /** İki istek arasındaki en küçük global boşluk, ms (--gap=N). */
    private static long minRequestGapMs = 25;

    private static final int CONNECT_TIMEOUT_MS     = 15_000;
    private static final int SOCKET_TIMEOUT_MS      = 30_000;   // "Read timed out" burada oluşuyordu
    private static final int CONN_REQUEST_TIMEOUT_MS = 20_000;

    /** Bir URL için toplam deneme sayısı (1 ilk deneme + 3 retry). */
    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_BASE_DELAY_MS = 700;
    private static final long RETRY_MAX_DELAY_MS  = 8_000;

    /** Geçici sayılan ve yeniden denenen HTTP durum kodları. */
    private static final Set<Integer> RETRYABLE_STATUS =
            new HashSet<>(Arrays.asList(408, 425, 429, 500, 502, 503, 504));

    /** Bir takım analizinin tamamı için üst sınır. */
    private static final int TASK_TIMEOUT_MINUTES = 15;

    // ─── SAYAÇLAR ───────────────────────────────────────────────────────────
    private static final AtomicInteger FETCH_OK       = new AtomicInteger();
    private static final AtomicInteger FETCH_RETRY    = new AtomicInteger();
    private static final AtomicInteger FETCH_FAILED   = new AtomicInteger();
    private static final Object PRINT_LOCK = new Object();

    private static int computeCurrentSeasonStartYear() {
        LocalDate now = LocalDate.now();
        return now.getMonthValue() >= SEASON_START_MONTH ? now.getYear() : now.getYear() - 1;
    }

    // ─── COMEBACK GRUPLARI ──────────────────────────────────────────────────
    static final String FULL_COMEBACK       = "FULL COMEBACK";
    static final String BERABERLIK_COMEBACK = "BERABERLİK COMEBACK";

    // ─── MAIN ───────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        boolean showAll = false;
        List<String> teamIds = new ArrayList<>();
        if (args != null) {
            for (String raw : args) {
                String arg = raw == null ? "" : raw.trim();
                if (arg.isEmpty()) continue;
                if ("--all".equalsIgnoreCase(arg)) {
                    showAll = true;
                } else if (arg.toLowerCase().startsWith("--threads=")) {
                    numThreads = Math.max(1, parseIntOr(arg.substring(10), numThreads));
                } else if (arg.toLowerCase().startsWith("--gap=")) {
                    minRequestGapMs = Math.max(0, parseIntOr(arg.substring(6), (int) minRequestGapMs));
                } else {
                    teamIds.add(arg);
                }
            }
        }

        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  HAFTA DÖNGÜ ANALİZÖRÜ (döngü uzunluğu dinamik)       ║");
        System.out.println("║  1/2, 2/1 → FULL COMEBACK                             ║");
        System.out.println("║  1/X, 2/X → BERABERLİK COMEBACK                       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");

        long globalStart = System.currentTimeMillis();

        System.out.printf("📅 Güncel sezon: %s | Döngü: %d..%d yıl (dinamik) | Taranan: son %d yıl%n",
                CURRENT_SEASON, MIN_CYCLE_YEARS, MAX_CYCLE_YEARS, LOOKBACK_YEARS);
        System.out.printf("🌐 Ağ: %d thread | timeout %ds | %d deneme | istek aralığı %dms%n%n",
                numThreads, SOCKET_TIMEOUT_MS / 1000, MAX_ATTEMPTS, minRequestGapMs);

        if (teamIds.isEmpty()) {
            System.out.println("🔄 Günün başlamamış maçlarından takım ID'leri alınıyor...");
            teamIds = TeamIdsFetcher.fetchUnstartedTeamIds();
            System.out.println("✅ " + teamIds.size() + " takım bulundu\n");
        }

        if (teamIds.isEmpty()) {
            System.out.println("❌ Analiz edilecek takım yok, program sonlandırılıyor.");
            return;
        }

        CloseableHttpClient http = buildHttpClient();
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        List<Future<String>> futures = new ArrayList<>();
        for (String idStr : teamIds) {
            final int teamId;
            try {
                teamId = Integer.parseInt(idStr.trim());
            } catch (NumberFormatException e) {
                System.err.println("   ❌ Geçersiz takım ID: " + idStr);
                continue;
            }
            final boolean all = showAll;
            futures.add(executor.submit((Callable<String>) () -> {
                try {
                    return analyzeTeam(http, teamId, all);
                } catch (Exception e) {
                    // Ağ hatası artık burada da yutuluyor: tek takım tüm taramayı bozmaz.
                    printErr("   ⚠️ [ID:" + teamId + "] atlandı: " + kisaHata(e));
                    return null;
                }
            }));
        }

        int processed = 0, signalCount = 0;
        for (Future<String> f : futures) {
            String result = null;
            try {
                result = f.get(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                f.cancel(true);
                printErr("   ⚠️ Görev zaman aşımı/hata: " + kisaHata(e));
            }
            processed++;
            if (result != null && !result.isEmpty()) {
                signalCount++;
                synchronized (PRINT_LOCK) {
                    System.out.println();
                    System.out.println(result);
                }
            }
            synchronized (PRINT_LOCK) {
                System.out.printf("\r⏳ İlerleme: %d/%d  |  Sinyal: %d  |  İstek: %d ok / %d retry / %d başarısız   ",
                        processed, futures.size(), signalCount,
                        FETCH_OK.get(), FETCH_RETRY.get(), FETCH_FAILED.get());
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            http.close();
        } catch (IOException ignored) {
        }

        long sure = (System.currentTimeMillis() - globalStart) / 1000;
        System.out.println("\n\n════════════════════════════════════════════════════════");
        System.out.printf("✅ TAMAMLANDI | %d takım | %d hafta-döngü sinyali | %ds%n", processed, signalCount, sure);
        System.out.printf("🌐 İstek özeti: %d başarılı, %d yeniden deneme, %d kalıcı başarısız%n",
                FETCH_OK.get(), FETCH_RETRY.get(), FETCH_FAILED.get());
        System.out.println("════════════════════════════════════════════════════════\n");

        System.exit(0);
    }

    private static int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static void printErr(String msg) {
        synchronized (PRINT_LOCK) {
            System.out.println();
            System.err.println(msg);
        }
    }

    private static String kisaHata(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m != null ? ": " + m : "");
    }

    // ─── HTTP CLIENT (timeout-dayanıklı) ────────────────────────────────────
    private static CloseableHttpClient buildHttpClient() {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(numThreads * 2);
        // Tüm istekler aynı host'a gidiyor → per-route limit thread sayısından KÜÇÜK olmamalı,
        // aksi halde thread'ler bağlantı bekler ve connectionRequest timeout alır.
        cm.setDefaultMaxPerRoute(numThreads * 2);
        // Havuzdaki bağlantı 2sn'den uzun boştaysa kullanmadan önce doğrula (yarı-kapalı soket = read timeout).
        cm.setValidateAfterInactivity(2_000);
        cm.setDefaultSocketConfig(SocketConfig.custom()
                .setSoTimeout(SOCKET_TIMEOUT_MS)
                .setSoKeepAlive(true)
                .setTcpNoDelay(true)
                .build());

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setConnectionRequestTimeout(CONN_REQUEST_TIMEOUT_MS)
                .setSocketTimeout(SOCKET_TIMEOUT_MS)
                .setCookieSpec(CookieSpecs.STANDARD)
                .setRedirectsEnabled(true)
                .build();

        return HttpClients.custom()
                .setConnectionManager(cm)
                .setConnectionManagerShared(false)
                .setDefaultRequestConfig(requestConfig)
                .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE)
                .evictExpiredConnections()
                .evictIdleConnections(30, TimeUnit.SECONDS)
                .disableAutomaticRetries()   // retry'ı biz yönetiyoruz (aşağıda)
                .build();
    }

    // ─── ANA ANALİZ ─────────────────────────────────────────────────────────
    static String analyzeTeam(CloseableHttpClient http, int teamId, boolean showAll) {

        // 1. Güncel sezon + bugünkü maç (ilk oynanmamış fikstür) → hedef hafta
        List<MacData> current = fetchSeasonMatches(http, teamId, CURRENT_SEASON);
        if (current == null || current.isEmpty()) return null;   // null → indirilemedi

        String teamName = detectTeamNameFromRows(current);
        if (teamName == null) return null;

        int weekIdx = -1;
        for (int i = 0; i < current.size(); i++) {
            if (!current.get(i).played) {
                weekIdx = i;
                break;
            }
        }
        if (weekIdx < 0) return null;          // sezon bitmiş, hedef hafta yok
        int weekNo = weekIdx + 1;
        MacData target = current.get(weekIdx);

        // 2. Her döngü uzunluğu P için aynı haftayı eşit aralıkla geriye tara.
        Map<Integer, List<MacData>> cache = new HashMap<>();   // değer null olabilir → indirilemedi
        List<Cycle> adaylar = new ArrayList<>();

        for (int p = MIN_CYCLE_YEARS; p <= MAX_CYCLE_YEARS; p++) {
            List<CycleHit> pts = new ArrayList<>();
            int expected = 0, missing = 0, comebacks = 0;

            for (int k = 1; k * p <= LOOKBACK_YEARS; k++) {
                expected++;
                CycleHit h = hitAt(http, teamId, k * p, weekIdx, cache);
                if (h == null) { missing++; continue; }
                pts.add(h);
                if (h.grup != null) comebacks++;
            }

            boolean complete = missing == 0;
            boolean allCome  = !pts.isEmpty() && comebacks == pts.size();
            boolean sinyal   = complete && allCome && pts.size() >= MIN_CYCLE;
            boolean gosterile = pts.size() >= MIN_CYCLE && comebacks >= MIN_CYCLE;  // --all için

            if (sinyal || (showAll && gosterile)) {
                adaylar.add(new Cycle(p, pts, expected, missing, complete, comebacks, sinyal));
            }
        }

        if (adaylar.isEmpty()) return null;

        // 3. Küçük P'nin katı olan (kapsanan) döngüleri ele.
        adaylar.sort(Comparator.comparingInt(c -> c.period));
        List<Cycle> secilen = new ArrayList<>();
        for (Cycle c : adaylar) {
            boolean kapsandi = false;
            for (Cycle q : secilen) {
                if (c.period % q.period == 0) { kapsandi = true; break; }
            }
            if (!kapsandi) secilen.add(c);
        }

        secilen.sort(Comparator
                .comparingInt((Cycle c) -> c.comebacks).reversed()
                .thenComparingInt(c -> c.period));

        StringBuilder out = new StringBuilder();
        for (Cycle c : secilen) {
            out.append(buildReport(teamName, teamId, weekNo, target, c));
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** yearsBack yıl önceki sezonda, weekIdx'teki maçın döngü kaydı; veri yoksa/indirilemediyse null. */
    private static CycleHit hitAt(CloseableHttpClient http, int teamId, int yearsBack,
                                  int weekIdx, Map<Integer, List<MacData>> cache) {
        int year = CURRENT_SEASON_START_YEAR - yearsBack;
        String season = year + "/" + (year + 1);

        List<MacData> matches;
        if (cache.containsKey(yearsBack)) {
            matches = cache.get(yearsBack);              // null olabilir (indirilemedi)
        } else {
            matches = fetchSeasonMatches(http, teamId, season);
            cache.put(yearsBack, matches);
        }
        if (matches == null || weekIdx >= matches.size()) return null;

        MacData m = matches.get(weekIdx);
        if (!m.played || m.htScore == null) return null;   // İY olmadan HT/FT hesaplanamaz

        String htFt = htFtCode(m.htScore, m.ftScore);
        return new CycleHit(season, m, htFt, comebackGrubu(htFt), toplamGol(m.ftScore));
    }

    // ─── RAPOR ──────────────────────────────────────────────────────────────
    private static String buildReport(String teamName, int teamId, int weekNo, MacData target, Cycle c) {

        StringBuilder sb = new StringBuilder();
        sb.append("╔════════════════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║ 🎯 HAFTA-DÖNGÜ SİNYALİ: %s  [ID:%d]%n", teamName, teamId));
        sb.append(String.format("║    Hedef: %d. hafta | Keşfedilen döngü: her %d yılda bir | Taranan: son %d yıl%n",
                weekNo, c.period, LOOKBACK_YEARS));
        sb.append("╠════════════════════════════════════════════════════════════════════╣\n");

        for (CycleHit a : c.hits) {
            String etiket = a.grup != null ? a.grup : "sürpriz yok";
            sb.append(String.format("║  %-9s (%d.h) %s %s %s  (İY %s) → %-4s | %d gol | %s%n",
                    a.season, weekNo, a.match.homeTeam, a.match.ftScore, a.match.awayTeam,
                    a.match.htScore, a.htFt, a.toplamGol, etiket));
        }

        sb.append("║  ──────────────────────────────────────────────────────────────────\n");

        String tahmin = ortakTahmin(c.hits);
        String durum = target.played
                ? "oynandı: " + target.ftScore + (target.htScore != null ? " (İY " + target.htScore + ")" : "")
                : (target.time != null ? target.time : "henüz oynanmadı");
        sb.append(String.format("║  %-9s (%d.h) %s vs %s  (%s)%n",
                CURRENT_SEASON, weekNo, target.homeTeam, target.awayTeam, durum));
        sb.append(String.format("║  ⁉️  TAHMİN: %s%n", tahmin));

        if (target.played && target.htScore != null) {
            String gHtFt = htFtCode(target.htScore, target.ftScore);
            String gGrup = comebackGrubu(gHtFt);
            int    gGol  = toplamGol(target.ftScore);
            boolean tuttu = gGrup != null && (tahmin.contains(gGrup) || tahmin.contains("comeback"));
            sb.append(String.format("║  GERÇEKLEŞEN: %s (%d gol)%s → %s%n",
                    gHtFt, gGol, gGrup != null ? " | " + gGrup : "",
                    tuttu ? "✅ tuttu" : "❌ tutmadı"));
        }

        String kalite = c.signal ? " → güçlü döngü (boşluksuz)"
                : (c.missing > 0 ? " (eksik: " + c.missing + " sezon veri yok)" : "");
        sb.append(String.format("║  Değerlendirme: %d/%d döngü maçı comeback%s%n",
                c.comebacks, c.hits.size(), kalite));
        sb.append("╚════════════════════════════════════════════════════════════════════╝");
        return sb.toString();
    }

    private static String ortakTahmin(List<CycleHit> hits) {
        String ortak = null;
        boolean hepsiCome = true;
        for (CycleHit a : hits) {
            if (a.grup == null) { hepsiCome = false; continue; }
            if (ortak == null) ortak = a.grup;
            else if (!ortak.equals(a.grup)) ortak = "MIXED";
        }
        if (!hepsiCome || ortak == null) return "belirsiz (geçmiş döngü maçları tutarsız)";
        if (FULL_COMEBACK.equals(ortak))       return FULL_COMEBACK + " (1/2 veya 2/1)";
        if (BERABERLIK_COMEBACK.equals(ortak)) return BERABERLIK_COMEBACK + " (1/X veya 2/X)";
        return "comeback (İY önde olan kazanamaz)";
    }

    // ─── HT/FT + GOL HESABI ─────────────────────────────────────────────────
    static String htFtCode(String htScore, String ftScore) {
        int[] ht = parseScore(htScore);
        int[] ft = parseScore(ftScore);
        if (ht == null || ft == null) return null;
        return "" + sonuc(ht) + "/" + sonuc(ft);
    }

    private static char sonuc(int[] s) {
        if (s[0] > s[1]) return '1';
        if (s[0] < s[1]) return '2';
        return 'X';
    }

    static String comebackGrubu(String htFt) {
        if (htFt == null) return null;
        switch (htFt) {
            case "1/2":
            case "2/1":
                return FULL_COMEBACK;
            case "1/X":
            case "2/X":
                return BERABERLIK_COMEBACK;
            default:
                return null;
        }
    }

    private static int toplamGol(String ftScore) {
        int[] ft = parseScore(ftScore);
        return ft == null ? 0 : ft[0] + ft[1];
    }

    private static int[] parseScore(String score) {
        if (score == null) return null;
        String[] parts = score.replaceAll("\\s*-\\s*", "-").split("-");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ─── DÖNGÜ MODELİ ───────────────────────────────────────────────────────
    private static class Cycle {
        final int period;
        final List<CycleHit> hits;
        final int expected;
        final int missing;
        final boolean complete;
        final int comebacks;
        final boolean signal;

        Cycle(int period, List<CycleHit> hits, int expected, int missing,
              boolean complete, int comebacks, boolean signal) {
            this.period    = period;
            this.hits      = hits;
            this.expected  = expected;
            this.missing   = missing;
            this.complete  = complete;
            this.comebacks = comebacks;
            this.signal    = signal;
        }
    }

    private static class CycleHit {
        final String season;
        final MacData match;
        final String htFt;
        final String grup;
        final int    toplamGol;

        CycleHit(String season, MacData match, String htFt, String grup, int toplamGol) {
            this.season    = season;
            this.match     = match;
            this.htFt      = htFt;
            this.grup      = grup;
            this.toplamGol = toplamGol;
        }
    }

    // ─── FİKSTÜR ÇEKME / PARSE ──────────────────────────────────────────────
    static class MacData {
        String homeTeam;
        String awayTeam;
        String ftScore;
        String htScore;
        String time;
        boolean played;
    }

    /**
     * @return maç listesi; sayfa alındı ama fikstür yoksa BOŞ liste;
     *         sayfa hiç indirilemediyse (timeout vb.) <b>null</b>.
     */
    private static List<MacData> fetchSeasonMatches(CloseableHttpClient http, int teamId, String season) {
        byte[] body = fetchBytes(http, String.format(BASE_URL, teamId, season));
        if (body == null) return null;

        Document doc;
        try {
            // charset'i HTML meta etiketinden tespit ettiriyoruz (Türkçe karakterler bozulmasın)
            doc = Jsoup.parse(new ByteArrayInputStream(body), null, "https://arsiv.mackolik.com/");
        } catch (IOException e) {
            return null;
        }

        List<MacData> result = new ArrayList<>();
        Element tbody = doc.selectFirst("#tblFixture > tbody");
        if (tbody == null) return result;

        // Sadece İLK turnuva bloğu (lig) — kupa/hazırlık maçları hafta dizilimini bozar
        List<Element> rows = new ArrayList<>();
        boolean inFirstLeague = false;
        for (Element row : tbody.select("tr")) {
            if (row.hasClass("competition")) {
                if (!inFirstLeague) {
                    inFirstLeague = true;
                    continue;
                }
                break;
            }
            if (inFirstLeague) rows.add(row);
        }
        if (rows.isEmpty()) {
            for (Element row : tbody.select("tr")) {
                String home = extractCell(row, "td:nth-child(3)");
                if (home != null && !home.isEmpty()) rows.add(row);
            }
        }

        for (Element row : rows) {
            String home = extractCell(row, "td:nth-child(3)");
            String away = extractCell(row, "td:nth-child(7)");
            if (home == null || home.isEmpty() || away == null || away.isEmpty()) continue;

            MacData md = new MacData();
            md.homeTeam = home;
            md.awayTeam = away;
            md.time     = extractCell(row, "td:nth-child(1)");

            Element scoreEl = row.selectFirst("td:nth-child(5) b a");
            String score = scoreEl != null ? scoreEl.text().trim() : "";
            int[] ft = parseScore(score);
            if (ft != null) {
                md.played  = true;
                md.ftScore = score.replaceAll("\\s*-\\s*", "-");
                String ht = extractCell(row, "td:nth-child(9)");
                if (ht != null && !ht.isEmpty() && parseScore(ht) != null) {
                    md.htScore = ht.replaceAll("\\s*-\\s*", "-");
                }
            }
            result.add(md);
        }
        return result;
    }

    /**
     * Timeout/geçici hatalarda üstel geri çekilmeyle yeniden dener.
     * Başarısızsa null döner — çağıran taraf bunu "veri yok" olarak ele alır.
     */
    private static byte[] fetchBytes(CloseableHttpClient http, String url) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throttle();
            HttpGet req = new HttpGet(url);
            req.addHeader("User-Agent", USER_AGENT);
            req.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            req.addHeader("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8");
            req.addHeader("Connection", "keep-alive");

            try (CloseableHttpResponse resp = http.execute(req)) {
                int code = resp.getStatusLine().getStatusCode();
                HttpEntity entity = resp.getEntity();

                if (code == 200) {
                    byte[] body = entity != null ? EntityUtils.toByteArray(entity) : new byte[0];
                    FETCH_OK.incrementAndGet();
                    return body;
                }

                // KRİTİK: 200 olmayan cevaplarda gövdeyi tüket, yoksa bağlantı havuza dönmez
                // ve sonraki istekler "Read timed out" / connection pool timeout alır.
                EntityUtils.consumeQuietly(entity);

                if (!RETRYABLE_STATUS.contains(code)) {
                    return null;                       // 404 vb. → tekrar denemenin anlamı yok
                }
            } catch (IOException e) {
                // SocketTimeoutException, ConnectTimeoutException, NoHttpResponseException,
                // SocketException("Connection reset") ... hepsi buraya düşer.
                req.abort();
            } catch (RuntimeException e) {
                req.abort();
                return null;
            }

            if (attempt < MAX_ATTEMPTS) {
                FETCH_RETRY.incrementAndGet();
                sleepBackoff(attempt);
            }
        }
        FETCH_FAILED.incrementAndGet();
        return null;
    }

    /** Üstel geri çekilme + jitter (sunucuyu daha da boğmamak için). */
    private static void sleepBackoff(int attempt) {
        long delay = Math.min(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS * (1L << (attempt - 1)));
        delay += ThreadLocalRandom.current().nextLong(200, 600);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── GLOBAL THROTTLE ────────────────────────────────────────────────────
    private static final Object RATE_LOCK = new Object();
    private static long nextAllowedRequestAt = 0L;

    private static void throttle() {
        if (minRequestGapMs <= 0) return;
        long waitMs;
        synchronized (RATE_LOCK) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextAllowedRequestAt);
            waitMs = slot - now;
            nextAllowedRequestAt = slot + minRequestGapMs;
        }
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String extractCell(Element row, String cssSelector) {
        Element el = row.selectFirst(cssSelector);
        return el != null ? el.text().trim() : null;
    }

    /** Fikstür satırlarında en sık geçen isim bizim takımdır. */
    private static String detectTeamNameFromRows(List<MacData> matches) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (MacData m : matches) {
            freq.merge(m.homeTeam, 1, Integer::sum);
            freq.merge(m.awayTeam, 1, Integer::sum);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best != null && bestCount >= Math.max(1, matches.size() / 2)) return best;
        return null;
    }
}