package analyzer.mackolik.xthmatch;

import analyzer.util.MackolikHttpFetcher;
import analyzer.util.TeamIdsFetcher;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * X. RESMİ MAÇ HT/FT ANALİZİ (2/1 - 1/2)
 * =====================================================================
 * ALGORİTMA:
 *   1. Bir takımın MEVCUT sezondaki (2025/2026) fikstüründe şimdi
 *      kaçıncı RESMİ maçını oynayacağını bulur  →  X.
 *      (Örn: 5 resmi maç oynanmış, o zaman şimdi 6. resmi maçını oynayacak → X = 6)
 *
 *   2. Geride kalan son {@link #PAST_SEASONS} sezona bakar. Her sezonun
 *      X. RESMİ maçını alır ve bu maçın HT/FT sonucu 2/1 ya da 1/2 ise
 *      sezonu ve tüm detaylarını ekrana yazar.
 *
 *   ÖNEMLİ:  "HAZ" / "Hazırlık" lig kısaltması bir HAZIRLIK (dostluk) maçıdır.
 *            RESMİ maç sayılmaz; sıralamaya ve saymaya dahil edilmez.
 *
 * Veri kaynağı: https://arsiv.mackolik.com/Team/Default.aspx?id=..&season=..
 *   - Fikstür tablosu (#tblFixture) maçları LİGE GÖRE gruplar. Her grubun
 *     başında bir <tr class="competition"> satırı lig adını verir. Biz her
 *     maçın ligini, üstündeki en yakın competition satırından alırız; sonra
 *     tüm resmi maçları TARİHE GÖRE birleştirip kronolojik sıralarız.
 *
 * Kullanım:
 *   - Argümansız: bugünün başlamamış maçlarındaki takımları otomatik çeker.
 *   - Argümanlı : java ... SeasonXthOfficialMatchAnalyzer 3 566 ...  (takım ID'leri)
 */
public class SeasonXthOfficialMatchAnalyzer {

    private static final String BASE_URL       = "https://arsiv.mackolik.com/Team/Default.aspx?id=%d&season=%s";
    /** Yeni futbol sezonunun başladığı ay (Temmuz). */
    private static final int    SEASON_START_MONTH = 7;
    private static final int    CURRENT_YEAR   = computeCurrentSeasonStartYear();
    private static final String CURRENT_SEASON = CURRENT_YEAR + "/" + (CURRENT_YEAR + 1);
    private static final int    PAST_SEASONS   = 20;     // geride kalan sezon sayısı
    private static final int    NUM_THREADS    = 8;
    /** İki HTTP isteği arasındaki en küçük global boşluk (ms) — sunucuyu boğmamak için. */
    private static final long   REQUEST_GAP_MS = 40;

    private static int computeCurrentSeasonStartYear() {
        LocalDate now = LocalDate.now();
        return now.getMonthValue() >= SEASON_START_MONTH ? now.getYear() : now.getYear() - 1;
    }

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d.M.yyyy", Locale.ROOT);

    // =====================================================================
    // Veri modeli
    // =====================================================================

    /** Fikstürdeki tek bir maç. */
    private static class Match {
        String    league;     // lig adı (competition satırından)
        String    dateStr;    // "11.08.2024"
        LocalDate date;       // parse edilmiş tarih (sıralama için, null olabilir)
        String    home;
        String    away;
        String    ftScore;    // "2-1"
        String    htScore;    // "1-0"
        boolean   played;     // skor sayısal olarak okunabildi mi

        boolean friendly() { return isFriendly(league); }
    }

    /** Geçmiş bir sezonda bulunan 2/1 veya 1/2 isabeti. */
    private static class Hit {
        final String season;
        final Match  match;
        final String htFt;

        Hit(String season, Match match, String htFt) {
            this.season = season;
            this.match  = match;
            this.htFt   = htFt;
        }
    }

    // =====================================================================
    // Takım başına görev
    // =====================================================================

    private static class TeamTask implements Callable<String> {
        private final int                teamId;
        private final MackolikHttpFetcher http;

        TeamTask(int teamId, MackolikHttpFetcher http) {
            this.teamId = teamId;
            this.http   = http;
        }

        @Override
        public String call() {
            try {
                // ── 1) Mevcut sezon: X'i belirle ──────────────────────────
                List<Match> current = fetchSeasonMatches(http, teamId, CURRENT_SEASON, false);
                if (current == null) {
                    System.err.println("   ❌ [ID:" + teamId + "] güncel sezon indirilemedi (retry'lar tükendi)");
                    return null;
                }
                if (current.isEmpty()) return null;

                String teamName = detectTeamName(current);

                List<Match> officialCurrent = officialSorted(current);
                if (officialCurrent.isEmpty()) return null;

                long playedOfficial = officialCurrent.stream().filter(m -> m.played).count();
                int  x = (int) playedOfficial + 1;   // şimdi oynayacağı resmi maç no

                // Oynanacak (X.) maçın bilgisi — henüz oynanmamış ilk resmi maç
                Match upcoming = (officialCurrent.size() >= x) ? officialCurrent.get(x - 1) : null;

                // ── 2) Geçmiş sezonların X. resmi maçına bak ──────────────
                List<Hit> hits = new ArrayList<>();
                for (int year = CURRENT_YEAR - 1; year >= CURRENT_YEAR - PAST_SEASONS; year--) {
                    String season = year + "/" + (year + 1);
                    List<Match> seasonMatches = fetchSeasonMatches(http, teamId, season, true);
                    if (seasonMatches == null) {
                        System.err.println("   ❌ [ID:" + teamId + "] " + season + " indirilemedi (retry'lar tükendi)");
                        continue;
                    }
                    if (seasonMatches.isEmpty()) continue;

                    List<Match> official = officialSorted(seasonMatches);
                    if (official.size() < x) continue;   // o sezon X. resmi maç yok

                    Match xth  = official.get(x - 1);
                    String htFt = computeHtFt(xth.ftScore, xth.htScore);
                    if (htFt != null) {
                        hits.add(new Hit(season, xth, htFt));
                    }
                }

                if (hits.isEmpty()) return null;
                return buildReport(teamId, teamName, x, upcoming, hits);

            } catch (Exception e) {
                System.err.println("   ❌ [ID:" + teamId + "] FATAL: " + e.getMessage());
                return null;
            }
        }
    }

    // =====================================================================
    // Rapor
    // =====================================================================

    private static String buildReport(int teamId, String teamName, int x, Match upcoming, List<Hit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════════════════╗\n");
        sb.append(String.format( "  ✅ %s (ID: %d)%n", teamName, teamId));
        sb.append(String.format( "  Şimdi oynayacağı RESMİ maç sırası : %d. maç%n", x));
        if (upcoming != null) {
            sb.append(String.format("  Oynanacak maç : %s  %s vs %s  [%s]%n",
                    upcoming.dateStr, upcoming.home, upcoming.away, upcoming.league));
        }
        sb.append(String.format( "  Son %d sezonda %d. resmi maçta 2/1 - 1/2 sayısı : %d%n",
                PAST_SEASONS, x, hits.size()));
        sb.append("╚══════════════════════════════════════════════════════════════════╝\n");

        for (Hit h : hits) {
            Match m = h.match;
            sb.append(String.format(
                    "   ⭐ %-9s | %d. resmi maç | HT/FT: %s%n" +
                    "        %s  →  %s %s %s   (İY: %s)  [%s]%n",
                    h.season, x, h.htFt,
                    m.dateStr, m.home, m.ftScore, m.away,
                    (m.htScore == null || m.htScore.isEmpty()) ? "-" : m.htScore,
                    m.league));
        }
        return sb.toString();
    }

    // =====================================================================
    // Fikstür çekme ve parse
    // =====================================================================

    /**
     * Bir sezonun TÜM maçlarını, her birinin ligi ile birlikte döndürür.
     * (Resmi/hazırlık ayrımı ve sıralama sonradan yapılır.)
     *
     * @param cacheable geçmiş (değişmez) sezon mu? Güncel sezon için false.
     * @return maç listesi; sayfa alındı ama fikstür yoksa BOŞ liste;
     *         sayfa retry'lara rağmen indirilemediyse <b>null</b>.
     */
    private static List<Match> fetchSeasonMatches(MackolikHttpFetcher http, int teamId,
                                                  String season, boolean cacheable) {

        Document doc = http.fetchDocument(String.format(BASE_URL, teamId, season), cacheable);
        if (doc == null) return null;

        List<Match> matches = new ArrayList<>();
        Elements rows = doc.select("#tblFixture > tbody > tr");
        if (rows.isEmpty()) rows = doc.select("#tblFixture > tr");

        String currentLeague = "?";
        for (Element row : rows) {
            // ── Lig başlığı satırı ────────────────────────────────────────
            if (row.hasClass("competition")) {
                Element link = row.selectFirst("td a");
                String name  = (link != null) ? link.text().trim() : row.text().trim();
                if (!name.isEmpty()) currentLeague = name;
                continue;
            }

            // ── Maç satırı ───────────────────────────────────────────────
            Element homeEl = row.selectFirst("td:nth-child(3)");
            Element awayEl = row.selectFirst("td:nth-child(7)");
            if (homeEl == null || awayEl == null) continue;

            String home = homeEl.text().trim();
            String away = awayEl.text().trim();
            if (home.isEmpty() || away.isEmpty()) continue;

            Match m   = new Match();
            m.league  = currentLeague;
            m.home    = home;
            m.away    = away;
            m.dateStr = extractText(row, "td:nth-child(1)");
            m.date    = parseDate(m.dateStr);

            // Skor (FT) — td:5 > b > a
            Element scoreEl = row.selectFirst("td:nth-child(5) b a");
            String  ftRaw   = (scoreEl != null) ? scoreEl.text().trim() : "";
            String  ft      = normalizeScore(ftRaw);
            m.played  = isPlayable(ft);
            m.ftScore = m.played ? ft : null;

            // İlk yarı (HT) — td:9
            String htRaw = extractText(row, "td:nth-child(9)");
            m.htScore = (htRaw == null) ? null : normalizeScore(htRaw);

            matches.add(m);
        }
        return matches;
    }

    /**
     * Yalnızca RESMİ maçları (hazırlık hariç) döndürür, tarihe göre
     * kronolojik sıralar. Tarih parse edilemezse tablo sırasını korur.
     */
    private static List<Match> officialSorted(List<Match> all) {
        List<Match> official = new ArrayList<>();
        for (Match m : all) {
            if (!m.friendly()) official.add(m);
        }
        // Stabil sıralama: tarihi olanlar tarihe göre, olmayanlar mevcut yerinde.
        official.sort(Comparator.comparing(
                m -> m.date == null ? LocalDate.MAX : m.date));
        return official;
    }

    // =====================================================================
    // Yardımcı metodlar
    // =====================================================================

    /** Lig adı hazırlık (dostluk) maçı mı? "HAZ" veya "Hazırlık" içerir. */
    private static boolean isFriendly(String league) {
        if (league == null) return false;
        String n = league
                .replace("ı", "i").replace("İ", "i")
                .replace("Ş", "s").replace("ş", "s")
                .toLowerCase(Locale.ROOT);
        // "hazirlik ..." (tam ad)  veya  "haz" kısaltması
        return n.contains("hazir") || n.equals("haz") || n.startsWith("haz ") || n.startsWith("haz.");
    }

    /** "2 - 1" → "2-1". */
    private static String normalizeScore(String s) {
        if (s == null) return null;
        return s.replaceAll("\\s*-\\s*", "-").trim();
    }

    /** Skor "N-N" biçiminde sayısal mı (yani maç oynandı mı)? */
    private static boolean isPlayable(String score) {
        if (score == null || score.isEmpty() || !score.contains("-")) return false;
        String[] p = score.split("-");
        if (p.length != 2) return false;
        try {
            Integer.parseInt(p[0].trim());
            Integer.parseInt(p[1].trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** HT/FT ters dönüşü: sadece "1/2" (ev öne geçti, deplasman kazandı) ve
     *  "2/1" (deplasman öne geçti, ev kazandı). Aksi halde null. */
    static String computeHtFt(String ftScore, String htScore) {
        if (ftScore == null || htScore == null) return null;
        try {
            String[] ft = normalizeScore(ftScore).split("-");
            String[] ht = normalizeScore(htScore).split("-");
            if (ft.length != 2 || ht.length != 2) return null;

            int ftH = Integer.parseInt(ft[0].trim());
            int ftA = Integer.parseInt(ft[1].trim());
            int htH = Integer.parseInt(ht[0].trim());
            int htA = Integer.parseInt(ht[1].trim());

            if (htH > htA && ftA > ftH) return "1/2";
            if (htA > htH && ftH > ftA) return "2/1";
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDate.parse(s.trim(), DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    /** Fikstür satırlarından en sık geçen takım adını (bizim takım) bulur. */
    private static String detectTeamName(List<Match> matches) {
        java.util.Map<String, Integer> freq = new java.util.HashMap<>();
        for (Match m : matches) {
            if (m.home != null) freq.merge(m.home, 1, Integer::sum);
            if (m.away != null) freq.merge(m.away, 1, Integer::sum);
        }
        return freq.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse("?");
    }

    private static String extractText(Element row, String css) {
        Element el = row.selectFirst(css);
        return el != null ? el.text().trim() : null;
    }

    // =====================================================================
    // main
    // =====================================================================

    public static void main(String[] args) {
        System.out.println("\n╔════════════════════════════════════════════════════╗");
        System.out.println("║   X. RESMİ MAÇ  HT/FT (2/1 - 1/2)  ANALİZİ          ║");
        System.out.println("╚════════════════════════════════════════════════════╝\n");

        // Takım ID'leri: argümanlardan ya da bugünün başlamamış maçlarından
        List<Integer> teamIds = new ArrayList<>();
        if (args.length > 0) {
            for (String a : args) {
                try { teamIds.add(Integer.parseInt(a.trim())); }
                catch (NumberFormatException e) { System.err.println("Geçersiz ID: " + a); }
            }
            System.out.println("📌 Argümanlardan " + teamIds.size() + " takım alındı.\n");
        } else {
            System.out.println("🔄 Bugünün başlamamış maçlarından takım ID'leri çekiliyor...");
            for (String id : TeamIdsFetcher.fetchUnstartedTeamIds()) {
                try { teamIds.add(Integer.parseInt(id.trim())); } catch (NumberFormatException ignored) {}
            }
            System.out.println("✅ " + teamIds.size() + " benzersiz takım bulundu.\n");
        }

        if (teamIds.isEmpty()) {
            System.out.println("❌ Hiç takım ID'si yok.");
            return;
        }

        MackolikHttpFetcher http = new MackolikHttpFetcher(NUM_THREADS, REQUEST_GAP_MS);

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<String>> futures = new ArrayList<>();
        for (int id : teamIds) {
            futures.add(executor.submit(new TeamTask(id, http)));
        }

        long start = System.currentTimeMillis();
        int found = 0, processed = 0;
        for (Future<String> f : futures) {
            try {
                String result = f.get(5, TimeUnit.MINUTES);
                processed++;
                if (result != null && !result.isEmpty()) {
                    System.out.println(result);
                    found++;
                }
                System.out.printf("\r⏳ İlerleme: %d/%d | Bulunan: %d", processed, futures.size(), found);
            } catch (TimeoutException e) {
                f.cancel(true);
                processed++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                processed++;
                System.err.println("\n   ❌ " + e.getCause().getMessage());
            }
        }

        long secs = (System.currentTimeMillis() - start) / 1000;
        System.out.println("\n\n════════════════════════════════════════════════════");
        System.out.printf("✅ TAMAMLANDI  |  Takım: %d  |  Bulunan: %d  |  Süre: %ds%n",
                processed, found, secs);
        System.out.println("🌐 İstek özeti: " + http.statsLine());
        System.out.println("════════════════════════════════════════════════════\n");

        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        http.close();
        System.exit(0);
    }
}
