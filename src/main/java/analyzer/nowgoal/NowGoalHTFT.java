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
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class NowGoalHTFT {

    // ── Parametrlər ──────────────────────────────────────────
    static final int    SEASON_LOOKBACK  = 15;
    static final int    THREADS          = 50;
    static final int    MAX_BRIDGE_OFFSET = 5;
    static final String CURRENT_SEASON   = String.valueOf(java.time.Year.now().getValue());
    static final String BASE             = "https://football.nowgoal26.com";
    static final String LIVE_BASE        = "https://live5.nowgoal26.com";
    // ─────────────────────────────────────────────────────────

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    static final ObjectMapper JSON = new ObjectMapper();

    // ── HTTP GET: gzip + BOM ──────────────────────────────────
    static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer",    BASE + "/")
                .header("Accept",     "text/html,application/json,*/*")
                .GET().build();

        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200)
            throw new RuntimeException("HTTP " + resp.statusCode());

        byte[] b = resp.body();

        // gzip
        if (b.length >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) {
            try (var gis = new GZIPInputStream(new ByteArrayInputStream(b));
                 var out = new ByteArrayOutputStream()) {
                b = gis.readAllBytes();
            }
        }
        // BOM
        if (b.length >= 3 && b[0] == (byte)0xEF && b[1] == (byte)0xBB && b[2] == (byte)0xBF)
            b = Arrays.copyOfRange(b, 3, b.length);

        return new String(b, StandardCharsets.UTF_8);
    }

    // ── ANA METOD ─────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        LogManager.getLogManager().reset();
        Logger.getLogger("org.openqa.selenium").setLevel(Level.OFF);
        System.setProperty("webdriver.chrome.silentOutput", "true");

        System.out.println(">>> Cari sezon: " + CURRENT_SEASON);
        System.out.println(">>> Liqa ID-ləri gətirilir...");

        List<Integer> leagueIds = fetchLeagueIdsViaBrowser();
        System.out.println(">>> Tapılan liqa sayı: " + leagueIds.size());
        System.out.println(">>> HTTP analiz başladı (körpü offset: 1-" + MAX_BRIDGE_OFFSET + ").\n");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> fs = new ArrayList<>();
        for (int id : leagueIds) {
            final int lid = id;
            fs.add(pool.submit(() -> {
                try { analyzeLeague(lid); }
                catch (Exception e) {
                    System.err.println("[XƏTA] liqa=" + lid + " : " + e.getMessage());
                }
            }));
        }
        for (Future<?> f : fs) { try { f.get(); } catch (Exception ignored) {} }
        pool.shutdown();
        System.out.println("\n>>> Analiz tamamlandı.");
    }

    // ── SELENIUM: Yalnız liqa ID-lərini çək ───────────────────
    static List<Integer> fetchLeagueIdsViaBrowser() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments("--headless","--disable-gpu","--no-sandbox",
                "--disable-dev-shm-usage","--blink-settings=imagesEnabled=false",
                "--log-level=3","--silent");
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

    // ── LİQA ANALİZİ ──────────────────────────────────────────
    static void analyzeLeague(int ligId) throws Exception {
        List<String> allSeasons = loadSeasons(ligId);
        if (allSeasons.isEmpty()) return;

        String curSeason = allSeasons.get(0);
        List<String> pastSeasons = allSeasons.subList(1, Math.min(allSeasons.size(), SEASON_LOOKBACK + 1));

        LeagueData cur = loadLeague(ligId, curSeason);
        if (cur == null) return;

        // YENİLƏNDİ: İndi ligId və cur eyni anda göndərilir
        int currentActiveRound = detectCurrentActiveRound(ligId, cur);
        if (currentActiveRound < 2) return;

        // Aktiv həftədəki OYNANMAMIŞ (Postp. deyil) matçları tap
        List<Match> upcomingMatches = new ArrayList<>();
        List<Match> roundMatches = cur.rounds.get(currentActiveRound);
        if (roundMatches != null) {
            for (Match m : roundMatches) {
                if (m.ft == null && !m.postponed) {
                    upcomingMatches.add(m);
                }
            }
        }
        if (upcomingMatches.isEmpty()) return;

        for (int offset = 1; offset <= MAX_BRIDGE_OFFSET; offset++) {
            int bridgeRound = currentActiveRound - offset;
            if (bridgeRound < 1) continue;

            Map<Integer, Integer> bridge = buildBridge(cur, bridgeRound);
            if (bridge.isEmpty()) continue;

            for (Match up : upcomingMatches) {
                Integer b1 = bridge.get(up.homeId);
                Integer b2 = bridge.get(up.awayId);
                if (b1 == null || b2 == null) continue;

                for (String season : pastSeasons) {
                    try {
                        searchInSeason(
                                ligId, season,
                                currentActiveRound,
                                offset,
                                b1, b2,
                                cur.teamName(up.homeId), cur.teamName(up.awayId),
                                cur.leagueName, cur
                        );
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    // ── CARİ AKTİV HƏFTƏNİ DOM-dan (class="current") TAP ─────
    static int detectCurrentActiveRound(int ligId, LeagueData ld) {
        try {
            // Liqanın əsas səhifəsini HTML olaraq çəkirik
            String html = get(BASE + "/league/" + ligId);

            // HTML içindəki <span ... class="current">12</span> hissəsini tapmaq üçün Regex
            Pattern pattern = Pattern.compile("<span[^>]*\\bclass\\s*=\\s*['\"][^'\"]*\\bcurrent\\b[^'\"]*['\"][^>]*>\\s*(\\d+)\\s*</span>", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(html);

            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1)); // 12 (və ya başqa bir rəqəm) qayıdacaq
            }
        } catch (Exception e) {
            // Səssizcə xətanı yoksayırıq və ehtiyat varianta keçirik
        }

        // HTML-dən (class="current") tapmaq alınmasa, köhnə "saymaq" məntiqindən istifadə et (Ehtiyat Variant)
        return fallbackDetectCurrentActiveRound(ld);
    }

    // ── EHTİYAT AKTİV HƏFTƏ MƏNTİQİ (Köhnə Metod) ────────────
    static int fallbackDetectCurrentActiveRound(LeagueData ld) {
        TreeMap<Integer, int[]> roundStats = new TreeMap<>();

        for (Map.Entry<Integer, List<Match>> entry : ld.rounds.entrySet()) {
            int r = entry.getKey();
            int played = 0, upcoming = 0;
            for (Match m : entry.getValue()) {
                if (m.postponed) continue;
                if (m.ft != null) played++;
                else upcoming++;
            }
            roundStats.put(r, new int[]{played, upcoming});
        }

        int lastPlayedRound = -1;
        for (Map.Entry<Integer, int[]> e : roundStats.entrySet()) {
            if (e.getValue()[0] > 0) lastPlayedRound = e.getKey();
        }

        if (lastPlayedRound == -1) return 1;

        int[] lastStats = roundStats.get(lastPlayedRound);
        if (lastStats[1] > 0) return lastPlayedRound;

        int nextRound = lastPlayedRound + 1;
        if (roundStats.containsKey(nextRound) && roundStats.get(nextRound)[1] > 0) {
            return nextRound;
        }

        return lastPlayedRound;
    }

    // ── KEÇMİŞ SEZONDA AXTAR ─────────────────────────────────
    static void searchInSeason(int ligId, String season,
                               int targetRound,
                               int bridgeOffset,
                               int b1, int b2,
                               String curHome, String curAway,
                               String leagueName, LeagueData curData) throws Exception {
        LeagueData past = loadLeague(ligId, season);
        if (past == null) return;

        int pastBridgeRound = targetRound - bridgeOffset;
        if (pastBridgeRound < 1) return;

        Map<Integer, Integer> pb = buildBridge(past, pastBridgeRound);
        Integer oldA = pb.get(b1);
        Integer oldB = pb.get(b2);
        if (oldA == null || oldB == null) return;

        Match found = findMatch(past, targetRound, oldA, oldB);
        if (found == null || found.ft == null || found.ht == null) return;

        String comeback = comeback(found.ft, found.ht);
        if (comeback.equals("NONE")) return;

        synchronized (System.out) {
            System.out.println("\n**********************");
            System.out.println("[LİQA]: " + leagueName
                    + " | [GÜNCƏL HƏFTƏ]: " + targetRound
                    + " | [KÖRPÜ OFFSETİ]: " + bridgeOffset + " tur öncə");
            System.out.println("[GÜNCƏL OYUN]: " + curHome + " vs " + curAway);
            System.out.println("[KÖPRÜLƏR]: "
                    + curData.teamName(b1) + " & " + curData.teamName(b2));
            System.out.println("[TAPILAN SEZON]: " + season
                    + " | [Eyni Həftə: " + targetRound + "]");
            System.out.println("🔥🔥🔥 [CRAZY COMEBACK HT/FT: " + comeback + "] 🔥🔥🔥");
            System.out.println("[KEÇMİŞ NƏTİCƏ]: "
                    + past.teamName(found.homeId)
                    + " " + found.ft + " "
                    + past.teamName(found.awayId)
                    + " | HT: (" + found.ht + ")");
            System.out.println("**********************");
        }
    }

    // ── YARDIMCI: bridge xeritesi ─────────────────────────────
    static Map<Integer,Integer> buildBridge(LeagueData ld, int round) {
        Map<Integer,Integer> map = new HashMap<>();
        List<Match> ms = ld.rounds.get(round);
        if (ms == null) return map;
        for (Match m : ms) {
            if (m.postponed) continue;
            if (m.ft != null) {
                map.put(m.homeId, m.awayId);
                map.put(m.awayId, m.homeId);
            }
        }
        return map;
    }

    // ── YARDIMCI: maç axtar ───────────────────────────────────
    static Match findMatch(LeagueData ld, int round, int a, int b) {
        List<Match> ms = ld.rounds.get(round);
        if (ms == null) return null;
        for (Match m : ms)
            if ((m.homeId==a && m.awayId==b)||(m.homeId==b && m.awayId==a))
                return m;
        return null;
    }

    // ── COMEBACK KONTROLU ─────────────────────────────────────
    static String comeback(String ft, String ht) {
        try {
            ft = ft.trim(); ht = ht.trim();
            String[] f = ft.split("-"), h = ht.split("-");
            int fH = Integer.parseInt(f[0]), fA = Integer.parseInt(f[1]);
            int hH = Integer.parseInt(h[0]), hA = Integer.parseInt(h[1]);
            if (hH > hA && fH < fA) return "1/2";
            if (hH < hA && fH > fA) return "2/1";
        } catch (Exception ignored) {}
        return "NONE";
    }

    // ── JSON YÜKLƏ ────────────────────────────────────────────
    static LeagueData loadLeague(int ligId, String season) {
        try {
            String raw = get(BASE + "/jsData/matchResult/json/" + season + "/s" + ligId + "_en.json");
            return new LeagueData(JSON.readTree(raw));
        } catch (Exception e) { return null; }
    }

    static List<String> loadSeasons(int ligId) {
        List<String> list = new ArrayList<>();
        try {
            String url = BASE + "/jsData/leagueSeason/sea" + ligId + ".json";
            String raw = get(url);
            JsonNode node = JSON.readTree(raw).get("SeasonList");
            if (node != null) {
                for (JsonNode s : node) list.add(s.asText());
            }
        } catch (Exception e) {
            String[] candidates = {
                    "2025-2026", "2026", "2025/26",
                    "2024-2025", "2025", "2024/25"
            };
            for (String c : candidates) {
                if (loadLeague(ligId, c) != null) {
                    list.add(c);
                    break;
                }
            }
            if (list.isEmpty()) return list;

            String cur = list.get(0);
            if (cur.contains("-")) {
                try {
                    String[] parts = cur.split("-");
                    int y1 = Integer.parseInt(parts[0]);
                    int y2 = Integer.parseInt(parts[1]);
                    for (int i = 1; i <= SEASON_LOOKBACK; i++)
                        list.add((y1-i) + "-" + (y2-i));
                } catch (Exception ignored) {}
            } else if (cur.contains("/")) {
                try {
                    String[] parts = cur.split("/");
                    int y1 = Integer.parseInt(parts[0]);
                    int y2s = Integer.parseInt(parts[1]);
                    for (int i = 1; i <= SEASON_LOOKBACK; i++)
                        list.add((y1-i) + "/" + String.format("%02d", y2s-i));
                } catch (Exception ignored) {}
            } else {
                try {
                    int y = Integer.parseInt(cur);
                    for (int i = 1; i <= SEASON_LOOKBACK; i++)
                        list.add(String.valueOf(y - i));
                } catch (Exception ignored) {}
            }
        }
        return list;
    }

    // ── DATA MODEL ────────────────────────────────────────────
    static class Match {
        int homeId, awayId;
        String ft, ht;
        boolean postponed = false;
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

                if (key.startsWith("R_")) {
                    parseRound(key, val);
                } else {
                    val.fields().forEachRemaining(re -> parseRound(re.getKey(), re.getValue()));
                }
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

                    String ft = m.size() > 6 ? m.get(6).asText("") : "";
                    String ht = m.size() > 7 ? m.get(7).asText("") : "";

                    boolean isPostponed = false;

                    if (m.size() > 2) {
                        int status = m.get(2).asInt(-1);
                        if (status == 4 || status == 5) isPostponed = true;
                    }

                    if (!isPostponed) {
                        String ftLower = ft.toLowerCase().trim();
                        if (ftLower.equals("postp.") || ftLower.equals("postponed")
                                || ftLower.equals("post") || ftLower.equals("canc.")
                                || ftLower.equals("cancelled") || ftLower.equals("aband.")
                                || ftLower.equals("walkover")) {
                            isPostponed = true;
                        }
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

        String teamName(int id) {
            return teamNames.getOrDefault(id, "ID:" + id);
        }
    }
}