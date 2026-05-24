package analyzer.bet365;

import java.sql.*;
import java.util.*;
import java.util.stream.*;

public class Bet365FilterAnalyzer {

    // ═══════════════════════════════════════════════════════════════════════
    // ✏️  BURAYA FİLTRELERİ EKLE / DEĞİŞTİR
    //     Her iç liste = bir filtre seti
    //     Kolon isimleri ALL_ODDS_COLS'daki displayName ile birebir eşleşmeli
    // ═══════════════════════════════════════════════════════════════════════
    private static final List<List<String>> FILTER_SETS = List.of(

            List.of("MS Skor 3:2", "A/U 2.5 Alt", "MS 2", "ÇŞ X2", "İY Skor 0:2"
            ),

            List.of("MS Skor 0:1", "MS 1", "HT/FT 1/1", "HT/FT 2/2", "MS Skor 2:1"
            ));

    // ─── kolon tanımları ────────────────────────────────────────────────────
    static class ColumnDef {
        final String sqlColumn, displayName;
        ColumnDef(String s, String d) { sqlColumn = s; displayName = d; }
    }

    private static final List<ColumnDef> ALL_ODDS_COLS = List.of(
            new ColumnDef("ft_1_a","MS 1"), new ColumnDef("ft_x_a","MS X"), new ColumnDef("ft_2_a","MS 2"),
            new ColumnDef("first_1_a","İY 1"), new ColumnDef("first_x_a","İY X"), new ColumnDef("first_2_a","İY 2"),
            new ColumnDef("second_1_a","2Y 1"), new ColumnDef("second_x_a","2Y X"), new ColumnDef("second_2_a","2Y 2"),
            new ColumnDef("bts_ft_yes_a","KG Evet"), new ColumnDef("bts_ft_no_a","KG Hayır"),
            new ColumnDef("bts_first_yes_a","İY KG Evet"), new ColumnDef("bts_first_no_a","İY KG Hayır"),
            new ColumnDef("bts_second_yes_a","2Y KG Evet"), new ColumnDef("bts_second_no_a","2Y KG Hayır"),
            new ColumnDef("dbc_ft_1x_a","ÇŞ 1X"), new ColumnDef("dbc_ft_12_a","ÇŞ 12"), new ColumnDef("dbc_ft_x2_a","ÇŞ X2"),
            new ColumnDef("dbc_first_1x_a","İY ÇŞ 1X"), new ColumnDef("dbc_first_12_a","İY ÇŞ 12"), new ColumnDef("dbc_first_x2_a","İY ÇŞ X2"),
            new ColumnDef("ft_0_5_over_a","A/U 0.5 Üst"), new ColumnDef("ft_0_5_under_a","A/U 0.5 Alt"),
            new ColumnDef("ft_1_5_over_a","A/U 1.5 Üst"), new ColumnDef("ft_1_5_under_a","A/U 1.5 Alt"),
            new ColumnDef("ft_2_5_over_a","A/U 2.5 Üst"), new ColumnDef("ft_2_5_under_a","A/U 2.5 Alt"),
            new ColumnDef("ft_3_5_over_a","A/U 3.5 Üst"), new ColumnDef("ft_3_5_under_a","A/U 3.5 Alt"),
            new ColumnDef("ft_4_5_over_a","A/U 4.5 Üst"), new ColumnDef("ft_4_5_under_a","A/U 4.5 Alt"),
            new ColumnDef("ft_5_5_over_a","A/U 5.5 Üst"), new ColumnDef("ft_5_5_under_a","A/U 5.5 Alt"),
            new ColumnDef("first_0_5_over_a","İY A/U 0.5 Üst"), new ColumnDef("first_0_5_under_a","İY A/U 0.5 Alt"),
            new ColumnDef("first_1_5_over_a","İY A/U 1.5 Üst"), new ColumnDef("first_1_5_under_a","İY A/U 1.5 Alt"),
            new ColumnDef("first_2_5_over_a","İY A/U 2.5 Üst"), new ColumnDef("first_2_5_under_a","İY A/U 2.5 Alt"),
            new ColumnDef("second_0_5_over_a","2Y A/U 0.5 Üst"), new ColumnDef("second_0_5_under_a","2Y A/U 0.5 Alt"),
            new ColumnDef("second_1_5_over_a","2Y A/U 1.5 Üst"), new ColumnDef("second_1_5_under_a","2Y A/U 1.5 Alt"),
            new ColumnDef("second_2_5_over_a","2Y A/U 2.5 Üst"), new ColumnDef("second_2_5_under_a","2Y A/U 2.5 Alt"),
            new ColumnDef("ht_ft_11_a","HT/FT 1/1"), new ColumnDef("ht_ft_1x_a","HT/FT 1/X"), new ColumnDef("ht_ft_12_a","HT/FT 1/2"),
            new ColumnDef("ht_ft_x1_a","HT/FT X/1"), new ColumnDef("ht_ft_xx_a","HT/FT X/X"), new ColumnDef("ht_ft_x2_a","HT/FT X/2"),
            new ColumnDef("ht_ft_21_a","HT/FT 2/1"), new ColumnDef("ht_ft_2x_a","HT/FT 2/X"), new ColumnDef("ht_ft_22_a","HT/FT 2/2"),
            new ColumnDef("first_score_1_0_a","İY Skor 1:0"), new ColumnDef("first_score_2_0_a","İY Skor 2:0"),
            new ColumnDef("first_score_2_1_a","İY Skor 2:1"), new ColumnDef("first_score_3_0_a","İY Skor 3:0"),
            new ColumnDef("first_score_3_1_a","İY Skor 3:1"), new ColumnDef("first_score_3_2_a","İY Skor 3:2"),
            new ColumnDef("first_score_0_0_a","İY Skor 0:0"), new ColumnDef("first_score_1_1_a","İY Skor 1:1"),
            new ColumnDef("first_score_2_2_a","İY Skor 2:2"), new ColumnDef("first_score_0_1_a","İY Skor 0:1"),
            new ColumnDef("first_score_0_2_a","İY Skor 0:2"), new ColumnDef("first_score_1_2_a","İY Skor 1:2"),
            new ColumnDef("first_score_0_3_a","İY Skor 0:3"), new ColumnDef("first_score_1_3_a","İY Skor 1:3"),
            new ColumnDef("first_score_2_3_a","İY Skor 2:3"),
            new ColumnDef("ft_score_1_0_a","MS Skor 1:0"), new ColumnDef("ft_score_2_0_a","MS Skor 2:0"),
            new ColumnDef("ft_score_2_1_a","MS Skor 2:1"), new ColumnDef("ft_score_3_0_a","MS Skor 3:0"),
            new ColumnDef("ft_score_3_1_a","MS Skor 3:1"), new ColumnDef("ft_score_3_2_a","MS Skor 3:2"),
            new ColumnDef("ft_score_4_0_a","MS Skor 4:0"), new ColumnDef("ft_score_4_1_a","MS Skor 4:1"),
            new ColumnDef("ft_score_4_2_a","MS Skor 4:2"), new ColumnDef("ft_score_4_3_a","MS Skor 4:3"),
            new ColumnDef("ft_score_5_0_a","MS Skor 5:0"), new ColumnDef("ft_score_5_1_a","MS Skor 5:1"),
            new ColumnDef("ft_score_5_2_a","MS Skor 5:2"), new ColumnDef("ft_score_0_0_a","MS Skor 0:0"),
            new ColumnDef("ft_score_1_1_a","MS Skor 1:1"), new ColumnDef("ft_score_2_2_a","MS Skor 2:2"),
            new ColumnDef("ft_score_3_3_a","MS Skor 3:3"), new ColumnDef("ft_score_4_4_a","MS Skor 4:4"),
            new ColumnDef("ft_score_0_1_a","MS Skor 0:1"), new ColumnDef("ft_score_0_2_a","MS Skor 0:2"),
            new ColumnDef("ft_score_1_2_a","MS Skor 1:2"), new ColumnDef("ft_score_0_3_a","MS Skor 0:3"),
            new ColumnDef("ft_score_1_3_a","MS Skor 1:3"), new ColumnDef("ft_score_2_3_a","MS Skor 2:3"),
            new ColumnDef("ft_score_0_4_a","MS Skor 0:4"), new ColumnDef("ft_score_1_4_a","MS Skor 1:4"),
            new ColumnDef("ft_score_2_4_a","MS Skor 2:4"), new ColumnDef("ft_score_3_4_a","MS Skor 3:4"),
            new ColumnDef("ft_score_0_5_a","MS Skor 0:5"), new ColumnDef("ft_score_1_5_a","MS Skor 1:5"),
            new ColumnDef("ft_score_2_5_a","MS Skor 2:5")
    );

