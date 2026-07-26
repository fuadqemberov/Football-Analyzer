package analyzer.mackolik.triplepattern;

import analyzer.mackolik.patternfinder.OnlyLeagueScraper;
import org.apache.http.client.config.RequestConfig;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches WHOLE league seasons from Mackolik instead of single-team fixture pages.
 *
 * The team pages used by the other scrapers in this package only expose one team's
 * matches. The fixture feed behind the league standings page returns every match of
 * a league round — with team IDs, full-time AND half-time scores — which is what the
 * league-wide pattern scan needs.
 *
 *   getWeeks   → [[week, firstDate, lastDate, flag], ...]
 *   getMatches → [[matchId, 'dd/MM', status, homeId, homeName, awayId, awayName,
 *                  code, statusCode, ftHome, ftAway, ...odds..., 'İY skoru', ...], ...]
 *
 * Season data is cached per seasonId, so every team of the same league downloads it once.
 */
class LeagueSeasonFetcher {

    private static final Logger log = LoggerFactory.getLogger(LeagueSeasonFetcher.class);

    private static final String TEAM_URL     = "https://arsiv.mackolik.com/Team/Default.aspx?id=%d&season=%s";
    private static final String STANDING_URL = "https://arsiv.mackolik.com/Puan-Durumu/s=%d/lig";
    private static final String WEEKS_URL    = "https://arsiv.mackolik.com/AjaxHandlers/FixtureHandler.aspx?command=getWeeks&id=%d";
    private static final String MATCHES_URL  = "https://arsiv.mackolik.com/AjaxHandlers/FixtureHandler.aspx?command=getMatches&id=%d&week=%d";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /** Status codes that mean the match was actually played to the end (see Fixture.js). */
    private static final int[] FINISHED_CODES = {4, 6, 8, 10};

    private static final Pattern SEASON_ID_PATTERN = Pattern.compile("Puan-Durumu/s=(\\d+)");

    /** seasonId → all matches of that league season. */
    private static final Map<Integer, List<LeagueMatch>> SEASON_CACHE = new ConcurrentHashMap<>();
    /** anchor seasonId → season label ("2023/2024") → seasonId, for the same league. */
    private static final Map<Integer, Map<String, Integer>> SEASON_INDEX_CACHE = new ConcurrentHashMap<>();

    private LeagueSeasonFetcher() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  League resolution
    // ═══════════════════════════════════════════════════════════════════════

    /** The league a team plays in during a given season. */
    static class LeagueRef {
        final int    seasonId;
        final String leagueName;
        final String seasonLabel;

        LeagueRef(int seasonId, String leagueName, String seasonLabel) {
            this.seasonId    = seasonId;
            this.leagueName  = leagueName;
            this.seasonLabel = seasonLabel;
        }
    }

    /**
     * Reads the team's season page and returns the first LEAGUE competition block
     * (cups and European competitions are skipped via
     * {@link OnlyLeagueScraper#isLeagueCompetition(String)}), together with the
     * seasonId embedded in its standings link.
     */
    static LeagueRef resolveLeague(CloseableHttpClient http, int teamId, String seasonLabel) throws IOException {
        String html = fetchText(http, String.format(TEAM_URL, teamId, seasonLabel));
        if (html == null) return null;

        Document doc = Jsoup.parse(html);
        Elements competitionRows = doc.select("tr.competition");

        for (Element row : competitionRows) {
            Element link = row.selectFirst("a[href*=Puan-Durumu/s=]");
            if (link == null) continue;

            String compName = link.text().trim();
            if (!OnlyLeagueScraper.isLeagueCompetition(compName)) continue;

            Matcher m = SEASON_ID_PATTERN.matcher(link.attr("href"));
            if (!m.find()) continue;

            int seasonId = Integer.parseInt(m.group(1));
            log.debug("Team {} season {} → league '{}' (seasonId={})", teamId, seasonLabel, compName, seasonId);
            return new LeagueRef(seasonId, compName, seasonLabel);
        }

        log.warn("No league competition found for team {} in season {}", teamId, seasonLabel);
        return null;
    }

    /** Team name as Mackolik spells it on the team page (used for headings only). */
    static String fetchTeamName(CloseableHttpClient http, int teamId, String seasonLabel) throws IOException {
        String html = fetchText(http, String.format(TEAM_URL, teamId, seasonLabel));
        if (html == null) return "Team#" + teamId;

        Element title = Jsoup.parse(html).selectFirst("title");
        if (title == null) return "Team#" + teamId;
        return title.text().split("-")[0].trim();
    }

