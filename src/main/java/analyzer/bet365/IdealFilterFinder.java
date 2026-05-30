package analyzer.bet365;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * IdealFilterFinder
 * -----------------
 * Meqsed: bet365 odds verilenlerinden ELE bir "filter" tapmaq ki, filterden kecen
 * oyunlardan 20-de en azi 17-si secilmis netice kriteriyasini (BTTS Yes/No, Over/Under 2.5,
 * ve ya 1/X/2) dogru versin.
 *
 * Filter = bir nece odds sutunu uzerinde [min,max] araliqlari toplusu.
 * Bir oyun filterden "kecir" eger butun araliqlara uygundursa.
 *
 * 3 ferqli taktika ile axtaris:
 *   A) Random 20 oyun + konsensus  -> senin literal tesvirin
 *   B) Filter-first random axtaris -> Virtual Threads ile parallel, ideal/generalize filter
 *   C) Greedy (acgozlu) yonlu axtaris -> en gucluleri addim-addim yigir
 *
 * Java 21 teleb olunur (Virtual Threads).
 * Ishe salmaq:   java IdealFilterFinder.java        (tek-fayl source launcher, javac lazim deyil)
 * ve ya:         javac IdealFilterFinder.java && java IdealFilterFinder
 */
public class IdealFilterFinder {

    /* =========================================================================
     *  KONFIQURASIYA  -- burani oz DB-ne gore deyish
     * ========================================================================= */
    static final String JDBC_URL = "jdbc:postgresql://localhost:5432/postgres";
    static final String DB_USER  = "postgres";
    static final String DB_PASS  = "fuad123";
    static       String TABLE_NAME = "public.bet365_matches";   // schema.table

    // Skor sutunlari STRING-dir ("H:A" formatinda, mes: "2:1").
    //   ft_ms  -> mac sonu (Full Time) skoru   -> butun FT kriteriyalari bundan hesablanir
    //   ht_iy  -> ilk hisse (1st Half) skoru    -> opsional (İY kriteriyalar ucun saxlanir)
    static String COL_SCORE_FT = "ft_ms";   // mac sonu skoru (mecburi)
    static String COL_SCORE_HT = "ht_iy";   // ilk yari skoru (opsional, null ola biler)

    // QEYD: namized odds sutunlari ARTIQ "specific" deyil -> ASHAGIDA generate olunan
    // BUTUN odds sutunlari (ODDS_COLUMNS) istifade olunur (1x2, BTTS, ÇŞ, A/U, HT/FT, Correct Score).
    // Butun odds sutunlari numeric(10,2)-dir, getDouble ile oxunur, boolean yoxdur.

    // Cox sutun + 780k setir cox yaddash teleb edir. Evvelce az setirle test etmek ucun
    // ROW_LIMIT teyin ede bilersen (0 = butun setirler). Real run ucun -Xmx4g (ve ya artiq) ver.
    static final int     ROW_LIMIT           = 0;        // 0 = limitsiz

    // Axtaris parametrleri
    static final int    SAMPLE_SIZE          = 20;        // her denemede oyun sayi
    static final int    MIN_SUCCESS          = 17;        // ugurlu sayilmasi ucun min dogru
    static final double TARGET_RATE          = MIN_SUCCESS / (double) SAMPLE_SIZE; // 0.85
    static final int    MIN_PASS_FOR_FILTER  = 40;        // filter bu qeder oyuna uygun olmalidir ki etibarli sayilsin

    static final int    STRAT_A_TRIALS       = 1500;      // A taktikasinda neche random deneme
    static final int    STRAT_B_ITERS        = 6000;      // B taktikasinda neche random filter
    static final int    MIN_COND             = 2;         // bir filterde min shert sayi
    static final int    MAX_COND             = 5;         // bir filterde max shert sayi
    static final int    QUANTILE_BINS        = 10;        // her sutun ucun kvantil zolaqlari (decile)
    static final long   SEED                 = 42L;
    static final int    PRINT_EVERY_B        = 250;       // B-de 17-den asagi denemeleri her N-de bir cap et

    // Sintetik test rejimi: DB-siz logikani yoxlamaq ucun suni data.
    // Real DB ucun false (indi false-dir). Offline test ucun true et.
    static final boolean USE_SYNTHETIC       = false;
    static final int     SYNTHETIC_ROWS      = 40000;

    /* =========================================================================
     *  Netice kriteriyalari
     * ========================================================================= */
    enum Criterion {
        BTTS_YES ("Full Time BTTS Evet"),
        BTTS_NO  ("Full Time BTTS Yox"),
        OVER_25  ("Full Time 2.5 Ust"),
        UNDER_25 ("Full Time 2.5 Alt"),
        HOME     ("Full Time 1 (Ev)"),
        DRAW     ("Full Time X (Heci-heci)"),
        AWAY     ("Full Time 2 (Qonaq)");
        final String label;
        Criterion(String l){ this.label = l; }
    }
    static final Criterion[] CRIT = Criterion.values();

