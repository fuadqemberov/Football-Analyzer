package analyzer.report;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bir analizin bir oyun üçün tapdığı nəticə — hesabatın vahidi.
 *
 * <p>Analizlər bunu {@link ReportSink#emit(Finding)} ilə JSON sətri kimi yazır;
 * {@link DailyReport} sonda hamısını oyun üzrə qruplaşdırıb tək hesabat qurur.
 *
 * <pre>
 *   Finding.of("SEASON_XTH", "X. rəsmi oyun İY/MS", "mackolik")
 *          .team(451, "Galatasaray")
 *          .match(451, "Galatasaray", 3, "Fenerbahçe", "Türkiye Süper Lig", "30.10.2026", "19:00")
 *          .explain("…cümlə ilə izah…")
 *          .predict("İY/MS 2/1 və ya 1/2")
 *          .evidence(Finding.ev("2019/2020", "29.10.2019", "Galatasaray", "Fenerbahçe", "2-1", "0-1").htft("2/1"))
 *          .emit();
 * </pre>
 */
public final class Finding {

    String analyzer, title, source;
    int teamId;
    String teamName;
    int homeId, awayId;
    String home, away, league, matchDate, kickoff;
    String explanation, prediction;
    final List<Evidence> evidence = new ArrayList<>();

    /** Keçmişdən bir sübut oyunu. */
    public static final class Evidence {
        String season, date, home, away, ft, ht, htft, note;
        int homeId, awayId;

        public Evidence ids(int homeId, int awayId) { this.homeId = homeId; this.awayId = awayId; return this; }
        public Evidence htft(String v)  { this.htft = v; return this; }
        public Evidence note(String v)  { this.note = v; return this; }
    }

    private Finding() {}

    /**
     * @param analyzer qısa kod (qruplaşdırma/sıralama üçün), məs. "TEMAS"
     * @param title    hesabatda görünən ad, məs. "Temas komandası"
     * @param source   "mackolik" və ya "nowgoal" (komanda ID-ləri hansı sayta aiddir)
     */
    public static Finding of(String analyzer, String title, String source) {
        Finding f = new Finding();
        f.analyzer = analyzer;
        f.title = title;
        f.source = source;
        return f;
    }

    /** Analiz olunan komanda (oyun səviyyəli nəticədə boş qala bilər). */
    public Finding team(int id, String name) { this.teamId = id; this.teamName = name; return this; }

    /** Bugünkü oyun. {@code date} istənilən formatda ola bilər ("30.10.2026", "30/10/2026", ISO). */
    public Finding match(int homeId, String home, int awayId, String away,
                         String league, String date, String kickoff) {
        this.homeId = homeId; this.home = home;
        this.awayId = awayId; this.away = away;
        this.league = league;
        this.matchDate = isoDate(date);
        this.kickoff = kickoff;
        return this;
    }

    public Finding explain(String sentence) { this.explanation = sentence; return this; }
    public Finding predict(String text)     { this.prediction = text; return this; }

    public Finding evidence(Evidence e) {
        if (e != null) evidence.add(e);
        return this;
    }

    public static Evidence ev(String season, String date, String home, String away, String ft, String ht) {
        Evidence e = new Evidence();
        e.season = season;
        e.date = isoDate(date);
        e.home = home;
        e.away = away;
        e.ft = ft;
        e.ht = ht;
        return e;
    }

    /** Yazmaq (hesabat faylı təyin olunmayıbsa heç nə etmir). */
    public void emit() { ReportSink.emit(this); }

    // ── Tarix ────────────────────────────────────────────────────────────
    private static final Pattern DMY = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})");
    private static final Pattern YMD = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");

    /** "5.11.2023", "05/11/2023", "2023-11-05", "2023-11-05T19:00" → "2023-11-05"; tanınmasa null. */
    public static String isoDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        try {
            Matcher m = YMD.matcher(s);
            if (m.find()) return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))).toString();
            m = DMY.matcher(s);
            if (m.find()) return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(1))).toString();
        } catch (RuntimeException ignored) {
            // yanlış tarix (31.02 və s.)
        }
        return null;
    }

    public static String isoDate(LocalDate d) { return d == null ? null : d.toString(); }

    static final DateTimeFormatter DMY_OUT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // ── JSON ─────────────────────────────────────────────────────────────
    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "finding");
        m.put("analyzer", analyzer);
        m.put("title", title);
        m.put("source", source);
        if (teamId != 0) m.put("teamId", teamId);
        m.put("teamName", teamName);
        if (homeId != 0) m.put("homeId", homeId);
        m.put("home", home);
        if (awayId != 0) m.put("awayId", awayId);
        m.put("away", away);
        m.put("league", league);
        m.put("matchDate", matchDate);
        m.put("kickoff", kickoff);
        m.put("explanation", explanation);
        m.put("prediction", prediction);
        List<Object> ev = new ArrayList<>();
        for (Evidence e : evidence) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("season", e.season);
            x.put("date", e.date);
            x.put("home", e.home);
            x.put("away", e.away);
            if (e.homeId != 0) x.put("homeId", e.homeId);
            if (e.awayId != 0) x.put("awayId", e.awayId);
            x.put("ft", e.ft);
            x.put("ht", e.ht);
            x.put("htft", e.htft);
            x.put("note", e.note);
            ev.add(x);
        }
        m.put("evidence", ev);
        return m;
    }

    static Finding fromMap(Map<String, Object> m) {
        Finding f = of(Json.str(m, "analyzer"), Json.str(m, "title"), Json.str(m, "source"));
        f.teamId = Json.num(m, "teamId");
        f.teamName = Json.str(m, "teamName");
        f.homeId = Json.num(m, "homeId");
        f.home = Json.str(m, "home");
        f.awayId = Json.num(m, "awayId");
        f.away = Json.str(m, "away");
        f.league = Json.str(m, "league");
        f.matchDate = Json.str(m, "matchDate");
        f.kickoff = Json.str(m, "kickoff");
        f.explanation = Json.str(m, "explanation");
        f.prediction = Json.str(m, "prediction");
        for (Map<String, Object> x : Json.list(m, "evidence")) {
            Evidence e = new Evidence();
            e.season = Json.str(x, "season");
            e.date = Json.str(x, "date");
            e.home = Json.str(x, "home");
            e.away = Json.str(x, "away");
            e.homeId = Json.num(x, "homeId");
            e.awayId = Json.num(x, "awayId");
            e.ft = Json.str(x, "ft");
            e.ht = Json.str(x, "ht");
            e.htft = Json.str(x, "htft");
            e.note = Json.str(x, "note");
            f.evidence.add(e);
        }
        return f;
    }
}
