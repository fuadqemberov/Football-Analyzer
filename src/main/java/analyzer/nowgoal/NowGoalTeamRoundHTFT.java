package analyzer.nowgoal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * NowGoalTeamRoundHTFT
 * ────────────────────────────────────────────────────────────────────────────
 * ŞƏRT: YALNIZ BUGÜNKÜ matçlar analiz olunur (live8-dən çəkilir).
 *
 * Hər bugünkü "A vs B" matçı üçün:
 *   1) Cari sezonu tapır və o matçın oynanacağı HƏFTƏ nömrəsini (round X) müəyyən edir.
 *   2) 2010/2011 sezonuna qədər GERİYƏ gedir.
 *   3) Hər sezonda A takımının EYNİ X həftəsindəki matçını tapır.
 *   4) YALNIZ bu HT/FT nümunələrini (A takımı perspektivindən) çap edir:
 *          2/1   1/2   1/X   2/X
 *
 *   Perspektiv A takımıdır:  1 = A öndə/qalib,  2 = A geridə/məğlub,  X = bərabərə.
 *      2/1 = A HT-də geridə idi → FT-də qalib gəldi  (comeback qələbə)
 *      1/2 = A HT-də öndə idi   → FT-də məğlub oldu   (çöküş)
 *      1/X = A HT-də öndə idi   → FT-də bərabərə
 *      2/X = A HT-də geridə idi → FT-də bərabərə
 *
 * Mövcud NowGoalHTFT klasına TOXUNULMUR — data yükləmə məntiqi oradan ilhamlanıb.
 * ────────────────────────────────────────────────────────────────────────────
 */
public class NowGoalTeamRoundHTFT {

    // ── Parametrlər ──────────────────────────────────────────
    static final int    MIN_SEASON_YEAR = 2010;   // 2010/2011 sezonuna qədər geri get
    static final int    THREADS         = 20;      // bugünkü matçların paralel analizi
    static final String BASE            = "https://football.nowgoal26.com";
    static final String LIVE_BASE       = "https://live10.nowgoal26.com/";

    // Yalnız bu HT/FT nümunələri çap olunur (A takımı perspektivindən)
    static final Set<String> WANTED = Set.of("2/1", "1/2", "1/X", "2/X");

    // ── TARİX PARAMETRLƏRİ ───────────────────────────────────
    /** NowGoal JSON-undakı vaxtlar UTC+8 (Çin) zonasındadır — yoxlanılıb. */
    static final ZoneId SITE_ZONE  = ZoneId.of("GMT+8");
    static final ZoneId LOCAL_ZONE = ZoneId.systemDefault();
    /** 0 = yalnız bugünkü oyunlar; 1 = bugün + sabah, və s. */
    static final int    DAY_WINDOW = 0;
    /** "Futbol günü" 06:00-da başlayır — gecə yarısından sonrakı oyunlar əvvəlki günə aiddir. */
    static final int    DAY_CUTOFF_HOUR = 6;
    /**
     * true → live səhifədəki matçın həftəsi cari sezon JSON-unda TARİXƏ görə
     * təsdiqlənməsə, o matç analiz olunmur (yanlış həftə ilə nəticə verməsin).
     */
    static final boolean STRICT_TODAY = true;
    static final DateTimeFormatter SITE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    // ─────────────────────────────────────────────────────────

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    static final ObjectMapper JSON = new ObjectMapper();

    // ── ANA METOD ─────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        LogManager.getLogManager().reset();
        Logger.getLogger("org.openqa.selenium").setLevel(Level.OFF);
        System.setProperty("webdriver.chrome.silentOutput", "true");

