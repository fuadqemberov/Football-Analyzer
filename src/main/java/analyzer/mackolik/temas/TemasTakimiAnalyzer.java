package analyzer.mackolik.temas;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * TEMAS TAKIMI ANALİZÖRÜ (Betosman yöntemi)
 *
 * Mantık (video: "Bu Sezon Freiburg 2 den 1 Maçımız"):
 *   Bir takım, ligdeki belirli bir "temas takımı" ile oynamadan ÖNCE veya oynadıktan
 *   SONRA sabit bir mesafede (offset) HT/FT sürprizi çıkarmaya yatkın olabiliyor.
 *   Örnekler:
 *     - Helsinki: Mariehamn maçından 1 maç ÖNCE  → 4 sezon üst üste 1/2 veya 2/1
 *     - Barcelona: Valencia deplasmanından 2 maç SONRA → 4 sezon üst üste 1/2
 *     - Freiburg: Frankfurt teması etrafında 2/1 veya 1/2
 *   Videodaki kural: aynı (temas takımı + offset) kombinasyonu 3+ sezonda tekrar
 *   ediyorsa artık tesadüf değildir.
 *
 * SÜRPRİZ TANIMI (4 ayrı tahmin DEĞİL, 2 grup):
 *   İlk yarıyı önde kapatan takımın maçı KAZANAMAMASI aranır ve iki gruba ayrılır:
 *     - BERABERLİK COMEBACK : HT/FT = 1/X veya 2/X (önde olan yakalanır, maç berabere)
 *     - FULL COMEBACK       : HT/FT = 1/2 veya 2/1 (önde olan maçı kaybeder)
 *   Desen tekrarı da grup bazında sayılır: aynı temas takımı + aynı offset + AYNI
 *   comeback grubu farklı sezonlarda tekrarlanmalıdır.
 *
 * Akış:
 *   1. Günün başlamamış maçlarındaki takım ID'leri alınır (vd.mackolik.com/livedata).
 *   2. Her takım için mevcut sezon fikstürü çekilir, bugünkü (ilk oynanmamış) maç bulunur.
 *   3. Geçmiş sezonlar taranır: her HT/FT sürpriz maçının ±MAX_OFFSET komşuluğundaki
 *      rakipler "temas takımı adayı" olarak kaydedilir.
 *   4. Aynı (temas takımı, offset) çifti MIN_SEZON_TEKRARI+ farklı sezonda görülüyorsa,
 *      mevcut sezonda temas maçının konumuna bakılır: bugünkü maç tam olarak
 *      temasIdx + offset konumuna denk geliyorsa konsola SİNYAL basılır.
 */
public class TemasTakimiAnalyzer {

    // ─── AYARLAR ────────────────────────────────────────────────────────────
    private static final String BASE_URL       = "https://arsiv.mackolik.com/Team/Default.aspx?id=%d&season=%s";

    /** Yeni futbol sezonunun başladığı ay (Temmuz). Bu aydan itibaren yıl/(yıl+1) sezonundayız. */
    private static final int SEASON_START_MONTH = 7;

    /** Güncel sezonun başlangıç yılı sistem tarihinden dinamik hesaplanır (ör. Temmuz 2026 → 2026). */
    private static final int CURRENT_SEASON_START_YEAR = computeCurrentSeasonStartYear();
    /** Güncel sezon "yyyy/yyyy" formatında dinamik (ör. "2026/2027"). */
    private static final String CURRENT_SEASON = CURRENT_SEASON_START_YEAR + "/" + (CURRENT_SEASON_START_YEAR + 1);

    private static final int START_YEAR = CURRENT_SEASON_START_YEAR - 1;   // en yeni geçmiş sezon
    private static final int END_YEAR   = 2010;   // en eski taranan sezon
    private static final int NUM_THREADS = 10;

    /** Sistem tarihine göre güncel futbol sezonunun başlangıç yılını döndürür. */
    private static int computeCurrentSeasonStartYear() {
        LocalDate now = LocalDate.now();
        return now.getMonthValue() >= SEASON_START_MONTH ? now.getYear() : now.getYear() - 1;
    }

