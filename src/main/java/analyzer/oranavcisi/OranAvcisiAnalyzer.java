package analyzer.oranavcisi;

import analyzer.mackolik.patternfinder.OnlyLeagueScraper;
import analyzer.util.MackolikHttpFetcher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * "ORAN AVCISI" — MAÇ SONU 0 (İY/MS 1/0 · 2/0) beraberlik ovu.
 *
 * <p>Hədəf bazar: <b>maç sonu beraberlik</b>. Taktikanın adındakı "1/0" və "2/0"
 * Türk kuponu yazılışıdır (0 = beraberlik), yəni İY/MS <b>1/X</b> və <b>2/X</b>:
 * ilk yarını öndə bitirən komanda qalib gələ bilmir. Bu, paketin qalanındakı
 * "sürpriz" siyahısı ilə eyni məntiqdir.
 *
 * <p><b>Denklem — MƏCBURİ şərtlər</b> (üçü də ödənməlidir):
 * <ol>
 *   <li>Hər iki komandanın son 5 oyununda (cəmi 10) ən azı <b>2 beraberlik</b></li>
 *   <li>Ən vacibi: <b>MS 0 (X) oranı 3.05-dən aşağı</b> — 3.00-dan aşağı daha yaxşı</li>
 *   <li>Favorit komandanın oranı <b>1.80 və yuxarı</b> — 2.00 və yuxarı daha makbul</li>
 * </ol>
 *
 * <p><b>Dəstəkləyici (bal verən) şərtlər:</b>
 * <ul>
 *   <li>2.5 ALT/ÜST oranlarının hər ikisi 1.60–1.70 aralığında → oyun kısır gedir</li>
 *   <li>və ya 2.5 ALT oranı 1.50 civarına enir</li>
 *   <li>Beraberliklərin ən azı <b>2-si 1-1</b> hesabı</li>
 *   <li>Son oyunlarda 2-0 / 0-2 hesabı çıxarmış olmaq</li>
 * </ul>
 *
 * <p><b>Xəbərdarlıq (taktikanın öz qeydi):</b> bu şərtlərə uyan bəzi oyunlar
 * beraberlik yerinə 2/1, 1/2 və ya ilk yarı 2-1 · 1-2 kimi nəticələr verir.
 * Sinif bunu gizlətmir — nəticə siyahısında həmişə həmin qeyd çap olunur.
 *
 * <p><b>Məlumat mənbələri</b> (ikisi də Mackolik, Playwright/verilənlər bazası lazım deyil):
 * <ul>
 *   <li>Oranlar + günün proqramı: {@code vd.mackolik.com/livedata?group=all}. Sətirdəki
 *       beş oran sahəsi sırayla <b>MS 1, MS 0, MS 2, 2.5 ALT, 2.5 ÜST</b>-dür
 *       (Man City–Bournemouth 1.30 / 4.52 / 5.40 / 2.52 / 1.29 ilə yoxlanıb).
 *       Yəni taktikanın istədiyi hər üç bazar bir sorğuda gəlir.</li>
 *   <li>Son 5 oyun: {@code arsiv.mackolik.com/Team/Default.aspx} fikstür cədvəli —
 *       sətirdəki {@code side} və {@code data-opponent} atributları sayəsində
 *       ad uyğunlaşdırmasına ehtiyac qalmır. Sezon başında son 5 oyun keçmiş
 *       sezona sarxdığı üçün iki sezon birlikdə oxunur.</li>
 * </ul>
 *
 * <p><b>Sıralama qərəzli deyil, ucuzdur:</b> əvvəlcə YALNIZ oran şərtləri yoxlanılır
 * (bir sorğu, bütün oyunlar), forma səhifələri isə yalnız oran filtrindən keçən
 * oyunlar üçün endirilir — 500+ oyunluq gündə minlərlə artıq sorğunu aradan qaldırır.
 *
 * <p>İşlətmə:
 * <pre>
 *   java ... OranAvcisiAnalyzer                        → bugün
 *   java ... OranAvcisiAnalyzer --tarix=24/08/2026
 *   java ... OranAvcisiAnalyzer --butun-gunler
 *   java ... OranAvcisiAnalyzer --x-max=3.00 --fav-min=2.00 --min-beraberlik=3
 *   java ... OranAvcisiAnalyzer --liqa="süper lig" --yalniz-lig
 * </pre>
 */
public class OranAvcisiAnalyzer {

    private static final String LIVEDATA_URL = "https://vd.mackolik.com/livedata?group=all";
    private static final String TEAM_URL     = "https://arsiv.mackolik.com/Team/Default.aspx?id=%d&season=%s";

    private static final int NUM_THREADS       = 8;
    private static final long MIN_REQUEST_GAP  = 80L;