    /**
     * Every season the league has data for: "2023/2024" → 63860.
     * Read once per league from the season dropdown of the standings page.
     */
    static Map<String, Integer> fetchSeasonIndex(CloseableHttpClient http, int anchorSeasonId) {
        return SEASON_INDEX_CACHE.computeIfAbsent(anchorSeasonId, id -> {
            Map<String, Integer> index = new LinkedHashMap<>();
            try {
                String html = fetchText(http, String.format(STANDING_URL, id));
                if (html == null) return index;

                Elements options = Jsoup.parse(html).select("select#cboSeason > option");
                for (Element option : options) {
                    String label = option.text().trim();
                    String value = option.attr("value").trim();
                    // Only real season labels ("2023/2024"); the same dropdown also
                    // lists national tournaments ("Euro 2024") with unrelated ids.
                    if (!label.matches("\\d{4}/\\d{4}")) continue;
                    try {
                        index.put(label, Integer.parseInt(value));
                    } catch (NumberFormatException ignored) {
                    }
                }
                log.debug("Season index for anchor {} contains {} seasons", id, index.size());
            } catch (IOException e) {
                log.error("Could not fetch season index for anchor {}: {}", id, e.getMessage());
            }
            return index;
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Season fixtures
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * All matches of one league season, chronologically ordered.
     * Cached — concurrent callers for the same season download it once.
     */
    static List<LeagueMatch> fetchLeagueSeason(CloseableHttpClient http, int seasonId, String seasonLabel) {
        return SEASON_CACHE.computeIfAbsent(seasonId, id -> {
            List<LeagueMatch> matches = new ArrayList<>();
            try {
                List<Integer> weeks = fetchWeeks(http, id);
                if (weeks.isEmpty()) {
                    log.warn("No weeks returned for seasonId {}", id);
                    return matches;
                }

                int startYear = parseStartYear(seasonLabel);
                for (int week : weeks) {
                    try {
                        matches.addAll(fetchWeekMatches(http, id, week, startYear));
                    } catch (IOException e) {
                        log.warn("Week {} of season {} failed: {}", week, id, e.getMessage());
                    }
                }
                matches.sort(LeagueSeasonFetcher::compareChronologically);
                log.info("Season {} ({}) loaded: {} matches over {} weeks",
                        seasonLabel, id, matches.size(), weeks.size());
            } catch (IOException e) {
                log.error("Could not load season {} ({}): {}", seasonLabel, id, e.getMessage());
            }
            return matches;
        });
    }

    private static int compareChronologically(LeagueMatch a, LeagueMatch b) {
        // Dates are reconstructed from "dd/MM" and can be absent; the round number
        // is the safety net (and the better order for postponed matches).
        if (a.date != null && b.date != null) {
            int byDate = a.date.compareTo(b.date);
            if (byDate != 0) return byDate;
        }
        return Integer.compare(a.week, b.week);
    }

    private static List<Integer> fetchWeeks(CloseableHttpClient http, int seasonId) throws IOException {
        String body = fetchText(http, String.format(WEEKS_URL, seasonId));
        List<Integer> weeks = new ArrayList<>();
        if (body == null) return weeks;

        for (List<String> row : JsArrayParser.parseRows(body)) {
            if (row.isEmpty()) continue;
            Integer week = toInt(row.get(0));
            if (week != null) weeks.add(week);
        }
        return weeks;
    }

    private static List<LeagueMatch> fetchWeekMatches(CloseableHttpClient http, int seasonId,
                                                     int week, int startYear) throws IOException {
        String body = fetchText(http, String.format(MATCHES_URL, seasonId, week));
        List<LeagueMatch> matches = new ArrayList<>();
        if (body == null) return matches;

        for (List<String> row : JsArrayParser.parseRows(body)) {
            LeagueMatch match = toMatch(row, week, startYear);
            if (match != null) matches.add(match);
        }
        return matches;
    }

    /**
     * Field layout comes from writeFixture() in cm.mackolik.com/js5/Mackolik/Fixture.js:
     * 0=matchId, 1=dd/MM, 3=homeId, 4=homeName, 5=awayId, 6=awayName,
     * 8=statusCode, 9=ftHome, 10=ftAway, 23=half-time score ("1 - 0").
     */
    private static LeagueMatch toMatch(List<String> row, int week, int startYear) {
        if (row.size() < 11) return null;

        Integer matchId = toInt(row.get(0));
        Integer homeId  = toInt(row.get(3));
        Integer awayId  = toInt(row.get(5));
        Integer status  = toInt(row.get(8));
        Integer ftHome  = toInt(row.get(9));
        Integer ftAway  = toInt(row.get(10));

        if (matchId == null || homeId == null || awayId == null || status == null) return null;

        boolean finished = isFinished(status);
        if (!finished) return null;                 // unplayed / postponed rows carry no usable score
        if (ftHome == null || ftAway == null) return null;

        String homeName = row.get(4);
        String awayName = row.get(6);
        if (homeName == null || awayName == null) return null;

        int htHome = -1;
        int htAway = -1;
        if (row.size() > 23) {
            int[] ht = parseHalfTime(row.get(23));
            if (ht != null) {
                htHome = ht[0];
                htAway = ht[1];
            }
        }

        LocalDate date = parseDate(row.get(1), startYear);

        return new LeagueMatch(matchId, week, date,
                homeId, homeName, awayId, awayName,
                ftHome, ftAway, htHome, htAway, true);
    }

    private static boolean isFinished(int statusCode) {
        for (int code : FINISHED_CODES) {
            if (code == statusCode) return true;
        }
        return false;
    }

    /** "1 - 0" → {1, 0}; null when absent or malformed. */
    private static int[] parseHalfTime(String raw) {
        if (raw == null) return null;
        String[] parts = raw.replaceAll("\\s*-\\s*", "-").split("-");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The feed only carries "dd/MM". European seasons run Jul→Jun, so a month of
     * July or later belongs to the first year of the label, everything else to the second.
     */
    private static LocalDate parseDate(String ddMM, int startYear) {
        if (ddMM == null || startYear <= 0) return null;
        String[] parts = ddMM.trim().split("/");
        if (parts.length != 2) return null;
        try {
            int day   = Integer.parseInt(parts[0].trim());
            int month = Integer.parseInt(parts[1].trim());
            int year  = (month >= 7) ? startYear : startYear + 1;
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseStartYear(String seasonLabel) {
        if (seasonLabel == null) return -1;
        try {
            return Integer.parseInt(seasonLabel.split("/")[0].trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static Integer toInt(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HTTP
    // ═══════════════════════════════════════════════════════════════════════

    private static String fetchText(CloseableHttpClient http, String url) throws IOException {
        HttpGet request = new HttpGet(url);
        request.addHeader("User-Agent", USER_AGENT);
        request.setConfig(RequestConfig.custom()
                .setConnectTimeout(10000)
                .setConnectionRequestTimeout(15000)
                .setSocketTimeout(20000)
                .build());

        log.debug("GET {}", url);
        try (CloseableHttpResponse response = http.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            if (status != 200) {
                log.warn("HTTP {} for {}", status, url);
                return null;
            }
            // Mackolik declares utf-8 but not always in a way HttpClient honours.
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Tolerant parser for Mackolik's JS array literals
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The fixture feed is a JavaScript literal, not JSON: strings use single quotes
     * and absent values are simply skipped ("...,261,4,,,,0,..."). Gson/org.json both
     * reject it, so it is scanned by hand.
     */
    static final class JsArrayParser {

        private JsArrayParser() {
        }

        /** Splits "[[a,b],[c,d]]" into a list of field lists; empty fields become null. */
        static List<List<String>> parseRows(String body) {
            List<List<String>> rows = new ArrayList<>();
            if (body == null) return rows;

            String trimmed = body.trim();
            int open  = trimmed.indexOf('[');
            int close = trimmed.lastIndexOf(']');
            if (open < 0 || close <= open) return rows;

            String inner = trimmed.substring(open + 1, close);

            boolean inQuote = false;
            int depth = 0;
            int rowStart = -1;

            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);

                if (inQuote) {
                    if (c == '\\') {
                        i++;                        // skip the escaped character
                    } else if (c == '\'') {
                        inQuote = false;
                    }
                    continue;
                }

                if (c == '\'') {
                    inQuote = true;
                } else if (c == '[') {
                    if (depth == 0) rowStart = i + 1;
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0 && rowStart >= 0) {
                        rows.add(splitFields(inner.substring(rowStart, i)));
                        rowStart = -1;
                    }
                }
            }
            return rows.isEmpty() ? Collections.emptyList() : rows;
        }

        private static List<String> splitFields(String row) {
            List<String> fields = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuote = false;
            boolean quoted  = false;

            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);

                if (inQuote) {
                    if (c == '\\' && i + 1 < row.length()) {
                        current.append(row.charAt(++i));
                    } else if (c == '\'') {
                        inQuote = false;
                    } else {
                        current.append(c);
                    }
                    continue;
                }

                if (c == '\'') {
                    inQuote = true;
                    quoted  = true;
                } else if (c == ',') {
                    fields.add(finish(current, quoted));
                    current.setLength(0);
                    quoted = false;
                } else {
                    current.append(c);
                }
            }
            fields.add(finish(current, quoted));
            return fields;
        }

        private static String finish(StringBuilder buffer, boolean quoted) {
            String value = buffer.toString().trim();
            if (!quoted && value.isEmpty()) return null;
            return value;
        }
    }
}
