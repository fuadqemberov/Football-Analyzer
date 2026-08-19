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
import java.util.*;
import java.util.concurrent.*;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  BET365 CANLI FİLTR KƏŞFİ + ANALİZ
 *
 *  Bet365DailyAutoAnalyzer-dən fərqi: HAZIR filtr siyahısı YOXDUR.
 *  Hər maç üçün filtr elə həmin an, həmin maçın öz oranları üzərində
 *  axtarılıb tapılır (beam search), sonra dərhal analiz edilir.
 *
 *  Axın:
 *    1. Flashscore-dan bugünkü maçlar + bet365 oranları
 *    2. bet365_matches yaddaşa + exact-match indeksi
 *    3. HƏR MAÇ ÜÇÜN: minlərlə kolon kombinasiyası sınanır, ən güclü
 *       "twin havuzu" verənlər seçilir
 *    4. Havuzun nəticə paylanması → siqnal; bazarın qiyməti ilə müqayisə
 *    5. Zəngin hesabat: konsensus, EV, havuz nümunələri
 *
 *  Siqnalın keçməli olduğu üç yoxlama:
 *    • HAVUZ ÖLÇÜSÜ  — çox kiçik havuz təsadüfdür
 *    • QİYMƏT UYĞUNLUĞU — havuz hədəflə eyni qiymət səviyyəsində olmalıdır,
 *      yoxsa başqa populyasiyanı müqayisə edirsən
 *    • MÜSBƏT EV — nümunə tapmaq azdır, bazarın səhv qiymətləndirməsi lazımdır
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class Bet365LiveFilterAnalyzer {

    // ─── Ayarlar ─────────────────────────────────────────────────────────
    static final int    MIN_POOL      = 150;     // havuzda minimum twin sayı
                                                 // (kiçik havuz çoxlu-sınaq həddini keçə bilmir)
    static final int    MAX_SEED_POOL = 80_000;  // bundan geniş başlanğıc = çox generik
    static final int    BEAM_WIDTH    = 16;      // hər dərinlikdə saxlanan ən yaxşı filtr sayı

    // ─── ORAN ARALIĞI ────────────────────────────────────────────────────
    // Dəqiq bərabərlik (1.50 = yalnız 1.50) havuzları həddindən artıq daraldır
    // və metodun ən zəif nöqtəsidir. Əvəzinə ±2% aralıq:
    //   1.50 → 1.47…1.53      15.00 → 14.70…15.30
    // Nisbi olduğu üçün həm aşağı, həm yüksək oranlarda düzgün işləyir.
    static final double BAND_REL     = 0.02;
    static final int    BAND_MIN_ABS = 2;        // ən azı ±0.02 (vahid: oran×100)
    static final int    MAX_DEPTH     = 4;       // filtrdə maksimum kolon sayı
    static final double MIN_EDGE      = 0.05;    // minimum konservativ üstünlük
    static final double PRICE_TOL     = 1.35;    // havuz/hədəf qiymət nisbəti həddi
    static final int    TOP_SIGNALS   = 6;       // maç başına göstərilən siqnal sayı

    static final String DB_URL  = "jdbc:postgresql://localhost:5432/postgres";
    static final String DB_USER = "postgres";
    static final String DB_PASS = "fuad123";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    // ═══════════════════════════════════════════════════════════════════
    //  KOLON TƏYİNLƏRİ  (sql kolonu ↔ göstəriş adı ↔ flashscore açarı)
    // ═══════════════════════════════════════════════════════════════════
    record Col(String sql, String name, String fsKey) {}

    static final List<Col> COLS = new ArrayList<>();
    static final Map<String, Integer> IDX = new HashMap<>();

    static {
        add("ft_1_a", "MS 1", "1x2|Full Time|Home");
        add("ft_x_a", "MS X", "1x2|Full Time|Draw");
        add("ft_2_a", "MS 2", "1x2|Full Time|Away");
        add("first_1_a", "İY 1", "1x2|1st Half|Home");
        add("first_x_a", "İY X", "1x2|1st Half|Draw");
        add("first_2_a", "İY 2", "1x2|1st Half|Away");
        add("second_1_a", "2Y 1", "1x2|2nd Half|Home");
        add("second_x_a", "2Y X", "1x2|2nd Half|Draw");
        add("second_2_a", "2Y 2", "1x2|2nd Half|Away");

        add("bts_ft_yes_a", "KG Var", "Both teams|Full Time|Yes");
        add("bts_ft_no_a", "KG Yox", "Both teams|Full Time|No");
        add("bts_first_yes_a", "İY KG Var", "Both teams|1st Half|Yes");
        add("bts_first_no_a", "İY KG Yox", "Both teams|1st Half|No");
        add("bts_second_yes_a", "2Y KG Var", "Both teams|2nd Half|Yes");
        add("bts_second_no_a", "2Y KG Yox", "Both teams|2nd Half|No");

        add("dbc_ft_1x_a", "ÇŞ 1X", "Double chance|Full Time|1X");
        add("dbc_ft_12_a", "ÇŞ 12", "Double chance|Full Time|12");
        add("dbc_ft_x2_a", "ÇŞ X2", "Double chance|Full Time|X2");
        add("dbc_first_1x_a", "İY ÇŞ 1X", "Double chance|1st Half|1X");
        add("dbc_first_12_a", "İY ÇŞ 12", "Double chance|1st Half|12");
        add("dbc_first_x2_a", "İY ÇŞ X2", "Double chance|1st Half|X2");

        String[][] scopes = {{"ft_", "", "Full Time"}, {"first_", "İY ", "1st Half"},
                             {"second_", "2Y ", "2nd Half"}};
        String[][] lines = {{"0_5", "0.5"}, {"1_5", "1.5"}, {"2_5", "2.5"},
                            {"3_5", "3.5"}, {"4_5", "4.5"}, {"5_5", "5.5"}};
        for (String[] sc : scopes) {
            int maxLine = sc[0].equals("ft_") ? 6 : 3;
            for (int i = 0; i < maxLine; i++) {
                String[] ln = lines[i];
                add(sc[0] + ln[0] + "_over_a", sc[1] + "A/Ü " + ln[1] + " Üst",
                        "Over/Under|" + sc[2] + "|O " + ln[1]);
                add(sc[0] + ln[0] + "_under_a", sc[1] + "A/Ü " + ln[1] + " Alt",
                        "Over/Under|" + sc[2] + "|U " + ln[1]);
            }
        }

        char[] cc = {'1', 'x', '2'};
        String[] ss = {"1", "X", "2"};
        for (int h = 0; h < 3; h++)
            for (int f = 0; f < 3; f++)
                add("ht_ft_" + cc[h] + cc[f] + "_a", "İY/MS " + ss[h] + "/" + ss[f],
                        "HTFT|" + ss[h] + "/" + ss[f]);

        String[] ftScores = {"1_0","2_0","2_1","3_0","3_1","3_2","4_0","4_1","4_2","4_3",
                "5_0","5_1","5_2","0_0","1_1","2_2","3_3","4_4","0_1","0_2","1_2","0_3",
                "1_3","2_3","0_4","1_4","2_4","3_4","0_5","1_5","2_5"};
        for (String s : ftScores)
            add("ft_score_" + s + "_a", "MS " + s.replace('_', ':'),
                    "Correct score|Full Time|" + s.replace('_', ':'));

        String[] htScores = {"1_0","2_0","2_1","3_0","3_1","3_2","0_0","1_1","2_2",
                "0_1","0_2","1_2","0_3","1_3","2_3"};
        for (String s : htScores)
            add("first_score_" + s + "_a", "İY " + s.replace('_', ':'),
                    "Correct score|1st Half|" + s.replace('_', ':'));
    }

    static void add(String sql, String name, String fsKey) {
        IDX.put(sql, COLS.size());
        COLS.add(new Col(sql, name, fsKey));
    }

    static int col(String sql) {
        Integer i = IDX.get(sql);
        return i == null ? -1 : i;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BAZARLAR — nəticə etiketi + seçim başına oran kolonu
    // ═══════════════════════════════════════════════════════════════════
    interface Labeler { int of(int hh, int ha, int fh, int fa); }

    static class Market {
        final String name;
        final String[] sel;
        final int[] oddsCol;
        final Labeler lab;
        byte[] label;              // sətir → seçim indeksi (-1 naməlum, 126 DİGƏR)
        final boolean primary;     // axtarış zamanı istifadə olunurmu (sürət üçün)

        Market(String name, String[] sel, String[] cols, Labeler lab, boolean primary) {
            this.name = name; this.sel = sel; this.lab = lab; this.primary = primary;
            this.oddsCol = new int[cols.length];
            for (int i = 0; i < cols.length; i++) this.oddsCol[i] = col(cols[i]);
        }
    }

    static final List<Market> MARKETS = new ArrayList<>();

    static int side(int h, int a) { return h > a ? 0 : (h == a ? 1 : 2); }

    static {
        MARKETS.add(new Market("MS", new String[]{"1", "X", "2"},
                new String[]{"ft_1_a", "ft_x_a", "ft_2_a"},
                (hh, ha, fh, fa) -> fh < 0 ? -1 : side(fh, fa), true));

        MARKETS.add(new Market("A/Ü 2.5", new String[]{"Üst 2.5", "Alt 2.5"},
                new String[]{"ft_2_5_over_a", "ft_2_5_under_a"},
                (hh, ha, fh, fa) -> fh < 0 ? -1 : (fh + fa >= 3 ? 0 : 1), true));

        MARKETS.add(new Market("A/Ü 1.5", new String[]{"Üst 1.5", "Alt 1.5"},
                new String[]{"ft_1_5_over_a", "ft_1_5_under_a"},
                (hh, ha, fh, fa) -> fh < 0 ? -1 : (fh + fa >= 2 ? 0 : 1), false));

        MARKETS.add(new Market("A/Ü 3.5", new String[]{"Üst 3.5", "Alt 3.5"},
                new String[]{"ft_3_5_over_a", "ft_3_5_under_a"},
                (hh, ha, fh, fa) -> fh < 0 ? -1 : (fh + fa >= 4 ? 0 : 1), false));

        MARKETS.add(new Market("KG", new String[]{"KG Var", "KG Yox"},
                new String[]{"bts_ft_yes_a", "bts_ft_no_a"},
                (hh, ha, fh, fa) -> fh < 0 ? -1 : (fh > 0 && fa > 0 ? 0 : 1), true));

        MARKETS.add(new Market("ÇŞ", new String[]{"1X", "12", "X2"},
                new String[]{"dbc_ft_1x_a", "dbc_ft_12_a", "dbc_ft_x2_a"},
                (hh, ha, fh, fa) -> {
                    if (fh < 0) return -1;
                    int s = side(fh, fa);
                    return s == 0 ? 0 : (s == 2 ? 2 : 0);   // qalib tərəfə görə ən dar ÇŞ
                }, false));

        String[] htftSel = new String[9];
        String[] htftCol = new String[9];
        char[] cc = {'1', 'x', '2'};
        String[] ss = {"1", "X", "2"};
        for (int h = 0; h < 3; h++)
            for (int f = 0; f < 3; f++) {
                htftSel[h * 3 + f] = ss[h] + "/" + ss[f];
                htftCol[h * 3 + f] = "ht_ft_" + cc[h] + cc[f] + "_a";
            }
        // İY/MS 9 seçimlidir — axtarış dövrəsində bahalıdır, yalnız yekun
        // qiymətləndirmədə (collect) hesablanır.
        MARKETS.add(new Market("İY/MS", htftSel, htftCol,
                (hh, ha, fh, fa) -> (hh < 0 || fh < 0) ? -1 : side(hh, ha) * 3 + side(fh, fa), false));

        MARKETS.add(new Market("İY MS", new String[]{"İY 1", "İY X", "İY 2"},
                new String[]{"first_1_a", "first_x_a", "first_2_a"},
                (hh, ha, fh, fa) -> hh < 0 ? -1 : side(hh, ha), false));

        MARKETS.add(new Market("İY A/Ü 0.5", new String[]{"İY Üst 0.5", "İY Alt 0.5"},
                new String[]{"first_0_5_over_a", "first_0_5_under_a"},
                (hh, ha, fh, fa) -> hh < 0 ? -1 : (hh + ha >= 1 ? 0 : 1), false));

        MARKETS.add(new Market("İY A/Ü 1.5", new String[]{"İY Üst 1.5", "İY Alt 1.5"},
                new String[]{"first_1_5_over_a", "first_1_5_under_a"},
                (hh, ha, fh, fa) -> hh < 0 ? -1 : (hh + ha >= 2 ? 0 : 1), false));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  YADDAŞDAKI CƏDVƏL
    // ═══════════════════════════════════════════════════════════════════
    private Connection conn;
    private int n;
    private short[][] odds;                    // [kolon][sətir], oran×100
    private byte[] htH, htA, ftH, ftA;
    private String[] home, away, dateStr;
    private long[] sig;                        // dublikat maç imzası

    // Kolon başına dəyərə görə sıralanmış indeks — aralıq sorğusu üçün.
    // orderByVal[c] : sətir indeksləri, oran dəyərinə görə artan sırada
    // valsSorted[c] : hər mövqedəki oran dəyəri (artan) — ikili axtarış üçün
    private int[][] orderByVal;
    private short[][] valsSorted;

    // ═══════════════════════════════════════════════════════════════════
    //  BUGÜNKÜ MAÇ
    // ═══════════════════════════════════════════════════════════════════
    static class Live {
        String id, home, away, kickoff = "";
        short[] o = new short[COLS.size()];
        int cnt;
        boolean flipped;
    }

    // ─── Tapılan siqnal ──────────────────────────────────────────────────
    static class Signal {
        int[] cols;
        Market market;
        int sel;
        int poolN, poolHit;
        double share, odds, evLower, poolMedian, impliedAvg, diff, z;
        int[] pool;
        int oddsCol, lo, hi;      // qiymət-uyğun havuzu bərpa etmək üçün

        double edge() { return share * odds - 1; }
        String filterText(Live m) {
            StringJoiner sj = new StringJoiner("  ·  ");
            for (int c : cols) {
                int[] b = band(m.o[c]);
                sj.add(String.format(Locale.US, "%s %.2f–%.2f",
                        COLS.get(c).name(), b[0] / 100.0, b[1] / 100.0));
            }
            return sj.toString();
        }
        String key() { return market.name + "|" + sel; }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ƏSAS
    // ═══════════════════════════════════════════════════════════════════
    /**
     * İstifadə:  [z-həddi]  [maç-limiti]
     *   z-həddi verilməzsə, çoxlu-sınaq düzəlişi ilə hesablanan "dürüst" hədd
     *   işlədilir (≈5.5) — praktikada demək olar heç nə keçmir.
     *   Kəşf üçün 3.0 kimi aşağı hədd ver; hər namizədin statusu göstərilir.
     */
    public static void main(String[] args) throws Exception {
        double z = args.length > 0 ? Double.parseDouble(args[0]) : -1;
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        Bet365LiveFilterAnalyzer app = new Bet365LiveFilterAnalyzer();
        app.zOverride = z;
        app.run(limit);
    }

    private double zOverride = -1;
    private double zHonest = 5.5;

    void run(int limit) throws Exception {
        System.out.println("═".repeat(78));
        System.out.println("  BET365 — ANLIQ FİLTR KƏŞFİ VƏ ANALİZ");
        System.out.println("═".repeat(78) + "\n");

        List<Live> today = scrapeToday(limit);
        if (today.isEmpty()) { System.out.println("❌ Bugün üçün oranlı maç tapılmadı."); return; }

        connect();
        loadTable();
        buildIndex();
        buildLabels();

        System.out.println("═".repeat(78));
        System.out.println("  ANALİZ — " + today.size() + " maç");
        System.out.println("═".repeat(78) + "\n");

        matchCount = today.size();
        List<Object[]> allSignals = new ArrayList<>();   // {Live, Signal}
        for (Live m : today) {
            long t0 = System.currentTimeMillis();
            Search res = discover(m);
            long ms = System.currentTimeMillis() - t0;
            printMatch(m, res, ms);
            for (Signal s : res.best) allSignals.add(new Object[]{m, s});
        }

        printSummary(allSignals, today.size());
        conn.close();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  1) SCRAPE
    // ═══════════════════════════════════════════════════════════════════
    List<Live> scrapeToday(int limit) {
        List<Live> out = new ArrayList<>();
        System.out.println("🔍 Flashscore-dan bugünkü maçlar çəkilir...");
        try (Playwright pw = Playwright.create();
             Browser br = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             Page page = br.newPage()) {

            page.navigate("https://www.flashscore.co.uk/football/");
            try { page.locator("#onetrust-accept-btn-handler")
                    .click(new Locator.ClickOptions().setTimeout(3000)); } catch (Exception ignored) {}
            page.waitForSelector("div[id^='g_1_'].event__match",
                    new Page.WaitForSelectorOptions().setTimeout(20000));

            Locator rows = page.locator("div[id^='g_1_'].event__match");
            int count = rows.count();
            System.out.println("📊 " + count + " maç tapıldı.");
            for (int i = 0; i < count && (limit <= 0 || out.size() < limit); i++) {
                try {
                    Locator r = rows.nth(i);
                    Live m = new Live();
                    m.id = r.getAttribute("id").replace("g_1_", "");
                    m.home = r.locator(".event__homeParticipant").innerText().trim();
                    m.away = r.locator(".event__awayParticipant").innerText().trim();
                    try { m.kickoff = r.locator(".event__time").innerText().trim(); }
                    catch (Exception ignored) {}
                    out.add(m);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("❌ Scraper xətası: " + e.getMessage());
            return out;
        }

        System.out.println("⏳ " + out.size() + " maçın bet365 oranları çəkilir...");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> fs = new ArrayList<>();
        for (Live m : out) fs.add(pool.submit(() -> fetchOdds(m)));
        for (Future<?> f : fs) { try { f.get(); } catch (Exception ignored) {} }
        pool.shutdown();

        out.removeIf(m -> m.cnt < 8);
        long flip = out.stream().filter(m -> m.flipped).count();
        System.out.println("✅ " + out.size() + " maçda kifayət qədər oran var"
                + (flip > 0 ? " (" + flip + " maçda ev/qonaq düzəldildi)" : "") + ".\n");
        return out;
    }

    void fetchOdds(Live m) {
        String url = String.format("https://global.ds.lsapp.eu/odds/pq_graphql?_hash=oce"
                + "&eventId=%s&projectId=5&geoIpCode=AZ&geoIpSubdivisionCode=AZBA", m.id);
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body().startsWith("{")) parseOdds(m, resp.body());
        } catch (Exception ignored) {}
    }

    void parseOdds(Live m, String body) {
        Map<String, String> raw = new HashMap<>();
        JSONObject data = new JSONObject(body).optJSONObject("data");
        if (data == null) return;
        JSONObject od = data.optJSONObject("findOddsByEventId");
        if (od == null) return;
        JSONArray list = od.optJSONArray("odds");
        if (list == null) return;

        String homePid = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            if (e.optInt("bookmakerId") != 16) continue;
            if ("HOME_DRAW_AWAY".equals(e.optString("bettingType"))
                    && "FULL_TIME".equals(e.optString("bettingScope"))) {
                JSONArray it = e.getJSONArray("odds");
                for (int j = 0; j < it.length(); j++) {
                    JSONObject o = it.getJSONObject(j);
                    if (!o.isNull("eventParticipantId")) { homePid = o.getString("eventParticipantId"); break; }
                }
                break;
            }
        }

        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            if (e.optInt("bookmakerId") != 16) continue;
            String type = e.optString("bettingType");
            String period = scope(e.optString("bettingScope"));
            JSONArray items = e.optJSONArray("odds");
            if (items == null) continue;

            switch (type) {
                case "HOME_DRAW_AWAY" -> {
                    if (period == null) break;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject o = items.getJSONObject(j);
                        String pid = o.isNull("eventParticipantId") ? null : o.getString("eventParticipantId");
                        String k = pid == null ? "1x2|" + period + "|Draw"
                                : pid.equals(homePid) ? "1x2|" + period + "|Home" : "1x2|" + period + "|Away";
                        raw.put(k, val(o));
                    }
                }
                case "BOTH_TEAMS_TO_SCORE" -> {
                    if (period == null) break;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject o = items.getJSONObject(j);
                        raw.put("Both teams|" + period + "|" + (o.optBoolean("bothTeamsToScore") ? "Yes" : "No"), val(o));
                    }
                }
                case "OVER_UNDER" -> {
                    if (period == null) break;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject o = items.getJSONObject(j);
                        if (o.isNull("handicap")) continue;
                        double h = o.getJSONObject("handicap").getDouble("value");
                        raw.put("Over/Under|" + period + "|"
                                + ("OVER".equals(o.optString("selection")) ? "O " : "U ") + h, val(o));
                    }
                }
                case "DOUBLE_CHANCE" -> {
                    if (period == null) break;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject o = items.getJSONObject(j);
                        String pid = o.isNull("eventParticipantId") ? null : o.getString("eventParticipantId");
                        String k = pid == null ? "Double chance|" + period + "|12"
                                : pid.equals(homePid) ? "Double chance|" + period + "|1X"
                                : "Double chance|" + period + "|X2";
                        raw.put(k, val(o));
                    }
                }
                case "CORRECT_SCORE" -> {
                    if (period == null || "2nd Half".equals(period)) break;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject o = items.getJSONObject(j);
                        if (o.isNull("score")) continue;
                        raw.put("Correct score|" + period + "|" + o.getString("score").replace(" ", ""), val(o));
                    }
                }
                case "HALF_FULL_TIME" -> {
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject o = items.getJSONObject(j);
                        if (!o.isNull("winner")) raw.put("HTFT|" + o.getString("winner"), val(o));
                    }
                }
            }
        }

        if (!fixOrientation(raw, m)) return;

        for (Col c : COLS) {
            String v = raw.get(c.fsKey());
            short s = toShort(v);
            if (s != 0) { m.o[IDX.get(c.sql())] = s; m.cnt++; }
        }
    }

    /**
     * Flashscore-un GraphQL cavabında ev/qonaq seçimlərinin sırası TƏMİNATLI DEYİL —
     * təxminən hər ikinci maçda 1X2 və ÇŞ tərsinə düşür (yoxlanıb: SK Rapid — Paide
     * üçün ev qalibiyyəti 17.00 görünürdü, əslində 1.11).
     *
     * Etalon olaraq participant-id-dən asılı OLMAYAN bazarlar götürülür:
     * dəqiq hesab ("2:0" həmişə ev:qonaq) və İY/MS ("1/1" həmişə ev).
     */
    boolean fixOrientation(Map<String, String> raw, Live m) {
        double p1 = prob(raw.get("1x2|Full Time|Home"));
        double p2 = prob(raw.get("1x2|Full Time|Away"));
        if (p1 == 0 || p2 == 0) return false;
        if (Math.abs(p1 - p2) < 0.02) return true;

        double ref = 0;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (!e.getKey().startsWith("Correct score|Full Time|")) continue;
            String sc = e.getKey().substring(e.getKey().lastIndexOf('|') + 1);
            int c = sc.indexOf(':');
            if (c < 0) continue;
            try {
                int h = Integer.parseInt(sc.substring(0, c));
                int a = Integer.parseInt(sc.substring(c + 1));
                if (h > a) ref += prob(e.getValue());
                else if (h < a) ref -= prob(e.getValue());
            } catch (NumberFormatException ignored) {}
        }
        if (Math.abs(ref) < 0.03) ref = prob(raw.get("HTFT|1/1")) - prob(raw.get("HTFT|2/2"));
        if (Math.abs(ref) < 0.01) return false;

        if ((p1 > p2) == (ref > 0)) return true;
        for (String period : new String[]{"Full Time", "1st Half", "2nd Half"}) {
            swap(raw, "1x2|" + period + "|Home", "1x2|" + period + "|Away");
            swap(raw, "Double chance|" + period + "|1X", "Double chance|" + period + "|X2");
        }
        m.flipped = true;
        return true;
    }

    static void swap(Map<String, String> map, String a, String b) {
        String va = map.get(a), vb = map.get(b);
        if (va == null && vb == null) return;
        if (vb == null) map.remove(a); else map.put(a, vb);
        if (va == null) map.remove(b); else map.put(b, va);
    }

    static double prob(String o) {
        double v = num(o);
        return v > 1 ? 1 / v : 0;
    }

    static double num(String s) {
        if (s == null || s.isEmpty() || "-".equals(s)) return 0;
        try { return Double.parseDouble(s.trim().replace(',', '.')); }
        catch (NumberFormatException e) { return 0; }
    }

    static short toShort(String s) {
        double v = num(s);
        if (v <= 1.0 || v > 320) return 0;
        return (short) Math.round(v * 100);
    }

    static String scope(String s) {
        return switch (s) {
            case "FULL_TIME" -> "Full Time";
            case "FIRST_HALF" -> "1st Half";
            case "SECOND_HALF" -> "2nd Half";
            default -> null;
        };
    }

    static String val(JSONObject o) {
        try {
            if (!o.isNull("opening")) return o.getString("opening");
            if (!o.isNull("value")) return o.getString("value");
        } catch (Exception ignored) {}
        return "-";
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2) BAZA → YADDAŞ
    // ═══════════════════════════════════════════════════════════════════
    void connect() throws Exception {
        Class.forName("org.postgresql.Driver");
        conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    void loadTable() throws SQLException {
        long t0 = System.currentTimeMillis();
        int total;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM bet365_matches")) {
            rs.next(); total = rs.getInt(1);
        }
        n = total;
        odds = new short[COLS.size()][total];
        htH = new byte[total]; htA = new byte[total];
        ftH = new byte[total]; ftA = new byte[total];
        home = new String[total]; away = new String[total]; dateStr = new String[total];

        StringBuilder sql = new StringBuilder(
                "SELECT home_team, away_team, ht_iy, ft_ms, date_time");
        for (Col c : COLS) sql.append(",").append(c.sql());
        sql.append(" FROM bet365_matches");

        System.out.println("⏳ bet365_matches yaddaşa yüklənir (" + total + " sətir)...");
        conn.setAutoCommit(false);
        int i = 0;
        try (Statement st = conn.createStatement()) {
            st.setFetchSize(10000);
            try (ResultSet rs = st.executeQuery(sql.toString())) {
                while (rs.next() && i < total) {
                    home[i] = rs.getString(1);
                    away[i] = rs.getString(2);
                    int[] ht = score(rs.getString(3));
                    int[] ft = score(rs.getString(4));
                    htH[i] = (byte) ht[0]; htA[i] = (byte) ht[1];
                    ftH[i] = (byte) ft[0]; ftA[i] = (byte) ft[1];
                    dateStr[i] = rs.getString(5);
                    for (int c = 0; c < COLS.size(); c++) {
                        String raw = rs.getString(6 + c);
                        odds[c][i] = toShort(raw);
                    }
                    i++;
                }
            }
        } finally { conn.setAutoCommit(true); }
        n = i;

        sig = new long[n];
        for (int r = 0; r < n; r++) {
            long h = 1469598103934665603L;
            for (int c = 0; c < COLS.size(); c++) { h ^= odds[c][r]; h *= 1099511628211L; }
            sig[r] = h;
        }
        System.out.printf("✅ %d sətir yükləndi (%.1f sn)%n", n, (System.currentTimeMillis() - t0) / 1000.0);
    }

    static int[] score(String s) {
        if (s != null) {
            int d = s.indexOf('-');
            if (d < 0) d = s.indexOf(':');
            if (d > 0) {
                try {
                    int a = Integer.parseInt(s.substring(0, d).trim());
                    int b = Integer.parseInt(s.substring(d + 1).trim());
                    if (a >= 0 && b >= 0 && a < 90 && b < 90) return new int[]{a, b};
                } catch (NumberFormatException ignored) {}
            }
        }
        return new int[]{-1, -1};
    }

    /**
     * Dəyərə görə sıralanmış indeks (counting sort — O(n) kolon başına).
     * Aralıq sorğusu: valsSorted üzərində ikili axtarışla [lo,hi] dilimi tapılır.
     */
    void buildIndex() {
        long t0 = System.currentTimeMillis();
        System.out.println("🔨 Aralıq indeksi qurulur...");
        orderByVal = new int[COLS.size()][];
        valsSorted = new short[COLS.size()][];
        int[] cnt = new int[32770];
        int[] pos = new int[32770];
        for (int c = 0; c < COLS.size(); c++) {
            short[] cv = odds[c];
            Arrays.fill(cnt, 0);
            int total = 0;
            for (int r = 0; r < n; r++) {
                short v = cv[r];
                if (v != 0) { cnt[v]++; total++; }
            }
            int[] order = new int[total];
            short[] vals = new short[total];
            int run = 0;
            for (int v = 0; v < cnt.length; v++) { pos[v] = run; run += cnt[v]; }
            for (int r = 0; r < n; r++) {          // r artan → qrup daxilində sətirlər sıralı
                short v = cv[r];
                if (v == 0) continue;
                int p = pos[v]++;
                order[p] = r;
                vals[p] = v;
            }
            orderByVal[c] = order;
            valsSorted[c] = vals;
        }
        System.out.printf("✅ İndeks hazır (%.1f sn)%n", (System.currentTimeMillis() - t0) / 1000.0);
    }

    /** Bir kolonda [lo,hi] oran aralığına düşən sətirlər — sətir indeksinə görə sıralı. */
    int[] bandRows(int c, int lo, int hi) {
        short[] vals = valsSorted[c];
        if (vals.length == 0) return new int[0];
        int s = lowerBound(vals, lo);
        int e = lowerBound(vals, hi + 1);
        if (e <= s) return new int[0];
        int[] out = Arrays.copyOfRange(orderByVal[c], s, e);
        Arrays.sort(out);      // dilim dəyərə görə sıralıdır → sətirə görə sırala
        return out;
    }

    static int lowerBound(short[] a, int key) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Verilmiş oran üçün ±BAND_REL aralığı (vahid: oran×100). */
    static int[] band(short v) {
        int d = Math.max(BAND_MIN_ABS, (int) Math.round(v * BAND_REL));
        return new int[]{v - d, v + d};
    }

    void buildLabels() {
        for (Market m : MARKETS) {
            byte[] lab = new byte[n];
            for (int r = 0; r < n; r++) {
                int v = m.lab.of(htH[r], htA[r], ftH[r], ftA[r]);
                lab[r] = (byte) (v < 0 ? -1 : Math.min(v, 126));
            }
            m.label = lab;
        }
        System.out.println("✅ Bazar etiketləri hazır (" + MARKETS.size() + " bazar).\n");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  3) ANLIQ FİLTR KƏŞFİ  (beam search)
    // ═══════════════════════════════════════════════════════════════════
    static class Node {
        int[] cols;
        int[] pool;
        double score;
    }

    static class Search {
        List<Signal> best = new ArrayList<>();
        int tried, kept;
        long nTests;
        double zBar, zHonest;
    }

    /** Çoxlu-sınaq həddi (√(2·ln N)) və günün maç sayı. */
    private double zBar = 4.5;
    private int matchCount = 1;

    Search discover(Live m) {
        Search out = new Search();

        // Yararlı kolonlar: maçda oran var + aralığa düşən sətir sayı münasibdir.
        // Hər kolonun aralıq havuzu maç başına BİR DƏFƏ hesablanır və saxlanır.
        List<int[]> usable = new ArrayList<>();       // {colIndex}
        Map<Integer, int[]> bandPool = new HashMap<>();
        for (int c = 0; c < COLS.size(); c++) {
            short v = m.o[c];
            if (v == 0) continue;
            int[] b = band(v);
            int[] rows = bandRows(c, b[0], b[1]);
            if (rows.length < MIN_POOL || rows.length > MAX_SEED_POOL) continue;
            usable.add(new int[]{c});
            bandPool.put(c, rows);
        }
        if (usable.isEmpty()) return out;

        // Aparılacaq test sayının qabaqcadan qiymətləndirilməsi → əhəmiyyət həddi
        int totalSel = 0;
        for (Market mk : MARKETS) totalSel += mk.sel.length;
        // Hədd bütün günün axtarışına görə qurulur, tək maça görə yox: günün
        // ƏN YAXŞI siqnalını seçmək bütün maçlarda aparılan testlərin
        // maksimumunu götürmək deməkdir.
        long nodes = usable.size() + (long) BEAM_WIDTH * usable.size() * (MAX_DEPTH - 1);
        long nTests = Math.max(10, nodes * totalSel * Math.max(1, matchCount));
        zHonest = Math.max(3.0, Math.sqrt(2 * Math.log(nTests)));
        zBar = zOverride > 0 ? zOverride : zHonest;
        out.nTests = nTests;
        out.zBar = zBar;
        out.zHonest = zHonest;

        // Başlanğıc: hər yararlı kolon tək başına bir filtr
        List<Node> beam = new ArrayList<>();
        for (int[] u : usable) {
            Node nd = new Node();
            nd.cols = new int[]{u[0]};
            nd.pool = bandPool.get(u[0]);
            nd.score = quickScore(nd.pool, m);
            beam.add(nd);
            out.tried++;
        }
        beam.sort((a, b) -> Double.compare(b.score, a.score));
        if (beam.size() > BEAM_WIDTH) beam = new ArrayList<>(beam.subList(0, BEAM_WIDTH));

        Map<String, Signal> found = new LinkedHashMap<>();
        collect(beam, m, found);

        // Dərinləşdirmə
        for (int depth = 2; depth <= MAX_DEPTH; depth++) {
            List<Node> next = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Node nd : beam) {
                for (int[] u : usable) {
                    int c = u[0];
                    if (contains(nd.cols, c)) continue;
                    int[] p = intersect(nd.pool, bandPool.get(c));
                    out.tried++;
                    if (p.length < MIN_POOL) continue;
                    if (p.length == nd.pool.length) continue;   // heç nə süzmədi
                    int[] cols = Arrays.copyOf(nd.cols, nd.cols.length + 1);
                    cols[cols.length - 1] = c;
                    Arrays.sort(cols);
                    String k = Arrays.toString(cols);
                    if (!seen.add(k)) continue;
                    Node nn = new Node();
                    nn.cols = cols; nn.pool = p;
                    nn.score = quickScore(p, m);
                    next.add(nn);
                }
            }
            if (next.isEmpty()) break;
            next.sort((a, b) -> Double.compare(b.score, a.score));
            beam = new ArrayList<>(next.subList(0, Math.min(BEAM_WIDTH, next.size())));
            collect(beam, m, found);
        }

        List<Signal> list = new ArrayList<>(found.values());
        list.sort((a, b) -> Double.compare(b.z, a.z));   // statistik dayaqlılığa görə
        out.best = list.size() > TOP_SIGNALS ? list.subList(0, TOP_SIGNALS) : list;
        out.kept = list.size();
        return out;
    }

    /** Axtarış zamanı ucuz qiymətləndirmə — yalnız əsas bazarlar. */
    double quickScore(int[] pool, Live m) {
        double best = -1;
        for (Market mk : MARKETS) {
            if (!mk.primary) continue;
            Signal s = evaluate(pool, m, mk, true);
            if (s != null && s.evLower > best) best = s.evLower;
        }
        return best;
    }

    /** Beam-dəki filtrlərin bütün bazarlar üzrə tam qiymətləndirilməsi. */
    void collect(List<Node> beam, Live m, Map<String, Signal> found) {
        for (Node nd : beam) {
            for (Market mk : MARKETS) {
                Signal s = evaluate(nd.pool, m, mk, false);
                if (s == null || s.evLower < MIN_EDGE) continue;
                s.cols = nd.cols;
                Signal prev = found.get(s.key());
                if (prev == null || s.evLower > prev.evLower) found.put(s.key(), s);
            }
        }
    }

    /**
     * Havuzu bir bazar üzrə qiymətləndirir.
     * Üç şərt: havuz ölçüsü, qiymət uyğunluğu, müsbət konservativ EV.
     */
    Signal evaluate(int[] pool, Live m, Market mk, boolean quick) {
        Signal best = null;
        byte[] lab = mk.label;

        // Hər seçim AYRICA qiymətləndirilir, çünki qiymət uyğunluğu seçimə bağlıdır.
        for (int s = 0; s < mk.sel.length; s++) {
            int oc = mk.oddsCol[s];
            if (oc < 0) continue;
            short tv = m.o[oc];
            if (tv == 0) continue;
            double o = tv / 100.0;

            // ── ƏSAS QAYDA ──
            // Havuza yalnız bu mərci hədəflə OXŞAR QİYMƏTƏ təklif edən maçlar girir.
            // Median yoxlaması kifayət etmir: havuz qarışıq olanda (əsasən ev-favoriti,
            // aralarında bir neçə qonaq-favoriti maç) median keçir, amma uğurların
            // hamısı müqayisəolunmaz azlıqdan gəlir və saxta "+228% EV" doğurur.
            int lo = (int) Math.round(tv / PRICE_TOL);
            int hi = (int) Math.round(tv * PRICE_TOL);
            short[] ocv = odds[oc];

            int tot = 0, hit = 0;
            double sumImplied = 0;
            for (int r : pool) {
                short v = ocv[r];
                if (v == 0 || v < lo || v > hi) continue;
                byte L = lab[r];
                if (L < 0) continue;
                if (m.home.equals(home[r]) && m.away.equals(away[r])) continue;
                tot++;
                sumImplied += 100.0 / v;
                if (L == s) hit++;
            }
            if (tot < MIN_POOL) continue;

            // ── KALİBRASİYA FƏRQİ ──
            // Havuzun tutma faizini hədəfin qiymətinə qarşı ölçmək YANLIŞDIR:
            // qiymət pəncərəsi daxilində aşağı oranlı (daha ehtimallı) maçlar
            // üstünlük təşkil edir, ona görə istənilən havuz hədəfdən "yaxşı"
            // görünür və saxta müsbət EV doğurur.
            //
            // Doğru ölçü: havuz bukmekerin ÖZ qiymətini üstələyibmi?
            //   fərq = real tezlik − havuzun orta nəzərdə tutduğu ehtimal
            // Bu fərq bukmekerin həmin nümunədəki kalibrasiya səhvidir və
            // qiymət səviyyəsindən asılı olmayaraq hədəfə köçürülə bilər.
            //
            // Havuzun qiymətinə marja daxildir (~3 xal), yəni filtr müsbət
            // fərq göstərməkçün əvvəlcə marjanı keçməlidir.
            double actual = (double) hit / tot;
            double impliedAvg = sumImplied / tot;
            double diff = actual - impliedAvg;

            // ── ÇOXLU-SINAQ DÜZƏLİŞİ ──
            // Maç başına on minlərlə kombinasiya sınanır. Adi 95% həddi (z=1.96)
            // tək test üçündür; on min testdə saf təsadüfdən yüzlərlə "95% əmin"
            // nəticə çıxır. Hədd N testin maksimumuna görə qaldırılır:
            //   z ≈ √(2·ln N)   — N standart normal dəyişənin maksimumu
            // Standart xəta SIFIR FƏRZİYYƏSİ altında (bukmekerin qiymətinə görə)
            // hesablanır, müşahidə olunan tezliyə görə YOX. Müşahidə 0/1-ə yaxın
            // olanda (məs. 170/177) müşahidə-əsaslı xəta süni kiçilir və z-i
            // şişirdir: eyni siqnal z=6.23 əvəzinə z=3.58 verir.
            double se = Math.sqrt(Math.max(impliedAvg * (1 - impliedAvg), 1e-9) / tot);
            if (diff <= zBar * se) continue;

            // EV = (hədəfin nəzərdə tutduğu ehtimal + fərq) × oran − 1  =  fərq × oran
            double ev = diff * o;
            if (ev < (quick ? 0.01 : MIN_EDGE)) continue;
            double zVal = diff / se;
            if (best != null && zVal <= best.z) continue;   // ən əhəmiyyətlisini saxla

            Signal sig = new Signal();
            sig.market = mk; sig.sel = s;
            sig.poolN = tot; sig.poolHit = hit;
            sig.share = actual; sig.impliedAvg = impliedAvg; sig.diff = diff;
            sig.odds = o; sig.evLower = ev; sig.z = zVal;
            sig.poolMedian = 1.0 / impliedAvg;
            sig.pool = pool;
            sig.oddsCol = oc; sig.lo = lo; sig.hi = hi;
            best = sig;
        }
        return best;
    }

    static double wilson(int hits, int n) {
        if (n == 0) return 0;
        double z = 1.96, p = (double) hits / n, z2 = z * z;
        double denom = 1 + z2 / n;
        double centre = p + z2 / (2.0 * n);
        double margin = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n));
        return Math.max(0, (centre - margin) / denom);
    }

    static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }

    static int[] intersect(int[] a, int[] b) {
        if (a == null || b == null) return new int[0];
        if (a.length > b.length) { int[] t = a; a = b; b = t; }
        int[] r = new int[a.length];
        int k = 0;
        if ((long) a.length * 32 < b.length) {
            for (int x : a) if (Arrays.binarySearch(b, x) >= 0) r[k++] = x;
        } else {
            int i = 0, j = 0;
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) { r[k++] = a[i++]; j++; }
                else if (a[i] < b[j]) i++;
                else j++;
            }
        }
        return Arrays.copyOf(r, k);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4) HESABAT
    // ═══════════════════════════════════════════════════════════════════
    void printMatch(Live m, Search res, long ms) {
        System.out.println("─".repeat(78));
        System.out.printf("⚽ %s%s — %s%n",
                m.kickoff.isEmpty() ? "" : "[" + m.kickoff + "]  ", m.home, m.away);
        System.out.printf("   MS %s / %s / %s   |   A/Ü 2.5 %s / %s   |   KG %s / %s%n",
                fmt(m, "ft_1_a"), fmt(m, "ft_x_a"), fmt(m, "ft_2_a"),
                fmt(m, "ft_2_5_over_a"), fmt(m, "ft_2_5_under_a"),
                fmt(m, "bts_ft_yes_a"), fmt(m, "bts_ft_no_a"));
        System.out.printf("   🔎 %,d kombinasiya · ~%,d test · hədd z=%.2f (dürüst hədd %.2f) · %d siqnal (%d ms)%n",
                res.tried, res.nTests, res.zBar, res.zHonest, res.kept, ms);

        if (res.best.isEmpty()) {
            System.out.println("   ⚪ Bu maçda şərtləri ödəyən filtr yoxdur.\n");
            return;
        }
        System.out.println();
        for (Signal s : res.best) {
            System.out.printf("   %s %-14s → %-10s @%.2f | EV %+.1f%% | z=%.2f %s | havuz %d (%d/%d)%n",
                    status(s, res.zHonest).charAt(0) == 'T' ? "★" : "•",
                    s.market.name, s.market.sel[s.sel], s.odds, s.evLower * 100,
                    s.z, status(s, res.zHonest), s.poolN, s.poolHit, s.poolN);
            System.out.printf("      filtr: %s%n", s.filterText(m));
            System.out.printf("      kalibrasiya: bukmeker %.1f%% qiymətləndirib, real %.1f%% oldu → %+.1f xal üstünlük%n",
                    s.impliedAvg * 100, s.share * 100, s.diff * 100);
            printTwins(s);
            System.out.println();
        }
    }

    /** Siqnalın statistik statusu — çoxlu-sınaq həddinə görə. */
    static String status(Signal s, double zHonest) {
        if (s.z >= zHonest) return "TƏSDİQLİ";
        if (s.z >= zHonest * 0.75) return "güclü namizəd";
        if (s.z >= zHonest * 0.55) return "zəif namizəd";
        return "təsadüf ehtimalı yüksək";
    }

    void printTwins(Signal s) {
        int shown = 0;
        System.out.println("      havuzdan nümunələr (mərc tutan):");
        short[] ocv = odds[s.oddsCol];
        for (int r : s.pool) {
            if (shown >= 3) break;
            if (ftH[r] < 0) continue;
            short v = ocv[r];
            if (v == 0 || v < s.lo || v > s.hi) continue;   // qiymət-uyğun havuz
            byte L = s.market.label[r];
            if (L != s.sel) continue;
            String dt = (dateStr[r] == null || dateStr[r].isEmpty()) ? "—" : dateStr[r];
            System.out.printf("        %-11s %-22s %-22s İY %d-%d  MS %d-%d%n",
                    dt, trunc(home[r], 22), trunc(away[r], 22),
                    htH[r], htA[r], ftH[r], ftA[r]);
            shown++;
        }
        if (shown == 0) System.out.println("        (nümunə tapılmadı)");
    }

    void printSummary(List<Object[]> all, int matchCount) {
        System.out.println("═".repeat(78));
        System.out.println("  GÜNÜN ƏN GÜCLÜ SİQNALLARI");
        System.out.println("═".repeat(78));
        if (all.isEmpty()) {
            System.out.println("Bu gün heç bir maçda şərtləri ödəyən siqnal tapılmadı.");
            System.out.println("Siqnal olmaması da nəticədir — bazar bugünkü maçları düzgün qiymətləndirib.");
            return;
        }
        all.sort((a, b) -> Double.compare(((Signal) b[1]).z, ((Signal) a[1]).z));
        int lim = Math.min(20, all.size());
        for (int i = 0; i < lim; i++) {
            Live m = (Live) all.get(i)[0];
            Signal s = (Signal) all.get(i)[1];
            System.out.printf("%2d. %-24s — %-24s | %-12s → %-10s @%.2f | EV %+5.1f%% | z=%.2f  %s%n",
                    i + 1, trunc(m.home, 24), trunc(m.away, 24),
                    s.market.name, s.market.sel[s.sel], s.odds, s.evLower * 100,
                    s.z, status(s, zHonest));
        }
        System.out.printf("%n%d maç analiz edildi, %d siqnal tapıldı.%n", matchCount, all.size());
        System.out.printf("""

            EV = (havuzun real tezliyi − havuzun orta qiyməti) × oran.
            Ölçülən şey bukmekerin KALİBRASİYA SƏHVİDİR, xam tutma faizi deyil.

            z = sapmanın standart xətaya nisbəti. Bu gedişdə milyonlarla kombinasiya
            sınandığı üçün dürüst hədd z=%.2f-dir: yalnız bunu keçən siqnal
            "TƏSDİQLİ" sayılır. Aşağıdakılar maraqlı ola bilər, amma statistik
            olaraq təsadüfdən ayırd edilə bilmir — kağız üzərində izlə, pul qoyma.%n""",
                zHonest);
    }

    String fmt(Live m, String sql) {
        int c = col(sql);
        if (c < 0 || m.o[c] == 0) return "—";
        return String.format(Locale.US, "%.2f", m.o[c] / 100.0);
    }

    static String trunc(String s, int max) {
        if (s == null) return "—";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