    // Oxunaqli display adlari (id -> Turkce/AZ ad). Tapilmasa id qaytarir.
    // Hem de BUTUN odds sutunlarinin siyahisini (ODDS_COL_LIST) qurur -- senin ALL_COLS-un eynisi.
    static final List<String>        ODDS_COL_LIST = new ArrayList<>();
    static final Map<String,String>  DISPLAY       = new HashMap<>();
    private static void add(String id, String name){ ODDS_COL_LIST.add(id); DISPLAY.put(id, name); }
    static {
        // 1x2
        add("ft_1_a","MS 1"); add("ft_x_a","MS X"); add("ft_2_a","MS 2");
        add("first_1_a","İY 1"); add("first_x_a","İY X"); add("first_2_a","İY 2");
        add("second_1_a","2Y 1"); add("second_x_a","2Y X"); add("second_2_a","2Y 2");
        // Both teams to score
        add("bts_ft_yes_a","KG Evet"); add("bts_ft_no_a","KG Hayır");
        add("bts_first_yes_a","İY KG Evet"); add("bts_first_no_a","İY KG Hayır");
        add("bts_second_yes_a","2Y KG Evet"); add("bts_second_no_a","2Y KG Hayır");
        // Double chance
        add("dbc_ft_1x_a","ÇŞ 1X"); add("dbc_ft_12_a","ÇŞ 12"); add("dbc_ft_x2_a","ÇŞ X2");
        add("dbc_first_1x_a","İY ÇŞ 1X"); add("dbc_first_12_a","İY ÇŞ 12"); add("dbc_first_x2_a","İY ÇŞ X2");
        // Over/Under Full Time
        add("ft_0_5_over_a","A/U 0.5 Üst"); add("ft_0_5_under_a","A/U 0.5 Alt");
        add("ft_1_5_over_a","A/U 1.5 Üst"); add("ft_1_5_under_a","A/U 1.5 Alt");
        add("ft_2_5_over_a","A/U 2.5 Üst"); add("ft_2_5_under_a","A/U 2.5 Alt");
        add("ft_3_5_over_a","A/U 3.5 Üst"); add("ft_3_5_under_a","A/U 3.5 Alt");
        add("ft_4_5_over_a","A/U 4.5 Üst"); add("ft_4_5_under_a","A/U 4.5 Alt");
        add("ft_5_5_over_a","A/U 5.5 Üst"); add("ft_5_5_under_a","A/U 5.5 Alt");
        // Over/Under 1st Half
        add("first_0_5_over_a","İY A/U 0.5 Üst"); add("first_0_5_under_a","İY A/U 0.5 Alt");
        add("first_1_5_over_a","İY A/U 1.5 Üst"); add("first_1_5_under_a","İY A/U 1.5 Alt");
        add("first_2_5_over_a","İY A/U 2.5 Üst"); add("first_2_5_under_a","İY A/U 2.5 Alt");
        // Over/Under 2nd Half
        add("second_0_5_over_a","2Y A/U 0.5 Üst"); add("second_0_5_under_a","2Y A/U 0.5 Alt");
        add("second_1_5_over_a","2Y A/U 1.5 Üst"); add("second_1_5_under_a","2Y A/U 1.5 Alt");
        add("second_2_5_over_a","2Y A/U 2.5 Üst"); add("second_2_5_under_a","2Y A/U 2.5 Alt");
        // HT/FT
        add("ht_ft_11_a","HT/FT 1/1"); add("ht_ft_1x_a","HT/FT 1/X"); add("ht_ft_12_a","HT/FT 1/2");
        add("ht_ft_x1_a","HT/FT X/1"); add("ht_ft_xx_a","HT/FT X/X"); add("ht_ft_x2_a","HT/FT X/2");
        add("ht_ft_21_a","HT/FT 2/1"); add("ht_ft_2x_a","HT/FT 2/X"); add("ht_ft_22_a","HT/FT 2/2");
        // Correct Score Full Time
        String[] ftScores = {"1:0","2:0","2:1","3:0","3:1","3:2","4:0","4:1","4:2","4:3","5:0","5:1","5:2",
                "0:0","1:1","2:2","3:3","4:4","0:1","0:2","1:2","0:3","1:3","2:3","0:4","1:4","2:4","3:4","0:5","1:5","2:5"};
        for (String sc : ftScores) add("ft_score_" + sc.replace(":", "_") + "_a", "MS Skor " + sc);
        // Correct Score 1st Half
        String[] htScores = {"1:0","2:0","2:1","3:0","3:1","3:2","0:0","1:1","2:2","0:1","0:2","1:2","0:3","1:3","2:3"};
        for (String sc : htScores) add("first_score_" + sc.replace(":", "_") + "_a", "İY Skor " + sc);
    }
    // Butun odds sutunlari (ALL_COLS) -- static blokdan SONRA initialize olunur
    static final String[] ODDS_COLUMNS = ODDS_COL_LIST.toArray(new String[0]);

    static String disp(String id){ return DISPLAY.getOrDefault(id, id); }

    /* =========================================================================
     *  Datanin saxlandigi struktur (sutun-bazli, suretli)
     * ========================================================================= */
    static int            N;           // oyun (setir) sayi
    static String[]       colNames;    // movcud namized odds sutunlari
    static double[][]     cols;        // cols[c][row] -> odds deyeri (yoxdursa NaN)
    static Map<String,Integer> colIdx; // sutun adi -> indeks
    static boolean[][]    outcome;     // outcome[criterion.ordinal()][row]
    static int[]          homeGoals;   // FT ev qollari (skor yoxdursa -1)
    static int[]          awayGoals;   // FT qonaq qollari
    static int[]          htHomeGoals; // İY ev qollari (opsional, -1)
    static int[]          htAwayGoals;
    static double[][]     quantiles;   // quantiles[c][0..BINS] -> kesim noqteleri

