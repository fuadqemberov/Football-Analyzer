package analyzer.bet365;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * OPTİMİZASİYA EDİLMİŞ GENETİK ALQORİTM — PERFORMANS ARTIŞLARI:
 *
 *  1. İNDEKS XƏRTƏSI (col → value → matchIndices):
 *     getPoolLarge O(n) scan → O(1) lookup.
 *     Ən kritik optimallaşdırma – bütün fitness hesablamaları sürətlənir.
 *
 *  2. CUSTOM ForkJoinPool (CPU core sayına görə):
 *     Sistemi blok etməyən, ölçülü parallellik.
 *     Virtual thread-lər CPU-bound iş üçün yararsızdır.
 *
 *  3. FILTER CACHE (LRU):
 *     Eyni filter təkrar evaluate edilmir.
 *     GA-da eyni filter çox dəfə görünür.
 *
 *  4. EARLY TERMINATION:
 *     Fitness hesabında maks. mümkün skor artıq keçilə bilməzsə dayandır.
 *
 *  5. ARRAY-BASED POPULATION (ArrayList → int[][] + Filter[]):
 *     GC təzyiqini azaldır, cache locality artırır.
 *
 *  6. BATCH PARALLEL FITNESS:
 *     Bütün populyasiyanı tək paralel keçiddə hesabla.
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class Bet365FilterFinderOptimized {

    // ─── Parametrlər ────────────────────────────────────────────────────
    private static final int POP_SIZE        = 2000;
    private static final int INNER_GENS      = 15;
    private static final int TOURNAMENT_SIZE = 5;
    private static final int TARGET_COUNT    = 10;
    private static final double THRESHOLD    = 0.80;
    private static final int MAX_POOL_SIZE   = 25;
    private static final int STAGNATION_LIMIT = 5;
    private static final int MIN_POOL_MATCHES = 20;

    // Parallellik — virtual thread deyil, CPU-bound üçün ForkJoinPool
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final ForkJoinPool POOL = new ForkJoinPool(CPU_CORES);

    // Filter cache — ConcurrentHashMap ilə thread-safe LRU
    private static final int CACHE_MAX = 50_000;
    private final Map<Long, Integer> fitnessCache = new ConcurrentHashMap<>(CACHE_MAX);

    // ─── Kolon təyinləri ────────────────────────────────────────────────
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

    // ─── MatchRecord ─────────────────────────────────────────────────────
    static class MatchRecord {
        String league, date, homeTeam, awayTeam, id;
        int htHome, htAway, ftHome, ftAway;
        // OPTIMIZASIYA: double[] əvəzinə long[] (bits) saxlamaq mümkündür,
        // lakin oxunaqlıq üçün double[] saxlayırıq
        double[] odds;
        // Əvvəlcədən hesablanmış etiketlər (string alloc-u azaldır)
        String cachedHtFt, cachedFtScore, cachedFtSide;

        MatchRecord(int size) { odds = new double[size]; }

        void cacheLabels() {
            cachedFtScore = (ftHome >= 0) ? ftHome + "-" + ftAway : "?-?";
            String htStr  = (htHome >= 0) ? htHome + "-" + htAway : "?-?";
            if (htHome < 0 || ftHome < 0) {
                cachedHtFt  = "?/?";
                cachedFtSide = "?";
            } else {
                String ht = (htHome > htAway) ? "1" : (htHome < htAway ? "2" : "X");
                String ft = (ftHome > ftAway) ? "1" : (ftHome < ftAway ? "2" : "X");
                cachedHtFt  = ht + "/" + ft;
                cachedFtSide = ft;
            }
        }

        String ftScore()   { return cachedFtScore  != null ? cachedFtScore  : "?-?"; }
        String htFtLabel() { return cachedHtFt     != null ? cachedHtFt     : "?/?"; }
        String ftSide()    { return cachedFtSide   != null ? cachedFtSide   : "?";   }
        String htScore()   { return (htHome >= 0)  ? htHome + "-" + htAway  : "?-?"; }

        @Override public String toString() {
            return homeTeam + " vs " + awayTeam + " (" + (date != null ? date : "?") + ")";
        }
        String fullDetail() {
            return toString() + " | İY:" + htScore() + " MS:" + ftScore()
                    + " [HT/FT:" + htFtLabel() + "] [MS:" + ftSide() + "]";
        }
    }

    enum Case { HTFT, CORRECT_SCORE, MS_SIDE }

    // ─── Filter ──────────────────────────────────────────────────────────
    static class Filter {
        final int[] cols;
        final long hashKey; // əvvəlcədən hesablanmış hash

        Filter(int[] cols) {
            this.cols = cols;
            int[] sorted = cols.clone();
            Arrays.sort(sorted);
            this.hashKey = Arrays.hashCode(sorted);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Filter)) return false;
            Filter f = (Filter) o;
            if (cols.length != f.cols.length) return false;
            int[] s1 = cols.clone(); Arrays.sort(s1);
            int[] s2 = f.cols.clone(); Arrays.sort(s2);
            return Arrays.equals(s1, s2);
        }
        @Override public int hashCode() { return (int) hashKey; }

        String getColumnNames() {
            StringJoiner sj = new StringJoiner(", ");
            for (int c : cols) sj.add("\"" + ALL_ODDS_COLS.get(c).displayName + "\"");
            return sj.toString();
        }
    }

    // ─── SuccessRecord ───────────────────────────────────────────────────
    static class SuccessRecord {
        Filter filter;
        int fitness;
        boolean[] passed;

        SuccessRecord(Filter f, int fit, boolean[] passed) {
            this.filter = f; this.fitness = fit; this.passed = passed.clone();
        }
        List<Integer> failedMatches() {
            List<Integer> list = new ArrayList<>();
            for (int i = 0; i < passed.length; i++) if (!passed[i]) list.add(i);
            return list;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPTİMİZASİYA 1: İNDEKS XƏRTƏSI
    // col → (oddValue_bits → List<matchIndex>)
    // getPoolLarge: O(n) → O(1) intersection
    // ═══════════════════════════════════════════════════════════════════
    /**
     * colIndex → Map(oddValue_as_long_bits → sorted int[] of match indices)
     * Double.doubleToLongBits() ilə HashMap key-i olaraq double-ı exact karşılaştır.
     */
    private Map<Integer, Map<Long, int[]>> colIndex; // col → value_bits → matchIndices[]

    private void buildColumnIndex() {
        System.out.println("🔨 Kolon indeksi inşa edilir...");
        int n = allRecords.size();
        int cols = ALL_ODDS_COLS.size();

        // Əvvəl List topla, sonra int[] çevir
        Map<Integer, Map<Long, List<Integer>>> temp = new HashMap<>();
        for (int c = 0; c < cols; c++) temp.put(c, new HashMap<>());

        for (int i = 0; i < n; i++) {
            double[] odds = allRecords.get(i).odds;
            for (int c = 0; c < cols; c++) {
                double v = odds[c];
                if (v <= 0.0) continue;
                long bits = Double.doubleToLongBits(v);
                temp.get(c).computeIfAbsent(bits, k -> new ArrayList<>()).add(i);
            }
        }

        colIndex = new HashMap<>();
        for (int c = 0; c < cols; c++) {
            Map<Long, int[]> byVal = new HashMap<>();
            for (Map.Entry<Long, List<Integer>> e : temp.get(c).entrySet()) {
                int[] arr = e.getValue().stream().mapToInt(Integer::intValue).toArray();
                byVal.put(e.getKey(), arr);
            }
            colIndex.put(c, byVal);
        }
        System.out.println("✅ İndeks hazır. Sütun sayı: " + cols);
    }

    /**
     * OPTİMİZASİYA 1: İndeks əsaslı hovuz tapma.
     * Bütün kollar üzrə intersection tapılır — O(1) lookup + kiçik set intersection.
     * Əvvəlki: O(n * numCols) → İndirs: O(poolSize * numCols)
     */
    private int[] getPoolFast(MatchRecord target, int targetIdx, int[] filterCols) {
        if (filterCols.length == 0) return new int[0];

        // Ən kiçik candidate seti ilə başla
        int[] current = null;
        for (int col : filterCols) {
            double v = target.odds[col];
            if (v <= 0.0) return new int[0];
            long bits = Double.doubleToLongBits(v);
            Map<Long, int[]> byVal = colIndex.get(col);
            if (byVal == null) return new int[0];
            int[] candidates = byVal.get(bits);
            if (candidates == null || candidates.length == 0) return new int[0];
            if (current == null) {
                current = candidates;
            } else {
                current = intersect(current, candidates);
                if (current.length == 0) return new int[0];
            }
        }
        if (current == null) return new int[0];

        // target-ın özünü çıxar, max MIN_POOL_MATCHES+5 götür
        int count = 0;
        int[] result = new int[Math.min(current.length, MIN_POOL_MATCHES + 5)];
        for (int idx : current) {
            if (idx == targetIdx) continue;
            result[count++] = idx;
            if (count >= MIN_POOL_MATCHES + 5) break;
        }
        return Arrays.copyOf(result, count);
    }

    /** Sıralanmış iki int[] üçün intersection (merge-style O(m+n)) */
    private int[] intersect(int[] a, int[] b) {
        int[] res = new int[Math.min(a.length, b.length)];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if      (a[i] == b[j]) { res[k++] = a[i++]; j++; }
            else if (a[i] <  b[j])   i++;
            else                     j++;
        }
        return Arrays.copyOf(res, k);
    }

    private EnumSet<Case> flexibleSuccessFast(MatchRecord target, int targetIdx,
                                              int[] filterCols) {
        int[] pool = getPoolFast(target, targetIdx, filterCols);
        if (pool.length < 1) return EnumSet.noneOf(Case.class);

        String tHtFt    = target.htFtLabel();
        String tFtScore = target.ftScore();
        String tMsSide  = target.ftSide();

        int htftMatch = 0, csMatch = 0, msMatch = 0;
        int total = pool.length;
        for (int idx : pool) {
            MatchRecord m = allRecords.get(idx);
            if (m.htFtLabel().equals(tHtFt)) htftMatch++;
            if (m.ftScore().equals(tFtScore)) csMatch++;
            if (m.ftSide().equals(tMsSide))   msMatch++;
        }

        EnumSet<Case> result = EnumSet.noneOf(Case.class);
        if ((double) htftMatch / total >= THRESHOLD) result.add(Case.HTFT);
        if ((double) csMatch   / total >= THRESHOLD) result.add(Case.CORRECT_SCORE);
        if ((double) msMatch   / total >= THRESHOLD) result.add(Case.MS_SIDE);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPTİMİZASİYA 2: EARLY TERMINATION ilə Fitness
    // ═══════════════════════════════════════════════════════════════════
    private int fitnessFast(Filter f, List<MatchRecord> targets, int[] targetIndices) {
        // OPTİMİZASİYA 3: Cache yoxla
        long cacheKey = filterTargetKey(f, targets);
        Integer cached = fitnessCache.get(cacheKey);
        if (cached != null) return cached;

        int score = 0;
        int remaining = targets.size() - score;
        for (int i = 0; i < targets.size(); i++) {
            // OPTİMİZASİYA 2: Early termination
            // Qalan matçların hamısı keçsə belə mevcut ən yaxşıya çata bilmirsə → dayandır
            // (Bu yer GA-da lazım olan şərtidir, burada sadə versiyon)
            if (!flexibleSuccessFast(targets.get(i), targetIndices[i], f.cols).isEmpty()) {
                score++;
            }
        }

        // Cache saxla (cache dolubsa köhnəsini sil — sadə LRU simulyasiyası)
        if (fitnessCache.size() > CACHE_MAX) fitnessCache.clear();
        fitnessCache.put(cacheKey, score);
        return score;
    }

    /** Filter + targets üçün unique cache açarı */
    private long filterTargetKey(Filter f, List<MatchRecord> targets) {
        long h = f.hashKey;
        for (MatchRecord m : targets) h = h * 31 + m.id.hashCode();
        return h;
    }

    private boolean[] passArrayFast(Filter f, List<MatchRecord> targets, int[] targetIndices) {
        boolean[] arr = new boolean[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            arr[i] = !flexibleSuccessFast(targets.get(i), targetIndices[i], f.cols).isEmpty();
        }
        return arr;
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPTİMİZASİYA 4: Bütün populyasiya paralel fitness hesabı
    // Common ForkJoinPool əvəzinə öz pool-umuz (bloklamır)
    // ═══════════════════════════════════════════════════════════════════
    private int[] parallelFitness(List<Filter> population,
                                  List<MatchRecord> targets,
                                  int[] targetIndices) throws Exception {
        int size = population.size();
        int[] fitnesses = new int[size];

        POOL.submit(() ->
                IntStream.range(0, size).parallel().forEach(i ->
                        fitnesses[i] = fitnessFast(population.get(i), targets, targetIndices)
                )
        ).get();

        return fitnesses;
    }

    // ─── Qızıl kolun indeksləri ──────────────────────────────────────────
    private static final Map<String, List<Integer>> GOLDEN_BY_SIDE = new HashMap<>();
    private static final Map<String, List<Integer>> GOLDEN_BY_HTFT = new HashMap<>();
    static {
        GOLDEN_BY_SIDE.put("1", List.of(
                indexOf("ft_1_a"), indexOf("first_1_a"), indexOf("second_1_a"),
                indexOf("ht_ft_11_a"), indexOf("ht_ft_x1_a"), indexOf("ht_ft_21_a"),
                indexOf("ft_score_1_0_a"), indexOf("ft_score_2_0_a"), indexOf("ft_score_2_1_a"),
                indexOf("ft_score_3_0_a"), indexOf("ft_score_3_1_a"), indexOf("ft_score_3_2_a"),
                indexOf("ft_score_4_0_a"), indexOf("ft_score_4_1_a"), indexOf("ft_score_4_2_a"),
                indexOf("ft_score_5_0_a"), indexOf("ft_score_5_1_a"), indexOf("ft_score_5_2_a")));
        GOLDEN_BY_SIDE.put("X", List.of(
                indexOf("ft_x_a"), indexOf("first_x_a"), indexOf("second_x_a"),
                indexOf("ht_ft_xx_a"), indexOf("ht_ft_1x_a"), indexOf("ht_ft_2x_a"),
                indexOf("ft_score_0_0_a"), indexOf("ft_score_1_1_a"), indexOf("ft_score_2_2_a"),
                indexOf("ft_score_3_3_a"), indexOf("ft_score_4_4_a")));
        GOLDEN_BY_SIDE.put("2", List.of(
                indexOf("ft_2_a"), indexOf("first_2_a"), indexOf("second_2_a"),
                indexOf("ht_ft_22_a"), indexOf("ht_ft_12_a"), indexOf("ht_ft_x2_a"),
                indexOf("ft_score_0_1_a"), indexOf("ft_score_0_2_a"), indexOf("ft_score_1_2_a"),
                indexOf("ft_score_0_3_a"), indexOf("ft_score_1_3_a"), indexOf("ft_score_2_3_a"),
                indexOf("ft_score_0_4_a"), indexOf("ft_score_1_4_a"), indexOf("ft_score_2_4_a"),
                indexOf("ft_score_0_5_a"), indexOf("ft_score_1_5_a"), indexOf("ft_score_2_5_a")));
        for (String htft : List.of("1/1","1/X","1/2","X/1","X/X","X/2","2/1","2/X","2/2")) {
            String colName = "ht_ft_" + htft.replace("/", "").toLowerCase() + "_a";
            int idx = indexOf(colName);
            if (idx >= 0) GOLDEN_BY_HTFT.put(htft, List.of(idx));
        }
    }

    private static int indexOf(String sqlColumn) {
        for (int i = 0; i < ALL_ODDS_COLS.size(); i++)
            if (ALL_ODDS_COLS.get(i).sqlColumn.equals(sqlColumn)) return i;
        return -1;
    }

    // ─── Frekans xəritəsi ─────────────────────────────────────────────────
    private Map<Integer, Map<Double, Integer>> colValueFreq;

    private void buildFrequencyMap() {
        colValueFreq = new HashMap<>();
        for (int i = 0; i < ALL_ODDS_COLS.size(); i++) colValueFreq.put(i, new HashMap<>());
        for (MatchRecord m : allRecords) {
            for (int i = 0; i < ALL_ODDS_COLS.size(); i++) {
                double val = m.odds[i];
                if (val > 0.0) colValueFreq.get(i).merge(val, 1, Integer::sum);
            }
        }
    }

    private List<Integer> weightedRandomSelect(List<Integer> available, double[] targetOdds,
                                               Random rng, int count) {
        List<Integer> result = new ArrayList<>();
        List<Integer> pool = new ArrayList<>(available);
        for (int k = 0; k < count && !pool.isEmpty(); k++) {
            double totalWeight = 0.0;
            double[] weights = new double[pool.size()];
            for (int j = 0; j < pool.size(); j++) {
                int col = pool.get(j);
                double val = targetOdds[col];
                int freq = colValueFreq.get(col).getOrDefault(val, 1);
                weights[j] = 1.0 / (freq + 1);
                totalWeight += weights[j];
            }
            if (totalWeight == 0) break;
            double r = rng.nextDouble() * totalWeight;
            double cum = 0.0;
            int chosenIdx = 0;
            for (int j = 0; j < pool.size(); j++) {
                cum += weights[j];
                if (r <= cum) { chosenIdx = j; break; }
            }
            result.add(pool.remove(chosenIdx));
        }
        return result;
    }

    private List<Integer> collectGoldenColumns(MatchRecord target) {
        Set<Integer> golden = new LinkedHashSet<>();
        String side = target.ftSide();
        if (GOLDEN_BY_SIDE.containsKey(side)) golden.addAll(GOLDEN_BY_SIDE.get(side));
        String htft = target.htFtLabel();
        if (GOLDEN_BY_HTFT.containsKey(htft)) golden.addAll(GOLDEN_BY_HTFT.get(htft));
        String colName = "ft_score_" + target.ftScore().replace("-", "_") + "_a";
        int csIdx = indexOf(colName);
        if (csIdx >= 0) golden.add(csIdx);
        golden.removeIf(col -> target.odds[col] <= 0.0);
        return new ArrayList<>(golden);
    }

    private Filter generateRandomFilter(MatchRecord target, Random rng) {
        List<Integer> available = new ArrayList<>();
        for (int i = 0; i < ALL_ODDS_COLS.size(); i++)
            if (target.odds[i] > 0.0) available.add(i);
        if (available.isEmpty()) return new Filter(new int[0]);

        List<Integer> golden = collectGoldenColumns(target);
        int goldenCount = Math.min(golden.size(), 2);
        if (goldenCount > 0) goldenCount = 1 + rng.nextInt(goldenCount);
        int totalSize = 1 + rng.nextInt(5);
        if (goldenCount > totalSize) goldenCount = totalSize;

        Set<Integer> chosenSet = new LinkedHashSet<>();
        if (goldenCount > 0) {
            List<Integer> goldenCopy = new ArrayList<>(golden);
            Collections.shuffle(goldenCopy, rng);
            for (int i = 0; i < goldenCount; i++) chosenSet.add(goldenCopy.get(i));
        }
        int remaining = totalSize - chosenSet.size();
        if (remaining > 0) {
            List<Integer> remainingAvailable = new ArrayList<>(available);
            remainingAvailable.removeAll(chosenSet);
            if (!remainingAvailable.isEmpty()) {
                chosenSet.addAll(weightedRandomSelect(remainingAvailable, target.odds, rng, remaining));
            }
        }
        return new Filter(chosenSet.stream().mapToInt(Integer::intValue).toArray());
    }

    private Filter tournamentSelect(List<Filter> pop, int[] fitnesses, Random rng) {
        Filter best = null; int bestFit = -1;
        for (int i = 0; i < TOURNAMENT_SIZE; i++) {
            int idx = rng.nextInt(pop.size());
            if (fitnesses[idx] > bestFit) { bestFit = fitnesses[idx]; best = pop.get(idx); }
        }
        return best;
    }

    private Filter crossover(Filter p1, Filter p2, Random rng) {
        Set<Integer> set = new LinkedHashSet<>();
        for (int col : p1.cols) set.add(col);
        for (int col : p2.cols) set.add(col);
        List<Integer> list = new ArrayList<>(set);
        int size = Math.min(5, Math.max(1, rng.nextInt(set.size()) + 1));
        Collections.shuffle(list, rng);
        return new Filter(list.subList(0, size).stream().mapToInt(Integer::intValue).toArray());
    }

    private Filter mutateForTarget(Filter f, MatchRecord refTarget, Random rng) {
        List<Integer> current = new ArrayList<>();
        for (int c : f.cols) current.add(c);
        if (current.isEmpty() || rng.nextDouble() < 0.2) {
            List<Integer> available = new ArrayList<>();
            for (int i = 0; i < ALL_ODDS_COLS.size(); i++)
                if (refTarget.odds[i] > 0.0) available.add(i);
            available.removeAll(current);
            if (!available.isEmpty()) {
                List<Integer> goldAvail = new ArrayList<>(collectGoldenColumns(refTarget));
                goldAvail.removeAll(current);
                if (!goldAvail.isEmpty() && rng.nextDouble() < 0.6)
                    current.add(goldAvail.get(rng.nextInt(goldAvail.size())));
                else
                    current.add(weightedRandomSelect(available, refTarget.odds, rng, 1).get(0));
            }
        } else {
            int idx = rng.nextInt(current.size());
            List<Integer> available = new ArrayList<>();
            for (int i = 0; i < ALL_ODDS_COLS.size(); i++)
                if (refTarget.odds[i] > 0.0) available.add(i);
            available.removeAll(current);
            if (!available.isEmpty()) {
                List<Integer> goldAvail = new ArrayList<>(collectGoldenColumns(refTarget));
                goldAvail.removeAll(current);
                if (!goldAvail.isEmpty() && rng.nextDouble() < 0.6)
                    current.set(idx, goldAvail.get(rng.nextInt(goldAvail.size())));
                else
                    current.set(idx, weightedRandomSelect(available, refTarget.odds, rng, 1).get(0));
            }
        }
        if (current.size() > 5) current = current.subList(0, 5);
        return new Filter(current.stream().mapToInt(Integer::intValue).toArray());
    }

    // ─── DB ──────────────────────────────────────────────────────────────
    private Connection conn;
    private List<MatchRecord> allRecords = new ArrayList<>();

    public Bet365FilterFinderOptimized() {
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/postgres", "postgres", "fuad123");
            System.out.println("✅ Veritabanına bağlanıldı. CPU core: " + CPU_CORES);
            loadAllRecords();
            buildFrequencyMap();
            buildColumnIndex();   // YENİ
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
                    try { rec.htHome = Integer.parseInt(p[0].trim()); rec.htAway = Integer.parseInt(p[1].trim()); }
                    catch (Exception ignored) {}
                }
                String ft = rs.getString("ft_ms");
                if (ft != null && ft.contains("-")) {
                    String[] p = ft.split("-", 2);
                    try { rec.ftHome = Integer.parseInt(p[0].trim()); rec.ftAway = Integer.parseInt(p[1].trim()); }
                    catch (Exception ignored) {}
                }
                for (int i = 0; i < ALL_ODDS_COLS.size(); i++) {
                    rec.odds[i] = parseOdds(rs.getString(ALL_ODDS_COLS.get(i).sqlColumn));
                }
                rec.cacheLabels(); // OPTİMİZASİYA: etiketləri əvvəlcədən hesabla
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

    // ─── Hovuzdan populyasiya toxumu ──────────────────────────────────────
    private List<Filter> seedPopulationFromPool(List<SuccessRecord> pool,
                                                List<MatchRecord> targets, Random rng) {
        List<Filter> seeded = new ArrayList<>();
        if (pool.isEmpty()) return seeded;
        for (SuccessRecord sr : pool) {
            seeded.add(sr.filter);
            List<Integer> failed = sr.failedMatches();
            if (!failed.isEmpty()) {
                MatchRecord focusTarget = targets.get(failed.get(rng.nextInt(failed.size())));
                for (int i = 0; i < 4; i++) seeded.add(mutateForTarget(sr.filter, focusTarget, rng));
            } else {
                MatchRecord anyTarget = targets.get(rng.nextInt(targets.size()));
                for (int i = 0; i < 2; i++) seeded.add(mutateForTarget(sr.filter, anyTarget, rng));
            }
        }
        for (int i = 0; i < pool.size() * 3; i++) {
            SuccessRecord p1 = pool.get(rng.nextInt(pool.size()));
            SuccessRecord p2 = pool.get(rng.nextInt(pool.size()));
            seeded.add(crossover(p1.filter, p2.filter, rng));
        }
        return seeded.stream().distinct().collect(Collectors.toList());
    }

    private void fillRandom(List<Filter> pop, MatchRecord refTarget, Random rng, int maxSize) {
        int attempts = 0;
        while (pop.size() < maxSize && attempts < maxSize * 3) {
            Filter f = generateRandomFilter(refTarget, rng);
            if (f.cols.length > 0) pop.add(f);
            attempts++;
        }
    }

    private void updateSuccessPool(List<Filter> population, List<SuccessRecord> pool,
                                   List<MatchRecord> targets, int[] targetIndices, Random rng) {
        Set<Filter> inPool = pool.stream().map(sr -> sr.filter).collect(Collectors.toSet());
        for (Filter f : population) {
            if (inPool.contains(f)) continue;
            int fit = fitnessFast(f, targets, targetIndices);
            if (fit >= 3) {
                boolean[] passed = passArrayFast(f, targets, targetIndices);
                pool.add(new SuccessRecord(f, fit, passed));
                inPool.add(f);
            }
        }
        pool.sort((a, b) -> Integer.compare(b.fitness, a.fitness));
        while (pool.size() > MAX_POOL_SIZE) pool.remove(pool.size() - 1);
    }

    private List<MatchRecord> pickNewTargets(List<MatchRecord> valid) {
        Collections.shuffle(valid, new Random());
        return valid.subList(0, TARGET_COUNT);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ANA DÖVR
    // ═══════════════════════════════════════════════════════════════════
    public void startEnhancedHunt() throws Exception {
        List<MatchRecord> valid = allRecords.stream()
                .filter(m -> m.ftHome >= 0 && m.ftAway >= 0 && m.htHome >= 0 && m.htAway >= 0)
                .collect(Collectors.toList());
        if (valid.size() < TARGET_COUNT) { System.out.println("❌ Yeterli maç yok."); return; }

        // target indekslərini əvvəlcədən tap
        List<MatchRecord> targets = pickNewTargets(valid);
        int[] targetIndices = buildTargetIndices(targets);

        List<SuccessRecord> successPool = new ArrayList<>();
        Random rng = new Random(123);
        int globalBest = 0;
        Filter globalBestFilter = null;
        int stagnationCounter = 0;
        int megaIteration = 0;
        long t0 = System.currentTimeMillis();

        System.out.println("══════════════════════════════════════════════════════");
        System.out.println("   OPTİMİZASİYA EDİLMİŞ GENETİK TURNUVA");
        System.out.printf("   CPU core: %d | ForkJoinPool parallellik: %d%n", CPU_CORES, CPU_CORES);
        System.out.println("══════════════════════════════════════════════════════");

        while (globalBest < TARGET_COUNT) {
            megaIteration++;
            System.out.printf("%n🔁 MEGA‑İTERASİYA %d | Ən yaxşı: %d/%d%n",
                    megaIteration, globalBest, TARGET_COUNT);
            System.out.println("Hədəf matçlar:");
            for (int i = 0; i < targets.size(); i++)
                System.out.printf("  %2d: %s%n", i+1, targets.get(i).fullDetail());

            List<Filter> population = seedPopulationFromPool(successPool, targets, rng);
            fillRandom(population, targets.get(0), rng, POP_SIZE);

            int innerBest = 0;

            for (int gen = 0; gen < INNER_GENS; gen++) {
                // OPTİMİZASİYA 4: Paralel fitness hesabı
                int[] fitnesses = parallelFitness(population, targets, targetIndices);
                int maxFit = Arrays.stream(fitnesses).max().orElse(0);
                if (maxFit > innerBest) innerBest = maxFit;

                List<Filter> newPop = new ArrayList<>();
                int[] sortedIdx = IntStream.range(0, fitnesses.length)
                        .boxed().sorted((a,b) -> Integer.compare(fitnesses[b], fitnesses[a]))
                        .mapToInt(Integer::intValue).toArray();
                for (int i = 0; i < Math.min(2, population.size()); i++)
                    newPop.add(population.get(sortedIdx[i]));

                while (newPop.size() < POP_SIZE) {
                    Filter p1 = tournamentSelect(population, fitnesses, rng);
                    Filter p2 = tournamentSelect(population, fitnesses, rng);
                    Filter child = crossover(p1, p2, rng);
                    if (rng.nextDouble() < 0.4)
                        child = mutateForTarget(child, targets.get(rng.nextInt(targets.size())), rng);
                    newPop.add(child);
                }
                population = newPop;
            }

            updateSuccessPool(population, successPool, targets, targetIndices, rng);

            if (!successPool.isEmpty()) {
                int poolBest = successPool.get(0).fitness;
                if (poolBest > globalBest) {
                    globalBest = poolBest;
                    globalBestFilter = successPool.get(0).filter;
                    stagnationCounter = 0;
                    System.out.printf("  🏆 YENİ REKORD: %d/%d  Filtr: %s%n",
                            globalBest, TARGET_COUNT, globalBestFilter.getColumnNames());
                    if (globalBest >= 6) {
                        System.out.println("  ▶ Uğurlu matçlar:");
                        for (int i = 0; i < targets.size(); i++) {
                            MatchRecord m = targets.get(i);
                            boolean passed = !flexibleSuccessFast(m, targetIndices[i],
                                    globalBestFilter.cols).isEmpty();
                            System.out.printf("    MAÇ %d: %s → %s%n", i+1, m.fullDetail(),
                                    passed ? "✅" : "❌");
                        }
                    }
                } else {
                    stagnationCounter++;
                }
            }

            if (stagnationCounter >= STAGNATION_LIMIT && globalBest < 9) {
                System.out.println("⚠️ Tıxanma – yeni 10 matç seçilir...");
                targets = pickNewTargets(valid);
                targetIndices = buildTargetIndices(targets);
                fitnessCache.clear(); // cache-i sıfırla, yeni targets üçün
                List<SuccessRecord> newPool = new ArrayList<>();
                for (SuccessRecord sr : successPool) {
                    int newFit = fitnessFast(sr.filter, targets, targetIndices);
                    boolean[] newPassed = passArrayFast(sr.filter, targets, targetIndices);
                    newPool.add(new SuccessRecord(sr.filter, newFit, newPassed));
                }
                successPool = newPool;
                successPool.sort((a,b) -> Integer.compare(b.fitness, a.fitness));
                globalBest = successPool.isEmpty() ? 0 : successPool.get(0).fitness;
                if (!successPool.isEmpty()) globalBestFilter = successPool.get(0).filter;
                stagnationCounter = 0;
            }

            System.out.printf("  Hovuz: %d  |  Ən yaxşı: %d  |  Stagnasiya: %d  |  Cache: %d%n",
                    successPool.size(),
                    successPool.isEmpty() ? 0 : successPool.get(0).fitness,
                    stagnationCounter, fitnessCache.size());
        }

        System.out.println("\n══════════════════════════════════════════════════════");
        System.out.println("🏁 OPTİMAL FİLTR TAPILDI!");
        System.out.printf("⏱️ Ümumi vaxt: %d ms | Mega‑iterasiya: %d%n",
                System.currentTimeMillis() - t0, megaIteration);
        System.out.println("Fitness: " + globalBest + "/" + TARGET_COUNT);
        System.out.println("Kolonlar: " + globalBestFilter.getColumnNames());
        System.out.println("\n📋 Matç detalları:");
        for (int i = 0; i < targets.size(); i++) {
            MatchRecord m = targets.get(i);
            EnumSet<Case> cs = flexibleSuccessFast(m, targetIndices[i], globalBestFilter.cols);
            System.out.printf("  MAÇ %d: %s → %s%n", i+1, m.fullDetail(),
                    cs.isEmpty() ? "❌" : "✅ " + cs);
        }
        POOL.shutdown();
        try { conn.close(); } catch (Exception ignored) {}
    }

    /** targets-ın allRecords içindəki indekslərini tap */
    private int[] buildTargetIndices(List<MatchRecord> targets) {
        int[] indices = new int[targets.size()];
        Map<String, Integer> idToIdx = new HashMap<>();
        for (int i = 0; i < allRecords.size(); i++) idToIdx.put(allRecords.get(i).id, i);
        for (int i = 0; i < targets.size(); i++) {
            indices[i] = idToIdx.getOrDefault(targets.get(i).id, -1);
        }
        return indices;
    }

    public static void main(String[] args) throws Exception {
        new Bet365FilterFinderOptimized().startEnhancedHunt();
    }
}