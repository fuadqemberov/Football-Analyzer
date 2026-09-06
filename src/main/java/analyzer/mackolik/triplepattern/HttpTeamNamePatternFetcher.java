package analyzer.mackolik.triplepattern;

import analyzer.util.MackolikHttpFetcher;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches and analyzes the TEAM NAME pattern surrounding an unstarted match.
 *
 * FIX SUMMARY (v3):
 *  1. detectTeamNameFromRows() — team name is detected from the fixture table itself
 *     (most frequent name in home/away columns), NOT from the page title.
 *     Fixes: title says "Polonia Warszawa U19" but table says "P.Warszawa U19".
 *  2. searchHistoricalSeason() uses detectTeamNameFromRows() per season so the
 *     correct abbreviation is used for every historical season too.
 *  3. teamsMatch() — multi-strategy token-level matching:
 *     exact → contains → shared token → prefix abbreviation.
 *     Fixes: "S.Bratislava" vs "Slovan Bratislava", "Atl." vs "Atletico", etc.
 *  4. collectLeagueRows() — robust fallback when competition CSS class is absent.
 *
 * FIX SUMMARY (v4) — ağ katmanı:
 *  5. Sayfa çekme işi {@link MackolikHttpFetcher}'a devredildi. Eski ham
 *     HttpClient kullanımı retry/throttle içermiyordu; 200 olmayan cevaplarda
 *     gövde tüketilmediği için bağlantılar havuza dönmüyordu. Sonuç:
 *     "Cannot fetch current season" ve "Read timed out" hataları yüzlerce takımda.
 *  6. Sayfa indirilemediğinde artık RuntimeException fırlatılmıyor; null dönülüyor
 *     (çağıran taraf bunu "veri yok" olarak ele alır, stack trace çöpü olmaz).
 *  7. Geçmiş (değişmez) sezon sayfaları disk önbelleğine uygun olarak isteniyor.
 */