    /* =========================================================================
     *  Filter strukturu
     * ========================================================================= */
    record Cond(int colIndex, double lo, double hi) {}

    static boolean passes(Cond[] f, int row){
        for (Cond c : f){
            double v = cols[c.colIndex()][row];
            if (Double.isNaN(v) || v < c.lo() || v > c.hi()) return false;
        }
        return true;
    }

    // Bir filterin butun datasetde neticesi
    static final class Eval {
        int pass = 0;
        int[] succ = new int[CRIT.length];
    }

    static Eval evaluate(Cond[] f){
        Eval e = new Eval();
        for (int r = 0; r < N; r++){
            if (passes(f, r)){
                e.pass++;
                for (int k = 0; k < CRIT.length; k++)
                    if (outcome[k][r]) e.succ[k]++;
            }
        }
        return e;
    }

    // Eval-den en yaxsi kriteriyani sec (pass >= MIN_PASS sherti ile)
    static int bestCriterion(Eval e){
        int best = -1; double bestRate = -1;
        for (int k = 0; k < CRIT.length; k++){
            if (e.pass < MIN_PASS_FOR_FILTER) continue;
            double rate = e.succ[k] / (double) e.pass;
            if (rate > bestRate){ bestRate = rate; best = k; }
        }
        return best;
    }

    /* =========================================================================
     *  MAIN
     * ========================================================================= */
    public static void main(String[] args) throws Exception {
        // Konsolda İ/Ü/Ş/ç ve ∈ duzgun gorunsun deye UTF-8 mecbur edirik
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        long t0 = System.currentTimeMillis();
        line();
        System.out.println("  IDEAL FILTER FINDER  |  hedef: " + SAMPLE_SIZE + " oyundan " +
                MIN_SUCCESS + " dogru  (>= " + pct(TARGET_RATE) + ")");
        line();

        if (USE_SYNTHETIC) {
            System.out.println("[REJIM] SINTETIK data (DB-siz test). Ishlek ucun USE_SYNTHETIC=false et.");
            loadSynthetic(SYNTHETIC_ROWS);
        } else {
            System.out.println("[REJIM] POSTGRESQL: " + JDBC_URL + "  cedvel=" + TABLE_NAME);
            loadFromDb();
        }

        System.out.println("[DATA] Yuklenen oyun sayi : " + N);
        System.out.println("[DATA] Namized odds sutunlari (" + colNames.length + "): " +
                Arrays.toString(colNames));
        printBaseRates();
        computeQuantiles();
        System.out.println();

        Random rnd = new Random(SEED);

        // Qlobal en yaxsi filteri saxlamaq ucun
        Found globalBest = null;

        globalBest = best(globalBest, strategyA(rnd));
        globalBest = best(globalBest, strategyB(rnd));
        globalBest = best(globalBest, strategyC());

        line();
        System.out.println("  YEKUN NETICE");
        line();
        if (globalBest != null && globalBest.rate >= TARGET_RATE){
            System.out.println("  >>> IDEAL FILTER TAPILDI <<<");
            printFound(globalBest);
            demoTwentyGames(globalBest, rnd);
        } else if (globalBest != null){
            System.out.println("  Hedefe (>=" + pct(TARGET_RATE) + ") catan filter tapilmadi.");
            System.out.println("  EN YAXSI NAMIZED (hedefden asagi):");
            printFound(globalBest);
            System.out.println("  Meslehet: STRAT_B_ITERS / MAX_COND artir, MIN_PASS_FOR_FILTER azalt.");
        } else {
            System.out.println("  Hec bir uygun filter alinmadi (pass>=" + MIN_PASS_FOR_FILTER + " olan yoxdur).");
        }
        System.out.println("\n[VAXT] Umumi: " + (System.currentTimeMillis()-t0)/1000.0 + " san");
    }

    /* =========================================================================
     *  TAKTIKA A  --  Random 20 oyun + konsensus (senin literal tesvirin)
     *  20 random oyun secir; hansi kriteriyada en cox dogru var? >=17 olsa,
     *  hemin 20 oyunun odds araliqlarindan filter cixarir ve butun DB-de yoxlayir.
     * ========================================================================= */
    static Found strategyA(Random rnd){
        line();
        System.out.println("  TAKTIKA A: random " + SAMPLE_SIZE + " oyun + konsensus  (" + STRAT_A_TRIALS + " deneme)");
        line();
        Found localBest = null;

        for (int t = 1; t <= STRAT_A_TRIALS; t++){
            int[] idx = randomDistinct(rnd, SAMPLE_SIZE, N);

            // her kriteriya ucun bu 20 oyunda dogru sayi
            int bestK = -1, bestCnt = -1;
            for (int k = 0; k < CRIT.length; k++){
                int c = 0;
                for (int r : idx) if (outcome[k][r]) c++;
                if (c > bestCnt){ bestCnt = c; bestK = k; }
            }

            if (bestCnt >= MIN_SUCCESS){
                // 20 oyunun odds araliqlarindan filter cixar
                Cond[] f = deriveFilterFromGames(idx);
                Eval e = evaluate(f);
                double rate = e.pass>0 ? e.succ[bestK]/(double)e.pass : 0;
                System.out.printf("  [A#%d] >> KONSENSUS! %s : %d/%d  -> filter cixarildi (sutun=%d)%n",
                        t, CRIT[bestK].label, bestCnt, SAMPLE_SIZE, f.length);
                System.out.println("        " + filterStr(f));
                System.out.printf("        Butun DB-de: kecen=%d, %s dogru=%d (%s)%n",
                        e.pass, CRIT[bestK].label, e.succ[bestK], pct(rate));
                if (e.pass >= MIN_PASS_FOR_FILTER){
                    Found cand = new Found("A", f, bestK, e.pass, e.succ[bestK], rate);
                    localBest = best(localBest, cand);
                }
            } else {
                // 17-den asagi denemeleri de gosterek (her 100-de bir, sel olmasin)
                if (t % 100 == 0 || t <= 5)
                    System.out.printf("  [A#%d] en yaxsi: %s %d/%d  (17-den asagi)%n",
                            t, CRIT[bestK].label, bestCnt, SAMPLE_SIZE);
            }
        }
        System.out.println("  TAKTIKA A bitdi. En yaxsi: " + (localBest==null? "-" : briefStr(localBest)));
        System.out.println();
        return localBest;
    }

