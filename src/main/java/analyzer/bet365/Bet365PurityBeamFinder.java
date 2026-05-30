package analyzer.bet365;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

public class Bet365PurityBeamFinder {

    /* ===================== KONFİQURASİYA ===================== */
    static final String JDBC_URL = "jdbc:postgresql://localhost:5432/postgres";
    static final String DB_USER  = "postgres";
    static final String DB_PASS  = "fuad123";
    static final String TABLE    = "bet365_matches";

    static final int    TARGET_COUNT = 20;
    static final int    MIN_COVERAGE = 17;        // ən az 17 oyunda hovuz 2‑5
    static final int    POOL_MIN     = 2;
    static final int    POOL_MAX     = 5;
    static final int    MAX_COLS     = 8;
    static final int    BEAM_WIDTH   = 15;
    static final long   SEED         = 123L;

    static final boolean USE_SYNTHETIC = false;
    static final int     SYNTHETIC_ROWS = 60000;

    /* ===================== Sütunlar ===================== */
    record Col(String sql, String disp) {}
    static final List<Col> COLS = buildCols();
    static final int NC = COLS.size();

    static int idx(String sql) {
        for (int i = 0; i < NC; i++) if (COLS.get(i).sql().equals(sql)) return i;
        return -1;
    }

    static List<Col> buildCols() {
        List<Col> c = new ArrayList<>();
        c.add(new Col("ft_1_a","MS 1")); c.add(new Col("ft_x_a","MS X")); c.add(new Col("ft_2_a","MS 2"));
        c.add(new Col("first_1_a","İY 1")); c.add(new Col("first_x_a","İY X")); c.add(new Col("first_2_a","İY 2"));
        c.add(new Col("second_1_a","2Y 1")); c.add(new Col("second_x_a","2Y X")); c.add(new Col("second_2_a","2Y 2"));
        c.add(new Col("bts_ft_yes_a","KG Evet")); c.add(new Col("bts_ft_no_a","KG Hayır"));
        c.add(new Col("bts_first_yes_a","İY KG Evet")); c.add(new Col("bts_first_no_a","İY KG Hayır"));
        c.add(new Col("bts_second_yes_a","2Y KG Evet")); c.add(new Col("bts_second_no_a","2Y KG Hayır"));
        c.add(new Col("dbc_ft_1x_a","ÇŞ 1X")); c.add(new Col("dbc_ft_12_a","ÇŞ 12")); c.add(new Col("dbc_ft_x2_a","ÇŞ X2"));
        c.add(new Col("dbc_first_1x_a","İY ÇŞ 1X")); c.add(new Col("dbc_first_12_a","İY ÇŞ 12")); c.add(new Col("dbc_first_x2_a","İY ÇŞ X2"));
        c.add(new Col("ft_0_5_over_a","A/U 0.5 Üst")); c.add(new Col("ft_0_5_under_a","A/U 0.5 Alt"));
        c.add(new Col("ft_1_5_over_a","A/U 1.5 Üst")); c.add(new Col("ft_1_5_under_a","A/U 1.5 Alt"));
        c.add(new Col("ft_2_5_over_a","A/U 2.5 Üst")); c.add(new Col("ft_2_5_under_a","A/U 2.5 Alt"));
        c.add(new Col("ft_3_5_over_a","A/U 3.5 Üst")); c.add(new Col("ft_3_5_under_a","A/U 3.5 Alt"));
        c.add(new Col("ft_4_5_over_a","A/U 4.5 Üst")); c.add(new Col("ft_4_5_under_a","A/U 4.5 Alt"));
        c.add(new Col("ft_5_5_over_a","A/U 5.5 Üst")); c.add(new Col("ft_5_5_under_a","A/U 5.5 Alt"));
        c.add(new Col("first_0_5_over_a","İY A/U 0.5 Üst")); c.add(new Col("first_0_5_under_a","İY A/U 0.5 Alt"));
        c.add(new Col("first_1_5_over_a","İY A/U 1.5 Üst")); c.add(new Col("first_1_5_under_a","İY A/U 1.5 Alt"));
        c.add(new Col("first_2_5_over_a","İY A/U 2.5 Üst")); c.add(new Col("first_2_5_under_a","İY A/U 2.5 Alt"));
        c.add(new Col("second_0_5_over_a","2Y A/U 0.5 Üst")); c.add(new Col("second_0_5_under_a","2Y A/U 0.5 Alt"));
        c.add(new Col("second_1_5_over_a","2Y A/U 1.5 Üst")); c.add(new Col("second_1_5_under_a","2Y A/U 1.5 Alt"));
        c.add(new Col("second_2_5_over_a","2Y A/U 2.5 Üst")); c.add(new Col("second_2_5_under_a","2Y A/U 2.5 Alt"));
        c.add(new Col("ht_ft_11_a","HT/FT 1/1")); c.add(new Col("ht_ft_1x_a","HT/FT 1/X")); c.add(new Col("ht_ft_12_a","HT/FT 1/2"));
        c.add(new Col("ht_ft_x1_a","HT/FT X/1")); c.add(new Col("ht_ft_xx_a","HT/FT X/X")); c.add(new Col("ht_ft_x2_a","HT/FT X/2"));
        c.add(new Col("ht_ft_21_a","HT/FT 2/1")); c.add(new Col("ht_ft_2x_a","HT/FT 2/X")); c.add(new Col("ht_ft_22_a","HT/FT 2/2"));
        String[] fs={"1:0","2:0","2:1","3:0","3:1","3:2","4:0","4:1","4:2","4:3","5:0","5:1","5:2",
                "0:0","1:1","2:2","3:3","4:4","0:1","0:2","1:2","0:3","1:3","2:3","0:4","1:4","2:4","3:4","0:5","1:5","2:5"};
        for (String s: fs) c.add(new Col("ft_score_"+s.replace(":","_")+"_a","MS Skor "+s));
        String[] hs={"1:0","2:0","2:1","3:0","3:1","3:2","0:0","1:1","2:2","0:1","0:2","1:2","0:3","1:3","2:3"};
        for (String s: hs) c.add(new Col("first_score_"+s.replace(":","_")+"_a","İY Skor "+s));
        return List.copyOf(c);
    }

