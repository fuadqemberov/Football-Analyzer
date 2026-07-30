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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * ŞƏRT: YALNIZ BUGÜNKÜ matçlar analiz olunur.
 *
 * BUGÜNKÜ MATÇLARIN ÇƏKİLMƏSİ (NowGoalHTFT ilə eyni üsul):
 *   1) Selenium yalnız CANLI səhifədəki LİQA ID-lərini ([sclassid]) yığır.
 *   2) Hər liqanın cari sezon JSON-u yüklənir və bugünkü tarixli, oynanmamış
 *      matçlar oradan götürülür (həftə nömrəsi + takım ID-ləri hazır gəlir).
 *   Beləliklə canlı səhifənin HTML strukturuna (tr1_/team link) bağlılıq yoxdur —
 *   sayt DOM-unu dəyişəndə də matçlar çəkilir.
 *
 * Hər bugünkü "A vs B" matçı üçün (A = ev, B = qonaq — HƏR İKİSİ ayrıca analiz olunur):
 *   1) O matçın oynanacağı HƏFTƏ nömrəsi (round X) cari sezon JSON-undan gəlir.
 *   2) 2010/2011 sezonuna qədər GERİYƏ gedir.
 *   3) Hər sezonda həmin takımın EYNİ X həftəsindəki matçını tapır — MEYDAN FƏRQİ
 *      YOXDUR: həmin həftədə takım istər evdə, istər səfərdə oynamış olsun,
 *      matç nəzərə alınır.
 *   4) YALNIZ bu HT/FT nümunələrini (takımın öz perspektivindən) SEÇİR:
 *          2/1   1/2   1/X   2/X
 *      Takımlardan ən azı birində uyğunluq varsa blok çap olunur (hər ikisi ayrı-ayrı).
 *
 *   SEÇİM perspektivi takımın özüdür:  1 = takım öndə/qalib,  2 = geridə/məğlub,  X = bərabərə.
 *      2/1 = HT-də geridə idi → FT-də qalib gəldi  (comeback qələbə)
 *      1/2 = HT-də öndə idi   → FT-də məğlub oldu   (çöküş)
 *      1/X = HT-də öndə idi   → FT-də bərabərə
 *      2/X = HT-də geridə idi → FT-də bərabərə
 *
 *   ÇAP isə ORİJİNAL formadadır: "ev takımı vs qonaq takımı" və HT/FT etiketi
 *   orijinal hesaba görə (1 = ev öndə/qalib, 2 = qonaq öndə/qalib, X = bərabərə).
 *
 * Mövcud NowGoalHTFT klasına TOXUNULMUR — data yükləmə məntiqi oradan ilhamlanıb.
 * ────────────────────────────────────────────────────────────────────────────
 */
public class NowGoalTeamRoundHTFT {

    // ── Parametrlər ──────────────────────────────────────────
    static final int    MIN_SEASON_YEAR = 2010;   // 2010/2011 sezonuna qədər geri get
    static final int    SEASON_LOOKBACK = 20;     // sezon siyahısı endpoint-i sınanda ehtiyat
    static final int    THREADS         = 50;     // liqa/matç yükləmələrinin paralelliyi
    static final String BASE            = "https://football.nowgoal26.com";
    static final String LIVE_BASE       = "https://live8.nowgoal26.com";

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

        System.out.println(">>> Bugünkü tarix: " + today() + " (zona: " + LOCAL_ZONE + ")");
        System.out.println(">>> Liqa ID-ləri gətirilir...");

        List<Integer> leagueIds = fetchLeagueIdsViaBrowser();
        System.out.println(">>> Tapılan liqa sayı: " + leagueIds.size());
        if (leagueIds.isEmpty()) {
            System.out.println(">>> Liqa ID tapılmadı — canlı səhifə açılmadı və ya boşdur.");
            return;
        }

        System.out.println(">>> Bugünkü matçlar liqa JSON-larından çəkilir...");
        List<Fixture> fixtures = collectTodayFixtures(leagueIds);
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