    // 20 oyunun namized sutunlari uzre [min,max] araliqlarindan filter qur
    static Cond[] deriveFilterFromGames(int[] idx){
        List<Cond> conds = new ArrayList<>();
        for (int c = 0; c < colNames.length; c++){
            double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
            int valid = 0;
            for (int r : idx){
                double v = cols[c][r];
                if (!Double.isNaN(v)){ mn = Math.min(mn, v); mx = Math.max(mx, v); valid++; }
            }
            // butun 20 oyunda dolu olan sutunlar + bir balaca tolerans
            if (valid == idx.length && mn <= mx){
                double pad = (mx - mn) * 0.02 + 1e-9;
                conds.add(new Cond(c, mn - pad, mx + pad));
            }
        }
        return conds.toArray(new Cond[0]);
    }

    /* =========================================================================
     *  TAKTIKA B  --  Filter-first random axtaris (VIRTUAL THREADS ile parallel)
     *  Random sutunlar + random kvantil zolaqlari ile minlerle filter yaradir,
     *  her birini butun DB-de paralel qiymetlendirir.
     * ========================================================================= */
    static Found strategyB(Random rnd) throws Exception {
        line();
        System.out.println("  TAKTIKA B: random filter axtarisi (Virtual Threads, " + STRAT_B_ITERS + " filter)");
        line();

        // her task ucun ona mexsus toxum (deterministik + thread-safe)
        List<Callable<Object[]>> tasks = new ArrayList<>(STRAT_B_ITERS);
        for (int i = 0; i < STRAT_B_ITERS; i++){
            final long taskSeed = rnd.nextLong();
            tasks.add(() -> {
                Random r = new Random(taskSeed);
                Cond[] f = randomFilter(r);
                Eval e = evaluate(f);
                int k = bestCriterion(e);
                double rate = (k < 0) ? -1 : e.succ[k]/(double)e.pass;
                return new Object[]{ f, k, e.pass, (k<0?0:e.succ[k]), rate };
            });
        }

        List<Future<Object[]>> futures;
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()){
            long ts = System.currentTimeMillis();
            futures = ex.invokeAll(tasks);
            System.out.printf("  (%d filter %d virtual thread-de %.2f san-de qiymetlendirildi)%n",
                    STRAT_B_ITERS, STRAT_B_ITERS, (System.currentTimeMillis()-ts)/1000.0);
        }