    /** livedata-da başlamamış oyunun status kodu. */
    private static final int STATUS_UNSTARTED = 0;

    private static final Pattern DATE_TEXT  = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");
    private static final Pattern TIME_TEXT  = Pattern.compile("\\d{1,2}:\\d{2}");
    private static final Pattern ODDS_TEXT  = Pattern.compile("\\d+\\.\\d+");
    private static final Pattern SCORE_TEXT = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");

    private static final DateTimeFormatter DAY_FORMAT  = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter PAGE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // ═══════════════════════════════════════════════════════════════════════
    //  Taktikanın hədləri
    // ═══════════════════════════════════════════════════════════════════════

    /** Mətndəki bütün rəqəmlər tək yerdə — arqumentlərlə dəyişdirilə bilir. */
    static final class Limits {
        double xMax        = 3.05;   // MS 0 bundan AŞAĞI olmalıdır (məcburi)
        double xGood       = 3.00;   // bundan aşağı daha yaxşı
        double xBest       = 2.90;
        double favMin      = 1.80;   // favoritin minimum oranı (məcburi)
        double favGood     = 2.00;   // "daha makbul"
        double underGood   = 1.70;   // 2.5 ALT
        double underBest   = 1.55;   // "1.50 civarı"
        double tightLow    = 1.60;   // ALT və ÜST hər ikisi bu aralıqda → kısır
        double tightHigh   = 1.70;
        int    minDraws    = 2;      // 10 oyunda ən azı bu qədər beraberlik (məcburi)
        int    idealDraws  = 3;      // "2 və ya 3" bandının yuxarı ucu
        int    minOneOne   = 2;      // beraberliklərin ən azı bu qədəri 1-1
        int    formSize    = 5;      // komanda başına neçə oyun
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Model
    // ═══════════════════════════════════════════════════════════════════════

    /** Günün proqramındakı bir oyun — oranları ilə birlikdə. */
    static final class Fixture {
        final int homeId;
        final String homeName;
        final int awayId;
        final String awayName;
        final String kickoff;
        final String dateText;
        final String leagueName;
        final String seasonLabel;
        /** MS 1 · MS 0 (X) · MS 2 · 2.5 ALT · 2.5 ÜST; oran yoxdursa 0. */
        final double ms1, msX, ms2, under25, over25;

        Fixture(int homeId, String homeName, int awayId, String awayName,
                String kickoff, String dateText, String leagueName, String seasonLabel,
                double ms1, double msX, double ms2, double under25, double over25) {
            this.homeId      = homeId;
            this.homeName    = homeName;
            this.awayId      = awayId;
            this.awayName    = awayName;
            this.kickoff     = kickoff;
            this.dateText    = dateText;
            this.leagueName  = leagueName;
            this.seasonLabel = seasonLabel;
            this.ms1         = ms1;
            this.msX         = msX;
            this.ms2         = ms2;
            this.under25     = under25;
            this.over25      = over25;
        }

        boolean hasOdds() {
            return ms1 > 0 && msX > 0 && ms2 > 0;
        }

        /** Favoritin oranı — iki tərəfdən kiçik olanı. */
        double favouriteOdds() {
            return Math.min(ms1, ms2);
        }

        String favouriteName() {
            return ms1 <= ms2 ? homeName : awayName;
        }

        String title() {
            return homeName + " – " + awayName;
        }
    }

    /** Bir komandanın son oyunlarından biri, həmin komandanın gözü ilə. */
    static final class FormMatch {
        final LocalDate date;
        final String opponentName;
        final boolean home;
        final int goalsFor;
        final int goalsAgainst;
        final String competition;

        FormMatch(LocalDate date, String opponentName, boolean home,
                  int goalsFor, int goalsAgainst, String competition) {
            this.date         = date;
            this.opponentName = opponentName;
            this.home         = home;
            this.goalsFor     = goalsFor;
            this.goalsAgainst = goalsAgainst;
            this.competition  = competition;
        }

        boolean draw()   { return goalsFor == goalsAgainst; }
        boolean oneOne() { return goalsFor == 1 && goalsAgainst == 1; }
        /** 2-0 və ya 0-2 — hansı tərəfin xeyrinə olmasından asılı olmayaraq. */
        boolean twoNil() {
            return (goalsFor == 2 && goalsAgainst == 0) || (goalsFor == 0 && goalsAgainst == 2);
        }

        String line() {
            return String.format(Locale.ROOT, "%s  %-22s %s %d-%d  %-18s%s",
                    date != null ? date.format(PAGE_FORMAT) : "??.??.????",
                    trim(opponentName, 22),
                    home ? "(E)" : "(D)",
                    goalsFor, goalsAgainst,
                    trim(competition, 18),
                    draw() ? (oneOne() ? "✓BERABERE 1-1" : "✓BERABERE") : (twoNil() ? "· 2-0/0-2" : ""));
        }
    }

