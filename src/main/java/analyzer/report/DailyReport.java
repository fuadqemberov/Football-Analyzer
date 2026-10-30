package analyzer.report;

import analyzer.util.AyniEslesme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GÜNLÜK TƏK HESABAT — bütün analizlərin nəticələrini oyun üzrə birləşdirir.
 * ────────────────────────────────────────────────────────────────────────────
 * Giriş: analizlərin {@link ReportSink} ilə yazdığı JSON-Lines faylı.
 * Çıxış: e-poçt üçün HTML (+ eyni məzmunlu mətn faylı).
 *
 * Hər oyun bir kartdır; altında o oyuna aid BÜTÜN analizlər, hər biri bir cümləlik
 * izah və keçmiş sübut oyunları ilə. Sübut oyunlarının işarələri:
 * <ul>
 *   <li>⭐⭐⭐⭐⭐ — keçmiş oyunun tarixi (gün.ay) bu günlə EYNİ</li>
 *   <li>⭐⭐⭐⭐ — 1 gün əvvəl/sonra</li>
 *   <li>⭐⭐⭐ — 2 gün əvvəl/sonra</li>
 *   <li>🔥🔥🔥🔥🔥 — keçmiş nəticə bugünkü oyunun EYNİ iki komandasının oyunudur</li>
 * </ul>
 * Ən güclü oyunlar (🔥, çox ulduz, çox analiz) yuxarıda.
 *
 * İşlətmə:
 * <pre>
 *   java analyzer.report.DailyReport --in findings.jsonl --html report.html --txt report.txt
 *        [--date 30.10.2026] [--log TEMAS=temas.txt --log SEASON_XTH=season_xth.txt ...]
 * </pre>
 */
public final class DailyReport {

    static final String STAR = "⭐";
    static final String FIRE5 = "🔥🔥🔥🔥🔥";

