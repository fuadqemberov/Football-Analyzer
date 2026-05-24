package analyzer.bet365;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class Bet365FilterHunt {

    private static final int RUNS = 1000;

    private Connection conn;
    private List<MatchRecord> allRecords = new ArrayList<>();

    // Sütun indeksi: col -> oddsCode -> sorted matchIndices
    private Map<Integer, Map<Integer, int[]>> colIndex;

    static class MatchRecord {
        String league, date, homeTeam, awayTeam, id;
        int htHome, htAway, ftHome, ftAway;
        int[] oddsCode;
        short[] oddsValue;
        BitSet oddsValid;

        MatchRecord(int size) {
            oddsCode = new int[size];
            oddsValue = new short[size];
            oddsValid = new BitSet(size);
        }

        String ftScore() { return (ftHome >= 0) ? ftHome + "-" + ftAway : "?-?"; }
        String htScore() { return (htHome >= 0) ? htHome + "-" + htAway : "?-?"; }

        @Override
        public String toString() {
            return String.format("%-20s %-12s %-20s %-20s %-8s %-8s",
                    league != null ? league : "",
                    date != null ? date : "",
                    homeTeam != null ? homeTeam : "",
                    awayTeam != null ? awayTeam : "",
                    htScore(), ftScore());
        }

        boolean hasValidOdds(int col) {
            return col >= 0 && col < oddsValid.size() && oddsValid.get(col);
        }
    }

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

    private static String cleanOddsString(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("-")) return null;
        String s = raw.trim()
                .replace("yan.", "")
                .replace(",", ".")
                .replace(" ", "")
                .replace("'", "");
        if (s.isEmpty() || s.equals("-")) return null;
        return s;
    }

    private void loadDataAndBuildIndex() throws SQLException {
        System.out.println("📊 Veritabanından veriler yükleniyor ve indeks kuruluyor...");
        int cols = ALL_ODDS_COLS.size();

        List<Map<String, Integer>> stringToCode = new ArrayList<>(cols);
        List<Map<Integer, List<Integer>>> codeToIndices = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            stringToCode.add(new HashMap<>());
            codeToIndices.add(new HashMap<>());
        }

        StringBuilder sql = new StringBuilder(
                "SELECT country_league, date_time, home_team, away_team, ht_iy, ft_ms, id");
        for (ColumnDef cd : ALL_ODDS_COLS) sql.append(",").append(cd.sqlColumn);
        sql.append(" FROM bet365_matches ORDER BY date_time DESC");

        try (Statement st = conn.createStatement()) {
            st.setFetchSize(1000);
            conn.setAutoCommit(false);
            try (ResultSet rs = st.executeQuery(sql.toString())) {
                int idx = 0;
                while (rs.next()) {
                    MatchRecord rec = new MatchRecord(cols);
                    rec.league   = rs.getString("country_league");
                    rec.date     = rs.getString("date_time");
                    rec.homeTeam = rs.getString("home_team");
                    rec.awayTeam = rs.getString("away_team");
                    rec.id       = rs.getString("id");
                    rec.htHome = rec.htAway = rec.ftHome = rec.ftAway = -1;
                    String ht = rs.getString("ht_iy");
                    if (ht != null && ht.contains("-")) {
                        String[] p = ht.split("-", 2);
                        try { rec.htHome = Integer.parseInt(p[0].trim()); rec.htAway = Integer.parseInt(p[1].trim()); }
                        catch (Exception ignored) {}
                    }
                    String ft = rs.getString("ft_ms");
                    if (ft != null && ft.contains("-")) {
                        String[] p = ft.split("-", 2);
                        try { rec.ftHome = Integer.parseInt(p[0].trim()); rec.ftAway = Integer.parseInt(p[1].trim()); }
                        catch (Exception ignored) {}
                    }

                    for (int c = 0; c < cols; c++) {
                        String raw = rs.getString(ALL_ODDS_COLS.get(c).sqlColumn);
                        String clean = cleanOddsString(raw);
                        if (clean != null) {
                            try {
                                double d = Double.parseDouble(clean);
                                if (d > 0.0) {
                                    rec.oddsValid.set(c);
                                    rec.oddsValue[c] = (short) Math.round(d * 100);
                                    Map<String, Integer> stc = stringToCode.get(c);
                                    Integer code = stc.get(clean);
                                    if (code == null) {
                                        code = stc.size();
                                        stc.put(clean, code);
                                        codeToIndices.get(c).put(code, new ArrayList<>());
                                    }
                                    codeToIndices.get(c).get(code).add(idx);
                                    rec.oddsCode[c] = code;
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }

                    allRecords.add(rec);
                    idx++;
                    if (idx % 100000 == 0) System.out.printf("  %d kayıt yüklendi...%n", idx);
                }
            }
        }
        System.out.println("✅ Toplam " + allRecords.size() + " kayıt.");

        colIndex = new HashMap<>();
        for (int c = 0; c < cols; c++) {
            Map<Integer, int[]> map = new HashMap<>();
            for (Map.Entry<Integer, List<Integer>> e : codeToIndices.get(c).entrySet()) {
                int[] arr = e.getValue().stream().mapToInt(Integer::intValue).toArray();
                Arrays.sort(arr);
                map.put(e.getKey(), arr);
            }
            colIndex.put(c, map);
        }
        System.out.println("✅ İndeks hazır.");
    }

    private int[] intersect(int[] a, int[] b) {
        int[] res = new int[Math.min(a.length, b.length)];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { res[k++] = a[i++]; j++; }
            else if (a[i] < b[j]) i++;
            else j++;
        }
        return Arrays.copyOf(res, k);
    }

    private int[] removeValue(int[] arr, int val) {
        int count = 0;
        for (int v : arr) if (v != val) count++;
        int[] res = new int[count];
        int idx = 0;
        for (int v : arr) if (v != val) res[idx++] = v;
        return res;
    }

    public void hunt() throws Exception {
        List<MatchRecord> valid = allRecords.stream()
                .filter(m -> m.ftHome >= 0 && m.ftAway >= 0 && m.htHome >= 0 && m.htAway >= 0)
                .collect(Collectors.toList());
        if (valid.size() < 2) {
            System.out.println("❌ Yeterli maç yok.");
            return;
        }

        Scanner scanner = new Scanner(System.in);
        MatchRecord target = null;
        while (target == null) {
            System.out.print("Hedef maçın veritabanı ID'sini girin: ");
            String targetId = scanner.nextLine().trim();
            final String searchId = targetId;
            target = valid.stream()
                    .filter(m -> m.id != null && m.id.equals(searchId))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                System.out.println("⚠️  Bu ID'ye sahip geçerli bir maç bulunamadı. Tekrar deneyin.");
            }
        }
        final String targetId = target.id;
        int targetGlobalIdx = allRecords.indexOf(target);

        System.out.println("\n🆕 Hedef Oyun: " + target.homeTeam + " vs " + target.awayTeam);
        System.out.println("   FT Score: " + target.ftScore() + " | HT Score: " + target.htScore());
        System.out.println("📊 Toplam geçerli maç sayısı: " + (valid.size() - 1) + "\n");

        List<Integer> oddsCols = new ArrayList<>();
        for (int i = 0; i < ALL_ODDS_COLS.size(); i++) oddsCols.add(i);

        List<String> allFtScores = new ArrayList<>();
        Random rng = new Random();

        for (int run = 1; run <= RUNS; run++) {
            Collections.shuffle(oddsCols, rng);
            List<Integer> activeCols = new ArrayList<>();
            int[] currentPool = null;

            for (int col : oddsCols) {
                if (!target.hasValidOdds(col)) continue;
                int code = target.oddsCode[col];
                Map<Integer, int[]> map = colIndex.get(col);
                if (map == null) continue;
                int[] cand = map.get(code);
                if (cand == null || cand.length == 0) continue;

                if (currentPool == null) {
                    int[] pool = removeValue(cand, targetGlobalIdx);
                    if (pool.length < 2) continue;
                    currentPool = pool;
                    activeCols.add(col);
                } else {
                    int[] newPool = intersect(currentPool, cand);
                    // Hedefin tekrar girmemesi için ID filtresi (intersect sonucunda da olmamalı ama garanti)
                    newPool = Arrays.stream(newPool)
                            .filter(i -> !allRecords.get(i).id.equals(targetId))
                            .toArray();
                    if (newPool.length < 2) continue;
                    currentPool = newPool;
                    activeCols.add(col);
                }

                if (currentPool.length >= 2 && currentPool.length <= 3) {
                    System.out.println("=".repeat(80));
                    System.out.println("🔁 RUN " + String.format("%02d", run));
                    System.out.println("✅ " + currentPool.length + " maç bulundu\n");
                    System.out.println(" 🔍 Kullanılan filtreler (" + activeCols.size() + " adet):");
                    for (int ac : activeCols) {
                        double val = target.oddsValue[ac] / 100.0;
                        System.out.printf("   • %s  =  %.2f%n", ALL_ODDS_COLS.get(ac).displayName, val);
                    }
                    System.out.println("\n  " + "-".repeat(80));
                    for (int idx : currentPool) {
                        MatchRecord m = allRecords.get(idx);
                        System.out.println("  " + m.toString());
                    }
                    for (int idx : currentPool) {
                        allFtScores.add(allRecords.get(idx).ftScore());
                    }
                    Map<String, Long> freq = Arrays.stream(currentPool)
                            .mapToObj(i -> allRecords.get(i).ftScore())
                            .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
                    Map.Entry<String, Long> most = Collections.max(freq.entrySet(), Map.Entry.comparingByValue());
                    double conf = (double) most.getValue() / currentPool.length * 100;
                    System.out.printf("\n 🔮 Bu run tahmini: %s | Güven: %.1f%%%n", most.getKey(), conf);
                    break;
                }
            }

            if (currentPool == null || currentPool.length < 2 || currentPool.length > 3) {
                System.out.println("=".repeat(80));
                System.out.println("🔁 RUN " + String.format("%02d", run));
                System.out.println("⚠️  Uygun aralıkta maç bulunamadı (kalan: " +
                        (currentPool == null ? "?" : currentPool.length) + ")");
            }
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println("📊 YEKUN NƏTİCƏ — " + RUNS + " RUN\n");
        if (!allFtScores.isEmpty()) {
            Map<String, Long> globalFreq = allFtScores.stream()
                    .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
            long total = allFtScores.size();
            System.out.println("🏆 SKOR DAĞILIMI:");
            globalFreq.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> {
                        long cnt = e.getValue();
                        double pct = (double) cnt / total * 100;
                        String bar = "█".repeat((int) Math.min(cnt, 40));
                        System.out.printf("  %-12s %3dx   %5.1f%%   %s%n", e.getKey(), cnt, pct, bar);
                    });
            Map.Entry<String, Long> champion = Collections.max(globalFreq.entrySet(), Map.Entry.comparingByValue());
            double finalConf = (double) champion.getValue() / total * 100;
            System.out.printf("\n🔮 FİNAL PROQNOZ: %s | Etibarlılık: %.1f%%%n", champion.getKey(), finalConf);
        } else {
            System.out.println("Hiçbir run’da uygun havuz bulunamadı.");
        }
    }

    public Bet365FilterHunt() {
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/postgres", "postgres", "fuad123");
            System.out.println("✅ Veritabanına bağlanıldı.");
            loadDataAndBuildIndex();
        } catch (Exception e) {
            System.err.println("❌ " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void main(String[] args) throws Exception {
        new Bet365FilterHunt().hunt();
    }
}