    /** Bir komandanın forması. */
    static final class TeamForm {
        final int teamId;
        final String teamName;
        final List<FormMatch> matches;

        TeamForm(int teamId, String teamName, List<FormMatch> matches) {
            this.teamId   = teamId;
            this.teamName = teamName;
            this.matches  = matches;
        }

        int draws()   { return (int) matches.stream().filter(FormMatch::draw).count(); }
        int oneOnes() { return (int) matches.stream().filter(FormMatch::oneOne).count(); }
        int twoNils() { return (int) matches.stream().filter(FormMatch::twoNil).count(); }
    }

    /** Bir oyunun qiymətləndirilməsi. */
    static final class Verdict {
        final Fixture fixture;
        final TeamForm homeForm;
        final TeamForm awayForm;
        final int score;
        final List<String> pros;
        final List<String> notes;

        Verdict(Fixture fixture, TeamForm homeForm, TeamForm awayForm,
                int score, List<String> pros, List<String> notes) {
            this.fixture  = fixture;
            this.homeForm = homeForm;
            this.awayForm = awayForm;
            this.score    = score;
            this.pros     = pros;
            this.notes    = notes;
        }

        int draws()   { return homeForm.draws() + awayForm.draws(); }
        int oneOnes() { return homeForm.oneOnes() + awayForm.oneOnes(); }
        int twoNils() { return homeForm.twoNils() + awayForm.twoNils(); }

        /** 12 mümkün baldan: ★★★ demək olar ki hər üstünlüyün ödənməsi deməkdir. */
        String stars() {
            if (score >= 10) return "★★★";
            if (score >= 7)  return "★★";
            return "★";
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append('\n').append(stars()).append("  ").append(fixture.title())
                    .append("   [").append(score).append(" bal]\n");
            sb.append("   ").append(fixture.leagueName)
                    .append("  ·  ").append(fixture.dateText).append(' ').append(fixture.kickoff).append('\n');
            sb.append(String.format(Locale.ROOT, "   ORANLAR : MS1 %.2f  ·  MS0 %.2f  ·  MS2 %.2f  ·  2.5ALT %.2f  ·  2.5ÜST %.2f%n",
                    fixture.ms1, fixture.msX, fixture.ms2, fixture.under25, fixture.over25));
            sb.append(String.format(Locale.ROOT, "   Favorit : %s (%.2f)   ·   10 oyunda beraberlik: %d   ·   1-1: %d   ·   2-0/0-2: %d%n",
                    fixture.favouriteName(), fixture.favouriteOdds(), draws(), oneOnes(), twoNils()));

            sb.append("   ──────────────────────────────────────────────\n");
            appendForm(sb, homeForm);
            appendForm(sb, awayForm);

            sb.append("   ──────────────────────────────────────────────\n");
            for (String pro : pros) sb.append("   + ").append(pro).append('\n');
            for (String note : notes) sb.append("   ! ").append(note).append('\n');
            sb.append("   >>> HƏDƏF: MAÇ SONU 0  (İY/MS 1/0 · 2/0)\n");
            return sb.toString();
        }