    /** Temas maçına maksimum uzaklık: -3..+3 (0 hariç). Negatif = temas maçından ÖNCE. */
    private static final int MAX_OFFSET = 3;

    /** Aynı (temas takımı, offset) çiftinin sinyal sayılması için gereken farklı sezon sayısı. */
    private static final int MIN_SEZON_TEKRARI = 2;

    /** İstatistikte "yüksek tekrar" sayılan eşik: tek sinyalde bu kadar+ farklı sezon tekrarı. */
    private static final int YUKSEK_TEKRAR_ESIGI = 8;

    /** "En çok sinyal alan maç" listesinde gösterilecek maç sayısı. */
    private static final int TOP_SINYAL_MAC = 20;

    // ─── MAIN ───────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  TEMAS TAKIMI ANALİZÖRÜ - HT/FT Sürpriz Avcısı        ║");
        System.out.println("║  1/X, 2/X → BERABERLİK COMEBACK                       ║");
        System.out.println("║  1/2, 2/1 → FULL COMEBACK                             ║");
        System.out.println("║  + CORRECT SCORE (ters-düz aynı skor tekrarı)         ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");

        long globalStart = System.currentTimeMillis();

        System.out.printf("📅 Güncel sezon (otomatik): %s | Taranan geçmiş: %d/%d → %d/%d%n%n",
                CURRENT_SEASON, START_YEAR, START_YEAR + 1, END_YEAR, END_YEAR + 1);

        System.out.println("🔄 [1/3] Günün başlamamış maçlarından takım ID'leri alınıyor...");
        List<String> teamIds = TeamIdsFetcher.fetchUnstartedTeamIds();
        System.out.println("✅ [1/3] " + teamIds.size() + " takım bulundu\n");

        if (teamIds.isEmpty()) {
            System.out.println("❌ Hiç takım ID'si bulunamadı, program sonlandırılıyor.");
            return;
        }

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(NUM_THREADS + 5);
        cm.setDefaultMaxPerRoute(NUM_THREADS);
        CloseableHttpClient http = HttpClients.custom().setConnectionManager(cm).build();

        System.out.println("🔄 [2/3] " + teamIds.size() + " takım " + NUM_THREADS + " thread ile taranıyor...\n");
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<TeamResult>> futures = new ArrayList<>();

        for (String idStr : teamIds) {
            try {
                int teamId = Integer.parseInt(idStr.trim());
                futures.add(executor.submit(new TemasTask(teamId, http)));
            } catch (NumberFormatException e) {
                System.err.println("   ❌ Geçersiz takım ID: " + idStr);
            }
        }

