package analyzer.mackolik.triplepattern;

import analyzer.mackolik.triplepattern.LeagueSeasonFetcher.JsArrayParser;
import analyzer.mackolik.triplepattern.LeagueSeasonFetcher.LeagueRef;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * "SEZON DENKLEMİ" — bir komandanın keçmiş sezondakı fikstür qəlibini (denklemini)
 * başqa bir komandanın bu sezon təkrarlaması. Şəkildəki Cruzeiro / Flamengo nümunəsi.
 *
 * <p><b>Nümunə (Brezilya Serie A):</b>
 * <pre>
 *   2025 — Cruzeiro:  Botafogo → [Santos ★ 1-2, İY/MS 1/2] → Mirassol
 *                     və Santos həmin oyundan sonra Vasco Da Gama-ya gedir (0-6)
 *   2026 — Flamengo:  Mirassol → [Cruzeiro ★ ??] → Botafogo
 *                     və Cruzeiro həmin oyundan sonra Vasco Da Gama-ya gedir
 * </pre>
 * Yəni Flamengo bu sezon keçən sezonkı Cruzeiro-nun DENKLEMİNİ alıb: eyni iki
 * qonşu rəqib (sıra tərs ola bilər) + orta halqanın eyni "körpü" komandaya yollanması.
 * Təxmin: hədəf oyun tarixi denklemdəki nəticəni (şəkildə 1/2 sürprizi) təkrarlayır.
 *
 * <p><b>Denklemin tərifi</b> — bir hədəf oyun (P protaqonist, M orta halqa) üçün imza:
 * <ol>
 *   <li><b>Ə</b> — P-nin hədəf oyundan ƏVVƏLKİ rəqibi</li>
 *   <li><b>S</b> — P-nin hədəf oyundan SONRAKI rəqibi</li>
 *   <li><b>V</b> — M-in hədəf oyundan sonrakı ilk rəqibi ("körpü" — hara yollanır)</li>
 * </ol>
 * Keçmiş sezonlarda EYNİ liqada elə bir (X, M2) oyunu axtarılır ki, X-in qonşu
 * rəqibləri {Ə, S} çoxluğu ilə üst-üstə düşsün (sıra sərbəst) və M2 həmin oyundan
 * sonra məhz V ilə oynasın. Komandalar ID ilə tutuşdurulur — ad dəyişikliyi problem deyil.
 *
 * <p>Standart olaraq yalnız keçmişdə <b>sürpriz</b> (İY/MS 2/1 · 1/2 · 1/X · 2/X) ilə
 * bitmiş denklemlər siqnal sayılır; {@code --hamisi} arqumenti bu filtri söndürür.
 *
 * <p>{@link LeagueSeasonFetcher}, {@link LeagueMatch} və {@link LeagueWidePatternMatcher}
 * paket-səviyyəli olduğu üçün sinif {@code triplepattern} paketinə qoyulub; mövcud
 * fayllara heç bir dəyişiklik edilmir. Fərq: bu analiz OYNANMAMIŞ oyunları da tələb
 * edir (hədəf oyun, sonrakı rəqib, körpü), ona görə sezon feed-i burada ayrıca —
 * bitməmiş sətirləri də saxlayaraq — oxunur.
 *
 * <p>İşlətmə:
 * <pre>
 *   java ... SezonDenklemiAnalyzer                  → bugünün başlamamış oyunları
 *   java ... SezonDenklemiAnalyzer --butun-gunler   → bugün + sabah (feed nə qədər verirsə)
 *   java ... SezonDenklemiAnalyzer --tarix=24/08/2026
 *   java ... SezonDenklemiAnalyzer 3319:3321        → yalnız verilən evId:deplasmanId
 *   java ... SezonDenklemiAnalyzer --hamisi --sezon=8
 * </pre>
 */