        // ŞƏRT: yalnız BUGÜNKÜ matçlar (live8-dən)
        System.out.println(">>> Bugünkü tarix: " + today() + " (zona: " + LOCAL_ZONE + ")");
        System.out.println(">>> Bugünkü matçlar live8-dən çəkilir...");
        List<Fixture> fixtures = fetchTodayFixtures();
        System.out.println(">>> Bugün üçün tapılan matç sayı: " + fixtures.size() + "\n");
        if (fixtures.isEmpty()) return;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> fs = new ArrayList<>();
        for (Fixture fx : fixtures) {
            fs.add(pool.submit(() -> {
                try { analyzeFixture(fx); }
                catch (Exception e) {
                    System.err.println("[XƏTA] " + fx + " : " + e.getMessage());
                }
            }));
        }
        for (Future<?> f : fs) { try { f.get(); } catch (Exception ignored) {} }
        pool.shutdown();
        System.out.println("\n>>> Bütün bugünkü matçların analizi tamamlandı.");
    }

    // ── BİR BUGÜNKÜ MATÇI ANALİZ ET ───────────────────────────
    static void analyzeFixture(Fixture fx) throws Exception {
        int ligId = fx.leagueId;

        List<String> seasons = loadSeasons(ligId);
        if (seasons.isEmpty()) return;

        String curSeasonKey = seasons.get(0);
        LeagueData cur = loadLeague(ligId, curSeasonKey);
        if (cur == null) return;

        // A (ev) və B (səfər) takımlarının ID-lərini cari sezon JSON-undan tap
        int idA = fx.homeId > 0 && cur.teamNames.containsKey(fx.homeId) ? fx.homeId : cur.findTeamId(fx.homeName);
        int idB = fx.awayId > 0 && cur.teamNames.containsKey(fx.awayId) ? fx.awayId : cur.findTeamId(fx.awayName);
        if (idA < 0 || idB < 0) return;

        // Cari sezonda A vs B matçının HƏFTƏSİNİ (round X) tap.
        // ARDICILLIQ: 1) bugünkü tarixlə üst-üstə düşən matç → 2) oynanmamış matç → 3) aktiv həftə
        LocalDate today = today();
        int round = findRoundOfToday(cur, idA, idB, today);
        LocalDateTime kickoff = null;
        if (round > 0) {
            Match m = findPairMatch(cur, round, idA, idB);
            if (m != null) kickoff = m.kickoff;
        } else {
            if (STRICT_TODAY) {
                // Bu cütün cari sezon cədvəlində bugünkü tarixi yoxdur →
                // həftə nömrəsinə güvənmək olmaz, oyunu ötür.
                return;
            }
            round = findRoundOf(cur, idA, idB);
            if (round < 0) round = detectCurrentActiveRound(ligId, cur);
        }
        if (round < 1) return;

        // Keçmiş sezonları gəz (cari daxil), 2010/2011-ə qədər — A takımı üçün
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        for (String season : seasons) {
            if (seasonStartYear(season) < MIN_SEASON_YEAR) break;

            LeagueData ld = season.equals(curSeasonKey) ? cur : loadLeague(ligId, season);
            if (ld == null) continue;

            Match m = ld.findTeamMatchInRound(round, idA);
            if (m == null || m.ft == null || m.ht == null) continue;
            // TARİX YOXLAMASI: nəticə yalnız artıq oynanmış matçdan götürülə bilər
            if (m.date() != null && !m.date().isBefore(today)) continue;

            String htft = htftForTeam(m, idA);
            if (htft == null || !WANTED.contains(htft)) continue;

            hits++;
            boolean aHome = (m.homeId == idA);
            String opp = ld.teamName(aHome ? m.awayId : m.homeId);
            String venue = aHome ? "(ev)" : "(səfər)";
            sb.append(String.format("     [%-9s]  R%-2d  HT/FT: %-3s  |  %s %s vs %s   HT(%s) FT(%s)%n",
                    season, round, htft, ld.teamName(idA), venue, opp, m.ht, m.ft));
        }

        if (hits == 0) return;   // Bu nümunələrdən heç biri yoxdursa çap etmə

        synchronized (System.out) {
            System.out.println("==================================================================");
            System.out.println("[LİQA]: " + cur.leagueName + "   |   HƏFTƏ: " + round);
            System.out.println("[BUGÜNKÜ MATÇ]: " + cur.teamName(idA) + "  vs  " + cur.teamName(idB)
                    + "   |   [TARİX]: " + fmt(kickoff) + "  (bugün: " + today + ")");
            System.out.println("[A takımı = " + cur.teamName(idA) + "] " + round
                    + "-ci həftədə keçmiş HT/FT (2/1, 1/2, 1/X, 2/X):");
            System.out.print(sb);
            System.out.println("     >>> Uyğun nəticə: " + hits);
            System.out.println("==================================================================");
        }
    }

    // ── LIVE8-DƏN BUGÜNKÜ MATÇLARI ÇƏK (Selenium) ─────────────
    static List<Fixture> fetchTodayFixtures() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments("--headless=new", "--disable-gpu", "--no-sandbox",
                "--disable-dev-shm-usage", "--blink-settings=imagesEnabled=false",
                "--log-level=3", "--silent");
        WebDriver driver = new ChromeDriver(opts);

        Map<String, Fixture> uniq = new LinkedHashMap<>();
        Pattern idInHref = Pattern.compile("/(?:team|analysis|h2h)[^0-9]*?(\\d+)");
        try {
            driver.get(LIVE_BASE + "/");
            Thread.sleep(6000);

            // Hər matç sətri: id="tr1_<matchId>", sclassid = liqa ID
            List<WebElement> rows = driver.findElements(By.cssSelector("tr[id^='tr1_'][sclassid]"));
            for (WebElement row : rows) {
                try {
                    String sclass = row.getAttribute("sclassid");
                    if (sclass == null || sclass.isBlank()) continue;
                    int leagueId = Integer.parseInt(sclass.trim());

                    // Sətir içindəki takım linkləri (ev sonra səfər ardıcıllığı)
                    List<WebElement> teamLinks = row.findElements(
                            By.cssSelector("a[href*='/team/'], a[onclick*='team'], .homeTeam a, .guestTeam a"));
                    if (teamLinks.size() < 2) continue;

                    WebElement homeEl = teamLinks.get(0);
                    WebElement awayEl = teamLinks.get(teamLinks.size() - 1);

                    String homeName = safeText(homeEl);
                    String awayName = safeText(awayEl);
                    if (homeName.isBlank() || awayName.isBlank()) continue;

                    int homeId = extractId(idInHref, homeEl.getAttribute("href"));
                    int awayId = extractId(idInHref, awayEl.getAttribute("href"));

                    String matchId = row.getAttribute("id");
                    uniq.putIfAbsent(matchId,
                            new Fixture(leagueId, homeName, awayName, homeId, awayId));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.out.println("Selenium xetasi: " + e.getMessage());
        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
        }
        return new ArrayList<>(uniq.values());
    }

    static String safeText(WebElement el) {
        try {
            String t = el.getText();
            if (t == null || t.isBlank()) t = el.getAttribute("title");
            return t == null ? "" : t.trim();
        } catch (Exception e) { return ""; }
    }

    static int extractId(Pattern p, String href) {
        if (href == null) return -1;
        Matcher m = p.matcher(href);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {} }
        return -1;
    }

    // ── A takımı perspektivindən HT/FT ────────────────────────
    //    1 = A öndə/qalib, 2 = A geridə/məğlub, X = bərabərə
    static String htftForTeam(Match m, int teamId) {
        try {
            String[] f = m.ft.split("-"), h = m.ht.split("-");
            int ftHome = Integer.parseInt(f[0].trim()), ftAway = Integer.parseInt(f[1].trim());
            int htHome = Integer.parseInt(h[0].trim()), htAway = Integer.parseInt(h[1].trim());

            boolean aHome = (m.homeId == teamId);
            int htA = aHome ? htHome : htAway;
            int htO = aHome ? htAway : htHome;
            int ftA = aHome ? ftHome : ftAway;
            int ftO = aHome ? ftAway : ftHome;

            return sign(htA, htO) + "/" + sign(ftA, ftO);
        } catch (Exception e) {
            return null;
        }
    }

    static String sign(int a, int o) {
        if (a > o) return "1";
        if (a < o) return "2";
        return "X";
    }

    // ── TARİX YARDIMÇILARI ───────────────────────────────────
    /**
     * Vaxtın aid olduğu "futbol günü": DAY_CUTOFF_HOUR-dan (06:00) əvvəlki oyunlar
     * əvvəlki günə yazılır. Məs. yerli vaxtla 27.07 01:30-dakı oyun 26 iyulun oyunudur.
     */
    static LocalDate footballDay(LocalDateTime t) {
        return t == null ? null : t.minusHours(DAY_CUTOFF_HOUR).toLocalDate();
    }

    /** Bugünkü futbol günü — hər dəfə yenidən hesablanır (gecə yarısını keçən işləmələr üçün). */
    static LocalDate today() {
        return footballDay(LocalDateTime.now(LOCAL_ZONE));
    }

    /** JSON-dakı "2025-08-16 03:00" (UTC+8) → yerli zonaya çevrilmiş vaxt. */
    static LocalDateTime parseSiteTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace('/', '-').replace('T', ' ');
        if (s.length() < 10) return null;
        if (s.length() == 10) s = s + " 00:00";
        if (s.length() > 16)  s = s.substring(0, 16);
        try {
            return ZonedDateTime.of(LocalDateTime.parse(s, SITE_FMT), SITE_ZONE)
                    .withZoneSameInstant(LOCAL_ZONE)
                    .toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    /** Matç verilən günə (və ya DAY_WINDOW pəncərəsinə) düşürmü? Tarixi yoxdursa — YOX. */
    static boolean isOnDay(Match m, LocalDate day) {
        LocalDate d = m.date();
        if (d == null) return false;
        return !d.isBefore(day) && !d.isAfter(day.plusDays(DAY_WINDOW));
    }

    static String fmt(LocalDateTime t) {
        return t == null ? "tarix yoxdur" : t.format(SITE_FMT);
    }

    // ── A vs B cütünün BUGÜNKÜ tarixli matçının həftəsi ──────
    //    Cüt sezonda 2 dəfə qarşılaşır (ev/səfər) — yanlış ayağı seçməmək
    //    üçün həftəni tarixlə təsdiqləyirik.
    static int findRoundOfToday(LeagueData ld, int a, int b, LocalDate day) {
        for (Map.Entry<Integer, List<Match>> e : ld.rounds.entrySet()) {
            for (Match m : e.getValue()) {
                if (m.postponed) continue;
                if (!isPair(m, a, b)) continue;
                if (isOnDay(m, day)) return e.getKey();
            }
        }
        return -1;
    }

    static boolean isPair(Match m, int a, int b) {
        return (m.homeId == a && m.awayId == b) || (m.homeId == b && m.awayId == a);
    }

    static Match findPairMatch(LeagueData ld, int round, int a, int b) {
        List<Match> ms = ld.rounds.get(round);
        if (ms == null) return null;
        for (Match m : ms) if (isPair(m, a, b)) return m;
        return null;
    }

    // ── Cari sezonda A vs B-nin oynanacağı həftəni tap ────────
    static int findRoundOf(LeagueData ld, int a, int b) {
        int best = -1;
        for (Map.Entry<Integer, List<Match>> e : ld.rounds.entrySet()) {
            for (Match m : e.getValue()) {
                if ((m.homeId == a && m.awayId == b) || (m.homeId == b && m.awayId == a)) {
                    // Oynanmamış matça üstünlük ver (gələcək matç), yoxsa istənilən birini götür
                    if (m.ft == null) return e.getKey();
                    best = e.getKey();
                }
            }
        }
        return best;
    }

    // ── Cari aktiv həftə: 1) TARİX → 2) HTML class="current" → 3) saymaq ──
    static int detectCurrentActiveRound(int ligId, LeagueData ld) {
        // Bugünkü tarixli matçların yerləşdiyi həftə ən etibarlı mənbədir
        LocalDate day = today();
        int bestRound = -1, bestCount = 0;
        for (Map.Entry<Integer, List<Match>> e : ld.rounds.entrySet()) {
            int cnt = 0;
            for (Match m : e.getValue()) {
                if (m.postponed) continue;
                if (isOnDay(m, day)) cnt++;
            }
            if (cnt == 0) continue;
            if (cnt > bestCount || (cnt == bestCount && e.getKey() < bestRound)) {
                bestCount = cnt;
                bestRound = e.getKey();
            }
        }
        if (bestCount > 0) return bestRound;

        try {
            String html = get(BASE + "/league/" + ligId);
            Pattern p = Pattern.compile(
                    "<span[^>]*\\bclass\\s*=\\s*['\"][^'\"]*\\bcurrent\\b[^'\"]*['\"][^>]*>\\s*(\\d+)\\s*</span>",
                    Pattern.CASE_INSENSITIVE);
            Matcher mt = p.matcher(html);
            if (mt.find()) return Integer.parseInt(mt.group(1));
        } catch (Exception ignored) {}

        // Ehtiyat: son oynanmış həftə
        TreeMap<Integer, int[]> stats = new TreeMap<>();
        for (Map.Entry<Integer, List<Match>> e : ld.rounds.entrySet()) {
            int played = 0, upcoming = 0;
            for (Match m : e.getValue()) {
                if (m.postponed) continue;
                if (m.ft != null) played++; else upcoming++;
            }
            stats.put(e.getKey(), new int[]{played, upcoming});
        }
        int lastPlayed = -1;
        for (Map.Entry<Integer, int[]> e : stats.entrySet())
            if (e.getValue()[0] > 0) lastPlayed = e.getKey();
        if (lastPlayed == -1) return 1;
        if (stats.get(lastPlayed)[1] > 0) return lastPlayed;
        int next = lastPlayed + 1;
        if (stats.containsKey(next) && stats.get(next)[1] > 0) return next;
        return lastPlayed;
    }

    // ── Sezon açar sətrindən başlanğıc ili çıxar (məs. "2024-2025" → 2024) ──
    static int seasonStartYear(String season) {
        Matcher m = Pattern.compile("(\\d{4})").matcher(season);
        if (m.find()) return Integer.parseInt(m.group(1));
        return -1;
    }

    // ── JSON YÜKLƏ (NowGoalHTFT ilə eyni endpoint) ────────────
    static LeagueData loadLeague(int ligId, String season) {
        try {
            String raw = get(BASE + "/jsData/matchResult/json/" + season + "/s" + ligId + "_en.json");
            return new LeagueData(JSON.readTree(raw));
        } catch (Exception e) { return null; }
    }

    static List<String> loadSeasons(int ligId) {
        Set<String> list = new LinkedHashSet<>();
        try {
            String raw = get(BASE + "/jsData/leagueSeason/sea" + ligId + ".json");
            JsonNode node = JSON.readTree(raw).get("SeasonList");
            if (node != null) for (JsonNode s : node) list.add(s.asText());
        } catch (Exception ignored) {}
        return new ArrayList<>(list);
    }

    // ── HTTP GET: gzip + BOM (NowGoalHTFT-dən) ────────────────
    static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer",    BASE + "/")
                .header("Accept",     "text/html,application/json,*/*")
                .GET().build();

        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());

        byte[] b = resp.body();
        if (b.length >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) {
            try (var gis = new GZIPInputStream(new ByteArrayInputStream(b))) {
                b = gis.readAllBytes();
            }
        }
        if (b.length >= 3 && b[0] == (byte) 0xEF && b[1] == (byte) 0xBB && b[2] == (byte) 0xBF)
            b = Arrays.copyOfRange(b, 3, b.length);
        return new String(b, StandardCharsets.UTF_8);
    }

    // ── BUGÜNKÜ MATÇ (live8-dən) ──────────────────────────────
    static class Fixture {
        final int leagueId;
        final String homeName, awayName;
        final int homeId, awayId;   // -1 ola bilər (href-dən çıxmasa)

        Fixture(int leagueId, String homeName, String awayName, int homeId, int awayId) {
            this.leagueId = leagueId;
            this.homeName = homeName;
            this.awayName = awayName;
            this.homeId = homeId;
            this.awayId = awayId;
        }

        @Override public String toString() {
            return "liga=" + leagueId + " " + homeName + " vs " + awayName;
        }
    }

    // ── DATA MODEL (NowGoalHTFT ilə eyni struktur) ────────────
    static class Match {
        int homeId, awayId;
        String ft, ht;
        boolean postponed = false;
        /** Yerli zonaya çevrilmiş başlama vaxtı (JSON-da index 3). Tapılmasa null. */
        LocalDateTime kickoff;

        /** Matçın aid olduğu futbol günü (06:00 kəsimi ilə). */
        LocalDate date() { return footballDay(kickoff); }
    }

    static class LeagueData {
        String leagueName = "";
        Map<Integer, String>      teamNames = new HashMap<>();
        Map<Integer, List<Match>> rounds    = new HashMap<>();

        LeagueData(JsonNode root) {
            JsonNode li = root.get("LeagueInfo");
            if (li != null && li.size() > 1) leagueName = li.get(1).asText();

            JsonNode ti = root.get("TeamInfo");
            if (ti != null) for (JsonNode t : ti) {
                try { teamNames.put(t.get(0).asInt(), t.get(1).asText()); }
                catch (Exception ignored) {}
            }

            JsonNode sl = root.get("ScheduleList");
            if (sl == null) return;
            sl.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode val = entry.getValue();
                if (key.startsWith("R_")) parseRound(key, val);
                else val.fields().forEachRemaining(re -> parseRound(re.getKey(), re.getValue()));
            });
        }

        void parseRound(String roundKey, JsonNode roundData) {
            int rNum;
            try { rNum = Integer.parseInt(roundKey.replace("R_", "")); }
            catch (Exception e) { return; }
            if (!roundData.isArray()) return;

            List<Match> list = new ArrayList<>();
            for (JsonNode m : roundData) {
                try {
                    if (m.size() < 6) continue;
                    Match match  = new Match();
                    match.homeId = m.get(4).asInt();
                    match.awayId = m.get(5).asInt();
                    match.kickoff = parseSiteTime(m.size() > 3 ? m.get(3).asText("") : "");

                    String ft = m.size() > 6 ? m.get(6).asText("") : "";
                    String ht = m.size() > 7 ? m.get(7).asText("") : "";

                    boolean isPostponed = false;
                    if (m.size() > 2) {
                        int status = m.get(2).asInt(-1);
                        if (status == 4 || status == 5) isPostponed = true;
                    }
                    if (!isPostponed) {
                        String fl = ft.toLowerCase().trim();
                        if (fl.equals("postp.") || fl.equals("postponed") || fl.equals("post")
                                || fl.equals("canc.") || fl.equals("cancelled") || fl.equals("aband.")
                                || fl.equals("walkover")) isPostponed = true;
                    }
                    match.postponed = isPostponed;
                    if (!isPostponed) {
                        match.ft = ft.matches("\\d+-\\d+") ? ft : null;
                        match.ht = ht.matches("\\d+-\\d+") ? ht : null;
                    }
                    list.add(match);
                } catch (Exception ignored) {}
            }
            rounds.computeIfAbsent(rNum, k -> new ArrayList<>()).addAll(list);
        }

        String teamName(int id) { return teamNames.getOrDefault(id, "ID:" + id); }

        /** Ada görə takım ID-si tap: əvvəl dəqiq, sonra "contains" (böyük/kiçik həssaslıq yox). */
        int findTeamId(String name) {
            String n = name.toLowerCase().trim();
            for (Map.Entry<Integer, String> e : teamNames.entrySet())
                if (e.getValue().toLowerCase().trim().equals(n)) return e.getKey();
            for (Map.Entry<Integer, String> e : teamNames.entrySet()) {
                String tn = e.getValue().toLowerCase();
                if (tn.contains(n) || n.contains(tn)) return e.getKey();
            }
            return -1;
        }

        /** Verilən həftədə (round) takımın matçını tap. */
        Match findTeamMatchInRound(int round, int teamId) {
            List<Match> ms = rounds.get(round);
            if (ms == null) return null;
            for (Match m : ms)
                if (m.homeId == teamId || m.awayId == teamId) return m;
            return null;
        }
    }
}
