package analyzer.util;

/** TEMP diagnostic — not for master. */
public class FetchProbe {
    public static void main(String[] a) throws Exception {
        String[] urls = {
            "https://arsiv.mackolik.com/Team/Default.aspx?id=451&season=2026/2027",
            "https://arsiv.mackolik.com/Team/Default.aspx?id=451&season=2025/2026",
            "https://arsiv.mackolik.com/Team/Default.aspx?id=3&season=2026/2027",
        };
        // 1) plain java.net.http on arsiv2
        var hc = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();
        for (String u : urls) {
            String u2 = u.replace("arsiv.", "arsiv2.");
            long t = System.currentTimeMillis();
            try {
                var r = hc.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(u2))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .timeout(java.time.Duration.ofSeconds(40)).build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                System.out.println("[jdk] " + r.statusCode() + " " + r.version() + " " + r.body().length + "B " + (System.currentTimeMillis()-t) + "ms " + u2);
            } catch (Exception e) { System.out.println("[jdk] EXC " + e + " " + (System.currentTimeMillis()-t) + "ms"); }
        }
        // 2) MackolikHttpFetcher (Apache HC4)
        try (MackolikHttpFetcher f = new MackolikHttpFetcher(4, 40)) {
            for (String u : urls) {
                long t = System.currentTimeMillis();
                byte[] b = f.fetchBytes(u);
                System.out.println("[hc4] " + (b == null ? "null" : b.length + "B") + " " + (System.currentTimeMillis()-t) + "ms " + u);
            }
            System.out.println("[hc4] " + f.statsLine());
        }
    }
}