        // neticeleri ardicil emal et ki loglar qarismasin
        Found localBest = null;
        int found = 0, shown = 0;
        for (int i = 0; i < futures.size(); i++){
            Object[] o = futures.get(i).get();
            Cond[] f = (Cond[]) o[0];
            int k = (int) o[1];
            int pass = (int) o[2];
            int succ = (int) o[3];
            double rate = (double) o[4];
            if (k < 0) continue; // pass < MIN_PASS

            Found cand = new Found("B", f, k, pass, succ, rate);

            boolean improved = (localBest == null) || better(cand, localBest);
            if (rate >= TARGET_RATE){
                found++;
                System.out.printf("  [B#%d] >> FILTER TAPILDI  %s  kecen=%d dogru=%d (%s)%n",
                        i, CRIT[k].label, pass, succ, pct(rate));
                System.out.println("        " + filterStr(f));
            } else if (improved){
                System.out.printf("  [B#%d] yeni en-yaxsi (hedefden asagi)  %s  kecen=%d dogru=%d (%s)%n",
                        i, CRIT[k].label, pass, succ, pct(rate));
            } else if ((shown++ % PRINT_EVERY_B) == 0){
                System.out.printf("  [B#%d] deneme  %s  kecen=%d dogru=%d (%s)%n",
                        i, CRIT[k].label, pass, succ, pct(rate));
            }
            localBest = best(localBest, cand);
        }
        System.out.println("  TAKTIKA B bitdi. Hedefe catan filter sayi: " + found +
                ". En yaxsi: " + (localBest==null? "-" : briefStr(localBest)));
        System.out.println();
        return localBest;
    }

    static Cond[] randomFilter(Random r){
        int k = MIN_COND + r.nextInt(MAX_COND - MIN_COND + 1);
        int[] pick = randomDistinct(r, Math.min(k, colNames.length), colNames.length);
        Cond[] f = new Cond[pick.length];
        for (int i = 0; i < pick.length; i++){
            int c = pick[i];
            int b1 = r.nextInt(QUANTILE_BINS);
            int b2 = b1 + r.nextInt(QUANTILE_BINS - b1);   // b2 >= b1
            double lo = quantiles[c][b1];
            double hi = quantiles[c][b2 + 1];
            f[i] = new Cond(c, lo, hi);
        }
        return f;
    }

    /* =========================================================================
     *  TAKTIKA C  --  Greedy (acgozlu) yonlu axtaris
     *  Her kriteriya ucun bosh filterden bashlayir, her addimda ugur faizini en cox
     *  artiran sherti (sutun + decile zolagi) elave edir (pass>=MIN_PASS qaldigi muddetce).
     * ========================================================================= */
    static Found strategyC() throws Exception {
        line();
        System.out.println("  TAKTIKA C: greedy yonlu axtaris (her kriteriya ucun)");
        line();
        Found localBest = null;

        for (int k = 0; k < CRIT.length; k++){
            System.out.println("  --- Kriteriya: " + CRIT[k].label + " ---");
            List<Cond> cur = new ArrayList<>();
            double curRate = baseRate(k);
            int curPass = N;
            System.out.printf("      bashlangic: kecen=%d dogru=%s%n", curPass, pct(curRate));

            for (int step = 0; step < MAX_COND; step++){
                Cond bestCond = bestGreedyCondition(cur, k);
                if (bestCond == null){ System.out.println("      daha yaxsilashma yoxdur, dayanir."); break; }

                List<Cond> trial = new ArrayList<>(cur);
                trial.add(bestCond);
                Eval e = evaluate(trial.toArray(new Cond[0]));
                if (e.pass < MIN_PASS_FOR_FILTER){
                    System.out.println("      en yaxsi shert pass-i " + e.pass + "-e salir (<" +
                            MIN_PASS_FOR_FILTER + "), dayanir.");
                    break;
                }
                double newRate = e.succ[k]/(double)e.pass;
                if (newRate <= curRate + 1e-9){
                    System.out.printf("      addim %d: yaxsilashma yoxdur (%s -> %s), dayanir.%n",
                            step+1, pct(curRate), pct(newRate));
                    break;
                }
                cur = trial; curRate = newRate; curPass = e.pass;
                System.out.printf("      addim %d: + %s  => kecen=%d dogru=%d (%s)%n",
                        step+1, condStr(bestCond), e.pass, e.succ[k], pct(newRate));
            }

            if (!cur.isEmpty() && curPass >= MIN_PASS_FOR_FILTER){
                int succ = (int)Math.round(curRate * curPass);
                Found cand = new Found("C", cur.toArray(new Cond[0]), k, curPass, succ, curRate);
                if (curRate >= TARGET_RATE)
                    System.out.println("      >> bu kriteriyada FILTER TAPILDI: " + pct(curRate));
                localBest = best(localBest, cand);
            }
        }
        System.out.println("  TAKTIKA C bitdi. En yaxsi: " + (localBest==null? "-" : briefStr(localBest)));
        System.out.println();
        return localBest;
    }

    // Movcud filtere elave edile bilecek en yaxsi sherti tap (Virtual Threads ile paralel skor)
    static Cond bestGreedyCondition(List<Cond> cur, int k) throws Exception {
        Cond[] base = cur.toArray(new Cond[0]);
        // namized shertler: her sutun, her zolaq (tek decile + 2-decile)
        List<Cond> cands = new ArrayList<>();
        for (int c = 0; c < colNames.length; c++){
            boolean used = false;
            for (Cond cc : base) if (cc.colIndex()==c){ used = true; break; }
            if (used) continue;
            for (int b1 = 0; b1 < QUANTILE_BINS; b1++){
                cands.add(new Cond(c, quantiles[c][b1], quantiles[c][b1+1]));       // tek decile
                if (b1 + 2 <= QUANTILE_BINS)
                    cands.add(new Cond(c, quantiles[c][b1], quantiles[c][b1+2]));   // ikiqat decile
            }
        }

        List<Callable<double[]>> tasks = new ArrayList<>();
        for (int ci = 0; ci < cands.size(); ci++){
            final int fci = ci;
            final Cond cnd = cands.get(ci);
            tasks.add(() -> {
                Cond[] trial = Arrays.copyOf(base, base.length + 1);
                trial[base.length] = cnd;
                Eval e = evaluate(trial);
                if (e.pass < MIN_PASS_FOR_FILTER) return new double[]{ fci, -1, e.pass };
                return new double[]{ fci, e.succ[k]/(double)e.pass, e.pass };
            });
        }

        double bestRate = -1; int bestIdx = -1;
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()){
            List<Future<double[]>> fs = ex.invokeAll(tasks);
            for (Future<double[]> fu : fs){
                double[] d = fu.get();
                if (d[1] > bestRate){ bestRate = d[1]; bestIdx = (int) d[0]; }
            }
        }
        return bestIdx < 0 ? null : cands.get(bestIdx);
    }

    /* =========================================================================
     *  20 OYUN DEMOSU  -- tapilan filterden 20 random oyun secib 17 yoxlamasi
     * ========================================================================= */
    static void demoTwentyGames(Found f, Random rnd){
        // filterden kecen oyunlarin indeksleri
        List<Integer> passing = new ArrayList<>();
        for (int r = 0; r < N; r++) if (passes(f.filter, r)) passing.add(r);
        System.out.println("\n  [DEMO] Filterden kecen oyun sayi: " + passing.size());
        if (passing.size() < SAMPLE_SIZE){
            System.out.println("  [DEMO] 20 oyunluq numune ucun kifayet deyil.");
            return;
        }
        int k = f.critK;
        for (int round = 1; round <= 3; round++){
            Collections.shuffle(passing, rnd);
            List<Integer> pick = passing.subList(0, SAMPLE_SIZE);
            int ok = 0;
            System.out.printf("  [DEMO #%d] tesadufi 20 oyun (kriteriya: %s):%n", round, CRIT[k].label);
            for (int r : pick){
                boolean hit = outcome[k][r];
                if (hit) ok++;
                System.out.printf("      oyun#%-7d skor %d-%d   %s%n",
                        r, homeGoals[r], awayGoals[r], hit ? "DOGRU" : "sehv");
            }
            System.out.printf("  [DEMO #%d] NETICE: %d/%d %s%n%n",
                    round, ok, SAMPLE_SIZE, ok>=MIN_SUCCESS ? ">>> UGUR" : "(17-den asagi)");
        }
    }

    /* =========================================================================
     *  En yaxsi neticenin saxlanmasi + komekci funksiyalar
     * ========================================================================= */
    record Found(String strat, Cond[] filter, int critK, int pass, int succ, double rate) {}

    static boolean better(Found a, Found b){
        if (a.rate != b.rate) return a.rate > b.rate;     // evvel ugur faizi
        return a.pass > b.pass;                            // beraberlikde daha cox oyun
    }
    static Found best(Found a, Found b){
        if (a == null) return b;
        if (b == null) return a;
        return better(a, b) ? a : b;
    }

    static String filterStr(Cond[] f){
        StringBuilder sb = new StringBuilder("Filter[");
        for (int i = 0; i < f.length; i++){
            if (i>0) sb.append(" VE ");
            sb.append(condStr(f[i]));
        }
        return sb.append("]").toString();
    }
    static String condStr(Cond c){
        return String.format("%s odds∈[%.2f, %.2f]", disp(colNames[c.colIndex()]), c.lo(), c.hi());
    }
    static String briefStr(Found f){
        return f.strat + " | " + CRIT[f.critK].label + " | kecen=" + f.pass +
                " dogru=" + f.succ + " (" + pct(f.rate) + ")";
    }
    static void printFound(Found f){
        System.out.println("    Taktika    : " + f.strat);
        System.out.println("    Kriteriya  : " + CRIT[f.critK].label);
        System.out.println("    " + filterStr(f.filter));
        System.out.println("    Netice     : kecen=" + f.pass + ", dogru=" + f.succ + " (" + pct(f.rate) + ")");
    }

    static int[] randomDistinct(Random r, int count, int bound){
        if (count >= bound){
            int[] all = new int[bound];
            for (int i = 0; i < bound; i++) all[i] = i;
            return all;
        }
        Set<Integer> s = new LinkedHashSet<>();
        while (s.size() < count) s.add(r.nextInt(bound));
        int[] out = new int[count]; int i = 0;
        for (int v : s) out[i++] = v;
        return out;
    }

    static double baseRate(int k){
        int c = 0; for (int r = 0; r < N; r++) if (outcome[k][r]) c++;
        return c/(double)N;
    }
    static void printBaseRates(){
        System.out.print("[BAZA] Datasetdeki ümumi faizler: ");
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < CRIT.length; k++){
            if (k>0) sb.append(", ");
            sb.append(CRIT[k].label).append("=").append(pct(baseRate(k)));
        }
        System.out.println(sb);
    }
    static String pct(double d){ return String.format("%.1f%%", d*100); }
    static void line(){ System.out.println("============================================================================"); }

    /* =========================================================================
     *  Kvantil (decile) kesim noqtelerinin hesablanmasi
     * ========================================================================= */
    static void computeQuantiles(){
        quantiles = new double[colNames.length][QUANTILE_BINS + 1];
        for (int c = 0; c < colNames.length; c++){
            double[] vals = new double[N];
            int m = 0;
            for (int r = 0; r < N; r++){
                double v = cols[c][r];
                if (!Double.isNaN(v)) vals[m++] = v;
            }
            if (m == 0){
                Arrays.fill(quantiles[c], Double.NaN);
                continue;
            }
            double[] v = Arrays.copyOf(vals, m);
            Arrays.sort(v);
            for (int b = 0; b <= QUANTILE_BINS; b++){
                double q = (double) b / QUANTILE_BINS;
                int pos = (int) Math.round(q * (m - 1));
                if (pos < 0) pos = 0; if (pos > m-1) pos = m-1;
                quantiles[c][b] = v[pos];
            }
            // kenarlari acaq ki uc-zolaqlar her seyi tutsun
            quantiles[c][0]              = v[0]   - 0.01;
            quantiles[c][QUANTILE_BINS]  = v[m-1] + 0.01;
        }
    }

    /* =========================================================================
     *  POSTGRESQL-den yukleme
     * ========================================================================= */
    static void loadFromDb() throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS)){
            conn.setAutoCommit(false);

            // schema.table ayir
            String schema = "public", tbl = TABLE_NAME;
            if (TABLE_NAME.contains(".")){ String[] p = TABLE_NAME.split("\\.", 2); schema = p[0]; tbl = p[1]; }

            // 1) movcud sutunlari oyren
            Set<String> existing = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?")){
                ps.setString(1, schema); ps.setString(2, tbl);
                try (ResultSet rs = ps.executeQuery()){
                    while (rs.next()) existing.add(rs.getString(1).toLowerCase());
                }
            }
            if (existing.isEmpty())
                throw new IllegalStateException("Cedvel tapilmadi/bosdur: " + TABLE_NAME +
                        ". TABLE_NAME-i duzelt (schema.table).");
            System.out.println("[DB] Cedvelde sutun sayi: " + existing.size());

            // 2) skor (string) sutunlarini yoxla / avtomatik tap
            verifyScoreColumns(existing);
            System.out.println("[DB] Skor sutunlari (string): FT=" + COL_SCORE_FT +
                    ", HT=" + (COL_SCORE_HT==null? "-" : COL_SCORE_HT));

            // 3) movcud BUTUN odds sutunlari (ALL_COLS)
            List<String> use = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String c : ODDS_COLUMNS){
                if (existing.contains(c.toLowerCase())) use.add(c); else missing.add(c);
            }
            if (!missing.isEmpty())
                System.out.println("[DB] Diqqet, cedvelde olmayan " + missing.size() +
                        " odds sutunu oturulur: " + missing);
            if (use.isEmpty())
                throw new IllegalStateException("Hec bir odds sutunu cedvelde yoxdur.");
            colNames = use.toArray(new String[0]);
            colIdx = new HashMap<>();
            for (int i = 0; i < colNames.length; i++) colIdx.put(colNames[i], i);
            System.out.println("[DB] Istifade olunan odds sutun sayi: " + colNames.length);

            // 4) row sayi
            int rowCount;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TABLE_NAME)){
                rs.next(); rowCount = rs.getInt(1);
            }
            if (ROW_LIMIT > 0) rowCount = Math.min(rowCount, ROW_LIMIT);

            // 5) data cek (streaming)
            cols = new double[colNames.length][rowCount];
            for (double[] a : cols) Arrays.fill(a, Double.NaN);
            homeGoals   = new int[rowCount];
            awayGoals   = new int[rowCount];
            htHomeGoals = new int[rowCount];
            htAwayGoals = new int[rowCount];

            boolean haveHt = COL_SCORE_HT != null && existing.contains(COL_SCORE_HT.toLowerCase());
            StringBuilder sel = new StringBuilder("SELECT ");
            for (String c : colNames) sel.append(c).append(", ");
            sel.append(COL_SCORE_FT);
            if (haveHt) sel.append(", ").append(COL_SCORE_HT);
            sel.append(" FROM ").append(TABLE_NAME);
            if (ROW_LIMIT > 0) sel.append(" LIMIT ").append(ROW_LIMIT);

            int ftIdx = colNames.length + 1;       // 1-based: ft_ms
            int htIdx = colNames.length + 2;       // 1-based: ht_iy
            int r = 0, badFt = 0;
            try (Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)){
                st.setFetchSize(10000);
                try (ResultSet rs = st.executeQuery(sel.toString())){
                    while (rs.next() && r < rowCount){
                        for (int c = 0; c < colNames.length; c++){
                            double v = rs.getDouble(c + 1);
                            cols[c][r] = rs.wasNull() ? Double.NaN : v;
                        }
                        int[] ft = parseScore(rs.getString(ftIdx));
                        if (ft == null){ homeGoals[r] = -1; awayGoals[r] = -1; badFt++; }
                        else { homeGoals[r] = ft[0]; awayGoals[r] = ft[1]; }

                        if (haveHt){
                            int[] ht = parseScore(rs.getString(htIdx));
                            if (ht == null){ htHomeGoals[r] = -1; htAwayGoals[r] = -1; }
                            else { htHomeGoals[r] = ht[0]; htAwayGoals[r] = ht[1]; }
                        } else { htHomeGoals[r] = -1; htAwayGoals[r] = -1; }

                        r++;
                        if (r % 100000 == 0) System.out.println("[DB] yuklendi: " + r + " setir...");
                    }
                }
            }
            N = r;
            if (badFt > 0)
                System.out.println("[DB] Diqqet: " + badFt + " setirde ft_ms skoru oxunmadi/bos idi (atlandi).");
            buildOutcomes();
        }
    }

    // "2:1" / "2-1" / "2 : 1" -> {2,1}; xetali/null -> null
    static int[] parseScore(String s){
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        String[] p = s.split("\\s*[:\\-–xX/]\\s*");
        if (p.length != 2) return null;
        try {
            return new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) };
        } catch (NumberFormatException e){ return null; }
    }

    static void verifyScoreColumns(Set<String> existing){
        // FT skoru
        if (!existing.contains(COL_SCORE_FT.toLowerCase())){
            String[] ftAlts = {"ft_ms","ftms","ms_skor","score","full_time_score","ms","ft_score"};
            COL_SCORE_FT = null;
            for (String a : ftAlts) if (existing.contains(a)){ COL_SCORE_FT = a; break; }
            if (COL_SCORE_FT == null)
                throw new IllegalStateException(
                        "\n>>> FT skor sutunu (ft_ms) tapilmadi! <<<\n" +
                                "COL_SCORE_FT-i elle teyin et.\nCedveldeki butun sutunlar:\n" + new TreeSet<>(existing));
        }
        // HT skoru (opsional)
        if (COL_SCORE_HT != null && !existing.contains(COL_SCORE_HT.toLowerCase())){
            String[] htAlts = {"ht_iy","htiy","iy_skor","ht_score","ht"};
            String found = null;
            for (String a : htAlts) if (existing.contains(a)){ found = a; break; }
            COL_SCORE_HT = found;  // tapilmasa null
        }
    }

    /* =========================================================================
     *  SINTETIK data (DB-siz test ucun) -- odds ile netice arasinda real elaqe planlanir
     * ========================================================================= */
    static void loadSynthetic(int rows){
        Random r = new Random(SEED);
        colNames = ODDS_COLUMNS.clone();
        colIdx = new HashMap<>();
        for (int i = 0; i < colNames.length; i++) colIdx.put(colNames[i], i);
        N = rows;
        cols = new double[colNames.length][rows];
        for (double[] a : cols) Arrays.fill(a, Double.NaN);
        homeGoals = new int[rows];
        awayGoals = new int[rows];

        int iO25  = colIdx.get("ft_2_5_over_a");
        int iU25  = colIdx.get("ft_2_5_under_a");
        int i1    = colIdx.get("ft_1_a");
        int iX    = colIdx.get("ft_x_a");
        int i2    = colIdx.get("ft_2_a");
        int iBy   = colIdx.get("bts_ft_yes_a");
        int iBn   = colIdx.get("bts_ft_no_a");

        for (int g = 0; g < rows; g++){
            double lamH = 0.4 + r.nextDouble()*2.4;   // ev gozlenilen qol
            double lamA = 0.3 + r.nextDouble()*2.1;   // qonaq
            int hg = poisson(r, lamH);
            int ag = poisson(r, lamA);
            homeGoals[g] = hg; awayGoals[g] = ag;

            double lam = lamH + lamA;
            double pOver = 1.0 - Math.exp(-lam)*(1 + lam + lam*lam/2.0);
            double pUnder = 1.0 - pOver;
            double[] p1x2 = oneXtwo(lamH, lamA);
            double pBy = (1-Math.exp(-lamH))*(1-Math.exp(-lamA));

            // odds = (1/p)*overround*noise   (real bukmeker mantiqi)
            cols[iO25][g] = fairOdds(pOver, r);
            cols[iU25][g] = fairOdds(pUnder, r);
            cols[i1][g]   = fairOdds(p1x2[0], r);
            cols[iX][g]   = fairOdds(p1x2[1], r);
            cols[i2][g]   = fairOdds(p1x2[2], r);
            cols[iBy][g]  = fairOdds(pBy, r);
            cols[iBn][g]  = fairOdds(1-pBy, r);

            // qalan namized sutunlari ucun realistik amma neticeyle az elaqeli odds
            for (int c = 0; c < colNames.length; c++){
                if (Double.isNaN(cols[c][g]))
                    cols[c][g] = Math.round((1.1 + r.nextDouble()*9.0) * 100)/100.0;
                // bezi nulllar
                if (r.nextDouble() < 0.02) cols[c][g] = Double.NaN;
            }
        }
        buildOutcomes();
    }

    static double fairOdds(double p, Random r){
        p = Math.max(0.01, Math.min(0.99, p));
        double overround = 1.06;
        double noise = 0.9 + r.nextDouble()*0.2;   // +-10%
        double o = (1.0/p) / overround * noise;
        return Math.round(Math.max(1.01, o) * 100)/100.0;
    }
    static int poisson(Random r, double lam){
        double L = Math.exp(-lam), p = 1; int k = 0;
        do { k++; p *= r.nextDouble(); } while (p > L);
        return k - 1;
    }
    static double[] oneXtwo(double lamH, double lamA){
        double h=0,d=0,a=0;
        for (int i = 0; i <= 12; i++){
            for (int j = 0; j <= 12; j++){
                double pij = pmf(i, lamH)*pmf(j, lamA);
                if (i>j) h+=pij; else if (i==j) d+=pij; else a+=pij;
            }
        }
        return new double[]{h,d,a};
    }
    static double pmf(int k, double lam){
        double p = Math.exp(-lam);
        for (int i = 1; i <= k; i++) p *= lam/i;
        return p;
    }

    /* =========================================================================
     *  Netice boolean massivlerinin qurulmasi (skordan)
     * ========================================================================= */
    static void buildOutcomes(){
        outcome = new boolean[CRIT.length][N];
        for (int rIdx = 0; rIdx < N; rIdx++){
            int hg = homeGoals[rIdx], ag = awayGoals[rIdx];
            if (hg < 0 || ag < 0) continue;  // skor yoxdur -> butun kriteriyalar false
            int tot = hg + ag;
            outcome[Criterion.BTTS_YES.ordinal()][rIdx] = (hg > 0 && ag > 0);
            outcome[Criterion.BTTS_NO.ordinal()][rIdx]  = !(hg > 0 && ag > 0);
            outcome[Criterion.OVER_25.ordinal()][rIdx]  = (tot >= 3);
            outcome[Criterion.UNDER_25.ordinal()][rIdx] = (tot <= 2);
            outcome[Criterion.HOME.ordinal()][rIdx]     = (hg > ag);
            outcome[Criterion.DRAW.ordinal()][rIdx]     = (hg == ag);
            outcome[Criterion.AWAY.ordinal()][rIdx]     = (hg < ag);
        }
    }
}