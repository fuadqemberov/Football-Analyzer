package analyzer.mackolik.triplepattern;

import analyzer.util.MackolikHost;

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
import java.util.Arrays;
import java.util.List;

public class HttpTripleScoreFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpTripleScoreFetcher.class);
    private static final String BASE_URL = MackolikHost.BASE + "/Team/Default.aspx?id=%d&season=%s";
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
        } catch (IOException e) {
            log.error("IOException while fetching URL: {}", url, e);
            throw e;
        }
    }

    /**
     * Normalize a score so "2-1" and "1-2" become the same canonical form.
     * We always put the smaller number first: "1-2".
     */
    private static String normalizeScore(String score) {
        String[] parts = score.split("-");
        if (parts.length != 2) return score;
        try {
            int a = Integer.parseInt(parts[0].trim());
            int b = Integer.parseInt(parts[1].trim());
            return Math.min(a, b) + "-" + Math.max(a, b);
        } catch (NumberFormatException e) {
            return score;
        }
    }

    /**
     * Returns true if the two scores are equal when ignoring direction (2-1 == 1-2).
     */
    private static boolean scoresMatchIgnoringDirection(String s1, String s2) {
        return normalizeScore(s1).equals(normalizeScore(s2));
    }

    /**
     * Returns true if the three given scores match the pattern's three scores
     * in ANY order, with direction (home/away) also ignored.
     *
     * Example: pattern = ["2-1", "0-0", "1-3"]
     *   candidate = ["0-0", "3-1", "2-1"]  → match  (order doesn't matter, 3-1==1-3)
     */
    private static boolean matchesTriplePatternUnordered(String s1, String s2, String s3,
                                                         ThreeMatchPattern pattern) {
        // Normalize all six scores
        String c1 = normalizeScore(s1);
        String c2 = normalizeScore(s2);
        String c3 = normalizeScore(s3);

        String p1 = normalizeScore(pattern.score1);
        String p2 = normalizeScore(pattern.score2);
        String p3 = normalizeScore(pattern.score3);

        // Collect candidates and try to match each pattern score to a unique candidate
        List<String> candidates = new ArrayList<>(Arrays.asList(c1, c2, c3));
        List<String> patternScores = Arrays.asList(p1, p2, p3);

        for (String patternScore : patternScores) {
            boolean found = false;
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).equals(patternScore)) {
                    candidates.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static String reverseScore(String score) {
        String[] parts = score.split("-");
        if (parts.length == 2) return parts[1].trim() + "-" + parts[0].trim();
        return score;
    }

    // -----------------------------------------------------------------------

    public static ThreeMatchPattern findCurrentSeasonLastThreeMatches(
            CloseableHttpClient httpClient, int teamId) throws IOException {

        String currentSeasonUrl = String.format(BASE_URL, teamId, CURRENT_SEASON);
        String html = fetchHtml(httpClient, currentSeasonUrl);

        if (html == null) {
            throw new RuntimeException("Could not fetch current season page for team ID: " + teamId);
        }

        Document doc = Jsoup.parse(html);
        Element tableBody = doc.selectFirst("#tblFixture > tbody");

        if (tableBody == null) {
            log.warn("Fixture table body not found for team ID {} in season {}", teamId, CURRENT_SEASON);
            throw new RuntimeException("Fixture table not found for team ID: " + teamId);
        }

        List<String> allScores     = new ArrayList<>();
        List<String> allHomeTeams  = new ArrayList<>();
        List<String> allAwayTeams  = new ArrayList<>();
        String nextHomeTeam = null;
        String nextAwayTeam = null;
        String teamName = "Unknown";

        try {
            Element titleEl = doc.selectFirst("title");
            if (titleEl != null) {
                teamName = titleEl.text().split("-")[0].trim();
            }
        } catch (Exception e) {
            log.warn("Could not extract team name for ID {}", teamId);
        }

        Elements rows = tableBody.select("tr");
        boolean foundUnplayed = false;

        for (Element row : rows) {
            if (row.selectFirst("td[itemprop=sportsevent]") != null) break;
            if (row.hasClass("competition")) continue;

            try {
                Element scoreElement    = row.selectFirst("td:nth-child(5) b a");
                Element homeTeamElement = row.selectFirst("td:nth-child(3)");
                Element awayTeamElement = row.selectFirst("td:nth-child(7)");

                if (scoreElement != null && homeTeamElement != null && awayTeamElement != null) {
                    String score    = scoreElement.text().trim();
                    String homeTeam = homeTeamElement.text().trim();
                    String awayTeam = awayTeamElement.text().trim();

                    if (!foundUnplayed && !score.isEmpty() && !score.equalsIgnoreCase("v") && score.contains("-")) {
                        allScores.add(score);
                        allHomeTeams.add(homeTeam);
                        allAwayTeams.add(awayTeam);
                    } else if (!foundUnplayed) {
                        nextHomeTeam = homeTeam;
                        nextAwayTeam = awayTeam;
                        foundUnplayed = true;
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Error parsing a match row for team {}: {}", teamId, e.getMessage());
            }
        }

        if (allScores.size() >= 3) {
            int last = allScores.size() - 1;
            // Take the last 3 played matches (the ones BEFORE the next unplayed match)
            return new ThreeMatchPattern(
                    allScores.get(last - 2), allScores.get(last - 1), allScores.get(last),
                    allHomeTeams.get(last - 2), allAwayTeams.get(last - 2),
                    allHomeTeams.get(last - 1), allAwayTeams.get(last - 1),
                    allHomeTeams.get(last),     allAwayTeams.get(last),
                    teamName,
                    nextHomeTeam, nextAwayTeam);
        } else {
            throw new RuntimeException(
                    "Son üç maç skoru bulunamadı for team ID: " + teamId + "! Found: " + allScores.size());
        }
    }

    // -----------------------------------------------------------------------

    public static List<TripleMatchResult> findTripleScorePattern(
            CloseableHttpClient httpClient,
            ThreeMatchPattern pattern,
            String seasonYear,
            int teamId) throws IOException {

        List<TripleMatchResult> foundResults = new ArrayList<>();
        String seasonUrl = String.format(BASE_URL, teamId, seasonYear);
        String html = fetchHtml(httpClient, seasonUrl);

        if (html == null) {
            log.warn("Could not fetch page for team ID {} and season {}", teamId, seasonYear);
            return foundResults;
        }

        Document doc = Jsoup.parse(html);
        Elements allTableRows = doc.select("table tbody tr");

        List<Element> leagueMatches = new ArrayList<>();
        boolean isFirstLeague = true;
        boolean collectRows   = false;

        for (Element row : allTableRows) {
            if (row.hasClass("competition")) {
                if (!isFirstLeague) break;
                isFirstLeague = false;
                collectRows   = true;
                continue;
            }
            if (collectRows && row.selectFirst("td:nth-child(5) b a") != null) {
                leagueMatches.add(row);
            }
        }

        log.debug("Collected {} potential league match rows for season {}", leagueMatches.size(), seasonYear);

        for (int i = 0; i < leagueMatches.size() - 2; i++) {
            Element row1 = leagueMatches.get(i);
            Element row2 = leagueMatches.get(i + 1);
            Element row3 = leagueMatches.get(i + 2);

            try {
                String score1 = row1.selectFirst("td:nth-child(5) b a").text().trim();
                String score2 = row2.selectFirst("td:nth-child(5) b a").text().trim();
                String score3 = row3.selectFirst("td:nth-child(5) b a").text().trim();

                // ★ Order-independent, direction-independent matching
                if (!matchesTriplePatternUnordered(score1, score2, score3, pattern)) continue;

                log.debug("Triple pattern match found for season {} at index {}", seasonYear, i);

                String homeTeam1 = row1.selectFirst("td:nth-child(3)").text().trim();
                String awayTeam1 = row1.selectFirst("td:nth-child(7)").text().trim();
                String htScore1  = row1.selectFirst("td:nth-child(9)").text().trim();

                String homeTeam2 = row2.selectFirst("td:nth-child(3)").text().trim();
                String awayTeam2 = row2.selectFirst("td:nth-child(7)").text().trim();
                String htScore2  = row2.selectFirst("td:nth-child(9)").text().trim();

                String homeTeam3 = row3.selectFirst("td:nth-child(3)").text().trim();
                String awayTeam3 = row3.selectFirst("td:nth-child(7)").text().trim();
                String htScore3  = row3.selectFirst("td:nth-child(9)").text().trim();

                TripleMatchResult result = new TripleMatchResult(
                        homeTeam1, awayTeam1, score1,
                        homeTeam2, awayTeam2, score2,
                        homeTeam3, awayTeam3, score3,
                        seasonYear, pattern);

                result.htScore1 = htScore1.isEmpty() ? null : htScore1;
                result.htScore2 = htScore2.isEmpty() ? null : htScore2;
                result.htScore3 = htScore3.isEmpty() ? null : htScore3;

                // ── Previous match ─────────────────────────────────────────
                if (i > 0) {
                    Element prev = leagueMatches.get(i - 1);
                    try {
                        String prevScore  = prev.selectFirst("td:nth-child(5) b a").text().trim();
                        String prevHT     = prev.selectFirst("td:nth-child(9)").text().trim();
                        String prevHome   = prev.selectFirst("td:nth-child(3)").text().trim();
                        String prevAway   = prev.selectFirst("td:nth-child(7)").text().trim();
                        // Full label: "TeamA 2-1 TeamB"
                        result.previousMatchScore = prevHome + " " + prevScore + " " + prevAway;
                        result.previousHTScore    = prevHT.isEmpty() ? null : prevHT;
                    } catch (Exception e) {
                        log.warn("Could not parse preceding match at index {} for season {}", i - 1, seasonYear);
                        result.previousMatchScore = "Hata (Önceki)";
                    }
                } else {
                    result.previousMatchScore = "Bilgi Yok (İlk Maç)";
                }

                // ── Next match ─────────────────────────────────────────────
                if (i + 3 < leagueMatches.size()) {
                    Element next = leagueMatches.get(i + 3);
                    try {
                        String nextScore = next.selectFirst("td:nth-child(5) b a").text().trim();
                        String nextHT    = next.selectFirst("td:nth-child(9)").text().trim();
                        String nextHome  = next.selectFirst("td:nth-child(3)").text().trim();
                        String nextAway  = next.selectFirst("td:nth-child(7)").text().trim();
                        result.nextMatchScore = nextHome + " " + nextScore + " " + nextAway;
                        result.nextHTScore    = nextHT.isEmpty() ? null : nextHT;
                    } catch (Exception e) {
                        log.warn("Could not parse following match at index {} for season {}", i + 3, seasonYear);
                        result.nextMatchScore = "Hata (Sonraki)";
                    }
                } else {
                    result.nextMatchScore = "Bilgi Yok (Son Maç)";
                }

                foundResults.add(result);
                log.info("Triple pattern match added for team {}, season {}", teamId, seasonYear);

            } catch (Exception e) {
                log.error("Error analyzing triple match at index {} for team {}, season {}: {}",
                        i, teamId, seasonYear, e.getMessage());
            }
        }

        return foundResults;
    }
}