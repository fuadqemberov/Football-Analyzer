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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Bet365DailyAutoAnalyzer {

    // ==================== KOLON TANIMLARI ====================
    static class ColumnDef {
        String sqlColumn;
        String displayName;
        String flashscoreKey;

        ColumnDef(String sqlColumn, String displayName, String flashscoreKey) {
            this.sqlColumn = sqlColumn;
            this.displayName = displayName;
            this.flashscoreKey = flashscoreKey;
        }
    }

    // ---------- TÜM KOLONLAR ----------
    private static final List<ColumnDef> ALL_COLS = new ArrayList<>();

    static {
        // 1x2
        ALL_COLS.add(new ColumnDef("ft_1_a", "MS 1", "1x2|Full Time|Home"));
        ALL_COLS.add(new ColumnDef("ft_x_a", "MS X", "1x2|Full Time|Draw"));
        ALL_COLS.add(new ColumnDef("ft_2_a", "MS 2", "1x2|Full Time|Away"));
        ALL_COLS.add(new ColumnDef("first_1_a", "İY 1", "1x2|1st Half|Home"));
        ALL_COLS.add(new ColumnDef("first_x_a", "İY X", "1x2|1st Half|Draw"));
        ALL_COLS.add(new ColumnDef("first_2_a", "İY 2", "1x2|1st Half|Away"));
        ALL_COLS.add(new ColumnDef("second_1_a", "2Y 1", "1x2|2nd Half|Home"));
        ALL_COLS.add(new ColumnDef("second_x_a", "2Y X", "1x2|2nd Half|Draw"));
        ALL_COLS.add(new ColumnDef("second_2_a", "2Y 2", "1x2|2nd Half|Away"));

        // Both teams to score
        ALL_COLS.add(new ColumnDef("bts_ft_yes_a", "KG Evet", "Both teams|Full Time|Yes"));
        ALL_COLS.add(new ColumnDef("bts_ft_no_a", "KG Hayır", "Both teams|Full Time|No"));
        ALL_COLS.add(new ColumnDef("bts_first_yes_a", "İY KG Evet", "Both teams|1st Half|Yes"));
        ALL_COLS.add(new ColumnDef("bts_first_no_a", "İY KG Hayır", "Both teams|1st Half|No"));
        ALL_COLS.add(new ColumnDef("bts_second_yes_a", "2Y KG Evet", "Both teams|2nd Half|Yes"));
        ALL_COLS.add(new ColumnDef("bts_second_no_a", "2Y KG Hayır", "Both teams|2nd Half|No"));

        // Double chance
        ALL_COLS.add(new ColumnDef("dbc_ft_1x_a", "ÇŞ 1X", "Double chance|Full Time|1X"));
        ALL_COLS.add(new ColumnDef("dbc_ft_12_a", "ÇŞ 12", "Double chance|Full Time|12"));
        ALL_COLS.add(new ColumnDef("dbc_ft_x2_a", "ÇŞ X2", "Double chance|Full Time|X2"));
        ALL_COLS.add(new ColumnDef("dbc_first_1x_a", "İY ÇŞ 1X", "Double chance|1st Half|1X"));
        ALL_COLS.add(new ColumnDef("dbc_first_12_a", "İY ÇŞ 12", "Double chance|1st Half|12"));
        ALL_COLS.add(new ColumnDef("dbc_first_x2_a", "İY ÇŞ X2", "Double chance|1st Half|X2"));

        // Over/Under Full Time
        ALL_COLS.add(new ColumnDef("ft_0_5_over_a", "A/U 0.5 Üst", "Over/Under|Full Time|O 0.5"));
        ALL_COLS.add(new ColumnDef("ft_0_5_under_a", "A/U 0.5 Alt", "Over/Under|Full Time|U 0.5"));
        ALL_COLS.add(new ColumnDef("ft_1_5_over_a", "A/U 1.5 Üst", "Over/Under|Full Time|O 1.5"));
        ALL_COLS.add(new ColumnDef("ft_1_5_under_a", "A/U 1.5 Alt", "Over/Under|Full Time|U 1.5"));
        ALL_COLS.add(new ColumnDef("ft_2_5_over_a", "A/U 2.5 Üst", "Over/Under|Full Time|O 2.5"));
        ALL_COLS.add(new ColumnDef("ft_2_5_under_a", "A/U 2.5 Alt", "Over/Under|Full Time|U 2.5"));
        ALL_COLS.add(new ColumnDef("ft_3_5_over_a", "A/U 3.5 Üst", "Over/Under|Full Time|O 3.5"));
        ALL_COLS.add(new ColumnDef("ft_3_5_under_a", "A/U 3.5 Alt", "Over/Under|Full Time|U 3.5"));
        ALL_COLS.add(new ColumnDef("ft_4_5_over_a", "A/U 4.5 Üst", "Over/Under|Full Time|O 4.5"));
        ALL_COLS.add(new ColumnDef("ft_4_5_under_a", "A/U 4.5 Alt", "Over/Under|Full Time|U 4.5"));
        ALL_COLS.add(new ColumnDef("ft_5_5_over_a", "A/U 5.5 Üst", "Over/Under|Full Time|O 5.5"));
        ALL_COLS.add(new ColumnDef("ft_5_5_under_a", "A/U 5.5 Alt", "Over/Under|Full Time|U 5.5"));

        // Over/Under 1st Half
        ALL_COLS.add(new ColumnDef("first_0_5_over_a", "İY A/U 0.5 Üst", "Over/Under|1st Half|O 0.5"));
        ALL_COLS.add(new ColumnDef("first_0_5_under_a", "İY A/U 0.5 Alt", "Over/Under|1st Half|U 0.5"));
        ALL_COLS.add(new ColumnDef("first_1_5_over_a", "İY A/U 1.5 Üst", "Over/Under|1st Half|O 1.5"));
        ALL_COLS.add(new ColumnDef("first_1_5_under_a", "İY A/U 1.5 Alt", "Over/Under|1st Half|U 1.5"));
        ALL_COLS.add(new ColumnDef("first_2_5_over_a", "İY A/U 2.5 Üst", "Over/Under|1st Half|O 2.5"));
        ALL_COLS.add(new ColumnDef("first_2_5_under_a", "İY A/U 2.5 Alt", "Over/Under|1st Half|U 2.5"));

        // Over/Under 2nd Half
        ALL_COLS.add(new ColumnDef("second_0_5_over_a", "2Y A/U 0.5 Üst", "Over/Under|2nd Half|O 0.5"));
        ALL_COLS.add(new ColumnDef("second_0_5_under_a", "2Y A/U 0.5 Alt", "Over/Under|2nd Half|U 0.5"));
        ALL_COLS.add(new ColumnDef("second_1_5_over_a", "2Y A/U 1.5 Üst", "Over/Under|2nd Half|O 1.5"));
        ALL_COLS.add(new ColumnDef("second_1_5_under_a", "2Y A/U 1.5 Alt", "Over/Under|2nd Half|U 1.5"));
        ALL_COLS.add(new ColumnDef("second_2_5_over_a", "2Y A/U 2.5 Üst", "Over/Under|2nd Half|O 2.5"));
        ALL_COLS.add(new ColumnDef("second_2_5_under_a", "2Y A/U 2.5 Alt", "Over/Under|2nd Half|U 2.5"));

        // HT/FT
        ALL_COLS.add(new ColumnDef("ht_ft_11_a", "HT/FT 1/1", "HTFT|1/1"));
        ALL_COLS.add(new ColumnDef("ht_ft_1x_a", "HT/FT 1/X", "HTFT|1/X"));
        ALL_COLS.add(new ColumnDef("ht_ft_12_a", "HT/FT 1/2", "HTFT|1/2"));
        ALL_COLS.add(new ColumnDef("ht_ft_x1_a", "HT/FT X/1", "HTFT|X/1"));
        ALL_COLS.add(new ColumnDef("ht_ft_xx_a", "HT/FT X/X", "HTFT|X/X"));
        ALL_COLS.add(new ColumnDef("ht_ft_x2_a", "HT/FT X/2", "HTFT|X/2"));
        ALL_COLS.add(new ColumnDef("ht_ft_21_a", "HT/FT 2/1", "HTFT|2/1"));
        ALL_COLS.add(new ColumnDef("ht_ft_2x_a", "HT/FT 2/X", "HTFT|2/X"));
        ALL_COLS.add(new ColumnDef("ht_ft_22_a", "HT/FT 2/2", "HTFT|2/2"));

        // Correct Score Full Time
        String[] ftScores = {"1:0","2:0","2:1","3:0","3:1","3:2","4:0","4:1","4:2","4:3","5:0","5:1","5:2",
                "0:0","1:1","2:2","3:3","4:4","0:1","0:2","1:2","0:3","1:3","2:3","0:4","1:4","2:4","3:4","0:5","1:5","2:5"};
        for (String sc : ftScores) {
            String sqlCol = "ft_score_" + sc.replace(":", "_") + "_a";
            String display = "MS Skor " + sc;
            ALL_COLS.add(new ColumnDef(sqlCol, display, "Correct score|Full Time|" + sc));
        }

        // Correct Score 1st Half
        String[] htScores = {"1:0","2:0","2:1","3:0","3:1","3:2","0:0","1:1","2:2","0:1","0:2","1:2","0:3","1:3","2:3"};
        for (String sc : htScores) {
            String sqlCol = "first_score_" + sc.replace(":", "_") + "_a";
            String display = "İY Skor " + sc;
            ALL_COLS.add(new ColumnDef(sqlCol, display, "Correct score|1st Half|" + sc));
        }

        // ---- EK SKORLAR (yeni filtre desenleri için gerekli) ----
        String[] extraFtScores = {"3:4", "1:4", "2:3", "2:4"};
        for (String sc : extraFtScores) {
            String sqlCol = "ft_score_" + sc.replace(":", "_") + "_a";
            String display = "MS Skor " + sc;
            boolean exists = ALL_COLS.stream().anyMatch(c -> c.displayName.equals(display));
            if (!exists) ALL_COLS.add(new ColumnDef(sqlCol, display, "Correct score|Full Time|" + sc));
        }
        String[] extraHtScores = {"0:2", "0:3", "2:2", "3:2"};
        for (String sc : extraHtScores) {
            String sqlCol = "first_score_" + sc.replace(":", "_") + "_a";
            String display = "İY Skor " + sc;
            boolean exists = ALL_COLS.stream().anyMatch(c -> c.displayName.equals(display));
            if (!exists) ALL_COLS.add(new ColumnDef(sqlCol, display, "Correct score|1st Half|" + sc));
        }
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private Connection conn;
    private List<String> sqlColumns = new ArrayList<>();

    // ---- Bellek içi tablo: tüm filtrelemeler SQL yerine RAM'de yapılır ----
    private final Map<String, float[]> oddsColumns = new HashMap<>(); // sql kolon -> değerler (NaN = NULL)
    private long[] rowIds;   // pkey (id) değerleri, date_time DESC sıralı
    private int rowCount;
    private String pkColumn;
    private int[] scratch;   // filterColumn için tekrar kullanılan tampon
    // Aynı desen listesi maç başına bir kez hesaplanır
    private final Map<String, int[]> patternResultCache = new HashMap<>();

    // ==================== VERİ MODELLERİ ====================
    static class MatchInfo {
        String id, home, away, date;
        String ftScore = "-";
        Map<String, String> odds = new HashMap<>();
    }

    static class MatchResult {
        Map<String, String> data = new HashMap<>();
    }

    // Bir twin oyunun istatistikte gösterilecek detayları
    static class TwinInfo {
        String date, home, away, ft, ht, htft;
    }

    // Bir yöntemin bir maç için bulduğu twin oyunların HT/FT sonuçları
    static class MethodVerdict {
        String methodName;
        List<String> htftOutcomes = new ArrayList<>(); // örn. "1/2", "2/1", "1/X" ...
        List<TwinInfo> twins = new ArrayList<>();
    }

    // Gün sonu istatistiği için: bugünkü maç -> onu bulan yöntemlerin kararları
    private final Map<MatchInfo, List<MethodVerdict>> matchVerdicts = new LinkedHashMap<>();

    // ==================== YAPILANDIRICI ====================
    public Bet365DailyAutoAnalyzer() {
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/postgres",
                    "postgres", "fuad123"
            );
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "bet365_matches", null)) {
                while (rs.next()) sqlColumns.add(rs.getString("COLUMN_NAME"));
            }
            System.out.println("✅ Veritabanına bağlandı. " + sqlColumns.size() + " kolon bulundu.");
            System.out.println("✅ SQL'de " + getSQLRowCount() + " kayıt var.\n");
            loadTableIntoMemory();
        } catch (Exception e) {
            System.err.println("❌ Veritabanı hatası: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private long getSQLRowCount() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM bet365_matches")) {
            if (rs.next()) return rs.getLong(1);
        }
        return 0;
    }

    // ==================== TABLOYU BELLEĞE YÜKLEME ====================
    // 771k+ satırlık tabloda indexsiz sorgular her seferinde tam tablo taraması yapıyordu.
    // Tüm oran kolonları bir kez float dizilerine yüklenir (~300MB); sonrası mikrosaniyelik tarama.
    private void loadTableIntoMemory() throws SQLException {
        long t0 = System.currentTimeMillis();
        System.out.println("⏳ bet365_matches belleğe yükleniyor...");

        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, "bet365_matches")) {
            if (rs.next()) pkColumn = rs.getString("COLUMN_NAME");
        }
        if (pkColumn == null) throw new SQLException("bet365_matches için primary key bulunamadı");

        LinkedHashSet<String> cols = new LinkedHashSet<>();
        for (ColumnDef c : ALL_COLS) if (sqlColumns.contains(c.sqlColumn)) cols.add(c.sqlColumn);
        List<String> colList = new ArrayList<>(cols);

        int capacity = (int) getSQLRowCount() + 1000; // yükleme sırasında eklenebilecek satırlar için pay
        rowIds = new long[capacity];
        float[][] data = new float[colList.size()][capacity];

        String sql = "SELECT " + pkColumn + ", " + String.join(", ", colList)
                + " FROM bet365_matches ORDER BY date_time DESC";
        conn.setAutoCommit(false); // fetchSize'ın etkili olması için gerekli
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(20000);
            try (ResultSet rs = ps.executeQuery()) {
                int r = 0;
                while (rs.next() && r < capacity) {
                    rowIds[r] = rs.getLong(1);
                    for (int i = 0; i < colList.size(); i++) {
                        float v = rs.getFloat(i + 2);
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
        scratch = new int[rowCount];

        System.out.printf("✅ Tablo belleğe yüklendi: %,d satır × %d kolon (%.1f sn)%n%n",
                rowCount, colList.size(), (System.currentTimeMillis() - t0) / 1000.0);
    }

    // ==================== SCRAPER ====================
    private List<MatchInfo> scrapeTodayMatches() {
        List<MatchInfo> matches = new ArrayList<>();
        System.out.println("🔍 Flashscore'dan bugünkü maçlar çekiliyor...\n");

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setHeadless(true));
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
                    String matchId = row.getAttribute("id").replace("g_1_", "");
                    String home = row.locator(".event__homeParticipant").innerText().trim();
                    String away = row.locator(".event__awayParticipant").innerText().trim();
                    MatchInfo mi = new MatchInfo();
                    mi.id = matchId;
                    mi.home = home;
                    mi.away = away;
                    mi.date = LocalDate.now().toString();
                    matches.add(mi);
                } catch (Exception ignored) {}
            }

            // Oranlar paralel çekilir; maç başına 2 HTTP isteğinin seri yapılması en büyük zaman kayıplarındandı
            ExecutorService pool = Executors.newFixedThreadPool(16);
            List<Future<?>> futures = new ArrayList<>();
            for (MatchInfo mi : matches) futures.add(pool.submit(() -> fetchOddsForMatch(mi)));
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
            pool.shutdown();
            System.out.println("✅ " + matches.size() + " maçın oranları çekildi.\n");

        } catch (Exception e) {
            System.err.println("❌ Scraper hatası: " + e.getMessage());
            e.printStackTrace();
        }
        return matches;
    }

    private void fetchOddsForMatch(MatchInfo mi) {
        String scoreUrl = "https://5.flashscore.ninja/5/x/feed/df_sui_1_" + mi.id;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(scoreUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("x-fsign", "SW9D1eZo")
                    .GET().build();
            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) parseScores(mi, resp.body());
        } catch (Exception ignored) {}

        String oddsUrl = String.format(
                "https://global.ds.lsapp.eu/odds/pq_graphql?_hash=oce&eventId=%s&projectId=5&geoIpCode=AZ&geoIpSubdivisionCode=AZBA",
                mi.id);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(oddsUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body().startsWith("{"))
                parseOdds(mi, resp.body());
        } catch (Exception ignored) {}
    }

    private void parseScores(MatchInfo mi, String body) {
        String htHome = "-", htAway = "-", ftHome = "-", ftAway = "-";
        for (String sec : body.split("~")) {
            String half = null, ig = null, ih = null;
            for (String part : sec.split("¬")) {
                if (part.startsWith("AC÷")) half = part.substring(3);
                else if (part.startsWith("IG÷")) ig = part.substring(3);
                else if (part.startsWith("IH÷")) ih = part.substring(3);
            }
            if (half == null || ig == null || ih == null) continue;
            if ("1st Half".equals(half)) {
                htHome = ig;
                htAway = ih;
            } else if ("2nd Half".equals(half)) {
                try {
                    ftHome = String.valueOf(Integer.parseInt(htHome) + Integer.parseInt(ig));
                    ftAway = String.valueOf(Integer.parseInt(htAway) + Integer.parseInt(ih));
                } catch (NumberFormatException e) {
                    ftHome = ig;
                    ftAway = ih;
                }
            }
        }
        mi.ftScore = ftHome + "-" + ftAway;
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
                case "HOME_DRAW_AWAY":
                    String period = mapScope(scope);
                    if (period != null) {
                        for (int j = 0; j < items.length(); j++) {
                            JSONObject item = items.getJSONObject(j);
                            String val = getOddsValue(item);
                            String pid = item.isNull("eventParticipantId") ? null : item.getString("eventParticipantId");
                            String key;
                            if (pid == null) key = "1x2|" + period + "|Draw";
                            else if (pid.equals(homePartId)) key = "1x2|" + period + "|Home";
                            else key = "1x2|" + period + "|Away";
                            mi.odds.put(key, val);
                        }
                    }
                    break;

                case "BOTH_TEAMS_TO_SCORE":
                    period = mapScope(scope);
                    if (period != null) {
                        for (int j = 0; j < items.length(); j++) {
                            JSONObject item = items.getJSONObject(j);
                            boolean yes = item.getBoolean("bothTeamsToScore");
                            mi.odds.put("Both teams|" + period + "|" + (yes ? "Yes" : "No"), getOddsValue(item));
                        }
                    }
                    break;

                case "OVER_UNDER":
                    period = mapScope(scope);
                    if (period != null) {
                        for (int j = 0; j < items.length(); j++) {
                            JSONObject item = items.getJSONObject(j);
                            if (!item.isNull("handicap")) {
                                double h = item.getJSONObject("handicap").getDouble("value");
                                String sel = item.getString("selection");
                                mi.odds.put("Over/Under|" + period + "|" + ("OVER".equals(sel) ? "O " : "U ") + h,
                                        getOddsValue(item));
                            }
                        }
                    }
                    break;

                case "DOUBLE_CHANCE":
                    period = mapScope(scope);
                    if (period != null) {
                        for (int j = 0; j < items.length(); j++) {
                            JSONObject item = items.getJSONObject(j);
                            String val = getOddsValue(item);
                            String pid = item.isNull("eventParticipantId") ? null : item.getString("eventParticipantId");
                            String key;
                            if (pid == null) key = "Double chance|" + period + "|12";
                            else if (pid.equals(homePartId)) key = "Double chance|" + period + "|1X";
                            else key = "Double chance|" + period + "|X2";
                            mi.odds.put(key, val);
                        }
                    }
                    break;

                case "CORRECT_SCORE":
                    period = mapScope(scope);
                    if (period != null && !"2nd Half".equals(period)) {
                        for (int j = 0; j < items.length(); j++) {
                            JSONObject item = items.getJSONObject(j);
                            if (!item.isNull("score")) {
                                String score = item.getString("score").replace(" ", "");
                                mi.odds.put("Correct score|" + period + "|" + score, getOddsValue(item));
                            }
                        }
                    }
                    break;

                case "HALF_FULL_TIME":
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j);
                        if (!item.isNull("winner")) {
                            mi.odds.put("HTFT|" + item.getString("winner"), getOddsValue(item));
                        }
                    }
                    break;
            }
        }
    }

    private String mapScope(String scope) {
        switch (scope) {
            case "FULL_TIME": return "Full Time";
            case "FIRST_HALF": return "1st Half";
            case "SECOND_HALF": return "2nd Half";
            default: return null;
        }
    }

    private String getOddsValue(JSONObject item) {
        try {
            if (!item.isNull("opening")) return item.getString("opening");
            if (!item.isNull("value")) return item.getString("value");
        } catch (Exception ignored) {}
        return "-";
    }

    // ==================== YÖNTEMLER İÇİN SEQUENTIAL ADAPTİF METOT (BELLEK İÇİ) ====================
    private void analyzeWithSequentialPattern(MatchInfo match, List<String> patternDisplayNames, String methodName) {
        // Səssiz işləyir: heç bir addım/başlıq çap etmir.
        // Yalnız sonda uyğun twin oyun (1-2 nəticə) tapılanda kompakt çap edir.
        // Filtreleme SQL yerine bellekteki tablo üzerinde yapılır.
        List<ColumnDef> patternFilters = new ArrayList<>();
        for (String displayName : patternDisplayNames) {
            ColumnDef col = ALL_COLS.stream()
                    .filter(c -> c.displayName.equals(displayName))
                    .findFirst().orElse(null);
            if (col == null) continue;
            if (!oddsColumns.containsKey(col.sqlColumn)) continue; // kolon tabloda yok
            String val = match.odds.getOrDefault(col.flashscoreKey, "");
            if (val.isEmpty() || "-".equals(val)) continue;
            patternFilters.add(col);
        }
        if (patternFilters.isEmpty()) return;

        String cacheKey = match.id + "::" + String.join(",", patternDisplayNames);
        int[] rows = patternResultCache.get(cacheKey);
        if (rows == null) {
            rows = runSequentialFilter(match, patternFilters);
            patternResultCache.put(cacheKey, rows);
        }

        // Uyğun twin oyun yoxdursa (aralıq dışı) heç nə çap etmə.
        if (rows.length < 1 || rows.length > 2) return;

        List<MatchResult> results = fetchRowsByIndex(rows);
        if (results.isEmpty()) return;

        printCompactResult(results, match, methodName);

        // İstatistik için bu yöntemin kararını kaydet (twin oyunların HT/FT sonuçları)
        MethodVerdict verdict = new MethodVerdict();
        verdict.methodName = methodName;
        for (MatchResult r : results) {
            String htft = computeHtFt(r.data.get("ht_iy"), r.data.get("ft_ms"));
            if (htft == null) continue;
            verdict.htftOutcomes.add(htft);

            TwinInfo twin = new TwinInfo();
            twin.date = r.data.getOrDefault("date_time", "-");
            twin.home = r.data.getOrDefault("home_team", "-");
            twin.away = r.data.getOrDefault("away_team", "-");
            twin.ft = r.data.getOrDefault("ft_ms", "-");
            twin.ht = r.data.getOrDefault("ht_iy", "-");
            twin.htft = htft;
            verdict.twins.add(twin);
        }
        if (!verdict.htftOutcomes.isEmpty()) {
            matchVerdicts.computeIfAbsent(match, k -> new ArrayList<>()).add(verdict);
        }
    }

    // ==================== HT/FT HESAPLAMA ====================
    // "2-1" / "2:1" formatındaki İY ve MS skorlarından "1/2", "X/1" gibi HT/FT sonucu üretir.
    private String computeHtFt(String htScore, String ftScore) {
        String ht = scoreSign(htScore);
        String ft = scoreSign(ftScore);
        if (ht == null || ft == null) return null;
        return ht + "/" + ft;
    }

    private String scoreSign(String score) {
        if (score == null) return null;
        String[] parts = score.trim().split("[-:]");
        if (parts.length != 2) return null;
        try {
            int h = Integer.parseInt(parts[0].trim());
            int a = Integer.parseInt(parts[1].trim());
            if (h > a) return "1";
            if (h < a) return "2";
            return "X";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== KOMPAKT ÇIXIŞ: yalnız uyğun twin oyun tapılanda ====================
    private void printCompactResult(List<MatchResult> results, MatchInfo match, String methodName) {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println(methodName);
        System.out.println("⚽ Analiz olunan maç: " + match.home + " vs " + match.away);
        System.out.println("✅ Uyğun twin oyunlar (" + results.size() + " ədəd):");
        System.out.println("┌──────┬─────────────────────┬─────────────────────┬──────────┬──────────┬──────────┐");
        System.out.println("│ #    │ Tarih               │ Ev Sahibi           │ Deplasman│ MS       │ İY       │");
        System.out.println("├──────┼─────────────────────┼─────────────────────┼──────────┼──────────┼──────────┤");
        for (int i = 0; i < results.size(); i++) {
            MatchResult r = results.get(i);
            String date = truncate(r.data.getOrDefault("date_time", "-"), 19);
            String home = truncate(r.data.getOrDefault("home_team", "-"), 19);
            String away = truncate(r.data.getOrDefault("away_team", "-"), 8);
            String ft = truncate(r.data.getOrDefault("ft_ms", "-"), 8);
            String ht = truncate(r.data.getOrDefault("ht_iy", "-"), 8);
            System.out.printf("│ %-4d │ %-19s │ %-19s │ %-8s │ %-8s │ %-8s │%n",
                    (i + 1), date, home, away, ft, ht);
        }
        System.out.println("└──────┴─────────────────────┴─────────────────────┴──────────┴──────────┴──────────┘\n");
    }

    // ==================== BELLEK İÇİ FİLTRELEME ====================
    // Sıralı desen mantığının aynısı: filtre tek tek eklenir, sonuç boşsa filtre atlanır,
    // 1-2 satır kalınca durulur. Satırlar date_time DESC sıralı yüklendiği için
    // eski SQL'deki ORDER BY davranışı korunur.
    private int[] runSequentialFilter(MatchInfo match, List<ColumnDef> patternFilters) {
        int[] candidates = null; // null = henüz koşul uygulanmadı (tüm tablo)
        for (ColumnDef col : patternFilters) {
            float value;
            try {
                value = Float.parseFloat(match.odds.get(col.flashscoreKey).replace(',', '.'));
            } catch (NumberFormatException e) {
                continue;
            }
            int[] next = filterColumn(candidates, col.sqlColumn, value);
            if (next.length == 0) continue;
            candidates = next;
            if (candidates.length <= 2) break;
        }
        return candidates == null ? new int[0] : candidates;
    }

    private int[] filterColumn(int[] base, String sqlColumn, float value) {
        float[] col = oddsColumns.get(sqlColumn);
        int k = 0;
        if (base == null) {
            for (int i = 0; i < rowCount; i++) if (col[i] == value) scratch[k++] = i;
        } else {
            for (int idx : base) if (col[idx] == value) scratch[k++] = idx;
        }
        return Arrays.copyOf(scratch, k);
    }

    // Bulunan 1-2 twin satırın gösterim kolonları pkey üzerinden çekilir (indexli, milisaniyelik)
    private List<MatchResult> fetchRowsByIndex(int[] rowIdx) {
        List<MatchResult> results = new ArrayList<>();
        String sql = "SELECT date_time, home_team, away_team, ft_ms, ht_iy FROM bet365_matches WHERE "
                + pkColumn + " = ?";
        for (int idx : rowIdx) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, rowIds[idx]);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        MatchResult mr = new MatchResult();
                        ResultSetMetaData meta = rs.getMetaData();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            mr.data.put(meta.getColumnName(i), rs.getString(i));
                        }
                        results.add(mr);
                    }
                }
            } catch (SQLException ex) {
                System.err.println("SQL Hatası: " + ex.getMessage());
            }
        }
        return results;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "-";
        if (s.length() > maxLen) return s.substring(0, maxLen - 1) + "…";
        return s;
    }

    // ==================== ANA ÇALIŞTIRICI (GÜNCELLENMİŞ) ====================
    public void run() {
        List<MatchInfo> todayMatches = scrapeTodayMatches();

        if (todayMatches.isEmpty()) {
            System.out.println("❌ Bugünkü maç bulunamadı!");
            return;
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("🤖 7 ANALİZ YÖNTEMİ İLE OTOMATİK ANALİZ BAŞLIYOR: " + todayMatches.size() + " maç");
        System.out.println("═══════════════════════════════════════════════════════════════\n");

        for (MatchInfo match : todayMatches) {
            // Qeyd: hər maç üçün başlıq çap edilmir. Yalnız uyğun twin oyun tapılan
            // yöntemlər (printCompactResult) ekrana yazır.

            // ── YENİ FİLTRE SETLERİ (Round-Robin HT/FT Yüksek Güvenli Setler) ──────────────
            analyzeWithSequentialPattern(match,
                    List.of(
                            "İY X", "MS Skor 0:2", "İY A/U 2.5 Üst", "İY Skor 1:1", "İY Skor 0:2",
                            "MS Skor 2:0", "İY A/U 1.5 Alt", "MS Skor 4:4", "İY Skor 1:2", "MS Skor 3:4",
                            "İY Skor 3:1", "MS Skor 4:0", "İY Skor 3:0", "KG Evet", "MS Skor 0:1",
                            "ÇŞ 1X", "2Y A/U 1.5 Üst", "İY Skor 2:0", "2Y A/U 2.5 Üst", "A/U 5.5 Alt",
                            "A/U 1.5 Üst", "MS Skor 4:2", "İY Skor 0:1", "İY Skor 0:3", "MS Skor 4:3",
                            "ÇŞ 12"
                    ),
                    "🔵 YÖNTEM 2 [HT/FT Seti 1]"
            );

            analyzeWithSequentialPattern(match,
                    List.of(
                            "İY A/U 0.5 Alt", "2Y A/U 1.5 Alt", "2Y A/U 2.5 Üst", "İY A/U 2.5 Alt",
                            "MS Skor 4:3", "MS Skor 3:0", "HT/FT 1/2", "A/U 5.5 Üst", "A/U 2.5 Alt",
                            "KG Evet", "2Y A/U 0.5 Alt", "2Y A/U 1.5 Üst", "MS Skor 2:4", "MS Skor 1:0",
                            "HT/FT X/2", "İY A/U 2.5 Üst", "İY Skor 3:1", "2Y A/U 0.5 Üst", "İY KG Hayır",
                            "2Y KG Evet", "MS Skor 4:4", "İY 1", "A/U 1.5 Alt", "İY Skor 2:1",
                            "İY A/U 1.5 Üst", "A/U 3.5 Üst", "KG Hayır", "İY Skor 0:3", "MS Skor 0:2"
                    ),
                    "🔵 YÖNTEM 3 [HT/FT Seti 2]"
            );

            analyzeWithSequentialPattern(match,
                    List.of(
                            "İY Skor 2:2", "MS Skor 2:2", "İY Skor 1:0", "İY Skor 1:1", "İY KG Evet",
                            "A/U 3.5 Üst", "MS Skor 3:0", "MS Skor 4:3", "İY KG Hayır", "İY ÇŞ 1X",
                            "MS Skor 1:3", "İY A/U 1.5 Üst", "ÇŞ X2", "MS Skor 3:3", "2Y KG Evet",
                            "İY Skor 0:2", "İY A/U 1.5 Alt", "İY Skor 2:3", "İY Skor 3:1", "İY A/U 0.5 Alt",
                            "2Y 2"
                    ),
                    "🔵 YÖNTEM 4 [HT/FT Seti 3]"
            );

            analyzeWithSequentialPattern(match,
                    List.of(
                            "A/U 0.5 Alt", "İY Skor 1:0", "A/U 4.5 Üst", "A/U 4.5 Alt", "HT/FT 2/1",
                            "MS Skor 0:1", "MS Skor 4:4", "A/U 5.5 Üst", "HT/FT 1/1", "KG Hayır",
                            "HT/FT X/1", "MS Skor 1:1", "MS Skor 2:4", "MS Skor 4:2", "2Y 2",
                            "İY Skor 0:3", "İY A/U 0.5 Üst", "İY Skor 3:1", "2Y X", "MS Skor 3:0",
                            "MS Skor 1:3", "İY A/U 2.5 Üst", "İY 2", "İY Skor 0:1", "HT/FT 2/2",
                            "İY Skor 2:1", "KG Evet", "HT/FT 1/2", "2Y A/U 2.5 Üst", "2Y A/U 0.5 Alt"
                    ),
                    "🎯 YÖNTEM 10 [HT/FT Seti 9]"
            );

            analyzeWithSequentialPattern(match,
                    List.of(
                            "İY Skor 2:1", "MS Skor 0:4", "MS Skor 4:1", "A/U 2.5 Üst", "KG Evet",
                            "İY Skor 1:2", "2Y A/U 2.5 Alt", "İY A/U 1.5 Alt", "A/U 0.5 Üst", "İY Skor 0:3",
                            "MS Skor 2:1", "2Y KG Evet", "İY Skor 1:0", "2Y KG Hayır", "A/U 3.5 Üst",
                            "HT/FT X/1", "İY 2"
                    ),
                    "🎯 YÖNTEM 11 [HT/FT Seti 10]"
            );

            analyzeWithSequentialPattern(match,
                    List.of(
                            "MS Skor 3:3", "İY Skor 3:1", "MS Skor 1:1", "İY A/U 0.5 Alt", "MS Skor 2:3",
                            "HT/FT X/1", "HT/FT 1/2", "A/U 3.5 Alt", "MS Skor 0:4", "MS Skor 3:4",
                            "MS Skor 2:2", "İY Skor 2:2", "MS Skor 3:2", "İY Skor 0:2", "HT/FT X/X",
                            "İY Skor 1:2", "MS Skor 3:0", "MS Skor 1:4", "MS Skor 2:4", "HT/FT 2/1",
                            "A/U 2.5 Üst"
                    ),
                    "🎯 YÖNTEM 13 [HT/FT Seti 12]"
            );

            analyzeWithSequentialPattern(match,
                    List.of(
                            "A/U 3.5 Üst", "İY ÇŞ X2", "2Y X", "MS Skor 1:1", "2Y KG Hayır",
                            "2Y A/U 1.5 Üst", "2Y A/U 2.5 Alt", "İY A/U 0.5 Alt", "2Y A/U 1.5 Alt", "A/U 5.5 Alt",
                            "HT/FT X/2", "İY Skor 0:2", "MS Skor 4:3", "İY KG Evet", "ÇŞ X2",
                            "A/U 4.5 Üst", "MS Skor 3:3", "İY Skor 2:0", "MS Skor 2:4", "A/U 3.5 Alt",
                            "İY 1", "ÇŞ 12", "MS Skor 3:2", "2Y A/U 0.5 Üst", "İY ÇŞ 12",
                            "MS Skor 0:4", "A/U 1.5 Üst", "İY 2", "A/U 5.5 Üst", "HT/FT 1/1",
                            "İY ÇŞ 1X", "HT/FT X/X", "İY Skor 3:1", "İY Skor 1:2", "MS Skor 2:2",
                            "MS Skor 1:2", "MS Skor 1:3"
                    ),
                    "🎯 YÖNTEM 14 [HT/FT Seti 13]"
            );

        }

        printEndOfDayStatistics();
        printCommonHtFtPredictions();
        printCommonSidePredictions();

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("✅ TÜM ANALİZLER TAMAMLANDI");
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    // ==================== GÜN SONU İSTATİSTİĞİ ====================
    // HT/FT marketinde 1/2 ve 2/1 = "Comeback", 1/X ve 2/X = "Beraberlik (geri dönüşlü)".
    // Bir maçı bulan TÜM yöntemlerin twin sonuçları aynı gruba düşüyorsa ortak karar sayılır.
    private void printEndOfDayStatistics() {
        Set<String> comebackSet = Set.of("1/2", "2/1");
        Set<String> drawbackSet = Set.of("1/X", "2/X");
        Set<String> xxSet = Set.of("X/X");

        List<Map.Entry<MatchInfo, List<MethodVerdict>>> comebackMatches = new ArrayList<>();
        List<Map.Entry<MatchInfo, List<MethodVerdict>>> drawbackMatches = new ArrayList<>();
        List<Map.Entry<MatchInfo, List<MethodVerdict>>> xxMatches = new ArrayList<>();

        for (Map.Entry<MatchInfo, List<MethodVerdict>> e : matchVerdicts.entrySet()) {
            List<MethodVerdict> verdicts = e.getValue();
            if (verdicts.isEmpty()) continue;

            Set<String> allOutcomes = new LinkedHashSet<>();
            for (MethodVerdict v : verdicts) allOutcomes.addAll(v.htftOutcomes);
            if (allOutcomes.isEmpty()) continue;

            if (comebackSet.containsAll(allOutcomes)) comebackMatches.add(e);
            else if (drawbackSet.containsAll(allOutcomes)) drawbackMatches.add(e);
            else if (xxSet.containsAll(allOutcomes)) xxMatches.add(e);
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("📈 GÜN SONU İSTATİSTİĞİ (ortak karar analizi)");
        System.out.println("═══════════════════════════════════════════════════════════════");

        System.out.println("1) Tüm yöntemlere uygun 2/1 & 1/2 COMEBACK maçları: "
                + (comebackMatches.isEmpty() ? "— yok —" : comebackMatches.size() + " maç"));
        printStatMatches(comebackMatches);

        System.out.println("2) Tüm yöntemlere uygun 1/X & 2/X BERABERLİK maçları: "
                + (drawbackMatches.isEmpty() ? "— yok —" : drawbackMatches.size() + " maç"));
        printStatMatches(drawbackMatches);

        System.out.println("3) Tüm yöntemlere uygun X/X maçları: "
                + (xxMatches.isEmpty() ? "— yok —" : xxMatches.size() + " maç"));
        printStatMatches(xxMatches);
        System.out.println();
    }

    // ==================== ORTAK HT/FT TƏXMİNİ (min. 6 yöntəm) ====================
    // Yalnız 1/X, 2/X, X/X, 1/2, 2/1 təxminləri nəzərə alınır.
    // 1/X ilə 2/X eyni təxmin sayılır (heç-heçə qrupu), 1/2 ilə 2/1 eyni təxmin sayılır (comeback qrupu),
    // X/X isə ayrıca qrup kimi hesablanır.
    private static final int MIN_COMMON_METHODS = 6;

    private void printCommonHtFtPredictions() {
        Set<String> drawGroup = Set.of("1/X", "2/X");
        Set<String> comebackGroup = Set.of("1/2", "2/1");
        Set<String> xxGroup = Set.of("X/X");

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("🎯 ORTAK HT/FT TƏXMİNLƏRİ (minimum " + MIN_COMMON_METHODS + " yöntəm eyni təxmin)");
        System.out.println("═══════════════════════════════════════════════════════════════");

        boolean anyFound = false;

        for (Map.Entry<MatchInfo, List<MethodVerdict>> e : matchVerdicts.entrySet()) {
            List<MethodVerdict> drawVerdicts = new ArrayList<>();
            List<MethodVerdict> comebackVerdicts = new ArrayList<>();
            List<MethodVerdict> xxVerdicts = new ArrayList<>();

            for (MethodVerdict v : e.getValue()) {
                Set<String> outcomes = new HashSet<>(v.htftOutcomes);
                if (outcomes.isEmpty()) continue;
                if (drawGroup.containsAll(outcomes)) drawVerdicts.add(v);
                else if (comebackGroup.containsAll(outcomes)) comebackVerdicts.add(v);
                else if (xxGroup.containsAll(outcomes)) xxVerdicts.add(v);
            }

            if (drawVerdicts.size() >= MIN_COMMON_METHODS) {
                printCommonPrediction(e.getKey(), drawVerdicts);
                anyFound = true;
            }
            if (comebackVerdicts.size() >= MIN_COMMON_METHODS) {
                printCommonPrediction(e.getKey(), comebackVerdicts);
                anyFound = true;
            }
            if (xxVerdicts.size() >= MIN_COMMON_METHODS) {
                printCommonPrediction(e.getKey(), xxVerdicts);
                anyFound = true;
            }
        }

        if (!anyFound) {
            System.out.println("— Minimum " + MIN_COMMON_METHODS
                    + " yöntəmin eyni HT/FT təxmini verdiyi oyun tapılmadı —");
        }
        System.out.println();
    }

    private void printCommonPrediction(MatchInfo match, List<MethodVerdict> verdicts) {
        Set<String> outcomes = new LinkedHashSet<>();
        for (MethodVerdict v : verdicts) outcomes.addAll(v.htftOutcomes);

        List<String> descs = new ArrayList<>();
        for (String o : outcomes) {
            String d = htftDescription(o);
            if (!descs.contains(d)) descs.add(d);
        }

        System.out.println(match.home + " vs " + match.away);
        System.out.println("Təxmin edilən HT/FT: " + String.join(" & ", outcomes)
                + " (" + String.join(" | ", descs) + ") — " + verdicts.size() + " Metodda uyğun gəlir.");
        System.out.println("Yöntəmlər və sübutlar:");
        for (MethodVerdict v : verdicts) {
            String name = shortMethodName(v.methodName);
            for (TwinInfo t : v.twins) {
                String[] p = t.htft.split("/");
                System.out.printf("%s: %s vs %s (%s, %s) ➔ İY: %s, MS: %s (%s)%n",
                        name, t.home, t.away, t.ft, t.ht, p[0], p[1], t.htft);
            }
        }
        System.out.println();
    }

    // ==================== EYNİ TƏRƏF TƏXMİNİ (7 yöntəmdən min. 6-sı) ====================
    // Hər yöntəmin twin oyunlarının HT/FT nəticəsinin MS işarəsi (1, X, 2) eyni tərəfi
    // göstərirsə, o yöntəm həmin tərəfə səs vermiş sayılır. 7 yöntəmdən ən az 6-sı
    // eyni tərəfi göstərən oyunlar burada çap edilir.
    private static final int MIN_SAME_SIDE_METHODS = 6;

    private void printCommonSidePredictions() {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("🤝 EYNİ TƏRƏF TƏXMİNLƏRİ (7 yöntəmdən minimum "
                + MIN_SAME_SIDE_METHODS + " yöntəm eyni MS tərəfi)");
        System.out.println("═══════════════════════════════════════════════════════════════");

        boolean anyFound = false;

        for (Map.Entry<MatchInfo, List<MethodVerdict>> e : matchVerdicts.entrySet()) {
            Map<String, List<MethodVerdict>> bySide = new LinkedHashMap<>();
            for (MethodVerdict v : e.getValue()) {
                Set<String> sides = new HashSet<>();
                for (String o : v.htftOutcomes) sides.add(o.substring(o.indexOf('/') + 1));
                if (sides.size() != 1) continue; // yöntəmin twinləri fərqli tərəflər göstərir
                bySide.computeIfAbsent(sides.iterator().next(), k -> new ArrayList<>()).add(v);
            }
            for (Map.Entry<String, List<MethodVerdict>> s : bySide.entrySet()) {
                if (s.getValue().size() < MIN_SAME_SIDE_METHODS) continue;
                printSameSidePrediction(e.getKey(), s.getKey(), s.getValue());
                anyFound = true;
            }
        }

        if (!anyFound) {
            System.out.println("— Minimum " + MIN_SAME_SIDE_METHODS
                    + " yöntəmin eyni tərəf təxmini verdiyi oyun tapılmadı —");
        }
        System.out.println();
    }

    private void printSameSidePrediction(MatchInfo match, String side, List<MethodVerdict> verdicts) {
        Set<String> outcomes = new LinkedHashSet<>();
        for (MethodVerdict v : verdicts) outcomes.addAll(v.htftOutcomes);

        System.out.println(match.home + " vs " + match.away);
        System.out.println("Təxmin edilən MS tərəfi: " + side + " (" + signName(side)
                + ") — " + verdicts.size() + " metod eyni tərəfi göstərir. Twin HT/FT nəticələri: "
                + String.join(", ", outcomes));
        System.out.println("Yöntəmlər və sübutlar:");
        for (MethodVerdict v : verdicts) {
            String name = shortMethodName(v.methodName);
            for (TwinInfo t : v.twins) {
                System.out.printf("%s: %s vs %s | İY %s → MS %s | HT/FT: %s%n",
                        name, t.home, t.away, t.ht, t.ft, t.htft);
            }
        }
        System.out.println();
    }

    private String htftDescription(String htft) {
        String[] p = htft.split("/");
        return signName(p[0]) + " / " + signName(p[1]);
    }

    private String signName(String sign) {
        switch (sign) {
            case "1": return "Ev sahibi";
            case "2": return "Deplasman";
            default:  return "Heç-heçə";
        }
    }

    private String shortMethodName(String fullName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("YÖNTEM \\d+").matcher(fullName);
        return m.find() ? m.group() : fullName;
    }

    // Uygun maçı ve onu bulan her yöntemin twin maçlarını yazdırır
    private void printStatMatches(List<Map.Entry<MatchInfo, List<MethodVerdict>>> entries) {
        for (Map.Entry<MatchInfo, List<MethodVerdict>> e : entries) {
            MatchInfo match = e.getKey();
            List<MethodVerdict> verdicts = e.getValue();

            Set<String> allOutcomes = new LinkedHashSet<>();
            for (MethodVerdict v : verdicts) allOutcomes.addAll(v.htftOutcomes);

            System.out.printf("   • %s vs %s  (%d yöntem hemfikir, twin HT/FT: %s)%n",
                    match.home, match.away, verdicts.size(), String.join(", ", allOutcomes));

            for (MethodVerdict v : verdicts) {
                System.out.println("       " + v.methodName);
                for (TwinInfo t : v.twins) {
                    System.out.printf("         └ %s | %s vs %s | İY %s → MS %s | HT/FT: %s%n",
                            t.date, t.home, t.away, t.ht, t.ft, t.htft);
                }
            }
        }
    }

    public static void main(String[] args) {
        Bet365DailyAutoAnalyzer analyzer = new Bet365DailyAutoAnalyzer();
        analyzer.run();
        try { analyzer.conn.close(); } catch (Exception ignored) {}
    }
}