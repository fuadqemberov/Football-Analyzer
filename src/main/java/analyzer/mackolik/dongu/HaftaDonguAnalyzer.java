package analyzer.mackolik.dongu;

import analyzer.util.TeamIdsFetcher;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * HAFTA DÖNGÜ ANALİZÖRÜ (yıllık + haftalık döngü, döngü uzunluğu DİNAMİK)
 *
 * Fikir (Kopenhag örneği, açılış maçına ÖZEL DEĞİL — her hafta için geçerli):
 *   Bir takımın belirli bir HAFTASI (maç günü / matchday), sabit bir YIL döngüsünde
 *   aynı HT/FT sürprizini tekrar edebiliyor. Döngü iki boyutlu:
 *     - HAFTALIK: aynı hafta numarası (1., 2., 3. ... hafta)
 *     - YILLIK  : eşit yıl aralığıyla (P yıl); P sabit 7 DEĞİL, keşfedilir.
 *   Kural: aradaki fark her adımda AYNI olmalı (year-P, year-2P, year-3P ...).
 *   7 yalnızca Kopenhag için keşfedilen değerdir; başka takımda 4, 5, 9 ... olabilir.
 *     2012/2013  Kopenhag 4-2 Midtjylland  (İY 0-1) → 2/1  | 6 gol
 *     2019/2020  Odense   2-3 Kopenhag      (İY 2-1) → 1/2  | 5 gol   (fark = 7, sabit)
 *     2026/2027  Kopenhag vs Lyngby         → ⁉️ tahmin: FULL COMEBACK
 *
 * SÜRPRİZ / COMEBACK TANIMI (TemasTakimiAnalyzer ile aynı 2 grup):
 *   İlk yarıyı önde kapatan takım maçı KAZANAMAZ:
 *     - FULL COMEBACK      : HT/FT = 1/2 veya 2/1 (önde olan kaybeder)
 *     - BERABERLİK COMEBACK: HT/FT = 1/X veya 2/X (önde olan yakalanır)
 *
 * Akış:
 *   1. Günün başlamamış maçlarındaki takım ID'leri alınır (veya argümanla verilir).
 *   2. Her takım için güncel sezonda BUGÜNKÜ maç (ilk oynanmamış fikstür) bulunur → hedef hafta.
 *   3. Olası her döngü uzunluğu P denenir ({@value #MIN_CYCLE_YEARS}..LOOKBACK/{@value #MIN_CYCLE}):
 *      year-P, year-2P ... hepsi son {@value #LOOKBACK_YEARS} yıl içinde ve AYNI hafta.
 *   4. Bir P, eğer bütün adımları (boşluksuz) comeback ise ve en az {@value #MIN_CYCLE} adım
 *      içeriyorsa SİNYAL olur. Küçük P'nin katı olan (kapsanan) P'ler elenir.
 *
 * Kullanım:
 *   java ... HaftaDonguAnalyzer                 → günün başlamamış maçlarındaki takımlar
 *   java ... HaftaDonguAnalyzer 5088 2029       → yalnız verilen takım ID'leri
 *   java ... HaftaDonguAnalyzer --all 5088      → boşluklu/eksik döngüleri de yazdır
 */
public class HaftaDonguAnalyzer {

    // ─── AYARLAR ────────────────────────────────────────────────────────────
    private static final String BASE_URL = "https://arsiv.mackolik.com/Team/Default.aspx?id=%d&season=%s";

    /** Yeni futbol sezonunun başladığı ay (Temmuz). */
    private static final int SEASON_START_MONTH = 7;

    /** Güncel sezonun başlangıç yılı sistem tarihinden dinamik hesaplanır (ör. Temmuz 2026 → 2026). */
    private static final int CURRENT_SEASON_START_YEAR = computeCurrentSeasonStartYear();
    /** Güncel sezon "yyyy/yyyy" (ör. "2026/2027"). */
    private static final String CURRENT_SEASON = CURRENT_SEASON_START_YEAR + "/" + (CURRENT_SEASON_START_YEAR + 1);

    /** Kaç yıl geriye bakılır. */
    private static final int LOOKBACK_YEARS = 20;
    /** Denenecek en küçük döngü uzunluğu (yıl). 1 = ardışık sezonlar; 2 = gerçek "döngü". */
    private static final int MIN_CYCLE_YEARS = 2;
    /** Bir döngünün sinyal sayılması için gereken en az adım (geçmiş maç) sayısı. */
    private static final int MIN_CYCLE = 2;
    /** Denenecek en büyük döngü uzunluğu: en az MIN_CYCLE adım sığabilmeli. */
    private static final int MAX_CYCLE_YEARS = LOOKBACK_YEARS / MIN_CYCLE;
    private static final int NUM_THREADS = 10;

    private static int computeCurrentSeasonStartYear() {
        LocalDate now = LocalDate.now();
        return now.getMonthValue() >= SEASON_START_MONTH ? now.getYear() : now.getYear() - 1;
    }

    // ─── COMEBACK GRUPLARI ──────────────────────────────────────────────────
    static final String FULL_COMEBACK       = "FULL COMEBACK";
    static final String BERABERLIK_COMEBACK = "BERABERLİK COMEBACK";

    // ─── MAIN ───────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        boolean showAll = false;
        List<String> teamIds = new ArrayList<>();
        if (args != null) {
            for (String arg : args) {
                if ("--all".equalsIgnoreCase(arg.trim())) showAll = true;
                else teamIds.add(arg);
            }
        }

        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  HAFTA DÖNGÜ ANALİZÖRÜ (döngü uzunluğu dinamik)       ║");
        System.out.println("║  1/2, 2/1 → FULL COMEBACK                             ║");
        System.out.println("║  1/X, 2/X → BERABERLİK COMEBACK                       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");

        long globalStart = System.currentTimeMillis();

        System.out.printf("📅 Güncel sezon: %s | Döngü uzunluğu: %d..%d yıl (dinamik) | Taranan: son %d yıl%n%n",
                CURRENT_SEASON, MIN_CYCLE_YEARS, MAX_CYCLE_YEARS, LOOKBACK_YEARS);

        if (teamIds.isEmpty()) {
            System.out.println("🔄 Günün başlamamış maçlarından takım ID'leri alınıyor...");
            teamIds = TeamIdsFetcher.fetchUnstartedTeamIds();
            System.out.println("✅ " + teamIds.size() + " takım bulundu\n");
        }

        if (teamIds.isEmpty()) {
            System.out.println("❌ Analiz edilecek takım yok, program sonlandırılıyor.");
            return;
        }

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(NUM_THREADS + 5);
        cm.setDefaultMaxPerRoute(NUM_THREADS);
        CloseableHttpClient http = HttpClients.custom().setConnectionManager(cm).build();

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<String>> futures = new ArrayList<>();
        for (String idStr : teamIds) {
            try {
                int teamId = Integer.parseInt(idStr.trim());
                boolean all = showAll;
                futures.add(executor.submit((Callable<String>) () -> {
                    try {
                        return analyzeTeam(http, teamId, all);
                    } catch (Exception e) {
                        System.err.println("   ❌ [ID:" + teamId + "] Hata: " + e.getMessage());
                        return null;
                    }
                }));
            } catch (NumberFormatException e) {
                System.err.println("   ❌ Geçersiz takım ID: " + idStr);
            }
        }

        int processed = 0, signalCount = 0;
        for (Future<String> f : futures) {
            try {
                String result = f.get(5, TimeUnit.MINUTES);
                processed++;
                if (result != null && !result.isEmpty()) {
                    signalCount++;
                    System.out.println(result);
                }
                System.out.printf("\r⏳ İlerleme: %d/%d  |  Sinyal: %d", processed, futures.size(), signalCount);
            } catch (Exception e) {
                processed++;
                System.err.println("\n   ❌ Görev hatası: " + e.getMessage());
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            http.close();
        } catch (IOException ignored) {
        }

        long sure = (System.currentTimeMillis() - globalStart) / 1000;
        System.out.println("\n\n════════════════════════════════════════════════════════");
        System.out.printf("✅ TAMAMLANDI | %d takım | %d hafta-döngü sinyali | %ds%n", processed, signalCount, sure);
        System.out.println("════════════════════════════════════════════════════════\n");

        System.exit(0);
    }

    // ─── ANA ANALİZ ─────────────────────────────────────────────────────────
    static String analyzeTeam(CloseableHttpClient http, int teamId, boolean showAll) throws IOException {

        // 1. Güncel sezon + bugünkü maç (ilk oynanmamış fikstür) → hedef hafta
        List<MacData> current = fetchSeasonMatches(http, teamId, CURRENT_SEASON);
        if (current.isEmpty()) return null;

        String teamName = detectTeamNameFromRows(current);
        if (teamName == null) return null;

        int weekIdx = -1;
        for (int i = 0; i < current.size(); i++) {
            if (!current.get(i).played) {
                weekIdx = i;
                break;
            }
        }
        if (weekIdx < 0) return null;          // sezon bitmiş, hedef hafta yok
        int weekNo = weekIdx + 1;
        MacData target = current.get(weekIdx);

        // 2. Her döngü uzunluğu P için aynı haftayı eşit aralıkla geriye tara.
        //    Geçmiş sezonları yıl-bazlı önbelleğe alarak tekrar indirme yapmıyoruz.
        Map<Integer, List<MacData>> cache = new HashMap<>();
        List<Cycle> adaylar = new ArrayList<>();

        for (int p = MIN_CYCLE_YEARS; p <= MAX_CYCLE_YEARS; p++) {
            List<CycleHit> pts = new ArrayList<>();
            int expected = 0, missing = 0, comebacks = 0;

            for (int k = 1; k * p <= LOOKBACK_YEARS; k++) {
                expected++;
                CycleHit h = hitAt(http, teamId, k * p, weekIdx, cache);
                if (h == null) { missing++; continue; }
                pts.add(h);
                if (h.grup != null) comebacks++;
            }

            boolean complete = missing == 0;
            boolean allCome  = !pts.isEmpty() && comebacks == pts.size();
            boolean sinyal   = complete && allCome && pts.size() >= MIN_CYCLE;
            boolean gosterile = pts.size() >= MIN_CYCLE && comebacks >= MIN_CYCLE;  // --all için

            if (sinyal || (showAll && gosterile)) {
                adaylar.add(new Cycle(p, pts, expected, missing, complete, comebacks, sinyal));
            }
        }

        if (adaylar.isEmpty()) return null;

        // 3. Küçük P'nin katı olan (sezon kümesi kapsanan) döngüleri ele: P%Q==0 ise gereksiz.
        adaylar.sort(Comparator.comparingInt(c -> c.period));
        List<Cycle> secilen = new ArrayList<>();
        for (Cycle c : adaylar) {
            boolean kapsandi = false;
            for (Cycle q : secilen) {
                if (c.period % q.period == 0) { kapsandi = true; break; }
            }
            if (!kapsandi) secilen.add(c);
        }

        // Görsel sıralama: kanıtı çok (adım sayısı) ve küçük döngü önce.
        secilen.sort(Comparator
                .comparingInt((Cycle c) -> c.comebacks).reversed()
                .thenComparingInt(c -> c.period));

        StringBuilder out = new StringBuilder();
        for (Cycle c : secilen) {
            out.append(buildReport(teamName, teamId, weekNo, target, c));
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** yearsBack yıl önceki sezonda, weekIdx'teki (aynı hafta) maçın döngü kaydı; veri yoksa null. */
    private static CycleHit hitAt(CloseableHttpClient http, int teamId, int yearsBack,
                                  int weekIdx, Map<Integer, List<MacData>> cache) {
        List<MacData> matches = cache.get(yearsBack);
        if (matches == null) {
            int year = CURRENT_SEASON_START_YEAR - yearsBack;
            String season = year + "/" + (year + 1);
            try {
                matches = fetchSeasonMatches(http, teamId, season);
            } catch (IOException e) {
                matches = new ArrayList<>();
            }
            cache.put(yearsBack, matches);
        }
        if (weekIdx >= matches.size()) return null;

        MacData m = matches.get(weekIdx);
        if (!m.played || m.htScore == null) return null;   // İY olmadan HT/FT hesaplanamaz

        int year = CURRENT_SEASON_START_YEAR - yearsBack;
        String season = year + "/" + (year + 1);
        String htFt = htFtCode(m.htScore, m.ftScore);
        return new CycleHit(season, m, htFt, comebackGrubu(htFt), toplamGol(m.ftScore));
    }

    // ─── RAPOR ──────────────────────────────────────────────────────────────
    private static String buildReport(String teamName, int teamId, int weekNo, MacData target, Cycle c) {

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n╔════════════════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║ 🎯 HAFTA-DÖNGÜ SİNYALİ: %s  [ID:%d]%n", teamName, teamId));
        sb.append(String.format("║    Hedef: %d. hafta | Keşfedilen döngü: her %d yılda bir | Taranan: son %d yıl%n",
                weekNo, c.period, LOOKBACK_YEARS));
        sb.append("╠════════════════════════════════════════════════════════════════════╣\n");

        for (CycleHit a : c.hits) {
            String etiket = a.grup != null ? a.grup : "sürpriz yok";
            sb.append(String.format("║  %-9s (%d.h) %s %s %s  (İY %s) → %-4s | %d gol | %s%n",
                    a.season, weekNo, a.match.homeTeam, a.match.ftScore, a.match.awayTeam,
                    a.match.htScore, a.htFt, a.toplamGol, etiket));
        }

        sb.append("║  ──────────────────────────────────────────────────────────────────\n");

        String tahmin = ortakTahmin(c.hits);
        String durum = target.played
                ? "oynandı: " + target.ftScore + (target.htScore != null ? " (İY " + target.htScore + ")" : "")
                : (target.time != null ? target.time : "henüz oynanmadı");
        sb.append(String.format("║  %-9s (%d.h) %s vs %s  (%s)%n",
                CURRENT_SEASON, weekNo, target.homeTeam, target.awayTeam, durum));
        sb.append(String.format("║  ⁉️  TAHMİN: %s%n", tahmin));

        // Bu haftanın maçı oynandıysa gerçekleşeni doğrula
        if (target.played && target.htScore != null) {
            String gHtFt = htFtCode(target.htScore, target.ftScore);
            String gGrup = comebackGrubu(gHtFt);
            int    gGol  = toplamGol(target.ftScore);
            boolean tuttu = gGrup != null && (tahmin.contains(gGrup) || tahmin.contains("comeback"));
            sb.append(String.format("║  GERÇEKLEŞEN: %s (%d gol)%s → %s%n",
                    gHtFt, gGol, gGrup != null ? " | " + gGrup : "",
                    tuttu ? "✅ tuttu" : "❌ tutmadı"));
        }

        String kalite = c.signal ? " → güçlü döngü (boşluksuz)"
                : (c.missing > 0 ? " (eksik: " + c.missing + " sezon veri yok)" : "");
        sb.append(String.format("║  Değerlendirme: %d/%d döngü maçı comeback%s%n",
                c.comebacks, c.hits.size(), kalite));
        sb.append("╚════════════════════════════════════════════════════════════════════╝");
        return sb.toString();
    }

    /** Geçmiş döngü maçları aynı comeback grubundaysa o grubu, değilse genel "comeback" tahminini döndürür. */
    private static String ortakTahmin(List<CycleHit> hits) {
        String ortak = null;
        boolean hepsiCome = true;
        for (CycleHit a : hits) {
            if (a.grup == null) { hepsiCome = false; continue; }
            if (ortak == null) ortak = a.grup;
            else if (!ortak.equals(a.grup)) ortak = "MIXED";
        }
        if (!hepsiCome || ortak == null) return "belirsiz (geçmiş döngü maçları tutarsız)";
        if (FULL_COMEBACK.equals(ortak))       return FULL_COMEBACK + " (1/2 və ya 2/1)";
        if (BERABERLIK_COMEBACK.equals(ortak)) return BERABERLIK_COMEBACK + " (1/X və ya 2/X)";
        return "comeback (İY önde olan kazanamaz)";
    }

    // ─── HT/FT + GOL HESABI ─────────────────────────────────────────────────
    /** Skor dizeleri ev-deplasman notasyonuyla HT/FT kodu döndürür, ör. "2/1". */
    static String htFtCode(String htScore, String ftScore) {
        int[] ht = parseScore(htScore);
        int[] ft = parseScore(ftScore);
        if (ht == null || ft == null) return null;
        return "" + sonuc(ht) + "/" + sonuc(ft);
    }

    private static char sonuc(int[] s) {
        if (s[0] > s[1]) return '1';
        if (s[0] < s[1]) return '2';
        return 'X';
    }

    /**
     * İlk yarıyı önde kapatan takım kazanamadıysa comeback grubunu döndürür.
     * 1/2, 2/1 → FULL; 1/X, 2/X → BERABERLİK; aksi halde (sürpriz yok) null.
     */
    static String comebackGrubu(String htFt) {
        if (htFt == null) return null;
        switch (htFt) {
            case "1/2":
            case "2/1":
                return FULL_COMEBACK;
            case "1/X":
            case "2/X":
                return BERABERLIK_COMEBACK;
            default:
                return null;
        }
    }

    private static int toplamGol(String ftScore) {
        int[] ft = parseScore(ftScore);
        return ft == null ? 0 : ft[0] + ft[1];
    }

    private static int[] parseScore(String score) {
        if (score == null) return null;
        String[] parts = score.replaceAll("\\s*-\\s*", "-").split("-");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ─── DÖNGÜ MODELİ ───────────────────────────────────────────────────────
    /** Belirli bir P (yıl) döngüsünün, aynı haftadaki eşit aralıklı geçmiş maçları. */
    private static class Cycle {
        final int period;               // yıl farkı (sabit)
        final List<CycleHit> hits;      // bulunan geçmiş maçlar (boşluklar hariç)
        final int expected;             // beklenen adım sayısı (lookback içinde)
        final int missing;              // veri bulunamayan adım sayısı
        final boolean complete;         // missing == 0
        final int comebacks;            // comeback olan adım sayısı
        final boolean signal;           // complete && hepsi comeback && >= MIN_CYCLE

        Cycle(int period, List<CycleHit> hits, int expected, int missing,
              boolean complete, int comebacks, boolean signal) {
            this.period    = period;
            this.hits      = hits;
            this.expected  = expected;
            this.missing   = missing;
            this.complete  = complete;
            this.comebacks = comebacks;
            this.signal    = signal;
        }
    }

    private static class CycleHit {
        final String season;
        final MacData match;
        final String htFt;
        final String grup;      // null → sürpriz yok
        final int    toplamGol;

        CycleHit(String season, MacData match, String htFt, String grup, int toplamGol) {
            this.season    = season;
            this.match     = match;
            this.htFt      = htFt;
            this.grup      = grup;
            this.toplamGol = toplamGol;
        }
    }

    // ─── FİKSTÜR ÇEKME / PARSE ──────────────────────────────────────────────
    static class MacData {
        String homeTeam;
        String awayTeam;
        String ftScore;   // oynanmadıysa null
        String htScore;   // yoksa null
        String time;      // oynanmadıysa saat/tarih bilgisi (varsa)
        boolean played;
    }

    private static List<MacData> fetchSeasonMatches(CloseableHttpClient http,
                                                    int teamId, String season) throws IOException {
        List<MacData> result = new ArrayList<>();
        String html = fetchHtml(http, String.format(BASE_URL, teamId, season));
        if (html == null) return result;

        Document doc = Jsoup.parse(html);
        Element tbody = doc.selectFirst("#tblFixture > tbody");
        if (tbody == null) return result;

        // Sadece İLK turnuva bloğu (lig) — kupa/hazırlık maçları hafta dizilimini bozar
        List<Element> rows = new ArrayList<>();
        boolean inFirstLeague = false;
        for (Element row : tbody.select("tr")) {
            if (row.hasClass("competition")) {
                if (!inFirstLeague) {
                    inFirstLeague = true;
                    continue;
                }
                break;
            }
            if (inFirstLeague) rows.add(row);
        }
        if (rows.isEmpty()) {
            for (Element row : tbody.select("tr")) {
                String home = extractCell(row, "td:nth-child(3)");
                if (home != null && !home.isEmpty()) rows.add(row);
            }
        }

        for (Element row : rows) {
            String home = extractCell(row, "td:nth-child(3)");
            String away = extractCell(row, "td:nth-child(7)");
            if (home == null || home.isEmpty() || away == null || away.isEmpty()) continue;

            MacData md = new MacData();
            md.homeTeam = home;
            md.awayTeam = away;
            md.time     = extractCell(row, "td:nth-child(1)");

            Element scoreEl = row.selectFirst("td:nth-child(5) b a");
            String score = scoreEl != null ? scoreEl.text().trim() : "";
            int[] ft = parseScore(score);
            if (ft != null) {
                md.played  = true;
                md.ftScore = score.replaceAll("\\s*-\\s*", "-");
                String ht = extractCell(row, "td:nth-child(9)");
                if (ht != null && !ht.isEmpty() && parseScore(ht) != null) {
                    md.htScore = ht.replaceAll("\\s*-\\s*", "-");
                }
            }
            result.add(md);
        }
        return result;
    }

    private static String fetchHtml(CloseableHttpClient http, String url) throws IOException {
        HttpGet req = new HttpGet(url);
        req.addHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/91.0 Safari/537.36");
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(10000)
                .setConnectionRequestTimeout(10000)
                .setSocketTimeout(15000)
                .build();
        req.setConfig(config);

        try (CloseableHttpResponse resp = http.execute(req)) {
            if (resp.getStatusLine().getStatusCode() == 200) {
                return EntityUtils.toString(resp.getEntity());
            }
            return null;
        }
    }

    private static String extractCell(Element row, String cssSelector) {
        Element el = row.selectFirst(cssSelector);
        return el != null ? el.text().trim() : null;
    }

    /** Fikstür satırlarında en sık geçen isim bizim takımdır (her maçta görünür). */
    private static String detectTeamNameFromRows(List<MacData> matches) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (MacData m : matches) {
            freq.merge(m.homeTeam, 1, Integer::sum);
            freq.merge(m.awayTeam, 1, Integer::sum);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best != null && bestCount >= Math.max(1, matches.size() / 2)) return best;
        return null;
    }
}