        private void appendForm(StringBuilder sb, TeamForm form) {
            sb.append("   ").append(form.teamName)
                    .append("  — son ").append(form.matches.size()).append(" oyun, ")
                    .append(form.draws()).append(" beraberlik\n");
            for (FormMatch match : form.matches) sb.append("      ").append(match.line()).append('\n');
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Addım 1 — oran filtri (şəbəkəsiz, saf məntiq)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Oyunun YALNIZ oran şərtlərini yoxlayır. Forma məlumatı hələ lazım deyil,
     * ona görə bu, minlərlə komanda səhifəsini endirməzdən əvvəl işləyir.
     *
     * @return uyğunsuzluğun səbəbi; oyun keçirsə {@code null}
     */
    static String oddsRejection(Fixture fixture, Limits limits) {
        if (!fixture.hasOdds()) return "oran yoxdur";
        if (fixture.msX >= limits.xMax) {
            return String.format(Locale.ROOT, "MS0 %.2f ≥ %.2f", fixture.msX, limits.xMax);
        }
        if (fixture.favouriteOdds() < limits.favMin) {
            return String.format(Locale.ROOT, "favorit %.2f < %.2f", fixture.favouriteOdds(), limits.favMin);
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Addım 2 — forma + bal (şəbəkəsiz, saf məntiq)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Oran filtrindən keçmiş oyunu forma ilə birlikdə qiymətləndirir.
     *
     * @return şərtləri ödəməyən oyun üçün {@code null}
     */
    static Verdict evaluate(Fixture fixture, TeamForm homeForm, TeamForm awayForm, Limits limits) {
        int draws   = homeForm.draws() + awayForm.draws();
        int oneOnes = homeForm.oneOnes() + awayForm.oneOnes();
        int twoNils = homeForm.twoNils() + awayForm.twoNils();

        if (draws < limits.minDraws) return null;          // 1-ci məcburi şərt

        int score = 0;
        List<String> pros  = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // Bal YALNIZ məcburi həddin üstünə çıxan üstünlüklərə verilir. Şərti sadəcə
        // ödəmək bal qazandırmır — əks halda filtri keçən hər oyun avtomatik yüksək
        // bal alır və ulduzlar oyunları bir-birindən ayırd etmir.
        // Maksimum: MS0 3 + favorit 2 + 2.5 3 + beraberlik bandı 1 + 1-1 2 + 2-0 1 = 12.

        // ── MS 0 oranı: taktikanın "ən önemlisi"
        pros.add(String.format(Locale.ROOT, "MS0 %.2f — %.2f həddinin altında (məcburi şərt ödənir)",
                fixture.msX, limits.xMax));
        if (fixture.msX < limits.xGood) {
            score += 2;
            pros.add(String.format(Locale.ROOT, "MS0 %.2f < %.2f — 3.00 altına düşməsi daha yaxşıdır", fixture.msX, limits.xGood));
        }
        if (fixture.msX < limits.xBest) {
            score += 1;
            pros.add(String.format(Locale.ROOT, "MS0 %.2f çox qısa — bazar beraberliyi ciddi qiymətləndirir", fixture.msX));
        }

        // ── Favorit
        if (fixture.favouriteOdds() >= limits.favGood) {
            score += 2;
            pros.add(String.format(Locale.ROOT, "favorit %s %.2f ≥ %.2f — 2.00 üstü oyunlar daha makbul adaydır",
                    fixture.favouriteName(), fixture.favouriteOdds(), limits.favGood));
        } else {
            pros.add(String.format(Locale.ROOT, "favorit %s %.2f — 1.80 üstü, amma 2.00 altı",
                    fixture.favouriteName(), fixture.favouriteOdds()));
        }

        // ── 2.5 ALT/ÜST — oyunun kısırlığı
        boolean tight = fixture.under25 >= limits.tightLow && fixture.under25 <= limits.tightHigh
                && fixture.over25 >= limits.tightLow && fixture.over25 <= limits.tightHigh;
        // Üç hal bir-birini istisna edir, ona görə buradan ən çoxu 3 bal gəlir.
        if (fixture.under25 > 0 && fixture.under25 <= limits.underBest) {
            score += 3;
            pros.add(String.format(Locale.ROOT, "2.5 ALT %.2f — 1.50 civarı, az qollu oyun gözlənilir", fixture.under25));
        } else if (tight) {
            score += 2;
            pros.add(String.format(Locale.ROOT, "2.5 ALT/ÜST %.2f / %.2f — hər ikisi %.2f–%.2f bandında, oyun kısır gedir",
                    fixture.under25, fixture.over25, limits.tightLow, limits.tightHigh));
        } else if (fixture.under25 > 0 && fixture.under25 <= limits.underGood) {
            score += 1;
            pros.add(String.format(Locale.ROOT, "2.5 ALT %.2f ≤ %.2f — az qollu oyun tərəfindədir",
                    fixture.under25, limits.underGood));
        }

        // ── Beraberlik sayı və keyfiyyəti
        if (draws >= limits.minDraws && draws <= limits.idealDraws) {
            score += 1;
            pros.add(draws + " beraberlik — taktikanın işarə etdiyi 2–3 bandında");
        } else {
            pros.add(draws + " beraberlik — 2–3 bandından yuxarı, komandalar daha da yenişməyəndir");
        }
        if (oneOnes >= limits.minOneOne) {
            score += 2;
            pros.add(oneOnes + " ədəd 1-1 hesabı — mühüm faktor");
        }
        if (twoNils > 0) {
            score += 1;
            pros.add(twoNils + " ədəd 2-0 / 0-2 hesabı — nəzərə alınması istənən detal");
        }

        // ── Taktikanın öz xəbərdarlığı, hər nəticədə görünür
        notes.add("XƏBƏRDARLIQ: bu şərtlərə uyan bəzi oyunlar beraberlik yerinə 2/1 · 1/2 "
                + "və ya ilk yarı 2-1 · 1-2 kimi nəticə verir.");
        if (fixture.over25 > 0 && fixture.over25 < limits.tightLow) {
            notes.add(String.format(Locale.ROOT, "2.5 ÜST %.2f qısadır — bazar qol gözləyir, kısırlıq şərti zəifdir",
                    fixture.over25));
        }

        return new Verdict(fixture, homeForm, awayForm, score, pros, notes);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Günün proqramı + oranlar
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Başlamamış oyunlar. Feed bugün + sabahı birlikdə verir, ona görə {@code day}
     * ("dd/MM/yyyy") ilə süzülür; {@code day} null olduqda bütün günlər qaytarılır.
     */
    static List<Fixture> fetchFixtures(MackolikHttpFetcher http, String day) {
        List<Fixture> fixtures = new ArrayList<>();

        String body = http.fetchHtml(LIVEDATA_URL);
        if (body == null) {
            System.err.println("livedata alınmadı.");
            return fixtures;
        }

        JSONArray rows;
        try {
            rows = new JSONObject(body).optJSONArray("m");
        } catch (RuntimeException e) {
            System.err.println("livedata parslanmadı: " + e.getMessage());
            return fixtures;
        }
        if (rows == null) {
            System.err.println("livedata cavabında \"m\" massivi yoxdur.");
            return fixtures;
        }

        for (int i = 0; i < rows.length(); i++) {
            Fixture fixture = toFixture(rows.optJSONArray(i));
            if (fixture == null) continue;
            if (day == null || day.equals(fixture.dateText)) fixtures.add(fixture);
        }
        return fixtures;
    }

    /**
     * livedata sətri: {@code [matchId, evId, "evAd", depId, "depAd", status, "statusMətn", ...,
     * "19:00", 0, "1.30", "4.52", "5.40", "2.52", "1.29", 1, "0.0"…, "23/08/2026",
     * [ölkəId, "Ölkə", liqaId, "Liqa", seasonId, "2026/2027", …]]}.
     *
     * <p>Sətrin uzunluğu turnirə görə dəyişdiyi üçün heç nə sabit indekslə oxunmur:
     * saat, tarix və liqa bloku tipinə görə tapılır, beş oran isə saatdan SONRAKI
     * ilk beş onluq mətndir (ondan sonrakı "0.0" sahələri başqa bazarlardır).
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
        int timeIndex = -1;

        for (int i = 6; i < row.length(); i++) {
            Object value = row.opt(i);
            if (value instanceof JSONArray array) {
                if (league == null && array.length() >= 6) league = array;
            } else if (value instanceof String text) {
                if (dateText.isEmpty() && DATE_TEXT.matcher(text).matches()) {
                    dateText = text;
                } else if (kickoff.isEmpty() && TIME_TEXT.matcher(text).matches()) {
                    kickoff = text;
                    timeIndex = i;
                }
            }
        }

        double[] odds = readOdds(row, timeIndex);

        String leagueName  = "";
        String seasonLabel = "";
        if (league != null) {
            String country = league.optString(1, "");
            String name    = league.optString(3, "");
            leagueName  = country.isEmpty() ? name : country + " · " + name;
            seasonLabel = league.optString(5, "");
        }

        return new Fixture(homeId, homeName, awayId, awayName,
                kickoff, dateText, leagueName, seasonLabel,
                odds[0], odds[1], odds[2], odds[3], odds[4]);
    }

    /** Saat sahəsindən sonrakı ilk beş onluq mətn: MS1, MS0, MS2, 2.5 ALT, 2.5 ÜST. */
    private static double[] readOdds(JSONArray row, int timeIndex) {
        double[] odds = new double[5];
        if (timeIndex < 0) return odds;

        int found = 0;
        for (int i = timeIndex + 1; i < row.length() && found < odds.length; i++) {
            Object value = row.opt(i);
            if (!(value instanceof String text)) continue;
            if (!ODDS_TEXT.matcher(text).matches()) continue;
            try {
                odds[found++] = Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return odds;
            }
        }
        return odds;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Komandanın son oyunları
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Komandanın son {@code count} oynanmış oyunu (bütün turnirlər), ən yenisi sonda.
     *
     * <p>Sezon başında son 5 oyun keçmiş sezona sarxır, ona görə cari sezonun
     * oyunları yetmirsə əvvəlki sezon da oxunur. Keçmiş sezon səhifəsi dəyişməz
     * olduğu üçün disk önbelleğine yazılır, cari sezon isə heç vaxt.
     */
    static TeamForm fetchForm(MackolikHttpFetcher http, int teamId, String teamName,
                              String seasonLabel, int count, boolean leagueOnly) {
        List<FormMatch> played = new ArrayList<>(readSeason(http, teamId, seasonLabel, false, leagueOnly));

        if (played.size() < count) {
            String previous = previousSeason(seasonLabel);
            if (previous != null) played.addAll(readSeason(http, teamId, previous, true, leagueOnly));
        }

        played.sort(Comparator.comparing(
                (FormMatch m) -> m.date == null ? LocalDate.MIN : m.date));

        int from = Math.max(0, played.size() - count);
        return new TeamForm(teamId, teamName, new ArrayList<>(played.subList(from, played.size())));
    }

    /**
     * Bir sezon səhifəsindəki OYNANMIŞ oyunlar.
     *
     * <p>Sətirdəki {@code side} atributu komandanın ev/deplasman olduğunu birbaşa
     * verir, ona görə komandanın adını sətirlərdən təxmin etməyə ehtiyac yoxdur.
     */
    private static List<FormMatch> readSeason(MackolikHttpFetcher http, int teamId,
                                              String seasonLabel, boolean cacheable, boolean leagueOnly) {
        List<FormMatch> matches = new ArrayList<>();
        if (seasonLabel == null || seasonLabel.isEmpty()) return matches;

        Document doc = http.fetchDocument(String.format(TEAM_URL, teamId, seasonLabel), cacheable);
        if (doc == null) return matches;

        Element tbody = doc.selectFirst("#tblFixture > tbody");
        if (tbody == null) return matches;

        String competition = "";
        boolean skipBlock = false;
        for (Element row : tbody.select("tr")) {
            if (row.hasClass("competition")) {
                competition = text(row, "a");
                skipBlock = leagueOnly
                        ? !OnlyLeagueScraper.isLeagueCompetition(competition)
                        : isFriendly(competition);
                continue;
            }
            if (skipBlock) continue;

            String side = row.attr("side");
            if (side.isEmpty()) continue;               // başlıq / boş sətir
            boolean home = "home".equalsIgnoreCase(side);

            String scoreText = text(row, "td:nth-child(5) b a");
            var score = SCORE_TEXT.matcher(scoreText);
            if (!score.find()) continue;                // oynanmamış oyun

            int homeGoals, awayGoals;
            try {
                homeGoals = Integer.parseInt(score.group(1));
                awayGoals = Integer.parseInt(score.group(2));
            } catch (NumberFormatException e) {
                continue;
            }

            String opponent = text(row, home ? "td:nth-child(7)" : "td:nth-child(3)");
            if (opponent.isEmpty()) continue;

            matches.add(new FormMatch(parseDate(text(row, "td:nth-child(1)")), opponent, home,
                    home ? homeGoals : awayGoals, home ? awayGoals : homeGoals, competition));
        }
        return matches;
    }

    /**
     * Hazırlıq (dostluq) oyunları "son 5 maç"a sayılmamalıdır — həmin oyunlar həm çox
     * beraberlik verir, həm də forma göstəricisi deyil. Mackolik onları "Hazırlık
     * Kulüpler" / "Hazırlık Milli" kimi ayrıca turnir bloklarında sadalayır.
     *
     * <p>{@link OnlyLeagueScraper#isLeagueCompetition} bundan daha sərtdir: kuboku və
     * Avropa turnirlərini də atır. Taktika "son 5 maç" deyir, yəni rəsmi kubok oyunu da
     * sayılır — ona görə standart rejim yalnız hazırlıq oyunlarını atır, sərt rejim isə
     * {@code --yalniz-lig} arqumenti ilə açılır.
     */
    static boolean isFriendly(String competition) {
        if (competition == null || competition.isEmpty()) return false;
        String lower = competition.toLowerCase(new Locale("tr", "TR"));
        return lower.contains("hazırlık") || lower.contains("hazirlik")
                || lower.contains("friendly") || lower.contains("friendlies");
    }

    /** "2026/2027" → "2025/2026"; təqvim ili liqalarında "2026" → "2025". */
    static String previousSeason(String label) {
        if (label == null || label.isEmpty()) return null;
        String[] parts = label.split("/");
        try {
            if (parts.length == 1) return String.valueOf(Integer.parseInt(parts[0].trim()) - 1);
            int start = Integer.parseInt(parts[0].trim());
            int end   = Integer.parseInt(parts[1].trim());
            return (start - 1) + "/" + (end - 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return LocalDate.parse(text.trim(), PAGE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Element row, String selector) {
        Element element = row.selectFirst(selector);
        return element != null ? element.text().trim() : "";
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Bir oyun üçün tapşırıq
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * AllInOneTactics üçün tək oyunluq giriş nöqtəsi. Oranlar günün proqramından
     * gəldiyi üçün oyun proqramda tapılmalıdır; tapılmasa və ya şərtləri ödəməsə null.
     */
    public static String analyzeSinglePair(MackolikHttpFetcher http, int homeId, int awayId) {
        Limits limits = new Limits();
        for (Fixture fixture : fetchFixtures(http, null)) {
            if (fixture.homeId != homeId || fixture.awayId != awayId) continue;
            if (oddsRejection(fixture, limits) != null) return null;

            TeamForm homeForm = fetchForm(http, fixture.homeId, fixture.homeName,
                    fixture.seasonLabel, limits.formSize, false);
            TeamForm awayForm = fetchForm(http, fixture.awayId, fixture.awayName,
                    fixture.seasonLabel, limits.formSize, false);
            if (homeForm.matches.isEmpty() || awayForm.matches.isEmpty()) return null;

            Verdict verdict = evaluate(fixture, homeForm, awayForm, limits);
            return verdict == null ? null : verdict.render();
        }
        return null;
    }

    private static final class FixtureTask implements Callable<Verdict> {

        private final Fixture fixture;
        private final MackolikHttpFetcher http;
        private final Limits limits;
        private final boolean leagueOnly;
        private final AtomicInteger formRejected;

        FixtureTask(Fixture fixture, MackolikHttpFetcher http, Limits limits,
                    boolean leagueOnly, AtomicInteger formRejected) {
            this.fixture      = fixture;
            this.http         = http;
            this.limits       = limits;
            this.leagueOnly   = leagueOnly;
            this.formRejected = formRejected;
        }

        @Override
        public Verdict call() {
            try {
                TeamForm homeForm = fetchForm(http, fixture.homeId, fixture.homeName,
                        fixture.seasonLabel, limits.formSize, leagueOnly);
                TeamForm awayForm = fetchForm(http, fixture.awayId, fixture.awayName,
                        fixture.seasonLabel, limits.formSize, leagueOnly);

                if (homeForm.matches.isEmpty() || awayForm.matches.isEmpty()) {
                    formRejected.incrementAndGet();
                    return null;
                }

                Verdict verdict = evaluate(fixture, homeForm, awayForm, limits);
                if (verdict == null) formRejected.incrementAndGet();
                return verdict;
            } catch (Exception e) {
                System.err.println("Oyun " + fixture.title() + " uğursuz: " + e.getMessage());
                return null;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  main
    // ═══════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        Limits limits = new Limits();
        String day = LocalDate.now().format(DAY_FORMAT);
        String leagueFilter = null;
        boolean leagueOnly = false;
        int maxFixtures = 0;

        for (String arg : args == null ? new String[0] : args) {
            if ("--butun-gunler".equalsIgnoreCase(arg)) {
                day = null;
            } else if ("--yalniz-lig".equalsIgnoreCase(arg)) {
                leagueOnly = true;
            } else if (arg.startsWith("--tarix=")) {
                String value = arg.substring("--tarix=".length()).trim();
                if (DATE_TEXT.matcher(value).matches()) day = value;
                else System.err.println("Keçərsiz tarix (dd/MM/yyyy gözlənilir): " + value);
            } else if (arg.startsWith("--liqa=")) {
                leagueFilter = arg.substring("--liqa=".length()).trim().toLowerCase(Locale.ROOT);
            } else if (arg.startsWith("--max=")) {
                maxFixtures = intArg(arg, maxFixtures);
            } else if (arg.startsWith("--x-max=")) {
                limits.xMax = doubleArg(arg, limits.xMax);
            } else if (arg.startsWith("--fav-min=")) {
                limits.favMin = doubleArg(arg, limits.favMin);
            } else if (arg.startsWith("--min-beraberlik=")) {
                limits.minDraws = intArg(arg, limits.minDraws);
            } else if (arg.startsWith("--form=")) {
                limits.formSize = intArg(arg, limits.formSize);
            } else {
                System.err.println("Naməlum arqument: " + arg);
            }
        }

        System.out.println("════════ ORAN AVCISI — MAÇ SONU 0 (İY/MS 1/0 · 2/0) ════════");
        System.out.printf(Locale.ROOT, "Məcburi şərtlər : MS0 < %.2f  ·  favorit ≥ %.2f  ·  10 oyunda ≥ %d beraberlik%n",
                limits.xMax, limits.favMin, limits.minDraws);
        System.out.printf(Locale.ROOT, "Bal verənlər    : MS0 < %.2f  ·  favorit ≥ %.2f  ·  2.5 ALT ≤ %.2f  ·  "
                        + "ALT/ÜST %.2f–%.2f  ·  ≥%d ədəd 1-1  ·  2-0/0-2%n",
                limits.xGood, limits.favGood, limits.underGood,
                limits.tightLow, limits.tightHigh, limits.minOneOne);

        MackolikHttpFetcher http = new MackolikHttpFetcher(NUM_THREADS, MIN_REQUEST_GAP);

        List<Fixture> fixtures = fetchFixtures(http, day);
        System.out.println("Forma qaynağı   : " + (leagueOnly ? "yalnız lig (kupa/Avropa da atılır)" : "rəsmi oyunlar (yalnız hazırlıq maçları atılır)"));
        System.out.println("Proqram         : " + (day == null ? "bütün günlər" : day)
                + " — " + fixtures.size() + " başlamamış oyun");

        if (leagueFilter != null && !leagueFilter.isEmpty()) {
            String needle = leagueFilter;
            fixtures.removeIf(f -> !f.leagueName.toLowerCase(Locale.ROOT).contains(needle));
            System.out.println("Liqa filtri     : '" + leagueFilter + "' → " + fixtures.size() + " oyun");
        }

        // ── Addım 1: oran filtri. Forma səhifələri yalnız buradan keçənlər üçün endirilir.
        List<Fixture> candidates = new ArrayList<>();
        Map<String, Integer> rejections = new LinkedHashMap<>();
        for (Fixture fixture : fixtures) {
            String rejection = oddsRejection(fixture, limits);
            if (rejection == null) candidates.add(fixture);
            else rejections.merge(rejectionKey(rejection), 1, Integer::sum);
        }

        System.out.println("Oran filtri     : " + candidates.size() + " oyun keçdi, "
                + (fixtures.size() - candidates.size()) + " oyun eləndi");
        rejections.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf(Locale.ROOT, "   %-24s %d oyun%n", e.getKey(), e.getValue()));

        if (maxFixtures > 0 && candidates.size() > maxFixtures) {
            candidates = new ArrayList<>(candidates.subList(0, maxFixtures));
            System.out.println("Limit           : " + maxFixtures + " oyuna endirildi");
        }

        if (candidates.isEmpty()) {
            System.out.println("\nBu şərtlərə uyan oyun YOXDUR. (Şərtlər dardır — boş nəticə normaldır.)");
            System.out.println("Hədləri yumşaltmaq üçün:  --x-max=3.20 --fav-min=1.70");
            http.close();
            System.exit(0);
        }

        // ── Addım 2: forma + bal
        System.out.println("\n" + candidates.size() + " namizədin forması yüklənir...\n");

        AtomicInteger formRejected = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Verdict>> futures = new ArrayList<>();
        for (Fixture fixture : candidates) {
            futures.add(executor.submit(new FixtureTask(fixture, http, limits, leagueOnly, formRejected)));
        }

        List<Verdict> verdicts = new ArrayList<>();
        for (Future<Verdict> future : futures) {
            try {
                Verdict verdict = future.get();
                if (verdict != null) verdicts.add(verdict);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                System.err.println("Tapşırıq xətası: "
                        + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(300, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        verdicts.sort(Comparator.comparingInt((Verdict v) -> v.score).reversed()
                .thenComparingDouble(v -> v.fixture.msX));

        for (Verdict verdict : verdicts) {
            System.out.println(verdict.render());
            System.out.println("══════════════════════════════════════════════");
        }

        System.out.println("\n════════ NƏTİCƏ ════════");
        System.out.println("Proqramdakı oyun        : " + fixtures.size());
        System.out.println("Oran filtrindən keçən   : " + candidates.size());
        System.out.println("Beraberlik/forma elədi  : " + formRejected.get());
        System.out.println("SİQNAL                  : " + verdicts.size());
        long threeStar = verdicts.stream().filter(v -> v.score >= 10).count();
        long twoStar   = verdicts.stream().filter(v -> v.score >= 7 && v.score < 10).count();
        System.out.println("   ★★★ " + threeStar + "   ★★ " + twoStar
                + "   ★ " + (verdicts.size() - threeStar - twoStar));
        System.out.println(http.statsLine());
        if (verdicts.isEmpty()) {
            System.out.println("→ Bugün bu qəlibə uyğun oyun yoxdur.");
        }

        http.close();
        System.exit(0);
    }

    /** Eyni tipli rədləri qruplaşdırmaq üçün rəqəmsiz açar. */
    private static String rejectionKey(String rejection) {
        if (rejection.startsWith("MS0")) return "MS0 oranı yüksək";
        if (rejection.startsWith("favorit")) return "favorit çox qısa";
        return rejection;
    }

    private static int intArg(String arg, int fallback) {
        try {
            return Integer.parseInt(arg.substring(arg.indexOf('=') + 1).trim());
        } catch (NumberFormatException e) {
            System.err.println("Keçərsiz rəqəm: " + arg);
            return fallback;
        }
    }

    private static double doubleArg(String arg, double fallback) {
        try {
            return Double.parseDouble(arg.substring(arg.indexOf('=') + 1).trim());
        } catch (NumberFormatException e) {
            System.err.println("Keçərsiz rəqəm: " + arg);
            return fallback;
        }
    }
}
