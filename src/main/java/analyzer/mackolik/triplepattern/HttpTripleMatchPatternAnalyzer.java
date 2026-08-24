package analyzer.mackolik.triplepattern;

import analyzer.util.TeamIdsFetcher;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class HttpTripleMatchPatternAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(HttpTripleMatchPatternAnalyzer.class);
    private static final int START_YEAR = 2024;
    private static final int END_YEAR = 2014;
    private static final int NUM_THREADS = 10;

    /** AllInOneTactics ucun tek komandaliq giris noktasi; signal yoxdursa null. */
    public static String analyzeSingleTeam(CloseableHttpClient httpClient, int teamId) {
        try {
            return new TeamTriplePatternTask(teamId, httpClient).call();
        } catch (Exception e) {
            return null;
        }
    }

    private static class TeamTriplePatternTask implements Callable<String> {
        private final int teamId;
        private final CloseableHttpClient httpClient;

        public TeamTriplePatternTask(int teamId, CloseableHttpClient httpClient) {
            this.teamId = teamId;
            this.httpClient = httpClient;
        }

        @Override
        public String call() throws Exception {
            log.info("Processing Team ID: {}", teamId);
            try {
                // 1. Find current season's last three matches
                ThreeMatchPattern currentPattern = HttpTripleScoreFetcher.findCurrentSeasonLastThreeMatches(httpClient, teamId);
                if (currentPattern == null) {
                    log.warn("Could not find current triple pattern for team ID: {}", teamId);
                    return null;
                }

                StringBuilder teamResults = new StringBuilder();
                boolean foundMatchesForTeam = false;

                // 2. Search past seasons for the pattern
                for (int year = START_YEAR; year >= END_YEAR; year--) {
                    String season = year + "/" + (year + 1);
                    log.debug("Searching team ID {} in season {}", teamId, season);
                    try {
                        List<TripleMatchResult> seasonMatches = HttpTripleScoreFetcher.findTripleScorePattern(httpClient, currentPattern, season, teamId);

                        if (!seasonMatches.isEmpty()) {
                            foundMatchesForTeam = true;
                            teamResults.append("\n").append(year).append(" Sezonu Analizi:\n------------------------\n");
                            for (TripleMatchResult match : seasonMatches) {
                                teamResults.append(match).append("\n\n");
                            }
                            if (teamResults.length() > 1) {
                                teamResults.setLength(teamResults.length() - 2);
                            }
                        }
                    } catch (IOException e) {
                        log.error("IOException while searching team {} season {}: {}", teamId, season, e.getMessage());
                    } catch (Exception e) {
                        log.error("Unexpected error processing team {} season {}: {}", teamId, season, e.getMessage(), e);
                    }
                }

                // 3. Format output if matches were found
                if (foundMatchesForTeam) {
                    String header = String.format("=== Team ID: %d (%s) ===\nAranan 3'lü skor paterni:\n%s\n",
                            teamId, currentPattern.teamName, currentPattern.toString());
                    return header + teamResults.toString();
                } else {
                    log.info("No matching triple patterns found for team ID: {}", teamId);
                    return null;
                }

            } catch (RuntimeException e) {
                log.error("Runtime error processing team ID {}: {}", teamId, e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("maç skoru bulunamadı")) {
                    log.warn("Skipping team {} due to missing initial scores.", teamId);
                } else {
                    log.error("Detailed error for team " + teamId, e);
                }
                return null;
            } catch (Exception e) {
                log.error("General error processing team ID {}: {}", teamId, e.getMessage(), e);
                return null;
            }
        }
    }

    public static void main(String[] args) {
        List<String> teamIds = TeamIdsFetcher.fetchUnstartedTeamIds();

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(NUM_THREADS + 5);
        cm.setDefaultMaxPerRoute(NUM_THREADS);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<String>> futures = new ArrayList<>();

        for (String idStr : teamIds) {
            try {
                int teamId = Integer.parseInt(idStr.trim());
                Callable<String> task = new TeamTriplePatternTask(teamId, httpClient);
                futures.add(executor.submit(task));
            } catch (NumberFormatException e) {
                log.warn("Skipping invalid team ID: {}", idStr);
            }
        }

        log.info("Submitted {} tasks to thread pool.", futures.size());

        int successCount = 0;
        int foundCount = 0;
        for (Future<String> future : futures) {
            try {
                String result = future.get();
                if (result != null && !result.isEmpty()) {
                    System.out.println(result);
                    System.out.println("====================================\n");
                    foundCount++;
                }
                successCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Main thread interrupted while waiting for task.", e);
            } catch (ExecutionException e) {
                log.error("Task execution failed: {}", e.getCause().getMessage(), e.getCause());
            }
        }

        log.info("Processing complete. {} tasks succeeded, {} teams had matching patterns.", successCount, foundCount);

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            httpClient.close();
            log.info("HttpClient closed.");
        } catch (IOException e) {
            log.error("Error closing HttpClient: {}", e.getMessage(), e);
        }

        System.exit(0);
    }
}