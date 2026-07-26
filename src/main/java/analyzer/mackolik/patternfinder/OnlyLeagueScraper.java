package analyzer.mackolik.patternfinder;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OnlyLeagueScraper {

    private static final Logger log = LoggerFactory.getLogger(OnlyLeagueScraper.class);

    private static final String BASE_URL =
            "https://arsiv.mackolik.com/Team/Default.aspx?id=%d&season=%s";

    private static final String CURRENT_SEASON = "2026/2027";

    // ═══════════════════════════════════════════════════════════════════════
    //  HTTP
    // ═══════════════════════════════════════════════════════════════════════

    private static String fetchHtml(CloseableHttpClient httpClient, String url)
            throws IOException {
        HttpGet request = new HttpGet(url);
        request.addHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            if (status == 200) return EntityUtils.toString(response.getEntity());
            log.warn("HTTP {} for URL: {}", status, url);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Lig filtresi
    // ═══════════════════════════════════════════════════════════════════════

    public static boolean isLeagueCompetition(String compName) {
        if (compName == null) return false;
        String lower = compName.toLowerCase(new Locale("tr", "TR"));

        String[] excluded = {
                "kupa", "cup", "copa", "coppa", "pokal", "taça", "taca",
                "hazırlık", "hazirlik", "friendlies", "avrupa", "europe",
                "şampiyonlar ligi", "sampiyonlar ligi", "champions league", "uefa",
                "konferans", "conference", "trophy", "shield",
                "dünya", "dunya", "world", "turnuva", "tournament",
                "recopa", "sudamericana", "libertadores"
        };

        for (String ex : excluded) {
            if (lower.contains(ex)) return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Mevcut sezon — N maçlık pattern çıkar (son maç "v" olabilir)
    // ═══════════════════════════════════════════════════════════════════════

    public static MatchPattern findCurrentSeasonPattern(
            CloseableHttpClient httpClient, int teamId, int patternSize)
            throws IOException {

        if (patternSize < 3 || patternSize > 5)
            throw new IllegalArgumentException("patternSize 3-5 arasında olmalı: " + patternSize);

        String html = fetchHtml(httpClient,
                String.format(BASE_URL, teamId, CURRENT_SEASON));
        if (html == null)
            throw new RuntimeException("Sayfa alınamadı: " + teamId);

        Document doc = Jsoup.parse(html);
        Element tableBody = doc.selectFirst("#tblFixture > tbody");
        if (tableBody == null)
            throw new RuntimeException("Fikstür tablosu bulunamadı: " + teamId);

        String teamName = "Unknown";
        try {
            Element title = doc.selectFirst("title");
            if (title != null) teamName = title.text().split("-")[0].trim();
        } catch (Exception ignored) {}

        // ── SADECE LİG MAÇLARINI TOPLA ──────────────────────────────────────
        List<String> scores    = new ArrayList<>();
        List<String> homeTeams = new ArrayList<>();
        List<String> awayTeams = new ArrayList<>();

        boolean collecting = false;

        for (Element row : tableBody.select("tr")) {
            if (row.hasClass("competition")) {
                if (collecting) {
                    break;
                }
                Element a = row.selectFirst("a");
                String compName = a != null ? a.text() : row.text();
                if (isLeagueCompetition(compName)) {
                    collecting = true;
                }
                continue;
            }
            if (!collecting) continue;
            if (row.hasClass("leg")) continue;

            Element homeEl = row.selectFirst("td:nth-child(3)");
            Element awayEl = row.selectFirst("td:nth-child(7)");
            if (homeEl == null || awayEl == null) continue;

            Element scoreEl = row.selectFirst("td:nth-child(5) b a");
            String score = "";
            if (scoreEl != null) {
                score = scoreEl.text().trim();
            } else {
                Element scoreTd = row.selectFirst("td:nth-child(5)");
                if (scoreTd != null) {
                    score = scoreTd.text().trim();
                }
            }

            homeTeams.add(homeEl.text().trim().replaceAll("&nbsp;", ""));
            awayTeams.add(awayEl.text().trim().replaceAll("&nbsp;", ""));
            scores.add(score);
        }

        // ── SON N MAÇI AL (bitmiş veya "v" fark etmez) ──────────────────
        if (scores.size() < patternSize) {
            throw new RuntimeException(
                    String.format("En az %d maç bulunamadı (takım: %d, bulunan: %d)",
                            patternSize, teamId, scores.size()));
        }

        int startIdx = scores.size() - patternSize;
        List<MatchPattern.Match> matchList = new ArrayList<>();
        for (int i = startIdx; i < scores.size(); i++) {
            matchList.add(new MatchPattern.Match(
                    homeTeams.get(i),
                    awayTeams.get(i),
                    scores.get(i)));
        }

        return new MatchPattern(matchList, teamName);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Geçmiş sezon arama — SADECE İDEAL (sıra esnek)
    // ═══════════════════════════════════════════════════════════════════════

    public static List<MatchResult> findScorePattern(
            CloseableHttpClient httpClient, MatchPattern pattern,
            String seasonYear, int teamId) throws IOException {

        List<MatchResult> foundResults = new ArrayList<>();

        String html = fetchHtml(httpClient,
                String.format(BASE_URL, teamId, seasonYear));
        if (html == null) return foundResults;

        Document doc = Jsoup.parse(html);

        // ── SADECE LİG MAÇLARINI AL ──────────────────────────────────────────
        List<Element> leagueMatches = new ArrayList<>();
        boolean collecting = false;

        for (Element row : doc.select("table tbody tr")) {
            if (row.hasClass("competition")) {
                if (collecting) {
                    break;
                }
                Element a = row.selectFirst("a");
                String compName = a != null ? a.text() : row.text();
                if (isLeagueCompetition(compName)) {
                    collecting = true;
                }
                continue;
            }
            if (collecting && row.selectFirst("td:nth-child(5) b a") != null)
                leagueMatches.add(row);
        }

        int n = leagueMatches.size();
        int windowSize = pattern.size;

        if (n < windowSize) return foundResults;

        MatchPattern.Match lastPatternMatch = pattern.getLastMatch();

        for (int i = 0; i <= n - windowSize; i++) {

            List<ParsedMatch> window = new ArrayList<>();
            boolean parseOk = true;
            for (int j = 0; j < windowSize; j++) {
                ParsedMatch pm = parseRow(leagueMatches.get(i + j));
                if (pm == null) { parseOk = false; break; }
                window.add(pm);
            }
            if (!parseOk) continue;

            // ── 1. Son maç kontrolü (birebir aynı olmalı) ──────────────────
            ParsedMatch lastWindow = window.get(windowSize - 1);
            if (!teamsMatchExact(lastWindow, lastPatternMatch)) continue;

            // ── 2. İlk N-1 maç için sıra esnek kontrol ──────────────────────
            List<MatchPattern.Match> firstNMinus1Pattern = pattern.matches.subList(0, windowSize - 1);
            List<ParsedMatch> firstNMinus1Window = window.subList(0, windowSize - 1);

            boolean firstPartOk = false;
            // Tüm permütasyonları dene (max 4! = 24 kombinasyon)
            List<List<MatchPattern.Match>> permutations = generatePermutations(firstNMinus1Pattern);
            for (List<MatchPattern.Match> perm : permutations) {
                boolean match = true;
                for (int j = 0; j < firstNMinus1Window.size(); j++) {
                    if (!teamsMatchExact(firstNMinus1Window.get(j), perm.get(j))) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    firstPartOk = true;
                    break;
                }
            }

            if (!firstPartOk) continue;

            // ── 3. Eşleşme bulundu, kaydet ─────────────────────────────────
            MatchResult mr = buildResult(
                    window, pattern, seasonYear, "İDEAL",
                    i > 0 ? leagueMatches.get(i - 1) : null,
                    i + windowSize < n ? leagueMatches.get(i + windowSize) : null);
            if (mr != null) foundResults.add(mr);
        }

        return foundResults;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Permütasyon oluşturucu (yardımcı)
    // ═══════════════════════════════════════════════════════════════════════

    private static <T> List<List<T>> generatePermutations(List<T> original) {
        List<List<T>> result = new ArrayList<>();
        if (original.isEmpty()) {
            result.add(new ArrayList<>());
            return result;
        }
        permute(original, 0, result);
        return result;
    }

    private static <T> void permute(List<T> arr, int index, List<List<T>> result) {
        if (index == arr.size()) {
            result.add(new ArrayList<>(arr));
            return;
        }
        for (int i = index; i < arr.size(); i++) {
            swap(arr, i, index);
            permute(arr, index + 1, result);
            swap(arr, i, index);
        }
    }

    private static <T> void swap(List<T> arr, int i, int j) {
        T temp = arr.get(i);
        arr.set(i, arr.get(j));
        arr.set(j, temp);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MatchResult oluştur
    // ═══════════════════════════════════════════════════════════════════════

    private static MatchResult buildResult(
            List<ParsedMatch> window,
            MatchPattern pattern,
            String season,
            String matchType,
            Element prevRow,
            Element nextRow) {

        try {
            MatchResult mr = new MatchResult(season, matchType, pattern);

            for (ParsedMatch pm : window) {
                String cb = calculateComeback(pm.score, pm.htScore);
                mr.patternMatches.add(new MatchResult.PatternMatch(
                        pm.homeTeam, pm.awayTeam, pm.score, pm.htScore, cb));
            }

            if (prevRow != null) {
                ParsedMatch prev = parseRow(prevRow);
                if (prev != null) {
                    String cbPrev = calculateComeback(prev.score, prev.htScore);
                    String cbStr  = cbPrev != null ? " 🔥" + cbPrev : "";
                    mr.previousMatchLine = prev.homeTeam + " " + prev.score + " "
                            + prev.awayTeam + " (HT: " + nvl(prev.htScore) + cbStr + ")";
                }
            } else {
                mr.previousMatchLine = "Bilgi Yok (İlk Maç)";
            }

            if (nextRow != null) {
                ParsedMatch next = parseRow(nextRow);
                if (next != null) {
                    String cbNext = calculateComeback(next.score, next.htScore);
                    String cbStr  = cbNext != null ? " 🔥" + cbNext : "";
                    mr.nextMatchLine = next.homeTeam + " " + next.score + " "
                            + next.awayTeam + " (HT: " + nvl(next.htScore) + cbStr + ")";
                }
            } else {
                mr.nextMatchLine = "Bilgi Yok (Son Maç)";
            }

            fillCompatFields(mr, window);

            return mr;

        } catch (Exception e) {
            log.error("buildResult hatası sezon={} matchType={}: {}",
                    season, matchType, e.getMessage());
            return null;
        }
    }

    private static void fillCompatFields(MatchResult mr, List<ParsedMatch> window) {
        if (window.isEmpty()) return;

        ParsedMatch m0 = window.get(0);
        mr.homeTeam = m0.homeTeam;
        mr.awayTeam = m0.awayTeam;
        mr.score    = m0.score;
        mr.firstMatchHTScore = m0.htScore;

        if (window.size() >= 2) {
            ParsedMatch m1 = window.get(1);
            mr.secondMatchHomeTeam = m1.homeTeam;
            mr.secondMatchAwayTeam = m1.awayTeam;
            mr.secondMatchScore    = m1.score;
            mr.secondMatchHTScore  = m1.htScore;
        }
        if (window.size() >= 3) {
            ParsedMatch m2 = window.get(2);
            mr.thirdMatchHomeTeam = m2.homeTeam;
            mr.thirdMatchAwayTeam = m2.awayTeam;
            mr.thirdMatchScore    = m2.score;
            mr.thirdMatchHTScore  = m2.htScore;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HTML Row → ParsedMatch
    // ═══════════════════════════════════════════════════════════════════════

    private static class ParsedMatch {
        final String homeTeam, awayTeam, score, htScore;

        ParsedMatch(String homeTeam, String awayTeam, String score, String htScore) {
            this.homeTeam = homeTeam;
            this.awayTeam = awayTeam;
            this.score    = score;
            this.htScore  = htScore;
        }
    }

    private static ParsedMatch parseRow(Element row) {
        try {
            Element homeEl  = row.selectFirst("td:nth-child(3)");
            Element awayEl  = row.selectFirst("td:nth-child(7)");
            Element scoreEl = row.selectFirst("td:nth-child(5) b a");
            Element htEl    = row.selectFirst("td:nth-child(9)");

            if (homeEl == null || awayEl == null || scoreEl == null) return null;

            String home  = homeEl.text().trim().replaceAll("&nbsp;", "");
            String away  = awayEl.text().trim().replaceAll("&nbsp;", "");
            String score = scoreEl.text().trim();
            String ht    = htEl != null ? nullIfEmpty(htEl.text().trim()) : null;

            return new ParsedMatch(home, away, score, ht);
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Yardımcı metodlar
    // ═══════════════════════════════════════════════════════════════════════

    // ── Birebir takım eşleşmesi (ev/deplasman aynı olmalı) ──
    private static boolean teamsMatchExact(ParsedMatch window, MatchPattern.Match patternMatch) {
        return window.homeTeam.equals(patternMatch.homeTeam) && window.awayTeam.equals(patternMatch.awayTeam);
    }

    private static String calculateComeback(String ftScore, String htScore) {
        if (ftScore == null || htScore == null) return null;
        try {
            String[] ft = ftScore.replaceAll("[^0-9-]", "").trim().split("-");
            String[] ht = htScore.replaceAll("[^0-9-]", "").trim().split("-");

            if (ft.length == 2 && ht.length == 2) {
                int ftH = Integer.parseInt(ft[0]), ftA = Integer.parseInt(ft[1]);
                int htH = Integer.parseInt(ht[0]), htA = Integer.parseInt(ht[1]);

                int htResult = Integer.compare(htH, htA);
                int ftResult = Integer.compare(ftH, ftA);

                if (htResult == -1 && ftResult ==  1) return "2/1";
                if (htResult ==  1 && ftResult == -1) return "1/2";
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static String nvl(String s) { return s != null ? s : "N/A"; }
}