public class SezonDenklemiAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SezonDenklemiAnalyzer.class);

    /** Sürpriz sayılan İY/MS kodları — paketin qalanı ilə eyni. */
    private static final List<String> TARGET_HT_FT = LeagueWidePatternMatcher.TARGET_HT_FT;

    private static final int NUM_THREADS = 8;
    /** Neçə keçmiş sezon taranır (cari sezon xaric). */
    private static final int DEFAULT_SEASON_LOOKBACK = 10;
    /** Komanda səhifəsi üçün neçə il geri etiket sınanır. */
    private static final int MAX_LABEL_FALLBACK = 3;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * Günün TAM proqramı. {@code group=0} (paketin qalanının işlətdiyi ünvan) yalnız canlı və
     * yenicə bitmiş oyunların lövhəsidir — səhər saatlarında axşamkı oyunlar hələ orada olmur,
     * ona görə "başlamamış oyun yoxdur" çıxırdı. {@code group=all} bugün + sabahın bütün
     * oyunlarını, üstəlik hər sətirdə liqa adı, seasonId və sezon etiketi ilə verir.
     */
    private static final String LIVEDATA_URL = "https://vd.mackolik.com/livedata?group=all";
    private static final String STANDING_URL = "https://arsiv.mackolik.com/Puan-Durumu/s=%d/lig";
    private static final String WEEKS_URL    = "https://arsiv.mackolik.com/AjaxHandlers/FixtureHandler.aspx?command=getWeeks&id=%d";
    private static final String MATCHES_URL  = "https://arsiv.mackolik.com/AjaxHandlers/FixtureHandler.aspx?command=getMatches&id=%d&week=%d";

    /** Fixture.js: oyun tam bitibsə status bu kodlardan biridir. */
    private static final int[] FINISHED_CODES = {4, 6, 8, 10};

    /** "2025/2026" və ya təqvim ili liqaları üçün "2025". */
    private static final Pattern SEASON_LABEL = Pattern.compile("\\d{4}(/\\d{4})?");

    /** livedata-da başlamamış oyunun status kodu. */
    private static final int STATUS_UNSTARTED = 0;

    /** livedata sətrindəki "23/08/2026" tipli tarix. */
    private static final Pattern DATE_TEXT = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");
    /** livedata sətrindəki "19:00" tipli başlama saatı. */
    private static final Pattern TIME_TEXT = Pattern.compile("\\d{1,2}:\\d{2}");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** seasonId → sezonun BÜTÜN oyunları (oynanmamışlar daxil). */
    private static final Map<Integer, List<LeagueMatch>> FULL_SEASON_CACHE = new ConcurrentHashMap<>();
    /** anchor seasonId → etiket → seasonId. */
    private static final Map<Integer, Map<String, Integer>> SEASON_INDEX_CACHE = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════════
    //  Model
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Proqramdakı bir oyun. Liqa məlumatı livedata sətrinin özündən gəlir, ona görə
     * komanda səhifəsini "hansı liqadır?" deyə yoxlamağa ehtiyac qalmır — bu, oyun başına
     * 6-12 artıq HTTP sorğusunu və sezon etiketinin təxmin edilməsini aradan qaldırır.
     * Yalnız əl ilə verilən {@code evId:deplasmanId} cütlərində {@code seasonId} 0 olur.
     */
    static final class Fixture {
        final int homeId;
        final String homeName;
        final int awayId;
        final String awayName;
        final String kickoff;      // "19:00" — bilinmirsə boş
        final String dateText;     // "23/08/2026" — bilinmirsə boş
        final String leagueName;   // "Türkiyə · Süper Lig" — bilinmirsə boş
        final int seasonId;        // 73482; 0 = bilinmir
        final String seasonLabel;  // "2026/2027" və ya təqvim ili "2026"

        Fixture(int homeId, String homeName, int awayId, String awayName,
                String kickoff, String dateText, String leagueName, int seasonId, String seasonLabel) {
            this.homeId      = homeId;
            this.homeName    = homeName;
            this.awayId      = awayId;
            this.awayName    = awayName;
            this.kickoff     = kickoff;
            this.dateText    = dateText;
            this.leagueName  = leagueName;
            this.seasonId    = seasonId;
            this.seasonLabel = seasonLabel;
        }

        /** Əl ilə verilən cüt — liqa sonradan komanda səhifəsindən tapılır. */
        static Fixture manual(int homeId, int awayId) {
            return new Fixture(homeId, "Ev#" + homeId, awayId, "Dep#" + awayId, "", "", "", 0, "");
        }

        @Override
        public String toString() {
            return homeName + " (" + homeId + ") vs " + awayName + " (" + awayId + ")"
                    + (leagueName.isEmpty() ? "" : " | " + leagueName)
                    + (kickoff.isEmpty() ? "" : " " + dateText + " " + kickoff);
        }
    }

    /** Keçmiş sezonda tapılan bir denklem təkrarı. */
    static final class DenklemHit {
        final String seasonLabel;
        final int protagonistId;
        final String protagonistName;
        final LeagueMatch previous;   // X – Ə
        final LeagueMatch star;       // X – M2 (nəticəsi bilinən oyun)
        final LeagueMatch next;       // X – S
        final LeagueMatch bridge;     // M2 – V
        final String htFt;            // ★ oyunun İY/MS kodu (ev/deplasman oxusu); null ola bilər
        final boolean sameOrder;      // qonşu rəqiblərin sırası cari sezonla eynidirmi

        DenklemHit(String seasonLabel, int protagonistId, String protagonistName,
                   LeagueMatch previous, LeagueMatch star, LeagueMatch next,
                   LeagueMatch bridge, String htFt, boolean sameOrder) {
            this.seasonLabel     = seasonLabel;
            this.protagonistId   = protagonistId;
            this.protagonistName = protagonistName;
            this.previous        = previous;
            this.star            = star;
            this.next            = next;
            this.bridge          = bridge;
            this.htFt            = htFt;
            this.sameOrder       = sameOrder;
        }

        /** ★ oyunun kodu protaqonistin gözü ilə (ev/deplasman fərqi silinir). */
        String protagonistCode() {
            if (htFt == null) return null;
            return star.isHome(protagonistId) ? htFt : flip(htFt);
        }
    }

    /** Bir hədəf oyun üçün tam siqnal: cari denklem + onu təkrarlayan keçmiş hallar. */
    static final class Signal {
        final String currentLabel;
        final int protagonistId;
        final String protagonistName;
        final String midName;
        final LeagueMatch target;     // P – M (oynanmamış hədəf oyun)
        final LeagueMatch previous;   // P – Ə
        final LeagueMatch next;       // P – S
        final LeagueMatch bridge;     // M – V
        final String prevOppName;
        final String nextOppName;
        final String bridgeName;
        final List<DenklemHit> hits;

        Signal(String currentLabel, int protagonistId, String protagonistName, String midName,
               LeagueMatch target, LeagueMatch previous, LeagueMatch next, LeagueMatch bridge,
               String prevOppName, String nextOppName, String bridgeName, List<DenklemHit> hits) {
            this.currentLabel    = currentLabel;
            this.protagonistId   = protagonistId;
            this.protagonistName = protagonistName;
            this.midName         = midName;
            this.target          = target;
            this.previous        = previous;
            this.next            = next;
            this.bridge          = bridge;
            this.prevOppName     = prevOppName;
            this.nextOppName     = nextOppName;
            this.bridgeName      = bridgeName;
            this.hits            = hits;
        }

        String render(String leagueName) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n★★★ SEZON DENKLEMİ TAPILDI ★★★  ").append(leagueName)
                    .append(' ').append(currentLabel).append('\n');
            sb.append("  Protaqonist (denklemi alan)      : ").append(protagonistName).append('\n');
            sb.append("  Orta halqa (bugünkü rəqib)       : ").append(midName).append('\n');
            sb.append("  Ə — əvvəlki rəqib                : ").append(prevOppName).append('\n');
            sb.append("  S — sonrakı rəqib                : ").append(nextOppName).append('\n');
            sb.append("  V — orta halqa hara yollanır     : ").append(bridgeName).append('\n');
            sb.append("  ────────────────────────────────────────────\n");
            sb.append("  CARİ SEZON ").append(currentLabel).append('\n');
            sb.append("    Ə əvvəl : ").append(line(previous)).append('\n');
            sb.append("    ★ HƏDƏF : ").append(line(target)).append('\n');
            sb.append("    S sonra : ").append(line(next)).append('\n');
            sb.append("    V körpü : ").append(line(bridge)).append('\n');

            for (DenklemHit hit : hits) {
                sb.append("  ────────────────────────────────────────────\n");
                sb.append("  KEÇMİŞ DENKLEM ").append(hit.seasonLabel)
                        .append(" — ").append(hit.protagonistName)
                        .append("   [qonşu sıra: ").append(hit.sameOrder ? "düz" : "tərs").append("]\n");
                sb.append("    Ə əvvəl : ").append(line(hit.previous)).append('\n');
                sb.append("    ★ NƏTİCƏ: ").append(line(hit.star));
                if (hit.htFt != null) {
                    sb.append("  → İY/MS ").append(hit.htFt);
                    if (TARGET_HT_FT.contains(hit.htFt)) sb.append(" ★SÜRPRİZ");
                }
                sb.append('\n');
                sb.append("    S sonra : ").append(line(hit.next)).append('\n');
                sb.append("    V körpü : ").append(line(hit.bridge)).append('\n');
                sb.append("    >>> TƏXMİN (kupon oxusu · ev/deplasman): ")
                        .append(hit.htFt != null ? hit.htFt : "İY skoru yoxdur").append('\n');
                sb.append("    >>> TƏXMİN (rol oxusu · protaqonist gözü): ")
                        .append(roleReading(hit)).append('\n');
            }
            return sb.toString();
        }

        /**
         * Keçmiş nəticəni protaqonist rolu üzərindən bugünkü oyuna köçürür.
         * Keçmiş protaqonist evdə, bugünkü deplasmandadırsa (şəkildəki hal) kod çevrilir —
         * ona görə iki oxu ayrıca yazılır, hansının götürüləcəyi istifadəçinin seçimidir.
         */
        private String roleReading(DenklemHit hit) {
            String code = hit.protagonistCode();
            if (code == null) return "İY skoru yoxdur";
            boolean protagonistHomeNow = target.isHome(protagonistId);
            String transferred = protagonistHomeNow ? code : flip(code);
            return transferred + "  (" + protagonistName + " "
                    + (protagonistHomeNow ? "evdə" : "deplasmanda") + ")";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Denklem dedektoru (şəbəkəsiz — əldə qurulmuş siyahılarla da sınana bilər)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * {@code protagonistId} (P) və bugünkü rəqibi {@code midId} (M) üçün denklemi çıxarır,
     * sonra {@code pastSeasons}-da onu təkrarlayan halları axtarır.
     *
     * @param pastSeasons  etiket → həmin sezonun bütün oyunları (yalnız EYNİ liqa)
     * @param onlySurprise true → yalnız İY/MS sürprizi ilə bitmiş keçmiş denklemlər sayılır
     * @return heç bir təkrar yoxdursa {@code null}
     */
    static Signal detect(List<LeagueMatch> currentSeason, String currentLabel,
                         int protagonistId, String protagonistName,
                         int midId, String midName,
                         Map<String, List<LeagueMatch>> pastSeasons,
                         boolean onlySurprise) {

        List<LeagueMatch> ownTimeline = fullTimeline(currentSeason, protagonistId);

        // Hədəf: P-nin M ilə hələ oynanmamış oyunu.
        int i = -1;
        for (int idx = 0; idx < ownTimeline.size(); idx++) {
            LeagueMatch match = ownTimeline.get(idx);
            if (!match.finished && match.opponentId(protagonistId) == midId) {
                i = idx;
                break;
            }
        }
        if (i <= 0 || i >= ownTimeline.size() - 1) return null;   // hər iki qonşu lazımdır

        LeagueMatch target   = ownTimeline.get(i);
        LeagueMatch previous = ownTimeline.get(i - 1);
        LeagueMatch next     = ownTimeline.get(i + 1);

        int prevOppId = previous.opponentId(protagonistId);
        int nextOppId = next.opponentId(protagonistId);
        if (prevOppId == midId || nextOppId == midId) return null;  // qonşu rəqib orta halqa ola bilməz

        // Körpü: orta halqanın hədəf oyundan sonrakı İLK oyunu.
        LeagueMatch bridge = matchAfter(currentSeason, midId, target);
        if (bridge == null) return null;
        int bridgeId = bridge.opponentId(midId);
        if (bridgeId == protagonistId) return null;                 // körpü üçüncü komanda olmalıdır

        List<DenklemHit> hits = new ArrayList<>();
        for (Map.Entry<String, List<LeagueMatch>> season : pastSeasons.entrySet()) {
            hits.addAll(findInSeason(season.getValue(), season.getKey(),
                    prevOppId, nextOppId, bridgeId, onlySurprise));
        }
        if (hits.isEmpty()) return null;

        hits.sort(Comparator.comparingInt((DenklemHit h) -> startYear(h.seasonLabel)).reversed());

        return new Signal(currentLabel, protagonistId, protagonistName, midName,
                target, previous, next, bridge,
                previous.opponentName(protagonistId), next.opponentName(protagonistId),
                bridge.opponentName(midId), hits);
    }

    /** Bir keçmiş sezonda eyni imzalı (Ə, S, V) bütün halları toplayır. */
    private static List<DenklemHit> findInSeason(List<LeagueMatch> season, String seasonLabel,
                                                 int prevOppId, int nextOppId, int bridgeId,
                                                 boolean onlySurprise) {
        List<DenklemHit> hits = new ArrayList<>();

        for (int teamId : teamIdsOf(season)) {
            List<LeagueMatch> timeline = fullTimeline(season, teamId);

            for (int j = 1; j < timeline.size() - 1; j++) {
                LeagueMatch star = timeline.get(j);
                if (!star.finished) continue;

                int p2 = timeline.get(j - 1).opponentId(teamId);
                int n2 = timeline.get(j + 1).opponentId(teamId);
                if (!samePair(p2, n2, prevOppId, nextOppId)) continue;

                int midId = star.opponentId(teamId);
                if (midId == prevOppId || midId == nextOppId) continue;

                LeagueMatch bridge = matchAfter(season, midId, star);
                if (bridge == null || bridge.opponentId(midId) != bridgeId) continue;

                String htFt = LeagueWidePatternMatcher.computeHtFtFull(star);
                if (onlySurprise && (htFt == null || !TARGET_HT_FT.contains(htFt))) continue;

                hits.add(new DenklemHit(seasonLabel, teamId, star.teamName(teamId),
                        timeline.get(j - 1), star, timeline.get(j + 1), bridge,
                        htFt, p2 == prevOppId && n2 == nextOppId));
            }
        }
        return hits;
    }

    /** {@code teamId}-nin {@code after} oyunundan SONRAKI ilk oyunu (oynanmamış da ola bilər). */
    private static LeagueMatch matchAfter(List<LeagueMatch> season, int teamId, LeagueMatch after) {
        List<LeagueMatch> timeline = fullTimeline(season, teamId);
        for (int i = 0; i < timeline.size(); i++) {
            if (timeline.get(i).matchId != after.matchId) continue;
            return i + 1 < timeline.size() ? timeline.get(i + 1) : null;
        }
        return null;
    }

    /** Qonşu rəqib cütü sıradan asılı olmadan eynidirmi. */
    private static boolean samePair(int a1, int a2, int b1, int b2) {
        return (a1 == b1 && a2 == b2) || (a1 == b2 && a2 == b1);
    }

    /**
     * Bir komandanın sezondakı bütün oyunları — {@link LeagueWidePattern#timelineOf}-dan
     * fərqli olaraq OYNANMAMIŞ oyunlar da saxlanılır (hədəf oyun onlardan biridir).
     */
    static List<LeagueMatch> fullTimeline(List<LeagueMatch> season, int teamId) {
        List<LeagueMatch> own = new ArrayList<>();
        for (LeagueMatch match : season) {
            if (match.involves(teamId)) own.add(match);
        }
        return own;
    }

    private static Set<Integer> teamIdsOf(List<LeagueMatch> season) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (LeagueMatch match : season) {
            ids.add(match.homeId);
            ids.add(match.awayId);
        }
        return ids;
    }

    private static String flip(String code) {
        String[] parts = code.split("/");
        if (parts.length != 2) return code;
        return flipToken(parts[0]) + "/" + flipToken(parts[1]);
    }

    private static String flipToken(String token) {
        return switch (token) {
            case "1" -> "2";
            case "2" -> "1";
            default  -> token;      // "X" olduğu kimi qalır
        };
    }

    /** Oynanmış oyun üçün skorlu sətir, oynanmamış üçün tarixli "vs" sətri. */
    private static String line(LeagueMatch match) {
        if (match == null) return "—";
        if (match.finished) return match.displayLine();
        String date = match.date != null ? match.date.toString() : ("hafta " + match.week);
        return match.homeName + " vs " + match.awayName + "  (" + date + ", oynanmayıb)";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Sezon feed-i — OYNANMAMIŞ oyunlar da daxil
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Bir liqa sezonunun bütün oyunları, xronoloji sıra ilə. {@link LeagueSeasonFetcher}
     * bitməmiş sətirləri atır; burada onlar {@code finished=false}, skor {@code -1} kimi
     * saxlanılır, çünki hədəf oyun / sonrakı rəqib / körpü məhz onlardır.
     */
    static List<LeagueMatch> fetchFullSeason(CloseableHttpClient http, int seasonId, String seasonLabel) {
        return FULL_SEASON_CACHE.computeIfAbsent(seasonId, id -> {
            List<LeagueMatch> matches = new ArrayList<>();
            try {
                List<Integer> weeks = fetchWeeks(http, id);
                if (weeks.isEmpty()) {
                    log.warn("seasonId {} üçün hafta siyahısı boşdur", id);
                    return matches;
                }
                for (int week : weeks) {
                    try {
                        matches.addAll(fetchWeekMatches(http, id, week, seasonLabel));
                    } catch (IOException e) {
                        log.warn("Sezon {} hafta {} alınmadı: {}", id, week, e.getMessage());
                    }
                }
                matches.sort(SezonDenklemiAnalyzer::compareChronologically);
                log.info("Sezon {} ({}) yükləndi: {} oyun / {} hafta",
                        seasonLabel, id, matches.size(), weeks.size());
            } catch (IOException e) {
                log.error("Sezon {} ({}) yüklənmədi: {}", seasonLabel, id, e.getMessage());
            }
            return matches;
        });
    }

    private static int compareChronologically(LeagueMatch a, LeagueMatch b) {
        // Tarix "dd/MM"-dən qurulur və ola bilməz; hafta nömrəsi ehtiyat sıralamadır.
        if (a.date != null && b.date != null) {
            int byDate = a.date.compareTo(b.date);
            if (byDate != 0) return byDate;
        }
        return Integer.compare(a.week, b.week);
    }

    private static List<Integer> fetchWeeks(CloseableHttpClient http, int seasonId) throws IOException {
        List<Integer> weeks = new ArrayList<>();
        String body = fetchText(http, String.format(WEEKS_URL, seasonId));
        if (body == null) return weeks;
        for (List<String> row : JsArrayParser.parseRows(body)) {
            if (row.isEmpty()) continue;
            Integer week = toInt(row.get(0));
            if (week != null) weeks.add(week);
        }
        return weeks;
    }

    private static List<LeagueMatch> fetchWeekMatches(CloseableHttpClient http, int seasonId,
                                                      int week, String seasonLabel) throws IOException {
        List<LeagueMatch> matches = new ArrayList<>();
        String body = fetchText(http, String.format(MATCHES_URL, seasonId, week));
        if (body == null) return matches;
        for (List<String> row : JsArrayParser.parseRows(body)) {
            LeagueMatch match = toMatch(row, week, seasonLabel);
            if (match != null) matches.add(match);
        }
        return matches;
    }

    /**
     * Sahə sırası writeFixture() (cm.mackolik.com/js5/Mackolik/Fixture.js) ilə eynidir:
     * 0=matchId, 1=dd/MM, 3=evId, 4=evAd, 5=depId, 6=depAd, 8=status, 9=evGol, 10=depGol,
     * 23=İY skoru ("1 - 0").
     */
    private static LeagueMatch toMatch(List<String> row, int week, String seasonLabel) {
        if (row.size() < 11) return null;

        Integer matchId = toInt(row.get(0));
        Integer homeId  = toInt(row.get(3));
        Integer awayId  = toInt(row.get(5));
        Integer status  = toInt(row.get(8));
        if (matchId == null || homeId == null || awayId == null || status == null) return null;

        String homeName = row.get(4);
        String awayName = row.get(6);
        if (homeName == null || awayName == null) return null;

        boolean finished = isFinished(status);

        int ftHome = -1;
        int ftAway = -1;
        int htHome = -1;
        int htAway = -1;

        if (finished) {
            Integer home = toInt(row.get(9));
            Integer away = toInt(row.get(10));
            if (home == null || away == null) return null;
            ftHome = home;
            ftAway = away;
            if (row.size() > 23) {
                int[] ht = parseHalfTime(row.get(23));
                if (ht != null) {
                    htHome = ht[0];
                    htAway = ht[1];
                }
            }
        }

        return new LeagueMatch(matchId, week, parseDate(row.get(1), seasonLabel),
                homeId, homeName, awayId, awayName,
                ftHome, ftAway, htHome, htAway, finished);
    }

    private static boolean isFinished(int statusCode) {
        for (int code : FINISHED_CODES) {
            if (code == statusCode) return true;
        }
        return false;
    }

    /** "1 - 0" → {1, 0}; yoxdursa/pozuqdursa null. */
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
     * Feed yalnız "dd/MM" verir. Avropa sezonu (2025/2026) iyul→iyun getdiyi üçün
     * iyul və sonrası birinci ilə, qalanı ikinci ilə aiddir. Braziliya kimi TƏQVİM İLİ
     * liqalarında etiket tək ildir ("2026") — orada bütün aylar həmin ilə aiddir.
     */
    private static LocalDate parseDate(String ddMM, String seasonLabel) {
        if (ddMM == null) return null;
        int startYear = startYear(seasonLabel);
        if (startYear <= 0) return null;

        String[] parts = ddMM.trim().split("/");
        if (parts.length != 2) return null;
        try {
            int day   = Integer.parseInt(parts[0].trim());
            int month = Integer.parseInt(parts[1].trim());
            int year  = isCalendarSeason(seasonLabel) ? startYear
                    : (month >= 7 ? startYear : startYear + 1);
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    /** "2026" və ya "2026/2026" → təqvim ili liqası. */
    private static boolean isCalendarSeason(String label) {
        if (label == null) return false;
        String[] parts = label.split("/");
        return parts.length == 1 || parts[0].trim().equals(parts[1].trim());
    }

    static int startYear(String label) {
        if (label == null) return -1;
        try {
            return Integer.parseInt(label.split("/")[0].trim());
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
    //  Sezon indeksi — təqvim ili etiketlərinə də icazə verir
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Liqanın bütün sezonları: "2025/2026" → 63860. {@link LeagueSeasonFetcher#fetchSeasonIndex}
     * yalnız "YYYY/YYYY" qəbul edir; Braziliya Serie A kimi liqalarda etiket "2025" olduğu üçün
     * burada hər iki forma oxunur.
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
                    if (!SEASON_LABEL.matcher(label).matches()) continue;   // "Euro 2024" və s. atılır
                    Integer value = toInt(option.attr("value").trim());
                    if (value != null) index.put(label, value);
                }
                log.debug("Anchor {} üçün {} sezon tapıldı", id, index.size());
            } catch (IOException e) {
                log.error("Sezon indeksi alınmadı (anchor {}): {}", id, e.getMessage());
            }
            return index;
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Günün proqramı (livedata group=all)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Başlamamış oyunlar. Feed bugün + sabahı birlikdə verir, ona görə {@code day}
     * ("dd/MM/yyyy") ilə süzülür; {@code day} null olduqda bütün günlər qaytarılır.
     */
    static List<Fixture> fetchFixtures(CloseableHttpClient http, String day) {
        List<Fixture> fixtures = new ArrayList<>();
        try {
            String body = fetchText(http, LIVEDATA_URL);
            if (body == null) {
                log.error("livedata boş cavab qaytardı");
                return fixtures;
            }

            JSONArray rows = new JSONObject(body).optJSONArray("m");
            if (rows == null) {
                log.error("livedata cavabında \"m\" massivi yoxdur");
                return fixtures;
            }

            int unstarted = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONArray row = rows.optJSONArray(i);
                Fixture fixture = toFixture(row);
                if (fixture == null) continue;
                unstarted++;
                if (day == null || day.equals(fixture.dateText)) fixtures.add(fixture);
            }
            log.info("livedata: {} başlamamış oyun, {} tarixi üçün {} oyun seçildi.",
                    unstarted, day == null ? "bütün günlər" : day, fixtures.size());
        } catch (IOException e) {
            log.error("livedata alınmadı: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("livedata parslanmadı: {}", e.getMessage());
        }
        return fixtures;
    }

    /**
     * livedata sətri: {@code [matchId, evId, "evAd", depId, "depAd", status, "statusMətn", ...,
     * "19:00", ..., "23/08/2026", [ölkəId, "Ölkə", liqaId, "Liqa", seasonId, "2026/2027", ...]]}.
     * Sətrin uzunluğu idman növünə/turnirə görə dəyişdiyi üçün saat, tarix və liqa bloku
     * sabit indekslə deyil, tipinə görə taranaraq tapılır.
     */
    private static Fixture toFixture(JSONArray row) {
        if (row == null || row.length() < 7) return null;
        if (row.optInt(5, -1) != STATUS_UNSTARTED) return null;

        int homeId = row.optInt(1, 0);
        int awayId = row.optInt(3, 0);
        if (homeId <= 0 || awayId <= 0) return null;

        String homeName = row.optString(2, "");
        String awayName = row.optString(4, "");
        if (homeName.isEmpty() || awayName.isEmpty()) return null;

        String kickoff  = "";
        String dateText = "";
        JSONArray league = null;

        for (int i = 6; i < row.length(); i++) {
            Object value = row.opt(i);
            if (value instanceof JSONArray array) {
                if (league == null && array.length() >= 6) league = array;
            } else if (value instanceof String text) {
                if (dateText.isEmpty() && DATE_TEXT.matcher(text).matches()) dateText = text;
                else if (kickoff.isEmpty() && TIME_TEXT.matcher(text).matches()) kickoff = text;
            }
        }

        String leagueName  = "";
        int seasonId       = 0;
        String seasonLabel = "";
        if (league != null) {
            String country = league.optString(1, "");
            String name    = league.optString(3, "");
            leagueName  = country.isEmpty() ? name : country + " · " + name;
            seasonId    = league.optInt(4, 0);
            seasonLabel = league.optString(5, "");
        }

        return new Fixture(homeId, homeName, awayId, awayName,
                kickoff, dateText, leagueName, seasonId, seasonLabel);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Bir oyun cütü üçün tapşırıq
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * AllInOneTactics üçün tək oyunluq giriş nöqtəsi. Liqa/sezon məlum deyilsə
     * komanda səhifəsindən tapılır; siqnal yoxdursa null.
     */
    public static String analyzeSinglePair(CloseableHttpClient http, int homeId, int awayId) {
        return new FixtureTask(Fixture.manual(homeId, awayId), http,
                DEFAULT_SEASON_LOOKBACK, true).call();
    }

    private static final class FixtureTask implements Callable<String> {

        private final Fixture fixture;
        private final CloseableHttpClient http;
        private final int lookback;
        private final boolean onlySurprise;

        FixtureTask(Fixture fixture, CloseableHttpClient http, int lookback, boolean onlySurprise) {
            this.fixture      = fixture;
            this.http         = http;
            this.lookback     = lookback;
            this.onlySurprise = onlySurprise;
        }

        @Override
        public String call() {
            try {
                return analyze();
            } catch (Exception e) {
                log.error("Oyun {} uğursuz: {}", fixture, e.getMessage(), e);
                return null;
            }
        }

        private String analyze() throws IOException {
            int seasonId      = fixture.seasonId;
            String leagueName = fixture.leagueName;
            String feedLabel  = fixture.seasonLabel;

            // Proqramdan gələn oyunlarda liqa artıq məlumdur; yalnız əl ilə verilən
            // cütlər üçün komanda səhifəsi yoxlanılır.
            if (seasonId <= 0) {
                LeagueRef league = resolveLeagueWithFallback(fixture.homeId);
                if (league == null) league = resolveLeagueWithFallback(fixture.awayId);
                if (league == null) {
                    log.debug("Oyun {}: liqa təyin edilmədi", fixture);
                    return null;
                }
                seasonId   = league.seasonId;
                leagueName = league.leagueName;
                feedLabel  = league.seasonLabel;
            }

            Map<String, Integer> index = fetchSeasonIndex(http, seasonId);

            // Etiket tarix parslamasını dəyişdiyi üçün (təqvim ili ↔ Avropa sezonu)
            // həqiqi etiket seasonId üzrə puan durumu siyahısından götürülür; feed-dəki
            // etiket yalnız ehtiyatdır.
            String currentLabel = labelOf(index, seasonId, feedLabel);

            List<LeagueMatch> current = fetchFullSeason(http, seasonId, currentLabel);
            if (current.isEmpty()) return null;

            Map<String, List<LeagueMatch>> past = loadPastSeasons(index, seasonId, currentLabel);
            if (past.isEmpty()) {
                log.debug("Oyun {}: keçmiş sezon tapılmadı", fixture);
                return null;
            }

            // Hər iki istiqamət: protaqonist ev sahibi də ola bilər, qonaq da.
            Signal fromHome = detect(current, currentLabel,
                    fixture.homeId, fixture.homeName, fixture.awayId, fixture.awayName,
                    past, onlySurprise);
            Signal fromAway = detect(current, currentLabel,
                    fixture.awayId, fixture.awayName, fixture.homeId, fixture.homeName,
                    past, onlySurprise);

            StringBuilder sb = new StringBuilder();
            if (fromHome != null) sb.append(fromHome.render(leagueName));
            if (fromAway != null) sb.append(fromAway.render(leagueName));
            return sb.length() == 0 ? null : sb.toString();
        }

        /** {@code seasonId}-nin puan durumu siyahısındakı həqiqi etiketi. */
        private String labelOf(Map<String, Integer> index, int seasonId, String fallback) {
            for (Map.Entry<String, Integer> entry : index.entrySet()) {
                if (entry.getValue() == seasonId) return entry.getKey();
            }
            log.debug("seasonId {} indeksdə yoxdur, etiket '{}' olaraq qalır", seasonId, fallback);
            return fallback;
        }

        /** Cari sezondan əvvəlki ən yaxın {@code lookback} sezon. */
        private Map<String, List<LeagueMatch>> loadPastSeasons(Map<String, Integer> index,
                                                               int currentSeasonId, String currentLabel) {
            int currentYear = startYear(currentLabel);

            List<Map.Entry<String, Integer>> older = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : index.entrySet()) {
                int year = startYear(entry.getKey());
                if (year > 0 && year < currentYear && entry.getValue() != currentSeasonId) {
                    older.add(entry);
                }
            }
            older.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> startYear(e.getKey())).reversed());

            Map<String, List<LeagueMatch>> seasons = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : older) {
                if (seasons.size() >= lookback) break;
                List<LeagueMatch> matches = fetchFullSeason(http, entry.getValue(), entry.getKey());
                if (!matches.isEmpty()) seasons.put(entry.getKey(), matches);
            }
            return seasons;
        }

        /**
         * Komanda səhifəsi həm "2025/2026", həm də təqvim ili ("2026") etiketi ilə açıla bilir;
         * cari il tapılmasa bir neçə il geri sınanır. Burada məqsəd yalnız liqanı (seasonId)
         * tapmaqdır — etiket sonra {@link #labelOf} ilə dəqiqləşdirilir.
         */
        private LeagueRef resolveLeagueWithFallback(int teamId) throws IOException {
            int thisYear = LocalDate.now().getYear();
            for (int back = 0; back < MAX_LABEL_FALLBACK; back++) {
                int year = thisYear - back;
                for (String label : List.of(String.valueOf(year), year + "/" + (year + 1))) {
                    LeagueRef ref = LeagueSeasonFetcher.resolveLeague(http, teamId, label);
                    if (ref != null) return ref;
                }
            }
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
                log.warn("HTTP {} — {}", status, url);
                return null;
            }
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  main
    // ═══════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        boolean onlySurprise = true;
        int lookback = DEFAULT_SEASON_LOOKBACK;
        String day = LocalDate.now().format(DAY_FORMAT);
        String leagueFilter = null;
        int maxFixtures = 0;                      // 0 = limitsiz
        List<Fixture> fixtures = new ArrayList<>();

        for (String arg : args == null ? new String[0] : args) {
            if ("--hamisi".equalsIgnoreCase(arg)) {
                onlySurprise = false;
            } else if ("--butun-gunler".equalsIgnoreCase(arg)) {
                day = null;                                   // feed bugün + sabahı verir
            } else if (arg.startsWith("--sezon=")) {
                Integer value = toInt(arg.substring("--sezon=".length()));
                if (value != null && value > 0) lookback = value;
            } else if (arg.startsWith("--tarix=")) {
                String value = arg.substring("--tarix=".length()).trim();
                if (DATE_TEXT.matcher(value).matches()) day = value;
                else log.warn("Keçərsiz tarix (dd/MM/yyyy gözlənilir): {}", value);
            } else if (arg.startsWith("--liqa=")) {
                leagueFilter = arg.substring("--liqa=".length()).trim().toLowerCase(Locale.ROOT);
            } else if (arg.startsWith("--max=")) {
                Integer value = toInt(arg.substring("--max=".length()));
                if (value != null && value > 0) maxFixtures = value;
            } else {
                Fixture fixture = parseFixtureArg(arg);
                if (fixture != null) fixtures.add(fixture);
            }
        }

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(NUM_THREADS + 5);
        cm.setDefaultMaxPerRoute(NUM_THREADS);
        CloseableHttpClient http = HttpClients.custom().setConnectionManager(cm).build();

        boolean manual = !fixtures.isEmpty();
        if (fixtures.isEmpty()) {
            fixtures = fetchFixtures(http, day);

            if (leagueFilter != null && !leagueFilter.isEmpty()) {
                String needle = leagueFilter;
                fixtures.removeIf(f -> !f.leagueName.toLowerCase(Locale.ROOT).contains(needle));
            }
            // Sezonlar liqa başına keşlənir, ona görə yük oyun sayından çox FƏRQLİ LİQA
            // sayından asılıdır: hər liqa üçün (1 + lookback) sezon × ~38 həftə sorğu gedir.
            if (maxFixtures > 0 && fixtures.size() > maxFixtures) {
                fixtures = new ArrayList<>(fixtures.subList(0, maxFixtures));
            }
        }

        // SLF4J konfiqindən asılı olmayaraq görünsün deyə xülasə birbaşa stdout-a.
        System.out.println("════════ SEZON DENKLEMİ — QƏLİB DEDEKTORU ════════");
        System.out.println("Denklem: [Ə əvvəlki rəqib] → ★hədəf → [S sonrakı rəqib] + orta halqanın körpüsü (V)");
        System.out.println("Keçmiş sezon sayı : " + lookback);
        System.out.println("Filtr             : "
                + (onlySurprise ? "yalnız sürprizlə bitmiş denklemlər" : "bütün nəticələr (--hamisi)"));
        System.out.println(manual
                ? "Mənbə: əl ilə verilən " + fixtures.size() + " oyun."
                : "Mənbə: livedata proqramı (" + (day == null ? "bütün günlər" : day) + ") — "
                        + fixtures.size() + " başlamamış oyun"
                        + (leagueFilter == null ? "" : ", liqa filtri: '" + leagueFilter + "'")
                        + (maxFixtures > 0 ? ", limit: " + maxFixtures : "") + ".");

        if (fixtures.isEmpty()) {
            System.out.println("Analiz ediləcək oyun yoxdur.");
            System.out.println("Səbəb ola bilər: seçilmiş tarixdə başlamamış oyun qalmayıb (hamısı oynanıb).");
            System.out.println("Sabahı da görmək üçün:  --butun-gunler   ·   başqa gün üçün:  --tarix=24/08/2026");
            System.out.println("Sınamaq üçün əl ilə oyun ver:  SezonDenklemiAnalyzer evId:deplasmanId");
            closeQuietly(http);
            System.exit(0);
        }

        printLeagueBreakdown(fixtures);

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<String>> futures = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            futures.add(executor.submit(new FixtureTask(fixture, http, lookback, onlySurprise)));
        }
        System.out.println(futures.size() + " oyun analiz edilir...\n");

        int found = 0;
        for (Future<String> future : futures) {
            try {
                String result = future.get();
                if (result != null && !result.isEmpty()) {
                    System.out.println(result);
                    System.out.println("══════════════════════════════════════════════");
                    found++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.error("Tapşırıq xətası: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
        }

        System.out.println("\n════════ NƏTİCƏ ════════");
        System.out.println("Analiz edilən oyun            : " + futures.size());
        System.out.println("Denklem siqnalı tapılan oyun  : " + found);
        if (found == 0) {
            System.out.println("→ Bugün bu qəlibə uyğun oyun YOXDUR. (Qəlib çox nadirdir — boş nəticə normaldır.)");
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(180, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        closeQuietly(http);
        System.exit(0);
    }

    /** "evId:deplasmanId" (test / əl ilə seçim üçün). */
    private static Fixture parseFixtureArg(String arg) {
        String[] parts = arg.split("[:\\-]");
        if (parts.length != 2) {
            log.warn("Keçərsiz arqument (evId:deplasmanId gözlənilir): {}", arg);
            return null;
        }
        Integer homeId = toInt(parts[0]);
        Integer awayId = toInt(parts[1]);
        if (homeId == null || awayId == null) {
            log.warn("Keçərsiz ID cütü: {}", arg);
            return null;
        }
        return Fixture.manual(homeId, awayId);
    }

    /**
     * Neçə oyunun hansı liqadan gəldiyini göstərir — sezon endirilməsi liqa başına
     * olduğu üçün bu, gözlənilən yükü də əvvəlcədən oxunaqlı edir.
     */
    private static void printLeagueBreakdown(List<Fixture> fixtures) {
        Map<String, Integer> perLeague = new LinkedHashMap<>();
        for (Fixture fixture : fixtures) {
            String key = fixture.leagueName.isEmpty() ? "(liqa bilinmir)" : fixture.leagueName;
            perLeague.merge(key, 1, Integer::sum);
        }
        System.out.println("Fərqli liqa       : " + perLeague.size());
        perLeague.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> System.out.printf("   %-45s %d oyun%n", e.getKey(), e.getValue()));
        if (perLeague.size() > 10) System.out.println("   … və daha " + (perLeague.size() - 10) + " liqa");
    }

    private static void closeQuietly(CloseableHttpClient http) {
        try {
            http.close();
        } catch (IOException e) {
            log.error("HttpClient bağlanmadı: {}", e.getMessage());
        }
    }
}