    // displayName → index map (başlangıçta bir kez build edilir)
    private static final Map<String, Integer> NAME_TO_IDX;
    static {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < ALL_ODDS_COLS.size(); i++)
            m.put(ALL_ODDS_COLS.get(i).displayName, i);
        NAME_TO_IDX = Collections.unmodifiableMap(m);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MatchRecord
    // ═══════════════════════════════════════════════════════════════════════
    static class MatchRecord {
        String league, date, homeTeam, awayTeam, id;
        int htHome, htAway, ftHome, ftAway;
        double[] odds;

        MatchRecord(int size) { odds = new double[size]; }

        String ftScore()  { return (ftHome >= 0) ? ftHome + "-" + ftAway : "?-?"; }
        String htScore()  { return (htHome >= 0) ? htHome + "-" + htAway : "?-?"; }

        String htFtLabel() {
            if (htHome < 0 || htAway < 0 || ftHome < 0 || ftAway < 0) return "?/?";
            String ht = (htHome > htAway) ? "1" : (htHome < htAway ? "2" : "X");
            String ft = (ftHome > ftAway) ? "1" : (ftHome < ftAway ? "2" : "X");
            return ht + "/" + ft;
        }

        String ftSide() {
            if (ftHome < 0 || ftAway < 0) return "?";
            return (ftHome > ftAway) ? "1" : (ftHome < ftAway ? "2" : "X");
        }