    // ── SELENIUM: Yalnız liqa ID-lərini çək (NowGoalHTFT ilə eyni) ──
    static List<Integer> fetchLeagueIdsViaBrowser() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments("--headless", "--disable-gpu", "--no-sandbox",
                "--disable-dev-shm-usage", "--blink-settings=imagesEnabled=false",
                "--log-level=3", "--silent");
        WebDriver driver = new ChromeDriver(opts);
        Set<Integer> ids = new TreeSet<>();
        try {
            driver.get(LIVE_BASE + "/");
            Thread.sleep(5000);
            for (WebElement el : driver.findElements(By.cssSelector("[sclassid]"))) {
                try {
                    String v = el.getAttribute("sclassid");
                    if (v != null && !v.isBlank()) ids.add(Integer.parseInt(v.trim()));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.out.println("Selenium xetasi: " + e.getMessage());
        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
        }
        System.out.println("DOM-dan tapilan ID sayi: " + ids.size());
        return new ArrayList<>(ids);
    }

    // ── BUGÜNKÜ MATÇLARI LİQA JSON-LARINDAN YIĞ ───────────────
    //    Canlı səhifədən yalnız liqa ID-si gəlir; matçın özü, həftəsi və
    //    takım ID-ləri cari sezon cədvəlindən TARİXƏ görə seçilir.
    static List<Fixture> collectTodayFixtures(List<Integer> leagueIds) {
        LocalDate today = today();
        List<Fixture> out = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> fs = new ArrayList<>();
        for (int id : leagueIds) {
            final int lid = id;
            fs.add(pool.submit(() -> {
                try {
                    List<String> seasons = loadSeasons(lid);
                    if (seasons.isEmpty()) return;

                    LeagueData cur = loadLeague(lid, seasons.get(0));
                    if (cur == null) return;

                    Set<String> seen = new HashSet<>();
                    for (Map.Entry<Integer, List<Match>> e : cur.rounds.entrySet()) {
                        for (Match m : e.getValue()) {
                            if (m.postponed) continue;
                            if (m.ft != null) continue;          // artıq oynanıb
                            if (!isOnDay(m, today)) continue;    // bugünkü oyun deyil
                            if (!seen.add(m.homeId + "-" + m.awayId)) continue;
                            out.add(new Fixture(lid, e.getKey(), m.homeId, m.awayId,
                                    m.kickoff, seasons, cur));
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[XƏTA] liqa=" + lid + " : " + e.getMessage());
                }
            }));
        }
        for (Future<?> f : fs) { try { f.get(); } catch (Exception ignored) {} }
        pool.shutdown();
        return new ArrayList<>(out);
    }

    // ── BİR BUGÜNKÜ MATÇI ANALİZ ET ───────────────────────────
    static void analyzeFixture(Fixture fx) throws Exception {
        LeagueData cur = fx.cur;
        int idA = fx.homeId, idB = fx.awayId;
        int round = fx.round;
        if (round < 1) return;

        LocalDate today = today();

        // MEYDAN ŞƏRTİ YOXDUR: keçmişdə həmin həftədə takımın həm EV,
        // həm də SƏFƏR matçları nəzərə alınır.

        // Keçmiş sezonları gəz (cari daxil), 2010/2011-ə qədər — HƏR İKİ takım üçün.
        // Sezon JSON-u bir dəfə yüklənir, hər iki takım eyni datadan yoxlanılır.
        StringBuilder sbA = new StringBuilder(), sbB = new StringBuilder();
        int hitsA = 0, hitsB = 0;
        for (String season : fx.seasons) {
            if (seasonStartYear(season) < MIN_SEASON_YEAR) break;

            LeagueData ld = season.equals(fx.seasons.get(0)) ? cur : loadLeague(fx.leagueId, season);
            if (ld == null) continue;

            if (appendRoundHit(sbA, ld, season, round, idA, today)) hitsA++;
            if (appendRoundHit(sbB, ld, season, round, idB, today)) hitsB++;
        }

        // Heç bir takımda bu nümunələrdən yoxdursa çap etmə
        if (hitsA == 0 && hitsB == 0) return;

        synchronized (System.out) {
            System.out.println("==================================================================");
            System.out.println("[LİQA]: " + cur.leagueName + "   |   HƏFTƏ: " + round);
            System.out.println("[BUGÜNKÜ MATÇ]: " + cur.teamName(idA) + "  vs  " + cur.teamName(idB)
                    + "   |   [TARİX]: " + fmt(fx.kickoff) + "  (bugün: " + today + ")");
            printTeamBlock("A takımı (ev)", cur.teamName(idA), round, sbA, hitsA);
            System.out.println();
            printTeamBlock("B takımı (qonaq)", cur.teamName(idB), round, sbB, hitsB);
            System.out.println("==================================================================");
        }
    }

    // ── BİR TAKIMIN BLOKUNU ÇAP ET ────────────────────────────
    static void printTeamBlock(String label, String teamName, int round, StringBuilder sb, int hits) {
        System.out.println("[" + label + " = " + teamName + "] " + round
                + "-ci həftədə (ev + səfər) keçmiş HT/FT (2/1, 1/2, 1/X, 2/X):");
        if (hits == 0) {
            System.out.println("     (bu nümunələr üzrə nəticə yoxdur)");
        } else {
            System.out.print(sb);
        }
        System.out.println("     >>> Uyğun nəticə: " + hits);
    }

    /**
     * Verilən sezonda takımın həmin həftədəki matçını yoxlayır.
     * Uyğun HT/FT nümunəsidirsə sətri {@code sb}-yə əlavə edir və {@code true} qaytarır.
     */
    static boolean appendRoundHit(StringBuilder sb, LeagueData ld, String season,
                                  int round, int teamId, LocalDate today) {
        Match m = ld.findTeamMatchInRound(round, teamId);
        if (m == null || m.ft == null || m.ht == null) return false;
        // TARİX YOXLAMASI: nəticə yalnız artıq oynanmış matçdan götürülə bilər
        if (m.date() != null && !m.date().isBefore(today)) return false;

        // SEÇİM takımın öz perspektivindən aparılır
        String htft = htftForTeam(m, teamId);
        if (htft == null || !WANTED.contains(htft)) return false;

        // ÇAP ORİJİNAL formadadır: ev takımı vs səfər takımı, HT/FT də orijinal
        // hesaba görə (1 = ev öndə/qalib, 2 = səfər öndə/qalib, X = bərabərə)
        String htftOrig = htftForTeam(m, m.homeId);
        sb.append(String.format("     [%-9s]  R%-2d  HT/FT: %-3s  |  %s vs %s   HT(%s) FT(%s)%n",
                season, round, htftOrig, ld.teamName(m.homeId), ld.teamName(m.awayId), m.ht, m.ft));
        return true;
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

    /** Sezon siyahısı; endpoint cavab verməsə NowGoalHTFT-dəki kimi ehtiyat siyahı qurulur. */
    static List<String> loadSeasons(int ligId) {
        Set<String> list = new LinkedHashSet<>();
        try {
            String raw = get(BASE + "/jsData/leagueSeason/sea" + ligId + ".json");
            JsonNode node = JSON.readTree(raw).get("SeasonList");
            if (node != null) for (JsonNode s : node) list.add(s.asText());
        } catch (Exception ignored) {}
        if (!list.isEmpty()) return new ArrayList<>(list);

        // ── EHTİYAT: cari sezon açarını sınayaraq tap, sonra geriyə düz ──
        String[] candidates = {
                "2025-2026", "2026", "2025/26",
                "2024-2025", "2025", "2024/25"
        };
        String cur = null;
        for (String c : candidates) {
            if (loadLeague(ligId, c) != null) { cur = c; break; }
        }
        if (cur == null) return new ArrayList<>();

        List<String> out = new ArrayList<>();
        out.add(cur);
        try {
            if (cur.contains("-")) {
                String[] parts = cur.split("-");
                int y1 = Integer.parseInt(parts[0]);
                int y2 = Integer.parseInt(parts[1]);
                for (int i = 1; i <= SEASON_LOOKBACK; i++) out.add((y1 - i) + "-" + (y2 - i));
            } else if (cur.contains("/")) {
                String[] parts = cur.split("/");
                int y1  = Integer.parseInt(parts[0]);
                int y2s = Integer.parseInt(parts[1]);
                for (int i = 1; i <= SEASON_LOOKBACK; i++)
                    out.add((y1 - i) + "/" + String.format("%02d", y2s - i));
            } else {
                int y = Integer.parseInt(cur);
                for (int i = 1; i <= SEASON_LOOKBACK; i++) out.add(String.valueOf(y - i));
            }
        } catch (Exception ignored) {}
        return out;
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

    // ── BUGÜNKÜ MATÇ (liqa JSON-undan) ────────────────────────
    static class Fixture {
        final int leagueId;
        final int round;
        final int homeId, awayId;
        final LocalDateTime kickoff;
        final List<String> seasons;   // [0] = cari sezon
        final LeagueData cur;         // artıq yüklənib — təkrar sorğuya ehtiyac yoxdur

        Fixture(int leagueId, int round, int homeId, int awayId,
                LocalDateTime kickoff, List<String> seasons, LeagueData cur) {
            this.leagueId = leagueId;
            this.round    = round;
            this.homeId   = homeId;
            this.awayId   = awayId;
            this.kickoff  = kickoff;
            this.seasons  = seasons;
            this.cur      = cur;
        }

        @Override public String toString() {
            return "liga=" + leagueId + " R" + round + " "
                    + cur.teamName(homeId) + " vs " + cur.teamName(awayId);
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

        /**
         * Verilən həftədə (round) takımın matçını tap — MEYDANDAN ASILI OLMAYARAQ:
         * takım həmin həftədə istər ev sahibi, istər qonaq olsun, matç qaytarılır.
         */
        Match findTeamMatchInRound(int round, int teamId) {
            List<Match> ms = rounds.get(round);
            if (ms == null) return null;
            for (Match m : ms)
                if (m.homeId == teamId || m.awayId == teamId) return m;
            return null;
        }
    }
}