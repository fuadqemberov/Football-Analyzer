package analyzer.flashscore;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainAppRunner {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Kaç gün taransın? (1-7): ");
        int days = Integer.parseInt(scanner.nextLine().trim());

        try {
            System.out.println("\n=== FAZ 1: MAÇ LİSTESİ TOPLANIYOR ===");
            List<MatchData> pendingMatches;

            try (Playwright pw = Playwright.create();
                 Browser br = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
                 BrowserContext ctx = br.newContext(new com.microsoft.playwright.Browser.NewContextOptions()
                         .setJavaScriptEnabled(true));
                 Page page = ctx.newPage()) {

                pendingMatches = collectWithRetry(page, br, days);
            }

            if (pendingMatches.isEmpty()) {
                System.out.println("Hiç maç bulunamadı.");
                return;
            }

            System.out.println("Toplam " + pendingMatches.size() + " maç bulundu.");

            System.out.println("\n=== FAZ 2: ULTRA HIZLI API PARALEL TARAMA ===");
            runParallelScraping(pendingMatches);

            System.out.println("\n=== FAZ 3: VERİTABANI İŞLEMLERİ ===");
            DatabaseService.insertToDatabase(pendingMatches);
            System.out.println("Tamamlandı! Veritabanına aktarıldı.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<MatchData> collectWithRetry(Page page, Browser browser, int days) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return MatchListScraper.collectMatchesForDays(page, days);
            } catch (Exception e) {
                System.err.println("[FAZ1 RETRY " + attempt + "/" + maxAttempts + "] " + e.getMessage());
                if (attempt == maxAttempts) throw e;
                try { page.close(); } catch (Exception ignored) {}
                try {
                    Thread.sleep(3000);
                    BrowserContext newCtx = browser.newContext();
                    page = newCtx.newPage();
                } catch (Exception ignored) {}
            }
        }
        return List.of();
    }

    private static void runParallelScraping(List<MatchData> matches) {
        ExecutorService executor = Executors.newFixedThreadPool(ScraperConstants.MAX_CONCURRENT_DRIVERS);

        for (MatchData m : matches) {
            executor.submit(() -> {
                try {
                    MatchDetailScraper.scrapeMatch(m);
                    System.out.println("[OK] " + m.homeTeam + " vs " + m.awayTeam);
                } catch (Exception e) {
                    System.err.println("[ERR] " + m.matchId + " -> " + e.getMessage());
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(4, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }
}