        List<SignalInfo> tumSinyaller = new ArrayList<>();
        int processed = 0, signalCount = 0;
        for (Future<TeamResult> f : futures) {
            try {
                TeamResult result = f.get(5, TimeUnit.MINUTES);
                processed++;
                if (result != null && !result.output.isEmpty()) {
                    signalCount++;
                    System.out.println(result.output);
                    tumSinyaller.addAll(result.signals);
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
        System.out.printf("✅ [3/3] TARAMA TAMAMLANDI | %d takım | %d sinyal | %ds%n",
                processed, signalCount, sure);
        System.out.println("════════════════════════════════════════════════════════\n");

        printStatistics(tumSinyaller);
    }

    // ─── İSTATİSTİK ÖZETİ ────────────────────────────────────────────────────
    /**
     * Program sonu istatistiği:
     *   1) En çok sinyal alan maç (aynı maça düşen sinyal sayısı en yüksek olan).
     *   2) Tek bir sinyalde {@link #YUKSEK_TEKRAR_ESIGI}+ farklı sezonda tekrarlayan maçlar,
     *      FULL COMEBACK ve BERABERLİK COMEBACK olarak ayrı listelenir.
     */
    private static void printStatistics(List<SignalInfo> sinyaller) {
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║  📊 İSTATİSTİK ÖZETİ                                     ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");

        if (sinyaller.isEmpty()) {
            System.out.println("Hiç sinyal üretilmedi — istatistik yok.\n");
            return;
        }

        // ── 1) En çok sinyal alan maçlar (ilk TOP_SINYAL_MAC) ──
        Map<String, Integer> macBasinaSinyal = new LinkedHashMap<>();
        for (SignalInfo s : sinyaller) {
            macBasinaSinyal.merge(s.matchKey(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> siralı = new ArrayList<>(macBasinaSinyal.entrySet());
        siralı.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int gosterilecek = Math.min(TOP_SINYAL_MAC, siralı.size());
        System.out.printf("%n🏆 EN ÇOK SİNYAL ALAN MAÇLAR (top %d / toplam %d maç):%n",
                gosterilecek, siralı.size());
        for (int i = 0; i < gosterilecek; i++) {
            Map.Entry<String, Integer> e = siralı.get(i);
            System.out.printf("   %2d) %3d sinyal | %s%n", i + 1, e.getValue(), e.getKey());
        }

        // ── 2) Tek sinyalde YUKSEK_TEKRAR_ESIGI+ sezon tekrarı olan maçlar ──
        List<SignalInfo> fullCB     = new ArrayList<>();
        List<SignalInfo> beraberCB  = new ArrayList<>();
        for (SignalInfo s : sinyaller) {
            if (s.seasonCount < YUKSEK_TEKRAR_ESIGI) continue;
            if (FULL_COMEBACK.equals(s.group)) fullCB.add(s);
            else beraberCB.add(s);
        }
        Comparator<SignalInfo> tekrardanAzalan = (a, b) -> Integer.compare(b.seasonCount, a.seasonCount);
        fullCB.sort(tekrardanAzalan);
        beraberCB.sort(tekrardanAzalan);

        System.out.printf("%n🔥 1 SİNYALDE %d+ SEZON TEKRARI OLAN MAÇLAR:%n", YUKSEK_TEKRAR_ESIGI);

        System.out.println("\n   ── FULL COMEBACK (1/2, 2/1) ──");
        printYuksekTekrarListesi(fullCB);

        System.out.println("\n   ── BERABERLİK COMEBACK (1/X, 2/X) ──");
        printYuksekTekrarListesi(beraberCB);

        System.out.println();
    }

    private static void printYuksekTekrarListesi(List<SignalInfo> liste) {
        if (liste.isEmpty()) {
            System.out.println("      (yok)");
            return;
        }
        for (SignalInfo s : liste) {
            System.out.printf("      %2d sezon | %s | temas: %s%n",
                    s.seasonCount, s.matchKey(), s.temasAdi);
        }
    }

    // ─── TAKIM BAŞINA GÖREV ─────────────────────────────────────────────────
    private static class TemasTask implements Callable<TeamResult> {
        private final int teamId;
        private final CloseableHttpClient http;

        TemasTask(int teamId, CloseableHttpClient http) {
            this.teamId = teamId;
            this.http   = http;
        }

        @Override
        public TeamResult call() {
            try {
                return analyzeTeam(http, teamId);
            } catch (Exception e) {
                System.err.println("   ❌ [ID:" + teamId + "] Hata: " + e.getMessage());
                return null;
            }
        }
    }

    // ─── ANA ANALİZ ─────────────────────────────────────────────────────────
    static TeamResult analyzeTeam(CloseableHttpClient http, int teamId) throws IOException {

        // 1. Mevcut sezon fikstürü + bugünkü maç
        List<MacData> current = fetchSeasonMatches(http, teamId, CURRENT_SEASON);
        if (current.isEmpty()) return null;

        String teamName = detectTeamNameFromRows(current);
        if (teamName == null) return null;

        int todayIdx = -1;
        for (int i = 0; i < current.size(); i++) {
            if (!current.get(i).played) {
                todayIdx = i;
                break;
            }
        }
        if (todayIdx < 0) return null;
        MacData todayMatch = current.get(todayIdx);

        // 2. Geçmiş sezonları tara → (temas takımı, offset, comeback grubu) kanıt havuzu
        //    key = normalize(temasAdı) + "|" + offset + "|" + grup
        Map<String, TemasKaniti> kanitlar = new LinkedHashMap<>();

        for (int year = START_YEAR; year >= END_YEAR; year--) {
            String season = year + "/" + (year + 1);
            List<MacData> matches;
            try {
                matches = fetchSeasonMatches(http, teamId, season);
            } catch (IOException e) {
                continue;
            }
            if (matches.isEmpty()) continue;

            String histName = detectTeamNameFromRows(matches);
            if (histName == null) continue;

            for (int i = 0; i < matches.size(); i++) {
                MacData m = matches.get(i);
                if (!m.played) continue;
                String surpriz = surprizHtFt(m.htScore, m.ftScore);
                if (surpriz == null) continue;

                for (int off = -MAX_OFFSET; off <= MAX_OFFSET; off++) {
                    if (off == 0) continue;
                    int j = i + off;
                    if (j < 0 || j >= matches.size()) continue;

                    MacData temasMac = matches.get(j);
                    String temas = opponentOf(temasMac, histName);
                    if (temas == null) continue;

                    // Not: offset = sürprizIdx - temasIdx.
                    // Sürpriz i'de, temas j=i+off'ta ise sürpriz temasa göre -off konumundadır.
                    int offset = -off;
                    String grup = comebackGrubu(surpriz);
                    String key = normalize(temas) + "|" + offset + "|" + grup;
                    TemasKaniti k = kanitlar.computeIfAbsent(key,
                            x -> new TemasKaniti(temas, offset, grup));
                    k.addHit(season, m, temasMac, surpriz, venueOf(temasMac, histName));
                }
            }
        }

        // 3. Yeterince tekrar eden desenleri bugünkü maçla eşleştir
        StringBuilder out = new StringBuilder();
        List<SignalInfo> signals = new ArrayList<>();
        String macAdi = todayMatch.homeTeam + " vs " + todayMatch.awayTeam;
        for (TemasKaniti k : kanitlar.values()) {
            if (k.seasons.size() < MIN_SEZON_TEKRARI) continue;

            // Bugünkü maç, temas maçına göre "offset" konumunda mı?
            // temasIdx + offset == todayIdx  →  temasIdx = todayIdx - offset
            int temasIdx = todayIdx - k.offset;
            if (temasIdx < 0 || temasIdx >= current.size()) continue;

            MacData temasMacBugun = current.get(temasIdx);
            String rakip = opponentOf(temasMacBugun, teamName);
            if (rakip == null || !teamsMatch(rakip, k.temasAdi)) continue;

            out.append(buildSignal(teamName, todayMatch, k, temasMacBugun, teamName));
            signals.add(new SignalInfo(teamName, macAdi, k.grup, k.seasons.size(), k.temasAdi));
        }

        return signals.isEmpty() ? null : new TeamResult(out.toString(), signals);
    }

    // ─── SİNYAL ÇIKTISI ─────────────────────────────────────────────────────
    private static String buildSignal(String teamName, MacData todayMatch,
                                      TemasKaniti k, MacData temasMacBugun, String bizimTakim) {
        String guc = k.seasons.size() >= 4 ? "ÇOK GÜÇLÜ"
                   : k.seasons.size() == 3 ? "GÜÇLÜ (tesadüf değil)"
                   : "ORTA";

        String konum = k.offset < 0
                ? Math.abs(k.offset) + " maç ÖNCE"
                : k.offset + " maç SONRA";

        String tahmin = k.grup.equals(FULL_COMEBACK)
                ? "HT/FT 1/2 veya 2/1 → FULL COMEBACK"
                : "HT/FT 1/X veya 2/X → BERABERLİK COMEBACK";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n╔════════════════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║ 🎯 SİNYAL: %s%n", teamName));
        sb.append(String.format("║    Bugünkü Maç : %s vs %s%n", todayMatch.homeTeam, todayMatch.awayTeam));
        sb.append(String.format("║    Tahmin      : %s%n", tahmin));
        String cs = k.correctScore();
        if (cs != null) {
            int[] p = parseScore(cs);
            String ters = p[1] + "-" + p[0];
            sb.append(String.format("║    CORRECT SCORE: %s veya %s (ters-düz AYNI skor, tüm sezonlar)%n",
                    cs, ters));
        }
        sb.append(String.format("║    Temas Takımı: %s%n", k.temasAdi));
        sb.append(String.format("║    Kural       : Sürpriz, %s temasından %s geliyor%n", k.temasAdi, konum));
        sb.append(String.format("║    Bugünkü temas maçı: %s vs %s (%s)%n",
                temasMacBugun.homeTeam, temasMacBugun.awayTeam,
                temasMacBugun.played ? "oynandı: " + temasMacBugun.ftScore : "henüz oynanmadı"));
        sb.append(String.format("║    Güç         : %s → %d farklı sezonda tekrarladı%n", guc, k.seasons.size()));
        sb.append("╠════════════════════════════════════════════════════════════════════╣\n");
        sb.append("║    GEÇMİŞ SEZON KANITLARI:\n");
        for (String satir : k.hitSatirlari) {
            sb.append("║    ").append(satir).append("\n");
        }
        sb.append("╚════════════════════════════════════════════════════════════════════╝");
        return sb.toString();
    }

    // ─── SONUÇ / SİNYAL MODELİ ──────────────────────────────────────────────
    /** Bir takım analizinin sonucu: ekrana basılan metin + istatistik için sinyal listesi. */
    static class TeamResult {
        final String output;
        final List<SignalInfo> signals;

        TeamResult(String output, List<SignalInfo> signals) {
            this.output  = output;
            this.signals = signals;
        }
    }

    /** İstatistik için tek bir sinyalin özeti. */
    static class SignalInfo {
        final String teamName;
        final String macAdi;       // "Ev vs Deplasman"
        final String group;        // FULL_COMEBACK / BERABERLIK_COMEBACK
        final int    seasonCount;  // desenin tekrarlandığı farklı sezon sayısı
        final String temasAdi;

        SignalInfo(String teamName, String macAdi, String group, int seasonCount, String temasAdi) {
            this.teamName    = teamName;
            this.macAdi      = macAdi;
            this.group       = group;
            this.seasonCount = seasonCount;
            this.temasAdi    = temasAdi;
        }

        /** Sinyalleri maça göre gruplamak için anahtar (hangi takım + bugünkü maç). */
        String matchKey() {
            return teamName + ": " + macAdi;
        }
    }

    // ─── KANIT MODELİ ───────────────────────────────────────────────────────
    private static class TemasKaniti {
        final String temasAdi;
        final int offset;                              // sürprizIdx - temasIdx
        final String grup;                             // BERABERLIK_COMEBACK / FULL_COMEBACK
        final Set<String> seasons = new LinkedHashSet<>();
        final List<String> hitSatirlari = new ArrayList<>();
        final List<String> ftSkorlari = new ArrayList<>();   // sürpriz maçların FT skorları (sezon başına 1)

        TemasKaniti(String temasAdi, int offset, String grup) {
            this.temasAdi = temasAdi;
            this.offset   = offset;
            this.grup     = grup;
        }

        void addHit(String season, MacData surprizMac, MacData temasMac,
                    String htFt, String temasVenue) {
            // Aynı sezonda aynı çift birden fazla kez düşerse sadece ilkini yaz
            if (!seasons.add(season)) return;
            ftSkorlari.add(surprizMac.ftScore);
            hitSatirlari.add(String.format(
                    "%-9s | SÜRPRİZ: %s %s (İY: %s) %s → HT/FT %s | TEMAS: %s vs %s [%s]",
                    season,
                    surprizMac.homeTeam, surprizMac.ftScore, surprizMac.htScore, surprizMac.awayTeam,
                    htFt,
                    temasMac.homeTeam, temasMac.awayTeam, temasVenue));
        }

        /**
         * Tüm sezonlardaki sürpriz maçlar AYNI skorla (ters-düz dahil) bittiyse
         * o skoru döndürür; örn. 3-1, 1-3, 3-1 → "3-1". Aksi halde null.
         */
        String correctScore() {
            if (ftSkorlari.size() < MIN_SEZON_TEKRARI) return null;
            String canon = null;
            for (String s : ftSkorlari) {
                int[] p = parseScore(s);
                if (p == null) return null;
                int hi = Math.max(p[0], p[1]);
                int lo = Math.min(p[0], p[1]);
                String c = hi + "-" + lo;
                if (canon == null) canon = c;
                else if (!canon.equals(c)) return null;   // sezonlardan biri farklı skor → yok
            }
            return canon;   // hi-lo formunda; ters-düz iki yön de geçerli
        }
    }

    // ─── HT/FT SÜRPRİZ HESABI ───────────────────────────────────────────────
    static final String BERABERLIK_COMEBACK = "BERABERLIK COMEBACK";
    static final String FULL_COMEBACK       = "FULL COMEBACK";

    /**
     * 1/X ve 2/X → beraberlik comeback, 1/2 ve 2/1 → full comeback.
     * 4 sonuç 4 ayrı tahmin olarak DEĞİL, bu 2 grup olarak sayılır.
     */
    static String comebackGrubu(String htFt) {
        return ("1/2".equals(htFt) || "2/1".equals(htFt)) ? FULL_COMEBACK : BERABERLIK_COMEBACK;
    }

    /**
     * İlk yarıyı önde kapatan takım maçı kazanamadıysa sürprizdir.
     * Dönüş: "1/X", "1/2", "2/X", "2/1" veya null.
     */
    static String surprizHtFt(String htScore, String ftScore) {
        int[] ht = parseScore(htScore);
        int[] ft = parseScore(ftScore);
        if (ht == null || ft == null) return null;

        if (ht[0] > ht[1]) {                       // ilk yarı ev önde
            if (ft[0] == ft[1]) return "1/X";
            if (ft[1] > ft[0])  return "1/2";
        } else if (ht[1] > ht[0]) {                // ilk yarı deplasman önde
            if (ft[0] == ft[1]) return "2/X";
            if (ft[0] > ft[1])  return "2/1";
        }
        return null;
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

    // ─── FİKSTÜR ÇEKME / PARSE ──────────────────────────────────────────────
    static class MacData {
        String homeTeam;
        String awayTeam;
        String ftScore;   // oynanmadıysa null
        String htScore;   // yoksa null
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

        // Sadece İLK turnuva bloğu (lig) alınır — kupa maçları desen dizilimini bozar
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

    // ─── TAKIM ADI TESPİTİ / EŞLEŞTİRME ────────────────────────────────────
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

    private static String opponentOf(MacData m, String teamName) {
        if (teamsMatch(m.homeTeam, teamName)) return m.awayTeam;
        if (teamsMatch(m.awayTeam, teamName)) return m.homeTeam;
        return null;
    }

    private static String venueOf(MacData m, String teamName) {
        return teamsMatch(m.homeTeam, teamName) ? "İç Saha" : "Deplasman";
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String ascii = s
                .replace("ı", "i").replace("İ", "i")
                .replace("ğ", "g").replace("Ğ", "g")
                .replace("ş", "s").replace("Ş", "s")
                .replace("ç", "c").replace("Ç", "c")
                .replace("ö", "o").replace("Ö", "o")
                .replace("ü", "u").replace("Ü", "u")
                .replace("é", "e").replace("á", "a")
                .replace("ó", "o").replace("ú", "u")
                .replace("ñ", "n").replace("ã", "a");
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Sezonlar arası kısaltma farklarına dayanıklı isim eşleştirme. */
    static boolean teamsMatch(String teamA, String teamB) {
        if (teamA == null || teamB == null) return false;
        String a = normalize(teamA);
        String b = normalize(teamB);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (a.contains(b) || b.contains(a)) return true;

        for (String tA : teamA.trim().split("\\s+")) {
            String nA = normalize(tA);
            if (nA.length() < 3) continue;
            for (String tB : teamB.trim().split("\\s+")) {
                String nB = normalize(tB);
                if (nB.length() < 3) continue;
                if (nA.equals(nB)) return true;
                if (nA.length() >= 4 && nB.startsWith(nA)) return true;
                if (nB.length() >= 4 && nA.startsWith(nB)) return true;
            }
        }
        return false;
    }
}
