package analyzer.bet365;

import java.sql.*;
        import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

public class SimilarityAnalyzer {

    // --- Kendi MatchRecord ve yardımcı sınıfları ---
    public static class MatchRecord {
        public String league, date, homeTeam, awayTeam, id;
        public int htHome, htAway, ftHome, ftAway;
        public double[] odds;

        public MatchRecord(int oddsSize) { odds = new double[oddsSize]; }
        public String ftScore() { return (ftHome>=0) ? ftHome+"-"+ftAway : "?-?"; }
        public String htScore() { return (htHome>=0) ? htHome+"-"+htAway : "?-?"; }
        public String htFt() {
            if (htHome<0 || htAway<0 || ftHome<0 || ftAway<0) return "?/?";
            String ht = (htHome>htAway)?"1":(htHome==htAway?"X":"2");
            String ft = (ftHome>ftAway)?"1":(ftHome==ftAway?"X":"2");
            return ht+"/"+ft;
        }
    }

    private static final List<String> ALL_ODDS_COLS = List.of(
            "ft_1_a","ft_x_a","ft_2_a",
            "first_1_a","first_x_a","first_2_a",
            "second_1_a","second_x_a","second_2_a",
            "bts_ft_yes_a","bts_ft_no_a",
            "bts_first_yes_a","bts_first_no_a",
            "bts_second_yes_a","bts_second_no_a",
            "dbc_ft_1x_a","dbc_ft_12_a","dbc_ft_x2_a",
            "dbc_first_1x_a","dbc_first_12_a","dbc_first_x2_a",
            "ft_0_5_over_a","ft_0_5_under_a",
            "ft_1_5_over_a","ft_1_5_under_a",
            "ft_2_5_over_a","ft_2_5_under_a",
            "ft_3_5_over_a","ft_3_5_under_a",
            "ft_4_5_over_a","ft_4_5_under_a",
            "ft_5_5_over_a","ft_5_5_under_a",
            "first_0_5_over_a","first_0_5_under_a",
            "first_1_5_over_a","first_1_5_under_a",
            "first_2_5_over_a","first_2_5_under_a",
            "second_0_5_over_a","second_0_5_under_a",
            "second_1_5_over_a","second_1_5_under_a",
            "second_2_5_over_a","second_2_5_under_a",
            "ht_ft_11_a","ht_ft_1x_a","ht_ft_12_a",
            "ht_ft_x1_a","ht_ft_xx_a","ht_ft_x2_a",
            "ht_ft_21_a","ht_ft_2x_a","ht_ft_22_a",
            "first_score_1_0_a","first_score_2_0_a","first_score_2_1_a",
            "first_score_3_0_a","first_score_3_1_a","first_score_3_2_a",
            "first_score_0_0_a","first_score_1_1_a","first_score_2_2_a",
            "first_score_0_1_a","first_score_0_2_a","first_score_1_2_a",
            "first_score_0_3_a","first_score_1_3_a","first_score_2_3_a",
            "ft_score_1_0_a","ft_score_2_0_a","ft_score_2_1_a",
            "ft_score_3_0_a","ft_score_3_1_a","ft_score_3_2_a",
            "ft_score_4_0_a","ft_score_4_1_a","ft_score_4_2_a","ft_score_4_3_a",
            "ft_score_5_0_a","ft_score_5_1_a","ft_score_5_2_a",
            "ft_score_0_0_a","ft_score_1_1_a","ft_score_2_2_a",
            "ft_score_3_3_a","ft_score_4_4_a",
            "ft_score_0_1_a","ft_score_0_2_a","ft_score_1_2_a",
            "ft_score_0_3_a","ft_score_1_3_a","ft_score_2_3_a",
            "ft_score_0_4_a","ft_score_1_4_a","ft_score_2_4_a","ft_score_3_4_a",
            "ft_score_0_5_a","ft_score_1_5_a","ft_score_2_5_a"
    );