public class HttpTeamNamePatternFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpTeamNamePatternFetcher.class);
    private static final String BASE_URL       = "https://arsiv.mackolik.com/Team/Default.aspx?id=%d&season=%s";

    /** Yeni futbol sezonunun başladığı ay (Temmuz). Bu aydan itibaren yıl/(yıl+1) sezonundayız. */
    private static final int SEASON_START_MONTH = 7;

    /**
     * Güncel sezonun başlangıç yılı sistem tarihinden hesaplanır (ör. Temmuz 2026 → 2026).
     * Sabit yazılmaz: eskiden "2025/2026" gömülüydü ve sezon dönünce analizör geçen sezonun
     * fikstürüne bakıp oynanmış maçları "yaklaşan maç" sanıyordu.
     */
    static final int CURRENT_SEASON_START_YEAR = computeCurrentSeasonStartYear();

    /** Güncel sezon "yyyy/yyyy" formatında (ör. "2026/2027"). */
    private static final String CURRENT_SEASON =
            CURRENT_SEASON_START_YEAR + "/" + (CURRENT_SEASON_START_YEAR + 1);

    private static int computeCurrentSeasonStartYear() {
        LocalDate now = LocalDate.now();
        return now.getMonthValue() >= SEASON_START_MONTH ? now.getYear() : now.getYear() - 1;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Step 1: Build the current-season TeamNamePattern for a team.
     *
     * @return desen; sayfa indirilemediyse ya da başlamamış maç yoksa <b>null</b>
     */
    public static TeamNamePattern buildCurrentPattern(MackolikHttpFetcher http, int teamId) {

        // Güncel sezon gün içinde değişir → asla önbelleklenmez.
        Document doc = http.fetchDocument(String.format(BASE_URL, teamId, CURRENT_SEASON), false);
        if (doc == null) {
            log.warn("Cannot fetch current season for team {}", teamId);
            return null;
        }

        Element tableBody = doc.selectFirst("#tblFixture > tbody");
        if (tableBody == null) {
            log.warn("No fixture table for team {}", teamId);
            return null;
        }

        // ERTELENMİŞ/GEÇERSİZ SATIRLAR ATILIR. Mackolik ertelenen maçı "P - P"
        // skoruyla gösteriyor; bu sayıya çevrilemediği için eskiden "başlamamış maç"
        // sanılıyor ve aylar önceki ertelenmiş bir maç "yaklaşan maç" seçiliyordu.
        List<Element> rows = new ArrayList<>();
        for (Element row : collectLeagueRows(tableBody)) {
            if (rowState(row) != RowState.INVALID) rows.add(row);
        }

        // ── Detect team name FROM THE ROWS (not the page title) ─────────────
        // The page title may use the full official name ("Polonia Warszawa U19")
        // while the fixture table uses an abbreviation ("P.Warszawa U19").
        // We count how often each name appears in home/away columns — our team
        // appears in every match, so it will have the highest frequency.
        String titleFallback = extractTeamName(doc);
        String teamName = detectTeamNameFromRows(rows, titleFallback);
        log.debug("Team {} name from rows: '{}'", teamId, teamName);

        // ── Find first unstarted match ───────────────────────────────────────
        // Tarihi geçmişte kalan "oynanmamış" satırlar da atlanır: fikstürde
        // kalmış artık satırlar yaklaşan maç sayılmamalı.
        int unstartedIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rowState(rows.get(i)) != RowState.SCHEDULED) continue;
            if (isPastDate(extractCell(rows.get(i), "td:nth-child(1)"))) continue;
            unstartedIdx = i;
            break;
        }

        if (unstartedIdx < 0) {
            log.warn("No unstarted match found for team {}", teamId);
            return null;
        }

        // ── Collect prev opponents (up to 3, oldest first) ──────────────────
        List<String> prevOpponents = new ArrayList<>();
        for (int i = Math.max(0, unstartedIdx - 3); i < unstartedIdx; i++) {
            String opp = extractOpponent(rows.get(i), teamName);
            if (opp != null && !opp.isEmpty()) prevOpponents.add(opp);
        }

        // ── Collect next opponents (up to 3) ────────────────────────────────
        List<String> nextOpponents = new ArrayList<>();
        for (int i = unstartedIdx + 1; i < Math.min(rows.size(), unstartedIdx + 4); i++) {
            Element row  = rows.get(i);
            String  home = extractCell(row, "td:nth-child(3)");
            String  away = extractCell(row, "td:nth-child(7)");
            if (home == null || away == null) continue;
            String opp;
            if (teamsMatch(home, teamName)) {
                opp = away;
            } else if (teamsMatch(away, teamName)) {
                opp = home;
            } else {
                opp = (!away.isEmpty()) ? away : home;
            }
            if (opp != null && !opp.isEmpty()) nextOpponents.add(opp);
        }

        String targetHome = extractCell(rows.get(unstartedIdx), "td:nth-child(3)");
        String targetAway = extractCell(rows.get(unstartedIdx), "td:nth-child(7)");

        log.info("Team {} ({}) | prev={} | target={} vs {} | next={}",
                teamId, teamName, prevOpponents, targetHome, targetAway, nextOpponents);

        return new TeamNamePattern(teamId, teamName, prevOpponents, nextOpponents, targetHome, targetAway);
    }

    /**
     * Step 2: Search a historical season for the same team-name sequence.
     *         Returns all matches that satisfy HT/FT = 1/2 or 2/1.
     */
    public static List<TeamNameMatchResult> searchHistoricalSeason(
            MackolikHttpFetcher http,
            TeamNamePattern pattern,
            String seasonYear,
            int teamId) {

        List<TeamNameMatchResult> results = new ArrayList<>();

        // Geçmiş sezon verisi değişmez → disk önbelleğine uygun.
        boolean cacheable = !CURRENT_SEASON.equals(seasonYear);
        Document doc = http.fetchDocument(String.format(BASE_URL, teamId, seasonYear), cacheable);
        if (doc == null) return results;

        Element tbody = doc.selectFirst("#tblFixture > tbody");
        if (tbody == null) return results;

        List<Element> rows = collectLeagueRows(tbody);

        // ── Detect team name as it appears IN THIS SEASON'S rows ─────────────
        // The same team may be abbreviated differently across seasons.
        // Always detect from the current page's rows, not from pattern.teamName.
        String histTeamName = detectTeamNameFromRows(rows, pattern.teamName);
        log.debug("Season {} histTeamName='{}' (pattern='{}')", seasonYear, histTeamName, pattern.teamName);

        List<MatchData> matches = new ArrayList<>();
        for (Element row : rows) {
            MatchData md = parseMatchData(row);
            if (md != null) matches.add(md);
        }

        if (matches.isEmpty()) return results;

        for (int i = 0; i < matches.size(); i++) {
            MatchData target = matches.get(i);

            // ── HT/FT filter: only 1/2 or 2/1 ──────────────────────────────
            String htFt = computeHtFt(target.ftScore, target.htScore);
            if (htFt == null) continue;

            // ── Build historical surrounding opponent lists ───────────────
            List<String> histPrev = new ArrayList<>();
            for (int k = Math.max(0, i - 3); k < i; k++) {
                histPrev.add(opponentOf(matches.get(k), histTeamName));
            }
            List<String> histNext = new ArrayList<>();
            for (int k = i + 1; k < Math.min(matches.size(), i + 4); k++) {
                histNext.add(opponentOf(matches.get(k), histTeamName));
            }

            // ── Try every combination ────────────────────────────────────
            for (CombinationDef combo : COMBINATIONS) {
                if (combo.matches(pattern.prevOpponents, pattern.nextOpponents, histPrev, histNext)) {
                    TeamNameMatchResult res = new TeamNameMatchResult(
                            teamId, pattern.teamName, seasonYear,
                            combo.label,
                            // ÖNCEKİ rakipler maçtan geriye doğru sayılır → SON n tanesi.
                            // SONRAKİ rakipler maçtan ileriye doğru sayılır → İLK n tanesi.
                            // (Eskiden ikisi de tailList idi; karşılaştırma histNext[0..n) ile
                            //  yapılırken ekrana histNext'in SON n'i basılıyordu.)
                            tailList(histPrev, combo.prevCount),
                            headList(histNext, combo.nextCount),
                            target.homeTeam, target.awayTeam,
                            target.ftScore, target.htScore,
                            htFt,
                            pattern.targetHomeTeam, pattern.targetAwayTeam);
                    results.add(res);
                    log.info("MATCH [{}] team={} season={} htFt={}", combo.label, pattern.teamName, seasonYear, htFt);
                }
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Combination definitions
    //
    // YALNIZCA 3'LÜ ARAMA: bir taraf kullanılacaksa tam 3 maçla kullanılır.
    // PREV1/PREV2/NEXT1/NEXT2 gibi zayıf kombinasyonlar kaldırıldı — eldeki
    // veriyi eksik kullanıp yanıltıcı sinyal üretiyorlardı.
    //
    // Sonuç olarak 3 önceki rakibi olmayan bir takım PREV tarafından, 3 sonraki
    // rakibi olmayan da NEXT tarafından eşleşemez.
    // -----------------------------------------------------------------------

    private static final List<CombinationDef> COMBINATIONS = Arrays.asList(
            new CombinationDef("PREV3",       3, 0),
            new CombinationDef("NEXT3",       0, 3),
            new CombinationDef("PREV3+NEXT3", 3, 3)
    );

    private static class CombinationDef {
        final String label;
        final int    prevCount;
        final int    nextCount;

        CombinationDef(String label, int prevCount, int nextCount) {
            this.label     = label;
            this.prevCount = prevCount;
            this.nextCount = nextCount;
        }

        boolean matches(List<String> curPrev, List<String> curNext,
                        List<String> histPrev, List<String> histNext) {
            if (histPrev.size() < prevCount || histNext.size() < nextCount) return false;
            if (curPrev.size()  < prevCount || curNext.size()  < nextCount) return false;

            // ELDEKİ VERİ EKSİK KULLANILAMAZ: bir taraf kullanılıyorsa TAMAMI kullanılmalı.
            // Aksi halde 2 önceki rakip varken PREV1 ile eşleşip "1 maçla bulundu" gibi
            // zayıf/yanıltıcı sinyal üretiliyordu. Taraf hiç kullanılmıyorsa (count=0)
            // kısıt yok — NEXT3 gibi tek taraflı kombinasyonlar geçerli kalır.
            if (prevCount > 0 && prevCount != curPrev.size()) return false;
            if (nextCount > 0 && nextCount != curNext.size()) return false;

            for (int i = 0; i < prevCount; i++) {
                String cur  = curPrev.get(curPrev.size()   - prevCount + i);
                String hist = histPrev.get(histPrev.size() - prevCount + i);
                if (!teamsMatch(cur, hist)) return false;
            }
            for (int i = 0; i < nextCount; i++) {
                String cur  = curNext.get(i);
                String hist = histNext.get(i);
                if (!teamsMatch(cur, hist)) return false;
            }
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // MatchData helpers
    // -----------------------------------------------------------------------

    private static class MatchData {
        String homeTeam;
        String awayTeam;
        String ftScore;
        String htScore;
    }

    private static MatchData parseMatchData(Element row) {
        try {
            Element scoreEl = row.selectFirst("td:nth-child(5) b a");
            if (scoreEl == null) return null;
            String score = scoreEl.text().trim();
            if (score.isEmpty() || !score.contains("-")) return null;
            if (score.equalsIgnoreCase("v")) return null;

            String normalizedScore = score.replaceAll("\\s*-\\s*", "-");
            String[] parts = normalizedScore.split("-");
            if (parts.length != 2) return null;
            Integer.parseInt(parts[0].trim());
            Integer.parseInt(parts[1].trim());

            MatchData md = new MatchData();
            md.homeTeam = extractCell(row, "td:nth-child(3)");
            md.awayTeam = extractCell(row, "td:nth-child(7)");
            md.ftScore  = normalizedScore;
            String ht   = extractCell(row, "td:nth-child(9)");
            if (ht != null && !ht.isEmpty()) {
                md.htScore = ht.replaceAll("\\s*-\\s*", "-");
            }
            return md;
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    static String computeHtFt(String ftScore, String htScore) {
        if (ftScore == null || htScore == null) return null;
        try {
            String ft = ftScore.replaceAll("\\s*-\\s*", "-");
            String ht = htScore.replaceAll("\\s*-\\s*", "-");

            String[] ftParts = ft.split("-");
            String[] htParts = ht.split("-");
            if (ftParts.length != 2 || htParts.length != 2) return null;

            int ftH = Integer.parseInt(ftParts[0].trim());
            int ftA = Integer.parseInt(ftParts[1].trim());
            int htH = Integer.parseInt(htParts[0].trim());
            int htA = Integer.parseInt(htParts[1].trim());

            if (htH > htA && ftA > ftH) return "1/2";
            if (htA > htH && ftH > ftA) return "2/1";
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Returns the opponent of the given team in a match. */
    private static String opponentOf(MatchData md, String teamName) {
        if (md == null) return "?";
        if (teamsMatch(md.homeTeam, teamName)) return md.awayTeam;
        if (teamsMatch(md.awayTeam, teamName)) return md.homeTeam;
        // Could not identify our team's side — return combined (signals a mismatch)
        return md.homeTeam + "/" + md.awayTeam;
    }

    // -----------------------------------------------------------------------
    // HTML / parsing utilities
    // -----------------------------------------------------------------------

    /**
     * Detects the team name exactly as it appears in the fixture rows.
     *
     * Counts occurrences of every home/away name across all rows.
     * Our team appears in every match, so it will have the highest frequency.
     * Falls back to titleFallback if detection fails.
     */
    private static String detectTeamNameFromRows(List<Element> rows, String titleFallback) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (Element row : rows) {
            String home = extractCell(row, "td:nth-child(3)");
            String away = extractCell(row, "td:nth-child(7)");
            if (home != null && !home.isEmpty()) freq.merge(home, 1, Integer::sum);
            if (away != null && !away.isEmpty()) freq.merge(away, 1, Integer::sum);
        }

        if (freq.isEmpty()) return titleFallback;

        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }

        // Our team should appear in at least half the rows
        if (best != null && bestCount >= Math.max(1, rows.size() / 2)) {
            log.debug("detectTeamNameFromRows → '{}' (count={}/{})", best, bestCount, rows.size());
            return best;
        }

        return titleFallback;
    }

    /** Bir fikstür satırının durumu. */
    private enum RowState {
        /** Geçerli sayısal skor var → oynanmış. */
        PLAYED,
        /** Skor "v" ya da boş → henüz oynanmamış, yaklaşan maç adayı. */
        SCHEDULED,
        /** Ertelendi ("P - P"), iptal, ya da takım adı okunamayan satır. */
        INVALID
    }

    /**
     * Satırın durumunu skor hücresinden belirler.
     *
     * Kritik nokta: ertelenen maç Mackolik'te "P - P" skoruyla görünür. Eski kod
     * bunu sayıya çeviremeyince "başlamamış maç" sayıyordu; sonuçta aylar önce
     * ertelenmiş bir maç güncel desenin hedefi oluyordu. Artık INVALID sayılıp
     * tamamen eleniyor — hem hedef seçiminden hem de önceki/sonraki rakip
     * listelerinden. (Geçmiş sezon tarafında zaten yalnızca sayısal skorlu
     * satırlar okunuyor, dolayısıyla iki taraf tutarlı.)
     */
    private static RowState rowState(Element row) {
        String home = extractCell(row, "td:nth-child(3)");
        String away = extractCell(row, "td:nth-child(7)");
        if (home == null || home.isEmpty() || away == null || away.isEmpty()) return RowState.INVALID;

        Element scoreEl = row.selectFirst("td:nth-child(5) b a");
        if (scoreEl == null) return RowState.SCHEDULED;

        String score = scoreEl.text().trim();
        if (score.isEmpty() || score.equalsIgnoreCase("v")) return RowState.SCHEDULED;

        String[] parts = score.replaceAll("\\s*-\\s*", "-").split("-");
        if (parts.length != 2) return RowState.INVALID;
        try {
            Integer.parseInt(parts[0].trim());
            Integer.parseInt(parts[1].trim());
            return RowState.PLAYED;
        } catch (NumberFormatException e) {
            return RowState.INVALID;   // "P - P" → ertelendi
        }
    }

    /**
     * Tarih hücresi ("6.09.2026") bugünden önce mi?
     * Ayrıştırılamazsa <b>false</b> döner — şüpheli durumda satırı elemeyiz.
     */
    private static boolean isPastDate(String text) {
        if (text == null) return false;
        String[] parts = text.trim().split("\\.");
        if (parts.length != 3) return false;
        try {
            int day   = Integer.parseInt(parts[0].trim());
            int month = Integer.parseInt(parts[1].trim());
            int year  = Integer.parseInt(parts[2].trim());
            return LocalDate.of(year, month, day).isBefore(LocalDate.now());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<Element> collectLeagueRows(Element tableBody) {
        List<Element> rows = new ArrayList<>();
        boolean inFirstLeague = false;

        for (Element row : tableBody.select("tr")) {
            if (row.hasClass("competition")) {
                if (!inFirstLeague) {
                    inFirstLeague = true;
                    continue;
                } else {
                    break;
                }
            }
            if (inFirstLeague) rows.add(row);
        }

        // Fallback: no competition CSS class — collect all rows with a team name
        if (rows.isEmpty()) {
            log.debug("competition-class detection found nothing, using fallback row collector");
            for (Element row : tableBody.select("tr")) {
                String home = extractCell(row, "td:nth-child(3)");
                if (home != null && !home.isEmpty()) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static String extractOpponent(Element row, String teamName) {
        String home = extractCell(row, "td:nth-child(3)");
        String away = extractCell(row, "td:nth-child(7)");
        if (home == null || away == null) return null;
        if (teamsMatch(home, teamName)) return away;
        if (teamsMatch(away, teamName)) return home;
        return away; // default fallback
    }

    private static String extractCell(Element row, String cssSelector) {
        Element el = row.selectFirst(cssSelector);
        return el != null ? el.text().trim() : null;
    }

    private static String extractTeamName(Document doc) {
        try {
            Element title = doc.selectFirst("title");
            if (title != null) return title.text().split("-")[0].trim();
        } catch (Exception ignored) {}
        return "Unknown";
    }

    // -----------------------------------------------------------------------
    // Team name matching
    // -----------------------------------------------------------------------

    /**
     * Normalizes a team name: lowercase, accent removal, strip non-alphanumeric.
     */
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

    /**
     * Returns true if teamA and teamB refer to the same club.
     *
     * ESKİ KURAL HATALIYDI: "A'nın herhangi bir kelimesi B'nin herhangi bir
     * kelimesine eşitse aynı takım" deniyordu. Bu yüzden ortak jenerik kelime
     * taşıyan FARKLI kulüpler eşleşiyordu:
     *     Real Madrid = Real Betis = Real Sociedad   ("Real")
     *     Sporting = Sporting Gijon                  ("Sporting")
     *
     * YENİ KURAL — kelime dizisi hizalaması:
     *  1. Normalize edilmiş adlar birebir aynı.
     *  2. Kelime sayısı eşitse HER pozisyon uyuşmalı. Kısaltma yalnızca İLK
     *     kelimede ön ek olarak kabul edilir ("S.Bratislava" = "Slovan Bratislava",
     *     "Atl.Madrid" = "Atletico Madrid", "R.Sociedad" = "Real Sociedad").
     *     Diğer pozisyonlar birebir eşit olmalı — böylece "Sporting B" ile
     *     "Sporting Braga" ya da "Real Madrid" ile "Real Betis" eşleşmez.
     *  3. Kelime sayısı farklıysa EŞLEŞMEZ. "Kısa ad uzun adın içinde geçiyorsa
     *     aynı takımdır" kuralı denendi ve KALDIRILDI: aynı ligde
     *     "Zalgiris" (Vilnius) ile "Kauno Zalgiris" FARKLI kulüpler, ama
     *     yapı olarak "Verona" / "Hellas Verona" ile birebir aynı. İkisini
     *     yalnızca isimden ayırmak mümkün değil; yanlış sinyal üretmektense
     *     sinyal kaçırmayı tercih ediyoruz.
     *     (Boşluk/noktalama farkları zaten 1. kuralda normalize ediliyor:
     *      "AC Milan" = "ACMilan".)
     */
    static boolean teamsMatch(String teamA, String teamB) {
        if (teamA == null || teamB == null) return false;
        String a = normalize(teamA);
        String b = normalize(teamB);
        if (a.isEmpty() || b.isEmpty()) return false;

        // 1. Birebir aynı
        if (a.equals(b)) return true;

        List<String> tokensA = tokens(teamA);
        List<String> tokensB = tokens(teamB);
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false;

        // 1b. Takım niteleyicileri (II, B, U19, (K) …) birebir aynı olmalı:
        //     "Barcelona II" ile "Barcelona", "Arsenal (K)" ile "Arsenal" AYNI KULÜP DEĞİLDİR.
        if (!squadMarkers(tokensA).equals(squadMarkers(tokensB))) return false;

        // 2. Aynı kelime sayısı → pozisyon pozisyon hizalama
        // 3. Farklı kelime sayısı → eşleşmez (bkz. javadoc)
        return tokensA.size() == tokensB.size() && alignsPositionally(tokensA, tokensB);
    }

    /**
     * Yedek takım / yaş grubu / kadın takımı niteleyicileri. Bunlar kulübü
     * değil TAKIMI belirler; biri varken diğerinde yoksa aynı takım değildir.
     */
    private static final java.util.Set<String> SQUAD_MARKERS = new java.util.HashSet<>(Arrays.asList(
            "ii", "iii", "b", "c", "k",
            "u17", "u18", "u19", "u20", "u21", "u23",
            "res", "reserve", "reserves", "akademi", "akademia", "academy",
            "amator", "amateur", "jr", "junior"));

    /**
     * Addaki niteleyici kelimeler ("Barcelona II" → {ii}, "Barcelona" → {}).
     *
     * İLK kelime hiçbir zaman niteleyici sayılmaz: niteleyici daima kulüp adından
     * SONRA gelir ("Arsenal (K)", "Barcelona II"), ilk kelime ise kısaltılmış bir
     * baş harf olabilir ("K.Zalgiris" → K, "B. Yeniçarşı" → B).
     */
    private static java.util.Set<String> squadMarkers(List<String> tokenList) {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (int i = 1; i < tokenList.size(); i++) {
            String n = normalize(tokenList.get(i));
            if (SQUAD_MARKERS.contains(n)) found.add(n);
        }
        return found;
    }

    /**
     * Eşit uzunluktaki iki kelime dizisi aynı kulübü mü gösteriyor?
     * İlk kelimede kısaltma (ön ek) serbest, diğer kelimeler birebir eşit olmalı.
     */
    private static boolean alignsPositionally(List<String> tokensA, List<String> tokensB) {
        for (int i = 0; i < tokensA.size(); i++) {
            String x = normalize(tokensA.get(i));
            String y = normalize(tokensB.get(i));
            if (x.isEmpty() || y.isEmpty()) return false;
            if (x.equals(y)) continue;
            // Kısaltma yalnızca ilk kelimede: "S." → "Slovan", "Atl." → "Atletico"
            if (i == 0 && (x.startsWith(y) || y.startsWith(x))) continue;
            return false;
        }
        return true;
    }

    /**
     * Adı kelimelere böler. Boşluğun yanı sıra NOKTALAMA da ayırıcıdır:
     * "P.Warszawa U19" → [P, Warszawa, U19]. Nokta ayırıcı sayılmazsa
     * "PWarszawa" tek kelime olur ve "Polonia Warszawa U19" ile hizalanamaz.
     */
    private static List<String> tokens(String name) {
        List<String> result = new ArrayList<>();
        if (name == null) return result;
        for (String part : name.trim().split("[^\\p{L}\\p{N}]+")) {
            if (!part.isEmpty()) result.add(part);
        }
        return result;
    }

    /** Listenin SON {@code count} elemanı — önceki rakipler için. */
    private static List<String> tailList(List<String> list, int count) {
        int from = Math.max(0, list.size() - count);
        return new ArrayList<>(list.subList(from, list.size()));
    }

    /** Listenin İLK {@code count} elemanı — sonraki rakipler için. */
    private static List<String> headList(List<String> list, int count) {
        int to = Math.min(list.size(), count);
        return new ArrayList<>(list.subList(0, to));
    }
}