package analyzer.bet365;

import java.io.FileWriter;
import java.sql.*;
import java.util.*;

/**
 * Bet365RuleSearch — ELMİ filtr/qayda kəşfi (Bet365FilterHunt-un "dürüst" qarşılığı).
 *
 * Niyə random {@link Bet365FilterHunt} aldadırdı:
 *   - çoxlu-yoxlama nəzarəti yox idi → təsadüfən "100%" tapırdı (overfit),
 *   - metrika "tutdu/tutmadı" idi, ROI deyil.
 *
 * Bu sinif bunları düzəldir:
 *   1) Hər qayda bir MƏRC qaydasıdır: "filan seçimi filan şərtlərdə oyna".
 *      Qiymət = real oran (vig artıq içindədir) → müsbət ROI = vig-i keçmək.
 *   2) Feature-lər KVANTİL bucket-lərinə bölünür (16-şərtli dəqiq-uyğunluq yox).
 *   3) Data zamana görə (id) bölünür:
 *        DISCOVERY (köhnə) — qaydalar burada axtarılır,
 *        HOLDOUT   (yeni)  — toxunulmamış; yalnız təsdiq üçün.
 *   4) Discovery-də hər qaydaya t-test → p; sonra Benjamini-Hochberg FDR.
 *   5) Sağ qalanlar HOLDOUT-da yenidən yoxlanır; yalnız orada da N≥min və ROI>0 olanlar.
 *
 * İşlətmə:
 *   java ... Bet365RuleSearch                 # ən yeni 400k sətir
 *   java ... Bet365RuleSearch --full          # bütün data
 *   java ... Bet365RuleSearch --sample 250000
 *   java ... Bet365RuleSearch --pairs --min-support 500 --fdr 0.05
 */
public class Bet365RuleSearch {

    // ----------------- PARAMETRLƏR (arqumentlərlə dəyişdirilə bilər) -----------------
    private int     sample        = 400_000;   // ən yeni N sətir; 0 = tam data
    private boolean full          = false;
    private int     minSupportDisc = 400;
    private int     minSupportHold = 150;
    private double  fdrQ          = 0.05;
    private int     nBins         = 8;
    private boolean usePairs      = false;
    private double  holdoutFrac   = 0.30;

    // ----------------- DATA (xronoloji sıra: köhnə → yeni) -----------------
    private int n;
    private int[] fh, fa, hh, ha;          // qol sayları (-1 = etibarsız)
    private boolean[] ftValid, htValid;
    private final Map<String, double[]> col = new HashMap<>();   // oran sütunu → massiv (NaN = yox)

    // Lazım olan oran sütunları (həm mərc, həm şərt üçün)
    private static final String[] LOAD_COLS = {
            // Şərt (context) feature-ləri üçün lazım olan oranlar
            "ft_1_a", "ft_x_a", "ft_2_a",
            "ft_2_5_over_a", "ft_2_5_under_a", "bts_ft_yes_a",
            // HT/FT (İlk Yarı / Maç Sonu) oranları — AXTARIŞIN HƏDƏFİ
            "ht_ft_11_a", "ht_ft_1x_a", "ht_ft_12_a",
            "ht_ft_x1_a", "ht_ft_xx_a", "ht_ft_x2_a",
            "ht_ft_21_a", "ht_ft_2x_a", "ht_ft_22_a"
    };

    private Connection conn;

    // ============================================================
    // MƏRC TƏRİFLƏRİ
    // ============================================================
    interface WinFn { boolean won(int i); }

    // Etibarlılıq rejimi: hansı skor(lar) lazımdır
    static final int VALID_FT = 0;    // yalnız maç sonu
    static final int VALID_HT = 1;    // yalnız ilk yarı
    static final int VALID_BOTH = 2;  // hər ikisi (HT/FT üçün)

    static class Bet {
        final String name, oddsCol;
        final WinFn win;
        final int validMode;
        Bet(String name, String oddsCol, WinFn win, int validMode) {
            this.name = name; this.oddsCol = oddsCol; this.win = win; this.validMode = validMode;
        }
    }