    private Connection conn;
    private List<MatchRecord> allRecords = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════════════
    // Kurucu
    // ═══════════════════════════════════════════════════════════════════════
    public SimilarityAnalyzer() {
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
        for (String col : ALL_ODDS_COLS) sb.append(",").append(col);
        sb.append(" FROM bet365_matches ORDER BY date_time DESC");

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sb.toString())) {
            while (rs.next()) {
                MatchRecord rec = new MatchRecord(ALL_ODDS_COLS.size());
                rec.league = rs.getString("country_league");
                rec.date   = rs.getString("date_time");
                rec.homeTeam = rs.getString("home_team");
                rec.awayTeam = rs.getString("away_team");
                rec.id       = rs.getString("id");
                rec.htHome = rec.htAway = rec.ftHome = rec.ftAway = -1;

                String ht = rs.getString("ht_iy");
                if (ht != null && ht.contains("-")) {
                    String[] p = ht.split("-");
                    if (p.length == 2) {
                        try { rec.htHome = Integer.parseInt(p[0].trim()); } catch (Exception _) {}
                        try { rec.htAway = Integer.parseInt(p[1].trim()); } catch (Exception _) {}
                    }
                }
                String ft = rs.getString("ft_ms");
                if (ft != null && ft.contains("-")) {
                    String[] p = ft.split("-");
                    if (p.length == 2) {
                        try { rec.ftHome = Integer.parseInt(p[0].trim()); } catch (Exception _) {}
                        try { rec.ftAway = Integer.parseInt(p[1].trim()); } catch (Exception _) {}
                    }
                }

                for (int i = 0; i < ALL_ODDS_COLS.size(); i++) {
                    String v = rs.getString(ALL_ODDS_COLS.get(i));
                    rec.odds[i] = parseOdds(v);
                }
                allRecords.add(rec);
            }
        }
        System.out.println("✅ " + allRecords.size() + " kayıt yüklendi.\n");
    }

    private double parseOdds(String s) {
        if (s == null || s.isEmpty() || s.equals("-")) return 0.0;
        try { return Double.parseDouble(s.replace(',', '.')); } catch (Exception _) { return 0.0; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Benzerlik analizi
    // ═══════════════════════════════════════════════════════════════════════
    private static final double[] TOLERANCES = {0.02, 0.03};
    private static final double[] SIMILARITIES = {0.96, 0.94, 0.92, 0.90};

    public void analyze(String targetId) {
        Optional<MatchRecord> opt = allRecords.stream()
                .filter(m -> m.id != null && m.id.equals(targetId))
                .findFirst();
        if (opt.isEmpty()) {
            System.out.println("❌ Hedef ID bulunamadı: " + targetId);
            return;
        }
        MatchRecord target = opt.get();
        System.out.println("\n🆕 Hedef maç: " + target.homeTeam + " vs " + target.awayTeam);
        System.out.println("   İY: " + target.htScore() + " | MS: " + target.ftScore() + "\n");

        List<CombinationResult> allCombinations = new ArrayList<>();
        for (double tol : TOLERANCES) {
            for (double sim : SIMILARITIES) {
                System.out.printf("→ Tolerance: %.1f%% | Similarity: %.1f%%", tol*100, sim*100);
                List<SimilarMatch> matches = findSimilar(target, tol, sim);
                System.out.printf(" → %d maç bulundu%n", matches.size());
                if (!matches.isEmpty()) {
                    allCombinations.add(new CombinationResult(tol, sim, matches));
                }
            }
        }
        printResults(allCombinations);
    }

    private List<SimilarMatch> findSimilar(MatchRecord target, double tol, double simThreshold) {
        List<SimilarMatch> result = new ArrayList<>();
        double[] tOdds = target.odds;
        int oddsLen = tOdds.length;

        for (MatchRecord row : allRecords) {
            if (row == target || row.id.equals(target.id)) continue;
            int valid = 0, match = 0;
            for (int i = 0; i < oddsLen; i++) {
                double tv = tOdds[i], rv = row.odds[i];
                if (tv == 0.0 || Double.isNaN(tv) || Double.isNaN(rv)) continue;
                double denom = (tv != 0.0) ? tv : 1.0;
                double relDiff = Math.abs(rv - tv) / denom;
                valid++;
                if (!Double.isNaN(relDiff) && relDiff <= tol) match++;
            }
            if (valid > 0) {
                double ratio = (double) match / valid;
                if (ratio >= simThreshold) {
                    SimilarMatch sm = new SimilarMatch();
                    sm.league = row.league;
                    sm.date = row.date;
                    sm.homeTeam = row.homeTeam;
                    sm.awayTeam = row.awayTeam;
                    sm.htScore = row.htScore();
                    sm.ftScore = row.ftScore();
                    sm.similarityPercent = ratio * 100.0;
                    result.add(sm);
                }
            }
        }
        return result;
    }

    private void printResults(List<CombinationResult> results) {
        // … (önceki cevaptaki printResults metodunun aynısı)
    }

    // --- yardımcı sınıflar ---
    static class SimilarMatch {
        String league, date, homeTeam, awayTeam;
        String htScore, ftScore;
        double similarityPercent;
    }
    static class CombinationResult {
        double tolerance, similarity;
        List<SimilarMatch> matches;
        CombinationResult(double t, double s, List<SimilarMatch> m) {
            tolerance=t; similarity=s; matches=m;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Hedef maç ID: ");
        String targetId = scanner.nextLine().trim();
        new SimilarityAnalyzer().analyze(targetId);
    }
}