package analyzer.mackolik.before_after_pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
    private static Set<String> fetchMatchIdsFromAPI() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            String apiUrl = "https://vd.mackolik.com/livedata?group=0";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            Pattern p = Pattern.compile("\\[(\\d{7}),");
            Matcher m = p.matcher(response.body());
            while (m.find()) ids.add(m.group(1));

            System.out.println("Toplam " + ids.size() + " adet maç ID'si bulundu.");
        } catch (Exception e) {
            System.err.println("❌ API Hatası: " + e.getMessage());
        }
        return ids;
    }

    // ─────────────────────────────────────────────────────────────
    // Retry mekanizması ile maç analizi
    // ─────────────────────────────────────────────────────────────
    private static void analyzeMatch(String matchId) {
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
                    if (forms.size() < 2) return;

                    TableAnalysis home = parseForm(forms.get(0), matchId);
                    TableAnalysis away = parseForm(forms.get(1), matchId);

                    checkPatterns(home, away, matchUrl);
                    return;

                } finally {
                    semaphore.release();
                }

            } catch (java.net.http.HttpTimeoutException e) {
                if (attempt < maxRetry) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                return;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Form Tablosunu Parse Et
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

        int targetIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Elements scoreLinks = rows.get(i).select("td:nth-child(4) a");
            if (!scoreLinks.isEmpty()
                && scoreLinks.first().attr("href").contains(targetId)) {
                targetIndex = i;
                break;
            }
        }

        String prev2 = null, prev1 = null, next1 = null, next2 = null;
        String prev2Score = null, prev1Score = null; // YENİ ÖZELLİK İÇİN DEĞİŞKENLER

        if (targetIndex != -1) {
            prev2 = getOpponentFromRow(rows, targetIndex - 2);
            prev1 = getOpponentFromRow(rows, targetIndex - 1);
            next1 = getOpponentFromRow(rows, targetIndex + 1);
            next2 = getOpponentFromRow(rows, targetIndex + 2);

            // YENİ ÖZELLİK: İlgili satırlardan skorları çekiyoruz
            prev2Score = getScoreFromRow(rows, targetIndex - 2);
            prev1Score = getScoreFromRow(rows, targetIndex - 1);
        }

        return new TableAnalysis(teamName, prev2, prev1, next1, next2, prev2Score, prev1Score);
    }

    private static String getOpponentFromRow(Elements rows, int index) {
        if (index < 0 || index >= rows.size()) return null;
        Elements links = rows.get(index).select(
                "td:nth-child(3) a[href*=/Takim/], td:nth-child(5) a[href*=/Takim/]");
        return links.isEmpty() ? null : links.first().text().trim();
    }

    // YENİ ÖZELLİK: HTML Satırından Skoru Çeken Yardımcı Metot
    private static String getScoreFromRow(Elements rows, int index) {
        if (index < 0 || index >= rows.size()) return null;
        Elements scoreLinks = rows.get(index).select("td:nth-child(4) a");
        return scoreLinks.isEmpty() ? null : scoreLinks.first().text().trim();
    }

    // YENİ ÖZELLİK: Skorun İstenen Skora Uyup Uymadığını Kontrol Eden Metot (Boşlukları silerek tam eşleşme arar)
    private static boolean isScoreMatch(String actualScore, String... acceptedScores) {
        if (actualScore == null) return false;
        String cleanScore = actualScore.replace(" ", ""); // Örneğin "1 - 0" skorunu "1-0" yapar
        for (String accepted : acceptedScores) {
            if (cleanScore.equals(accepted)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Pattern Kontrolleri
    // ─────────────────────────────────────────────────────────────
    private static void checkPatterns(TableAnalysis h, TableAnalysis a, String url) {

        // =========================================================================
        // 🎯 YENİ ÖZELLİK: ARDIŞIK SKOR PATTERNI (Önce 1-0/0-1 sonra 2-1/1-2)
        // =========================================================================
        boolean homeSequenceMatch = isScoreMatch(h.prev2Score, "1-0", "0-1") && isScoreMatch(h.prev1Score, "2-1", "1-2");
        boolean awaySequenceMatch = isScoreMatch(a.prev2Score, "1-0", "0-1") && isScoreMatch(a.prev1Score, "2-1", "1-2");

        if (homeSequenceMatch) {
            recordMatch(url, h.teamName, a.teamName, Set.of(h.prev2Score + " ardından " + h.prev1Score),
                    "🎯 SKOR SERİSİ (EV SAHİBİ)", "Ev Sahibinin son 2 maçı sırasıyla (1-0 / 0-1) ve ardından (2-1 / 1-2) bitti.", "🎯");
        }
        if (awaySequenceMatch) {
            recordMatch(url, h.teamName, a.teamName, Set.of(a.prev2Score + " ardından " + a.prev1Score),
                    "🎯 SKOR SERİSİ (DEPLASMAN)", "Deplasmanın son 2 maçı sırasıyla (1-0 / 0-1) ve ardından (2-1 / 1-2) bitti.", "🎯");
        }
        // =========================================================================

        // ESKİ PATTERNLER
        Set<String> matchType1 = new HashSet<>(h.prevOpponents);
        matchType1.retainAll(a.nextOpponents);

        Set<String> matchType2 = new HashSet<>(h.nextOpponents);
        matchType2.retainAll(a.prevOpponents);

        if (matchType1.size() >= 2)
            recordMatch(url, h.teamName, a.teamName, matchType1,
                    "BİLGİ-3 (Havuz Eşleşmesi)", "A'nın Önceki 2 Maçı = B'nin Sonraki 2 Maçı", "🟢");
        if (matchType2.size() >= 2)
            recordMatch(url, h.teamName, a.teamName, matchType2,
                    "BİLGİ-3 (Havuz Eşleşmesi)", "A'nın Sonraki 2 Maçı = B'nin Önceki 2 Maçı", "🟢");

        boolean c1A = h.prev1 != null && h.prev1.equals(a.next1);
        boolean c1B = h.next1 != null && h.next1.equals(a.prev1);
        boolean c2A = h.prev2 != null && h.prev2.equals(a.next2);
        boolean c2B = h.next2 != null && h.next2.equals(a.prev2);

        if (c1A && c1B) {
            recordMatch(url, h.teamName, a.teamName, Set.of(h.prev1, h.next1),
                    "🔥 KUSURSUZ X ÇAPRAZ (Mesafe 1)", "Ev[-1] = Dep[+1] VE Ev[+1] = Dep[-1]", "🔥");
        } else {
            if (c1A) recordMatch(url, h.teamName, a.teamName, Set.of(h.prev1),
                    "YENİ PATTERN (1. Mesafe Çapraz)", "Ev Sahibinin 1 Önceki Rakibi [-1] = Deplasmanın 1 Sonraki Rakibi [+1]", "🔵");
            if (c1B) recordMatch(url, h.teamName, a.teamName, Set.of(h.next1),
                    "YENİ PATTERN (1. Mesafe Çapraz)", "Ev Sahibinin 1 Sonraki Rakibi [+1] = Deplasmanın 1 Önceki Rakibi [-1]", "🔵");
        }

        if (c2A && c2B) {
            recordMatch(url, h.teamName, a.teamName, Set.of(h.prev2, h.next2),
                    "🔥 KUSURSUZ X ÇAPRAZ (Mesafe 2)", "Ev[-2] = Dep[+2] VE Ev[+2] = Dep[-2]", "🔥");
        } else {
            if (c2A) recordMatch(url, h.teamName, a.teamName, Set.of(h.prev2),
                    "YENİ PATTERN (2. Mesafe Çapraz)", "Ev Sahibinin 2 Önceki Rakibi [-2] = Deplasmanın 2 Sonraki Rakibi [+2]", "🔵");
            if (c2B) recordMatch(url, h.teamName, a.teamName, Set.of(h.next2),
                    "YENİ PATTERN (2. Mesafe Çapraz)", "Ev Sahibinin 2 Sonraki Rakibi [+2] = Deplasmanın 2 Önceki Rakibi [-2]", "🔵");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Yardımcı Metodlar
    // ─────────────────────────────────────────────────────────────
    private static void recordMatch(String url, String home, String away,
                                    Set<String> common, String patternName,
                                    String matchDesc, String emoji) {
        String resultMsg = String.format(
                "%s [%s] %s vs %s\n   Açıklama: %s\n   Eşleşen Takım/Veri: %s\n   Taktik: 2/1 VEYA 1/2 Oyna!\n   Link: %s",
                emoji, patternName, home, away, matchDesc, common, url);
        matchedPatterns.add(resultMsg);
    }

    private static void printProgress(int completed, int total) {
        int percentage = (int) ((completed * 100.0) / total);
        System.out.print("\r  ✓ İlerleme: " + completed + "/" + total + " (" + percentage + "%)");
    }

    private static void printResults(long startTime) {
        System.out.println("\n\n==== BÜTÜN MAÇLARIN ANALİZİ TAMAMLANDI ====");
        System.out.println("\n=======================================================");
        System.out.println("🔥 SONUÇ: SİSTEM TAKTİKLERİNE UYAN MAÇLAR 🔥");
        System.out.println("=======================================================");

        if (matchedPatterns.isEmpty()) {
            System.out.println("❌ Maalesef bugün için patternlere uyan maç bulunamadı.");
        } else {
            for (String result : matchedPatterns) {
                System.out.println(result);
                System.out.println("-------------------------------------------------------");
            }
            System.out.println("\n✅ Toplam Bulunan Sinyal Sayısı: " + matchedPatterns.size());
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
    // Veri Modeli
    // ─────────────────────────────────────────────────────────────
    static class TableAnalysis {
        String teamName;
        String prev2, prev1, next1, next2;
        String prev2Score, prev1Score; // YENİ ÖZELLİK: Skor Değişkenleri
        Set<String> prevOpponents = new HashSet<>();
        Set<String> nextOpponents = new HashSet<>();

        TableAnalysis(String teamName, String prev2, String prev1, String next1, String next2, String prev2Score, String prev1Score) {
            this.teamName = teamName;
            this.prev2 = prev2;
            this.prev1 = prev1;
            this.next1 = next1;
            this.next2 = next2;
            this.prev2Score = prev2Score;
            this.prev1Score = prev1Score;
            if (prev2 != null) prevOpponents.add(prev2);
            if (prev1 != null) prevOpponents.add(prev1);
            if (next1 != null) nextOpponents.add(next1);
            if (next2 != null) nextOpponents.add(next2);
        }
    }
}