    /* ===================== Data ===================== */
    static int N;
    static double[][] odds;
    static long[][]   bits;
    static int[] ftH, ftA, htH, htA;
    static String[] home, away, league, date;
    static List<Map<Long,int[]>> index;

    /* ===================== MAIN ===================== */
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        long t0 = System.currentTimeMillis();
        head("🎯 HOVUZ 2‑5 ŞÜA AXTARISI (BEAM SEARCH)");

        if (USE_SYNTHETIC) { System.out.println("📂 🧪 SİNTETİK data"); loadSynthetic(); }
        else { System.out.println("📂 🐘 PostgreSQL → " + TABLE); loadDb(); }
        System.out.println("✅ " + N + " oyun, " + NC + " sütun yükləndi");

        buildIndex();

        Random rng = new Random(SEED);
        int[] targets = pickValidTargets(rng, TARGET_COUNT);
        sec("🎯 SEÇİLƏN " + targets.length + " HƏDƏF OYUN");
        for (int i = 0; i < targets.length; i++) System.out.printf("  %2d. %s%n", i+1, gameLong(targets[i]));

        // Şüa axtarışı
        List<Candidate> beam = new ArrayList<>();
        beam.add(new Candidate(new int[0], 0));
        Candidate best = beam.get(0);

        for (int depth = 1; depth <= MAX_COLS; depth++) {
            List<Candidate> expanded = new ArrayList<>();
            for (Candidate cand : beam) {
                for (int c = 0; c < NC; c++) {
                    if (contains(cand.cols, c)) continue;
                    boolean useful = false;
                    for (int t : targets) if (odds[c][t] > 0) { useful = true; break; }
                    if (!useful) continue;

                    int[] newCols = append(cand.cols, c);
                    int covered = countInRange(newCols, targets);
                    expanded.add(new Candidate(newCols, covered));
                }
            }
            if (expanded.isEmpty()) break;

            expanded.sort(Comparator.comparingInt((Candidate x) -> -x.covered)
                    .thenComparingInt(x -> x.cols.length));

            beam.clear();
            Set<String> seen = new HashSet<>();
            for (Candidate c : expanded) {
                String keyStr = key(c.cols);
                if (seen.add(keyStr)) {
                    beam.add(c);
                    if (beam.size() >= BEAM_WIDTH) break;
                }
            }

            Candidate top = beam.get(0);
            if (top.covered > best.covered) best = top;
            System.out.printf("  dərinlik %d: ən yaxşı = %d/%d (filter: %s)%n",
                    depth, top.covered, targets.length, names(top.cols));
            if (top.covered >= MIN_COVERAGE) {
                System.out.printf("✅ %d/%d hədəfə çatdıq, dayandı.%n", top.covered, targets.length);
                break;
            }
        }

