package analyzer.bet365;

import com.microsoft.playwright.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  Bet365HundredMethodAnalyzer — 100 FƏRQLİ YÖNTEM İLƏ TWIN ANALİZ
 * ═══════════════════════════════════════════════════════════════════════════
 *  Məntiq:
 *   • Bugünkü oyunlar Flashscore-dan çəkilir (oranları ilə).
 *   • bet365_matches (~780k sətir) TAM RAM-a yüklənir — heç bir sətir-sətir SQL yoxdur.
 *   • Hər oran kolonu üçün (dəyər → sətir indeksləri) indeksi qurulur → O(1) axtarış.
 *   • 100 fərqli YÖNTEM var. Hər yöntem = oran kolonlarının fərqli, ardıcıl daralan dəsti.
 *   • Bir oyunun analizi 100 cür ola bilər, amma hər yöntem 780k datanı ardıcıl süzərək
 *     sonda 1–3 TWIN oyuna endirir (eyni oran dəyərli tarixi oyunlar).
 *   • Yalnız 1–3 twin qalanda çap edir: [yöntem nömrəsi] + [hansı oyun] + [twin nəticələri].
 *
 *  Performans:
 *   • Kolon-dəyər indeksi + sıralanmış merge-intersection (O(pool)).
 *   • Yöntemlər maç başına paralel işlənir (read-only paylaşılan massivlər → thread-safe).
 *   • Bütün göstəriş kolonları (tarix, komanda, nəticə) da RAM-dadır → DB gediş-gəlişi sıfır.
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class Bet365HundredMethodAnalyzer {

    // ==================== KOLON TANIMLARI ====================
    static final class ColumnDef {
        final String sqlColumn, displayName, flashscoreKey;
        ColumnDef(String sqlColumn, String displayName, String flashscoreKey) {
            this.sqlColumn = sqlColumn; this.displayName = displayName; this.flashscoreKey = flashscoreKey;
        }
    }

    private static final List<ColumnDef> ALL_COLS = new ArrayList<>();
    private static final Map<String, ColumnDef> COL_BY_DISPLAY = new HashMap<>();

    static {
        // 1x2
        add("ft_1_a", "MS 1", "1x2|Full Time|Home");
        add("ft_x_a", "MS X", "1x2|Full Time|Draw");
        add("ft_2_a", "MS 2", "1x2|Full Time|Away");
        add("first_1_a", "İY 1", "1x2|1st Half|Home");
        add("first_x_a", "İY X", "1x2|1st Half|Draw");
        add("first_2_a", "İY 2", "1x2|1st Half|Away");
        add("second_1_a", "2Y 1", "1x2|2nd Half|Home");
        add("second_x_a", "2Y X", "1x2|2nd Half|Draw");
        add("second_2_a", "2Y 2", "1x2|2nd Half|Away");

        // Both teams to score
        add("bts_ft_yes_a", "KG Evet", "Both teams|Full Time|Yes");
        add("bts_ft_no_a", "KG Hayır", "Both teams|Full Time|No");
        add("bts_first_yes_a", "İY KG Evet", "Both teams|1st Half|Yes");
        add("bts_first_no_a", "İY KG Hayır", "Both teams|1st Half|No");
        add("bts_second_yes_a", "2Y KG Evet", "Both teams|2nd Half|Yes");
        add("bts_second_no_a", "2Y KG Hayır", "Both teams|2nd Half|No");

        // Double chance
        add("dbc_ft_1x_a", "ÇŞ 1X", "Double chance|Full Time|1X");
        add("dbc_ft_12_a", "ÇŞ 12", "Double chance|Full Time|12");
        add("dbc_ft_x2_a", "ÇŞ X2", "Double chance|Full Time|X2");
        add("dbc_first_1x_a", "İY ÇŞ 1X", "Double chance|1st Half|1X");
        add("dbc_first_12_a", "İY ÇŞ 12", "Double chance|1st Half|12");
        add("dbc_first_x2_a", "İY ÇŞ X2", "Double chance|1st Half|X2");

        // Over/Under Full Time
        add("ft_0_5_over_a", "A/U 0.5 Üst", "Over/Under|Full Time|O 0.5");
        add("ft_0_5_under_a", "A/U 0.5 Alt", "Over/Under|Full Time|U 0.5");
        add("ft_1_5_over_a", "A/U 1.5 Üst", "Over/Under|Full Time|O 1.5");
        add("ft_1_5_under_a", "A/U 1.5 Alt", "Over/Under|Full Time|U 1.5");
        add("ft_2_5_over_a", "A/U 2.5 Üst", "Over/Under|Full Time|O 2.5");
        add("ft_2_5_under_a", "A/U 2.5 Alt", "Over/Under|Full Time|U 2.5");
        add("ft_3_5_over_a", "A/U 3.5 Üst", "Over/Under|Full Time|O 3.5");
        add("ft_3_5_under_a", "A/U 3.5 Alt", "Over/Under|Full Time|U 3.5");
        add("ft_4_5_over_a", "A/U 4.5 Üst", "Over/Under|Full Time|O 4.5");
        add("ft_4_5_under_a", "A/U 4.5 Alt", "Over/Under|Full Time|U 4.5");
        add("ft_5_5_over_a", "A/U 5.5 Üst", "Over/Under|Full Time|O 5.5");
        add("ft_5_5_under_a", "A/U 5.5 Alt", "Over/Under|Full Time|U 5.5");

        // Over/Under 1st Half
        add("first_0_5_over_a", "İY A/U 0.5 Üst", "Over/Under|1st Half|O 0.5");
        add("first_0_5_under_a", "İY A/U 0.5 Alt", "Over/Under|1st Half|U 0.5");
        add("first_1_5_over_a", "İY A/U 1.5 Üst", "Over/Under|1st Half|O 1.5");
        add("first_1_5_under_a", "İY A/U 1.5 Alt", "Over/Under|1st Half|U 1.5");
        add("first_2_5_over_a", "İY A/U 2.5 Üst", "Over/Under|1st Half|O 2.5");
        add("first_2_5_under_a", "İY A/U 2.5 Alt", "Over/Under|1st Half|U 2.5");

        // Over/Under 2nd Half
        add("second_0_5_over_a", "2Y A/U 0.5 Üst", "Over/Under|2nd Half|O 0.5");
        add("second_0_5_under_a", "2Y A/U 0.5 Alt", "Over/Under|2nd Half|U 0.5");
        add("second_1_5_over_a", "2Y A/U 1.5 Üst", "Over/Under|2nd Half|O 1.5");
        add("second_1_5_under_a", "2Y A/U 1.5 Alt", "Over/Under|2nd Half|U 1.5");
        add("second_2_5_over_a", "2Y A/U 2.5 Üst", "Over/Under|2nd Half|O 2.5");
        add("second_2_5_under_a", "2Y A/U 2.5 Alt", "Over/Under|2nd Half|U 2.5");

        // HT/FT
        add("ht_ft_11_a", "HT/FT 1/1", "HTFT|1/1");
        add("ht_ft_1x_a", "HT/FT 1/X", "HTFT|1/X");
        add("ht_ft_12_a", "HT/FT 1/2", "HTFT|1/2");
        add("ht_ft_x1_a", "HT/FT X/1", "HTFT|X/1");
        add("ht_ft_xx_a", "HT/FT X/X", "HTFT|X/X");
        add("ht_ft_x2_a", "HT/FT X/2", "HTFT|X/2");
        add("ht_ft_21_a", "HT/FT 2/1", "HTFT|2/1");
        add("ht_ft_2x_a", "HT/FT 2/X", "HTFT|2/X");
        add("ht_ft_22_a", "HT/FT 2/2", "HTFT|2/2");

        // Correct Score Full Time
        String[] ftScores = {"1:0","2:0","2:1","3:0","3:1","3:2","4:0","4:1","4:2","4:3","5:0","5:1","5:2",
                "0:0","1:1","2:2","3:3","4:4","0:1","0:2","1:2","0:3","1:3","2:3","0:4","1:4","2:4","3:4","0:5","1:5","2:5"};
        for (String sc : ftScores)
            add("ft_score_" + sc.replace(":", "_") + "_a", "MS Skor " + sc, "Correct score|Full Time|" + sc);

        // Correct Score 1st Half
        String[] htScores = {"1:0","2:0","2:1","3:0","3:1","3:2","0:0","1:1","2:2","0:1","0:2","1:2","0:3","1:3","2:3"};
        for (String sc : htScores)
            add("first_score_" + sc.replace(":", "_") + "_a", "İY Skor " + sc, "Correct score|1st Half|" + sc);
    }

    private static void add(String sql, String display, String flash) {
        ColumnDef c = new ColumnDef(sql, display, flash);
        ALL_COLS.add(c);
        COL_BY_DISPLAY.put(display, c);
    }

    // ==================== 100 YÖNTEM ÜÇÜN SİQNAL HOVUZU ====================
    // Ən çox dolu olan (parse edilə bilən) marketlər — bunlardan 100 unikal kombinasiya yığılır.
    private static final String[] SIGNAL_POOL = {
            // 1x2
            "MS 1","MS X","MS 2","İY 1","İY X","İY 2","2Y 1","2Y X","2Y 2",
            // Double chance
            "ÇŞ 1X","ÇŞ 12","ÇŞ X2","İY ÇŞ 1X","İY ÇŞ 12","İY ÇŞ X2",
            // Over/Under
            "A/U 1.5 Üst","A/U 1.5 Alt","A/U 2.5 Üst","A/U 2.5 Alt","A/U 3.5 Üst","A/U 3.5 Alt",
            "İY A/U 0.5 Üst","İY A/U 0.5 Alt","İY A/U 1.5 Üst","İY A/U 1.5 Alt",
            "2Y A/U 1.5 Üst","2Y A/U 1.5 Alt",
            // BTS
            "KG Evet","KG Hayır","İY KG Evet","İY KG Hayır",
            // HT/FT
            "HT/FT 1/1","HT/FT X/X","HT/FT 2/2","HT/FT 1/X","HT/FT X/1",
            "HT/FT 2/X","HT/FT X/2","HT/FT 1/2","HT/FT 2/1",
            // Correct score FT
            "MS Skor 1:0","MS Skor 2:0","MS Skor 2:1","MS Skor 1:1","MS Skor 0:0",
            "MS Skor 0:1","MS Skor 0:2","MS Skor 1:2",
            // Correct score HT
            "İY Skor 0:0","İY Skor 1:0","İY Skor 0:1","İY Skor 1:1"
    };

    // Marketlərin daralma sırası (geniş → dar). Yöntem daxilində kolonlar buna görə sıralanır.
    private static int familyRank(String display) {
        if (display.startsWith("HT/FT")) return 4;
        if (display.contains("Skor")) return 5;
        if (display.startsWith("KG") || display.contains("KG ")) return 3;
        if (display.contains("A/U")) return 2;
        if (display.startsWith("ÇŞ") || display.contains("ÇŞ ")) return 1;
        return 0; // 1x2
    }

    // ─── Bir yöntem = nömrə + ardıcıl daralan kolon dəsti ───
    static final class Method {
        final int number;
        final ColumnDef[] cols;
        Method(int number, ColumnDef[] cols) { this.number = number; this.cols = cols; }
        String recipe() {
            StringJoiner sj = new StringJoiner(", ");
            for (ColumnDef c : cols) sj.add("\"" + c.displayName + "\"");
            return sj.toString();
        }
    }

    private static final int METHOD_COUNT = 100;
    private static final int TWIN_MIN = 1;   // sonda ən az bu qədər twin
    private static final int TWIN_MAX = 3;   // sonda ən çox bu qədər twin ("2-3 twin qalmalıdı")

    /** Deterministik 100 unikal yöntem yaradılır (seed sabit → təkrar oluna bilən). */
    private static List<Method> buildMethods() {
        Random rng = new Random(365_100L);
        List<Method> methods = new ArrayList<>(METHOD_COUNT);
        Set<String> seen = new HashSet<>();
        List<String> pool = new ArrayList<>(Arrays.asList(SIGNAL_POOL));
        int guard = 0;
        while (methods.size() < METHOD_COUNT && guard++ < METHOD_COUNT * 50) {
            int len = 4 + rng.nextInt(4); // 4..7 kolon
            Collections.shuffle(pool, rng);
            List<String> chosen = new ArrayList<>(pool.subList(0, len));
            // geniş → dar sırala ki, ardıcıl süzgəc məntiqli daralsın
            chosen.sort(Comparator.comparingInt(Bet365HundredMethodAnalyzer::familyRank)
                    .thenComparing(Comparator.naturalOrder()));
            String key = String.join("|", new TreeSet<>(chosen));
            if (!seen.add(key)) continue;
            ColumnDef[] cols = chosen.stream().map(COL_BY_DISPLAY::get).toArray(ColumnDef[]::new);
            methods.add(new Method(methods.size() + 1, cols));
        }
        return methods;
    }

    // ==================== HTTP ====================
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    // ==================== RAM-DAXİLİ TABLO ====================
    private final Map<String, float[]> oddsColumns = new HashMap<>(); // sqlColumn → dəyərlər (NaN = NULL)
    private String[] rDate, rHome, rAway, rHt, rFt, rHtFt, rFtSide;   // göstəriş + hesablanmış etiketlər
    private int rowCount;
    // sqlColumn → (float dəyərin bit təsviri → sıralanmış sətir indeksləri)
    private final Map<String, Map<Integer, int[]>> colIndex = new HashMap<>();

    private Connection conn;

    public Bet365HundredMethodAnalyzer() {
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/postgres", "postgres", "fuad123");
            System.out.println("✅ Veritabanına bağlanıldı.");
            loadTableIntoMemory();
        } catch (Exception e) {
            System.err.println("❌ Veritabanı hatası: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private long sqlRowCount() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM bet365_matches")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    // Tüm oran + göstəriş kolonları bir dəfə RAM-a yüklənir, sonra indeks qurulur.
    private void loadTableIntoMemory() throws SQLException {
        long t0 = System.currentTimeMillis();
        System.out.println("⏳ bet365_matches belleğe yükleniyor...");

        // Tabloda mövcud oran kolonları
        Set<String> existing = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, "bet365_matches", null)) {
            while (rs.next()) existing.add(rs.getString("COLUMN_NAME"));
        }
        List<String> colList = ALL_COLS.stream()
                .map(c -> c.sqlColumn).filter(existing::contains).distinct().collect(Collectors.toList());

        int cap = (int) sqlRowCount() + 1000;
        rDate = new String[cap]; rHome = new String[cap]; rAway = new String[cap];
        rHt = new String[cap]; rFt = new String[cap]; rHtFt = new String[cap]; rFtSide = new String[cap];
        float[][] data = new float[colList.size()][cap];

        String sql = "SELECT date_time, home_team, away_team, ht_iy, ft_ms, "
                + String.join(", ", colList) + " FROM bet365_matches ORDER BY date_time DESC";
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(20000);
            try (ResultSet rs = ps.executeQuery()) {
                int r = 0;
                while (rs.next() && r < cap) {
                    rDate[r] = rs.getString(1);
                    rHome[r] = rs.getString(2);
                    rAway[r] = rs.getString(3);
                    rHt[r]   = rs.getString(4);
                    rFt[r]   = rs.getString(5);
                    rHtFt[r] = computeHtFt(rHt[r], rFt[r]);
                    rFtSide[r] = scoreSign(rFt[r]);
                    for (int i = 0; i < colList.size(); i++) {
                        float v = rs.getFloat(i + 6);
                        data[i][r] = rs.wasNull() ? Float.NaN : v;
                    }
                    r++;
                }
                rowCount = r;
            }
        } finally {
            conn.setAutoCommit(true);
        }
        for (int i = 0; i < colList.size(); i++) oddsColumns.put(colList.get(i), data[i]);

        System.out.printf("✅ Tablo belleğe yüklendi: %,d satır × %d kolon (%.1f sn)%n",
                rowCount, colList.size(), (System.currentTimeMillis() - t0) / 1000.0);
        buildColumnIndex(colList);
    }

    // Kolon-dəyər indeksi: sətirlər date_time DESC yükləndiyi üçün indekslər artan sırada gedir → sıralı.
    private void buildColumnIndex(List<String> colList) {
        long t0 = System.currentTimeMillis();
        System.out.println("🔨 Kolon indeksi inşa edilir...");
        for (String col : colList) {
            float[] vals = oddsColumns.get(col);
            Map<Integer, List<Integer>> tmp = new HashMap<>();
            for (int i = 0; i < rowCount; i++) {
                float v = vals[i];
                if (Float.isNaN(v)) continue;
                tmp.computeIfAbsent(Float.floatToIntBits(v), k -> new ArrayList<>()).add(i);
            }
            Map<Integer, int[]> byVal = new HashMap<>(tmp.size() * 2);
            for (Map.Entry<Integer, List<Integer>> e : tmp.entrySet()) {
                int[] arr = new int[e.getValue().size()];
                for (int k = 0; k < arr.length; k++) arr[k] = e.getValue().get(k);
                byVal.put(e.getKey(), arr); // artan sırada
            }
            colIndex.put(col, byVal);
        }
        System.out.printf("✅ İndeks hazır (%.1f sn)%n%n", (System.currentTimeMillis() - t0) / 1000.0);
    }

    // ==================== SCRAPER (bugünkü oyunlar + oranlar) ====================
    static final class MatchInfo {
        String id, home, away, date;
        final Map<String, String> odds = new HashMap<>();
    }

    private List<MatchInfo> scrapeTodayMatches() {
        List<MatchInfo> matches = new ArrayList<>();
        System.out.println("🔍 Flashscore'dan bugünkü maçlar çekiliyor...\n");
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             Page page = browser.newPage()) {

            page.navigate("https://www.flashscore.co.uk/football/");
            try { page.locator("#onetrust-accept-btn-handler")
                    .click(new Locator.ClickOptions().setTimeout(3000)); } catch (Exception _) {}
            page.waitForSelector("div[id^='g_1_'].event__match",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            Locator rows = page.locator("div[id^='g_1_'].event__match");
            int count = rows.count();
            System.out.println("📊 Toplam " + count + " maç bulundu.\n");
            for (int i = 0; i < count; i++) {
                try {
                    Locator row = rows.nth(i);
                    MatchInfo mi = new MatchInfo();
                    mi.id = row.getAttribute("id").replace("g_1_", "");
                    mi.home = row.locator(".event__homeParticipant").innerText().trim();
                    mi.away = row.locator(".event__awayParticipant").innerText().trim();
                    mi.date = LocalDate.now().toString();
                    matches.add(mi);
                } catch (Exception _) {}
            }
        } catch (Exception e) {
            System.err.println("❌ Scraper hatası: " + e.getMessage());
        }

        // Oranlar paralel çekilir
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (MatchInfo mi : matches) futures.add(pool.submit(() -> fetchOddsForMatch(mi)));
        for (Future<?> f : futures) { try { f.get(); } catch (Exception _) {} }
        pool.shutdown();
        System.out.println("✅ " + matches.size() + " maçın oranları çekildi.\n");
        return matches;
    }

    private void fetchOddsForMatch(MatchInfo mi) {
        String oddsUrl = String.format(
                "https://global.ds.lsapp.eu/odds/pq_graphql?_hash=oce&eventId=%s&projectId=5&geoIpCode=AZ&geoIpSubdivisionCode=AZBA",
                mi.id);
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(oddsUrl))
                    .header("User-Agent", "Mozilla/5.0").GET().build();
            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body().startsWith("{")) parseOdds(mi, resp.body());
        } catch (Exception _) {}
    }

    private void parseOdds(MatchInfo mi, String jsonBody) {
        JSONObject root = new JSONObject(jsonBody);
        JSONObject data = root.optJSONObject("data");
        if (data == null) return;
        JSONObject oddsData = data.optJSONObject("findOddsByEventId");
        if (oddsData == null) return;
        JSONArray oddsList = oddsData.optJSONArray("odds");
        if (oddsList == null) return;

        String homePartId = null, awayPartId = null;
        for (int i = 0; i < oddsList.length(); i++) {
            JSONObject entry = oddsList.getJSONObject(i);
            if (entry.getInt("bookmakerId") != 16) continue;
            if ("HOME_DRAW_AWAY".equals(entry.getString("bettingType"))
                    && "FULL_TIME".equals(entry.getString("bettingScope"))) {
                JSONArray items = entry.getJSONArray("odds");
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.getJSONObject(j);
                    if (!item.isNull("eventParticipantId")) {
                        String pid = item.getString("eventParticipantId");
                        if (homePartId == null) homePartId = pid;
                        else if (!pid.equals(homePartId)) awayPartId = pid;
                    }
                }
                break;
            }
        }

        for (int i = 0; i < oddsList.length(); i++) {
            JSONObject entry = oddsList.getJSONObject(i);
            if (entry.getInt("bookmakerId") != 16) continue;
            String bettingType = entry.getString("bettingType");
            String scope = entry.getString("bettingScope");
            JSONArray items = entry.getJSONArray("odds");

            switch (bettingType) {
                case "HOME_DRAW_AWAY": {
                    String period = mapScope(scope);
                    if (period == null) break;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j);
                        String val = getOddsValue(item);
                        String pid = item.isNull("eventParticipantId") ? null : item.getString("eventParticipantId");
                        String key = (pid == null) ? "1x2|" + period + "|Draw"
                                : pid.equals(homePartId) ? "1x2|" + period + "|Home" : "1x2|" + period + "|Away";
                        mi.odds.put(key, val);
                    }
                    break;
                }
                case "BOTH_TEAMS_TO_SCORE": {
                    String period = mapScope(scope);
                    if (period == null) break;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j);
                        boolean yes = item.getBoolean("bothTeamsToScore");
                        mi.odds.put("Both teams|" + period + "|" + (yes ? "Yes" : "No"), getOddsValue(item));
                    }
                    break;
                }
                case "OVER_UNDER": {
                    String period = mapScope(scope);
                    if (period == null) break;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j);
                        if (!item.isNull("handicap")) {
                            double h = item.getJSONObject("handicap").getDouble("value");
                            String sel = item.getString("selection");
                            mi.odds.put("Over/Under|" + period + "|" + ("OVER".equals(sel) ? "O " : "U ") + h, getOddsValue(item));
                        }
                    }
                    break;
                }
                case "DOUBLE_CHANCE": {
                    String period = mapScope(scope);
                    if (period == null) break;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j);
                        String val = getOddsValue(item);
                        String pid = item.isNull("eventParticipantId") ? null : item.getString("eventParticipantId");
                        String key = (pid == null) ? "Double chance|" + period + "|12"
                                : pid.equals(homePartId) ? "Double chance|" + period + "|1X" : "Double chance|" + period + "|X2";
                        mi.odds.put(key, val);
                    }
                    break;
                }
                case "CORRECT_SCORE": {
                    String period = mapScope(scope);
                    if (period == null || "2nd Half".equals(period)) break;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j);
                        if (!item.isNull("score")) {
                            String score = item.getString("score").replace(" ", "");
                            mi.odds.put("Correct score|" + period + "|" + score, getOddsValue(item));
                        }
                    }
                    break;
                }
                case "HALF_FULL_TIME": {
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j);
                        if (!item.isNull("winner")) mi.odds.put("HTFT|" + item.getString("winner"), getOddsValue(item));
                    }
                    break;
                }
            }
        }
    }

    private String mapScope(String scope) {
        return switch (scope) {
            case "FULL_TIME" -> "Full Time";
            case "FIRST_HALF" -> "1st Half";
            case "SECOND_HALF" -> "2nd Half";
            default -> null;
        };
    }

    private String getOddsValue(JSONObject item) {
        try {
            if (!item.isNull("opening")) return item.getString("opening");
            if (!item.isNull("value")) return item.getString("value");
        } catch (Exception _) {}
        return "-";
    }

    // ==================== ARDICIL SÜZGƏC (indeksli, thread-safe) ====================
    /**
     * Yöntemin kolonları ardıcıl tətbiq olunur: hər kolon bugünkü oyunun oran dəyərinə
     * uyğun tarixi sətirlərlə kəsişdirilir. Kəsişmə boş qalarsa o kolon atlanır
     * (məlumat itməsin), pool TWIN_MAX-a enəndə dayanır. Nəticə = twin sətir indeksləri.
     */
    private int[] applyMethod(MatchInfo match, Method method) {
        int[] current = null;
        for (ColumnDef col : method.cols) {
            String raw = match.odds.get(col.flashscoreKey);
            if (raw == null || raw.isEmpty() || "-".equals(raw)) continue;
            float value;
            try { value = Float.parseFloat(raw.replace(',', '.')); }
            catch (NumberFormatException _) { continue; }

            Map<Integer, int[]> byVal = colIndex.get(col.sqlColumn);
            if (byVal == null) continue;
            int[] bucket = byVal.get(Float.floatToIntBits(value));
            if (bucket == null || bucket.length == 0) continue;

            if (current == null) {
                current = bucket;
            } else {
                int[] next = intersect(current, bucket);
                if (next.length == 0) continue; // bu kolon çox daraldır → atla
                current = next;
            }
            if (current.length <= TWIN_MAX) break;
        }
        return current == null ? new int[0] : current;
    }

    /** İki sıralanmış int[] üçün kəsişmə (merge, O(m+n)). */
    private static int[] intersect(int[] a, int[] b) {
        int[] res = new int[Math.min(a.length, b.length)];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if      (a[i] == b[j]) { res[k++] = a[i++]; j++; }
            else if (a[i] <  b[j])   i++;
            else                     j++;
        }
        return Arrays.copyOf(res, k);
    }

    // ==================== NƏTİCƏ MODELLERİ ====================
    static final class Hit {
        final int methodNumber;
        final String recipe;
        final int[] twinRows;
        Hit(int methodNumber, String recipe, int[] twinRows) {
            this.methodNumber = methodNumber; this.recipe = recipe; this.twinRows = twinRows;
        }
    }

    // ==================== ANA ÇALIŞTIRICI ====================
    public void run() {
        List<Method> methods = buildMethods();
        System.out.println("🧪 " + methods.size() + " fərqli yöntem hazırlandı.\n");

        List<MatchInfo> today = scrapeTodayMatches();
        if (today.isEmpty()) { System.out.println("❌ Bugünkü maç bulunamadı!"); return; }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("🤖 100 YÖNTEM İLƏ ANALİZ — %d oyun × %d yöntem  (baza: %,d tarixi oyun)%n",
                today.size(), methods.size(), rowCount);
        System.out.println("═══════════════════════════════════════════════════════════════\n");

        int totalHits = 0, matchesWithHit = 0;
        for (MatchInfo match : today) {
            // Yöntemlər paralel işlənir (paylaşılan indeks read-only → thread-safe)
            ConcurrentLinkedQueue<Hit> hitQ = new ConcurrentLinkedQueue<>();
            methods.parallelStream().forEach(m -> {
                int[] twins = applyMethod(match, m);
                if (twins.length >= TWIN_MIN && twins.length <= TWIN_MAX)
                    hitQ.add(new Hit(m.number, m.recipe(), twins));
            });
            if (hitQ.isEmpty()) continue;

            List<Hit> hits = new ArrayList<>(hitQ);
            hits.sort(Comparator.comparingInt(h -> h.methodNumber));
            matchesWithHit++;
            totalHits += hits.size();

            printMatchHeader(match, hits.size());
            for (Hit h : hits) printHit(match, h);
            printMatchPrognosis(match, hits);
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("✅ TAMAMLANDI — %d oyunda %d yöntem 2–3 twinə endirdi.%n", matchesWithHit, totalHits);
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    // ==================== ÇAP ====================
    private void printMatchHeader(MatchInfo match, int hitCount) {
        System.out.println("╔══════════════════════════════════════════════════════════════");
        System.out.println("║ ⚽ OYUN: " + match.home + " vs " + match.away + "   [" + match.date + "]");
        System.out.println("║ " + hitCount + " yöntem bu oyunu 2–3 twinə endirdi.");
        System.out.println("╚══════════════════════════════════════════════════════════════");
    }

    private void printHit(MatchInfo match, Hit h) {
        System.out.println("┌─ 🔎 YÖNTEM #" + h.methodNumber + "  │  " + match.home + " vs " + match.away);
        System.out.println("│  Filter: " + h.recipe);
        System.out.println("│  " + String.format("%,d", rowCount) + " datadan → " + h.twinRows.length + " twin oyun qaldı:");
        for (int idx = 0; idx < h.twinRows.length; idx++) {
            int r = h.twinRows[idx];
            System.out.printf("│    %d. %-19s │ %-22s │ İY %-5s → MS %-5s │ HT/FT %s%n",
                    idx + 1, safe(rDate[r]), safe(rHome[r]) + " - " + safe(rAway[r]),
                    safe(rHt[r]), safe(rFt[r]), rHtFt[r] == null ? "?/?" : rHtFt[r]);
        }
        System.out.println("└──────────────────────────────────────────────────────────────");
    }

    // Bir oyun üçün bütün yöntem-twinlərinin ortaq proqnozu
    private void printMatchPrognosis(MatchInfo match, List<Hit> hits) {
        Map<String, Integer> htftFreq = new HashMap<>();
        Map<String, Integer> sideFreq = new HashMap<>();
        int twinTotal = 0;
        for (Hit h : hits) {
            for (int r : h.twinRows) {
                if (rHtFt[r] != null) htftFreq.merge(rHtFt[r], 1, Integer::sum);
                if (rFtSide[r] != null) sideFreq.merge(rFtSide[r], 1, Integer::sum);
                twinTotal++;
            }
        }
        String bestHtFt = topKey(htftFreq);
        String bestSide = topKey(sideFreq);
        System.out.println("  🔮 PROQNOZ (" + match.home + " vs " + match.away + "): "
                + "HT/FT ➜ " + bestHtFt + " (" + freqOf(htftFreq, bestHtFt) + "/" + twinTotal + " twin)  │  "
                + "MS tərəf ➜ " + bestSide + " (" + signName(bestSide) + ", "
                + freqOf(sideFreq, bestSide) + "/" + twinTotal + ")");
        System.out.println();
    }

    private static int freqOf(Map<String, Integer> m, String k) { return k == null ? 0 : m.getOrDefault(k, 0); }

    private static String topKey(Map<String, Integer> m) {
        return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("—");
    }

    // ==================== YARDIMÇILAR ====================
    private static String computeHtFt(String ht, String ft) {
        String a = scoreSign(ht), b = scoreSign(ft);
        return (a == null || b == null) ? null : a + "/" + b;
    }

    private static String scoreSign(String score) {
        if (score == null) return null;
        String[] p = score.trim().split("[-:]");
        if (p.length != 2) return null;
        try {
            int h = Integer.parseInt(p[0].trim()), a = Integer.parseInt(p[1].trim());
            return h > a ? "1" : (h < a ? "2" : "X");
        } catch (NumberFormatException _) { return null; }
    }

    private static String signName(String s) {
        if (s == null) return "—";
        return switch (s) { case "1" -> "Ev sahibi"; case "2" -> "Deplasman"; default -> "Heç-heçə"; };
    }

    private static String safe(String s) { return s == null ? "-" : s; }

    public static void main(String[] args) {
        Bet365HundredMethodAnalyzer analyzer = new Bet365HundredMethodAnalyzer();
        analyzer.run();
        try { analyzer.conn.close(); } catch (Exception _) {}
    }
}