    /** HT/FT (İlk Yarı / Maç Sonu) — 9 kombinasiya, hər ikisi etibarlı olmalı. */
    private List<Bet> htftBets() {
        List<Bet> b = new ArrayList<>();
        b.add(new Bet("HT/FT 1/1", "ht_ft_11_a", i -> hh[i] >  ha[i] && fh[i] >  fa[i], VALID_BOTH));
        b.add(new Bet("HT/FT 1/X", "ht_ft_1x_a", i -> hh[i] >  ha[i] && fh[i] == fa[i], VALID_BOTH));
        b.add(new Bet("HT/FT 1/2", "ht_ft_12_a", i -> hh[i] >  ha[i] && fh[i] <  fa[i], VALID_BOTH));
        b.add(new Bet("HT/FT X/1", "ht_ft_x1_a", i -> hh[i] == ha[i] && fh[i] >  fa[i], VALID_BOTH));
        b.add(new Bet("HT/FT X/X", "ht_ft_xx_a", i -> hh[i] == ha[i] && fh[i] == fa[i], VALID_BOTH));
        b.add(new Bet("HT/FT X/2", "ht_ft_x2_a", i -> hh[i] == ha[i] && fh[i] <  fa[i], VALID_BOTH));
        b.add(new Bet("HT/FT 2/1", "ht_ft_21_a", i -> hh[i] <  ha[i] && fh[i] >  fa[i], VALID_BOTH));
        b.add(new Bet("HT/FT 2/X", "ht_ft_2x_a", i -> hh[i] <  ha[i] && fh[i] == fa[i], VALID_BOTH));
        b.add(new Bet("HT/FT 2/2", "ht_ft_22_a", i -> hh[i] <  ha[i] && fh[i] <  fa[i], VALID_BOTH));
        return b;
    }

    /** Axtarış YALNIZ HT/FT marketləri üzərindədir. */
    private List<Bet> buildBets() {
        return htftBets();
    }

    // ============================================================
    // ŞƏRT FEATURE-LƏRİ
    // ============================================================
    private static final String[] CONTEXT_FEATURES = {
            "fav_oran", "ev_oran", "qonaq_oran", "ust25_oran",
            "alt25_oran", "kg_var_oran", "overround", "favoritlik"
    };

    private static String featLabel(String f) {
        switch (f) {
            case "fav_oran":    return "Favorit oranı";
            case "ev_oran":     return "Ev oranı (MS1)";
            case "qonaq_oran":  return "Qonaq oranı (MS2)";
            case "ust25_oran":  return "Üst 2.5 oranı";
            case "alt25_oran":  return "Alt 2.5 oranı";
            case "kg_var_oran": return "KG Var oranı";
            case "overround":   return "Bazar marjası";
            case "favoritlik":  return "Favoritlik (2/1)";
            case "öz_oranı":    return "Seçimin öz oranı";
            default:            return f;
        }
    }

    private Map<String, double[]> buildFeatures() {
        double[] h = col.get("ft_1_a"), x = col.get("ft_x_a"), a = col.get("ft_2_a");
        double[] ouO = col.get("ft_2_5_over_a"), ouU = col.get("ft_2_5_under_a");
        double[] kg = col.get("bts_ft_yes_a");

        double[] fav = new double[n], over = new double[n], favlik = new double[n];
        for (int i = 0; i < n; i++) {
            double hv = h[i], xv = x[i], av = a[i];
            fav[i] = min3(hv, xv, av);
            over[i] = (valid(hv) && valid(xv) && valid(av)) ? (1.0 / hv + 1.0 / xv + 1.0 / av) : Double.NaN;
            favlik[i] = (valid(hv) && valid(av)) ? (av / hv) : Double.NaN;
        }
        Map<String, double[]> f = new HashMap<>();
        f.put("fav_oran", fav);
        f.put("ev_oran", h);
        f.put("qonaq_oran", a);
        f.put("ust25_oran", ouO);
        f.put("alt25_oran", ouU);
        f.put("kg_var_oran", kg);
        f.put("overround", over);
        f.put("favoritlik", favlik);
        return f;
    }

    private static boolean valid(double d) { return !Double.isNaN(d) && d > 0; }
    private static double min3(double a, double b, double c) {
        double m = Double.NaN;
        for (double v : new double[]{a, b, c}) if (valid(v) && (Double.isNaN(m) || v < m)) m = v;
        return m;
    }

