package analyzer.mackolik.patternfinder;

import analyzer.util.TeamIdsFetcher;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;

/**
 * Tüm takımları sanal thread'lerle paralel işler.
 *
 * Her takım için 3 ayrı pattern boyutu (3, 4, 5) çalışır.
 * Her boyut kendi raporunu üretir.
 *
 * Console çıktısı:
 *   İDEAL EŞLEŞME → ★ işaretiyle
 *   HAVUZ EŞLEŞME → ◈ işaretiyle
 */
public class OnlyLeagueVirtualThreadedAnalyzer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OnlyLeagueVirtualThreadedAnalyzer.class);

    // Aranacak geçmiş sezon aralığı (kapsayıcı, azalan sırayla)
    private static final int START_YEAR = 2024;
    private static final int END_YEAR   = 2010;

    // Bağlantı havuzu
    private static final int CONNECTION_POOL_SIZE = 1000;

    // Çalıştırılacak pattern boyutları
    private static final int[] PATTERN_SIZES = {3, 4, 5};

    // ═══════════════════════════════════════════════════════════════════════════
    //  Görev
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * AllInOneTactics ucun tek komandaliq giris noktasi: eyni tapsiriq, sadece
     * havuz/ExecutorService olmadan. Signal yoxdursa null.
     */
    public static String analyzeSingleTeam(CloseableHttpClient httpClient, int teamId) {
        try {
            return new TeamProcessorTask(teamId, httpClient).call();
        } catch (Exception e) {
            return null;
        }
    }

    private static class TeamProcessorTask implements Callable<String> {

        private final int teamId;
        private final CloseableHttpClient httpClient;

        TeamProcessorTask(int teamId, CloseableHttpClient httpClient) {
            this.teamId     = teamId;
            this.httpClient = httpClient;
        }

        @Override
        public String call() {
            long startTime = System.currentTimeMillis();
            LOGGER.info("[START] Takım ID: {}", teamId);

            StringBuilder teamReport = new StringBuilder();

            for (int patternSize : PATTERN_SIZES) {
                String singleReport = processForSize(patternSize);
                if (singleReport != null) {
                    teamReport.append(singleReport);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            LOGGER.info("[END] Takım ID: {} → {} ms", teamId, duration);

            String result = teamReport.toString().trim();
            return result.isEmpty() ? null : result;
        }

        /**
         * Belirli bir pattern boyutu için:
         *   1. Mevcut sezondaki son N maçı çek → pattern oluştur
         *   2. Geçmiş sezonlarda bu pattern'i ara
         *   3. Bulunan sonuçları formatla
         */
        private String processForSize(int patternSize) {
            MatchPattern currentPattern;
            try {
                currentPattern = OnlyLeagueScraper.findCurrentSeasonPattern(
                        httpClient, teamId, patternSize);
            } catch (Exception e) {
                LOGGER.warn("[SKIP-{}] Takım ID {}: {}",
                        patternSize, teamId, e.getMessage());
                return null;
            }

            LOGGER.info("[PATTERN-{}] Takım '{}' (ID: {})",
                    patternSize, currentPattern.teamName, teamId);

            // Geçmiş sezonlarda ara
            Map<Integer, List<MatchResult>> allFound = searchPastSeasons(currentPattern);

            if (allFound.isEmpty()) {
                LOGGER.info("[NO-MATCH-{}] Takım '{}' (ID: {})",
                        patternSize, currentPattern.teamName, teamId);
                return null;
            }

            long total = allFound.values().stream().mapToLong(List::size).sum();
            LOGGER.info("[SUCCESS-{}] {} eşleşme — Takım '{}' (ID: {})",
                    patternSize, total, currentPattern.teamName, teamId);

            return formatReport(patternSize, currentPattern, allFound);
        }

        private Map<Integer, List<MatchResult>> searchPastSeasons(MatchPattern pattern) {
            Map<Integer, List<MatchResult>> found = new TreeMap<>();
            for (int year = START_YEAR; year >= END_YEAR; year--) {
                String season = year + "/" + (year + 1);
                try {
                    List<MatchResult> results = OnlyLeagueScraper.findScorePattern(
                            httpClient, pattern, season, teamId);
                    if (results != null && !results.isEmpty()) {
                        LOGGER.info("  {} sezonu → {} eşleşme (takım ID: {})",
                                season, results.size(), teamId);
                        found.put(year, results);
                    }
                } catch (IOException e) {
                    LOGGER.error("  IOException — sezon {} takım ID {}", season, teamId, e);
                }
            }
            return found;
        }

        private String formatReport(int patternSize,
                                    MatchPattern pattern,
                                    Map<Integer, List<MatchResult>> allFound) {
            StringBuilder sb = new StringBuilder();

            sb.append(String.format(
                    "\n╔══════════════════════════════════════════════════════╗\n" +
                            "║  %d'lü Pattern  —  Takım ID: %-5d  (%s)\n" +
                            "╚══════════════════════════════════════════════════════╝\n",
                    patternSize, teamId, pattern.teamName));

            sb.append("Aranan pattern:\n").append(pattern.toString()).append("\n\n");

            // İDEAL ve HAVUZ ayrı grupla
            List<MatchResult> idealList = new ArrayList<>();
            List<MatchResult> poolList  = new ArrayList<>();

            for (List<MatchResult> results : allFound.values()) {
                for (MatchResult mr : results) {
                    if ("İDEAL".equals(mr.matchType)) idealList.add(mr);
                    else                              poolList.add(mr);
                }
            }

            if (!idealList.isEmpty()) {
                sb.append("─── ★ İDEAL EŞLEŞMELER (" + idealList.size() + " adet) ───\n");
                for (MatchResult mr : idealList) {
                    sb.append(mr.toString()).append("\n\n");
                }
            }

            if (!poolList.isEmpty()) {
                sb.append("─── ◈ HAVUZ EŞLEŞMELER (" + poolList.size() + " adet) ───\n");
                for (MatchResult mr : poolList) {
                    sb.append(mr.toString()).append("\n\n");
                }
            }

            return sb.toString();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  main
    // ═══════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {

        List<String> teamIds = TeamIdsFetcher.fetchUnstartedTeamIds();
        if (teamIds.isEmpty()) {
            LOGGER.warn("Takım ID listesi boş. Çıkılıyor.");
            return;
        }
        LOGGER.info("{} takım ID'si yüklendi.", teamIds.size());

        // HTTP bağlantı havuzu
        PoolingHttpClientConnectionManager cm =
                new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(CONNECTION_POOL_SIZE + 10);
        cm.setDefaultMaxPerRoute(CONNECTION_POOL_SIZE);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(15000)
                .setSocketTimeout(30000)
                .setConnectionRequestTimeout(5000)
                .build();

        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .build()) {

            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CompletionService<String> completionService =
                    new ExecutorCompletionService<>(executor);

            long startTime = System.currentTimeMillis();
            int submitted  = 0;

            for (String idStr : teamIds) {
                try {
                    int id = Integer.parseInt(idStr.trim());
                    completionService.submit(new TeamProcessorTask(id, httpClient));
                    submitted++;
                } catch (NumberFormatException e) {
                    LOGGER.warn("Geçersiz ID formatı atlandı: {}", idStr);
                }
            }

            LOGGER.info("{} görev gönderildi. Sonuçlar bekleniyor...", submitted);
            System.out.println(
                    "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                            "   İşlem Başladı — Eşleşmeler bulundukça gösterilir\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            int foundCount = 0;

            for (int i = 0; i < submitted; i++) {
                try {
                    Future<String> future = completionService.take();
                    String result = future.get();

                    if (result != null && !result.isBlank()) {
                        System.out.println(result);
                        System.out.println(
                                "═══════════════════════════════════════════════════\n");
                        foundCount++;
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.error("Ana thread kesildi.", e);
                    break;
                } catch (ExecutionException e) {
                    LOGGER.error("Görev hata verdi.", e.getCause());
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            System.out.printf(
                    "%n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%n" +
                            "   Tüm işlemler tamamlandı — %.1f sn%n" +
                            "   %d / %d takımda eşleşme bulundu%n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%n",
                    duration / 1000.0, foundCount, submitted);

            LOGGER.info("Tamamlandı: {}ms, eşleşme: {}/{}", duration, foundCount, submitted);

            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS))
                    executor.shutdownNow();
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }

        } catch (IOException e) {
            LOGGER.error("HttpClient lifecycle hatası.", e);
        }
    }
}