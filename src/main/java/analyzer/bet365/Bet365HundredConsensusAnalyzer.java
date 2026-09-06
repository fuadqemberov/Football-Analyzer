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
 *  Bet365HundredConsensusAnalyzer — 1 OYUN üçün 100 ANALİZİN ORTAQ, ƏN GÜVƏNLİ TƏXMİNİ
 * ═══════════════════════════════════════════════════════════════════════════
 *  {@link Bet365HundredMethodAnalyzer}-in kopyasıdır. Fərqi:
 *   • 100 yöntemin tapdığı BÜTÜN twin oyunlar bir oyun üçün birləşdirilir (havuz).
 *   • Bu havuz üzərində HƏR market növü hesablanır:
 *       – Tərəf (MS 1/X/2), İkili şans (1X/12/X2)
 *       – MS A/U 0.5 / 1.5 / 2.5 / 3.5 / 4.5 (Üst & Alt)
 *       – İY (HT) A/U 0.5 / 1.5 / 2.5 (Üst & Alt)  ← "HT 0.5 üst" burada
 *       – 2Y A/U 0.5 / 1.5 / 2.5 (Üst & Alt)
 *       – Qarşılıqlı Qol (KG/BTS) Var/Yox, İY KG Var/Yox
 *       – İY tərəf (1/X/2), HT/FT (modal), MS Skor (modal)
 *   • Hər təxminin güvəni = havuzdakı twinlərin neçə faizinin o nəticə ilə bitməsidir.
 *   • EKRANA yalnız ƏN GÜVƏNLİ təxmin çıxır (≥ 99% olduqda "🔒 99% GÜVƏNLİ" işarəsi).
 *   • Güvəni 96%-dən aşağı olan oyunlar tamamilə ignore olunur, ekrana çıxmır.
 *   • 50-dən az yöntem uyğun gələn oyunlar da ignore olunur (yalnız 50+ yöntem).
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class Bet365HundredConsensusAnalyzer {

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
    private static final double CONFIDENCE_THRESHOLD = 99.0; // "99% güvənli"
    private static final double MIN_DISPLAY_CONFIDENCE = 96.0; // ən güvənli təxmin bundan aşağıdırsa oyun çap olunmur
    private static final int MIN_DISPLAY_TWINS = 15;         // havuzda bundan az twin oyun varsa güvənilir sayılmır, çap olunmur
    private static final int MIN_METHODS_AGREEING = 50;      // 50-dən az yöntem uyğun gəlirsə oyun ignore olunur
    private static final double MIN_OTHER_PICK_CONFIDENCE = 90.0; // "Digər güclü təxminlər" siyahısında minimum güvən
    private static final int MIN_POOL_TWINS = 10;            // güvən mənalı olsun deyə min. twin sayı

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
            chosen.sort(Comparator.comparingInt(Bet365HundredConsensusAnalyzer::familyRank)
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

    public Bet365HundredConsensusAnalyzer() {
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
    // Havuzdakı hər twin oyunun qolları üzərində qiymətləndirilir.
    static final class Pick {
        final String label;      // ekrana çıxacaq təxmin
        final int hit, total;    // neçə twin bu nəticə ilə bitib / etibarlı twin sayı
        Pick(String label, int hit, int total) { this.label = label; this.hit = hit; this.total = total; }
        double conf() { return total == 0 ? 0 : 100.0 * hit / total; }
    }

    private boolean ftValid(int r) { return rFtH[r] >= 0 && rFtA[r] >= 0; }
    private boolean htValid(int r) { return rHtH[r] >= 0 && rHtA[r] >= 0; }
    private boolean shValid(int r) { return ftValid(r) && htValid(r); }
    private int ftTot(int r) { return rFtH[r] + rFtA[r]; }
    private int htTot(int r) { return rHtH[r] + rHtA[r]; }
    private int shTot(int r) { return (rFtH[r] - rHtH[r]) + (rFtA[r] - rHtA[r]); }

    /** Havuz (pooled twin sətirləri) üzərində bütün marketlərin güvənini hesablayır. */
    private List<Pick> evaluatePicks(int[] pool) {
        List<Pick> picks = new ArrayList<>();

        // ── MS tərəf ──
        addPred(picks, pool, "Tərəf: MS 1 (Ev sahibi qalib)", r -> rFtH[r] > rFtA[r], this::ftValid);
        addPred(picks, pool, "Tərəf: MS X (Heç-heçə)",        r -> rFtH[r] == rFtA[r], this::ftValid);
        addPred(picks, pool, "Tərəf: MS 2 (Deplasman qalib)", r -> rFtH[r] < rFtA[r], this::ftValid);
        // ── MS tam qol A/U ── (MS 0.5 ÜST çıxarıldı: həmişə ~99% olan trivial variant)
        for (int line : new int[]{0,1,2,3,4}) {
            double L = line + 0.5;
            int t = line;
            if (line != 0)
                addPred(picks, pool, "MS A/U " + L + " ÜST (" + (t+1) + "+ qol)", r -> ftTot(r) > t, this::ftValid);
            addPred(picks, pool, "MS A/U " + L + " ALT (" + t + " və ya az qol)", r -> ftTot(r) <= t, this::ftValid);
        }
        // ── İY (HT) qol A/U — "HT 0.5 üst" burada ──
        for (int line : new int[]{0,1,2}) {
            double L = line + 0.5;
            int t = line;
            addPred(picks, pool, "İY (HT) A/U " + L + " ÜST", r -> htTot(r) > t, this::htValid);
            addPred(picks, pool, "İY (HT) A/U " + L + " ALT", r -> htTot(r) <= t, this::htValid);
        }
        // ── 2Y qol A/U ──
        for (int line : new int[]{0,1,2}) {
            double L = line + 0.5;
            int t = line;
            addPred(picks, pool, "2Y A/U " + L + " ÜST", r -> shTot(r) > t, this::shValid);
            addPred(picks, pool, "2Y A/U " + L + " ALT", r -> shTot(r) <= t, this::shValid);
        }
        // ── Qarşılıqlı qol (KG/BTS) ──
        addPred(picks, pool, "KG (Hər iki komanda qol) VAR", r -> rFtH[r] > 0 && rFtA[r] > 0, this::ftValid);
        addPred(picks, pool, "KG (Hər iki komanda qol) YOX", r -> rFtH[r] == 0 || rFtA[r] == 0, this::ftValid);
        addPred(picks, pool, "İY KG VAR", r -> rHtH[r] > 0 && rHtA[r] > 0, this::htValid);
        addPred(picks, pool, "İY KG YOX", r -> rHtH[r] == 0 || rHtA[r] == 0, this::htValid);
        // ── İY tərəf ──
        addPred(picks, pool, "İY tərəf: 1", r -> rHtH[r] > rHtA[r], this::htValid);
        addPred(picks, pool, "İY tərəf: X", r -> rHtH[r] == rHtA[r], this::htValid);
        addPred(picks, pool, "İY tərəf: 2", r -> rHtH[r] < rHtA[r], this::htValid);

        // ── HT/FT (ən çox təkrarlanan) ──
        Pick htft = modalLabelPick(pool, "HT/FT", r -> shValid(r)
                ? sign(rHtH[r], rHtA[r]) + "/" + sign(rFtH[r], rFtA[r]) : null);
        if (htft != null) picks.add(htft);
        // ── MS Skor (ən çox təkrarlanan) ──
        Pick cs = modalLabelPick(pool, "MS Skor", r -> ftValid(r) ? rFtH[r] + "-" + rFtA[r] : null);
        if (cs != null) picks.add(cs);

        return picks;
    }

    private void addPred(List<Pick> picks, int[] pool, String label, Predicate<Integer> hit, Predicate<Integer> valid) {
        int h = 0, t = 0;
        for (int r : pool) {
            if (!valid.test(r)) continue;
            t++;
            if (hit.test(r)) h++;
        }
        if (t >= MIN_POOL_TWINS) picks.add(new Pick(label, h, t));
    }

    private Pick modalLabelPick(int[] pool, String prefix, java.util.function.IntFunction<String> labelOf) {
        Map<String, Integer> freq = new HashMap<>();
        int total = 0;
        for (int r : pool) {
            String l = labelOf.apply(r);
            if (l == null) continue;
            freq.merge(l, 1, Integer::sum);
            total++;
        }
        if (total < MIN_POOL_TWINS || freq.isEmpty()) return null;
        Map.Entry<String, Integer> best = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
        return new Pick(prefix + " " + best.getKey(), best.getValue(), total);
    }

    private static String sign(int h, int a) { return h > a ? "1" : (h < a ? "2" : "X"); }

    // ==================== ANA ÇALIŞTIRICI ====================
    public void run() {
        List<Method> methods = buildMethods();
        System.out.println("🧪 " + methods.size() + " fərqli yöntem hazırlandı.\n");

        List<MatchInfo> today = scrapeTodayMatches();
        if (today.isEmpty()) { System.out.println("❌ Bugünkü maç bulunamadı!"); return; }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("🤖 100 ANALİZİN ORTAQ ƏN GÜVƏNLİ TƏXMİNİ — %d oyun  (baza: %,d tarixi oyun)%n",
                today.size(), rowCount);
        System.out.println("═══════════════════════════════════════════════════════════════\n");

        int printed = 0;
        for (MatchInfo match : today) {
            // 100 yöntemin tapdığı bütün twinləri bir havuza yığ (paralel)
            ConcurrentLinkedQueue<int[]> hitQ = new ConcurrentLinkedQueue<>();
            methods.parallelStream().forEach(m -> {
                int[] twins = applyMethod(match, m);
                if (twins.length >= TWIN_MIN && twins.length <= TWIN_MAX) hitQ.add(twins);
            });
            if (hitQ.isEmpty()) continue;

            int methodsAgreeing = hitQ.size();
            if (methodsAgreeing < MIN_METHODS_AGREEING) continue; // 50-dən az yöntem uyğun gəldi → ekrana çıxmır
            int[] pool = flatten(hitQ);
            if (pool.length < MIN_DISPLAY_TWINS) continue; // 15-dən az twin oyun → güvənilir deyil, çap olunmur
            List<Pick> picks = evaluatePicks(pool);
            if (picks.isEmpty()) continue;

            // ən güvənlisi: əvvəl güvən faizi, bərabərlikdə daha böyük seçki bazası
            picks.sort(Comparator.<Pick>comparingDouble(Pick::conf).reversed()
                    .thenComparingInt(p -> p.total));
            Pick best = picks.get(0);
            if (best.conf() < MIN_DISPLAY_CONFIDENCE) continue; // 93%-dən aşağı oyunlar ekrana çıxmır

            printConsensus(match, best, picks, methodsAgreeing, pool.length);
            printed++;
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("✅ TAMAMLANDI — %d oyun üçün ortaq təxmin çıxarıldı.%n", printed);
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
                                int methodsAgreeing, int poolTwins) {
        boolean sure = best.conf() >= CONFIDENCE_THRESHOLD;
        System.out.println("╔══════════════════════════════════════════════════════════════");
        System.out.println("║ ⚽ OYUN: " + match.home + " vs " + match.away + "   [" + match.date + "]");
        System.out.printf("║ 📊 %d yöntem uyğun gəldi, cəmi %d twin oyun analiz olundu.%n",
                methodsAgreeing, poolTwins);
        System.out.println("╠══════════════════════════════════════════════════════════════");
        System.out.printf("║ %s ƏN GÜVƏNLİ TƏXMİN ➜ %s%n",
                sure ? "🔒 99%+ GÜVƏNLİ" : "⭐ ƏN GÜVƏNLİ (96–99%)", best.label);
        System.out.printf("║    Güvən: %.1f%%  (%d/%d twin bu nəticə ilə bitib)%n",
                best.conf(), best.hit, best.total);
        int shown = 0;
        for (int i = 1; i < picks.size() && shown < 5; i++) {
            Pick p = picks.get(i);
            if (p.conf() < MIN_OTHER_PICK_CONFIDENCE) continue; // 90%-dən aşağı təxminlər göstərilmir
            if (shown == 0) {
                System.out.println("╠──────────────────────────────────────────────────────────────");
                System.out.println("║ Digər güclü təxminlər:");
            }
            System.out.printf("║    %2d) %-34s  %.1f%% (%d/%d)%n",
                    shown + 1, p.label, p.conf(), p.hit, p.total);
            shown++;
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
        Bet365HundredConsensusAnalyzer analyzer = new Bet365HundredConsensusAnalyzer();
        analyzer.run();
        try { analyzer.conn.close(); } catch (Exception ignored) {}
    }
}
