package analyzer.mackolik.multiversion;


import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AdvancedScoreScraper {

    private static final Logger log = LoggerFactory.getLogger(AdvancedScoreScraper.class);
    private static final String BASE_URL = "https://arsiv.mackolik.com/Team/Default.aspx?id=%d&season=%s";
    private static final String CURRENT_SEASON = "2025/2026";

    private static String fetchHtml(CloseableHttpClient httpClient, String url) throws IOException {
        HttpGet request = new HttpGet(url);
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        log.debug("Fetching URL: {}", url);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 200) {
                return EntityUtils.toString(response.getEntity());
            } else {
                log.warn("Failed to fetch URL: {}. Status code: {}", url, statusCode);
                return null;
            }
        }
    }

    /**
     * Mevcut sezondaki SONDAN N. MAÇI bulur ve bir desen olarak döndürür.
     * @param matchesBack 1 = sondan 1. maç (son maç), 2 = sondan 2. maç, vb.
     */
    public static AdvancedMatchPattern findLastMatchPattern(CloseableHttpClient httpClient, int teamId, int matchesBack) throws IOException, RuntimeException {
        String currentSeasonUrl = String.format(BASE_URL, teamId, CURRENT_SEASON);
        String html = fetchHtml(httpClient, currentSeasonUrl);
        if (html == null) throw new RuntimeException("Could not fetch current season page for team ID: " + teamId);

        Document doc = Jsoup.parse(html);
        Element tableBody = doc.selectFirst("#tblFixture > tbody");
        if (tableBody == null) throw new RuntimeException("Fixture table not found for team ID: " + teamId);

        List<String> scores = new ArrayList<>();
        List<String> homeTeams = new ArrayList<>();
        List<String> awayTeams = new ArrayList<>();
        String nextHomeTeam = null, nextAwayTeam = null;
        String teamName = doc.selectFirst("title").text().split("-")[0].trim();

        Elements rows = tableBody.select("tr");
        boolean foundUnplayed = false;

        for (Element row : rows) {
            if (row.hasClass("competition") || row.selectFirst("td[itemprop=sportsevent]") != null) continue;

            Element scoreElement = row.selectFirst("td:nth-child(5) b a");
            Element homeTeamElement = row.selectFirst("td:nth-child(3)");
            Element awayTeamElement = row.selectFirst("td:nth-child(7)");

            if (scoreElement != null && homeTeamElement != null && awayTeamElement != null) {
                String score = scoreElement.text().trim();
                String home = homeTeamElement.text().trim();
                String away = awayTeamElement.text().trim();

                if (!foundUnplayed && !score.isEmpty() && score.contains("-")) {
                    scores.add(score);
                    homeTeams.add(home);
                    awayTeams.add(away);
                } else if (!foundUnplayed) {
                    nextHomeTeam = home;
                    nextAwayTeam = away;
                    foundUnplayed = true;
                }
            }
        }

        // Oynanmış maçlar listesinden SONDAN matchesBack. MAÇI AL
        if (scores.size() >= matchesBack) {
            int targetIndex = scores.size() - matchesBack;
            String targetScore = scores.get(targetIndex);
            String targetHome = homeTeams.get(targetIndex);
            String targetAway = awayTeams.get(targetIndex);

            log.info("Baz alınan maç (sondan {}. maç) for team ID {}: {} {} {}",
                    matchesBack, teamId, targetHome, targetScore, targetAway);
            return new AdvancedMatchPattern(targetHome, targetAway, targetScore, teamName, nextHomeTeam, nextAwayTeam);
        } else {
            throw new RuntimeException(String.format("Yeterli tamamlanmış maç bulunamadı for team ID: %d (Gerekli: %d, Bulunan: %d)",
                    teamId, matchesBack, scores.size()));
        }
    }

    /**
     * Verilen deseni geçmiş sezonlarda arar.
     * Pattern bulunduğunda, ondan matchesForward MAÇ SONRAKİ SONUCU gösterir.
     * @param matchesForward Kaç maç sonrasını göstereceği (1, 2, 3, 4)
     */
    public static List<AdvancedMatchResult> findHistoricalMatchPatterns(
            CloseableHttpClient httpClient,
            AdvancedMatchPattern pattern,
            String seasonYear,
            int teamId,
            int matchesForward) throws IOException {

        List<AdvancedMatchResult> foundResults = new ArrayList<>();
        String seasonUrl = String.format(BASE_URL, teamId, seasonYear);
        String html = fetchHtml(httpClient, seasonUrl);
        if (html == null) return foundResults;

        Document doc = Jsoup.parse(html);
        Elements allTableRows = doc.select("#tblFixture > tbody > tr");

        List<Element> leagueMatches = new ArrayList<>();
        boolean collectRows = false;
        boolean isFirstLeague = true;
        // Sadece ilk ligin maçlarını topla
        for (Element row : allTableRows) {
            if (row.hasClass("competition")) {
                if (!isFirstLeague) break;
                isFirstLeague = false;
                collectRows = true;
                continue;
            }
            if (collectRows && row.selectFirst("td:nth-child(5) b a") != null) {
                leagueMatches.add(row);
            }
        }

        for (int i = 0; i < leagueMatches.size(); i++) {
            Element row = leagueMatches.get(i);
            try {
                String homeTeam = row.selectFirst("td:nth-child(3)").text().trim();
                String awayTeam = row.selectFirst("td:nth-child(7)").text().trim();
                String score = row.selectFirst("td:nth-child(5) b a").text().trim();

                // Desenle eşleşme kontrolü (takımlar ve skor aynı olmalı)
                boolean teamsMatch = (homeTeam.equals(pattern.homeTeam) && awayTeam.equals(pattern.awayTeam)) ||
                                     (homeTeam.equals(pattern.awayTeam) && awayTeam.equals(pattern.homeTeam));

                if (score.equals(pattern.score) && teamsMatch) {
                    log.info("Pattern match found for team {}, season {} -> {} {} {}", teamId, seasonYear, homeTeam, score, awayTeam);

                    String htScore = row.selectFirst("td:nth-child(9)").text().trim();
                    AdvancedMatchResult result = new AdvancedMatchResult(homeTeam, awayTeam, score, htScore, seasonYear, pattern);

                    // ÖNCEKİ MAÇI AL (pattern'den 1 maç önce)
                    if (i > 0) {
                        result.previousMatchInfo = getMatchInfo(leagueMatches.get(i - 1));
                    } else {
                        result.previousMatchInfo = "Bilgi Yok (Sezonun İlk Maçı)";
                    }

                    // matchesForward MAÇ SONRAKİ SONUCU AL (1, 2, 3, veya 4 maç sonra)
                    if (i + matchesForward < leagueMatches.size()) {
                        Element nextRow = leagueMatches.get(i + matchesForward);
                        result.nextMatchInfo = getMatchInfo(nextRow);
                        try {
                            result.nextScore = nextRow.selectFirst("td:nth-child(5) b a").text().trim();
                            result.nextHtScore = nextRow.selectFirst("td:nth-child(9)").text().trim();
                        } catch (Exception ignored) {
                        }
                        log.info("{} maç sonraki sonuç: {}", matchesForward, result.nextMatchInfo);
                    } else {
                        // Yeterli maç yoksa mevcut olanları göster
                        StringBuilder availableMatches = new StringBuilder("UYARI: ");
                        int availableCount = leagueMatches.size() - i - 1;
                        if (availableCount > 0) {
                            availableMatches.append(String.format("Sadece %d maç sonrası var -> ", availableCount));
                            for (int j = 1; j <= availableCount; j++) {
                                availableMatches.append(getMatchInfo(leagueMatches.get(i + j)));
                                if (j < availableCount) availableMatches.append(" | ");
                            }
                        } else {
                            availableMatches.append(String.format("Sezonun son maçı, %d maç sonrası yok", matchesForward));
                        }
                        result.nextMatchInfo = availableMatches.toString();
                    }

                    foundResults.add(result);
                }
            } catch (Exception e) {
                log.error("Error analyzing match row at index {} for team {}, season {}: {}", i, teamId, seasonYear, e.getMessage());
            }
        }
        return foundResults;
    }

    // Helper method to get match info from a row
    private static String getMatchInfo(Element row) {
        try {
            String home = row.selectFirst("td:nth-child(3)").text().trim();
            String away = row.selectFirst("td:nth-child(7)").text().trim();
            String score = row.selectFirst("td:nth-child(5) b a").text().trim();
            String ht = row.selectFirst("td:nth-child(9)").text().trim();
            return String.format("%s %s %s (HT: %s)", home, score, away, ht.isEmpty() ? "N/A" : ht);
        } catch (Exception e) {
            return "Hata (Okunamadı)";
        }
    }
}