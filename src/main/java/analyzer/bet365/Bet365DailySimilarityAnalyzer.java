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
 *  Bet365DailySimilarityAnalyzer — 100 YÖNTEMLİ TWIN ARAMA (başlamamış maçlar)
 * ═══════════════════════════════════════════════════════════════════════════
 *  Mantık:
 *   • Flashscore'dan SADECE başlamamış (canlı/bitmiş olmayan) maçlar + oranları çekilir.
 *   • Analiz havuzu kullanıcı seçimine göre daraltılır: 1 hafta / 1 ay / 1 yıl / tüm veri.
 *   • Havuz RAM'e alınır, her oran kolonu için (değer → satır indeksleri) indeksi kurulur.
 *   • 100 farklı YÖNTEM vardır; her yöntem = farklı bir oran kolonu dizisi.
 *     Yöntem kolonları sırayla uygulanır, havuz adım adım daralır ve
 *     TAM 2 TWIN maç kalınca durur. (Havuzu 2'nin altına düşüren kolon atlanır.)
 *   • Sadece 2 twin ile biten yöntemler ekrana basılır: yöntem no + filtre + 2 twin maç.
 *   • Sonda bütün twin maçların ORTAK KARARI tahmin olarak yazılır
 *     (MS, HT/FT, 2.5 Alt/Üst, KG, en sık MS skoru).
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class Bet365DailySimilarityAnalyzer {

    // ==================== KOLON TANIMLARI (Flashscore ile SQL eşlemesi) ====================
    static final class ColumnDef {
        final String sqlColumn, displayName, flashscoreKey;
        ColumnDef(String sqlColumn, String displayName, String flashscoreKey) {
            this.sqlColumn = sqlColumn; this.displayName = displayName; this.flashscoreKey = flashscoreKey;
        }
    }

    private static final List<ColumnDef> ALL_COLS = new ArrayList<>();
    private static final Map<String, ColumnDef> COL_BY_DISPLAY = new HashMap<>();

    private static void add(String sql, String display, String flash) {
        ColumnDef c = new ColumnDef(sql, display, flash);
        ALL_COLS.add(c);
        COL_BY_DISPLAY.put(display, c);
    }

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

    // ==================== ANALİZ PERİYODU ====================
    enum Period {
        WEEK("Son 1 hafta", 7),
        MONTH("Son 1 ay", 30),
        YEAR("Son 1 yıl", 365),
        ALL("Tüm veri", 0);

        final String label;
        final int days;
        Period(String label, int days) { this.label = label; this.days = days; }

        /** Bu periyodun başlangıç tarihi; ALL için null (filtre yok). */
        LocalDate cutoff() { return (days <= 0) ? null : LocalDate.now().minusDays(days); }
    }

    // ==================== 100 YÖNTEM ====================
    private static final int METHOD_COUNT = 100;
    /** Her yöntem havuzu tam bu sayıda twin maça indirmelidir. */
    private static final int TWIN_TARGET = 2;

    // Yöntemlerin kurulduğu sinyal havuzu: en çok dolu olan marketler.
    private static final String[] SIGNAL_POOL = {
            // 1x2
            "MS 1","MS X","MS 2","İY 1","İY X","İY 2","2Y 1","2Y X","2Y 2",
            // Double chance
            "ÇŞ 1X","ÇŞ 12","ÇŞ X2","İY ÇŞ 1X","İY ÇŞ 12","İY ÇŞ X2",
            // Over/Under
            "A/U 1.5 Üst","A/U 1.5 Alt","A/U 2.5 Üst","A/U 2.5 Alt","A/U 3.5 Üst","A/U 3.5 Alt",
            "İY A/U 0.5 Üst","İY A/U 0.5 Alt","İY A/U 1.5 Üst","İY A/U 1.5 Alt",
            "2Y A/U 1.5 Üst","2Y A/U 1.5 Alt",
            // KG
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

    /** Marketlerin daralma sırası (geniş → dar); yöntem içinde kolonlar buna göre sıralanır. */
    private static int familyRank(String display) {
        if (display.startsWith("HT/FT")) return 4;
        if (display.contains("Skor")) return 5;
        if (display.startsWith("KG") || display.contains("KG ")) return 3;
        if (display.contains("A/U")) return 2;
        if (display.startsWith("ÇŞ") || display.contains("ÇŞ ")) return 1;
        return 0; // 1x2
    }

    /** Bir yöntem = numara + sırayla uygulanan kolon dizisi. */
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

    /** Deterministik 100 benzersiz yöntem (sabit seed → her çalıştırmada aynı yöntemler). */
    private static List<Method> buildMethods() {
        Random rng = new Random(365_100L);
        List<Method> methods = new ArrayList<>(METHOD_COUNT);
        Set<String> seen = new HashSet<>();
        List<String> pool = new ArrayList<>(Arrays.asList(SIGNAL_POOL));
        int guard = 0;
        while (methods.size() < METHOD_COUNT && guard++ < METHOD_COUNT * 50) {
            // 6..12 kolon: büyük havuzda (tüm veri) 2 twine inebilmek için yeterli daralma gerekiyor,
            // küçük havuzda zaten 2'ye inince duruluyor.
            int len = 6 + rng.nextInt(7);
            Collections.shuffle(pool, rng);
            List<String> chosen = new ArrayList<>(pool.subList(0, len));
            chosen.sort(Comparator.comparingInt(Bet365DailySimilarityAnalyzer::familyRank)
                    .thenComparing(Comparator.naturalOrder()));
            if (!seen.add(String.join("|", new TreeSet<>(chosen)))) continue;
            ColumnDef[] cols = chosen.stream().map(COL_BY_DISPLAY::get).toArray(ColumnDef[]::new);
            methods.add(new Method(methods.size() + 1, cols));
        }
        return methods;
    }

    // ==================== HTTP ====================
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    // ==================== RAM İÇİ TABLO ====================
    private final Map<String, float[]> oddsColumns = new HashMap<>();          // sqlColumn → değerler (NaN = NULL)
    private final Map<String, Map<Integer, int[]>> colIndex = new HashMap<>(); // sqlColumn → (değer biti → sıralı satır indeksleri)
    private String[] rDate, rHome, rAway, rHt, rFt, rHtFt, rFtSide;
    private int[] rGoals;      // MS toplam gol (-1 = bilinmiyor)
    private byte[] rBtts;      // 1 = KG var, 0 = KG yok, -1 = bilinmiyor
    private int rowCount;

    private Connection conn;
    private final Period period;

    public Bet365DailySimilarityAnalyzer(Period period) {
        this.period = period;
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres", "postgres", "fuad123");
            System.out.println("✅ Veritabanına bağlanıldı.");
            loadTableIntoMemory();
        } catch (Exception e) {
            System.err.println("❌ Veritabanı hatası: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Seçilen periyot için WHERE parçası; ALL'da boş string. */
    private String periodWhere() {
        // date_time varchar; boş/bozuk kayıtlar var, sadece yyyy-MM-dd ile başlayanlar filtrelenebilir.
        return (period.cutoff() == null) ? ""
                : " WHERE date_time ~ '^\\d{4}-\\d{2}-\\d{2}' AND LEFT(date_time,10) >= ?";
    }

    private int countRows() throws SQLException {
        LocalDate cutoff = period.cutoff();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM bet365_matches" + periodWhere())) {
            if (cutoff != null) ps.setString(1, cutoff.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private void loadTableIntoMemory() throws SQLException {
        long t0 = System.currentTimeMillis();
        LocalDate cutoff = period.cutoff();
        System.out.println("⏳ Havuz belleğe yükleniyor... (" + period.label
                + (cutoff != null ? ", " + cutoff + " tarihinden itibaren" : "") + ")");

        Set<String> existing = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, "bet365_matches", null)) {
            while (rs.next()) existing.add(rs.getString("COLUMN_NAME"));
        }
        List<String> colList = ALL_COLS.stream()
                .map(c -> c.sqlColumn).filter(existing::contains).distinct().collect(Collectors.toList());

        int cap = countRows() + 16;
        rDate = new String[cap]; rHome = new String[cap]; rAway = new String[cap];
        rHt = new String[cap]; rFt = new String[cap]; rHtFt = new String[cap]; rFtSide = new String[cap];
        rGoals = new int[cap]; rBtts = new byte[cap];
        float[][] data = new float[colList.size()][cap];

        String sql = "SELECT date_time, home_team, away_team, ht_iy, ft_ms, "
                + String.join(", ", colList) + " FROM bet365_matches" + periodWhere()
                + " ORDER BY date_time DESC";
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (cutoff != null) ps.setString(1, cutoff.toString());
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
                    int[] ftGoals = scoreGoals(rFt[r]);
                    rGoals[r] = (ftGoals == null) ? -1 : ftGoals[0] + ftGoals[1];
                    rBtts[r] = (ftGoals == null) ? -1 : (byte) ((ftGoals[0] > 0 && ftGoals[1] > 0) ? 1 : 0);
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

        System.out.printf("✅ Havuz hazır: %,d satır × %d kolon (%.1f sn)%n",
                rowCount, colList.size(), (System.currentTimeMillis() - t0) / 1000.0);
        if (rowCount == 0) {
            System.out.println("⚠ Bu periyotta hiç kayıt yok — daha geniş bir periyot seçin.");
            return;
        }
        buildColumnIndex(colList);
    }

    /** Kolon-değer indeksi: satırlar date_time DESC yüklendiği için indeksler artan sırada → sıralı. */
    private void buildColumnIndex(List<String> colList) {
        long t0 = System.currentTimeMillis();
        System.out.println("🔨 Kolon indeksi inşa ediliyor...");
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

    // ==================== SCRAPER (başlamamış maçlar + oranları) ====================
    static final class MatchInfo {
        String id, home, away, date, kickoff = "-";
        final Map<String, String> odds = new HashMap<>();
    }

    private List<MatchInfo> scrapeUpcomingMatches() {
        List<MatchInfo> matches = new ArrayList<>();
        System.out.println("🔍 Flashscore'dan başlamamış maçlar çekiliyor...\n");

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             Page page = browser.newPage()) {

            page.navigate("https://www.flashscore.co.uk/football/");
            try { page.locator("#onetrust-accept-btn-handler")
                    .click(new Locator.ClickOptions().setTimeout(3000)); } catch (Exception ignored) {}
            page.waitForSelector("div[id^='g_1_'].event__match", new Page.WaitForSelectorOptions().setTimeout(15000));

            Locator rows = page.locator("div[id^='g_1_'].event__match");
            int count = rows.count();
            int skipped = 0;
            for (int i = 0; i < count; i++) {
                try {
                    Locator row = rows.nth(i);
                    if (!isNotStarted(row)) { skipped++; continue; }
                    MatchInfo mi = new MatchInfo();
                    mi.id = row.getAttribute("id").replace("g_1_", "");
                    mi.home = row.locator(".event__homeParticipant").innerText().trim();
                    mi.away = row.locator(".event__awayParticipant").innerText().trim();
                    mi.date = LocalDate.now().toString();
                    try { mi.kickoff = row.locator(".event__time").innerText().trim().replace("\n", " "); }
                    catch (Exception ignored) {}
                    matches.add(mi);
                } catch (Exception ignored) {}
            }
            System.out.println("📊 Toplam " + count + " maç bulundu → " + matches.size()
                    + " başlamamış, " + skipped + " canlı/bitmiş atlandı.\n");

        } catch (Exception e) {
            System.err.println("❌ Scraper hatası: " + e.getMessage());
            return matches;
        }

        // Oranlar paralel çekilir
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (MatchInfo mi : matches) futures.add(pool.submit(() -> fetchOddsForMatch(mi)));
        for (Future<?> f : futures) { try { f.get(); } catch (Exception ignored) {} }
        pool.shutdown();
        System.out.println("✅ " + matches.size() + " maçın oranları çekildi.\n");
        return matches;
    }

    /** Canlı ve bitmiş maçlarda "stage" bloğu (dakika / Finished) olur; başlamamışta sadece saat vardır. */
    private boolean isNotStarted(Locator row) {
        String cls = row.getAttribute("class");
        if (cls != null) {
            if (cls.contains("event__match--scheduled")) return true;
            if (cls.contains("event__match--live")) return false;
        }
        try { return row.locator(".event__stage").count() == 0; }
        catch (Exception ignored) { return false; }
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
            if ("HOME_DRAW_AWAY".equals(entry.getString("bettingType")) && "FULL_TIME".equals(entry.getString("bettingScope"))) {
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
                        String pid = item.isNull("eventParticipantId") ? null : item.getString("eventParticipantId");
                        String key = (pid == null) ? "1x2|" + period + "|Draw"
                                : pid.equals(homePartId) ? "1x2|" + period + "|Home" : "1x2|" + period + "|Away";
                        mi.odds.put(key, getOddsValue(item));
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
                        String pid = item.isNull("eventParticipantId") ? null : item.getString("eventParticipantId");
                        String key = (pid == null) ? "Double chance|" + period + "|12"
                                : pid.equals(homePartId) ? "Double chance|" + period + "|1X" : "Double chance|" + period + "|X2";
                        mi.odds.put(key, getOddsValue(item));
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

    // ==================== TWIN ARAMA (sıralı süzgeç, indeksli) ====================
    /**
     * Yöntemin kolonları sırayla uygulanır: her kolon, bugünkü maçın oran değerine eşit
     * geçmiş satırlarla kesiştirilir. Havuzu boşaltan ya da TWIN_TARGET'ın altına düşüren
     * kolon atlanır (veri kaybolmasın), tam TWIN_TARGET kalınca durulur.
     */
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
                if (bucket.length < TWIN_TARGET) continue; // tek başına hedefin altına düşürüyor → atla
                current = bucket;
            } else {
                int[] next = intersect(current, bucket);
                if (next.length < TWIN_TARGET) continue;   // bu kolon fazla daraltıyor → atla
                current = next;
            }
            if (current.length == TWIN_TARGET) break;
        }
        return current == null ? new int[0] : current;
    }

    /** İki sıralı int[] için kesişim (merge, O(m+n)). */
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

    /** Tam 2 twine inen bir yöntemin sonucu. */
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
        if (rowCount == 0) return;

        List<Method> methods = buildMethods();
        System.out.println("🧪 " + methods.size() + " farklı yöntem hazırlandı.\n");

        List<MatchInfo> upcoming = scrapeUpcomingMatches();
        if (upcoming.isEmpty()) {
            System.out.println("❌ Başlamamış maç bulunamadı!");
            return;
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("🤖 %d YÖNTEMLİ TWIN ANALİZ — %d başlamamış maç  (havuz: %s, %,d oyun)%n",
                methods.size(), upcoming.size(), period.label, rowCount);
        System.out.println("═══════════════════════════════════════════════════════════════\n");

        int totalHits = 0, matchesWithHit = 0;
        for (MatchInfo match : upcoming) {
            // Yöntemler paralel işlenir (paylaşılan indeks read-only → thread-safe)
            ConcurrentLinkedQueue<Hit> hitQ = new ConcurrentLinkedQueue<>();
            methods.parallelStream().forEach(m -> {
                int[] twins = applyMethod(match, m);
                if (twins.length == TWIN_TARGET) hitQ.add(new Hit(m.number, m.recipe(), twins));
            });
            if (hitQ.isEmpty()) continue;

            List<Hit> hits = new ArrayList<>(hitQ);
            hits.sort(Comparator.comparingInt(h -> h.methodNumber));
            matchesWithHit++;
            totalHits += hits.size();

            printMatchHeader(match, hits.size(), methods.size());
            for (Hit h : hits) printHit(h);
            printConsensus(match, hits);
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("✅ TAMAMLANDI — %d maçta toplam %d yöntem tam %d twine indi. (havuz: %s)%n",
                matchesWithHit, totalHits, TWIN_TARGET, period.label);
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    // ==================== ÇIKTI ====================
    private void printMatchHeader(MatchInfo match, int hitCount, int methodTotal) {
        System.out.println("╔══════════════════════════════════════════════════════════════");
        System.out.println("║ ⚽ MAÇ: " + match.home + " vs " + match.away
                + "   [" + match.date + " " + match.kickoff + "]");
        System.out.println("║ " + methodTotal + " yöntemden " + hitCount + " tanesi tam "
                + TWIN_TARGET + " twin maça indi.");
        System.out.println("╚══════════════════════════════════════════════════════════════");
    }

    private void printHit(Hit h) {
        System.out.println("┌─ 🔎 YÖNTEM #" + h.methodNumber);
        System.out.println("│  Filtre: " + h.recipe);
        System.out.println("│  " + String.format("%,d", rowCount) + " oyundan → " + h.twinRows.length + " twin kaldı:");
        for (int idx = 0; idx < h.twinRows.length; idx++) {
            int r = h.twinRows[idx];
            System.out.printf("│    %d. %-12s │ %-34s │ İY %-5s → MS %-5s │ HT/FT %s%n",
                    idx + 1, shortDate(rDate[r]), safe(rHome[r]) + " - " + safe(rAway[r]),
                    safe(rHt[r]), safe(rFt[r]), rHtFt[r] == null ? "?/?" : rHtFt[r]);
        }
        System.out.println("└──────────────────────────────────────────────────────────────");
    }

    /** Bütün yöntemlerin twin maçlarının ortak kararı = bu maç için tahmin. */
    private void printConsensus(MatchInfo match, List<Hit> hits) {
        Map<String, Integer> sideFreq = new HashMap<>();   // MS 1/X/2
        Map<String, Integer> htftFreq = new HashMap<>();   // HT/FT
        Map<String, Integer> ouFreq = new HashMap<>();     // 2.5 Alt/Üst
        Map<String, Integer> bttsFreq = new HashMap<>();   // KG Var/Yok
        Map<String, Integer> scoreFreq = new HashMap<>();  // MS skoru
        Set<Integer> distinctTwins = new HashSet<>();
        int twinTotal = 0;

        for (Hit h : hits) {
            for (int r : h.twinRows) {
                twinTotal++;
                distinctTwins.add(r);
                if (rFtSide[r] != null) sideFreq.merge(rFtSide[r], 1, Integer::sum);
                if (rHtFt[r] != null)   htftFreq.merge(rHtFt[r], 1, Integer::sum);
                if (rFt[r] != null)     scoreFreq.merge(rFt[r].trim(), 1, Integer::sum);
                if (rGoals[r] >= 0)     ouFreq.merge(rGoals[r] > 2 ? "2.5 ÜST" : "2.5 ALT", 1, Integer::sum);
                if (rBtts[r] >= 0)      bttsFreq.merge(rBtts[r] == 1 ? "KG VAR" : "KG YOK", 1, Integer::sum);
            }
        }

        System.out.println("  ┌─ 🔮 ORTAK KARAR — " + match.home + " vs " + match.away);
        System.out.println("  │  " + hits.size() + " yöntem × " + TWIN_TARGET + " twin = "
                + twinTotal + " twin oyun (" + distinctTwins.size() + " farklı maç)");
        printVerdictLine("MS (1/X/2) ", sideFreq, twinTotal, true);
        printVerdictLine("HT/FT      ", htftFreq, twinTotal, false);
        printVerdictLine("Toplam gol ", ouFreq, twinTotal, false);
        printVerdictLine("KG         ", bttsFreq, twinTotal, false);
        printVerdictLine("MS skoru   ", scoreFreq, twinTotal, false);
        System.out.println("  └──────────────────────────────────────────────────────────────\n");
    }

    private void printVerdictLine(String label, Map<String, Integer> freq, int total, boolean withSideName) {
        if (freq.isEmpty() || total == 0) {
            System.out.println("  │  " + label + "➜ —");
            return;
        }
        String best = freq.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("—");
        int n = freq.getOrDefault(best, 0);
        String extra = withSideName ? " (" + signName(best) + ")" : "";
        System.out.printf("  │  %s➜ %s%s  —  %d/%d twin (%%%.1f)%n",
                label, best, extra, n, total, n * 100.0 / total);
    }

    // ==================== YARDIMCILAR ====================
    private static String computeHtFt(String ht, String ft) {
        String a = scoreSign(ht), b = scoreSign(ft);
        return (a == null || b == null) ? null : a + "/" + b;
    }

    private static String scoreSign(String score) {
        int[] g = scoreGoals(score);
        if (g == null) return null;
        return g[0] > g[1] ? "1" : (g[0] < g[1] ? "2" : "X");
    }

    /** "2-1" / "2:1" biçimindeki skoru {ev, deplasman} olarak ayırır; bozuksa null. */
    private static int[] scoreGoals(String score) {
        if (score == null) return null;
        String[] p = score.trim().split("[-:]");
        if (p.length != 2) return null;
        try { return new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) }; }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String signName(String s) {
        if (s == null) return "—";
        return switch (s) { case "1" -> "Ev sahibi"; case "2" -> "Deplasman"; default -> "Beraberlik"; };
    }

    private static String safe(String s) { return (s == null || s.isBlank()) ? "-" : s; }

    /** date_time "yyyy-MM-dd HH:mm" biçiminde; tabloda sadece gün kısmı gösterilir. */
    private static String shortDate(String date) {
        if (date == null || date.isBlank()) return "-";
        return (date.length() > 10) ? date.substring(0, 10) : date;
    }

    // ==================== PERİYOT SEÇİMİ ====================
    /** Argüman ya da kullanıcı girdisini periyoda çevirir; tanınmazsa null. */
    private static Period parsePeriod(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.forLanguageTag("tr-TR"));
        return switch (s) {
            case "1", "7", "1h", "1w", "hafta", "week" -> Period.WEEK;
            case "2", "30", "1a", "1m", "ay", "month" -> Period.MONTH;
            case "3", "365", "1y", "yıl", "yil", "year" -> Period.YEAR;
            case "4", "0", "hepsi", "tüm", "tum", "all" -> Period.ALL;
            default -> null;
        };
    }

    /** Konsoldan periyot sorar; girdi yoksa (cron vb.) 1 yıl varsayılanı kullanılır. */
    private static Period askPeriod() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("📅 Twin search hangi veri aralığında yapılsın?");
            System.out.println("   1) Son 1 hafta");
            System.out.println("   2) Son 1 ay");
            System.out.println("   3) Son 1 yıl");
            System.out.println("   4) Tüm veri");
            System.out.print("Seçim (1-4): ");

            if (!scanner.hasNextLine()) {
                System.out.println("\n⚠ Girdi yok, varsayılan kullanılıyor: " + Period.YEAR.label);
                return Period.YEAR;
            }
            Period p = parsePeriod(scanner.nextLine());
            if (p != null) {
                System.out.println("✔ Seçilen periyot: " + p.label + "\n");
                return p;
            }
            System.out.println("❌ Geçersiz seçim, tekrar deneyin.\n");
        }
    }

    public static void main(String[] args) {
        Period period = (args.length > 0) ? parsePeriod(args[0]) : null;
        if (period != null) {
            System.out.println("✔ Seçilen periyot (argüman): " + period.label + "\n");
        } else {
            if (args.length > 0) System.out.println("❌ Geçersiz argüman: " + args[0] + "\n");
            period = askPeriod();
        }
        Bet365DailySimilarityAnalyzer analyzer = new Bet365DailySimilarityAnalyzer(period);
        analyzer.run();
        try { analyzer.conn.close(); } catch (Exception ignored) {}
    }
}