    // ============================================================
    // QAYDA modeli
    // ============================================================
    static class Cond {
        final String feat;      // "öz_oranı" və ya kontekst feature adı
        final double[] edges;   // bin sərhədləri
        final int bin;
        Cond(String feat, double[] edges, int bin) { this.feat = feat; this.edges = edges; this.bin = bin; }
        String label() {
            double lo = edges[bin];
            double hi = (bin + 1 < edges.length) ? edges[bin + 1] : edges[edges.length - 1];
            return String.format("%s∈[%.2f, %.2f)", featLabel(feat), lo, hi);
        }
    }

    static class Rule {
        String betName;
        double baseRoi;
        List<Cond> conds = new ArrayList<>();
        int nDisc; double roiDisc, tDisc, pDisc;
        int nHold; double roiHold, pHold;
    }

    // ============================================================
    // ƏSAS AXTARIŞ
    // ============================================================
    public void run() throws Exception {
        long t0 = System.currentTimeMillis();
        loadData();

        int split = (int) (n * (1 - holdoutFrac));
        System.out.printf("🔎 DISCOVERY: %,d maç (köhnə)  |  HOLDOUT: %,d maç (yeni, toxunulmamış)%n",
                split, n - split);

        Map<String, double[]> feats = buildFeatures();

        // Kontekst feature-lərini binlə (sərhədlər YALNIZ discovery-dən)
        Map<String, int[]> binIdx = new HashMap<>();
        Map<String, double[]> edgesMap = new HashMap<>();
        for (String f : CONTEXT_FEATURES) {
            double[] edges = quantileEdges(feats.get(f), 0, split, nBins);
            if (edges == null) continue;
            binIdx.put(f, digitizeAll(feats.get(f), edges));
            edgesMap.put(f, edges);
        }

        List<Bet> bets = buildBets();

        // Hər mərc üçün profit vektoru + öz oranını da binlə
        Map<String, double[]> betProfit = new HashMap<>();
        Map<String, int[]> betOwnBin = new HashMap<>();
        Map<String, double[]> betOwnEdges = new HashMap<>();
        for (Bet bet : bets) {
            double[] odds = col.get(bet.oddsCol);
            double[] profit = new double[n];
            Arrays.fill(profit, Double.NaN);
            for (int i = 0; i < n; i++) {
                boolean ok = bet.validMode == VALID_BOTH ? (htValid[i] && ftValid[i])
                        : bet.validMode == VALID_HT ? htValid[i] : ftValid[i];
                if (ok && valid(odds[i]))
                    profit[i] = bet.win.won(i) ? (odds[i] - 1.0) : -1.0;
            }
            betProfit.put(bet.name, profit);
            double[] edges = quantileEdges(odds, 0, split, nBins);
            if (edges != null) {
                betOwnBin.put(bet.name, digitizeAll(odds, edges));
                betOwnEdges.put(bet.name, edges);
            }
        }

        // ---- Qaydaları sayla ----
        List<Rule> rules = new ArrayList<>();
        for (Bet bet : bets) {
            double[] profit = betProfit.get(bet.name);
            double[] base = stats(profit, null, -1, 0, split);  // bütün discovery (baza ROI)
            double baseRoi = base[1];

            // Şərt namizədləri: öz oranı + kontekst
            List<String> condFeats = new ArrayList<>();
            List<int[]> condBins = new ArrayList<>();
            List<double[]> condEdges = new ArrayList<>();
            if (betOwnBin.containsKey(bet.name)) {
                condFeats.add("öz_oranı");
                condBins.add(betOwnBin.get(bet.name));
                condEdges.add(betOwnEdges.get(bet.name));
            }
            for (String f : CONTEXT_FEATURES) {
                if (!binIdx.containsKey(f)) continue;
                condFeats.add(f);
                condBins.add(binIdx.get(f));
                condEdges.add(edgesMap.get(f));
            }

            // --- 1 şərtli qaydalar ---
            for (int c = 0; c < condFeats.size(); c++) {
                int[] bins = condBins.get(c);
                double[] edges = condEdges.get(c);
                int nb = edges.length - 1;
                for (int b = 0; b < nb; b++) {
                    double[] s = stats(profit, bins, b, 0, split);
                    int cnt = (int) s[0];
                    if (cnt >= minSupportDisc && s[1] > 0 && Double.isFinite(s[3])) {
                        Rule r = new Rule();
                        r.betName = bet.name; r.baseRoi = baseRoi;
                        r.conds.add(new Cond(condFeats.get(c), edges, b));
                        r.nDisc = cnt; r.roiDisc = s[1]; r.tDisc = s[2]; r.pDisc = s[3];
                        rules.add(r);
                    }
                }
            }

            // --- 2 şərtli (öz oranı × kontekst) ---
            if (usePairs && betOwnBin.containsKey(bet.name)) {
                int[] ownBins = betOwnBin.get(bet.name);
                double[] ownEdges = betOwnEdges.get(bet.name);
                int nbOwn = ownEdges.length - 1;
                for (String f : CONTEXT_FEATURES) {
                    if (!binIdx.containsKey(f)) continue;
                    int[] fbins = binIdx.get(f);
                    double[] fedges = edgesMap.get(f);
                    int nbF = fedges.length - 1;
                    for (int bo = 0; bo < nbOwn; bo++) {
                        for (int bff = 0; bff < nbF; bff++) {
                            double[] s = stats2(profit, ownBins, bo, fbins, bff, 0, split);
                            int cnt = (int) s[0];
                            if (cnt >= minSupportDisc && s[1] > 0 && Double.isFinite(s[3])) {
                                Rule r = new Rule();
                                r.betName = bet.name; r.baseRoi = baseRoi;
                                r.conds.add(new Cond("öz_oranı", ownEdges, bo));
                                r.conds.add(new Cond(f, fedges, bff));
                                r.nDisc = cnt; r.roiDisc = s[1]; r.tDisc = s[2]; r.pDisc = s[3];
                                rules.add(r);
                            }
                        }
                    }
                }
            }
        }

        System.out.printf("🧪 Yoxlanan qayda (discovery, ROI>0 & N≥%d): %,d%n", minSupportDisc, rules.size());
        if (rules.isEmpty()) {
            System.out.println("❌ Şərtləri ödəyən qayda yoxdur. --min-support azalt və ya --pairs əlavə et.");
            return;
        }

        // ---- Benjamini-Hochberg FDR ----
        boolean[] keep = bhFdr(rules, fdrQ);
        List<Rule> survivors = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) if (keep[i]) survivors.add(rules.get(i));
        System.out.printf("✅ FDR (q=%.2f) keçən qayda: %,d%n", fdrQ, survivors.size());
        if (survivors.isEmpty()) {
            System.out.println("⚠️  Çoxlu-yoxlama düzəlişindən sonra heç bir qayda sağ qalmadı.");
            System.out.println("    → Bu, EHTİMAL Kİ DÜRÜST nəticədir: təsadüfi 'qızıl filtr' yox idi.");
            printRejectedCandidates(rules);
            return;
        }

