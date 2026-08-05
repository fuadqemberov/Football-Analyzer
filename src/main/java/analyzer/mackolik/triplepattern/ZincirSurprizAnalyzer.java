package analyzer.mackolik.triplepattern;

import analyzer.mackolik.triplepattern.LeagueSeasonFetcher.LeagueRef;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "SKOR ZİNCİRİ" (score-echo chain) sürpriz dedektoru — Örebro / Oddevold nümunəsi.
 *
 * <p>Üç komanda ilə işləyir:
 * <ul>
 *   <li><b>A</b> — proqonist (məs. Örebro), bugün oyunu olan komanda</li>
 *   <li><b>B</b> — A-nın son oynadığı rəqib (məs. Oddevold), "körpü" komanda</li>
 *   <li><b>C</b> — A-nın bugünkü rəqibi (məs. Norrby / Örgryte)</li>
 * </ul>
 *
 * <p>Zəncirin şərtləri (hamısı EYNİ liqada, çünki A–B, B–C və bugünkü A–C
 * hamısı həmin liqanın oyunlarıdır — ona görə bir sezon endirilməsi kifayətdir):
 * <ol>
 *   <li>A hansısa B ilə oyun oynayıb (ANKER) — B həmin oyunda hansı nəticəni çıxarıb
 *       (nə vurub / nə yeyib) yadda saxlanılır. Skor SABİT deyil, maçın webdəki
 *       nəticəsindən gəlir; bərabərlik olması <b>şərt deyil</b> (0-0, 2-1, 3-0 …)</li>
 *   <li>Ankerdən sonra A ən azı bir <b>sürpriz</b> çıxarıb — İY/MS dönüşü
 *       (2/1, 1/2, 1/X, 2/X: ilk yarı öndə gedən qalib gəlmir)</li>
 *   <li>B, ankerdən sonra <b>C-yə qarşı EYNİ nəticəni</b> təkrar edib (körpü ankeri təkrar edir)</li>
 *   <li>A-nın növbəti (bugünkü) oyunu C ilədir</li>
 * </ol>
 * (Örebro nümunəsində təkrarlanan nəticə 0-0-dır — ancaq bu, məlumatdan gələn bir haldır,
 * kodda 0-0 və ya başqa heç bir dəqiq skor yazılmayıb.)
 * Nəticə → TƏXMİN: A bugün C-yə qarşı sürpriz (İY/MS 2/1 · 1/2 · 1/X · 2/X).
 *
 * <p>Bu sinif {@link LeagueSeasonFetcher} (komanda ID-li, İY + MS skorlu, keşlənən
 * sezon feed-i) və {@link LeagueWidePatternMatcher#computeHtFtFull} İY/MS məntiqindən
 * istifadə etdiyi üçün {@code triplepattern} paketində yerləşdirilib; mövcud fayllara
 * heç bir dəyişiklik lazım deyil.
 *
 * <p>İşlətmə:
 * <pre>
 *   java ... ZincirSurprizAnalyzer                 → bugünün başlamamış oyunları
 *   java ... ZincirSurprizAnalyzer 12345:67890 ...  → yalnız verilən evId:deplasmanId cütləri
 * </pre>
 */
public class ZincirSurprizAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ZincirSurprizAnalyzer.class);

    /** Sürpriz sayılan İY/MS kodları — kodun qalan hissəsi ilə eyni. */
    private static final List<String> TARGET_HT_FT = LeagueWidePatternMatcher.TARGET_HT_FT;

    /** Cari mövsümün komanda səhifəsi hələ yoxdursa bir neçə il geri baxılır. */
    private static final int MAX_ANCHOR_FALLBACK = 4;
    private static final int NUM_THREADS = 10;

    // ═══════════════════════════════════════════════════════════════════════
    //  Bugünkü fikstür (livedata) — cütlər (evId, evAd, deplasmanId, deplasmanAd)
    // ═══════════════════════════════════════════════════════════════════════

    /** Bir oyun cütü: kim kiminlə oynayır. */
    static final class Fixture {
        final int homeId;
        final String homeName;
        final int awayId;
        final String awayName;

        Fixture(int homeId, String homeName, int awayId, String awayName) {
            this.homeId = homeId;
            this.homeName = homeName;
            this.awayId = awayId;
            this.awayName = awayName;
        }

        @Override
        public String toString() {
            return homeName + " (" + homeId + ") vs " + awayName + " (" + awayId + ")";
        }
    }

    private static final String LIVEDATA_URL = "https://vd.mackolik.com/livedata?group=0";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * Feed sətri: [matchId, evId, "evAd", deplasmanId, "deplasmanAd", status, "statusMətn", ...].
     * Başlamamış oyun: status = 0, statusMətn = "" (TeamIdsFetcher-dəki qəlibin cütlü variantı).
     */
    private static final Pattern UNSTARTED_FIXTURE =
            Pattern.compile("\\[\\d+,(\\d+),\"([^\"]*)\",(\\d+),\"([^\"]*)\",0,\"\",");

    static List<Fixture> fetchTodayFixtures(CloseableHttpClient http) {
        List<Fixture> fixtures = new ArrayList<>();
        try {
            String body = fetchText(http, LIVEDATA_URL);
            if (body == null) return fixtures;

            int mIndex = body.indexOf("\"m\":[[");
            String scope = mIndex >= 0 ? body.substring(mIndex) : body;

            Matcher m = UNSTARTED_FIXTURE.matcher(scope);
            while (m.find()) {
                int homeId = Integer.parseInt(m.group(1));
                int awayId = Integer.parseInt(m.group(3));
                fixtures.add(new Fixture(homeId, m.group(2), awayId, m.group(4)));
            }
            log.info("Bugün başlamamış {} oyun tapıldı.", fixtures.size());
        } catch (IOException e) {
            log.error("livedata alınmadı: {}", e.getMessage());
        }
        return fixtures;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Zəncir siqnalı modeli
    // ═══════════════════════════════════════════════════════════════════════

    /** Bir A-vs-C oyunu üçün tam zəncir tapıldıqda doldurulur. */
    static final class ChainSignal {
        final String aName, bName, cName;
        final LeagueMatch anchor;          // A–B (anker oyunu)
        final List<LeagueMatch> surprises; // A-nın ankerdən sonrakı sürprizləri
        final List<String> surpriseCodes;  // yuxarıdakılarla eyni sıra ilə İY/MS
        final LeagueMatch bridge;          // B <linkText> C
        final boolean lastWasSurprise;     // A-nın SON oynadığı oyun da sürpriz idimi
        final String linkText;             // təkrarlanan skor, B gözü ilə, məs. "0-0" / "2-1" (webdən)

        ChainSignal(String aName, String bName, String cName,
                    LeagueMatch anchor, List<LeagueMatch> surprises,
                    List<String> surpriseCodes, LeagueMatch bridge, boolean lastWasSurprise,
                    String linkText) {
            this.aName = aName;
            this.bName = bName;
            this.cName = cName;
            this.anchor = anchor;
            this.surprises = surprises;
            this.surpriseCodes = surpriseCodes;
            this.bridge = bridge;
            this.lastWasSurprise = lastWasSurprise;
            this.linkText = linkText;
        }

        String render(String leagueName) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n★★★ ZİNCİR TAPILDI ★★★  ").append(leagueName).append('\n');
            sb.append("  A (proqonist) : ").append(aName).append('\n');
            sb.append("  B (körpü)     : ").append(bName).append('\n');
            sb.append("  C (bugün)     : ").append(cName).append('\n');
            sb.append("  Təkrarlanan skor (B gözü): ").append(linkText).append('\n');
            sb.append("  ────────────────────────────────────────────\n");
            sb.append("  1) ANKER  A–B : ").append(anchor.displayLine()).append('\n');
            sb.append("  2) A sürprizi : ");
            for (int i = 0; i < surprises.size(); i++) {
                sb.append(i == 0 ? "" : "                ")
                        .append(surprises.get(i).displayLine())
                        .append("  → İY/MS ").append(surpriseCodes.get(i)).append(" ★\n");
            }
            sb.append("  3) KÖRPÜ  B–C : ").append(bridge.displayLine()).append('\n');
            sb.append("  ────────────────────────────────────────────\n");
            sb.append("  >>> TƏXMİN: ").append(aName).append(" bugün ").append(cName)
                    .append(" ilə SÜRPRIZ (İY/MS 2/1 · 1/2 · 1/X · 2/X)\n");
            if (lastWasSurprise) {
                sb.append("  >>> GÜCLÜ SİQNAL: A-nın SON oyunu da sürpriz idi (2026 Örebro ssenarisi) 🔥\n");
            }
            return sb.toString();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Zəncir dedektoru (şəbəkəsiz — əldə qurulmuş LeagueMatch siyahısı ilə də sınana bilər)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * {@code protagonistId} (A) və bugünkü rəqibi {@code targetOpponentId} (C) üçün
     * zəncir şərtlərini yoxlayır. A-nın ən son oyunundan geriyə doğru anker axtarır
     * və ilk keçərli zənciri qaytarır; heç bir zəncir yoxdursa {@code null}.
     */
    static ChainSignal detect(List<LeagueMatch> season,
                              int protagonistId, String protagonistName,
                              int targetOpponentId, String targetOpponentName) {

        List<LeagueMatch> aTimeline = LeagueWidePattern.timelineOf(season, protagonistId);
        if (aTimeline.size() < 2) return null;

        int lastIdx = aTimeline.size() - 1;

        // A-nın ən son oyunundan geriyə: hər oyun bir anker namizədidir.
        for (int ai = lastIdx; ai >= 0; ai--) {
            LeagueMatch anchor = aTimeline.get(ai);

            int bId = anchor.opponentId(protagonistId);
            if (bId <= 0 || bId == targetOpponentId) continue;   // B, C-dən fərqli olmalıdır

            // Bağlayıcı skor: körpü komanda B-nin ANKERDƏKİ nəticəsi (nə vurub / nə yeyib).
            // Heç bir dəqiq hesab yazılmır — skor tamamilə maçın webdəki nəticəsindən gəlir və
            // bərabərlik olmaq MƏCBURİ DEYİL (0-0, 2-1, 3-0 … fərq etməz).
            int bScored   = anchor.goalsFor(bId);
            int bConceded = anchor.goalsAgainst(bId);

            // (2) A ankerdən SONRA ən azı bir sürpriz (İY/MS dönüşü) çıxarıb
            List<LeagueMatch> surprises = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (int j = ai + 1; j <= lastIdx; j++) {
                String code = homeAwayCode(aTimeline.get(j), protagonistId);
                if (code != null && TARGET_HT_FT.contains(code)) {
                    surprises.add(aTimeline.get(j));
                    codes.add(code);
                }
            }
            if (surprises.isEmpty()) continue;

            // (3) B, ankerdən sonra C-yə qarşı EYNİ nəticəni təkrar edib (bərabərlik şərti yoxdur)
            LeagueMatch bridge = findEcho(season, bId, targetOpponentId, bScored, bConceded, anchor.date);
            if (bridge == null) continue;

            boolean lastWasSurprise = surprises.get(surprises.size() - 1) == aTimeline.get(lastIdx);
            String linkText = bScored + "-" + bConceded;   // B gözü ilə təkrarlanan skor
            return new ChainSignal(protagonistName,
                    anchor.opponentName(protagonistId), targetOpponentName,
                    anchor, surprises, codes, bridge, lastWasSurprise, linkText);
        }
        return null;
    }

    /**
     * İY/MS kodunu proqonistin ev/deplasman mövqeyinə görə çevirir, ki sürpriz
     * (öndə gedib qalib gəlməmək) hər iki tərəf üçün eyni cür oxunsun.
     * {@link LeagueWidePatternMatcher#computeHtFtFull} ev/deplasman baxımlıdır;
     * proqonist deplasmandadırsa 1↔2 yerlərini dəyişirik.
     */
    private static String homeAwayCode(LeagueMatch match, int teamId) {
        String code = LeagueWidePatternMatcher.computeHtFtFull(match);
        if (code == null) return null;
        if (match.isHome(teamId)) return code;
        return flip(code);
    }

    private static String flip(String code) {
        String[] parts = code.split("/");
        return flipToken(parts[0]) + "/" + flipToken(parts[1]);
    }

    private static String flipToken(String t) {
        return switch (t) {
            case "1" -> "2";
            case "2" -> "1";
            default -> t;   // "X" olduğu kimi qalır
        };
    }

    /**
     * {@code teamId} (körpü B) komandasının {@code opponentId} (C) ilə DƏQİQ
     * {@code scored}-{@code conceded} nəticəsi ilə bitən oyunu — skor ankerdən gəlir,
     * kodda sabit deyil, bərabərlik olması şərt deyil.
     * {@code notBefore} verilibsə həmin tarixdən əvvəlki oyunlar sayılmır.
     */
    private static LeagueMatch findEcho(List<LeagueMatch> season, int teamId,
                                        int opponentId, int scored, int conceded, LocalDate notBefore) {
        for (LeagueMatch match : LeagueWidePattern.timelineOf(season, teamId)) {
            if (match.opponentId(teamId) != opponentId) continue;
            if (match.goalsFor(teamId) != scored || match.goalsAgainst(teamId) != conceded) continue;
            if (notBefore != null && match.date != null && match.date.isBefore(notBefore)) continue;
            return match;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Bir oyun cütü üçün tapşırıq
    // ═══════════════════════════════════════════════════════════════════════

    private static final class FixtureTask implements Callable<String> {

        private final Fixture fixture;
        private final CloseableHttpClient http;

        FixtureTask(Fixture fixture, CloseableHttpClient http) {
            this.fixture = fixture;
            this.http = http;
        }

        @Override
        public String call() {
            try {
                return analyze();
            } catch (Exception e) {
                log.error("Oyun {} uğursuz: {}", fixture, e.getMessage(), e);
                return null;
            }
        }

        private String analyze() throws IOException {
            LeagueRef league = resolveLeagueWithFallback(fixture.homeId);
            if (league == null) league = resolveLeagueWithFallback(fixture.awayId);
            if (league == null) {
                log.debug("Oyun {}: liqa təyin edilmədi", fixture);
                return null;
            }

            Map<String, Integer> seasonIndex = LeagueSeasonFetcher.fetchSeasonIndex(http, league.seasonId);
            if (seasonIndex.isEmpty()) return null;

            String currentLabel = newestLabel(seasonIndex);
            if (currentLabel == null) return null;

            List<LeagueMatch> season = LeagueSeasonFetcher.fetchLeagueSeason(
                    http, seasonIndex.get(currentLabel), currentLabel);
            if (season.isEmpty()) return null;

            // Hər iki istiqamət: ev sahibi proqonist ola bilər, deplasman da.
            ChainSignal fromHome = detect(season,
                    fixture.homeId, fixture.homeName, fixture.awayId, fixture.awayName);
            ChainSignal fromAway = detect(season,
                    fixture.awayId, fixture.awayName, fixture.homeId, fixture.homeName);

            StringBuilder sb = new StringBuilder();
            if (fromHome != null) sb.append(fromHome.render(league.leagueName + " " + currentLabel));
            if (fromAway != null) sb.append(fromAway.render(league.leagueName + " " + currentLabel));
            return sb.length() == 0 ? null : sb.toString();
        }

        private LeagueRef resolveLeagueWithFallback(int teamId) throws IOException {
            int startYear = LocalDate.now().getYear();
            for (int back = 0; back < MAX_ANCHOR_FALLBACK; back++) {
                String label = (startYear - back) + "/" + (startYear - back + 1);
                LeagueRef ref = LeagueSeasonFetcher.resolveLeague(http, teamId, label);
                if (ref != null) return ref;
            }
            return null;
        }
    }

    /** İndeksdəki ən yeni "YYYY/YYYY" etiketi (cari mövsüm). */
    private static String newestLabel(Map<String, Integer> seasonIndex) {
        return seasonIndex.keySet().stream()
                .max(Comparator.comparingInt(ZincirSurprizAnalyzer::startYear))
                .orElse(null);
    }

    private static int startYear(String label) {
        try {
            return Integer.parseInt(label.split("/")[0].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HTTP
    // ═══════════════════════════════════════════════════════════════════════

    private static String fetchText(CloseableHttpClient http, String url) throws IOException {
        HttpGet request = new HttpGet(url);
        request.addHeader("User-Agent", USER_AGENT);
        request.setConfig(RequestConfig.custom()
                .setConnectTimeout(10000)
                .setConnectionRequestTimeout(15000)
                .setSocketTimeout(20000)
                .build());
        try (CloseableHttpResponse response = http.execute(request)) {
            if (response.getStatusLine().getStatusCode() != 200) return null;
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  main
    // ═══════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(NUM_THREADS + 5);
        cm.setDefaultMaxPerRoute(NUM_THREADS);
        CloseableHttpClient http = HttpClients.custom().setConnectionManager(cm).build();

        List<Fixture> fixtures = parseFixtureArgs(args);
        boolean manual = !fixtures.isEmpty();
        if (fixtures.isEmpty()) fixtures = fetchTodayFixtures(http);

        // SLF4J konfiqindən asılı olmayaraq görünsün deyə xülasə birbaşa stdout-a.
        System.out.println("════════ SKOR ZİNCİRİ — SÜRPRİZ DEDEKTORU ════════");
        System.out.println(manual
                ? "Mənbə: əl ilə verilən " + fixtures.size() + " oyun."
                : "Mənbə: bugün başlamamış " + fixtures.size() + " oyun (livedata).");

        if (fixtures.isEmpty()) {
            System.out.println("Analiz ediləcək oyun yoxdur.");
            System.out.println("Səbəb: bu an başlamamış maç yoxdur (maçlar başlayıb/bitib ya da fasilə).");
            System.out.println("Sınamaq üçün əl ilə oyun ver:  ZincirSurprizAnalyzer evId:deplasmanId");
            closeQuietly(http);
            System.exit(0);
        }

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<String>> futures = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            futures.add(executor.submit(new FixtureTask(fixture, http)));
        }
        System.out.println(futures.size() + " oyun analiz edilir...\n");

        int found = 0;
        for (Future<String> future : futures) {
            try {
                String result = future.get();
                if (result != null && !result.isEmpty()) {
                    System.out.println(result);
                    System.out.println("══════════════════════════════════════════════");
                    found++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.error("Tapşırıq xətası: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
        }
        System.out.println("\n════════ NƏTİCƏ ════════");
        System.out.println("Analiz edilən oyun          : " + futures.size());
        System.out.println("Zəncir siqnalı tapılan oyun : " + found);
        if (found == 0) {
            System.out.println("→ Bugün bu qəlibə uyğun oyun YOXDUR. (Qəlib nadirdir — boş nəticə normaldır.)");
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(120, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        closeQuietly(http);
        System.exit(0);
    }

    /** "evId:deplasmanId" cütlərini oxuyur (test / əl ilə seçim üçün). */
    private static List<Fixture> parseFixtureArgs(String[] args) {
        List<Fixture> fixtures = new ArrayList<>();
        if (args == null) return fixtures;
        for (String arg : args) {
            String[] parts = arg.split("[:\\-]");
            if (parts.length != 2) {
                log.warn("Keçərsiz cüt (evId:deplasmanId gözlənilir): {}", arg);
                continue;
            }
            try {
                int homeId = Integer.parseInt(parts[0].trim());
                int awayId = Integer.parseInt(parts[1].trim());
                fixtures.add(new Fixture(homeId, "Ev#" + homeId, awayId, "Dep#" + awayId));
            } catch (NumberFormatException e) {
                log.warn("Keçərsiz ID cütü: {}", arg);
            }
        }
        return fixtures;
    }

    private static void closeQuietly(CloseableHttpClient http) {
        try {
            http.close();
        } catch (IOException e) {
            log.error("HttpClient bağlanmadı: {}", e.getMessage());
        }
    }
}
