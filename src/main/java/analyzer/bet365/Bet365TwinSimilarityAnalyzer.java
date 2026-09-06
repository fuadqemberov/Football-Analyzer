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
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  Bet365TwinSimilarityAnalyzer — 99% OXŞARLIQ ƏSASLI TWIN AXTARIŞI
 * ═══════════════════════════════════════════════════════════════════════════
 *  ALQORİTM:
 *   1) Bugünkü (başlamamış) hər oyunun bütün mövcud oranları vektor kimi götürülür.
 *   2) Tarixi bazadakı HƏR oyunla müqayisə olunur:
 *        - hər ortaq kolon üçün oxşarlıq = 1 − |a−b| / max(a,b)
 *        - ümumi oxşarlıq = ortaq kolonların ortalaması (%)
 *   3) Oxşarlığı ≥ 99% olan VƏ ən azı MIN_COMMON_COLS ortaq oranı olan
 *      tarixi oyunlar "twin" sayılır.
 *   4) Tapılan bütün twinlər EKRANA ÇAP OLUNUR (tarix, komandalar, İY və MS
 *      skoru, oxşarlıq faizi, ortaq kolon sayı) — ən oxşardan başlayaraq.
 *   5) Twinlərin sonunda ORTAQ TƏXMİN bloku yazılır: 1x2 (MS/İY/2Y), KG,
 *      FT 2.5 A/U, İY 0.5 ALT, 2Y 0.5 ALT — hər market üzrə neçə twin o
 *      nəticə ilə bitibsə, o faiz güvən sayılır. Ən güvənlisi ⭐ ilə işarələnir.
 *   6) Günün BÜTÜN oyunları bu qayda ilə analiz olunur; twin tapılmayan
 *      oyunlar bir sətirlə bildirilir.
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class Bet365TwinSimilarityAnalyzer {

    // ==================== KOLON TANIMLARI ====================
    static final class ColumnDef {
        final String sqlColumn, displayName, flashscoreKey;
        ColumnDef(String sqlColumn, String displayName, String flashscoreKey) {
            this.sqlColumn = sqlColumn; this.displayName = displayName; this.flashscoreKey = flashscoreKey;
        }
    }

    private static final List<ColumnDef> ALL_COLS = new ArrayList<>();

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
        ALL_COLS.add(new ColumnDef(sql, display, flash));
    }

    // ==================== PARAMETRLƏR ====================
    private static final double SIMILARITY_THRESHOLD = 99.0; // twin sayılmaq üçün minimum oxşarlıq (%)
    private static final int MIN_COMMON_COLS = 15;           // müqayisə mənalı olsun deyə minimum ortaq oran sayı
    private static final int MIN_TWINS_FOR_CONSENSUS = 3;    // ortaq təxmin üçün minimum twin sayı
    private static final int MAX_TWINS_TO_PRINT = 100;       // ekranı boğmamaq üçün çap limiti

    // ==================== HTTP ====================
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    // ==================== RAM-DAXİLİ TABLO ====================
    private final Map<String, float[]> oddsColumns = new HashMap<>();
    private String[] rDate, rHome, rAway;
    private int[] rHtH, rHtA, rFtH, rFtA; // parse olunmuş qollar (-1 = naməlum)
    private int rowCount;
    private List<String> loadedCols; // DB-də mövcud olan kolonlar (sıra ilə)

    private Connection conn;

    public Bet365TwinSimilarityAnalyzer() {
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
        loadedCols = ALL_COLS.stream()
                .map(c -> c.sqlColumn).filter(existing::contains).distinct().collect(Collectors.toList());

        int cap = (int) sqlRowCount() + 1000;
        rDate = new String[cap]; rHome = new String[cap]; rAway = new String[cap];
        rHtH = new int[cap]; rHtA = new int[cap]; rFtH = new int[cap]; rFtA = new int[cap];
        float[][] data = new float[loadedCols.size()][cap];

        String sql = "SELECT date_time, home_team, away_team, ht_iy, ft_ms, "
                + String.join(", ", loadedCols) + " FROM bet365_matches ORDER BY date_time DESC";
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
                    for (int i = 0; i < loadedCols.size(); i++) {
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
        for (int i = 0; i < loadedCols.size(); i++) oddsColumns.put(loadedCols.get(i), data[i]);

        System.out.printf("✅ Tablo belleğe yüklendi: %,d satır × %d kolon (%.1f sn)%n%n",
                rowCount, loadedCols.size(), (System.currentTimeMillis() - t0) / 1000.0);
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

    // ==================== 99% OXŞARLIQ ALQORİTMİ ====================
    static final class Twin {
        final int row;            // tarixi oyunun sətir indeksi
        final double similarity;  // % oxşarlıq
        final int commonCols;     // müqayisə olunan ortaq kolon sayı
        Twin(int row, double similarity, int commonCols) {
            this.row = row; this.similarity = similarity; this.commonCols = commonCols;
        }
    }

    /**
     * Bugünkü oyunun oran vektorunu tarixi bazadakı bütün oyunlarla müqayisə
     * edir və oxşarlığı SIMILARITY_THRESHOLD-dan yüksək olanları qaytarır.
     */
    private List<Twin> findTwins(MatchInfo match) {
        // 1) Bugünkü oyunun mövcud oranlarını (kolon → dəyər) çıxar
        List<float[]> cols = new ArrayList<>(); // hər element: DB kolon massivi
        List<Float> vals = new ArrayList<>();   // bugünkü oyunun dəyəri
        for (ColumnDef col : ALL_COLS) {
            String raw = match.odds.get(col.flashscoreKey);
            if (raw == null || raw.isEmpty() || "-".equals(raw)) continue;
            float v;
            try { v = Float.parseFloat(raw.replace(',', '.')); }
            catch (NumberFormatException ignored) { continue; }
            float[] dbCol = oddsColumns.get(col.sqlColumn);
            if (dbCol == null) continue;
            cols.add(dbCol);
            vals.add(v);
        }
        if (cols.size() < MIN_COMMON_COLS) return List.of();

        int n = cols.size();
        float[][] colArr = cols.toArray(new float[0][]);
        float[] valArr = new float[n];
        for (int i = 0; i < n; i++) valArr[i] = vals.get(i);

        // 2) Bütün tarixi sətirləri paralel gəz
        return java.util.stream.IntStream.range(0, rowCount).parallel()
                .mapToObj(r -> {
                    double simSum = 0;
                    int common = 0;
                    for (int i = 0; i < n; i++) {
                        float h = colArr[i][r];
                        if (Float.isNaN(h)) continue;
                        float a = valArr[i];
                        float max = Math.max(a, h);
                        double sim = max <= 0 ? 1.0 : 1.0 - Math.abs(a - h) / max;
                        simSum += sim;
                        common++;
                    }
                    if (common < MIN_COMMON_COLS) return null;
                    double similarity = 100.0 * simSum / common;
                    if (similarity < SIMILARITY_THRESHOLD) return null;
                    return new Twin(r, similarity, common);
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble((Twin t) -> t.similarity).reversed())
                .collect(Collectors.toList());
    }

    // ==================== ORTAQ TƏXMİN ====================
    static final class Pick {
        final String label;
        final int hit, total;
        Pick(String label, int hit, int total) { this.label = label; this.hit = hit; this.total = total; }
        double conf() { return total == 0 ? 0 : 100.0 * hit / total; }
    }

    private boolean ftValid(int r) { return rFtH[r] >= 0 && rFtA[r] >= 0; }
    private boolean htValid(int r) { return rHtH[r] >= 0 && rHtA[r] >= 0; }
    private boolean shValid(int r) { return ftValid(r) && htValid(r); }
    private int ftTot(int r) { return rFtH[r] + rFtA[r]; }
    private int shH(int r) { return rFtH[r] - rHtH[r]; }
    private int shA(int r) { return rFtA[r] - rHtA[r]; }

    private List<Pick> evaluatePicks(List<Twin> twins) {
        List<Pick> picks = new ArrayList<>();

        addPred(picks, twins, "MS 1 (Ev qalibi)",       this::ftValid, r -> rFtH[r] > rFtA[r]);
        addPred(picks, twins, "MS X (Bərabərlik)",      this::ftValid, r -> rFtH[r] == rFtA[r]);
        addPred(picks, twins, "MS 2 (Qonaq qalibi)",    this::ftValid, r -> rFtH[r] < rFtA[r]);

        addPred(picks, twins, "İY 1 (İlk yarı ev)",     this::htValid, r -> rHtH[r] > rHtA[r]);
        addPred(picks, twins, "İY X (İlk yarı bərabər)", this::htValid, r -> rHtH[r] == rHtA[r]);
        addPred(picks, twins, "İY 2 (İlk yarı qonaq)",  this::htValid, r -> rHtH[r] < rHtA[r]);

        addPred(picks, twins, "2Y 1 (İkinci yarı ev)",     this::shValid, r -> shH(r) > shA(r));
        addPred(picks, twins, "2Y X (İkinci yarı bərabər)", this::shValid, r -> shH(r) == shA(r));
        addPred(picks, twins, "2Y 2 (İkinci yarı qonaq)",  this::shValid, r -> shH(r) < shA(r));

        addPred(picks, twins, "FT KG VAR (BTTS Yes)",   this::ftValid, r -> rFtH[r] > 0 && rFtA[r] > 0);
        addPred(picks, twins, "FT KG YOX (BTTS No)",    this::ftValid, r -> rFtH[r] == 0 || rFtA[r] == 0);

        addPred(picks, twins, "FT 2.5 ÜST (3+ qol)",    this::ftValid, r -> ftTot(r) > 2);
        addPred(picks, twins, "FT 2.5 ALT (0-2 qol)",   this::ftValid, r -> ftTot(r) <= 2);

        picks.sort(Comparator.<Pick>comparingDouble(Pick::conf).reversed()
                .thenComparing(Comparator.<Pick>comparingInt(p -> p.total).reversed()));
        return picks;
    }

    private void addPred(List<Pick> picks, List<Twin> twins, String label,
                         Predicate<Integer> valid, Predicate<Integer> hit) {
        int h = 0, t = 0;
        for (Twin tw : twins) {
            int r = tw.row;
            if (!valid.test(r)) continue;
            t++;
            if (hit.test(r)) h++;
        }
        if (t >= MIN_TWINS_FOR_CONSENSUS) picks.add(new Pick(label, h, t));
    }

    // ==================== ANA ÇALIŞTIRICI ====================
    public void run() {
        List<MatchInfo> today = scrapeTodayMatches();
        if (today.isEmpty()) { System.out.println("❌ Bugünkü maç bulunamadı!"); return; }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("🤖 99%% OXŞARLIQ TWIN ANALİZİ — %d oyun  (baza: %,d tarixi oyun)%n",
                today.size(), rowCount);
        System.out.println("═══════════════════════════════════════════════════════════════\n");

        int withTwins = 0;
        for (MatchInfo match : today) {
            List<Twin> twins = findTwins(match);
            if (twins.isEmpty()) {
                System.out.printf("▫️ %s vs %s — ≥%.0f%% oxşar twin tapılmadı.%n%n",
                        match.home, match.away, SIMILARITY_THRESHOLD);
                continue;
            }
            withTwins++;
            printMatch(match, twins);
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("✅ TAMAMLANDI — %d oyundan %d-ində ≥%.0f%% oxşar twin tapıldı.%n",
                today.size(), withTwins, SIMILARITY_THRESHOLD);
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    // ==================== ÇAP ====================
    private void printMatch(MatchInfo match, List<Twin> twins) {
        System.out.println("╔══════════════════════════════════════════════════════════════");
        System.out.println("║ ⚽ OYUN: " + match.home + " vs " + match.away + "   [" + match.date + "]");
        System.out.printf("║ 🔎 ≥%.0f%% oxşar %d twin tapıldı:%n", SIMILARITY_THRESHOLD, twins.size());
        System.out.println("╠══════════════════════════════════════════════════════════════");

        int printCount = Math.min(twins.size(), MAX_TWINS_TO_PRINT);
        for (int i = 0; i < printCount; i++) {
            Twin t = twins.get(i);
            int r = t.row;
            String ht = (rHtH[r] >= 0) ? rHtH[r] + ":" + rHtA[r] : "?";
            String ft = (rFtH[r] >= 0) ? rFtH[r] + ":" + rFtA[r] : "?";
            System.out.printf("║ %3d) %.2f%%  [%s]  %s vs %s   İY %s | MS %s  (%d oran)%n",
                    i + 1, t.similarity, rDate[r], rHome[r], rAway[r], ht, ft, t.commonCols);
        }
        if (twins.size() > printCount)
            System.out.printf("║ ... və daha %d twin (çap limiti %d).%n",
                    twins.size() - printCount, MAX_TWINS_TO_PRINT);

        // ── Ortaq təxmin ──
        List<Pick> picks = evaluatePicks(twins);
        System.out.println("╠══════════════════════════════════════════════════════════════");
        if (picks.isEmpty()) {
            System.out.printf("║ ⚠️ Ortaq təxmin üçün kifayət qədər nəticəli twin yoxdur (min %d).%n",
                    MIN_TWINS_FOR_CONSENSUS);
        } else {
            System.out.println("║ 🎯 ORTAQ TƏXMİN (twinlərin nəticələrinə görə):");
            for (int i = 0; i < picks.size(); i++) {
                Pick p = picks.get(i);
                System.out.printf("║   %s %-28s %5.1f%%  (%d/%d twin)%n",
                        i == 0 ? "⭐" : "  ", p.label, p.conf(), p.hit, p.total);
            }
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
        Bet365TwinSimilarityAnalyzer analyzer = new Bet365TwinSimilarityAnalyzer();
        analyzer.run();
        try { analyzer.conn.close(); } catch (Exception ignored) {}
    }
}