        // ---- HOLDOUT təsdiqi ----
        List<Rule> confirmed = new ArrayList<>();
        for (Rule r : survivors) {
            double[] profit = betProfit.get(r.betName);
            double[] s = statsRule(profit, r, betOwnBin, binIdx, split, n);
            r.nHold = (int) s[0]; r.roiHold = s[1]; r.pHold = s[3];
            if (r.nHold >= minSupportHold && r.roiHold > 0) confirmed.add(r);
        }
        confirmed.sort((p, q) -> Double.compare(q.roiHold, p.roiHold));

        System.out.printf("🏁 HOLDOUT-da da müsbət (N≥%d): %,d%n", minSupportHold, confirmed.size());
        System.out.println("=".repeat(92));
        if (confirmed.isEmpty()) {
            System.out.println("⚠️  Discovery-də güclü görünən qaydalar HOLDOUT-da çökdü → real edge yox (overfit).");
            System.out.println("    Bu, niyə random filtrin işləmədiyinin sübutudur.");
            return;
        }

        System.out.println("💎 TƏSDİQLƏNMİŞ QAYDALAR (həm FDR, həm holdout keçən) — ROI holdout-a görə:");
        System.out.println("-".repeat(92));
        int show = Math.min(confirmed.size(), 30);
        for (int i = 0; i < show; i++) {
            Rule r = confirmed.get(i);
            StringBuilder cs = new StringBuilder();
            for (int j = 0; j < r.conds.size(); j++) {
                if (j > 0) cs.append("  VƏ  ");
                cs.append(r.conds.get(j).label());
            }
            System.out.printf("%2d. 🎯 %s%n", i + 1, r.betName);
            System.out.println("     Şərt: " + cs);
            System.out.printf("     DISCOVERY: N=%,7d  ROI=%+6.2f%%  (baza %+.2f%%)  p=%.2e%n",
                    r.nDisc, r.roiDisc * 100, r.baseRoi * 100, r.pDisc);
            System.out.printf("     HOLDOUT  : N=%,7d  ROI=%+6.2f%%  ✅%n", r.nHold, r.roiHold * 100);
        }
        System.out.println("=".repeat(92));
        System.out.printf("⏱  %.1fs | data: %s%n", (System.currentTimeMillis() - t0) / 1000.0,
                full ? "TAM data" : "ən yeni " + sample + " sətir");

