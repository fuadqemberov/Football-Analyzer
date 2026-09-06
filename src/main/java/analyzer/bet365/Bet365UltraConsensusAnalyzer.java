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
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  Bet365UltraConsensusAnalyzer — MÖHTƏŞƏM OYUN DETEKTORU
 * ═══════════════════════════════════════════════════════════════════════════
 *  {@link Bet365BttsOverUnderConsensusAnalyzer}-in təkmilləşdirilmiş kopyasıdır.
 *
 *  YENİ TƏXMİNLƏR (əvvəlki 4 marketə əlavə):
 *      • MS 1 / MS X / MS 2          (Full Time 1x2)
 *      • İY 1 / İY X / İY 2          (1st Half 1x2)
 *      • 2Y 1 / 2Y X / 2Y 2          (2nd Half 1x2)
 *      • İY 0.5 ALT, 2Y 0.5 ALT
 *
 *  YENİ ALQORİTMLƏR:
 *   1) WILSON AŞAĞI SƏRHƏDİ (95%): kiçik havuzda çıxan "yalançı 100%"-ləri
 *      cəzalandırır. 10/10 = 72.2% LB, amma 48/50 = 86.5% LB — böyük nümunə
 *      həmişə üstün tutulur.
 *   2) ÇƏKİLİ SƏSVERMƏ: eyni twin sətri neçə yöntem tərəfindən tapılıbsa,
 *      o qədər çox "dəstək" çəkisi alır (loqarifmik dampinq ilə). Yəni
 *      100 yöntemin ortaq tapdığı oyun, 1 yöntemin təsadüfən tapdığından güclüdür.
 *   3) TƏZƏLİK ÇƏKİSİ: bazadakı ən yeni oyunlar 1.5x, ən köhnələr 1.0x çəki
 *      alır — bahis bazarının son davranışı daha çox əks olunur.
 *   4) MÖHTƏŞƏMLİK SKORU (💎): Wilson LB + çəkili güvən + unikal twin sayı +
 *      yöntem razılığından hesablanan 0–100 kompozit skor. Skor həddi keçəndə
 *      oyun "💎 MÖHTƏŞƏM OYUN" bannerı ilə çap olunur, 1–5 ulduz reytinqi verilir.
 *   5) UNİKAL TWIN FİLTRİ: havuzdakı təkrarlar çıxılmaqla ən azı
 *      MIN_DISTINCT_TWINS fərqli tarixi oyun tələb olunur.
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class Bet365UltraConsensusAnalyzer {

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

    // ==================== YÖNTEM ÜÇÜN SİQNAL HOVUZU ====================
    private static final String[] SIGNAL_POOL = {
            "MS 1","MS X","MS 2","İY 1","İY X","İY 2","2Y 1","2Y X","2Y 2",
            "ÇŞ 1X","ÇŞ 12","ÇŞ X2","İY ÇŞ 1X","İY ÇŞ 12","İY ÇŞ X2",
            "A/U 1.5 Üst","A/U 1.5 Alt","A/U 2.5 Üst","A/U 2.5 Alt","A/U 3.5 Üst","A/U 3.5 Alt",
            "İY A/U 0.5 Üst","İY A/U 0.5 Alt","İY A/U 1.5 Üst","İY A/U 1.5 Alt",
            "2Y A/U 1.5 Üst","2Y A/U 1.5 Alt",
            "KG Evet","KG Hayır","İY KG Evet","İY KG Hayır",
            "HT/FT 1/1","HT/FT X/X","HT/FT 2/2","HT/FT 1/X","HT/FT X/1",
            "HT/FT 2/X","HT/FT X/2","HT/FT 1/2","HT/FT 2/1",
            "MS Skor 1:0","MS Skor 2:0","MS Skor 2:1","MS Skor 1:1","MS Skor 0:0",
            "MS Skor 0:1","MS Skor 0:2","MS Skor 1:2",
            "İY Skor 0:0","İY Skor 1:0","İY Skor 0:1","İY Skor 1:1"
    };

    private static int familyRank(String display) {
        if (display.startsWith("HT/FT")) return 4;
        if (display.contains("Skor")) return 5;
        if (display.startsWith("KG") || display.contains("KG ")) return 3;
        if (display.contains("A/U")) return 2;
        if (display.startsWith("ÇŞ") || display.contains("ÇŞ ")) return 1;
        return 0;
    }

    static final class Method {
        final int number;
        final ColumnDef[] cols;
        Method(int number, ColumnDef[] cols) { this.number = number; this.cols = cols; }
    }

    private static final int METHOD_COUNT = 1000;
    private static final int TWIN_MIN = 1;
    private static final int TWIN_MAX = 3;
    private static final double CONFIDENCE_THRESHOLD = 80.0;      // "yüksək güvən" bannerı
    private static final double MIN_DISPLAY_CONFIDENCE = 75.0;    // ən güvənli təxmin bundan aşağıdırsa oyun çap olunmur
    private static final int MIN_DISPLAY_TWINS = 15;              // havuz (təkrarlı) minimum
    private static final int MIN_DISTINCT_TWINS = 10;             // YENİ: unikal twin oyun minimumu
    private static final int MIN_METHODS_AGREEING = 50;           // az yöntem uyğun gəlirsə ignore
    private static final double MIN_OTHER_PICK_CONFIDENCE = 70.0; // "Digər güclü təxminlər" minimum güvən
    private static final int MIN_POOL_TWINS = 10;                 // pick üçün minimum etibarlı twin

    // 💎 Möhtəşəm oyun meyarları
    private static final double GEM_SCORE_THRESHOLD = 85.0;       // kompozit skor həddi
    private static final int GEM_MIN_DISTINCT = 25;               // ən azı bu qədər unikal twin
    private static final int GEM_MIN_METHODS = 120;               // ən azı bu qədər yöntem razılığı

    private static List<Method> buildMethods() {
        Random rng = new Random(365_100L);
        List<Method> methods = new ArrayList<>(METHOD_COUNT);
        Set<String> seen = new HashSet<>();
        List<String> pool = new ArrayList<>(Arrays.asList(SIGNAL_POOL));
        int guard = 0;
        while (methods.size() < METHOD_COUNT && guard++ < METHOD_COUNT * 50) {
            int len = 4 + rng.nextInt(4);
            Collections.shuffle(pool, rng);
            List<String> chosen = new ArrayList<>(pool.subList(0, len));
            chosen.sort(Comparator.comparingInt(Bet365UltraConsensusAnalyzer::familyRank)
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
    private final Map<String, float[]> oddsColumns = new HashMap<>();
    private String[] rDate, rHome, rAway;
    private int[] rHtH, rHtA, rFtH, rFtA; // parse olunmuş qollar (-1 = naməlum)
    private int rowCount;
    private final Map<String, Map<Integer, int[]>> colIndex = new HashMap<>();

    private Connection conn;

    public Bet365UltraConsensusAnalyzer() {
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

    private void loadTableIntoMemory() throws SQLException {
        long t0 = System.currentTimeMillis();
        System.out.println("⏳ bet365_matches belleğe yükleniyor...");

        Set<String> existing = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, "bet365_matches", null)) {
            while (rs.next()) existing.add(rs.getString("COLUMN_NAME"));
        }
        List<String> colList = ALL_COLS.stream()
                .map(c -> c.sqlColumn).filter(existing::contains).distinct().collect(Collectors.toList());

        int cap = (int) sqlRowCount() + 1000;
        rDate = new String[cap]; rHome = new String[cap]; rAway = new String[cap];
        rHtH = new int[cap]; rHtA = new int[cap]; rFtH = new int[cap]; rFtA = new int[cap];
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
                    int[] ht = parseScore(rs.getString(4));
                    int[] ft = parseScore(rs.getString(5));
                    rHtH[r] = ht[0]; rHtA[r] = ht[1];
                    rFtH[r] = ft[0]; rFtA[r] = ft[1];
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
                byVal.put(e.getKey(), arr);
            }
            colIndex.put(col, byVal);
        }
        System.out.printf("✅ İndeks hazır (%.1f sn)%n%n", (System.currentTimeMillis() - t0) / 1000.0);
    }

    // ==================== SCRAPER ====================
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
                    .click(new Locator.ClickOptions().setTimeout(3000)); } catch (Exception ignored) {}
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
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("❌ Scraper hatası: " + e.getMessage());
        }

        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (MatchInfo mi : matches) futures.add(pool.submit(() -> fetchOddsForMatch(mi)));
        for (Future<?> f : futures) { try { f.get(); } catch (Exception ignored) {} }
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
        } catch (Exception ignored) {}
    }

    private void parseOdds(MatchInfo mi, String jsonBody) {
        JSONObject root = new JSONObject(jsonBody);
        JSONObject data = root.optJSONObject("data");
        if (data == null) return;
        JSONObject oddsData = data.optJSONObject("findOddsByEventId");
        if (oddsData == null) return;
        JSONArray oddsList = oddsData.optJSONArray("odds");
        if (oddsList == null) return;

        String homePartId = null;
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
        } catch (Exception ignored) {}
        return "-";
    }

    // ==================== ARDICIL SÜZGƏC ====================
    private int[] applyMethod(MatchInfo match, Method method) {
        int[] current = null;
        for (ColumnDef col : method.cols) {
            String raw = match.odds.get(col.flashscoreKey);
            if (raw == null || raw.isEmpty() || "-".equals(raw)) continue;
            float value;
            try { value = Float.parseFloat(raw.replace(',', '.')); }
            catch (NumberFormatException ignored) { continue; }

            Map<Integer, int[]> byVal = colIndex.get(col.sqlColumn);
            if (byVal == null) continue;
            int[] bucket = byVal.get(Float.floatToIntBits(value));
            if (bucket == null || bucket.length == 0) continue;

            if (current == null) {
                current = bucket;
            } else {
                int[] next = intersect(current, bucket);
                if (next.length == 0) continue;
                current = next;
            }
            if (current.length <= TWIN_MAX) break;
        }
        return current == null ? new int[0] : current;
    }

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

    // ==================== MARKET (TƏXMİN) TƏYİNLERİ ====================
    static final class Pick {
        final String label;        // ekrana çıxacaq təxmin
        final int hit, total;      // unikal twin sayı ilə: uğur / etibarlı
        final double weightedConf; // dəstək+təzəlik çəkili güvən (%)
        final double wilson;       // Wilson 95% aşağı sərhədi (%)
        Pick(String label, int hit, int total, double weightedConf, double wilson) {
            this.label = label; this.hit = hit; this.total = total;
            this.weightedConf = weightedConf; this.wilson = wilson;
        }
        double conf() { return total == 0 ? 0 : 100.0 * hit / total; }
        /** Kompozit skor: yarısı Wilson LB (nümunə böyüklüyünə həssas), yarısı çəkili güvən. */
        double score() { return 0.5 * wilson + 0.5 * weightedConf; }
    }

    private boolean ftValid(int r) { return rFtH[r] >= 0 && rFtA[r] >= 0; }
    private boolean htValid(int r) { return rHtH[r] >= 0 && rHtA[r] >= 0; }
    private boolean shValid(int r) { return ftValid(r) && htValid(r); }
    private int ftTot(int r) { return rFtH[r] + rFtA[r]; }
    private int shH(int r) { return rFtH[r] - rHtH[r]; }
    private int shA(int r) { return rFtA[r] - rHtA[r]; }
    private int shTot(int r) { return shH(r) + shA(r); }

    /** Wilson 95% aşağı sərhədi — kiçik nümunədə şişirdilmiş faizləri cəzalandırır. */
    private static double wilsonLB(int hit, int total) {
        if (total == 0) return 0;
        double z = 1.96, p = (double) hit / total;
        double denom = 1 + z * z / total;
        double centre = p + z * z / (2 * total);
        double margin = z * Math.sqrt((p * (1 - p) + z * z / (4 * total)) / total);
        return Math.max(0, 100.0 * (centre - margin) / denom);
    }

    /** Təzəlik çəkisi: sətirlər tarixə görə DESC sıralanıb → ən yeni 1.5x, ən köhnə 1.0x. */
    private double recencyWeight(int r) {
        return rowCount <= 1 ? 1.0 : 1.5 - 0.5 * ((double) r / (rowCount - 1));
    }

    /**
     * Havuz üzərində bütün marketlərin güvənini hesablayır.
     * support = hər unikal twin sətrini neçə yöntemin tapdığı (çəkili səsvermə).
     */
    private List<Pick> evaluatePicks(int[] pool) {
        // unikal sətir → dəstək sayı
        Map<Integer, Integer> support = new HashMap<>();
        for (int r : pool) support.merge(r, 1, Integer::sum);

        List<Pick> picks = new ArrayList<>();

        // ── Full Time 1x2 (YENİ) ──
        addPred(picks, support, "MS 1 (Ev qalibi)",     this::ftValid, r -> rFtH[r] > rFtA[r]);
        addPred(picks, support, "MS X (Bərabərlik)",    this::ftValid, r -> rFtH[r] == rFtA[r]);
        addPred(picks, support, "MS 2 (Qonaq qalibi)",  this::ftValid, r -> rFtH[r] < rFtA[r]);

        // ── 1st Half 1x2 (YENİ) ──
        addPred(picks, support, "İY 1 (İlk yarı ev)",    this::htValid, r -> rHtH[r] > rHtA[r]);
        addPred(picks, support, "İY X (İlk yarı bərabər)", this::htValid, r -> rHtH[r] == rHtA[r]);
        addPred(picks, support, "İY 2 (İlk yarı qonaq)", this::htValid, r -> rHtH[r] < rHtA[r]);

        // ── 2nd Half 1x2 (YENİ) ──
        addPred(picks, support, "2Y 1 (İkinci yarı ev)",    this::shValid, r -> shH(r) > shA(r));
        addPred(picks, support, "2Y X (İkinci yarı bərabər)", this::shValid, r -> shH(r) == shA(r));
        addPred(picks, support, "2Y 2 (İkinci yarı qonaq)", this::shValid, r -> shH(r) < shA(r));

        // ── Full Time — Qarşılıqlı qol (BTTS) ──
        addPred(picks, support, "FT KG VAR (BTTS Yes)", this::ftValid, r -> rFtH[r] > 0 && rFtA[r] > 0);
        addPred(picks, support, "FT KG YOX (BTTS No)",  this::ftValid, r -> rFtH[r] == 0 || rFtA[r] == 0);

        // ── Full Time — A/U xətləri ──
        addPred(picks, support, "FT 2.5 ÜST (3+ qol)",  this::ftValid, r -> ftTot(r) > 2);
        addPred(picks, support, "FT 2.5 ALT (0-2 qol)", this::ftValid, r -> ftTot(r) <= 2);

        // ── Yarı A/U 0.5 ALT ──
        addPred(picks, support, "İY 0.5 ALT (ilk yarı 0-0)",    this::htValid, r -> rHtH[r] + rHtA[r] == 0);
        addPred(picks, support, "2Y 0.5 ALT (ikinci yarı 0-0)", this::shValid, r -> shTot(r) == 0);

        return picks;
    }

    private void addPred(List<Pick> picks, Map<Integer, Integer> support,
                         String label, Predicate<Integer> valid, Predicate<Integer> hit) {
        int h = 0, t = 0;
        double wh = 0, wt = 0;
        for (Map.Entry<Integer, Integer> e : support.entrySet()) {
            int r = e.getKey();
            if (!valid.test(r)) continue;
            // çəki = təzəlik × (1 + ln(dəstək)) — çox yöntemin tapdığı twin güclüdür,
            // amma loqarifm tək bir sətrin havuzu ələ keçirməsinin qarşısını alır
            double w = recencyWeight(r) * (1.0 + Math.log(e.getValue()));
            t++; wt += w;
            if (hit.test(r)) { h++; wh += w; }
        }
        if (t < MIN_POOL_TWINS) return;
        double weightedConf = wt == 0 ? 0 : 100.0 * wh / wt;
        picks.add(new Pick(label, h, t, weightedConf, wilsonLB(h, t)));
    }

    // ==================== MÖHTƏŞƏMLİK SKORU ====================
    /** 0–100 kompozit: pick skoru + havuz böyüklüyü bonusu + yöntem razılığı bonusu. */
    private double gemScore(Pick best, int distinctTwins, int methodsAgreeing) {
        double base = best.score();                                     // 0–100
        double sizeBonus = Math.min(1.0, distinctTwins / 50.0) * 5.0;   // 0–5
        double methodBonus = Math.min(1.0, methodsAgreeing / (double) METHOD_COUNT) * 5.0; // 0–5
        return Math.min(100.0, base * 0.90 + sizeBonus + methodBonus);
    }

    private static String stars(double score) {
        int n = score >= 92 ? 5 : score >= 87 ? 4 : score >= 82 ? 3 : score >= 78 ? 2 : 1;
        return "★".repeat(n) + "☆".repeat(5 - n);
    }

    // ==================== ANA ÇALIŞTIRICI ====================
    public void run() {
        List<Method> methods = buildMethods();
        System.out.println("🧪 " + methods.size() + " fərqli yöntem hazırlandı.\n");

        List<MatchInfo> today = scrapeTodayMatches();
        if (today.isEmpty()) { System.out.println("❌ Bugünkü maç bulunamadı!"); return; }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("🤖 ULTRA KONSENSUS — 1x2 + KG + A/U — %d oyun  (baza: %,d tarixi oyun)%n",
                today.size(), rowCount);
        System.out.println("═══════════════════════════════════════════════════════════════\n");

        int printed = 0, gems = 0;
        for (MatchInfo match : today) {
            // yöntemlerin tapdığı bütün twinləri bir havuza yığ (paralel)
            ConcurrentLinkedQueue<int[]> hitQ = new ConcurrentLinkedQueue<>();
            methods.parallelStream().forEach(m -> {
                int[] twins = applyMethod(match, m);
                if (twins.length >= TWIN_MIN && twins.length <= TWIN_MAX) hitQ.add(twins);
            });
            if (hitQ.isEmpty()) continue;

            int methodsAgreeing = hitQ.size();
            if (methodsAgreeing < MIN_METHODS_AGREEING) continue;
            int[] pool = flatten(hitQ);
            if (pool.length < MIN_DISPLAY_TWINS) continue;

            int distinctTwins = (int) Arrays.stream(pool).distinct().count();
            if (distinctTwins < MIN_DISTINCT_TWINS) continue; // YENİ: unikal twin filtri

            List<Pick> picks = evaluatePicks(pool);
            if (picks.isEmpty()) continue;

            // sıralama: kompozit skor (Wilson LB + çəkili güvən), bərabərlikdə böyük nümunə
            picks.sort(Comparator.<Pick>comparingDouble(Pick::score).reversed()
                    .thenComparing(Comparator.<Pick>comparingInt(p -> p.total).reversed()));
            Pick best = picks.get(0);
            if (best.conf() < MIN_DISPLAY_CONFIDENCE) continue;

            double gScore = gemScore(best, distinctTwins, methodsAgreeing);
            boolean isGem = gScore >= GEM_SCORE_THRESHOLD
                    && distinctTwins >= GEM_MIN_DISTINCT
                    && methodsAgreeing >= GEM_MIN_METHODS;
            if (isGem) gems++;

            printConsensus(match, best, picks, methodsAgreeing, pool.length, distinctTwins, gScore, isGem);
            printed++;
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("✅ TAMAMLANDI — %d oyun çap olundu, onlardan %d-i 💎 MÖHTƏŞƏM.%n", printed, gems);
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    private int[] flatten(ConcurrentLinkedQueue<int[]> q) {
        int size = 0;
        for (int[] a : q) size += a.length;
        int[] out = new int[size];
        int k = 0;
        for (int[] a : q) for (int v : a) out[k++] = v;
        return out;
    }

    // ==================== ÇAP ====================
    private void printConsensus(MatchInfo match, Pick best, List<Pick> picks,
                                int methodsAgreeing, int poolTwins, int distinctTwins,
                                double gScore, boolean isGem) {
        boolean sure = best.conf() >= CONFIDENCE_THRESHOLD;
        System.out.println("╔══════════════════════════════════════════════════════════════");
        if (isGem) {
            System.out.println("║ 💎💎💎  M Ö H T Ə Ş Ə M   O Y U N  💎💎💎");
            System.out.println("╠══════════════════════════════════════════════════════════════");
        }
        System.out.println("║ ⚽ OYUN: " + match.home + " vs " + match.away + "   [" + match.date + "]");
        System.out.printf("║ 📊 %d yöntem uyğun gəldi | havuz: %d twin (%d unikal)%n",
                methodsAgreeing, poolTwins, distinctTwins);
        System.out.printf("║ 🏆 Möhtəşəmlik skoru: %.1f/100   %s%n", gScore, stars(gScore));
        System.out.println("╠══════════════════════════════════════════════════════════════");
        System.out.printf("║ %s ƏN GÜVƏNLİ TƏXMİN ➜ %s%n",
                sure ? "🔒 YÜKSƏK GÜVƏN" : "⭐ ƏN GÜVƏNLİ", best.label);
        System.out.printf("║    Güvən: %.1f%% (%d/%d unikal twin) | Çəkili: %.1f%% | Wilson LB: %.1f%%%n",
                best.conf(), best.hit, best.total, best.weightedConf, best.wilson);
        int shown = 0;
        for (int i = 1; i < picks.size(); i++) {
            Pick p = picks.get(i);
            if (p.conf() < MIN_OTHER_PICK_CONFIDENCE) continue;
            if (shown == 0) {
                System.out.println("╠──────────────────────────────────────────────────────────────");
                System.out.println("║ Digər güclü təxminlər:            güvən   çəkili  WilsonLB");
            }
            System.out.printf("║    %2d) %-32s %5.1f%%  %5.1f%%  %5.1f%%  (%d/%d)%n",
                    shown + 1, p.label, p.conf(), p.weightedConf, p.wilson, p.hit, p.total);
            shown++;
            if (shown >= 8) break; // ekranı boğmamaq üçün ən yaxşı 8 əlavə təxmin
        }
        System.out.println("╚══════════════════════════════════════════════════════════════\n");
    }

    // ==================== YARDIMÇILAR ====================
    private static int[] parseScore(String score) {
        if (score == null) return new int[]{-1, -1};
        String[] p = score.trim().split("[-:]");
        if (p.length != 2) return new int[]{-1, -1};
        try {
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (NumberFormatException ignored) { return new int[]{-1, -1}; }
    }

    public static void main(String[] args) {
        Bet365UltraConsensusAnalyzer analyzer = new Bet365UltraConsensusAnalyzer();
        analyzer.run();
        try { analyzer.conn.close(); } catch (Exception ignored) {}
    }
}