        String fullDetail() {
            return "[" + id + "] " + homeTeam + " vs " + awayTeam
                    + " | İY:" + htScore()
                    + " MS:" + ftScore()
                    + " [HT/FT:" + htFtLabel() + "]"
                    + " [MS:" + ftSide() + "]";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DB
    // ═══════════════════════════════════════════════════════════════════════
    private Connection        conn;
    private List<MatchRecord> allRecords = new ArrayList<>();

    public Bet365FilterAnalyzer() {
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/postgres", "postgres", "fuad123");
            System.out.println("✅ Veritabanına bağlanıldı.");
            loadAllRecords();
        } catch (Exception e) {
            System.err.println("❌ " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void loadAllRecords() throws SQLException {
        System.out.println("📊 Veriler yükleniyor...");
        StringBuilder sb = new StringBuilder(
                "SELECT country_league,date_time,home_team,away_team,ht_iy,ft_ms,id");
        for (ColumnDef cd : ALL_ODDS_COLS) sb.append(",").append(cd.sqlColumn);
        sb.append(" FROM bet365_matches ORDER BY date_time DESC");

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sb.toString())) {
            while (rs.next()) {
                MatchRecord rec = new MatchRecord(ALL_ODDS_COLS.size());
                rec.league   = rs.getString("country_league");
                rec.date     = rs.getString("date_time");
                rec.homeTeam = rs.getString("home_team");
                rec.awayTeam = rs.getString("away_team");
                rec.id       = rs.getString("id");
                rec.htHome = rec.htAway = rec.ftHome = rec.ftAway = -1;

                String ht = rs.getString("ht_iy");
                if (ht != null && ht.contains("-")) {
                    String[] p = ht.split("-", 2);
                    try { rec.htHome = Integer.parseInt(p[0].trim());
                        rec.htAway = Integer.parseInt(p[1].trim()); }
                    catch (Exception ignored) {}
                }
                String ft = rs.getString("ft_ms");
                if (ft != null && ft.contains("-")) {
                    String[] p = ft.split("-", 2);
                    try { rec.ftHome = Integer.parseInt(p[0].trim());
                        rec.ftAway = Integer.parseInt(p[1].trim()); }
                    catch (Exception ignored) {}
                }
                for (int i = 0; i < ALL_ODDS_COLS.size(); i++) {
                    String v = rs.getString(ALL_ODDS_COLS.get(i).sqlColumn);
                    rec.odds[i] = parseOdds(v);
                }
                allRecords.add(rec);
            }
        }
        System.out.println("✅ " + allRecords.size() + " kayıt yüklendi.\n");
    }

    private double parseOdds(String s) {
        if (s == null || s.isEmpty() || s.equals("-")) return 0.0;
        try { return Double.parseDouble(s.replace(',', '.')); }
        catch (Exception ignored) { return 0.0; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Yardımcılar
    // ═══════════════════════════════════════════════════════════════════════
    private MatchRecord findById(String id) {
        for (MatchRecord r : allRecords)
            if (id.equals(r.id)) return r;
        return null;
    }

    /** displayName listesini → int[] col indekslerine çevirir */
    private int[] resolveColumns(List<String> names) {
        List<Integer> idxList = new ArrayList<>();
        for (String name : names) {
            Integer idx = NAME_TO_IDX.get(name.trim());
            if (idx == null) {
                System.out.println("  ⚠️  Bilinmeyen kolon adı: \"" + name + "\" — atlandı");
            } else {
                idxList.add(idx);
            }
        }
        return idxList.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Havuz: bu filtredeki kolonlarda target ile birebir eşleşen 1-3 maç */
    private List<MatchRecord> getPool(int[] cols, MatchRecord target) {
        List<MatchRecord> pool = new ArrayList<>();
        double[] tOdds = target.odds;
        for (MatchRecord r : allRecords) {
            if (r == target) continue;
            boolean ok = true;
            for (int col : cols) {
                if (r.odds[col] != tOdds[col]) { ok = false; break; }
            }
            if (ok) {
                pool.add(r);
                if (pool.size() > 3) return Collections.emptyList(); // çok geniş
            }
        }
        return pool;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ANA DÖNGÜ
    // ═══════════════════════════════════════════════════════════════════════
    public void run() {
        // Filtre setlerini başta int[] olarak derle (bir kez)
        List<int[]> compiledFilters = new ArrayList<>();
        System.out.println("📋 Yüklenen filtre setleri:");
        for (int fi = 0; fi < FILTER_SETS.size(); fi++) {
            List<String> names = FILTER_SETS.get(fi);
            int[] cols = resolveColumns(names);
            compiledFilters.add(cols);
            System.out.printf("  Filtre #%d: %s%n", fi + 1,
                    names.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(", ")));
        }
        System.out.println();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("🔎 Maç ID girin (çıkmak için 'q'): ");
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("q")) break;
            if (input.isEmpty()) continue;

            MatchRecord target = findById(input);
            if (target == null) {
                System.out.println("❌ ID bulunamadı: " + input + "\n");
                continue;
            }

            System.out.println("\n" + "=".repeat(70));
            System.out.println("📌 Maç: " + target.fullDetail());
            System.out.printf("   Beklenti → HT/FT: %s | CS: %s | MS: %s%n",
                    target.htFtLabel(), target.ftScore(), target.ftSide());
            System.out.println("=".repeat(70));

            // Her filtre setini bu maça uygula
            for (int fi = 0; fi < compiledFilters.size(); fi++) {
                int[] cols        = compiledFilters.get(fi);
                List<String> names = FILTER_SETS.get(fi);

                System.out.printf("%n--- FİLTRE #%d ---%n", fi + 1);
                System.out.println("    Kolonlar: " +
                        names.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(", ")));

                if (cols.length == 0) {
                    System.out.println("    ⚠️  Geçerli kolon yok, atlandı.");
                    continue;
                }

                List<MatchRecord> pool = getPool(cols, target);

                if (pool.isEmpty()) {
                    System.out.println("    ❌ Havuz boş (0 veya 4+ eşleşme — filtre geçersiz)");
                    continue;
                }

                // Case kontrolü
                String tHtFt = target.htFtLabel();
                String tCs   = target.ftScore();
                String tMs   = target.ftSide();

                boolean htftOk = true, csOk = true, msOk = true;
                for (MatchRecord m : pool) {
                    if (!m.htFtLabel().equals(tHtFt)) htftOk = false;
                    if (!m.ftScore().equals(tCs))     csOk   = false;
                    if (!m.ftSide().equals(tMs))      msOk   = false;
                }

                // Başarı etiketi
                StringJoiner verdict = new StringJoiner(" | ");
                if (htftOk) verdict.add("HT/FT ✅");
                if (csOk)   verdict.add("Correct Score ✅");
                if (msOk)   verdict.add("MS Taraf ✅");
                String verdictStr = verdict.toString().isEmpty() ? "❌ Hiçbir case geçmedi" : verdict.toString();

                System.out.printf("    Havuz: %d maç  →  %s%n", pool.size(), verdictStr);

                // Havuz tablosu
                System.out.println("    " + "─".repeat(75));
                System.out.printf("    %-22s  %-22s  %-5s  %-5s  %-7s  %-7s  %-5s%n",
                        "Ev Sahibi", "Deplasman", "İY", "MS", "HT/FT", "CS", "MS");
                System.out.println("    " + "─".repeat(75));
                for (MatchRecord m : pool) {
                    System.out.printf("    %-22s  %-22s  %-5s  %-5s  %s %-4s  %s %-4s  %s %-3s%n",
                            trunc(m.homeTeam, 22),
                            trunc(m.awayTeam, 22),
                            m.htScore(), m.ftScore(),
                            m.htFtLabel().equals(tHtFt) ? "✅" : "❌", m.htFtLabel(),
                            m.ftScore().equals(tCs)     ? "✅" : "❌", m.ftScore(),
                            m.ftSide().equals(tMs)      ? "✅" : "❌", m.ftSide());
                }
                System.out.println("    " + "─".repeat(75));
                System.out.printf("    Hedef → HT/FT: %s | CS: %s | MS: %s%n",
                        tHtFt, tCs, tMs);
            }

            System.out.println("\n" + "=".repeat(70) + "\n");
        }

        System.out.println("👋 Çıkılıyor...");
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    public static void main(String[] args) {
        new Bet365FilterAnalyzer().run();
    }
}