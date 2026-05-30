package analyzer.bet365;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  Bet365BttsFilterHunter
 * ───────────────────────────────────────────────────────────────────────────
 *  AXIN (sənin dediyin kimi):
 *   1) 20 random oyun seç.
 *   2) 1-ci oyun (seed) üçün: random sütunları bir-bir əlavə edib hovuzu
 *      DARALT — DƏQİQ 2-5 oyun qalana qədər. Bu sütun-ardıcıllığı = "filter".
 *      Hovuzun BTTS proqnozu seed-in əsl BTTS-i ilə uyğundursa → uğurlu filter.
 *      Min-maks 2-5 oyun (158/279 kimi böyük hovuz YOX).
 *   3) Bir seed üçün ən yaxşı 5-10 belə filter yığılır (List).
 *   4) Hər filter QALAN 19 oyuna (cəmi 20) tətbiq olunur: hər oyun öz əmsalları
 *      ilə eyni sütun-ardıcıllığında 2-5-ə daraldılır, BTTS proqnozu çıxır.
 *   5) Filter 20-dən ≥17-ni düz proqnozlayırsa → İDEAL FİLTER tapıldı.
 *   6) Olmasa loop təkrar (20 oyun eyni qala bilər; seed dəyişir, yeni random
 *      ardıcıllıqlar yoxlanır).
 *
 *  UĞUR KRİTERİYASI: yalnız BTTS (KG Var / KG Yox).  Qəbul: 17/20.
 *  PARALLELLİK: Virtual Threads (sənin tələbin).
 *
 *  Java 21.  İşə sal:  java -Xmx6g Bet365BttsFilterHunter.java
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class Bet365BttsFilterHunter {

    /* ===================== KONFIQURASIYA ===================== */
    static final String JDBC_URL = "jdbc:postgresql://localhost:5432/postgres";
    static final String DB_USER  = "postgres";
    static final String DB_PASS  = "fuad123";
    static final String TABLE    = "bet365_matches";

    static final int    GAMES         = 20;     // oyun sayı
    static final int    NEED_HITS     = 17;     // 17/20
    static final int    POOL_MIN      = 2;      // hovuz alt sərhəd
    static final int    POOL_MAX      = 5;      // hovuz üst sərhəd
    static final long   SEED          = 123L;

    // ───── DELİ AXTARIŞ: sonsuz loop + hill-climbing + restart ─────
    static final int    MIN_FILTER_LEN   = 3;       // filterdə min sütun
    static final int    MAX_FILTER_LEN   = 7;       // filterdə maks sütun
    static final int    RANDOM_STARTS    = 600;     // hər restartda neçə random başlanğıc nöqtəsi
    static final int    CLIMB_STEPS      = 40;      // bir başlanğıcdan neçə hill-climb addımı
    static final int    NEIGHBORS_PER_STEP = 40;    // hər addımda neçə qonşu yoxlanır
    static final int    SEED_TRIES       = 120;     // hər oyun üçün neçə dəfə toxum filteri axtarılsın
    static final int    STAGNATION_RESTARTS = 15;   // bu qədər restartda yaxşılaşma olmasa → yeni 20 oyun
    static final boolean RESELECT_ON_STUCK = true;  // ilişəndə yeni 20 oyun seçilsinmi
    static final long   TIME_LIMIT_MS    = 6L*60*60*1000; // 6 saat (təhlükəsizlik limiti; 0 = limitsiz)

    // ADAPTİV DARALMA: ortaq filter sütunları oyunu 2-5-ə endirə bilməsə,
    // oyunun ÖZ əmsallarına uyğun ən-yaxşı doldurucu sütunlar avtomatik əlavə olunur.
    static final boolean ADAPTIVE_FILL   = true;
    static final int     FILL_MAX        = 4;       // maks neçə doldurucu sütun əlavə oluna bilər

    static final boolean USE_SYNTHETIC = false; // offline test üçün true
    static final int     SYNTHETIC_ROWS = 60000;

    /* ===================== Sütunlar ===================== */
    record Col(String sql, String disp) {}
    static final List<Col> COLS = buildCols();
    static final int NC = COLS.size();

    static List<Col> buildCols(){
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

    /* ===================== Data (sütun-bazlı) ===================== */
    static int N;
    static int[][]  code;     // code[col][row] = round(odds*100); 0 = yox
    static byte[]   btts;     // 1=KG Var, 0=KG Yox, -1=skor yox
    static int[]    ftH, ftA, htH, htA;
    static String[] home, away, date, league;

    // İNVERTED İNDEKS: col -> (oddsCode -> sorted row[])
    static List<Map<Integer,int[]>> index;

    static String bttsStr(int v){ return v==1?"KG Var":v==0?"KG Yox":"?"; }

    /* ===================== MAIN ===================== */
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        long t0 = System.currentTimeMillis();
        head("🎯 BET365 BTTS FILTER HUNTER  —  20 oyun → 17 düz (KG Var/Yox)");
        System.out.println("🧵 Virtual Threads | hovuz daralma: " + POOL_MIN + "-" + POOL_MAX + " oyun");

        if (USE_SYNTHETIC){ System.out.println("📂 🧪 SİNTETİK data"); loadSynthetic(); }
        else { System.out.println("📂 🐘 PostgreSQL → " + TABLE); loadDb(); }
        System.out.println("✅ " + N + " oyun, " + NC + " sütun");
        buildIndex();

        // BTTS-i olan oyunlar
        int[] valid = validRows();
        System.out.println("✅ BTTS hesablana bilən oyun: " + valid.length);
        if (valid.length < GAMES){ System.out.println("❌ Kifayət oyun yoxdur."); return; }

        Random rng = new Random(SEED);
        int[] games = pick(valid, GAMES, rng);

        sec("🎲 SEÇİLƏN " + GAMES + " OYUN");
        for (int i = 0; i < games.length; i++)
            System.out.printf("  %2d. %-34s MS:%s → %s%n", i+1, home[games[i]]+" v "+away[games[i]],
                    ftScore(games[i]), bttsStr(btts[games[i]]));

        // ════════ DELİ AXTARIŞ MOTORU: sonsuz loop + hill-climbing + restart ════════
        sec("🔥 DELİ AXTARIŞ — sonsuz loop, hill-climbing, " + NEED_HITS + "/" + GAMES + " tapılana qədər");
        System.out.println("  (dayandırmaq üçün Ctrl+C — ən yaxşı nəticə daim ekranda yenilənir)");

        CommonFilter best = null;
        long tStart = System.currentTimeMillis();
        int restart = 0, stagnation = 0;
        int bestEver = -1;

        outer:
        while (true){
            restart++;
            // bu restart üçün toxumlar (hər oyunun öz filterlərindən) + parametrli random
            long base = SEED * 1_000_003L + restart * 7919L;
            List<int[]> seeds = seedCandidates(games, base);

            // paralel: hər task bir random/toxum başlanğıcdan hill-climb edir
            int starts = RANDOM_STARTS + seeds.size();
            final int[] gms = games;   // lambda üçün effectively-final snapshot
            List<Callable<CommonFilter>> tasks = new ArrayList<>(starts);
            for (int s = 0; s < starts; s++){
                final long sd = base * 31 + s;
                final int  si = s;
                tasks.add(() -> {
                    Random r = new Random(sd);
                    int[] start = (si < seeds.size()) ? seeds.get(si) : randomCols(r);
                    return hillClimb(start, gms, r);   // local search
                });
            }

            CommonFilter roundBest = null;
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()){
                for (Future<CommonFilter> fu : ex.invokeAll(tasks)){
                    CommonFilter c = fu.get();
                    if (roundBest == null || better(c, roundBest)) roundBest = c;
                    if (best == null || better(c, best)){
                        best = c;
                        System.out.printf("  ⬆️ [restart %d] yeni REKORD: %d/%d  (%d sütun: %s)%n",
                                restart, best.hits, GAMES, best.cols.length, names(best.cols));
                        if (best.hits >= NEED_HITS) break outer;   // 🎉 tapıldı
                    }
                }
            }

            if (best != null && best.hits > bestEver){ bestEver = best.hits; stagnation = 0; }
            else stagnation++;

            long el = (System.currentTimeMillis() - tStart);
            System.out.printf("  · restart %d bitdi | round ən yaxşı %d/%d | qlobal %d/%d | stagn %d | %.0f san%n",
                    restart, roundBest==null?0:roundBest.hits, GAMES, best==null?0:best.hits, GAMES,
                    stagnation, el/1000.0);

            // ilişəndə yeni 20 oyun seç
            if (RESELECT_ON_STUCK && stagnation >= STAGNATION_RESTARTS){
                games = pick(valid, GAMES, rng);
                bestEver = -1; best = null; stagnation = 0;
                sec("♻️ İLİŞDİ → YENİ " + GAMES + " OYUN SEÇİLDİ");
                for (int i = 0; i < games.length; i++)
                    System.out.printf("  %2d. %-34s MS:%s → %s%n", i+1, home[games[i]]+" v "+away[games[i]],
                            ftScore(games[i]), bttsStr(btts[games[i]]));
            }

            if (TIME_LIMIT_MS > 0 && el > TIME_LIMIT_MS){
                System.out.println("\n⏰ Vaxt limiti doldu. Ən yaxşı nəticə ilə dayanılır.");
                break;
            }
        }

        sec("🏁 NƏTİCƏ");
        System.out.printf("📈 Ən yaxşı ortaq filter: %d/%d oyun düz  (hədəf: %d)  | restart: %d%n",
                best==null?0:best.hits, GAMES, NEED_HITS, restart);
        if (best != null && best.hits >= NEED_HITS)
            System.out.println("🎉 UĞUR! Tək ortaq filter " + best.hits + "/" + GAMES + " oyunu düz tapdı.");
        else
            System.out.println("😕 Limit daxilində 17/20 tapılmadı (ən yaxşı yuxarıda).");

        if (best != null){
            printFilterBox(best.cols);
            sec("🔬 DETALLI ANALİZ — eyni ortaq filter, hər oyunda");
            for (int g : games) analyze(g, best.cols);
        }
        System.out.printf("%n⏱️  Ümumi vaxt: %.1f san%n", (System.currentTimeMillis()-tStart)/1000.0);
    }

    /* ===================== ORTAQ FİLTER + HILL CLIMBING ===================== */
    record CommonFilter(int[] cols, int hits) {}

    static boolean better(CommonFilter a, CommonFilter b){
        if (a.hits != b.hits) return a.hits > b.hits;
        return a.cols.length < b.cols.length;   // bərabər hitsdə qısa filter
    }

    // Hill-climbing: start nöqtəsindən başla, hər addımda ən yaxşı qonşuya keç (əlavə/dəyiş/sil).
    static CommonFilter hillClimb(int[] start, int[] games, Random r){
        int[] cur = dedupeCols(start);
        int curHits = countHits(cur, games);
        for (int step = 0; step < CLIMB_STEPS; step++){
            int[] bestNbr = null; int bestNbrHits = curHits; int bestLen = cur.length;
            for (int n = 0; n < NEIGHBORS_PER_STEP; n++){
                int[] nbr = mutate(cur, r);
                if (nbr == null) continue;
                int h = countHits(nbr, games);
                if (h > bestNbrHits || (h == bestNbrHits && nbr.length < bestLen)){
                    bestNbrHits = h; bestNbr = nbr; bestLen = nbr.length;
                }
            }
            if (bestNbr == null) break;            // yaxşılaşma yoxdur → lokal maksimum
            cur = bestNbr; curHits = bestNbrHits;
            if (curHits >= NEED_HITS) break;       // erkən dayan
        }
        return new CommonFilter(cur, curHits);
    }

    // qonşu: 3 əməliyyatdan biri — sütun əlavə et / sütun dəyiş / sütun sil
    static int[] mutate(int[] cols, Random r){
        int op = r.nextInt(3);
        if (op == 0 && cols.length < MAX_FILTER_LEN){           // əlavə
            int c; int guard=0; do { c = r.nextInt(NC); } while (contains(cols,c) && ++guard<20);
            if (contains(cols,c)) return null;
            return append(cols, c);
        } else if (op == 2 && cols.length > MIN_FILTER_LEN){    // sil
            return remove(cols, r.nextInt(cols.length));
        } else {                                                 // dəyiş
            int[] out = cols.clone();
            int pos = r.nextInt(out.length);
            int c; int guard=0; do { c = r.nextInt(NC); } while (contains(cols,c) && ++guard<20);
            if (contains(cols,c)) return null;
            out[pos] = c; return out;
        }
    }

    static int[] dedupeCols(int[] a){
        LinkedHashSet<Integer> s = new LinkedHashSet<>();
        for (int x : a) s.add(x);
        int[] o = new int[s.size()]; int i=0; for (int x : s) o[i++]=x; return o;
    }

    // toxumlar: hər oyun üçün tək-oyunluq filter(lər) tapıb ortaq-namizəd kimi götür
    static List<int[]> seedCandidates(int[] games, long base) throws Exception {
        List<int[]> seeds = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int g : games)
            tasks.add(() -> {
                Random r = new Random(base * 131 + g);
                int found = 0;
                for (int t = 0; t < SEED_TRIES && found < 3; t++){
                    int[] f = narrowSeedCols(g, btts[g], r);
                    if (f != null){ seeds.add(f); found++; }
                }
                return null;
            });
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()){ ex.invokeAll(tasks); }
        return new ArrayList<>(seeds);
    }

    static int countHits(int[] cols, int[] games){
        int hits = 0;
        for (int g : games){
            int pred = applyFilter(cols, g);
            if (pred >= 0 && pred == btts[g]) hits++;
        }
        return hits;
    }

    // filter sütunlarını g oyununun öz əmsalları ilə 2-5-ə daralt → BTTS proqnozu (-1 = alınmadı)
    // ADAPTIVE_FILL: ortaq sütunlar bəs etməsə, oyunun öz ən-daraldıcı sütunları ilə doldurulur.
    static int applyFilter(int[] cols, int g){
        int[] pool = null;
        for (int col : cols){
            int cd = code[col][g];
            if (cd == 0) continue;
            int[] cand = index.get(col).get(cd);
            if (cand == null || cand.length == 0) continue;
            int[] np = (pool == null) ? without(cand, g) : without(intersect(pool, cand), g);
            if (np.length < POOL_MIN) continue;
            pool = np;
            if (pool.length <= POOL_MAX) return predict(pool);
        }
        // adaptiv doldurma: hələ böyükdürsə, oyunun digər sütunları ilə daralt
        if (ADAPTIVE_FILL && pool != null && pool.length > POOL_MAX){
            int fills = 0;
            // ən çox daraldan sütunu acgözlüklə seç
            while (pool.length > POOL_MAX && fills < FILL_MAX){
                int bestCol = -1; int[] bestPool = null;
                for (int col = 0; col < NC; col++){
                    if (contains(cols, col)) continue;
                    int cd = code[col][g];
                    if (cd == 0) continue;
                    int[] cand = index.get(col).get(cd);
                    if (cand == null) continue;
                    int[] np = without(intersect(pool, cand), g);
                    if (np.length < POOL_MIN) continue;            // çox daraldar → keç
                    if (bestPool == null || np.length < bestPool.length){ bestPool = np; bestCol = col; }
                    if (np.length <= POOL_MAX){ bestPool = np; bestCol = col; break; }
                }
                if (bestCol < 0) break;
                pool = bestPool; fills++;
                if (pool.length <= POOL_MAX) return predict(pool);
            }
        }
        if (pool != null && pool.length >= POOL_MIN && pool.length <= POOL_MAX) return predict(pool);
        return -1;
    }
    static int predict(int[] pool){ int[] v = bttsVote(pool); return v[0] >= v[1] ? 1 : 0; }

    static int[] randomCols(Random r){
        int len = MIN_FILTER_LEN + r.nextInt(MAX_FILTER_LEN - MIN_FILTER_LEN + 1);
        int[] pick = new int[len];
        boolean[] used = new boolean[NC];
        for (int i = 0; i < len; i++){ int c; do { c = r.nextInt(NC); } while (used[c]); used[c]=true; pick[i]=c; }
        return pick;
    }

    /* ===================== tək-oyunluq daralma (toxum üçün) ===================== */
    // seed-i random sütun ardıcıllığı ilə 2-5-ə daralt; hovuz seed-in BTTS-ini düz verirsə
    // istifadə olunan sütunları (toxum kimi) qaytar, yoxsa null.
    static int[] narrowSeedCols(int seed, int targetBtts, Random r){
        int[] order = shuffledValidCols(seed, r);
        int[] pool = null;
        int[] active = new int[MAX_FILTER_LEN]; int al = 0;
        for (int col : order){
            if (al >= MAX_FILTER_LEN) break;
            int cd = code[col][seed];
            if (cd == 0) continue;
            int[] cand = index.get(col).get(cd);
            if (cand == null || cand.length == 0) continue;
            int[] np = (pool == null) ? without(cand, seed) : without(intersect(pool, cand), seed);
            if (np.length < POOL_MIN){ continue; }   // bu sütun çox daraltdı → atla
            pool = np; active[al++] = col;
            if (pool.length <= POOL_MAX){
                int[] vote = bttsVote(pool);
                int pred = vote[0] >= vote[1] ? 1 : 0;
                if (pred == targetBtts) return Arrays.copyOf(active, al);
                return null;
            }
        }
        return null;
    }

    // {kgVar, kgYox} sayı
    static int[] bttsVote(int[] pool){
        int yes=0,no=0;
        for (int r : pool){ if (btts[r]==1) yes++; else if (btts[r]==0) no++; }
        return new int[]{yes,no};
    }

    /* ===================== Detallı analiz ===================== */
    static void analyze(int g, int[] cols){
        System.out.println("\n┌─ 🎯 " + home[g]+" v "+away[g] + "  MS:" + ftScore(g) + " → əsl: " + bttsStr(btts[g]) + "  #"+g);
        System.out.println("│  🧩 Ortaq filter: " + names(cols));
        int[] pool = null;
        StringBuilder steps = new StringBuilder();
        for (int col : cols){
            int cd = code[col][g];
            if (cd == 0){ continue; }
            int[] cand = index.get(col).get(cd);
            if (cand == null || cand.length == 0) continue;
            int[] np = (pool == null) ? without(cand, g) : without(intersect(pool, cand), g);
            if (np.length < POOL_MIN) continue;
            pool = np;
            steps.append(String.format("│     + %-16s (%.2f) → hovuz %d%n", COLS.get(col).disp(), cd/100.0, pool.length));
            if (pool.length <= POOL_MAX) break;
        }
        // adaptiv doldurma (applyFilter ilə eyni məntiq) — display üçün
        if (ADAPTIVE_FILL && pool != null && pool.length > POOL_MAX){
            int fills = 0;
            while (pool.length > POOL_MAX && fills < FILL_MAX){
                int bestCol=-1; int[] bestPool=null;
                for (int col=0; col<NC; col++){
                    if (contains(cols,col)) continue;
                    int cd = code[col][g]; if (cd==0) continue;
                    int[] cand = index.get(col).get(cd); if (cand==null) continue;
                    int[] np = without(intersect(pool,cand), g);
                    if (np.length < POOL_MIN) continue;
                    if (bestPool==null || np.length<bestPool.length){ bestPool=np; bestCol=col; }
                    if (np.length<=POOL_MAX){ bestPool=np; bestCol=col; break; }
                }
                if (bestCol<0) break;
                pool = bestPool; fills++;
                steps.append(String.format("│     +🔧%-15s (%.2f) → hovuz %d  (adaptiv)%n",
                        COLS.get(bestCol).disp(), code[bestCol][g]/100.0, pool.length));
                if (pool.length<=POOL_MAX) break;
            }
        }
        System.out.print(steps);
        if (pool == null || pool.length < POOL_MIN || pool.length > POOL_MAX){
            System.out.printf("│  ⚠️ 2-5 hovuz alınmadı (son hovuz: %s) → ❌ proqnoz yox%n",
                    pool==null?"0":String.valueOf(pool.length));
            System.out.println("└────────"); return;
        }
        System.out.println("│  📜 Hovuz (" + pool.length + " əkiz oyun):");
        for (int r : pool)
            System.out.printf("│     • %-34s MS:%s → %s%n", home[r]+" v "+away[r], ftScore(r), bttsStr(btts[r]));
        int[] v = bttsVote(pool);
        int pred = v[0] >= v[1] ? 1 : 0;
        boolean hit = pred == btts[g];
        System.out.printf("│  🔮 PROQNOZ: %s  (KG Var %d / KG Yox %d)  →  %s%n",
                bttsStr(pred), v[0], v[1], hit?"✅ DÜZ":"❌ SƏHV");
        System.out.println("└────────────────────────────────────────────────");
    }

    /* ===================== Köməkçilər ===================== */
    static int[] shuffledValidCols(int row, Random r){
        int[] v = new int[NC]; int n=0;
        for (int c=0;c<NC;c++) if (code[c][row] != 0) v[n++]=c;
        v = Arrays.copyOf(v, n);
        for (int i=n-1;i>0;i--){ int j=r.nextInt(i+1); int t=v[i]; v[i]=v[j]; v[j]=t; }
        return v;
    }
    static int[] without(int[] a, int val){
        int n=0; for (int x:a) if (x!=val) n++;
        if (n==a.length) return a;
        int[] r=new int[n]; int k=0; for (int x:a) if (x!=val) r[k++]=x; return r;
    }
    static int[] intersect(int[] a, int[] b){
        int[] r = new int[Math.min(a.length,b.length)]; int i=0,j=0,k=0;
        while (i<a.length && j<b.length){
            if (a[i]==b[j]){ r[k++]=a[i++]; j++; }
            else if (a[i]<b[j]) i++; else j++;
        }
        return Arrays.copyOf(r,k);
    }
    static boolean contains(int[] a, int v){ for (int x:a) if (x==v) return true; return false; }
    static int[] append(int[] a, int v){ int[] r=Arrays.copyOf(a,a.length+1); r[a.length]=v; return r; }
    static int[] remove(int[] a, int i){ int[] r=new int[a.length-1]; int k=0; for(int j=0;j<a.length;j++) if(j!=i) r[k++]=a[j]; return r; }
    static String key(int[] cols){ int[] s=cols.clone(); Arrays.sort(s); return Arrays.toString(s); }
    static String names(int[] cols){ StringJoiner sj=new StringJoiner(", "); for(int c:cols) sj.add(COLS.get(c).disp()); return sj.toString(); }
    static void printFilterBox(int[] cols){
        System.out.println("┌─ 🧩 FİLTER (" + cols.length + " sütun, sıra ilə) ──────────────");
        for (int c : cols) System.out.println("│   • " + COLS.get(c).disp() + "   [" + COLS.get(c).sql() + "]");
        System.out.println("└──────────────────────────────────");
    }
    static String ftScore(int r){ return ftH[r]<0?"?-?":ftH[r]+"-"+ftA[r]; }
    static int[] pick(int[] pool, int k, Random r){
        int[] p = pool.clone();
        for (int i=p.length-1;i>0;i--){ int j=r.nextInt(i+1); int t=p[i]; p[i]=p[j]; p[j]=t; }
        return Arrays.copyOf(p, k);
    }
    static int[] validRows(){
        int[] v=new int[N]; int n=0;
        for (int r=0;r<N;r++) if (btts[r]>=0) v[n++]=r;
        return Arrays.copyOf(v,n);
    }
    static void head(String t){ System.out.println("════════════════════════════════════════════════════════"); System.out.println("  "+t); System.out.println("════════════════════════════════════════════════════════"); }
    static void sec(String t){ System.out.println("\n════════════════════════════════════════════════════════"); System.out.println("  "+t); System.out.println("════════════════════════════════════════════════════════"); }

    /* ===================== İndeks ===================== */
    static void buildIndex(){
        System.out.println("🔨 İnverted indeks qurulur...");
        index = new ArrayList<>(NC);
        for (int c=0;c<NC;c++) index.add(null);
        // virtual thread-lərlə paralel qur
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int c=0;c<NC;c++){
            final int col=c;
            tasks.add(() -> {
                Map<Integer,List<Integer>> tmp = new HashMap<>();
                for (int r=0;r<N;r++){ int cd=code[col][r]; if (cd==0) continue;
                    tmp.computeIfAbsent(cd,k->new ArrayList<>()).add(r); }
                Map<Integer,int[]> byVal = new HashMap<>();
                for (var e: tmp.entrySet()){
                    int[] arr=e.getValue().stream().mapToInt(Integer::intValue).toArray(); // artan
                    byVal.put(e.getKey(), arr);
                }
                index.set(col, byVal);
                return null;
            });
        }
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()){ ex.invokeAll(tasks); }
        catch (InterruptedException e){ Thread.currentThread().interrupt(); }
        System.out.println("✅ İndeks hazır");
    }

    /* ===================== DB ===================== */
    static void loadDb() throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS)){
            conn.setAutoCommit(false);
            StringBuilder sb=new StringBuilder("SELECT country_league,date_time,home_team,away_team,ht_iy,ft_ms,id");
            for (Col c: COLS) sb.append(",").append(c.sql());
            sb.append(" FROM ").append(TABLE);
            int rc;
            try (Statement st=conn.createStatement(); ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM "+TABLE)){ rs.next(); rc=rs.getInt(1); }
            alloc(rc);
            int r=0;
            try (Statement st=conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
                st.setFetchSize(5000);
                try (ResultSet rs=st.executeQuery(sb.toString())){
                    while (rs.next() && r<rc){
                        league[r]=rs.getString(1); date[r]=rs.getString(2);
                        home[r]=rs.getString(3); away[r]=rs.getString(4);
                        int[] ht=parse(rs.getString(5)), ft=parse(rs.getString(6));
                        htH[r]=ht==null?-1:ht[0]; htA[r]=ht==null?-1:ht[1];
                        ftH[r]=ft==null?-1:ft[0]; ftA[r]=ft==null?-1:ft[1];
                        for (int c=0;c<NC;c++) code[c][r]=parseCode(rs.getString(8+c));
                        btts[r] = (ftH[r]<0)? -1 : (byte)((ftH[r]>0 && ftA[r]>0)?1:0);
                        r++;
                        if (r%100000==0) System.out.println("📥 "+r+" sətir...");
                    }
                }
            }
            N=r;
        }
    }
    static int parseCode(String s){
        if (s==null) return 0; s=s.trim().replace(',','.').replace(" ","");
        if (s.isEmpty()||s.equals("-")) return 0;
        try { double d=Double.parseDouble(s); return d>0? (int)Math.round(d*100):0; }
        catch(Exception e){ return 0; }
    }
    static int[] parse(String s){
        if (s==null) return null; s=s.trim(); if (s.isEmpty()) return null;
        String[] p=s.split("\\s*[-:–xX/]\\s*"); if (p.length!=2) return null;
        try { return new int[]{Integer.parseInt(p[0].trim()),Integer.parseInt(p[1].trim())}; }
        catch(Exception e){ return null; }
    }
    static void alloc(int rows){
        code=new int[NC][rows]; btts=new byte[rows];
        ftH=new int[rows]; ftA=new int[rows]; htH=new int[rows]; htA=new int[rows];
        home=new String[rows]; away=new String[rows]; date=new String[rows]; league=new String[rows];
    }

    /* ===================== SİNTETİK (offline test) ===================== */
    static final String[] TEAMS={"Qarabağ","Neftçi","Sabah","Zirə","Real","Barca","Bayern","PSG","City","Inter","Milan","Ajax","Porto","Roma"};
    static void loadSynthetic(){
        Random r=new Random(SEED); N=SYNTHETIC_ROWS; alloc(N);
        int iBy=col("bts_ft_yes_a"), iBn=col("bts_ft_no_a"),
                iO=col("ft_2_5_over_a"), iU=col("ft_2_5_under_a");
        for (int g=0;g<N;g++){
            int profile=r.nextInt(20);
            // profilə bağlı BTTS meyli
            double pYes = 0.2 + (profile%10)*0.06;
            boolean yes = r.nextDouble() < pYes;
            int hg = yes ? 1+r.nextInt(3) : r.nextInt(2);
            int ag = yes ? 1+r.nextInt(3) : (hg>0?0:1);
            if (yes){ hg=Math.max(1,hg); ag=Math.max(1,ag); } else { if (r.nextBoolean()) ag=0; else hg=0; }
            ftH[g]=hg; ftA[g]=ag; htH[g]=Math.min(hg,r.nextInt(hg+1)); htA[g]=Math.min(ag,r.nextInt(ag+1));
            btts[g]=(byte)((hg>0&&ag>0)?1:0);
            home[g]=TEAMS[r.nextInt(TEAMS.length)]; away[g]=TEAMS[r.nextInt(TEAMS.length)]; date[g]="2020-01-01";
            for (int c=0;c<NC;c++){
                int cd;
                if (c==iBy) cd = (int)Math.round((1.4+(profile%10)*0.18)*100);
                else if (c==iBn) cd = (int)Math.round((1.5+((profile+5)%10)*0.18)*100);
                else cd = (int)Math.round((1.2 + r.nextInt(45)*0.2)*100);   // diskret şum
                if (r.nextDouble()<0.02) cd=0;
                code[c][g]=cd;
            }
        }
    }
    static int col(String sql){ for (int i=0;i<NC;i++) if (COLS.get(i).sql().equals(sql)) return i; return -1; }
}