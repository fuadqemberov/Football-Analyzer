package analyzer.flashscore;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FlashscoreApp extends Application {

    private final CopyOnWriteArrayList<MatchData> resultList = new CopyOnWriteArrayList<>();
    private final AtomicInteger doneCount = new AtomicInteger(0);
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final ScheduledExecutorService timerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "elapsed-timer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> timerFuture;

    private ComboBox<Integer> daysCombo;
    private Button startBtn;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label timerLabel;
    private Label freshnessLabel;
    private TextArea logArea;

    // AppLauncher üzerinden başlatılınca hazır gelir; doğrudan çalıştırılırsa start() içinde hesaplanır.
    private static DatabaseService.DataFreshness dataFreshness;

    public static void setDataFreshness(DatabaseService.DataFreshness freshness) {
        dataFreshness = freshness;
    }

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("FlashScore Bet365 Enterprise v4.1 - Auto Desktop Edition");

        Label titleLabel = new Label("FlashScore Bet365 Bot");
        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        HBox daysBox = new HBox(10);
        daysBox.setAlignment(Pos.CENTER_LEFT);
        daysCombo = new ComboBox<>();
        daysCombo.getItems().addAll(1, 2, 3, 4, 5, 6, 7);
        daysCombo.setValue(1);
        daysBox.getChildren().addAll(new Label("Taranacak Gün Sayısı:"), daysCombo);

        freshnessLabel = new Label();
        freshnessLabel.setWrapText(true);
        if (dataFreshness == null) dataFreshness = DatabaseService.checkDataFreshness();
        showFreshness(dataFreshness);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(420);
        progressBar.setPrefHeight(22);
        statusLabel = new Label("Bekleniyor...");
        timerLabel = new Label("⏱ 00:00:00");
        timerLabel.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 13px; -fx-text-fill: #7f8c8d;");

        HBox statusRow = new HBox(20);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.getChildren().addAll(statusLabel, timerLabel);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(220);
        logArea.setStyle("-fx-control-inner-background:#1e1e1e; -fx-text-fill:#00ff00; -fx-font-family:'Consolas';");
        AppLogger.setConsoleArea(logArea);

        startBtn = new Button("TARAMAYI BAŞLAT");
        startBtn.setStyle("-fx-background-color:#27ae60; -fx-text-fill:white; -fx-font-weight:bold; -fx-padding:12 25;");
        startBtn.setOnAction(e -> startScrapingTask());

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.getChildren().addAll(titleLabel, freshnessLabel, daysBox, startBtn, statusRow, progressBar, logArea);

        Scene scene = new Scene(root, 580, 550);
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.setOnCloseRequest(e -> {
            stopTimer();
            Platform.exit();
            System.exit(0);
        });
        primaryStage.show();
    }

    /** Güncellik etiketini tazeler; güncel veride yeşil, geride kalmışsa kırmızı gösterir. */
    private void showFreshness(DatabaseService.DataFreshness freshness) {
        dataFreshness = freshness;
        boolean stale = !freshness.isAvailable() || freshness.daysBehind() > 0;
        freshnessLabel.setText(freshness.message());
        freshnessLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: "
                + (stale ? "#c0392b" : "#27ae60") + ";");
    }

    private void startTimer() {
        startTimeMs.set(System.currentTimeMillis());
        timerFuture = timerScheduler.scheduleAtFixedRate(() -> {
            long elapsed = System.currentTimeMillis() - startTimeMs.get();
            long h = elapsed / 3600000;
            long m = (elapsed % 3600000) / 60000;
            long s = (elapsed % 60000) / 1000;
            Platform.runLater(() -> timerLabel.setText(String.format("⏱ %02d:%02d:%02d", h, m, s)));
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void stopTimer() {
        if (timerFuture != null) timerFuture.cancel(false);
    }

    private void startScrapingTask() {
        final int daysToProcess = daysCombo.getValue();

        startBtn.setDisable(true);
        doneCount.set(0);
        resultList.clear();
        logArea.clear();
        startTimer();

        new Thread(() -> {
            try {
                AppLogger.log("=== FAZ 1: MAÇ LİSTESİ TOPLANIYOR ===");
                Platform.runLater(() -> statusLabel.setText("Faz 1: Liste alınıyor..."));

                List<MatchData> pendingMatches;
                try (Playwright playwright = Playwright.create();
                     Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
                     BrowserContext ctx = browser.newContext();
                     Page page = ctx.newPage()) {

                    pendingMatches = MatchListScraper.collectMatchesForDays(page, daysToProcess);
                }

                if (pendingMatches.isEmpty()) {
                    AppLogger.log("Hiç maç bulunamadı.");
                    Platform.runLater(() -> {
                        startBtn.setDisable(false);
                        statusLabel.setText("Maç bulunamadı.");
                    });
                    stopTimer();
                    return;
                }

                AppLogger.log("Bulunan maç: " + pendingMatches.size());
                AppLogger.log("\n=== FAZ 2: ULTRA HIZLI API TARAMA BAŞLIYOR ===");
                Platform.runLater(() -> statusLabel.setText("Faz 2: Skorlar ve oranlar API ile çekiliyor..."));

                runParallelScraping(pendingMatches);

                AppLogger.log("\n=== FAZ 3: VERİTABANI İŞLEMLERİ ===");

                DatabaseService.insertToDatabase(resultList);

                AppLogger.log("İŞLEM TAMAMLANDI! Veritabanına aktarıldı.");

                DatabaseService.DataFreshness updated = DatabaseService.checkDataFreshness();
                AppLogger.log(updated.message());

                Platform.runLater(() -> {
                    statusLabel.setText("Tamamlandı!");
                    startBtn.setDisable(false);
                    progressBar.setProgress(1.0);
                    showFreshness(updated);
                });
                stopTimer();

            } catch (Exception e) {
                AppLogger.log("KRİTİK HATA: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> startBtn.setDisable(false));
                stopTimer();
            }
        }).start();
    }

    private void runParallelScraping(List<MatchData> matches) {
        int total = matches.size();

        ExecutorService executor = Executors.newFixedThreadPool(ScraperConstants.MAX_CONCURRENT_DRIVERS);

        for (MatchData m : matches) {
            executor.submit(() -> {
                try {
                    MatchDetailScraper.scrapeMatch(m);

                    resultList.add(m);
                    int done = doneCount.incrementAndGet();

                    AppLogger.log(String.format(" [OK %d/%d] %s vs %s", done, total, m.homeTeam, m.awayTeam));
                    Platform.runLater(() -> progressBar.setProgress((double) done / total));

                } catch (Exception e) {
                    AppLogger.log(" [ERR] " + m.homeTeam + " -> " + e.getMessage());
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(4, TimeUnit.HOURS);
        } catch (Exception ignored) {
        }
    }
}