        sec("🏁 NƏTİCƏ");
        if (best.covered >= MIN_COVERAGE) {
            System.out.printf("📈 Örtülən: %d/%d (hovuz 2‑5)%n", best.covered, targets.length);
            printFilterBox(best.cols);
            sec("🔬 DETALLI ANALİZ");
            for (int t : targets) {
                int s = poolSize(best.cols, t);
                System.out.printf("  %-50s hovuz=%d%n", gameShort(t), s);
            }
        } else {
            System.out.println("❌ Filter tapılmadı. Maks örtülən: " + best.covered + "/" + targets.length);
            System.out.println("   BEAM_WIDTH/MAX_COLS artırmağı yoxlayın.");
        }
        System.out.printf("%n⏱️  Vaxt: %.1f san%n", (System.currentTimeMillis()-t0)/1000.0);
    }

    static class Candidate {
        int[] cols;
        int covered;
        Candidate(int[] c, int cov) { cols = c; covered = cov; }
    }

    /* ===================== HOVUZ HESABLAMA ===================== */
    static int poolSize(int[] cols, int target) {
        if (cols.length == 0) return N - 1;
        List<int[]> lists = new ArrayList<>();
        for (int c : cols) {
            long val = bits[c][target];
            if (val == 0) return 0;
            int[] arr = index.get(c).get(val);
            if (arr == null || arr.length == 0) return 0;
            lists.add(arr);
        }
        int[] smallest = lists.get(0);
        for (int[] a : lists) if (a.length < smallest.length) smallest = a;
        int count = 0;
        for (int r : smallest) {
            if (r == target) continue;
            boolean all = true;
            for (int[] other : lists) {
                if (other == smallest) continue;
                if (Arrays.binarySearch(other, r) < 0) { all = false; break; }
            }
            if (all) count++;
        }
        return count;
    }

    static int countInRange(int[] filter, int[] targets) {
        int cnt = 0;
        for (int t : targets) {
            int s = poolSize(filter, t);
            if (s >= POOL_MIN && s <= POOL_MAX) cnt++;
        }
        return cnt;
    }

    /* ===================== KÖMƏKÇİLƏR ===================== */
    static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }
    static int[] append(int[] a, int v) { int[] r = Arrays.copyOf(a, a.length+1); r[a.length]=v; return r; }
    static String key(int[] cols) { int[] s = cols.clone(); Arrays.sort(s); return Arrays.toString(s); }
    static String names(int[] cols) { StringJoiner sj = new StringJoiner(", "); for (int c : cols) sj.add(COLS.get(c).disp()); return sj.toString(); }

    static void printFilterBox(int[] cols) {
        System.out.println("┌─ 🧩 FİLTER (" + cols.length + " sütun) ──────────────");
        for (int c : cols) System.out.println("│   • " + COLS.get(c).disp() + "   [" + COLS.get(c).sql() + "]");
        System.out.println("└──────────────────────────────────");
    }

    static int[] pickValidTargets(Random rng, int count) {
        List<Integer> valid = new ArrayList<>();
        for (int r = 0; r < N; r++) if (ftH[r] >= 0 && htH[r] >= 0) valid.add(r);
        Collections.shuffle(valid, rng);
        int[] out = new int[Math.min(count, valid.size())];
        for (int i = 0; i < out.length; i++) out[i] = valid.get(i);
        return out;
    }

    static String gameLong(int r) {
        return home[r] + " v " + away[r] + "  MS:" + ftScore(r) + " İY:" + htScore(r)
                + " [" + htftStr(r) + "]" + (date[r]!=null ? "  "+date[r]:"") + "  #"+r;
    }
    static String gameShort(int r) { return home[r] + " v " + away[r] + "  MS:" + ftScore(r); }
    static String ftScore(int r) { return ftH[r]<0 ? "?-?" : ftH[r]+"-"+ftA[r]; }
    static String htScore(int r) { return htH[r]<0 ? "?-?" : htH[r]+"-"+htA[r]; }
    static String sideStr(int s) { return s==0?"1":s==1?"X":s==2?"2":"?"; }
    static String htftStr(int r) {
        int hs = sideOf(htH[r], htA[r]), fs = sideOf(ftH[r], ftA[r]);
        return hs<0||fs<0 ? "?/?" : sideStr(hs)+"/"+sideStr(fs);
    }
    static int sideOf(int h, int a) { return h<0 ? -1 : (h>a ? 0 : (h==a ? 1 : 2)); }

    static void head(String t) { System.out.println("════════════════════════════════════════════════════════\n  "+t+"\n════════════════════════════════════════════════════════"); }
    static void sec(String t) { System.out.println("\n════════════════════════════════════════════════════════\n  "+t+"\n════════════════════════════════════════════════════════"); }

    /* ===================== İNDEKS ===================== */
    static void buildIndex() {
        System.out.println("🔨 İndeks qurulur...");
        index = new ArrayList<>(NC);
        for (int c = 0; c < NC; c++) index.add(null);
        ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
        try {
            pool.submit(() -> IntStream.range(0, NC).parallel().forEach(c -> {
                Map<Long, List<Integer>> tmp = new HashMap<>();
                for (int r = 0; r < N; r++) {
                    double v = odds[c][r];
                    if (v <= 0) continue;
                    tmp.computeIfAbsent(bits[c][r], k -> new ArrayList<>()).add(r);
                }
                Map<Long, int[]> byVal = new HashMap<>();
                for (var e : tmp.entrySet()) {
                    int[] arr = e.getValue().stream().mapToInt(Integer::intValue).toArray();
                    byVal.put(e.getKey(), arr);
                }
                index.set(c, byVal);
            })).join();
        } finally { pool.shutdown(); }
        System.out.println("✅ İndeks hazır");
    }

    /* ===================== DB ===================== */
    static void loadDb() throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS)) {
            conn.setAutoCommit(false);
            StringBuilder sb = new StringBuilder("SELECT country_league,date_time,home_team,away_team,ht_iy,ft_ms,id");
            for (Col c : COLS) sb.append(",").append(c.sql());
            sb.append(" FROM ").append(TABLE);

            int rc;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM "+TABLE)) {
                rs.next(); rc = rs.getInt(1);
            }
            alloc(rc);
            int r = 0;
            try (Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                st.setFetchSize(10000);
                try (ResultSet rs = st.executeQuery(sb.toString())) {
                    while (rs.next() && r < rc) {
                        league[r] = rs.getString(1); date[r] = rs.getString(2);
                        home[r] = rs.getString(3); away[r] = rs.getString(4);
                        int[] ht = parse(rs.getString(5)), ft = parse(rs.getString(6));
                        htH[r] = ht==null?-1:ht[0]; htA[r] = ht==null?-1:ht[1];
                        ftH[r] = ft==null?-1:ft[0]; ftA[r] = ft==null?-1:ft[1];
                        for (int c = 0; c < NC; c++) {
                            double v = parseOdds(rs.getString(8+c));
                            odds[c][r] = v; bits[c][r] = Double.doubleToLongBits(v);
                        }
                        r++;
                        if (r % 100000 == 0) System.out.println("📥 " + r + " sətir...");
                    }
                }
            }
            N = r;
        }
    }
    static double parseOdds(String s) {
        if (s==null||s.isEmpty()||s.equals("-")) return 0;
        try { return Double.parseDouble(s.replace(',','.')); } catch (Exception e) { return 0; }
    }
    static int[] parse(String s) {
        if (s==null) return null; s=s.trim(); if (s.isEmpty()) return null;
        String[] p = s.split("\\s*[-:–xX/]\\s*"); if (p.length!=2) return null;
        try { return new int[]{Integer.parseInt(p[0].trim()),Integer.parseInt(p[1].trim())}; } catch (Exception e) { return null; }
    }
    static void alloc(int rows) {
        odds = new double[NC][rows]; bits = new long[NC][rows];
        ftH=new int[rows]; ftA=new int[rows]; htH=new int[rows]; htA=new int[rows];
        home=new String[rows]; away=new String[rows]; league=new String[rows]; date=new String[rows];
    }

    /* ===================== SİNTETİK ===================== */
    static final String[] TEAMS = {"Qarabağ","Neftçi","Sabah","Zirə","Real","Barca","Bayern","PSG","City","Inter","Milan","Ajax","Porto","Roma"};
    static void loadSynthetic() {
        Random r = new Random(SEED); N = SYNTHETIC_ROWS; alloc(N);
        int i1 = idx("ft_1_a"), iX = idx("ft_x_a"), i2 = idx("ft_2_a");
        for (int g = 0; g < N; g++) {
            int p = r.nextInt(14), domSide = p % 3;
            double o1 = round2(1.4 + (p%7)*0.35), oX = round2(3.0 + (p%5)*0.40), o2 = round2(1.6 + ((p+3)%7)*0.33);
            int side = r.nextDouble()<0.82 ? domSide : r.nextInt(3);
            int[] sc = scoreFor(side, r);
            ftH[g]=sc[0]; ftA[g]=sc[1];
            int hside = r.nextDouble()<0.6 ? side : r.nextInt(3);
            int[] hsc = scoreFor(hside, r);
            htH[g]=Math.min(hsc[0], ftH[g]); htA[g]=Math.min(hsc[1], ftA[g]);
            home[g]=TEAMS[r.nextInt(TEAMS.length)]; away[g]=TEAMS[r.nextInt(TEAMS.length)]; date[g]="2020-01-01";
            for (int c=0; c<NC; c++) {
                double v = c==i1 ? o1 : c==iX ? oX : c==i2 ? o2 : round2(1.2 + r.nextInt(40)*0.2);
                if (r.nextDouble()<0.02) v=0;
                odds[c][g]=v; bits[c][g]=Double.doubleToLongBits(v);
            }
        }
    }
    static int[] scoreFor(int side, Random r) {
        if (side==0) { int[][] o={{1,0},{2,0},{2,1},{3,1}}; return o[r.nextInt(o.length)]; }
        if (side==2) { int[][] o={{0,1},{0,2},{1,2},{1,3}}; return o[r.nextInt(o.length)]; }
        return new int[][]{{0,0},{1,1},{2,2}}[r.nextInt(3)];
    }
    static double round2(double x) { return Math.round(x*100)/100.0; }
}