    /** Analiz kodu → hesabatda görünən ad (iş axınındakı sıra ilə). */
    static final Map<String, String> ANALYZERS = new LinkedHashMap<>();
    static {
        ANALYZERS.put("NG_TEAM_ROUND", "NowGoal · Eyni tur İY/MS");
        ANALYZERS.put("NG_BRIDGE",     "NowGoal · Körpü (comeback)");
        ANALYZERS.put("TEMAS",         "Temas komandası");
        ANALYZERS.put("HAFTA_DONGU",   "Həftə dövrü");
        ANALYZERS.put("SEASON_XTH",    "X. rəsmi oyun İY/MS");
        ANALYZERS.put("SEZON_DENKLEMI","Sezon denklemi");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Model
    // ═══════════════════════════════════════════════════════════════════════

    static final class Fixture {
        int matchId, homeId, awayId;
        String home, away, league, date, kickoff;
    }

    static final class Group {
        String key;
        String home, away, league, date, kickoff;
        final List<Finding> findings = new ArrayList<>();
        // hesablanan
        int fire, maxStars, star5, star4, star3;
        /** Oyun bu günə aiddir (tarixi bilinmirsə də true). */
        boolean today = true;
        final Set<String> analyzers = new LinkedHashSet<>();

        String title() { return nz(home) + " – " + nz(away); }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  main
    // ═══════════════════════════════════════════════════════════════════════

    public static void main(String[] args) throws IOException {
        Path in = null, html = null, txt = null, emailHtml = null;
        LocalDate today = null;
        Map<String, Path> logs = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String v = i + 1 < args.length ? args[i + 1] : null;
            switch (a) {
                case "--in"   -> { in = Paths.get(v); i++; }
                case "--html" -> { html = Paths.get(v); i++; }
                case "--txt"  -> { txt = Paths.get(v); i++; }
                case "--email-html" -> { emailHtml = Paths.get(v); i++; }
                case "--date" -> { today = LocalDate.parse(Finding.isoDate(v)); i++; }
                case "--log"  -> {
                    int eq = v.indexOf('=');
                    if (eq > 0) logs.put(v.substring(0, eq), Paths.get(v.substring(eq + 1)));
                    i++;
                }
                default -> System.err.println("Naməlum arqument: " + a);
            }
        }
        if (today == null) {
            String tz = System.getenv("REPORT_TZ");
            today = LocalDate.now(ZoneId.of(tz == null || tz.isBlank() ? "Asia/Baku" : tz));
        }
        if (in == null) in = Paths.get(System.getenv().getOrDefault("ANALYZER_REPORT_FILE", "findings.jsonl"));
        if (html == null) html = Paths.get("report.html");

        List<Fixture> fixtures = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        int bad = load(in, fixtures, findings);

        List<Group> groups = group(fixtures, findings);
        for (Group g : groups) score(g, today);
        groups.sort(GROUP_ORDER);

        List<Status> statuses = statuses(logs, findings);

        String htmlText = renderHtml(today, groups, statuses, findings.size(), false);
        Files.writeString(html, htmlText, StandardCharsets.UTF_8);
        if (emailHtml != null) {
            Files.writeString(emailHtml, renderHtml(today, groups, statuses, findings.size(), true), StandardCharsets.UTF_8);
        }
        if (txt != null) Files.writeString(txt, renderText(today, groups, statuses, findings.size()), StandardCharsets.UTF_8);

        System.out.printf("Hesabat hazırdır: %d oyun, %d analiz nəticəsi%s → %s%n",
                groups.size(), findings.size(), bad > 0 ? " (" + bad + " pozuq sətir atlandı)" : "", html);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Oxuma
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    static int load(Path in, List<Fixture> fixtures, List<Finding> findings) throws IOException {
        if (!Files.exists(in)) return 0;
        int bad = 0;
        for (String line : Files.readAllLines(in, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                Map<String, Object> m = (Map<String, Object>) Json.parse(line);
                String type = Json.str(m, "type");
                if ("fixture".equals(type)) {
                    Fixture f = new Fixture();
                    f.matchId = Json.num(m, "matchId");
                    f.homeId = Json.num(m, "homeId");
                    f.awayId = Json.num(m, "awayId");
                    f.home = Json.str(m, "home");
                    f.away = Json.str(m, "away");
                    f.league = Json.str(m, "league");
                    f.date = Json.str(m, "matchDate");
                    f.kickoff = Json.str(m, "kickoff");
                    fixtures.add(f);
                } else if ("finding".equals(type)) {
                    findings.add(Finding.fromMap(m));
                }
            } catch (RuntimeException e) {
                bad++;           // yarımçıq yazılmış sətir (məs. vaxt limiti ilə kəsilmə)
            }
        }
        return bad;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Qruplaşdırma: hər nəticə → bugünkü oyun
    // ═══════════════════════════════════════════════════════════════════════

    static List<Group> group(List<Fixture> fixtures, List<Finding> findings) {
        Map<String, Fixture> byPair = new HashMap<>();
        Map<Integer, Fixture> byTeam = new HashMap<>();
        for (Fixture f : fixtures) {
            byPair.putIfAbsent(pairKey(f.homeId, f.awayId), f);
            byTeam.putIfAbsent(f.homeId, f);
            byTeam.putIfAbsent(f.awayId, f);
        }

        Map<String, Group> groups = new LinkedHashMap<>();
        for (Finding f : findings) {
            Fixture fx = null;
            if ("mackolik".equals(f.source)) {
                if (f.homeId > 0 && f.awayId > 0) fx = byPair.get(pairKey(f.homeId, f.awayId));
                if (fx == null && f.teamId > 0) {
                    Fixture t = byTeam.get(f.teamId);
                    // komandanın bugünkü oyunu ilə nəticənin oyunu eyni olmalıdır
                    if (t != null && (f.home == null || namesMatch(t, f.home, f.away))) fx = t;
                }
            }
            if (fx == null && f.home != null && f.away != null) fx = byNames(fixtures, f);

            String key;
            Group g;
            if (fx != null) {
                final Fixture found = fx;
                key = "F:" + pairKey(fx.homeId, fx.awayId);
                g = groups.computeIfAbsent(key, k -> fromFixture(k, found));
            } else if (f.home != null && f.away != null) {
                key = "N:" + nameKey(f.home, f.away);
                g = groups.computeIfAbsent(key, k -> fromFinding(k, f));
            } else {
                // Oyun məlumatı yoxdur — komanda üzrə ayrıca kart
                key = "T:" + f.source + ":" + f.teamId + ":" + AyniEslesme.normalize(f.teamName);
                g = groups.computeIfAbsent(key, k -> {
                    Group x = fromFinding(k, f);
                    x.home = f.teamName;
                    x.away = "(rəqib bilinmir)";
                    return x;
                });
            }
            g.findings.add(f);
            g.analyzers.add(f.analyzer);
            if (g.league == null || g.league.isBlank()) g.league = f.league;
            if (g.kickoff == null || g.kickoff.isBlank()) g.kickoff = f.kickoff;
        }
        return new ArrayList<>(groups.values());
    }

    private static Group fromFixture(String key, Fixture f) {
        Group g = new Group();
        g.key = key;
        g.home = f.home; g.away = f.away; g.league = f.league; g.date = f.date; g.kickoff = f.kickoff;
        return g;
    }

    private static Group fromFinding(String key, Finding f) {
        Group g = new Group();
        g.key = key;
        g.home = f.home; g.away = f.away; g.league = f.league; g.date = f.matchDate; g.kickoff = f.kickoff;
        return g;
    }

    static String pairKey(int a, int b) { return Math.min(a, b) + "-" + Math.max(a, b); }

    static String nameKey(String a, String b) {
        String x = AyniEslesme.normalize(a), y = AyniEslesme.normalize(b);
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }

    private static boolean namesMatch(Fixture fx, String home, String away) {
        return (strong(fx.home, home) && loose(fx.away, away)) || (loose(fx.home, home) && strong(fx.away, away))
            || (strong(fx.home, away) && loose(fx.away, home)) || (loose(fx.home, away) && strong(fx.away, home));
    }

    /** NowGoal (ingiliscə ad) nəticəsini mackolik (türkcə ad) proqramındakı oyuna bağlayır. */
    private static Fixture byNames(List<Fixture> fixtures, Finding f) {
        for (Fixture fx : fixtures) {
            if (f.matchDate != null && fx.date != null && !f.matchDate.equals(fx.date)) continue;
            if (namesMatch(fx, f.home, f.away)) return fx;
        }
        return null;
    }

    private static boolean strong(String a, String b) { return AyniEslesme.teamsMatch(a, b); }

    /** Zəif uyğunluq: tam uyğunluq və ya ilk 4 hərf eyni ("Munich" ↔ "Münih"). */
    private static boolean loose(String a, String b) {
        if (strong(a, b)) return true;
        String x = AyniEslesme.normalize(a), y = AyniEslesme.normalize(b);
        return x.length() >= 4 && y.length() >= 4 && x.substring(0, 4).equals(y.substring(0, 4));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  İşarələr: ulduz (tarix yaxınlığı) və alov (eyni komandalar)
    // ═══════════════════════════════════════════════════════════════════════

    /** Keçmiş oyunun gün.ay-ı bu günə neçə gün uzaqdır (il nəzərə alınmır). Bilinmirsə -1. */
    static int dayDistance(LocalDate today, String isoPast) {
        if (isoPast == null) return -1;
        LocalDate past;
        try { past = LocalDate.parse(isoPast); } catch (RuntimeException e) { return -1; }
        long best = Long.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {                     // 31.12 ↔ 01.01 kimi il keçidləri
            int y = today.getYear() + dy;
            LocalDate p;
            try {
                p = LocalDate.of(y, past.getMonth(), past.getDayOfMonth());
            } catch (DateTimeException e) {                     // 29 fevral → 28 fevral
                p = LocalDate.of(y, 2, 28);
            }
            best = Math.min(best, Math.abs(ChronoUnit.DAYS.between(today, p)));
        }
        return (int) best;
    }

    /** 0 gün → 5, 1 gün → 4, 2 gün → 3 ulduz; qalan → 0. */
    static int stars(LocalDate today, String isoPast) {
        int d = dayDistance(today, isoPast);
        return switch (d) {
            case 0 -> 5;
            case 1 -> 4;
            case 2 -> 3;
            default -> 0;
        };
    }

    /** Keçmiş oyun bugünkü oyunun eyni iki komandasıdırmı (ev/səfər sırası fərq etmir)? */
    static boolean sameTeams(Finding f, Finding.Evidence e) {
        if (f.homeId > 0 && f.awayId > 0 && e.homeId > 0 && e.awayId > 0) {
            return AyniEslesme.ayniCift(e.homeId, e.awayId, f.homeId, f.awayId);
        }
        return AyniEslesme.ayniCift(e.home, e.away, f.home, f.away);
    }

    static void score(Group g, LocalDate today) {
        g.today = g.date == null || g.date.equals(today.toString());
        for (Finding f : g.findings) {
            for (Finding.Evidence e : f.evidence) {
                int s = stars(today, e.date);
                if (s == 5) g.star5++;
                else if (s == 4) g.star4++;
                else if (s == 3) g.star3++;
                g.maxStars = Math.max(g.maxStars, s);
                if (sameTeams(f, e)) g.fire++;
            }
        }
        // Qrup içində: ən çox işarəli analiz əvvəl
        g.findings.sort(Comparator
                .comparingInt((Finding f) -> -findingWeight(f, today))
                .thenComparingInt(f -> analyzerOrder(f.analyzer)));
    }

    private static int findingWeight(Finding f, LocalDate today) {
        int w = 0;
        for (Finding.Evidence e : f.evidence) {
            if (sameTeams(f, e)) w += 100;
            w += stars(today, e.date) * 10;
        }
        return w + f.evidence.size();
    }

    private static int analyzerOrder(String code) {
        int i = 0;
        for (String k : ANALYZERS.keySet()) { if (k.equals(code)) return i; i++; }
        return i;
    }

    static final Comparator<Group> GROUP_ORDER = Comparator
            .comparingInt((Group g) -> g.today ? 0 : 1)          // başqa günün oyunu ən sonda
            .thenComparingInt(g -> g.fire > 0 ? 0 : 1)
            .thenComparingInt(g -> -g.maxStars)
            .thenComparingInt(g -> -g.analyzers.size())
            .thenComparingInt(g -> -g.fire)
            .thenComparingInt(g -> -(g.star5 * 100 + g.star4 * 10 + g.star3))
            .thenComparingInt(g -> -g.findings.size())
            .thenComparing(g -> nz(g.kickoff));

    /** Diqqətçəkən oyun: 🔥 var, ən azı 4 ulduz var, və ya 2+ fərqli analiz. */
    static boolean highlighted(Group g) {
        return g.today && (g.fire > 0 || g.maxStars >= 4 || g.analyzers.size() >= 2);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Analizlərin vəziyyəti (loglardan)
    // ═══════════════════════════════════════════════════════════════════════

    static final class Status {
        String code, name, state, requests;
        int findings;
    }

    private static final Pattern REQ = Pattern.compile("(\\d+) ok / (\\d+) retry / (\\d+) başarısız");

    static List<Status> statuses(Map<String, Path> logs, List<Finding> findings) {
        Map<String, Integer> counts = new HashMap<>();
        for (Finding f : findings) counts.merge(f.analyzer, 1, Integer::sum);

        List<Status> out = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>(ANALYZERS.keySet());
        codes.addAll(logs.keySet());
        codes.addAll(counts.keySet());
        for (String code : codes) {
            Path p = logs.get(code);
            if (p == null && !counts.containsKey(code)) continue;
            Status s = new Status();
            s.code = code;
            s.name = ANALYZERS.getOrDefault(code, code);
            s.findings = counts.getOrDefault(code, 0);
            s.state = "—";
            s.requests = "";
            if (p != null && Files.exists(p)) {
                String log;
                try { log = Files.readString(p, StandardCharsets.UTF_8); } catch (IOException e) { log = ""; }
                if (log.contains("step timed out or failed")) s.state = "⏱️ vaxt limitində kəsildi (qismən)";
                else if (log.contains("TAMAMLANDI") || log.contains("tamamlandı") || log.contains("NƏTİCƏ")
                        || log.contains("Analiz tamamlandı")) s.state = "✅ tamamlandı";
                else s.state = "⚠️ sonu görünmür";
                Matcher m = REQ.matcher(log);
                String last = null;
                while (m.find()) last = m.group(1) + " uğurlu / " + m.group(3) + " uğursuz";
                if (last != null) s.requests = last;
            } else if (p != null) {
                s.state = "⚠️ log yoxdur";
            }
            out.add(s);
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HTML (e-poçt üçün — bütün üslublar sətir daxilində)
    // ═══════════════════════════════════════════════════════════════════════

    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM", Locale.ROOT);

    /** E-poçtun içində ən çox bu qədər oyun kartı (Gmail ~102 KB-dan sonra məktubu kəsir). */
    static final int EMAIL_MAX_CARDS = 8;
    /** E-poçt kartında bir analiz üçün ən çox bu qədər işarəli sübut oyunu. */
    static final int EMAIL_MAX_EVIDENCE = 3;
    /** E-poçtdakı "diqqətçəkən oyunlar" siyahısında ən çox bu qədər sətir. */
    static final int EMAIL_MAX_TOP = 20;
    /** E-poçt kartında ən çox bu qədər analiz nəticəsi. */
    static final int EMAIL_MAX_FINDINGS = 6;

    /**
     * @param emailMode true → yalnız diqqətçəkən oyunların kartları (e-poçt gövdəsi üçün);
     *                  tam hesabat əlavədəki faylda olur.
     */
    static String renderHtml(LocalDate today, List<Group> groups, List<Status> statuses, int findingCount,
                             boolean emailMode) {
        int fireGroups = 0, star5Groups = 0;
        for (Group g : groups) { if (g.fire > 0) fireGroups++; if (g.maxStars == 5) star5Groups++; }

        StringBuilder h = new StringBuilder(64_000);
        h.append("<!doctype html><html lang=\"az\"><head><meta charset=\"utf-8\">")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<title>Günlük analiz hesabatı ").append(today.format(Finding.DMY_OUT)).append("</title></head>")
         .append("<body style=\"margin:0;padding:0;background:#f3f4f6;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#111827;\">")
         .append("<div style=\"max-width:760px;margin:0 auto;padding:16px;\">");

        // Başlıq
        h.append("<div style=\"background:#111827;color:#fff;border-radius:12px;padding:18px 20px;\">")
         .append("<div style=\"font-size:20px;font-weight:700;\">📊 Günlük analiz hesabatı — ")
         .append(today.format(Finding.DMY_OUT)).append("</div>")
         .append("<div style=\"margin-top:6px;font-size:14px;color:#d1d5db;\">")
         .append(groups.size()).append(" oyun · ").append(findingCount).append(" analiz nəticəsi · ")
         .append("🔥 ").append(fireGroups).append(" oyun · ⭐5 ").append(star5Groups).append(" oyun</div></div>");

        // İşarələrin izahı
        h.append("<div style=\"background:#fff;border-radius:12px;padding:12px 16px;margin-top:12px;font-size:13px;line-height:1.6;color:#374151;\">")
         .append("<b>İşarələr</b> (keçmiş sübut oyunları üzrə):<br>")
         .append("⭐⭐⭐⭐⭐ keçmiş oyunun tarixi (gün.ay) bu günlə eynidir (").append(today.format(DM)).append(")<br>")
         .append("⭐⭐⭐⭐ 1 gün əvvəl/sonra &nbsp;·&nbsp; ⭐⭐⭐ 2 gün əvvəl/sonra<br>")
         .append(FIRE5).append(" keçmiş nəticə bugünkü oyunun eyni iki komandası arasında olub")
         .append("</div>");

        // Diqqətçəkən oyunlar
        List<Group> top = new ArrayList<>();
        for (Group g : groups) if (highlighted(g)) top.add(g);
        if (!top.isEmpty()) {
            h.append(sectionTitle("🎯 Diqqətçəkən oyunlar (" + top.size() + ")"));
            h.append("<div style=\"background:#fff;border-radius:12px;padding:6px 0;\">");
            List<Group> topShown = emailMode && top.size() > EMAIL_MAX_TOP ? top.subList(0, EMAIL_MAX_TOP) : top;
            for (Group g : topShown) {
                String tag = emailMode ? "div" : "a";
                h.append('<').append(tag).append(emailMode ? "" : " href=\"#" + anchor(g) + "\"")
                 .append(" style=\"display:block;padding:9px 16px;")
                 .append("border-bottom:1px solid #f3f4f6;color:#111827;text-decoration:none;font-size:14px;\">")
                 .append("<b>").append(esc(g.title())).append("</b>")
                 .append(" <span style=\"color:#6b7280;\">").append(esc(timeLeague(g))).append("</span><br>")
                 .append("<span style=\"font-size:13px;\">").append(badgeText(g)).append("</span></").append(tag).append('>');
            }
            if (topShown.size() < top.size()) {
                h.append("<div style=\"padding:9px 16px;font-size:13px;color:#6b7280;\">+ ")
                 .append(top.size() - topShown.size()).append(" diqqətçəkən oyun daha — əlavədəki tam hesabatda</div>");
            }
            h.append("</div>");
        }

        if (emailMode) {
            // E-poçt: yalnız diqqətçəkən oyunların kartları, qalanı əlavədəki tam hesabatda
            List<Group> shown = top.size() > EMAIL_MAX_CARDS ? top.subList(0, EMAIL_MAX_CARDS) : top;
            if (!shown.isEmpty()) h.append(sectionTitle("⚽ Diqqətçəkən oyunların analizləri"));
            for (Group g : shown) h.append(card(g, today, true));
            int rest = groups.size() - shown.size();
            h.append("<div style=\"background:#eef2ff;border-radius:12px;padding:12px 16px;margin-top:12px;font-size:14px;\">")
             .append(groups.isEmpty() ? "Bu gün heç bir analiz nəticə vermədi."
                     : "📎 Bütün " + groups.size() + " oyunun tam hesabatı əlavədədir (<b>report.html</b>)"
                       + (rest > 0 ? " — burada göstərilməyən " + rest + " oyun da orada." : "."))
             .append("</div>");
        } else {
            h.append(sectionTitle("⚽ Bütün oyunlar və analizlər"));
            if (groups.isEmpty()) {
                h.append("<div style=\"background:#fff;border-radius:12px;padding:16px;font-size:14px;\">")
                 .append("Bu gün heç bir analiz nəticə vermədi.</div>");
            }
            for (Group g : groups) h.append(card(g, today, false));
        }

        // Vəziyyət
        if (!statuses.isEmpty()) {
            h.append(sectionTitle("🛠️ Analizlərin vəziyyəti"));
            h.append("<table style=\"width:100%;border-collapse:collapse;background:#fff;border-radius:12px;font-size:13px;\">")
             .append("<tr style=\"background:#f9fafb;text-align:left;\"><th style=\"padding:8px 12px;\">Analiz</th>")
             .append("<th style=\"padding:8px 12px;\">Nəticə</th><th style=\"padding:8px 12px;\">Vəziyyət</th>")
             .append("<th style=\"padding:8px 12px;\">Sorğular</th></tr>");
            for (Status s : statuses) {
                h.append("<tr style=\"border-top:1px solid #f3f4f6;\"><td style=\"padding:8px 12px;\">").append(esc(s.name))
                 .append("</td><td style=\"padding:8px 12px;\">").append(s.findings)
                 .append("</td><td style=\"padding:8px 12px;\">").append(esc(s.state))
                 .append("</td><td style=\"padding:8px 12px;color:#6b7280;\">").append(esc(s.requests)).append("</td></tr>");
            }
            h.append("</table>");
        }

        h.append("<div style=\"font-size:12px;color:#9ca3af;text-align:center;margin:18px 0 8px;\">")
         .append("Avtomatik hesabat · Football-Analyzer</div></div></body></html>");
        return h.toString();
    }

    private static String sectionTitle(String t) {
        return "<div style=\"font-size:16px;font-weight:700;margin:22px 4px 8px;\">" + esc(t) + "</div>";
    }

    /** @param compact true → yalnız işarəli (⭐/🔥) sübut oyunları, qalanların sayı (e-poçt üçün) */
    private static String card(Group g, LocalDate today, boolean compact) {
        StringBuilder h = new StringBuilder();
        String border = g.fire > 0 ? "#f97316" : g.maxStars >= 4 ? "#eab308" : "#e5e7eb";
        h.append("<div id=\"").append(anchor(g)).append("\" style=\"background:#fff;border-radius:12px;")
         .append("border:2px solid ").append(border).append(";margin-bottom:12px;overflow:hidden;\">");

        // Oyun başlığı
        h.append("<div style=\"padding:12px 16px;background:#f9fafb;border-bottom:1px solid #e5e7eb;\">")
         .append("<div style=\"font-size:17px;font-weight:700;\">⚽ ").append(esc(g.title())).append("</div>")
         .append("<div style=\"font-size:13px;color:#6b7280;margin-top:2px;\">").append(esc(timeLeague(g))).append("</div>")
         .append("<div style=\"font-size:13px;margin-top:4px;\">").append(badgeText(g)).append("</div></div>");

        // Analizlər
        int fIdx = 0;
        for (Finding f : g.findings) {
            if (compact && fIdx++ >= EMAIL_MAX_FINDINGS) {
                h.append("<div style=\"padding:10px 16px;font-size:12px;color:#6b7280;\">+ ")
                 .append(g.findings.size() - EMAIL_MAX_FINDINGS)
                 .append(" analiz nəticəsi daha (əlavədəki tam hesabatda)</div>");
                break;
            }
            h.append("<div style=\"padding:12px 16px;border-bottom:1px solid #f3f4f6;\">")
             .append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;\">")
             .append(esc(analyzerName(f)));
            if (f.teamName != null && !f.teamName.isBlank()) {
                h.append(" <span style=\"font-weight:400;color:#6b7280;\">· ").append(esc(f.teamName)).append("</span>");
            }
            h.append("</div>");
            if (f.explanation != null) {
                h.append("<div style=\"font-size:14px;line-height:1.5;margin-top:4px;\">").append(esc(f.explanation)).append("</div>");
            }
            if (f.prediction != null) {
                h.append("<div style=\"font-size:14px;margin-top:4px;\">👉 <b>").append(esc(f.prediction)).append("</b></div>");
            }
            if (!f.evidence.isEmpty()) {
                h.append("<div style=\"margin-top:6px;\">");
                int hidden = 0, shown = 0;
                for (Finding.Evidence e : f.evidence) {
                    if (compact && ((stars(today, e.date) == 0 && !sameTeams(f, e)) || shown >= EMAIL_MAX_EVIDENCE)) {
                        hidden++;
                        continue;
                    }
                    shown++;
                    h.append(evidenceHtml(f, e, today));
                }
                if (hidden > 0) {
                    h.append("<div style=\"font-size:12px;color:#6b7280;margin-top:4px;\">+ ").append(hidden)
                     .append(" keçmiş oyun daha (əlavədəki tam hesabatda)</div>");
                }
                h.append("</div>");
            }
            h.append("</div>");
        }
        h.append("</div>");
        return h.toString();
    }

    private static String evidenceHtml(Finding f, Finding.Evidence e, LocalDate today) {
        int s = stars(today, e.date);
        boolean fire = sameTeams(f, e);
        String bg = fire ? "#fff7ed" : s >= 4 ? "#fefce8" : "#f9fafb";
        StringBuilder h = new StringBuilder();
        h.append("<div style=\"font-size:13px;line-height:1.5;padding:6px 10px;margin-top:4px;border-radius:8px;background:")
         .append(bg).append(";\">");
        if (s > 0 || fire) {
            h.append("<div>");
            if (s > 0) h.append(STAR.repeat(s)).append(" <span style=\"color:#6b7280;\">").append(starNote(today, e.date)).append("</span> ");
            if (fire) h.append(FIRE5).append(" <span style=\"color:#9a3412;\">eyni komandalar</span>");
            h.append("</div>");
        }
        h.append("<span style=\"color:#6b7280;\">").append(esc(dateOut(e.date)));
        if (e.season != null) h.append(" · ").append(esc(e.season));
        h.append("</span> — <b>").append(esc(nz(e.home))).append(' ').append(esc(nz(e.ft))).append(' ')
         .append(esc(nz(e.away))).append("</b>");
        if (e.ht != null && !e.ht.isBlank()) h.append(" <span style=\"color:#6b7280;\">(İY ").append(esc(e.ht)).append(")</span>");
        if (e.htft != null) h.append(" → <b>").append(esc(e.htft)).append("</b>");
        if (e.note != null && !e.note.isBlank()) h.append("<br><span style=\"color:#6b7280;\">").append(esc(e.note)).append("</span>");
        h.append("</div>");
        return h.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Mətn (log/terminal üçün)
    // ═══════════════════════════════════════════════════════════════════════

    static String renderText(LocalDate today, List<Group> groups, List<Status> statuses, int findingCount) {
        StringBuilder t = new StringBuilder();
        t.append("📊 GÜNLÜK ANALİZ HESABATI — ").append(today.format(Finding.DMY_OUT)).append('\n')
         .append(groups.size()).append(" oyun · ").append(findingCount).append(" analiz nəticəsi\n")
         .append("⭐⭐⭐⭐⭐ eyni gün.ay · ⭐⭐⭐⭐ ±1 gün · ⭐⭐⭐ ±2 gün · ").append(FIRE5).append(" eyni komandalar\n\n");

        List<Group> top = new ArrayList<>();
        for (Group g : groups) if (highlighted(g)) top.add(g);
        if (!top.isEmpty()) {
            t.append("🎯 DİQQƏTÇƏKƏN OYUNLAR\n");
            for (Group g : top) t.append("  • ").append(g.title()).append("  ").append(timeLeague(g))
                                 .append("\n    ").append(badgeText(g)).append('\n');
            t.append('\n');
        }

        for (Group g : groups) {
            t.append("══════════════════════════════════════════════════════════════\n")
             .append("⚽ ").append(g.title()).append("   ").append(timeLeague(g)).append('\n')
             .append("   ").append(badgeText(g)).append('\n');
            for (Finding f : g.findings) {
                t.append("  ── ").append(analyzerName(f));
                if (f.teamName != null && !f.teamName.isBlank()) t.append(" · ").append(f.teamName);
                t.append('\n');
                if (f.explanation != null) t.append("     ").append(f.explanation).append('\n');
                if (f.prediction != null) t.append("     👉 ").append(f.prediction).append('\n');
                for (Finding.Evidence e : f.evidence) {
                    int s = stars(today, e.date);
                    boolean fire = sameTeams(f, e);
                    t.append("     ");
                    if (s > 0) t.append(STAR.repeat(s)).append(' ');
                    if (fire) t.append(FIRE5).append(' ');
                    t.append(dateOut(e.date));
                    if (e.season != null) t.append(" · ").append(e.season);
                    t.append(" — ").append(nz(e.home)).append(' ').append(nz(e.ft)).append(' ').append(nz(e.away));
                    if (e.ht != null && !e.ht.isBlank()) t.append(" (İY ").append(e.ht).append(')');
                    if (e.htft != null) t.append(" → ").append(e.htft);
                    if (e.note != null && !e.note.isBlank()) t.append("  [").append(e.note).append(']');
                    t.append('\n');
                }
            }
        }

        if (!statuses.isEmpty()) {
            t.append("\n🛠️ ANALİZLƏRİN VƏZİYYƏTİ\n");
            for (Status s : statuses) {
                t.append(String.format("  %-28s %4d nəticə  %s  %s%n", s.name, s.findings, s.state, s.requests));
            }
        }
        return t.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Köməkçilər
    // ═══════════════════════════════════════════════════════════════════════

    static String badgeText(Group g) {
        List<String> parts = new ArrayList<>();
        if (g.fire > 0) parts.add(FIRE5 + (g.fire > 1 ? " ×" + g.fire : ""));
        if (g.star5 > 0) parts.add("⭐⭐⭐⭐⭐" + (g.star5 > 1 ? " ×" + g.star5 : ""));
        if (g.star4 > 0) parts.add("⭐⭐⭐⭐" + (g.star4 > 1 ? " ×" + g.star4 : ""));
        if (g.star3 > 0) parts.add("⭐⭐⭐" + (g.star3 > 1 ? " ×" + g.star3 : ""));
        int n = g.analyzers.size();
        parts.add(n + " analiz" + (n > 1 ? " (" + analyzerList(g) + ")" : ""));
        return String.join(" · ", parts);
    }

    private static String analyzerList(Group g) {
        List<String> names = new ArrayList<>();
        for (String c : g.analyzers) names.add(ANALYZERS.getOrDefault(c, c));
        return String.join(", ", names);
    }

    static String analyzerName(Finding f) {
        String n = ANALYZERS.get(f.analyzer);
        return n != null ? n : (f.title != null ? f.title : f.analyzer);
    }

    static String timeLeague(Group g) {
        List<String> p = new ArrayList<>();
        if (g.date != null) {
            try { p.add(LocalDate.parse(g.date).format(DM)); } catch (RuntimeException ignored) { }
        }
        if (g.kickoff != null && !g.kickoff.isBlank()) p.add(g.kickoff);
        if (g.league != null && !g.league.isBlank()) p.add(g.league);
        return String.join(" · ", p);
    }

    static String starNote(LocalDate today, String iso) {
        int d = dayDistance(today, iso);
        return d == 0 ? "eyni tarix" : d + " gün fərq";
    }

    static String dateOut(String iso) {
        if (iso == null) return "tarix ?";
        try { return LocalDate.parse(iso).format(Finding.DMY_OUT); } catch (RuntimeException e) { return iso; }
    }

    private static String anchor(Group g) {
        return "m" + Integer.toHexString(g.key.hashCode());
    }

    static String nz(String s) { return s == null ? "?" : s; }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
