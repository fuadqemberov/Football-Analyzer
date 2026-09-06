package analyzer.mackolik.before_after_pattern;

import analyzer.util.TeamIdsFetcher;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MackolikHalfTimePatternFinder {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final List<String> matchedPatterns = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger completedCount = new AtomicInteger(0);

    private static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final Semaphore semaphore = new Semaphore(20);

    public static void main(String[] args) throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("🚀 Sistem başlatıldı");
        System.out.println("📊 Paralel istek limiti: 20\n");

        System.out.println("📍 Maç ID'leri toplanıyor...\n");
        Set<String> matchIds = fetchMatchIdsFromAPI();

        if (matchIds.isEmpty()) {
            System.out.println("❌ Hiç maç bulunamadı!");
            return;
        }

        System.out.println("================================");
        System.out.println("📋 Toplanan Maç ID Sayısı: " + matchIds.size());
        System.out.println("================================\n");
        System.out.println("⚙️  ANALİZ BAŞLIYOR...\n");

        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String id : matchIds) {
                Future<?> future = executor.submit(() -> analyzeMatch(id));
                futures.add(future);
            }

            System.out.println("⏳ Tüm maç analizleri tamamlanıyor...");
            int done = 0;
            for (Future<?> future : futures) {
                try {
                    future.get(120, TimeUnit.SECONDS);
                    done++;
                    printProgress(done, matchIds.size());
                } catch (TimeoutException te) {
                    future.cancel(true);
                } catch (Exception ignored) {}
            }
        }

        printResults(startTime);
    }

    // ─────────────────────────────────────────────────────────────
    // API'den Maç ID'lerini Çek
    // ─────────────────────────────────────────────────────────────
    /**
     * Günün BAŞLAMAMIŞ maçlarının ID'leri.
     *
     * <p>Eskiden burada {@code livedata?group=0} gövdesinden {@code \[(\d{7}),} deseniyle
     * 7 haneli her sayı toplanıyordu. İki sorun vardı: (1) {@code group=0} günün programı
     * değil canlı maç tahtasıdır, sabah saatlerinde akşamki maçlar orada olmaz;
     * (2) desen "m" dizisinin dışındaki (canlı olay) satırlardan da ID topluyor, üstelik
     * başlamış/bitmiş maçları ayırmıyordu. Ortak okuma artık {@link TeamIdsFetcher}
     * içinde ({@code group=all} + durum ve tarih süzgeci).
     */
    private static Set<String> fetchMatchIdsFromAPI() {
        Set<String> ids = new LinkedHashSet<>(TeamIdsFetcher.fetchUnstartedMatchIds());
        System.out.println("Toplam " + ids.size() + " adet başlamamış maç ID'si bulundu.");
        return ids;
    }

    // ─────────────────────────────────────────────────────────────
    // Maç Analizi
    // ─────────────────────────────────────────────────────────────
    /**
     * AllInOneTactics için tek maçlık giriş noktası. Aynı analiz çalışır; tek fark
     * sonucun global listeye yazılmak yerine geri döndürülmesidir. Sinyal yoksa null.
     */
    public static String analyzeSingleMatch(String matchId) {
        try {
            return analyzeMatchInternal(matchId);
        } catch (Exception e) {
            return null;
        }
    }

    /** main() akışı: sonucu global listeye ekler. */
    private static void analyzeMatch(String matchId) {
        String signal = analyzeMatchInternal(matchId);
        if (signal != null) matchedPatterns.add(signal);
    }

    /** Tek maçın analizi; eşik aşılmazsa null. */
    private static String analyzeMatchInternal(String matchId) {
        int maxRetry = 3;
        int attempt = 0;

        while (attempt < maxRetry) {
            attempt++;
            try {
                semaphore.acquire();
                try {
                    String url = "https://arsiv.mackolik.com/Match/Head2Head.aspx?id=" + matchId + "&s=1";
                    String matchUrl = "https://arsiv.mackolik.com/Mac/" + matchId + "/";

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("User-Agent", USER_AGENT)
                            .timeout(Duration.ofSeconds(30))
                            .GET()
                            .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    Document doc = Jsoup.parse(response.body());

                    Elements forms = doc.select("div.md:has(div.detail-title:contains(Form Durumu))");
                    if (forms.size() < 2) return null;

                    TableAnalysis home = parseForm(forms.get(0), matchId);
                    TableAnalysis away = parseForm(forms.get(1), matchId);

                    MatchResult result = new MatchResult(matchUrl, home.teamName, away.teamName);
                    checkPatterns(home, away, result);
                    checkTriplePattern(home, away, result);
                    return renderResult(result);

                } finally {
                    semaphore.release();
                }

            } catch (java.net.http.HttpTimeoutException e) {
                if (attempt < maxRetry) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // Form Tablosunu Parse Et (GÜNCELLENDİ)
    // ─────────────────────────────────────────────────────────────
    private static TableAnalysis parseForm(Element container, String targetId) {
        String fullTitle = container.select(".detail-title").text();
        String teamName;
        if (fullTitle.contains("-")) {
            teamName = fullTitle.split("-")[0].trim();
        } else {
            teamName = fullTitle.replace("Form Durumu", "").trim();
        }

        Elements rows = container.select("tr.row, tr.row-2");
        List<PastMatch> pastMatches = new ArrayList<>();
        String upcomingLeague = null;

        // Eski pattern için hala lazım
        int targetIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Element row = rows.get(i);
            Elements scoreLinks = row.select("td:nth-child(4) a");
            if (scoreLinks.isEmpty()) continue;

            String href = scoreLinks.first().attr("href");
            boolean isTarget = href.contains(targetId);
            if (isTarget) targetIndex = i;

            // Lig kısaltması
            String league = row.select("td:first-child a").text().trim();

            // Tarih
            String date = row.select("td:nth-child(2)").text().trim();

            // Ev sahibi ve deplasman
            Element homeElem = row.select("td:nth-child(3) a[href*=/Takim/], td:nth-child(3) span.team").first();
            Element awayElem = row.select("td:nth-child(5) a[href*=/Takim/], td:nth-child(5) span.team").first();
            if (homeElem == null || awayElem == null) continue;
            String homeTeam = homeElem.text().trim();
            String awayTeam = awayElem.text().trim();

            // Skor ve tamamlanma durumu
            String scoreText = scoreLinks.first().text().trim();
            boolean completed = scoreText.matches(".*\\d+.*-.*\\d+.*");

            // Maç ID'sini skor linkinden al
            String matchId = "";
            Matcher idMatcher = Pattern.compile("/Mac/(\\d+)/").matcher(href);
            if (idMatcher.find()) matchId = idMatcher.group(1);

            PastMatch pm = new PastMatch(league, date, matchId, homeTeam, awayTeam, scoreText, completed);
            pastMatches.add(pm);

            // Eğer bu satır analiz edilen maç ise ve henüz oynanmamışsa ligini al
            if (isTarget && !completed) {
                upcomingLeague = league;
            }
        }

        // Eski patternler için çapraz rakamlar (opsiyonel olarak hala kullanılıyor)
        String prev2 = null, prev1 = null, next1 = null, next2 = null;
        if (targetIndex != -1) {
            prev2 = getOpponentFromRow(rows, targetIndex - 2);
            prev1 = getOpponentFromRow(rows, targetIndex - 1);
            next1 = getOpponentFromRow(rows, targetIndex + 1);
            next2 = getOpponentFromRow(rows, targetIndex + 2);
        }

        // Bağımsız skor serisi için son 2 tamamlanmış skor
        List<String> playedScores = new ArrayList<>();
        for (PastMatch pm : pastMatches) {
            if (pm.completed) {
                playedScores.add(pm.score);
            }
        }
        String last2Score = playedScores.size() >= 2 ? playedScores.get(playedScores.size() - 2) : null;
        String last1Score = playedScores.size() >= 2 ? playedScores.get(playedScores.size() - 1) : null;

        return new TableAnalysis(teamName, prev2, prev1, next1, next2, last2Score, last1Score, pastMatches, upcomingLeague);
    }

    private static String getOpponentFromRow(Elements rows, int index) {
        if (index < 0 || index >= rows.size()) return null;
        Elements links = rows.get(index).select(
                "td:nth-child(3) a[href*=/Takim/], td:nth-child(5) a[href*=/Takim/]");
        return links.isEmpty() ? null : links.first().text().trim();
    }

    private static boolean isScoreMatch(String actualScore, String... acceptedScores) {
        if (actualScore == null) return false;
        String cleanScore = actualScore.replace(" ", "");
        for (String accepted : acceptedScores) {
            if (cleanScore.equals(accepted)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Pattern Kontrolleri: 🎯 Bağımsız Skor Serisi + 🔥 Kusursuz X Çapraz
    // ─────────────────────────────────────────────────────────────
    private static void checkPatterns(TableAnalysis h, TableAnalysis a, MatchResult r) {
        // 🎯 Bağımsız skor serisi
        boolean homeSequenceMatch = isScoreMatch(h.last2Score, "1-0", "0-1") && isScoreMatch(h.last1Score, "2-1", "1-2");
        boolean awaySequenceMatch = isScoreMatch(a.last2Score, "1-0", "0-1") && isScoreMatch(a.last1Score, "2-1", "1-2");

        if (homeSequenceMatch) {
            r.foundSequence = true;
            r.details.add("🎯 BAĞIMSIZ SKOR SERİSİ (EV SAHİBİ): Maç 1: " + h.last2Score + " | Maç 2: " + h.last1Score
                    + " — Ev Sahibinin son 2 maçı sırasıyla (1-0 / 0-1) ve ardından (2-1 / 1-2) bitti.");
        }
        if (awaySequenceMatch) {
            r.foundSequence = true;
            r.details.add("🎯 BAĞIMSIZ SKOR SERİSİ (DEPLASMAN): Maç 1: " + a.last2Score + " | Maç 2: " + a.last1Score
                    + " — Deplasmanın son 2 maçı sırasıyla (1-0 / 0-1) ve ardından (2-1 / 1-2) bitti.");
        }

        // 🔥 Kusursuz X Çapraz
        boolean c1A = h.prev1 != null && h.prev1.equals(a.next1);
        boolean c1B = h.next1 != null && h.next1.equals(a.prev1);
        boolean c2A = h.prev2 != null && h.prev2.equals(a.next2);
        boolean c2B = h.next2 != null && h.next2.equals(a.prev2);

        if (c1A && c1B) {
            r.foundCross = true;
            r.details.add("🔥 KUSURSUZ X ÇAPRAZ (Mesafe 1): Ev[-1] = Dep[+1] VE Ev[+1] = Dep[-1] — Eşleşme: "
                    + Set.of(h.prev1, h.next1));
        }
        if (c2A && c2B) {
            r.foundCross = true;
            r.details.add("🔥 KUSURSUZ X ÇAPRAZ (Mesafe 2): Ev[-2] = Dep[+2] VE Ev[+2] = Dep[-2] — Eşleşme: "
                    + Set.of(h.prev2, h.next2));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // YENİ ÜÇLÜ AĞ PATTERNİ
    // ─────────────────────────────────────────────────────────────
    private static void checkTriplePattern(TableAnalysis home, TableAnalysis away, MatchResult r) {
        // Lig tanımlı değilse çık
        if (home.upcomingLeague == null || away.upcomingLeague == null) return;
        if (!home.upcomingLeague.equals(away.upcomingLeague)) return;
        String league = home.upcomingLeague;   // Aynı lig

        // Aynı ligdeki tamamlanmış maçları filtrele
        List<PastMatch> homeCompleted = home.pastMatches.stream()
                .filter(pm -> pm.completed && pm.league.equals(league))
                .toList();
        List<PastMatch> awayCompleted = away.pastMatches.stream()
                .filter(pm -> pm.completed && pm.league.equals(league))
                .toList();

        // Ev = Z (sonra X ile oynar), Dep = Y (önce X ile oynar)
        // Ortak rakip adayları
        Set<String> homeOpponents = new HashSet<>();
        for (PastMatch pm : homeCompleted) {
            String opp = pm.homeTeam.equals(home.teamName) ? pm.awayTeam : pm.homeTeam;
            homeOpponents.add(opp);
        }
        Set<String> awayOpponents = new HashSet<>();
        for (PastMatch pm : awayCompleted) {
            String opp = pm.homeTeam.equals(away.teamName) ? pm.awayTeam : pm.homeTeam;
            awayOpponents.add(opp);
        }

        Set<String> commonX = new HashSet<>(homeOpponents);
        commonX.retainAll(awayOpponents);

        for (String x : commonX) {
            // Y'nin X ile maçı (dep'in, yani awayCompleted'da)
            PastMatch matchY_X = null;
            int indexY = -1;
            for (int i = 0; i < awayCompleted.size(); i++) {
                PastMatch pm = awayCompleted.get(i);
                String opp = pm.homeTeam.equals(away.teamName) ? pm.awayTeam : pm.homeTeam;
                if (opp.equals(x)) {
                    matchY_X = pm;
                    indexY = i;
                    break;
                }
            }
            // Z'nin X ile maçı (ev'in, yani homeCompleted'da)
            PastMatch matchZ_X = null;
            int indexZ = -1;
            for (int i = 0; i < homeCompleted.size(); i++) {
                PastMatch pm = homeCompleted.get(i);
                String opp = pm.homeTeam.equals(home.teamName) ? pm.awayTeam : pm.homeTeam;
                if (opp.equals(x)) {
                    matchZ_X = pm;
                    indexZ = i;
                    break;
                }
            }

            // Y-X, Z-X'ten önce oynanmış olmalı (indeksleri küçük olan daha eski)
            if (matchY_X == null || matchZ_X == null) continue;
            if (indexY >= indexZ) continue;   // Y-X daha eski değilse olmaz

            // X takımının formunu, Z-X maçının sayfasından kontrol et
            if (matchZ_X.matchId.isEmpty()) continue;
            List<PastMatch> xPastMatches = fetchTeamPastMatches(matchZ_X.matchId, x);
            if (xPastMatches == null) continue;

            // X'in aynı ligdeki tamamlanmış maçları (kronolojik sıralı)
            List<PastMatch> xLeagueMatches = xPastMatches.stream()
                    .filter(pm -> pm.completed && pm.league.equals(league))
                    .toList();
            if (xLeagueMatches.size() < 2) continue;

            // Son iki lig maçı (en güncel ikisi)
            PastMatch lastMatch = xLeagueMatches.get(xLeagueMatches.size() - 1);
            PastMatch secondLast = xLeagueMatches.get(xLeagueMatches.size() - 2);

            String oppLast = lastMatch.homeTeam.equals(x) ? lastMatch.awayTeam : lastMatch.homeTeam;
            String oppSecond = secondLast.homeTeam.equals(x) ? secondLast.awayTeam : secondLast.homeTeam;

            // Sırasıyla önce Y'ye, sonra Z'ye karşı oynamış olmalı
            if (oppSecond.equals(away.teamName) && oppLast.equals(home.teamName)) {
                r.foundTriple = true;
                r.details.add(String.format(
                        "🧩 ÜÇLÜ AĞ PATTERNİ (Lig: %s): %s → %s (önce), %s → %s (sonra), şimdi %s - %s oynanacak.",
                        league, x, away.teamName, x, home.teamName, home.teamName, away.teamName));
            }
        }
    }

    // Belirli bir maçın Head2Head sayfasından, verilen takımın formundaki PastMatch listesini döner
    private static List<PastMatch> fetchTeamPastMatches(String matchId, String teamName) {
        try {
            String url = "https://arsiv.mackolik.com/Match/Head2Head.aspx?id=" + matchId + "&s=1";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            Document doc = Jsoup.parse(resp.body());

            Elements forms = doc.select("div.md:has(div.detail-title:contains(Form Durumu))");
            for (Element form : forms) {
                String title = form.select(".detail-title").text();
                // Takım adı başlıkta geçiyorsa bu form X'indir
                if (title.contains(teamName)) {
                    // parseForm mantığını kullanalım ama sadece pastMatches için
                    Elements rows = form.select("tr.row, tr.row-2");
                    List<PastMatch> list = new ArrayList<>();
                    for (Element row : rows) {
                        Elements scoreLinks = row.select("td:nth-child(4) a");
                        if (scoreLinks.isEmpty()) continue;
                        String href = scoreLinks.first().attr("href");
                        String league = row.select("td:first-child a").text().trim();
                        String date = row.select("td:nth-child(2)").text().trim();
                        Element homeElem = row.select("td:nth-child(3) a[href*=/Takim/], td:nth-child(3) span.team").first();
                        Element awayElem = row.select("td:nth-child(5) a[href*=/Takim/], td:nth-child(5) span.team").first();
                        if (homeElem == null || awayElem == null) continue;
                        String homeTeam = homeElem.text().trim();
                        String awayTeam = awayElem.text().trim();
                        String scoreText = scoreLinks.first().text().trim();
                        boolean completed = scoreText.matches(".*\\d+.*-.*\\d+.*");
                        String mid = "";
                        Matcher idMatcher = Pattern.compile("/Mac/(\\d+)/").matcher(href);
                        if (idMatcher.find()) mid = idMatcher.group(1);
                        list.add(new PastMatch(league, date, mid, homeTeam, awayTeam, scoreText, completed));
                    }
                    return list;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // Yardımcı Metodlar
    // ─────────────────────────────────────────────────────────────
    // 3 yöntemden en az 2'si bulunduysa sonuca ekle; 3'ü birden bulunduysa BINGOOO!
    /** Sonucu metne çevirir; 3 yöntemden en az 2'si tutmadıysa null. */
    private static String renderResult(MatchResult r) {
        int count = (r.foundTriple ? 1 : 0) + (r.foundCross ? 1 : 0) + (r.foundSequence ? 1 : 0);
        if (count < 2) return null;

        String header = (count == 3)
                ? "🎰🎰🎰 BİNGOOO!!! 3/3 PATTERN 🎰🎰🎰"
                : "⭐ 2/3 PATTERN EŞLEŞMESİ ⭐";

        StringBuilder sb = new StringBuilder();
        sb.append(header).append("\n");
        sb.append("   Maç: ").append(r.home).append(" vs ").append(r.away).append("\n");
        sb.append("   Bulunan Yöntemler: ")
                .append(r.foundTriple ? "[🧩 ÜÇLÜ AĞ] " : "")
                .append(r.foundCross ? "[🔥 KUSURSUZ X ÇAPRAZ] " : "")
                .append(r.foundSequence ? "[🎯 BAĞIMSIZ SKOR SERİSİ] " : "")
                .append("(").append(count).append("/3)\n");
        for (String detail : r.details) {
            sb.append("   • ").append(detail).append("\n");
        }
        sb.append("   Taktik: 2/1 VEYA 1/2 Oyna!\n");
        sb.append("   Link: ").append(r.url);
        return sb.toString();
    }

    private static void printProgress(int completed, int total) {
        int percentage = (int) ((completed * 100.0) / total);
        System.out.print("\r  ✓ İlerleme: " + completed + "/" + total + " (" + percentage + "%)");
    }

    private static void printResults(long startTime) {
        System.out.println("\n\n==== BÜTÜN MAÇLARIN ANALİZİ TAMAMLANDI ====");
        System.out.println("\n=======================================================");
        System.out.println("🔥 SONUÇ: EN AZ 2/3 YÖNTEME UYAN MAÇLAR 🔥");
        System.out.println("=======================================================");

        if (matchedPatterns.isEmpty()) {
            System.out.println("❌ Maalesef bugün için en az 2 yönteme birden uyan maç bulunamadı.");
        } else {
            // BINGOOO (3/3) olanlar en üstte gösterilsin
            List<String> sorted = new ArrayList<>(matchedPatterns);
            sorted.sort((s1, s2) -> Boolean.compare(s2.startsWith("🎰"), s1.startsWith("🎰")));

            long bingoCount = sorted.stream().filter(s -> s.startsWith("🎰")).count();

            for (String result : sorted) {
                System.out.println(result);
                System.out.println("-------------------------------------------------------");
            }
            System.out.println("\n✅ Toplam Bulunan Maç Sayısı: " + sorted.size()
                    + " (🎰 BINGOOO: " + bingoCount + " | ⭐ 2/3: " + (sorted.size() - bingoCount) + ")");
        }
        System.out.println("=======================================================\n");

        long elapsed = System.currentTimeMillis() - startTime;
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (minutes > 0) {
            System.out.println("⏱️  Toplam çalışma süresi: " + minutes + " dakika " + secs + " saniye.");
        } else {
            System.out.println("⏱️  Toplam çalışma süresi: " + secs + " saniye.");
        }
        System.out.println("✓ Sistem başarılı şekilde kapatıldı.\n");
    }

    // ─────────────────────────────────────────────────────────────
    // Veri Modelleri
    // ─────────────────────────────────────────────────────────────
    // Bir maçta 3 yöntemden hangilerinin bulunduğunu toplar
    static class MatchResult {
        final String url;
        final String home;
        final String away;
        boolean foundTriple;    // 🧩 Üçlü Ağ Patterni
        boolean foundCross;     // 🔥 Kusursuz X Çapraz
        boolean foundSequence;  // 🎯 Bağımsız Skor Serisi
        final List<String> details = new ArrayList<>();

        MatchResult(String url, String home, String away) {
            this.url = url;
            this.home = home;
            this.away = away;
        }
    }

    static class PastMatch {
        String league;
        String date;
        String matchId;
        String homeTeam;
        String awayTeam;
        String score;
        boolean completed;

        PastMatch(String league, String date, String matchId, String homeTeam, String awayTeam, String score, boolean completed) {
            this.league = league;
            this.date = date;
            this.matchId = matchId;
            this.homeTeam = homeTeam;
            this.awayTeam = awayTeam;
            this.score = score;
            this.completed = completed;
        }
    }

    static class TableAnalysis {
        String teamName;
        String prev2, prev1, next1, next2;
        String last2Score, last1Score;
        Set<String> prevOpponents = new HashSet<>();
        Set<String> nextOpponents = new HashSet<>();
        List<PastMatch> pastMatches;      // YENİ
        String upcomingLeague;            // YENİ

        TableAnalysis(String teamName, String prev2, String prev1, String next1, String next2, String last2Score, String last1Score,
                      List<PastMatch> pastMatches, String upcomingLeague) {
            this.teamName = teamName;
            this.prev2 = prev2;
            this.prev1 = prev1;
            this.next1 = next1;
            this.next2 = next2;
            this.last2Score = last2Score;
            this.last1Score = last1Score;
            this.pastMatches = pastMatches;
            this.upcomingLeague = upcomingLeague;
            if (prev2 != null) prevOpponents.add(prev2);
            if (prev1 != null) prevOpponents.add(prev1);
            if (next1 != null) nextOpponents.add(next1);
            if (next2 != null) nextOpponents.add(next2);
        }
    }
}