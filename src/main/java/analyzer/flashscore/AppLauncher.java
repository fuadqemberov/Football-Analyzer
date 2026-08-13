package analyzer.flashscore;

public class AppLauncher {
    public static void main(String[] args) {
        DatabaseService.DataFreshness freshness = DatabaseService.checkDataFreshness();

        System.out.println("=== VERİ GÜNCELLİK KONTROLÜ ===");
        System.out.println(freshness.message());
        if (freshness.isAvailable() && freshness.daysBehind() > 0) {
            System.out.println("➜ Eksik günleri kapatmak için en az "
                    + freshness.daysBehind() + " gün taranmalı.");
        }
        System.out.println("===============================");

        FlashscoreApp.setDataFreshness(freshness);
        FlashscoreApp.main(args);
    }
}