        saveCsv(confirmed);
    }

    // ============================================================
    // STATİSTİKA KÖMƏKÇİLƏRİ
    // ============================================================
    /** Bir bin maskası üçün {n, roi, t, p}. binIdx==null → bütün aralıq. */
    private double[] stats(double[] profit, int[] binIdx, int targetBin, int from, int to) {
        double sum = 0, sumsq = 0; int cnt = 0;
        for (int i = from; i < to; i++) {
            if (binIdx != null && binIdx[i] != targetBin) continue;
            double p = profit[i];
            if (Double.isNaN(p)) continue;
            sum += p; sumsq += p * p; cnt++;
        }
        return finishStats(sum, sumsq, cnt);
    }

    /** İki şərtli maska üçün statistika. */
    private double[] stats2(double[] profit, int[] binA, int ba, int[] binB, int bb, int from, int to) {
        double sum = 0, sumsq = 0; int cnt = 0;
        for (int i = from; i < to; i++) {
            if (binA[i] != ba || binB[i] != bb) continue;
            double p = profit[i];
            if (Double.isNaN(p)) continue;
            sum += p; sumsq += p * p; cnt++;
        }
        return finishStats(sum, sumsq, cnt);
    }

    /** Tam qayda (1-2 şərt) maskası — holdout aralığında. */
    private double[] statsRule(double[] profit, Rule r, Map<String, int[]> betOwnBin,
                               Map<String, int[]> binIdx, int from, int to) {
        double sum = 0, sumsq = 0; int cnt = 0;
        for (int i = from; i < to; i++) {
            boolean ok = true;
            for (Cond c : r.conds) {
                int[] bins = c.feat.equals("öz_oranı") ? betOwnBin.get(r.betName) : binIdx.get(c.feat);
                if (bins == null || bins[i] != c.bin) { ok = false; break; }
            }
            if (!ok) continue;
            double p = profit[i];
            if (Double.isNaN(p)) continue;
            sum += p; sumsq += p * p; cnt++;
        }
        return finishStats(sum, sumsq, cnt);
    }

    private double[] finishStats(double sum, double sumsq, int cnt) {
        if (cnt < 2) return new double[]{cnt, Double.NaN, Double.NaN, Double.NaN};
        double roi = sum / cnt;
        double var = (sumsq - sum * sum / cnt) / (cnt - 1);
        if (var <= 0) return new double[]{cnt, roi, roi > 0 ? 1e9 : -1e9, roi > 0 ? 0.0 : 1.0};
        double t = roi / Math.sqrt(var / cnt);
        double p = normSf(t);   // bir tərəfli ROI>0 (N böyük → t≈z)
        return new double[]{cnt, roi, t, p};
    }

    /** FDR-dən heç nə keçməyəndə ən güclü namizədləri (REDD EDİLMİŞ) göstər — şəffaflıq üçün. */
    private void printRejectedCandidates(List<Rule> rules) {
        rules.sort((p, q) -> Double.compare(q.roiDisc, p.roiDisc));
        int show = Math.min(rules.size(), 10);
        System.out.println("\n🔍 Ən güclü görünən namizədlər (REDD EDİLDİ — statistik təsdiq yoxdur):");
        System.out.println("-".repeat(92));
        for (int i = 0; i < show; i++) {
            Rule r = rules.get(i);
            StringBuilder cs = new StringBuilder();
            for (int j = 0; j < r.conds.size(); j++) {
                if (j > 0) cs.append("  VƏ  ");
                cs.append(r.conds.get(j).label());
            }
            System.out.printf("  • %s | %s%n", r.betName, cs);
            System.out.printf("      DISCOVERY ROI=%+.2f%% (N=%,d, p=%.2e) → təsadüf, gələcəyə keçmir%n",
                    r.roiDisc * 100, r.nDisc, r.pDisc);
        }
        System.out.println("-".repeat(92));
        System.out.println("ℹ️  Köhnə random hunter məhz belə qaydaları 'qızıl filtr' sanırdı. FDR onları rədd edir.");
    }

    /** Benjamini-Hochberg: q-FDR səviyyəsində qəbul olunan qaydalar (bool maska). */
    private boolean[] bhFdr(List<Rule> rules, double q) {
        int m = rules.size();
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> rules.get(i).pDisc));
        double cutoff = -1;
        for (int k = 0; k < m; k++) {
            double thresh = q * (k + 1) / m;
            if (rules.get(order[k]).pDisc <= thresh) cutoff = rules.get(order[k]).pDisc;
        }
        boolean[] keep = new boolean[m];
        if (cutoff < 0) return keep;
        for (int i = 0; i < m; i++) keep[i] = rules.get(i).pDisc <= cutoff;
        return keep;
    }

    /** Normal sağ-quyruq (survival) funksiyası: P(Z > z). */
    private static double normSf(double z) { return 0.5 * erfc(z / Math.sqrt(2.0)); }

    /** erfc — Numerical Recipes yaxınlaşması (yüksək dəqiqlik). */
    private static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 +
                t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 +
                t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0.0 ? ans : 2.0 - ans;
    }

    // ============================================================
    // BIN (kvantil) köməkçiləri
    // ============================================================
    /** Discovery aralığından [from,to) kvantil sərhədləri (NaN-lar atılır). */
    private double[] quantileEdges(double[] vals, int from, int to, int q) {
        double[] tmp = new double[to - from];
        int k = 0;
        for (int i = from; i < to; i++) if (!Double.isNaN(vals[i])) tmp[k++] = vals[i];
        if (k < q) return null;
        double[] v = Arrays.copyOf(tmp, k);
        Arrays.sort(v);
        double[] raw = new double[q + 1];
        for (int i = 0; i <= q; i++) {
            double pos = (double) i / q * (k - 1);
            int lo = (int) Math.floor(pos);
            int hi = (int) Math.ceil(pos);
            raw[i] = v[lo] + (v[hi] - v[lo]) * (pos - lo);
        }
        // Təkrarları at
        double[] uniq = new double[q + 1];
        int u = 0;
        for (double e : raw) if (u == 0 || e > uniq[u - 1]) uniq[u++] = e;
        return u >= 2 ? Arrays.copyOf(uniq, u) : null;
    }

    /** Bütün massivi bin indeksinə çevir; NaN → -1. (numpy digitize, right=False) */
    private int[] digitizeAll(double[] vals, double[] edges) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(vals[i])) { idx[i] = -1; continue; }
            int b = 0;
            for (int e = 1; e < edges.length - 1; e++) if (vals[i] >= edges[e]) b++;
            idx[i] = b;
        }
        return idx;
    }

    // ============================================================
    // DATA YÜKLƏMƏ
    // ============================================================
    private void loadData() throws SQLException {
        System.out.println("📥 Data yüklənir ...");
        StringBuilder sql = new StringBuilder("SELECT ht_iy, ft_ms");
        for (String c : LOAD_COLS) sql.append(",").append(c);
        sql.append(" FROM bet365_matches ");
        if (full) sql.append("ORDER BY id ASC");
        else      sql.append("ORDER BY id DESC LIMIT ").append(sample);

        List<int[]> scores = new ArrayList<>();          // {fh,fa,hh,ha}
        List<double[]> rows = new ArrayList<>();          // LOAD_COLS dəyərləri

        try (Statement st = conn.createStatement()) {
            st.setFetchSize(2000);
            conn.setAutoCommit(false);
            try (ResultSet rs = st.executeQuery(sql.toString())) {
                while (rs.next()) {
                    int[] sc = parseScores(rs.getString("ht_iy"), rs.getString("ft_ms"));
                    double[] od = new double[LOAD_COLS.length];
                    for (int c = 0; c < LOAD_COLS.length; c++) od[c] = parseOdd(rs.getString(LOAD_COLS[c]));
                    scores.add(sc);
                    rows.add(od);
                }
            }
        }
        // Recent rejimində DESC yüklədik → köhnə→yeni üçün tərsinə çevir
        if (!full) { Collections.reverse(scores); Collections.reverse(rows); }

        n = scores.size();
        fh = new int[n]; fa = new int[n]; hh = new int[n]; ha = new int[n];
        ftValid = new boolean[n]; htValid = new boolean[n];
        for (String c : LOAD_COLS) col.put(c, new double[n]);

        for (int i = 0; i < n; i++) {
            int[] sc = scores.get(i);
            hh[i] = sc[2]; ha[i] = sc[3]; fh[i] = sc[0]; fa[i] = sc[1];
            ftValid[i] = fh[i] >= 0 && fa[i] >= 0;
            htValid[i] = hh[i] >= 0 && ha[i] >= 0;
            double[] od = rows.get(i);
            for (int c = 0; c < LOAD_COLS.length; c++) col.get(LOAD_COLS[c])[i] = od[c];
        }
        System.out.printf("✅ %,d sətir yükləndi (%s).%n", n, full ? "TAM" : "ən yeni " + sample);
    }

    /** "2-1" / "0-0" → {fh,fa,hh,ha}, etibarsız → -1. */
    private static int[] parseScores(String ht, String ft) {
        int[] r = {-1, -1, -1, -1};
        int[] f = splitScore(ft);
        int[] h = splitScore(ht);
        r[0] = f[0]; r[1] = f[1]; r[2] = h[0]; r[3] = h[1];
        return r;
    }

    private static int[] splitScore(String s) {
        if (s != null && s.contains("-")) {
            String[] p = s.split("-", 2);
            try { return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())}; }
            catch (Exception ignored) {}
        }
        return new int[]{-1, -1};
    }

    private static double parseOdd(String raw) {
        if (raw == null) return Double.NaN;
        String s = raw.trim().replace("yan.", "").replace(",", ".").replace(" ", "").replace("'", "");
        if (s.isEmpty() || s.equals("-")) return Double.NaN;
        try { double d = Double.parseDouble(s); return d > 0 ? d : Double.NaN; }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    // ============================================================
    // CSV
    // ============================================================
    private void saveCsv(List<Rule> rules) {
        try (FileWriter w = new FileWriter("discovered_rules.csv")) {
            w.write("bet,conditions,n_disc,roi_disc,n_hold,roi_hold,p_disc,p_hold,base_roi\n");
            for (Rule r : rules) {
                StringBuilder cs = new StringBuilder();
                for (int j = 0; j < r.conds.size(); j++) {
                    if (j > 0) cs.append(" AND ");
                    cs.append(r.conds.get(j).label());
                }
                w.write(String.format(Locale.US, "\"%s\",\"%s\",%d,%.5f,%d,%.5f,%.3e,%.3e,%.5f%n",
                        r.betName, cs, r.nDisc, r.roiDisc, r.nHold, r.roiHold, r.pDisc, r.pHold, r.baseRoi));
            }
            System.out.println("💾 Saxlanıldı: discovered_rules.csv");
        } catch (Exception e) {
            System.err.println("CSV yazıla bilmədi: " + e.getMessage());
        }
    }

    // ============================================================
    // Constructor + main
    // ============================================================
    public Bet365RuleSearch(String[] args) {
        parseArgs(args);
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/postgres", "postgres", "fuad123");
            System.out.println("✅ Veritabanına bağlanıldı.");
        } catch (Exception e) {
            System.err.println("❌ " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--full":        full = true; break;
                case "--pairs":       usePairs = true; break;
                case "--sample":      sample = Integer.parseInt(args[++i]); break;
                case "--min-support": minSupportDisc = Integer.parseInt(args[++i]); break;
                case "--min-support-hold": minSupportHold = Integer.parseInt(args[++i]); break;
                case "--fdr":         fdrQ = Double.parseDouble(args[++i]); break;
                case "--bins":        nBins = Integer.parseInt(args[++i]); break;
                default: System.out.println("⚠️  Naməlum arqument: " + args[i]);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(92));
        System.out.println("🤖 ELMİ FİLTR/QAYDA KƏŞFİ — YALNIZ HT/FT (Java) — ROI + zaman holdout + FDR");
        System.out.println("=".repeat(92));
        new Bet365RuleSearch(args).run();
    }
}
