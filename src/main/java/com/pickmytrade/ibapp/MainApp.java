package com.pickmytrade.ibapp;

import com.google.api.core.ApiService;
import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import com.google.cloud.pubsub.v1.*;
import com.google.gson.JsonObject;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.pubsub.v1.Subscription;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.pickmytrade.ibapp.bussinesslogic.PlaceOrderService;
import com.pickmytrade.ibapp.bussinesslogic.TwsEngine;
import com.pickmytrade.ibapp.db.DatabaseConfig;
import com.pickmytrade.ibapp.db.entities.*;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import static com.pickmytrade.ibapp.config.Config.log;

import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;

import java.awt.Desktop;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonParser;
import com.pickmytrade.ibapp.config.LoggingConfig;

public class MainApp extends Application {
    public TwsEngine twsEngine;
    private PlaceOrderService placeOrderService;
    private int retrycheck_count = 1;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final ExecutorService executor = Executors.newFixedThreadPool(32);
    private final ExecutorService websocketExecutor = Executors.newSingleThreadExecutor();
    private ExecutorService orderExecutor = Executors.newSingleThreadExecutor();
    private WebSocketClient websocket;
    private Label twsStatusLabel;
    private Label websocketStatusLabel; // Repurposed for server connection status
    private Circle twsLight;
    private Circle websocketLight; // Repurposed for server connection status
    private TextArea consoleLog;
    private static String lastUsername = "";
    private static String lastPassword = "";
    private static String lastConnectionName = "";
    private static String lastSubscriptionId = "sub-user-A"; // Default for testing
    private TradeServer tradeServer;
    private final List<Future<?>> websocketTasks = new ArrayList<>();
    private Stage connectionStage;
    private final ReentrantLock websocketCleanupLock = new ReentrantLock();
    private final AtomicLong lastHeartbeatAck = new AtomicLong(System.currentTimeMillis());
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(16);
    private long appStartTime;
    private long manualTradeCloseTime;
    private String app_version = "10.29.0";
    private static final String VERSION_CHECK_URL = "https://api.pickmytrade.io/v2/exe_App_latest_version_windows";
    private static final String UPDATE_DIR = System.getenv("APPDATA") + "/PickMyTrade/updates";
    private volatile boolean isJavaFxInitialized = false;
    private volatile boolean isUpdating = false;
    private Subscriber subscriber;
    private static final AtomicBoolean pubsubIsStarting = new AtomicBoolean(false);
    private final AtomicReference<Subscriber> pubsubSubscriberRef = new AtomicReference<>();
    private final AtomicReference<GoogleCredentials> pubsubCredentialsRef = new AtomicReference<>();
    private final AtomicLong pubsubLastMessageReceived = new AtomicLong(System.currentTimeMillis());
    private final AtomicReference<String> pubsubAccessTokenRef = new AtomicReference<>();
    private final ScheduledExecutorService pubsubScheduler = Executors.newScheduledThreadPool(1);
    private static String heartbeat_auth_token = "";
    private static String heartbeat_connection_id = "";
    private static String heartbeat_new_token = "";
    private static String heartbeat_snew_token_id = "";
    private static final JsonObject SettingData = new JsonObject();
    private final AtomicBoolean lastNetworkState = new AtomicBoolean(true); // Assume network is initially available
    private final AtomicLong networkRestoredTime = new AtomicLong(0);
    private final AtomicLong networkDroppedTime = new AtomicLong(0);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private int trade_server_port = 7507;
    private int tws_trade_port = 7497;
    private String current_db_url;
    private static String pickMyTradeDirPath;

    private static final String GOOGLE_CLIENT_ID = "976773838704-16mo2mteso7glm97h37604c1rv0sgnvl.apps.googleusercontent.com"; // From Google Console (Installed App/Other)
    private static final String BACKEND_URL = "https://api.pickmytrade.io/google_signup";
    private static final String GOOGLE_AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";
//    private static final String GOOGLE_SCOPES = "openid email profile";

    private static final String GOOGLE_SCOPES = "https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile openid";

    public static void main(String[] args) {
        try {
            log.info("Checking APPDATA environment variable");
            String appDataPath = System.getenv("APPDATA");
            if (appDataPath == null) {
                log.error("APPDATA environment variable not found");
                throw new RuntimeException("APPDATA environment variable not found");
            }
            log.info("Creating PickMYTrade directory if not exists");
            File pickMyTradeDir = new File(appDataPath, "PickMYTrade");
            if (!pickMyTradeDir.exists()) {
                boolean created = pickMyTradeDir.mkdirs();
                if (!created) {
                    log.error("Failed to create directory: {}", pickMyTradeDir.getAbsolutePath());
                    throw new RuntimeException("Failed to create directory: " + pickMyTradeDir.getAbsolutePath());
                }
            }
            pickMyTradeDirPath = pickMyTradeDir.getAbsolutePath().replace("\\", "/");
            String logPath = pickMyTradeDir.getAbsolutePath().replace("\\", "/");
            // System.setProperty("log.path", String.valueOf(pickMyTradeDir)); // Removed as per update

            log.info("Computed log directory: {}", logPath);
            // log.info("System property log.path set to: {}", System.getProperty("log.path")); // Removed
            if (pickMyTradeDir.exists() && pickMyTradeDir.isDirectory()) {
                log.info("Log directory verified: {}", pickMyTradeDir.getAbsolutePath());
                if (pickMyTradeDir.canWrite()) {
                    log.info("Log directory is writable");
                } else {
                    log.error("Log directory is not writable: {}", pickMyTradeDir.getAbsolutePath());
                }
            } else {
                log.error("Log directory does not exist or is not a directory: {}", pickMyTradeDir.getAbsolutePath());
            }

            File logFile = new File(logPath, "log.log");
            try {
                if (!logFile.exists()) {
                    boolean created = logFile.createNewFile();
                    log.info("Test log file creation: {}", created ? "Created" : "Failed to create");
                } else {
                    log.info("Test log file already exists at: {}", logFile.getAbsolutePath());
                }
            } catch (IOException e) {
                log.error("Failed to create test log file: {}", e.getMessage(), e);
            }

            // Configure logging with default port and cleanup old logs
            LoggingConfig.configure(7497); // Default port
            LoggingConfig.cleanupOldLogs();

            log.info("Starting PickMyTrade IB App");
            log.info("Loading SQLite JDBC driver");
            try {
                Class.forName("org.sqlite.JDBC");
                log.info("SQLite JDBC driver loaded successfully");
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
                    log.info("SQLite in-memory connection successful");
                } catch (SQLException e) {
                    log.error("SQLite connection test failed", e);
                }
            } catch (ClassNotFoundException e) {
                log.error("Failed to load SQLite JDBC driver", e);
            }

            if (logFile.exists()) {
                log.info("Log file found at: {}", logFile.getAbsolutePath());
            } else {
                log.warn("Log file not found at: {}", logFile.getAbsolutePath());
            }

            launch(args);
        } catch (Exception e) {
            log.error("Exception in main method", e);
            System.exit(1);
        }
    }

    @Override
    public void start(Stage primaryStage) {
        log.info("Entering start method");
        appStartTime = Instant.now().toEpochMilli();
        manualTradeCloseTime = appStartTime;
        log.info("Application started at: {}, Manual trade close time set to: {}",
                appStartTime, manualTradeCloseTime);

        try {
            // Don't show the primary stage - just hide it to prevent blank window
            primaryStage.setTitle("PickMyTradeIB");

            Platform.runLater(() -> {
                isJavaFxInitialized = true;
                log.info("JavaFX platform initialized");
            });

            log.info("Checking for updates");
            checkForUpdates(primaryStage);
        } catch (Exception e) {
            log.error("Exception in start method", e);
            throw new RuntimeException("Failed to start application", e);
        }
    }

    private void configureTaskbarSupport(Stage stage) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Platform.runLater(() -> {
                try {
                    log.info("Configuring taskbar support for: {}", stage.getTitle());

                    // Small delay to ensure window is fully initialized
                    Thread.sleep(200);

                    // Get all Glass windows
                    java.util.List<com.sun.glass.ui.Window> windows = com.sun.glass.ui.Window.getWindows();

                    if (windows.isEmpty()) {
                        log.warn("No Glass windows found");
                        return;
                    }

                    // Get the last window in the list (most recently created)
                    com.sun.glass.ui.Window window = windows.get(windows.size() - 1);
                    long lhwnd = window.getNativeWindow();

                    if (lhwnd == 0) {
                        log.warn("Could not find native window handle for stage: {}", stage.getTitle());
                        return;
                    }

                    Pointer lpVoid = new Pointer(lhwnd);
                    WinDef.HWND hwnd = new WinDef.HWND(lpVoid);
                    User32 user32 = User32.INSTANCE;

                    // Get current window style
                    int style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE);

                    // Add minimize box
                    style |= 0x00020000; // WS_MINIMIZEBOX
                    user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style);

                    // Get extended style
                    int exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);

                    // Ensure WS_EX_APPWINDOW is set (shows in taskbar)
                    exStyle |= 0x00040000; // WS_EX_APPWINDOW

                    // Remove WS_EX_TOOLWINDOW if present (prevents taskbar)
                    exStyle &= ~0x00000080; // ~WS_EX_TOOLWINDOW

                    user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle);

                    // Force window to update its frame
                    user32.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                            0x0001 | 0x0002 | 0x0004 | 0x0020); // SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_FRAMECHANGED

                    log.info("Taskbar support configured successfully for: {}", stage.getTitle());
                } catch (Exception ex) {
                    log.error("Failed to configure taskbar support: {}", ex.getMessage(), ex);
                }
            });
        }
    }


    private void checkForUpdates(Stage primaryStage) {
        log.info("Checking for updates at: {}", VERSION_CHECK_URL);
        new Thread(() -> {
            while (!isJavaFxInitialized) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    log.error("Interrupted while waiting for JavaFX initialization", e);
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpGet get = new HttpGet(VERSION_CHECK_URL);
                try (CloseableHttpResponse response = client.execute(get)) {
                    String responseText = EntityUtils.toString(response.getEntity());
                    log.debug("Version check response: {}", responseText);
                    Map<String, Object> versionInfo = gson.fromJson(responseText, new TypeToken<Map<String, Object>>() {
                    }.getType());
                    String minimum_version = (String) versionInfo.get("min_version");
                    String latestVersion = (String) versionInfo.get("latest_version");
                    String releaseNotes = (String) versionInfo.get("release_notes");
                    Map<String, Object> downloadUrlMap = (Map<String, Object>) versionInfo.get("download_url");

                    if (isNewerVersion(minimum_version, app_version)) {
                        log.info("New version available: {}. Current version: {}", latestVersion, app_version);
                        String downloadUrl = getPlatformDownloadUrl(downloadUrlMap, primaryStage);
                        if (downloadUrl != null) {
                            boolean proceedWithLogin = showUpdatePrompt(latestVersion, downloadUrl, releaseNotes);
                            if (proceedWithLogin) {
                                proceedToLogin(primaryStage);
                            }
                        } else {
                            log.warn("No valid download URL for the current platform");
                            Platform.runLater(() -> showErrorPopup("Update not available for this platform."));
                            proceedToLogin(primaryStage);
                        }
                    } else {
                        log.info("No new version available. Current version: {}", app_version);
                        proceedToLogin(primaryStage);
                    }
                }
            } catch (Exception e) {
                log.error("Error checking for updates: {}", e.getMessage(), e);
                Platform.runLater(() -> showErrorPopup("Failed to check for updates: " + e.getMessage()));
                proceedToLogin(primaryStage);
            }
        }).start();
    }

    private void proceedToLogin(Stage primaryStage) {
        Platform.runLater(() -> {
            log.info("No update or update skipped, proceeding with UI");
            log.info("Showing splash screen");
            showSplashScreen(primaryStage);
            log.info("Creating login stage");
            Stage loginStage = new Stage();
            log.info("Showing login window");
            showLoginWindow(loginStage);
        });
    }

    private boolean isNewerVersion(String latestVersion, String currentVersion) {
        String[] latestParts = latestVersion.split("\\.");
        String[] currentParts = currentVersion.split("\\.");
        int length = Math.max(latestParts.length, currentParts.length);

        for (int i = 0; i < length; i++) {
            int latestNum = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
            int currentNum = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            if (latestNum > currentNum) {
                return true;
            } else if (latestNum < currentNum) {
                return false;
            }
        }
        return false;
    }

    private String getPlatformDownloadUrl(Map<String, Object> downloadUrlMap, Stage primaryStage) {
        String osName = System.getProperty("os.name").toLowerCase();
        log.debug("Detected OS: {}", osName);

        if (osName.contains("win")) {
            String windowsUrl = (String) downloadUrlMap.get("windows");
            if (windowsUrl == null || windowsUrl.isEmpty()) {
                log.warn("No Windows download URL provided in server response");
                return null;
            }
            log.info("Using Windows download URL: {}", windowsUrl);
            return windowsUrl;
        } else if (osName.contains("mac")) {
            Map<String, String> macUrls = (Map<String, String>) downloadUrlMap.get("mac");
            if (macUrls == null || macUrls.isEmpty()) {
                log.warn("No macOS download URLs provided in server response");
                return null;
            }
            return showMacOsSelectionPopup(macUrls, primaryStage);
        } else {
            log.warn("Unsupported platform: {}", osName);
            return null;
        }
    }

    private String showMacOsSelectionPopup(Map<String, String> macUrls, Stage primaryStage) {
        log.info("Showing macOS architecture selection popup");
        Stage popup = new Stage();
        popup.initModality(Modality.APPLICATION_MODAL);
        popup.initOwner(primaryStage);
        popup.setTitle("Select macOS Architecture");

        VBox layout = new VBox(10);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(20));

        Label label = new Label("Please select your macOS architecture for the update:");
        label.setFont(Font.font("Arial", 14));

        ComboBox<String> architectureCombo = new ComboBox<>();
        architectureCombo.getItems().addAll("Silicon (Apple M1/M2)", "Intel");
        architectureCombo.setValue("Silicon (Apple M1/M2)");
        architectureCombo.setStyle("-fx-font-size: 14; -fx-pref-height: 40; -fx-border-radius: 20");

        Button okButton = new Button("OK");
        okButton.setStyle("-fx-background-color: #dc143c; -fx-text-fill: white; -fx-border-radius: 20; -fx-pref-height: 40");
        okButton.setOnAction(e -> popup.close());

        layout.getChildren().addAll(label, architectureCombo, okButton);
        Scene scene = new Scene(layout, 400, 200);
        popup.setScene(scene);

        popup.showAndWait();
        String selected = architectureCombo.getValue();
        String downloadUrl = selected.equals("Silicon (Apple M1/M2)") ? macUrls.get("silicon") : macUrls.get("intel");
        log.info("Selected macOS architecture: {}, URL: {}", selected, downloadUrl != null ? downloadUrl : "None");
        return downloadUrl;
    }

    private boolean showUpdatePrompt(String latestVersion, String downloadUrl, String releaseNotes) {
        log.info("Showing update prompt for version: {}", latestVersion);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean proceedWithLogin = new AtomicBoolean(true);

        Platform.runLater(() -> {
            try {
                log.debug("Creating update alert dialog");
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Update Available");
                alert.setHeaderText("A new version (" + latestVersion + ") is available!");
                alert.setContentText("Release Notes: " + releaseNotes + "\nDownloading and Installing Latest version of PickMyTrade IB App.");

                isUpdating = true;
                executor.submit(() -> {
                    try {
                        downloadAndInstallUpdate(downloadUrl, latestVersion);
                    } finally {
                        isUpdating = false;
                    }
                });
                proceedWithLogin.set(false);

            } catch (Exception e) {
                log.error("Error showing update prompt: {}", e.getMessage(), e);
                Platform.runLater(() -> showErrorPopup("Failed to show update prompt: " + e.getMessage()));
                proceedWithLogin.set(true);
            } finally {
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(20, TimeUnit.SECONDS);
            if (!completed) {
                log.error("Update prompt dialog timed out after 20 seconds");
                Platform.runLater(() -> showErrorPopup("Update prompt failed to respond. Proceeding to login."));
                proceedWithLogin.set(true);
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for update prompt: {}", e.getMessage());
            Thread.currentThread().interrupt();
            proceedWithLogin.set(true);
        }

        return proceedWithLogin.get();
    }

    private void downloadAndInstallUpdate(String downloadUrl, String version) {
        log.info("Downloading update from: {}", downloadUrl);
        try {
            Path updateDir = Paths.get(UPDATE_DIR);
            Files.createDirectories(updateDir);
            String fileExtension = System.getProperty("os.name").toLowerCase().contains("win") ? ".msi" : ".pkg";
            Path installerPath = updateDir.resolve("PickMyTrade-IB-" + version + fileExtension);

            final Stage[] progressStage = new Stage[1];
            final ProgressBar[] progressBar = new ProgressBar[1];
            final Label[] progressLabel = new Label[1];
            Platform.runLater(() -> {
                log.debug("Creating progress dialog on JavaFX thread");
                progressStage[0] = new Stage();
                progressStage[0].initModality(Modality.APPLICATION_MODAL);
                progressStage[0].setTitle("Downloading Update v" + version);
                progressStage[0].setAlwaysOnTop(true);
                progressStage[0].centerOnScreen();
                VBox layout = new VBox(10);
                layout.setAlignment(Pos.CENTER);
                layout.setPadding(new Insets(20));
                Label label = new Label("Downloading update, please wait...");
                label.setFont(Font.font("Arial", 14));
                progressBar[0] = new ProgressBar();
                progressBar[0].setPrefWidth(300);
                progressBar[0].setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                progressLabel[0] = new Label("Initializing...");
                progressLabel[0].setFont(Font.font("Arial", 12));
                layout.getChildren().addAll(label, progressBar[0], progressLabel[0]);
                Scene scene = new Scene(layout, 350, 200);
                progressStage[0].setScene(scene);
                progressStage[0].show();
                log.debug("Progress dialog shown, isShowing: {}", progressStage[0].isShowing());
            });

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for progress dialog: {}", e.getMessage());
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during update process");
            }

            log.info("Downloading installer to: {}", installerPath);
            long totalBytes = 0;
            final long[] contentLengthHolder = new long[]{-1};
            try {
                URL url = new URL(downloadUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("HEAD");
                contentLengthHolder[0] = connection.getContentLengthLong();
                connection.disconnect();
                log.info("Content-Length from server: {} bytes", contentLengthHolder[0] >= 0 ? contentLengthHolder[0] : "unknown");

                Platform.runLater(() -> {
                    log.debug("Setting initial progress bar state on JavaFX thread");
                    if (contentLengthHolder[0] > 0) {
                        progressBar[0].setProgress(0);
                        progressLabel[0].setText(String.format("0%% (0/%d bytes)", contentLengthHolder[0]));
                    } else {
                        progressBar[0].setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                        progressLabel[0].setText("Unknown size, starting download...");
                    }
                });

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    log.error("Interrupted while waiting for initial UI update: {}", e.getMessage());
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during update process");
                }

                try (InputStream in = url.openStream();
                     FileOutputStream out = new FileOutputStream(installerPath.toFile())) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    long lastUpdateTime = System.currentTimeMillis();
                    final long[] updateInterval = new long[]{50};
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                        final long downloaded = totalBytes;
                        if (System.currentTimeMillis() - lastUpdateTime >= updateInterval[0]) {
                            Platform.runLater(() -> {
                                log.debug("Updating progress: {} bytes downloaded on JavaFX thread", downloaded);
                                if (contentLengthHolder[0] > 0) {
                                    double progress = (double) downloaded / contentLengthHolder[0];
                                    progressBar[0].setProgress(Math.min(progress, 1.0));
                                    progressLabel[0].setText(String.format("%.1f%% (%d/%d bytes)", progress * 100, downloaded, contentLengthHolder[0]));
                                } else {
                                    progressLabel[0].setText(String.format("%d bytes downloaded", downloaded));
                                }
                            });
                            lastUpdateTime = System.currentTimeMillis();
                        }
                    }
                    log.info("Update downloaded successfully, size: {} bytes", totalBytes);
                }
            } catch (IOException e) {
                log.error("Failed to download installer: {}", e.getMessage(), e);
                throw new IOException("Failed to download installer", e);
            }

            final long finalTotalBytes = totalBytes;
            Platform.runLater(() -> {
                log.debug("Finalizing progress bar state");
                if (contentLengthHolder[0] > 0) {
                    progressBar[0].setProgress(1.0);
                    progressLabel[0].setText(String.format("100%% (%d/%d bytes)", finalTotalBytes, contentLengthHolder[0]));
                } else {
                    progressLabel[0].setText(String.format("%d bytes downloaded", finalTotalBytes));
                }
            });

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.error("Interrupted while showing final progress: {}", e.getMessage());
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during update process");
            }

            Platform.runLater(() -> {
                log.debug("Closing progress dialog");
                if (progressStage[0] != null) {
                    progressStage[0].close();
                }
            });

            File installerFile = installerPath.toFile();
            if (!installerFile.exists() || installerFile.length() == 0) {
                log.error("Installer file does not exist or is empty: {}", installerPath);
                throw new IOException("Downloaded installer file is invalid or empty");
            }
            if (contentLengthHolder[0] > 0 && installerFile.length() != contentLengthHolder[0]) {
                log.error("Installer file size mismatch. Expected: {} bytes, Actual: {} bytes", contentLengthHolder[0], installerFile.length());
                throw new IOException("Downloaded installer file size does not match expected size");
            }
            if (!installerFile.canExecute()) {
                log.warn("Installer file is not executable, attempting to set executable permission: {}", installerPath);
                boolean setExecutable = installerFile.setExecutable(true);
                if (!setExecutable) {
                    log.error("Failed to set executable permission for installer: {}", installerPath);
                    throw new IOException("Cannot set executable permission for installer");
                }
            }

            app_version = version;
            log.info("Updated app_version to: {}", version);

            log.info("Launching installer: {}", installerPath);
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "\"" + installerPath.toString() + "\"");
            } else {
                pb = new ProcessBuilder("open", "-W", installerPath.toString());
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);

            Process process = pb.start();
            shutdownApplication();
            StringBuilder processOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processOutput.append(line).append("\n");
                }
            }
            String output = processOutput.toString();
            log.info("Installer process output: {}", output.isEmpty() ? "<empty>" : output);
            shutdownApplication();
            try {
                boolean exited = process.waitFor(5, TimeUnit.SECONDS);
                if (exited) {
                    int exitCode = process.exitValue();
                    isUpdating = false;
                    shutdownApplication();
                } else {
                    log.info("Installer process started successfully, continuing");
                    isUpdating = false;
                    shutdownApplication();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for installer to start: {}", e.getMessage());
                isUpdating = false;
                shutdownApplication();
            }

        } catch (Exception e) {
            log.error("Error downloading or installing update: {}", e.getMessage(), e);
            isUpdating = false;
            shutdownApplication();
        }
    }

    @Override
    public void stop() {
        if (isUpdating) {
            log.info("Application stop requested but update is in progress, delaying shutdown");
            return;
        }
        log.info("Application stop method called");
        new Thread(() -> {
            try {
                showCloseWarningPopup();
                shutdownApplication();
            } catch (Exception e) {
                log.error("Error during application stop: {}", e.getMessage());
                System.exit(1);
            }
        }).start();
    }

    private void showCloseWarningPopup() {
        log.info("Displaying application closing warning popup");
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                Stage popupStage = new Stage();
                popupStage.initModality(Modality.APPLICATION_MODAL);
                popupStage.initStyle(StageStyle.UTILITY);
                popupStage.setTitle("PickMyTrade IB App Closing");

                VBox layout = new VBox(20);
                layout.setAlignment(Pos.CENTER);
                layout.setPadding(new Insets(20));
                layout.setStyle("-fx-background-color: white;");

                Label title = new Label("PickMyTrade IB App Closing");
                title.setFont(Font.font("Arial", 18));
                title.setTextFill(Color.BLACK);
                title.setTextAlignment(TextAlignment.CENTER);

                Label message = new Label("The application is closing. Please manage your open positions and open orders manually.");
                message.setFont(Font.font("Arial", 14));
                message.setWrapText(true);
                message.setTextAlignment(TextAlignment.CENTER);
                message.setTextFill(Color.BLACK);

                Button okButton = new Button("OK");
                okButton.setPrefHeight(40);
                okButton.setStyle("-fx-background-color: #dc143c; -fx-text-fill: white; -fx-border-radius: 20; -fx-font-family: Arial; -fx-font-size: 14;");
                okButton.setOnMouseEntered(e -> okButton.setStyle("-fx-background-color: #c21032; -fx-text-fill: white; -fx-border-radius: 20; -fx-font-family: Arial; -fx-font-size: 14;"));
                okButton.setOnMouseExited(e -> okButton.setStyle("-fx-background-color: #dc143c; -fx-text-fill: white; -fx-border-radius: 20; -fx-font-family: Arial; -fx-font-size: 14;"));
                okButton.setOnAction(e -> {
                    log.debug("User clicked OK on warning popup");
                    popupStage.close();
                    latch.countDown();
                });

                layout.getChildren().addAll(title, message, okButton);
                Scene scene = new Scene(layout, 400, 250);
                popupStage.setScene(scene);

                PauseTransition delay = new PauseTransition(Duration.seconds(5));
                delay.setOnFinished(e -> {
                    if (popupStage.isShowing()) {
                        log.debug("Auto-closing warning popup");
                        popupStage.close();
                        latch.countDown();
                    }
                });

                log.debug("Showing warning popup");
                popupStage.show();
                delay.play();
            } catch (Exception e) {
                log.error("Error displaying warning popup: {}", e.getMessage(), e);
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(6, TimeUnit.SECONDS);
            if (completed) {
                log.info("Application closing warning popup displayed and closed");
            } else {
                log.warn("Popup did not close within 6 seconds, proceeding with shutdown");
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for warning popup: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownApplication() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            log.info("Shutdown already in progress, skipping duplicate request");
            return;
        }
        log.info("Shutting down application...");
        long runtimeMillis = System.currentTimeMillis() - appStartTime;
        log.info("Application ran for {} seconds", runtimeMillis / 1000);
        try {
            synchronized (websocketTasks) {
                websocketTasks.forEach(task -> task.cancel(true));
                websocketTasks.clear();
            }

            if (tradeServer != null) {
                log.info("Stopping trade server...");
                tradeServer.stop();
            }

            if (twsEngine != null && twsEngine.isConnected()) {
                log.info("Disconnecting TWS...");
                twsEngine.disconnect();
            }

            if (websocket != null && websocket.isOpen()) {
                log.info("Closing WebSocket...");
                websocket.close();
            }

            if (subscriber != null) {
                log.info("Stopping server connection subscriber...");
                try {
                    subscriber.stopAsync().awaitTerminated(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.warn("server connection subscriber did not terminate in time, forcing shutdown...");
                }
                subscriber = null;
            }

            if (executor != null && !executor.isShutdown()) {
                log.info("Shutting down executor service...");
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("Executor did not terminate in time, forcing shutdown...");
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    log.error("Executor shutdown interrupted: {}", e.getMessage());
                    executor.shutdownNow();
                }
            }

            if (websocketExecutor != null && !websocketExecutor.isShutdown()) {
                log.info("Shutting down WebSocket executor service...");
                websocketExecutor.shutdown();
                try {
                    if (!websocketExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("WebSocket executor did not terminate in time, forcing shutdown...");
                        websocketExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    log.error("WebSocket executor shutdown interrupted: {}", e.getMessage());
                    websocketExecutor.shutdownNow();
                }
            }

            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdownNow();
            }
            if (pubsubScheduler != null && !pubsubScheduler.isShutdown()) {
                pubsubScheduler.shutdownNow();
            }

            log.info("Exiting JavaFX platform...");
            Platform.exit();

            log.info("Terminating JVM...");
            System.exit(0);
        } catch (Exception e) {
            log.error("Error during shutdown: {}", e.getMessage());
            System.exit(1);
        }
    }

    private void showSplashScreen(Stage primaryStage) {
        log.info("Displaying splash screen");
        try {
            Stage splashStage = new Stage();
            splashStage.initOwner(primaryStage);
            splashStage.initStyle(StageStyle.UNDECORATED);
            VBox splashLayout = new VBox(20);
            splashLayout.setAlignment(Pos.CENTER);
            splashLayout.setStyle("-fx-background-color: white; -fx-padding: 20;");
            log.info("Loading spinner.gif");
            Image spinnerImage = new Image(getClass().getResourceAsStream("/spinner.gif"));
            if (spinnerImage.isError()) {
                log.error("Failed to load spinner.gif");
                throw new RuntimeException("Missing or invalid spinner.gif");
            }
            ImageView spinner = new ImageView(spinnerImage);
            spinner.setFitWidth(100);
            spinner.setFitHeight(100);
            Label text = new Label("PickMyTrade IB App starting");
            text.setFont(Font.font("Arial", 18));
            text.setTextFill(Color.BLACK);
            splashLayout.getChildren().addAll(spinner, text);
            Scene splashScene = new Scene(splashLayout, 400, 400);
            splashStage.setScene(splashScene);
            splashStage.setOnCloseRequest(e -> {
                if (isUpdating) {
                    log.info("Splash screen close request ignored due to ongoing update");
                    e.consume();
                }
            });
            splashStage.show();

            new Thread(() -> {
                try {
                    log.debug("Splash screen displayed, waiting for 5 seconds");
                    Thread.sleep(5000);
                    Platform.runLater(() -> {
                        log.info("Closing splash screen");
                        splashStage.close();
                    });
                } catch (InterruptedException e) {
                    log.error("Splash screen interrupted: {}", e.getMessage());
                }
            }).start();
        } catch (Exception e) {
            log.error("Error in showSplashScreen", e);
            throw e;
        }
    }

    private void showLoginWindow(Stage loginStage) {
        log.info("Setting up login window");
        try {
            // Load configs and set current if exists
            List<Map<String, Object>> configs = loadConfigs();
            if (!configs.isEmpty()) {
                Map<String, Object> last = configs.get(configs.size() - 1);
                tws_trade_port = ((Number) last.get("tws_port")).intValue();
                trade_server_port = ((Number) last.get("trade_port")).intValue();
                current_db_url = (String) last.get("db_url");
            } else {
                current_db_url = "jdbc:sqlite:" + pickMyTradeDirPath.replace("\\", "/") + "/IB_7497.db";
            }

            // Reconfigure logging based on the loaded/ default port
            LoggingConfig.configure(tws_trade_port);

            loginStage.setTitle("Login");
            VBox layout = new VBox(15);
            layout.setPadding(new Insets(20));
            layout.setStyle("-fx-background-color: white;");
            layout.setAlignment(Pos.TOP_CENTER);

            // Title with logo
            HBox titleLayout = new HBox(10);
            titleLayout.setAlignment(Pos.CENTER);
            log.info("Loading logo.png");
            Image logoImage = new Image(getClass().getResourceAsStream("/logo.png"));
            if (logoImage.isError()) {
                log.error("Failed to load logo.png");
                throw new RuntimeException("Missing or invalid logo.png");
            }
            ImageView logo = new ImageView(logoImage);
            logo.setFitWidth(120);
            logo.setFitHeight(120);
            Label title = new Label("PickMyTrade IB application");
            title.setFont(Font.font("Arial", 18));
            titleLayout.getChildren().addAll(logo, title);

            // Subtitle
            Label subtitle = new Label("Please ensure TWS is open and logged in before accessing this application.");
            subtitle.setFont(Font.font("Arial", 14));
            subtitle.setWrapText(true);
            subtitle.setTextAlignment(TextAlignment.CENTER);

            // Email input
            Label emailLabel = new Label("Email*");
            TextField emailInput = new TextField();
            emailInput.setPromptText("Enter Email");
            emailInput.setPrefHeight(40);
            emailInput.setStyle("-fx-border-radius: 20;");

            // Password input
            Label passwordLabel = new Label("Password*");
            PasswordField passwordInput = new PasswordField();
            passwordInput.setPromptText("Enter your password");
            passwordInput.setPrefHeight(40);
            passwordInput.setStyle("-fx-border-radius: 20;");

            // Login button
            Button loginButton = new Button("Login");
            loginButton.setPrefHeight(50);
            loginButton.setStyle("-fx-background-color: #dc143c; -fx-text-fill: white; -fx-border-radius: 20;");
            loginButton.setOnMouseEntered(e -> loginButton.setStyle("-fx-background-color: #c21032; -fx-text-fill: white; -fx-border-radius: 20;"));
            loginButton.setOnMouseExited(e -> loginButton.setStyle("-fx-background-color: #dc143c; -fx-text-fill: white; -fx-border-radius: 20;"));

            // Loader
            ProgressBar loader = new ProgressBar();
            loader.setPrefHeight(20);
            loader.setVisible(false);
            loader.setStyle("-fx-accent: #dc143c;");

            // Error label
            Label errorLabel = new Label("");
            errorLabel.setTextFill(Color.RED);
            errorLabel.setFont(Font.font("Arial", 10));
            errorLabel.setWrapText(true);
            errorLabel.setTextAlignment(TextAlignment.CENTER);

            Button googleButton = new Button("Sign in with Google");
            googleButton.setPrefHeight(50);
            googleButton.setStyle("-fx-background-color: #4285F4; -fx-text-fill: white; -fx-border-radius: 20;");
            googleButton.setOnMouseEntered(e -> googleButton.setStyle("-fx-background-color: #357ae8; -fx-text-fill: white; -fx-border-radius: 20;"));
            googleButton.setOnMouseExited(e -> googleButton.setStyle("-fx-background-color: #4285F4; -fx-text-fill: white; -fx-border-radius: 20;"));
            googleButton.setOnAction(e -> googleLogin(loginStage, errorLabel, loader, googleButton));

            // Actions
            loginButton.setOnAction(e -> login(loginStage, emailInput.getText(), passwordInput.getText(), errorLabel, loader, loginButton));
            passwordInput.setOnAction(e -> login(loginStage, emailInput.getText(), passwordInput.getText(), errorLabel, loader, loginButton));

            // New buttons
            Button videosButton = new Button("View Tutorials");
            videosButton.setPrefHeight(40);
            videosButton.setStyle("-fx-background-color: #007bff; -fx-text-fill: white; -fx-border-radius: 20;");
            videosButton.setOnAction(e -> showVideosPopup());

            Button sendLogsButton = new Button("Reset Connection");
            sendLogsButton.setPrefHeight(40);
            sendLogsButton.setStyle("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-border-radius: 20;");
            sendLogsButton.setOnAction(e -> executor.submit(this::resetconnection));

            Button configureButton = new Button("Configure");
            configureButton.setPrefHeight(40);
            configureButton.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-border-radius: 20;");
            configureButton.setOnAction(e -> showConfigureDialog());

            HBox buttonsHBox = new HBox(10);
            buttonsHBox.setAlignment(Pos.CENTER);
            buttonsHBox.getChildren().addAll(videosButton, sendLogsButton, configureButton);

            // Spacer to push bottom buttons down
            Region spacer = new Region();
            VBox.setVgrow(spacer, Priority.ALWAYS);

            // Add everything to layout
            layout.getChildren().addAll(
                    titleLayout,
                    subtitle,
                    emailLabel, emailInput,
                    passwordLabel, passwordInput,
                    loginButton,
                    googleButton,
                    loader,
                    errorLabel,
                    spacer,
                    buttonsHBox
            );

            // Scene
            Scene scene = new Scene(layout, 600, 500);
            loginStage.setScene(scene);

            loginStage.setOnCloseRequest(e -> {
                if (isUpdating) {
                    log.info("Login window close request ignored due to ongoing update");
                    e.consume();
                } else {
                    log.info("Close request received for login stage. Initiating shutdown.");
                    stop();
                }
            });

            // Configure taskbar support before showing
            loginStage.show();
            configureTaskbarSupport(loginStage);

            if (!lastUsername.isEmpty()) {
                log.debug("Populating email input with last username: {}", lastUsername);
                emailInput.setText(lastUsername);
            }
            if (!lastPassword.isEmpty()) {
                log.debug("Populating password input with last password");
                passwordInput.setText(lastPassword);
            }
        } catch (Exception e) {
            log.error("Error in showLoginWindow", e);
            throw e;
        }
    }

    private boolean checkServerPortFree(int port) {
        try {
            Process process = Runtime.getRuntime().exec("cmd /c netstat -ano | findstr :" + port);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            boolean inUse = false;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    inUse = true;
                    break;
                }
            }
            process.waitFor();
            return !inUse;
        } catch (Exception e) {
            log.error("Error checking port {} availability: {}", port, e.getMessage(), e);
            return false;
        }
    }

    private void showConfigureDialog() {
        log.info("Showing configure dialog");
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Configure TWS Port");

        VBox layout = new VBox(10);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(20));

        Label label = new Label("TWS API Port:");
        label.setFont(Font.font("Arial", 14));

        ComboBox<String> portCombo = new ComboBox<>();
        portCombo.setEditable(true);

        List<Map<String, Object>> configs = loadConfigs();
        for (Map<String, Object> c : configs) {
            portCombo.getItems().add(String.valueOf(((Number) c.get("tws_port")).intValue()));
        }
        portCombo.setValue(String.valueOf(tws_trade_port));
        portCombo.setStyle("-fx-font-size: 14; -fx-pref-height: 40; -fx-border-radius: 20");

        Button saveButton = new Button("Save");
        saveButton.setStyle("-fx-background-color: #dc143c; -fx-text-fill: white; -fx-border-radius: 20; -fx-pref-height: 40");
        saveButton.setOnAction(e -> {
            String portStr = portCombo.getValue();
            if (portStr == null || portStr.trim().isEmpty()) {
                showErrorPopup("Please enter a TWS API port");
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portStr.trim());
                if (port < 1024 || port > 65535) {
                    showErrorPopup("Port must be between 1024 and 65535");
                    return;
                }
            } catch (NumberFormatException ex) {
                showErrorPopup("Invalid port number");
                return;
            }

            int proposedTradePort = port + 10;

//            // Check TWS port available
//            if (!checkClientPortAvailable("localhost", port)) {
//                showErrorPopup("TWS is not running on port " + port + ". Please select a different TWS port.");
//                return;
//            }

            // Check local trade port free
            if (!checkServerPortFree(proposedTradePort)) {
                showErrorPopup("The local port " + proposedTradePort + " is not available. Please select a different TWS port.");
                return;
            }

            // Compute db url
            String proposedDbPath = pickMyTradeDirPath + "/IB_" + port + ".db";
            String proposedDbUrl = "jdbc:sqlite:" + proposedDbPath.replace("\\", "/");

            // Add if new
            boolean exists = configs.stream().anyMatch(c -> ((Number) c.get("tws_port")).intValue() == port);
            if (!exists) {
                Map<String, Object> newConfig = new HashMap<>();
                newConfig.put("tws_port", port);
                newConfig.put("db_url", proposedDbUrl);
                newConfig.put("trade_port", proposedTradePort);
                configs.add(newConfig);
                saveConfigs(configs);
            }

            // Set current
            tws_trade_port = port;
            trade_server_port = proposedTradePort;
            current_db_url = proposedDbUrl;

            // Reconfigure logging for the new port
            LoggingConfig.configure(tws_trade_port);

            dialog.close();
        });

        layout.getChildren().addAll(label, portCombo, saveButton);
        Scene scene = new Scene(layout, 400, 200);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private List<Map<String, Object>> loadConfigs() {
        List<Map<String, Object>> configs = new ArrayList<>();
        String path = pickMyTradeDirPath + "/configs.json";
        File configFile = new File(path);
        if (configFile.exists()) {
            try (FileReader fr = new FileReader(configFile)) {
                Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
                configs = gson.fromJson(fr, listType);
            } catch (Exception e) {
                log.error("Error loading configs: {}", e.getMessage(), e);
            }
        }
        if (configs.isEmpty()) {
            int defaultTwsPort = 7497;
            int defaultTradePort = defaultTwsPort + 10;
            String defaultDbPath = pickMyTradeDirPath + "/IB_" + defaultTwsPort + ".db";
            String defaultDbUrl = "jdbc:sqlite:" + defaultDbPath.replace("\\", "/");
            Map<String, Object> defaultConfig = new HashMap<>();
            defaultConfig.put("tws_port", defaultTwsPort);
            defaultConfig.put("db_url", defaultDbUrl);
            defaultConfig.put("trade_port", defaultTradePort);
            configs.add(defaultConfig);
        }
        return configs;
    }

    private void saveConfigs(List<Map<String, Object>> configs) {
        String path = pickMyTradeDirPath + "/configs.json";
        try (FileWriter fw = new FileWriter(path)) {
            gson.toJson(configs, fw);
        } catch (Exception e) {
            log.error("Error saving configs: {}", e.getMessage(), e);
        }
    }


    private void showVideosPopup() {
        Stage popup = new Stage();
        popup.initModality(Modality.APPLICATION_MODAL);
        popup.setTitle("Tutorial Videos");

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(20));
        layout.setStyle("-fx-background-color: white;");

        List<Map<String, String>> videos = Arrays.asList(
                Map.of("title", "How to set up automated trading from TradingView to Interactive Brokers?",
                        "link", "https://youtu.be/7P7tEw0zSIQ"),
                Map.of("title", "Automating TradingView strategies on Interactive Brokers for futures and stocks.",
                        "link", "https://youtu.be/iwhUZKdBCzI"),
                Map.of("title", "Automating TradingView indicators with Interactive Brokers (IBKR).",
                        "link", "https://youtu.be/U1aM18NXk10"),
                Map.of("title", "How to Automate Interactive Brokers (IBKR) Trading Using a TradingView Indicator",
                        "link", "https://youtu.be/TAZuCYmYp9A"),
                Map.of("title", "How to Automate Interactive Brokers (IBKR) Trading Using a TradingView Strategy",
                        "link", "https://youtu.be/TfqrekJ6SZw")
        );

        for (Map<String, String> video : videos) {
            HBox videoBox = new HBox(10);
            videoBox.setAlignment(Pos.CENTER_LEFT);

            // Extract videoId from link
            String link = video.get("link");
            String videoId = null;
            if (link.contains("youtu.be/")) {
                videoId = link.substring(link.lastIndexOf("/") + 1);
            } else if (link.contains("v=")) {
                videoId = link.substring(link.indexOf("v=") + 2);
                int ampIndex = videoId.indexOf("&");
                if (ampIndex != -1) {
                    videoId = videoId.substring(0, ampIndex);
                }
            }

            String thumbnailUrl = "https://img.youtube.com/vi/" + videoId + "/0.jpg";
            ImageView thumbnail = new ImageView(new Image(thumbnailUrl, true));
            thumbnail.setFitWidth(120);
            thumbnail.setFitHeight(90);

            Label titleLabel = new Label(video.get("title"));
            titleLabel.setFont(Font.font("Arial", 14));
            titleLabel.setWrapText(true);

            videoBox.getChildren().addAll(thumbnail, titleLabel);
            videoBox.setOnMouseClicked(e -> getHostServices().showDocument(link));
            videoBox.setStyle("-fx-cursor: hand;");

            layout.getChildren().add(videoBox);
        }

        ScrollPane scrollPane = new ScrollPane(layout);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: white;");

        Scene scene = new Scene(scrollPane, 600, 400);
        popup.setScene(scene);
        popup.showAndWait();
    }


    private void login(Stage loginStage, String username, String password, Label errorLabel, ProgressBar loader, Button loginButton) {
        log.info("Attempting login for user: {}", username);
        String termsMessage = """
                PickMyTrade offers a powerful solution for fully automating trade execution through webhooks that link directly to your broker and exchange accounts. However, it’s important to recognize that automation carries inherent risks. Whether you're a beginner or a seasoned trader, it’s vital to approach automated trading carefully and stay mindful of the potential dangers involved.
                
                BY USING PICKMYTRADE, YOU ACKNOWLEDGE THAT YOU ARE AWARE OF THE RISKS ASSOCIATED WITH AUTOMATING TRADES ON THE PLATFORM AND ACCEPT FULL RESPONSIBILITY FOR THESE RISKS.
                
                By continuing to use PickMyTrade, you agree to release the company, its parent entity, affiliates, and employees from any claims, liabilities, costs, losses, damages, or expenses resulting from or related to your use of the platform. This includes, but is not limited to, any complications caused by system errors, malfunctions, or downtime impacting PickMyTrade or its third-party service providers. You are fully responsible for monitoring your trades and ensuring that your signals are accurately executed. By using PickMyTrade, you accept this waiver and confirm that you have read, understood, and agreed to the PickMyTrade Terms of Service and Privacy Policy, which includes additional disclaimers and restrictions.
                """;
        try {
            // Set DB URL before any DB access
            DatabaseConfig.setDbUrl(current_db_url);
            try {
                DatabaseConfig.initializeTables();
            } catch (SQLException ex) {
                log.error("Failed to initialize database tables: {}", ex.getMessage(), ex);
                showErrorPopup("Failed to initialize database: " + ex.getMessage());
                return;
            }
            log.debug("Checking existing token in database");
            Token token = DatabaseConfig.getToken();
            if (token == null) {
                log.info("No existing token found, showing terms confirmation");
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION, termsMessage, ButtonType.YES, ButtonType.NO);
                alert.setTitle("Connect");
                alert.setHeaderText(null);
                Optional<ButtonType> result = alert.showAndWait();
                if (result.isEmpty() || result.get() != ButtonType.YES) {
                    log.info("User declined terms, aborting login");
                    return;
                }
            }

            log.debug("Showing loader and disabling login button");
            loader.setVisible(true);
            loginButton.setDisable(true);

            Map<String, String> payload = new HashMap<>();
            payload.put("username", username);
            payload.put("password", password);
            payload.put("app_version", app_version );
            ConnectionEntity connection = DatabaseConfig.getConnectionEntity();
            if (connection != null) {
                log.debug("Adding connection name to payload: {}", connection.getConnectionName());
                payload.put("connection_name", connection.getConnectionName());
            }

            log.info("Sending login request with payload: {}", gson.toJson(payload));
            Map<String, Object> response = loginAndGetToken(payload);
            log.info("Login response: {}", gson.toJson(response));

            log.debug("Hiding loader and enabling login button");
            loader.setVisible(false);
            loginButton.setDisable(false);

            Platform.runLater(() -> handleLoginResponse(loginStage, response, username, password, errorLabel, loader, false, null));
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "An unexpected error occurred during login";
            log.error("Login error: {}", errorMessage, e);
            showErrorPopup(errorMessage);
        } finally {
            Platform.runLater(() -> {
                loader.setVisible(false);
                loginButton.setDisable(false);
            });
        }
    }

    private void googleLogin(Stage loginStage, Label errorLabel, ProgressBar loader, Button googleButton) {
        log.info("Starting Google login process");
        loader.setVisible(true);
        googleButton.setDisable(true);

        executor.submit(() -> {
            try {
                // Set DB URL before any DB access
                DatabaseConfig.setDbUrl(current_db_url);
                try {
                    DatabaseConfig.initializeTables();
                } catch (SQLException ex) {
                    log.error("Failed to initialize database tables: {}", ex.getMessage(), ex);
                    Platform.runLater(() -> showErrorPopup("Failed to initialize database: " + ex.getMessage()));
                    return;
                }
                log.debug("Checking existing token in database");
                Token token = DatabaseConfig.getToken();
                String termsMessage = """
                        PickMyTrade offers a powerful solution for fully automating trade execution through webhooks that link directly to your broker and exchange accounts. However, it’s important to recognize that automation carries inherent risks. Whether you're a beginner or a seasoned trader, it’s vital to approach automated trading carefully and stay mindful of the potential dangers involved.
                        
                        BY USING PICKMYTRADE, YOU ACKNOWLEDGE THAT YOU ARE AWARE OF THE RISKS ASSOCIATED WITH AUTOMATING TRADES ON THE PLATFORM AND ACCEPT FULL RESPONSIBILITY FOR THESE RISKS.
                        
                        By continuing to use PickMyTrade, you agree to release the company, its parent entity, affiliates, and employees from any claims, liabilities, costs, losses, damages, or expenses resulting from or related to your use of the platform. This includes, but is not limited to, any complications caused by system errors, malfunctions, or downtime impacting PickMyTrade or its third-party service providers. You are fully responsible for monitoring your trades and ensuring that your signals are accurately executed. By using PickMyTrade, you accept this waiver and confirm that you have read, understood, and agreed to the PickMyTrade Terms of Service and Privacy Policy, which includes additional disclaimers and restrictions.
                        """;
                if (token == null) {
                    log.info("No existing token found, showing terms confirmation");
                    CountDownLatch latch = new CountDownLatch(1);
                    AtomicBoolean accepted = new AtomicBoolean(false);
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, termsMessage, ButtonType.YES, ButtonType.NO);
                        alert.setTitle("Connect");
                        alert.setHeaderText(null);
                        Optional<ButtonType> result = alert.showAndWait();
                        if (result.isPresent() && result.get() == ButtonType.YES) {
                            accepted.set(true);
                        }
                        latch.countDown();
                    });
                    latch.await();
                    if (!accepted.get()) {
                        log.info("User declined terms, aborting login");
                        return;
                    }
                }

                int port = 5000;
                String redirectUri = "http://127.0.0.1:" + port ;

                // Step 1: Build Google OAuth URL (Implicit Flow)
                String authUrl = "https://accounts.google.com/o/oauth2/v2/auth"
                        + "?response_type=id_token"
                        + "&client_id=" + URLEncoder.encode(GOOGLE_CLIENT_ID, "UTF-8")
                        + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
                        + "&scope=" + URLEncoder.encode("openid email profile", "UTF-8")
                        + "&nonce=" + UUID.randomUUID()
                        + "&prompt=select_account";

                log.info("Opening browser for Google authentication...");
                Desktop.getDesktop().browse(URI.create(authUrl));

                String idToken = null;

                // Step 2: Start local server to capture redirect
                try (ServerSocket server = new ServerSocket(port)) {
                    server.setSoTimeout(60000); // 60 seconds timeout for accept
                    log.info("Listening for Google redirect on port {}", port);

                    // First: Handle the initial GET /auth (respond with HTML + JS to POST the token)
                    try (Socket getSocket = server.accept();
                         BufferedReader getReader = new BufferedReader(new InputStreamReader(getSocket.getInputStream()));
                         PrintWriter getWriter = new PrintWriter(getSocket.getOutputStream(), true)) {

                        String getLine = getReader.readLine();  // e.g., "GET /auth HTTP/1.1" (no fragment)

                        // Respond with HTML containing JS to extract and POST the id_token
                        getWriter.println("HTTP/1.1 200 OK");
                        getWriter.println("Content-Type: text/html");
                        getWriter.println();
                        getWriter.println("<html><body>");
                        getWriter.println("<h2>Authenticating...</h2>");
                        getWriter.println("<script>");
                        getWriter.println("const hash = window.location.hash.substring(1);");
                        getWriter.println("const params = new URLSearchParams(hash);");
                        getWriter.println("const idToken = params.get('id_token');");
                        getWriter.println("if (idToken) {");
                        getWriter.println("  fetch('/token', {");
                        getWriter.println("    method: 'POST',");
                        getWriter.println("    headers: {'Content-Type': 'text/plain'},");
                        getWriter.println("    body: idToken");
                        getWriter.println("  }).then(response => response.text()).then(text => {");
                        getWriter.println("    document.body.innerHTML = '<h2> Google Authentication successful!</h2><p>You can close this tab and return to the app.</p>';");
                        getWriter.println("  }).catch(error => {");
                        getWriter.println("    document.body.innerHTML = '<h2>Error: ' + error.message + '</h2>';");
                        getWriter.println("  });");
                        getWriter.println("} else {");
                        getWriter.println("  document.body.innerHTML = '<h2>Error: No ID token found in URL.</h2>';");
                        getWriter.println("}");
                        getWriter.println("</script>");
                        getWriter.println("</body></html>");
                    }

                    // Second: Handle the POST /token from JS (read id_token from body)
                    try (Socket postSocket = server.accept();
                         BufferedReader postReader = new BufferedReader(new InputStreamReader(postSocket.getInputStream()));
                         PrintWriter postWriter = new PrintWriter(postSocket.getOutputStream(), true)) {

                        String postLine = postReader.readLine();  // e.g., "POST /token HTTP/1.1"

                        if (postLine != null && postLine.startsWith("POST /token")) {
                            // Read headers to find Content-Length
                            String headerLine;
                            int contentLength = 0;
                            while ((headerLine = postReader.readLine()) != null && !headerLine.isEmpty()) {
                                if (headerLine.startsWith("Content-Length:")) {
                                    contentLength = Integer.parseInt(headerLine.substring("Content-Length:".length()).trim());
                                }
                            }

                            // Read the body (id_token)
                            if (contentLength > 0) {
                                char[] buffer = new char[contentLength];
                                postReader.read(buffer, 0, contentLength);
                                idToken = new String(buffer);
                                idToken = URLDecoder.decode(idToken, "UTF-8");  // Optional: decode if needed
                            }

                            // Respond to the POST (JS doesn't need the body, but send OK)
                            postWriter.println("HTTP/1.1 200 OK");
                            postWriter.println("Content-Type: text/plain");
                            postWriter.println();
                            postWriter.println("Token received");
                        } else {
                            throw new Exception("Expected POST /token but received: " + postLine);
                        }
                    }
                }

                if (idToken == null) {
                    throw new Exception("No ID token received from Google redirect.");
                }

                log.info("✅ Received ID token from Google.");

                // Build payload similar to normal login, but with token instead of username/password
                Map<String, String> payload = new HashMap<>();
                payload.put("token", idToken);
                payload.put("app_version", app_version);
                ConnectionEntity connection = DatabaseConfig.getConnectionEntity();
                if (connection != null) {
                    payload.put("connection_name", connection.getConnectionName());
                }

                log.info("Sending Google login payload to backend...");
                Map<String, Object> response = loginAndGetToken(payload);

                // Handle backend response
                String finalIdToken = idToken;
                Platform.runLater(() -> handleLoginResponse(loginStage, response, "", "", errorLabel, loader, true, finalIdToken));
            } catch (Exception e) {
                log.error("Google login failed: {}", e.getMessage(), e);
                Platform.runLater(() -> {
                    showErrorPopup("Google login failed: " + e.getMessage());
                    loader.setVisible(false);
                    googleButton.setDisable(false);
                });
            } finally {
                Platform.runLater(() -> {
                    loader.setVisible(false);
                    googleButton.setDisable(false);
                });
            }
        });
    }

    private void handleLoginResponse(Stage loginStage, Map<String, Object> response, String username, String password, Label errorLabel, ProgressBar loader, boolean isGoogle, String googleToken) {
        if (response.containsKey("success") && (boolean) response.get("success")) {
            appStartTime = Instant.now().toEpochMilli();
            log.info("Login successful for user: {}", username);
            if (!isGoogle) {
                lastUsername = username;
                lastPassword = password;
            } else {
                // Decode JWT to get email for lastUsername
                try {
                    String[] parts = googleToken.split("\\.");
                    String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                    JsonObject json = gson.fromJson(payloadJson, JsonObject.class);
                    String email = json.get("email").getAsString();
                    lastUsername = email;
                    lastPassword = "";
                    log.info("Set lastUsername to Google email: {}", email);
                } catch (Exception e) {
                    log.warn("Failed to extract email from Google id_token: {}", e.getMessage(), e);
                    lastUsername = "";
                    lastPassword = "";
                }
            }
            lastConnectionName = (String) response.get("connection_name");
            lastSubscriptionId = response.containsKey("subscription_id") ? (String) response.get("subscription_id") : "sub-user-A";
            String accessTokenString = response.containsKey("access_token") ? (String) response.get("access_token") : "ya29.c.c0ASRK0....."; // Fallback to default
            String accessTokenKey = response.containsKey("token_key") ? (String) response.get("token_key") : "N/A";
            log.info("Subscription ID: {}, Access Token: {}", lastSubscriptionId, accessTokenString);

            // Initialize after successful login
            log.info("Creating TwsEngine");
            twsEngine = new TwsEngine();
            log.info("Creating PlaceOrderService");
            placeOrderService = new PlaceOrderService(twsEngine);
            log.info("Initializing TradeServer");
            tradeServer = new TradeServer(trade_server_port, placeOrderService);
            log.info("Starting TradeServer");
            try {
                tradeServer.start();
            } catch (IOException e) {
                log.error("Failed to start trade server: {}", e.getMessage(), e);
                showErrorPopup("Failed to start local trade server on port " + trade_server_port + ". Please check if the port is free or configure a different port.");
            }
            log.info("Clearing OrderClient table");
            DatabaseConfig.emptyOrderClientTable();

            connectionStage = new Stage();
            connectionStage.setTitle("Connection Status");
            connectionStage.setScene(createConnectionStatusScene(connectionStage));
            connectionStage.setOnCloseRequest(e -> {
                if (isUpdating) {
                    log.info("Connection stage close request ignored due to ongoing update");
                    e.consume();
                } else {
                    log.info("Close request received for connection stage. Initiating shutdown.");
                    stop();
                }
            });
            log.info("Showing connection status window");
            connectionStage.show();
            configureTaskbarSupport(connectionStage);
            log.debug("Closing login stage");
            loginStage.close();
            log.info("Starting TWS and server connection connections after successful login");
            heartbeat_auth_token = (String) response.get("connection_name");
            heartbeat_connection_id = (String) response.get("id");
            heartbeat_new_token = accessTokenString;
            heartbeat_snew_token_id = accessTokenKey;
            orderExecutor.submit(this::scheduleOrderSender);
//                            executor.submit(() -> monitorHeartbeatAck(connectionStage));
            executor.submit(() -> continuouslyCheckTwsConnection(connectionStage));
            // Comment out WebSocket initialization as per requirement
            // websocketExecutor.submit(() -> checkWebsocket(connectionStage));

            // Start server connection subscriber with access token
            executor.submit(() -> startPubSubSubscriber(lastSubscriptionId, heartbeat_new_token, heartbeat_snew_token_id));
            executor.submit(() -> monitorPubSubConnection(lastSubscriptionId));
            executor.submit(this::sendAccountDataToServer);

        } else if (response.containsKey("connections")) {
            log.info("Multiple connections available, showing connection selection");
            List<String> connections = (List<String>) response.get("connections");
            if (!connections.isEmpty()) {
                String selectedConnection = showConnectionPopup(connections);
                if (selectedConnection != null) {
                    log.info("User selected connection: {}", selectedConnection);
                    Map<String, String> payload = new HashMap<>();
                    if (isGoogle) {
                        payload.put("token", googleToken);
                    } else {
                        payload.put("username", username);
                        payload.put("password", password);
                    }
                    payload.put("app_version", app_version);
                    payload.put("connection_name", selectedConnection);
                    log.info("Retrying login with selected connection, payload: {}", gson.toJson(payload));
                    response = loginAndGetToken(payload);
                    log.info("Login response after connection selection: {}", gson.toJson(response));
                    handleLoginResponse(loginStage, response, username, password, errorLabel, loader, isGoogle, googleToken);
                } else {
                    log.info("Connection selection cancelled by user");
                    showErrorPopup("Connection selection cancelled");
                }
            } else {
                String errorMessage = response.getOrDefault("message", response.getOrDefault("data", "No connections available")).toString();
                log.warn("Login failed, no connections available: {}", errorMessage);
                showErrorPopup(errorMessage);
            }
        } else {
            String errorMessage = response.getOrDefault("message", response.getOrDefault("data", "Login failed")).toString();
            log.warn("Login failed: {}", errorMessage);
            showErrorPopup(errorMessage);
        }
    }

    private boolean validateSubscription(String projectId, String subscriptionId, GoogleCredentials credentials) {
        try {
            SubscriptionAdminSettings adminSettings =
                    SubscriptionAdminSettings.newBuilder()
                            .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                            .build();

            try (SubscriptionAdminClient adminClient = SubscriptionAdminClient.create(adminSettings)) {
                ProjectSubscriptionName subName = ProjectSubscriptionName.of(projectId, subscriptionId);
                Subscription sub = adminClient.getSubscription(subName);
                log.info("✅ Subscription exists: {}", sub.getName());
                return true;
            }
        } catch (Exception e) {
            log.error("❌ Subscription validation failed: {}", e.getMessage(), e);
            return false;
        }
    }

    private String refreshAccessToken(String currentAccessToken) {
        log.info("Attempting to refresh access token via exe/pubsubtoken");
        Map<String, String> payload = new HashMap<>();
        payload.put("username", lastUsername);
        payload.put("password", lastPassword);
        payload.put("app_version", app_version);
        if (!lastConnectionName.isEmpty()) {
            payload.put("connection_name", lastConnectionName);
        }

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost("https://api.pickmytrade.io/v2/refesh_pubsubtoken");
            post.setEntity(new StringEntity(gson.toJson(payload)));
            post.setHeader("Content-Type", "application/json");
            log.debug("Executing HTTP POST request to exe/pubsubtoken");

            try (CloseableHttpResponse response = client.execute(post)) {
                String responseText = EntityUtils.toString(response.getEntity());
                log.info("Received response from exe/pubsubtoken: {}", responseText);

                Map<String, Object> responseMap;
                try {
                    if (responseText.trim().startsWith("{")) {
                        responseMap = gson.fromJson(responseText, new TypeToken<Map<String, Object>>() {
                        }.getType());
                    } else {
                        log.warn("exe/pubsubtoken API returned a string instead of a JSON object: {}", responseText);
                        return null;
                    }
                } catch (Exception parseEx) {
                    log.error("Failed to parse exe/pubsubtoken response as JSON: {}. Raw response: {}", parseEx.getMessage(), responseText);
                    return null;
                }

                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == 200 && responseMap.containsKey("access_token")) {
                    String newAccessToken = (String) responseMap.get("access_token");
                    log.info("Successfully refreshed access token via exe/pubsubtoken");
                    return newAccessToken;
                } else {
                    String errorMessage = responseMap.getOrDefault("message", responseMap.getOrDefault("data", "Failed to refresh access token")).toString();
                    log.error("Failed to refresh access token: {}", errorMessage);
                    Platform.runLater(() -> showErrorPopup(errorMessage));
                    return null;
                }
            }
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Failed to connect to exe/pubsubtoken endpoint";
            log.error("exe/pubsubtoken request error: {}", errorMessage);
            Platform.runLater(() -> showErrorPopup(errorMessage));
            return null;
        }
    }

    private void handleAddIbSettings(Map<String, Object> tradeData) {
        log.info("Handling add_ib_settings request: {}", tradeData);

        try {
            // Extract contract details from tradeData
            String symbol = (String) tradeData.get("Symbol");
            String localSymbol = (String) tradeData.get("LocalSymbol");
            String securityType = (String) tradeData.get("SecurityType");
            String maturityDate = (String) tradeData.get("MaturityDate");
            String currency = (String) tradeData.get("Currency");
            String exchange = (String) tradeData.get("Exchange");
            String Ibsymbol = (String) tradeData.get("Ibsymbol");
            String randomId = heartbeat_connection_id;

            // Validate required fields
            if (symbol == null || securityType == null || currency == null || exchange == null) {
                log.error("Missing required fields in add_ib_settings data: {}", tradeData);
                sendIbSettingsToApi(randomId, localSymbol, securityType, "LMT", exchange, symbol, "", "", currency,
                        "", "", maturityDate, "", "", true, "Missing required fields");
                return;
            }

            // Create contract using TwsEngine
            Contract contract = twsEngine.createContract(
                    securityType,
                    Ibsymbol,
                    exchange,
                    currency,
                    null, // strike (not needed for FUT)
                    null, // right (not needed for FUT)
                    symbol, // baseSymbol (same as symbol for FUT)
                    maturityDate,
                    Ibsymbol // tradingClass (using localSymbol as initial value)
            );

            // Fetch contract details synchronously
            List<ContractDetails> contractDetailsList = twsEngine.reqContractDetailsSync(contract);
            if (contractDetailsList == null || contractDetailsList.isEmpty()) {
                log.error("No contract details found for contract: {}", contract.toString());
                sendIbSettingsToApi(randomId, localSymbol, securityType, "LMT", exchange, symbol, "", "", currency,
                        "", "", maturityDate, "", "", true, "No contract details found");
                return;
            }

            // Extract details from the first contract
            ContractDetails contractDetails = contractDetailsList.get(0);
            Contract detailedContract = contractDetails.contract();
            String lotSize = detailedContract.multiplier() != null ? detailedContract.multiplier() : "";
            String minTick = contractDetails.minTick() > 0 ? String.valueOf(contractDetails.minTick()) : "";
            String conId = String.valueOf(detailedContract.conid());
            localSymbol = detailedContract.localSymbol() != null ? detailedContract.localSymbol() : "";
            String tradingClass = detailedContract.tradingClass() != null ? detailedContract.tradingClass() : "";
            String marketRule = contractDetails.marketRuleIds() != null ? contractDetails.marketRuleIds() : "";


            sendIbSettingsToApi(
                    randomId,
                    localSymbol,
                    securityType,
                    "LMT",
                    exchange,
                    symbol,
                    conId,
                    Ibsymbol,
                    currency,
                    lotSize,
                    minTick,
                    maturityDate,
                    tradingClass,
                    marketRule,
                    false,
                    ""
            );

        } catch (Exception e) {
            log.error("Error processing add_ib_settings: {}", e.getMessage(), e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Failed to process add_ib_settings";
            sendIbSettingsToApi(
                    (String) tradeData.get("random_id"),
                    (String) tradeData.get("LocalSymbol"),
                    (String) tradeData.get("SecurityType"),
                    "LMT",
                    (String) tradeData.get("Exchange"),
                    (String) tradeData.get("Symbol"),
                    "",
                    "",
                    (String) tradeData.get("Currency"),
                    "",
                    "",
                    (String) tradeData.get("MaturityDate"),
                    "",
                    "",
                    true,
                    errorMsg
            );
        }
    }

    private void sendIbSettingsToApi(String randomId, String localSymbol, String instType, String orderType,
                                     String exchange, String symbol, String conId, String ibSymbol, String currency,
                                     String lotSize, String minTick, String maturityDate, String tradingClass,
                                     String marketRule, boolean error, String errorMsg) {
        log.info("Sending IB settings to API for random_id: {}", randomId);

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost("https://api.pickmytrade.io/v2/save_ib_setting_via_app");

            // Prepare payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("random_id", randomId != null ? randomId : "");
            payload.put("local_symbol", localSymbol != null ? localSymbol : "");
            payload.put("inst_type", instType != null ? instType : "");
            payload.put("order_type", orderType != null ? orderType : "LMT");
            payload.put("exchange", exchange != null ? exchange : "");
            payload.put("symbol", symbol != null ? symbol : "");
            payload.put("con_id", conId != null ? conId : "");
            payload.put("ib_symbol", ibSymbol != null ? ibSymbol : "");
            payload.put("currency", currency != null ? currency : "");
            payload.put("lot_size", lotSize != null ? lotSize : "");
            payload.put("min_tick", minTick != null ? minTick : "");
            payload.put("maturity_date", maturityDate != null ? maturityDate : "");
            payload.put("trading_class", tradingClass != null ? tradingClass : "");
            payload.put("market_rule", marketRule != null ? marketRule : "");
            payload.put("error", error);
            payload.put("error_msg", errorMsg != null ? errorMsg : "");

            String jsonPayload = gson.toJson(payload);
            log.info("IB settings payload: {}", jsonPayload);

            post.setEntity(new StringEntity(jsonPayload));
            post.setHeader("Content-Type", "application/json");

            // Add Authorization header
            Token tokenRecord = DatabaseConfig.getToken();
            String authToken = tokenRecord != null ? tokenRecord.getToken() : "";
            ConnectionEntity conn = DatabaseConfig.getConnectionEntity();
            String connName = conn != null ? conn.getConnectionName() : "";
            if (!authToken.isEmpty() && !connName.isEmpty()) {
                post.setHeader("Authorization", authToken + "_" + connName);
            }

            try (CloseableHttpResponse response = client.execute(post)) {
                String responseText = EntityUtils.toString(response.getEntity());
                log.info("IB settings API response: {}", responseText);

                if (response.getStatusLine().getStatusCode() == 200) {
                    log.info("Successfully sent IB settings for random_id: {}", randomId);
                } else {
                    log.error("Failed to send IB settings for random_id: {}. Response: {}", randomId, responseText);
                }
            }
        } catch (Exception e) {
            log.error("Error sending IB settings to API: {}", e.getMessage(), e);
        }
    }

    private void startPubSubSubscriber(String subscriptionId, String accessHexString , String secretId ) {
        if (!pubsubIsStarting.compareAndSet(false, true)) {
            log.warn("Subscriber start already in progress for subscription: {}, skipping", subscriptionId);
            return;
        }

        try {
            log.info("Attempting to start server connection subscriber for subscription: {}", subscriptionId);
            String projectId = "pickmytrader"; // Replace with your actual project ID

            // -------------------------------------------------------
            // 1. Decode hex string -> SecretAccessor credentials
            // -------------------------------------------------------
            // ---------------------------
            // 1️⃣ Decode Secret Accessor hex → JSON
            // ---------------------------
            pubsubAccessTokenRef.set(accessHexString);
            byte[] secretAccessorBytes = hexToBytes(accessHexString);
            String secretAccessorJson = new String(secretAccessorBytes, StandardCharsets.UTF_8);

            // ---------------------------
            // 2️⃣ Create Secret Manager client with Secret Accessor JSON
            // ---------------------------
            GoogleCredentials secretAccessorCreds = ServiceAccountCredentials
                    .fromStream(new ByteArrayInputStream(secretAccessorJson.getBytes(StandardCharsets.UTF_8)))
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");

            SecretManagerServiceSettings smSettings = SecretManagerServiceSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(secretAccessorCreds))
                    .build();

            try (SecretManagerServiceClient smClient = SecretManagerServiceClient.create(smSettings)) {

                SecretVersionName secretVersionName = SecretVersionName.of(projectId, secretId, "latest");
                AccessSecretVersionResponse response = smClient.accessSecretVersion(secretVersionName);

                // ---------------------------
                // 3️⃣ Fetch Pub/Sub service account JSON from Secret Manager
                // ---------------------------
                String pubsubJsonString = response.getPayload().getData().toStringUtf8();

                GoogleCredentials pubsubCredentials = ServiceAccountCredentials
                        .fromStream(new ByteArrayInputStream(pubsubJsonString.getBytes(StandardCharsets.UTF_8)))
                        .createScoped("https://www.googleapis.com/auth/pubsub");

                pubsubCredentialsRef.set(pubsubCredentials);
            }

            log.info("✅ Successfully created Pub/Sub subscriber credentials via Secret Manager");

            // Define message receiver

            MessageReceiver receiver = (PubsubMessage message, AckReplyConsumer consumer) -> {
                try {
                    String messageData = message.getData().toStringUtf8();
                    Map<String, String> attributes = message.getAttributesMap();
                    log.info("Received server connection message ID: {} with data: {} and attributes: {}",
                            message.getMessageId(), messageData, attributes);

                    pubsubLastMessageReceived.set(System.currentTimeMillis());
                    updatePubSubStatus("connected");
                    Map<String, Object> tradeData = gson.fromJson(messageData, new TypeToken<Map<String, Object>>() {}.getType());

                    // Handle heartbeat
                    if (tradeData.containsKey("heartbeat")) {
                        log.debug("Received heartbeat message ID: {}", message.getMessageId());
                        pubsubLastMessageReceived.set(System.currentTimeMillis());
                        executor.submit(this::sendHeartbeatToApiOnce);
                        consumer.ack();
                        log.debug("Acknowledged server connection heartbeat message ID: {}", message.getMessageId());
                        return;
                    }

                    // Handle add_ib_settings
                    if (tradeData.containsKey("add_ib_settings")) {
                        log.debug("Received add_ib_settings message ID: {}", message.getMessageId());
                        pubsubLastMessageReceived.set(System.currentTimeMillis());
                        Map<String, Object> new_trade_settings = (Map<String, Object>) tradeData.get("add_ib_settings");
                        executor.submit(() -> handleAddIbSettings(new_trade_settings));
                        log.debug("Acknowledged server connection message ID: {} (add_ib_settings)", message.getMessageId());
                        consumer.ack();
                        return;
                    }

                    // Handle send_logs
                    if (tradeData.containsKey("send_logs")) {
                        log.debug("Received send_logs message ID: {}", message.getMessageId());
                        pubsubLastMessageReceived.set(System.currentTimeMillis());
                        executor.submit(this::uploadLogs);
                        consumer.ack();
                        log.debug("Acknowledged server connection message ID: {} (send_logs)", message.getMessageId());
                        return;
                    }

                    // Handle random_alert_key
                    if (tradeData.containsKey("random_alert_key")) {
                        String randomAlertKey = (String) tradeData.get("random_alert_key");
                        log.info("Received random alert key: {}", randomAlertKey);

                        // Check server_data_sent timestamp
                        if (tradeData.containsKey("server_data_sent")) {
                            String serverDataSent = (String) tradeData.get("server_data_sent");
                            try {
                                // Parse ISO 8601 string (UTC)
                                Instant instant = Instant.parse(serverDataSent);
                                long sentTimeMillis = instant.toEpochMilli();
                                pubsubLastMessageReceived.set(System.currentTimeMillis());

                                // Case 1: Trade is older than appStartTime
                                if (sentTimeMillis < appStartTime) {
                                    log.info("Ignoring trade with random_alert_key={} as server_data_sent ({}) is older than appStartTime ({})",
                                            randomAlertKey, serverDataSent,
                                            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'").format(new Date(appStartTime)));
                                    executor.submit(() -> sendotradeconfirmationToApiOnce(randomAlertKey, "Trade ignored: Order Sent Time is older than application start time"));
                                    consumer.ack();
                                    return;
                                }

                                // Case 2: Manual trade close occurred and trade is between appStartTime and manualTradeCloseTime
                                if (manualTradeCloseTime != appStartTime && sentTimeMillis < manualTradeCloseTime && sentTimeMillis > appStartTime) {
                                    log.info("Ignoring trade with random_alert_key={} as server_data_sent ({}) is between appStartTime ({}) and manualTradeCloseTime ({})",
                                            randomAlertKey, serverDataSent,
                                            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'").format(new Date(appStartTime)),
                                            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'").format(new Date(manualTradeCloseTime)));
                                    executor.submit(() -> sendotradeconfirmationToApiOnce(randomAlertKey, "Trade ignored: manual trade close triggered"));
                                    consumer.ack();
                                    return;
                                }

                                // Case 3: Process trade normally
                                log.info("Processing trade with random_alert_key={} and server_data_sent={}", randomAlertKey, serverDataSent);
                                executor.submit(() -> sendotradeconfirmationToApiOnce(randomAlertKey, "Trade processed successfully"));

                            } catch (Exception e) {
                                log.error("Failed to parse server_data_sent timestamp: {}", serverDataSent, e);
                                consumer.nack();
                                return;
                            }
                        }

                        // Handle trade data
                        String user = attributes.get("user");
                        tradeData.put("user", user);

                        Platform.runLater(() -> {
                            consoleLog.appendText(String.format("Alert received: %s for user: %s\n",
                                    tradeData.get("alert"), user));
                        });

                        try (CloseableHttpClient client = HttpClients.createDefault()) {
                            HttpPost post = new HttpPost("http://localhost:" + trade_server_port + "/place-trade");
                            String payload = gson.toJson(tradeData);
                            log.debug("Sending trade request to HTTP server: {}", payload);
                            post.setEntity(new StringEntity(payload));
                            post.setHeader("Content-Type", "application/json");
                            try (CloseableHttpResponse apiResponse = client.execute(post)) {
                                String responseText = EntityUtils.toString(apiResponse.getEntity());
                                log.info("HTTP server response: {}", responseText);
                                Map<String, Object> apiResult = gson.fromJson(responseText, new TypeToken<Map<String, Object>>() {}.getType());
                                if (!(boolean) apiResult.get("success")) {
                                    log.error("Trade placement failed: {}", apiResult.get("message"));
                                }
                            }
                        } catch (Exception e) {
                            log.error("Error calling HTTP trade server: {}", e.getMessage(), e);
                        }

                        consumer.ack();
                        log.debug("Acknowledged server connection message ID: {}", message.getMessageId());
                        return;
                    }

                    // Handle restart_order_status
                    if (tradeData.containsKey("restart_order_status")) {
                        log.info("Received request to restart order status processor");
                        pubsubLastMessageReceived.set(System.currentTimeMillis());
                        restartOrderStatusProcessor();
                        log.debug("Acknowledged server connection message ID: {} (restart order status)", message.getMessageId());
                        consumer.ack();
                        return;
                    }

                    consumer.ack();
                    log.debug("Acknowledged server connection message ID: {} (default handler)", message.getMessageId());

                } catch (Exception e) {
                    log.error("Error processing server connection message ID {}: {}", message.getMessageId(), e.getMessage(), e);
                    consumer.nack();
                }
            };

            int maxRetries = 5;
            int attempt = 0;
            long retryDelayMs = 5000; // 5 seconds
            ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(projectId, subscriptionId);

            while (attempt < maxRetries) {
                try {
                    log.debug("Attempting to create server connection subscriber (attempt {}/{})", attempt + 1, maxRetries);
                    Subscriber newSubscriber = Subscriber.newBuilder(subscriptionName, receiver)
                            .setCredentialsProvider(FixedCredentialsProvider.create(pubsubCredentialsRef.get()))
                            .build();
                    pubsubSubscriberRef.set(newSubscriber);


                    newSubscriber.startAsync().awaitRunning(10, TimeUnit.SECONDS);
                    log.info("✅ Subscriber started for subscription: {}", subscriptionId);
                    executor.submit(this::sendHeartbeatToApiOnce);
                    updatePubSubStatus("connected");
                    return; // Exit on success
                } catch (Exception e) {
                    attempt++;
                    log.error("Failed to start server connection subscriber (attempt {}/{}): {}", attempt, maxRetries, e.getMessage(), e);
                    if (attempt >= maxRetries) {
                        log.warn("Max retries reached. Marking as disconnected");
                        updatePubSubStatus("disconnected");
                        pubsubLastMessageReceived.set(System.currentTimeMillis() - 61_000);
                        return;
                    }
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

        } catch (Exception e) {
            log.error("Fatal error starting subscriber: {}", e.getMessage(), e);
            updatePubSubStatus("disconnected");
        } finally {
            pubsubIsStarting.set(false);
        }
    }

    // Utility: hex string → byte[]
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        if (len % 2 != 0) throw new IllegalArgumentException("Invalid hex string length");
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    private boolean isNetworkAvailable() {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost("https://api.pickmytrade.io/wbsk/exe_heartbeat");
            post.setConfig(RequestConfig.custom().setConnectTimeout(2000).setSocketTimeout(2000).build());

            // Construct user_key using heartbeat_connection_id and heartbeat_auth_token
            String userKey = (heartbeat_connection_id != null && heartbeat_auth_token != null)
                    ? heartbeat_connection_id + "_" + heartbeat_auth_token
                    : "demo"; // Fallback to "demo" if either is null
            Map<String, String> payload = new HashMap<>();
            payload.put("user_key", userKey);
            String jsonPayload = gson.toJson(payload);
            post.setEntity(new StringEntity(jsonPayload));
            post.setHeader("Content-Type", "application/json");

            log.debug("Checking network availability with POST to {}, user_key: {}", post.getURI(), userKey);
            try (CloseableHttpResponse response = client.execute(post)) {
                int statusCode = response.getStatusLine().getStatusCode();
                log.debug("Network check response status: {}", statusCode);
                return statusCode >= 200 && statusCode < 300;
            }
        } catch (IOException e) {
            log.warn("Network availability check failed: {}", e.getMessage());
            return false;
        }
    }

    private void monitorPubSubConnection(String subscriptionId) {
        log.info("Starting Pub/Sub connection monitor for subscription: {}", subscriptionId);

        Runnable monitorTask = () -> {
            try {
                // Step 1: Check network availability

                boolean isNetworkAvailable = isNetworkAvailable();


                // Step 2: Detect network state change
                if (!isNetworkAvailable && lastNetworkState.get()) {
                    log.warn("Network dropped, marking as unavailable");
                    lastNetworkState.set(false);
                    networkDroppedTime.set(Instant.now().toEpochMilli());
                    updatePubSubStatus("disconnected");
                    return;
                } else if (isNetworkAvailable && !lastNetworkState.get()) {
                    log.info("Network restored, recording restoration time");
                    lastNetworkState.set(true);
                    networkRestoredTime.set(System.currentTimeMillis());

                }

                // Step 3: Skip all Pub/Sub checks if network is unavailable
                if (!isNetworkAvailable) {
                    log.warn("Network is unavailable, skipping Pub/Sub connection check");
                    updatePubSubStatus("disconnected");
                    return;
                }


                log.info("Networkrestoretime", networkRestoredTime.get());
                log.info("current_time", System.currentTimeMillis());
                // Step 4: Check if network was recently restored and wait 15-20 seconds
                long timeSinceNetworkRestored = System.currentTimeMillis() - networkRestoredTime.get();
                Subscriber currentSubscriber = pubsubSubscriberRef.get();
                boolean isSubscriberRunning = currentSubscriber != null && currentSubscriber.isRunning();
                if (currentSubscriber != null) {
                    boolean x = currentSubscriber.isRunning();
                    boolean y = currentSubscriber.state() == ApiService.State.RUNNING;
                    String z = String.valueOf(currentSubscriber.state());
                    log.info("Current subscriber state: {}, {}", x , y);
                    log.info("Current subscriber state: {}", z);
                } else {
                    log.warn("Current subscriber is null");
                }
                log.info("timenetworkrestored: {}", timeSinceNetworkRestored);
                if (timeSinceNetworkRestored > 0 && timeSinceNetworkRestored < 32_000) {
                    log.info("Network restored {} ms ago, waiting up to 20 seconds for automatic Pub/Sub reconnection", timeSinceNetworkRestored);
                    log.info("networkdropget", networkDroppedTime.get());
                    if (((Instant.now().toEpochMilli() - networkDroppedTime.get()) > 600_000) && networkDroppedTime.get()!=0) {
                        networkDroppedTime.set(0);
                        manualTradeCloseTime = Instant.now().toEpochMilli();
                        log.warn("Network was down for more than 10 minutes, manual trade close time set to {}", manualTradeCloseTime);
                        Platform.runLater(() -> showErrorPopup("Network was dropped for more than 10 Minutes. All trades received during this period have been ignored."));
                    }

                    // Use a for loop to check subscriber state every second for up to 20 seconds
                    for (int i = 0; i < 20 && timeSinceNetworkRestored < 32_000; i++) {
                        isSubscriberRunning = currentSubscriber != null && currentSubscriber.state() == ApiService.State.RUNNING;
                        if (isSubscriberRunning && (System.currentTimeMillis() - pubsubLastMessageReceived.get() <= 60_000)) {
                            log.debug("Subscriber is running and receiving messages after {} ms", timeSinceNetworkRestored);

                            updatePubSubStatus("connected");
                            return;
                        }
                        log.debug("Waiting for automatic reconnection, {} ms elapsed", System.currentTimeMillis() - networkRestoredTime.get());
                        try {
                            Thread.sleep(1000); // Wait 1 second per iteration
                        } catch (InterruptedException e) {
                            log.warn("Interrupted during wait period: {}", e.getMessage());
                            Thread.currentThread().interrupt();
                            return;
                        }
                        timeSinceNetworkRestored = System.currentTimeMillis() - networkRestoredTime.get();
                    }

                    // After the loop, check if reconnection succeeded
                    isSubscriberRunning = currentSubscriber != null && currentSubscriber.state() == ApiService.State.RUNNING;
                    if (isSubscriberRunning && (System.currentTimeMillis() - pubsubLastMessageReceived.get() <= 60_000)) {
                        log.debug("Subscriber reconnected successfully after wait period");
                        updatePubSubStatus("connected");
                        return;
                    }
                    log.info("No automatic reconnection after 20 seconds, proceeding to manual check");
                }

                // Step 5: Check Pub/Sub connection status only if network is available
                if (!isSubscriberRunning || (System.currentTimeMillis() - pubsubLastMessageReceived.get() > 60_000)) {
                    pubsubLastMessageReceived.set(System.currentTimeMillis());
                    log.warn("Pub/Sub subscriber is not running or no messages received for 60s, attempting to restart");
                    updatePubSubStatus("connecting");

                    // Step 6: Ensure valid token
                    String token = pubsubAccessTokenRef.get();
                    if (token == null) {
                        log.info("Token is null, attempting to refresh");
                        token = refreshAccessToken(null);
                        if (token != null) {
                            pubsubAccessTokenRef.set(token);
                        } else {
                            log.error("Failed to refresh token, will retry on next monitor cycle");
                            updatePubSubStatus("disconnected");
                            return;
                        }
                    }

                    // Step 7: Stop existing subscriber if it exists
                    if (currentSubscriber != null) {
                        try {
                            log.info("Stopping current Pub/Sub subscriber");
                            currentSubscriber.stopAsync().awaitTerminated(5, TimeUnit.SECONDS);
                            pubsubSubscriberRef.set(null);
                        } catch (Exception e) {
                            log.warn("Failed to stop subscriber cleanly: {}", e.getMessage());
                        }
                    }

                    // Step 8: Attempt to start a new subscriber with a 20-second timeout
                    CompletableFuture<Boolean> connectionFuture = new CompletableFuture<>();
                    String finalToken = token;
                    executor.submit(() -> {
                        try {

                            startPubSubSubscriber(subscriptionId, finalToken, heartbeat_snew_token_id);
                            Subscriber newSubscriber = pubsubSubscriberRef.get();
                            if (newSubscriber != null && newSubscriber.isRunning()) {
                                connectionFuture.complete(true);
                            } else {
                                connectionFuture.complete(false);
                            }
                        } catch (Exception e) {
                            log.error("Failed to start Pub/Sub subscriber: {}", e.getMessage());
                            connectionFuture.complete(false);
                        }
                    });

                    // Wait for 20 seconds to check if the subscriber connects
                    try {
                        boolean connected = connectionFuture.get(30, TimeUnit.SECONDS);
                        if (connected) {
                            log.info("Pub/Sub subscriber successfully connected");
                            updatePubSubStatus("connected");
                        } else {
                            log.warn("Pub/Sub subscriber failed to connect within 20 seconds");
                            updatePubSubStatus("disconnected");
                            Platform.runLater(() -> showErrorPopup("Pub/Sub connection failed after 30 seconds. Retrying..."));
                        }
                    } catch (TimeoutException e) {
                        log.warn("Pub/Sub connection attempt timed out after 20 seconds");
                        updatePubSubStatus("disconnected");
                        Platform.runLater(() -> showErrorPopup("Pub/Sub connection timed out. Retrying..."));
                    } catch (Exception e) {
                        log.error("Error during Pub/Sub connection attempt: {}", e.getMessage());
                        updatePubSubStatus("disconnected");
                    }
                } else {
                    log.debug("Pub/Sub subscriber is running and receiving messages");
                    updatePubSubStatus("connected");
                }
            } catch (Exception e) {
                log.error("Monitor error: {}", e.getMessage(), e);
                updatePubSubStatus("error");
            }
        };

        // Schedule monitor task to run every 5 seconds for faster response
        pubsubScheduler.scheduleAtFixedRate(monitorTask, 10, 10, TimeUnit.SECONDS);
    }


    private void updatePubSubStatus(String status) {
        log.debug("Updating server connection status to: {}", status);
        Platform.runLater(() -> {
            if (status.startsWith("retry")) {
                websocketLight.setFill(Color.ORANGE);
                websocketStatusLabel.setText("PickMyTrade server connection Status: \nRetrying (" + status + ")");
            } else {
                switch (status) {
                    case "connected":
                        websocketLight.setFill(Color.GREEN);
                        websocketStatusLabel.setText("PickMyTrade server connection Status: \nConnected");
                        break;
                    case "connecting":
                        websocketLight.setFill(Color.ORANGE);
                        websocketStatusLabel.setText("PickMyTrade server connection Status: \nConnecting");
                        break;
                    case "disconnected":
                    case "error":
                        websocketLight.setFill(Color.RED);
                        websocketStatusLabel.setText("PickMyTrade server connection Status: \n" + (status.equals("error") ? "Error" : "Disconnected"));
                        break;
                }
            }
        });
    }

    private void restartOrderStatusProcessor() {
        log.info("Restarting order status processor...");
        TwsEngine.orderStatusProcessingStarted.set(false);
        TwsEngine.orderStatusExecutor.shutdownNow();
        try {
            if (!TwsEngine.orderStatusExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Order status executor did not terminate gracefully");
            }
        } catch (InterruptedException e) {
            log.error("Interrupted during order status executor shutdown", e);
        }
        orderExecutor.shutdownNow();
        try {
            if (!orderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Order executor did not terminate gracefully");
            }
        } catch (InterruptedException e) {
            log.error("Interrupted during order executor shutdown", e);
        }

        // Optionally clear the queue if you want to discard pending items
        // TwsEngine.orderStatusQueue.clear();
        TwsEngine.orderStatusExecutor = Executors.newSingleThreadExecutor();
        twsEngine.startOrderStatusProcessing();
        log.info("Order status processor restarted successfully");


        orderExecutor = Executors.newSingleThreadExecutor();
        orderExecutor.submit(this::scheduleOrderSender);
        log.info("Order send processor restarted successfully");
    }

    private void showErrorPopup(String errorMessage) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText(errorMessage);
            alert.initModality(Modality.APPLICATION_MODAL);
            alert.showAndWait();
        });
    }

    private Map<String, Object> loginAndGetToken(Map<String, String> payload) {
        log.info("Sending login request to API with payload: {}", gson.toJson(payload));
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost("https://api.pickmytrade.io/v2/exe_Login_1");
            post.setEntity(new StringEntity(gson.toJson(payload)));
            post.setHeader("Content-Type", "application/json");
            log.debug("Executing HTTP POST request to login endpoint");

            try (CloseableHttpResponse response = client.execute(post)) {
                String responseText = EntityUtils.toString(response.getEntity());
                log.info("Received login response: {}", responseText);

                Map<String, Object> responseMap;
                try {
                    if (responseText.trim().startsWith("{")) {
                        responseMap = gson.fromJson(responseText, new TypeToken<Map<String, Object>>() {
                        }.getType());
                    } else {
                        log.warn("API returned a string instead of a JSON object: {}", responseText);
                        responseMap = new HashMap<>();
                        responseMap.put("success", false);
                        responseMap.put("message", responseText);
                        return responseMap;
                    }
                } catch (Exception parseEx) {
                    log.error("Failed to parse response as JSON: {}. Raw response: {}", parseEx.getMessage(), responseText);
                    responseMap = new HashMap<>();
                    responseMap.put("success", false);
                    responseMap.put("message", "Parsing error: " + parseEx.getMessage() + ". Raw response: " + responseText);
                    return responseMap;
                }

                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == 200 && responseMap.containsKey("error") && !(boolean) responseMap.get("error")) {
                    if (responseMap.containsKey("id")) {
                        log.info("Login request successful, saving token");
                        String token = (String) responseMap.get("id");
                        DatabaseConfig.saveOrUpdateToken(token);

                        if (responseMap.containsKey("connection_name")) {
                            log.debug("Saving connection name: {}", responseMap.get("connection_name"));
                            DatabaseConfig.saveConnection((String) responseMap.get("connection_name"));
                        }
                        responseMap.put("success", true);
                        responseMap.put("message", "");
                    } else if (responseMap.containsKey("connections")) {
                        log.info("Login response contains connections, returning for selection");
                        return responseMap;
                    } else {
                        String error_string = (String) responseMap.get("data");
                        log.warn("Login response missing expected fields (id or connections): {}", error_string);
                        responseMap.put("success", false);
                        responseMap.put("message", error_string);
                    }
                } else {
                    log.warn("Login request failed: {}", responseMap.getOrDefault("data", responseMap.getOrDefault("message", "Login failed")));
                    responseMap.put("success", false);
                    responseMap.put("message", responseMap.getOrDefault("data", responseMap.getOrDefault("message", "Login failed")).toString());
                }
                return responseMap;
            }
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Failed to connect to the server";
            log.error("Login request error: {}", errorMessage);
            return Map.of("success", false, "message", errorMessage);
        }
    }

    private String showConnectionPopup(List<String> connections) {
        log.info("Showing connection selection popup with {} connections", connections.size());
        Stage popup = new Stage();
        popup.initModality(Modality.APPLICATION_MODAL);
        popup.setTitle("Select Connection");
        VBox layout = new VBox(20);
        layout.setPadding(new Insets(20));
        Label label = new Label("Please select a connection:");
        label.setFont(Font.font("Arial", 14));
        ComboBox<String> dropdown = new ComboBox<>();
        dropdown.getItems().add("Select a connection");
        dropdown.getItems().addAll(connections);
        dropdown.setValue("Select a connection");
        dropdown.setStyle("-fx-font-size: 16; -fx-pref-height: 40; -fx-border-radius: 20");
        Button okButton = new Button("OK");
        okButton.setStyle("-fx-background-color: #dc143c; -fx-text-fill: white; -fx-border-radius: 20; -fx-pref-height: 40");
        okButton.setOnAction(e -> {
            log.debug("User confirmed connection selection");
            popup.close();
        });
        layout.getChildren().addAll(label, dropdown, okButton);
        layout.setAlignment(Pos.CENTER);
        Scene scene = new Scene(layout, 400, 200);
        popup.setScene(scene);
        popup.showAndWait();
        String selected = dropdown.getValue();
        log.info("Selected connection: {}", selected.equals("Select a connection") ? "None" : selected);
        return "Select a connection".equals(selected) ? null : selected;
    }

    private Scene createConnectionStatusScene(Stage stage) {
        log.info("Creating connection status scene");
        VBox layout = new VBox(20);
        layout.setPadding(new Insets(20));
        layout.setStyle("-fx-background-color: white;");

        Label title = new Label("Connection Status");
        title.setFont(Font.font("Arial", 16));
        title.setTextAlignment(TextAlignment.CENTER);

        // Add current connection name and TWS port labels
        Label connectionNameLabel = new Label("Current Connection Name: " + lastConnectionName);
        connectionNameLabel.setFont(Font.font("Arial", 14));
        connectionNameLabel.setTextAlignment(TextAlignment.CENTER);

        Label twsPortLabel = new Label("TWS Port: " + tws_trade_port);
        twsPortLabel.setFont(Font.font("Arial", 14));
        twsPortLabel.setTextAlignment(TextAlignment.CENTER);

        HBox connectionsLayout = new HBox(20);
        VBox twsBox = new VBox(10);
        twsBox.setAlignment(Pos.CENTER);
        twsBox.setStyle("-fx-border-width: 1; -fx-border-color: black;");
        twsStatusLabel = new Label("TWS Connection Status: \nConnecting...");
        twsStatusLabel.setFont(Font.font("Arial", 12));
        twsLight = new Circle(10, Color.ORANGE);
        twsBox.getChildren().addAll(twsStatusLabel, twsLight);

        VBox websocketBox = new VBox(10);
        websocketBox.setAlignment(Pos.CENTER);
        websocketBox.setStyle("-fx-border-width: 1; -fx-border-color: black;");
        websocketStatusLabel = new Label("PickMyTrade server connection Status: \nConnecting...");
        websocketStatusLabel.setFont(Font.font("Arial", 12));
        websocketLight = new Circle(10, Color.ORANGE);
        websocketBox.getChildren().addAll(websocketStatusLabel, websocketLight);

        connectionsLayout.getChildren().addAll(twsBox, websocketBox);
        connectionsLayout.setAlignment(Pos.CENTER);

        consoleLog = new TextArea();
        consoleLog.setEditable(false);
        consoleLog.setFont(Font.font("Courier New", 10));

        HBox bottomLayout = new HBox(10);
        Button openPortalButton = new Button("Open pickmytrade web portal");
        openPortalButton.setStyle("-fx-background-color: blue; -fx-text-fill: white; -fx-padding: 8 15");
        openPortalButton.setOnAction(e -> {
            log.info("Opening PickMyTrade web portal");
            getHostServices().showDocument("https://app.pickmytrade.io");
        });

        Button sendLogsButton = new Button("Send Logs");
        sendLogsButton.setStyle("-fx-background-color: #17a2b8; -fx-text-fill: white; -fx-padding: 8 15");

        ProgressIndicator logLoader = new ProgressIndicator();
        logLoader.setVisible(false);
        HBox logBox = new HBox(5, sendLogsButton, logLoader);

        // Manual Trade Close button
        Button manualTradeCloseButton = new Button("Manual Trade Close");
        manualTradeCloseButton.setStyle("-fx-background-color: #dc143c; -fx-text-fill: white; -fx-padding: 8 15");
        manualTradeCloseButton.setOnAction(e -> {
            manualTradeCloseTime = Instant.now().toEpochMilli();
            log.info("Manual trade close triggered, updated manualTradeCloseTime to: {}",
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'").format(new java.util.Date(manualTradeCloseTime)));
            Platform.runLater(() -> {
                consoleLog.appendText(String.format("Manual trade close triggered at: %s\n",
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'").format(new java.util.Date(manualTradeCloseTime))));
                showErrorPopup("Manual trade close triggered. All trades should be closed manually.");
            });
        });

        Label versionLabel = new Label("App Version: " + app_version);
        versionLabel.setFont(Font.font("Arial", 10));
        versionLabel.setTextFill(Color.GRAY);
        bottomLayout.getChildren().addAll(openPortalButton, logBox, manualTradeCloseButton, new Region(), versionLabel);
        HBox.setHgrow(bottomLayout.getChildren().get(3), Priority.ALWAYS);

        sendLogsButton.setOnAction(e -> {
            logLoader.setVisible(true);
            sendLogsButton.setDisable(true);
            executor.submit(() -> {
                String result = uploadLogs();
                Platform.runLater(() -> {
                    logLoader.setVisible(false);
                    sendLogsButton.setDisable(false);
                    Alert.AlertType alertType = result.contains("successfully") ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR;
                    Alert alert = new Alert(alertType);
                    alert.setTitle(alertType == Alert.AlertType.INFORMATION ? "Success" : "Error");
                    alert.setHeaderText(null);
                    alert.setContentText(result);
                    alert.showAndWait();
                });
            });
        });

        layout.getChildren().addAll(title, connectionNameLabel, twsPortLabel, connectionsLayout, new Label("IB Logs:"), consoleLog, bottomLayout);

        // Configure taskbar support after stage is shown
        stage.setOnShown(e -> configureTaskbarSupport(stage));

        return new Scene(layout, 900, 600);
    }

    private void continuouslyCheckTwsConnection(Stage stage) {
        log.info("Starting continuous TWS connection check");
        Platform.runLater(() -> consoleLog.clear());
        boolean placetrade_new = false;
        twsEngine.twsConnect(tws_trade_port);

        while (true) {
            try {
                Thread.sleep(2000);
                if (twsEngine.isConnected()) {
                    log.debug("TWS is connected");
                    updateTwsStatus("connected");

                    if (!placetrade_new) {
                        log.info("Starting place order service");
                        placetrade_new = true;
                        placeOrderService.setTwsEngine(twsEngine);
                        placeRemainingTpSlOrderWrapper(appStartTime);
                    }
                    retrycheck_count = 1;
                } else {
                    log.info("TWS disconnected or not yet connected. Attempting to connect...");
                    updateTwsStatus("disconnected");
                    placetrade_new = false;
                    twsEngine.disconnect();
                    TwsEngine.orderStatusProcessingStarted.set(false);
                    twsEngine = new TwsEngine();
                    placeOrderService.setTwsEngine(twsEngine);
                    connectToTwsWithRetries(stage);
                }

                Thread.sleep(2000);
            } catch (InterruptedException e) {
                log.info("TWS connection check interrupted");
                break;
            } catch (Exception e) {
                log.error("Error checking TWS connection: {}", e.getMessage());
                updateTwsStatus("error");
            }
        }
        log.info("TWS connection check loop stopped");
    }

    private void connectToTwsWithRetries(Stage stage) {
        int maxRetries = Integer.MAX_VALUE;
        int delaySeconds = 10;
        int attempt = 1;

        log.info("Attempting to connect to TWS with indefinite retries");
        updateTwsStatus("connecting");

        while (true) {
            try {
                log.info("Attempt {} to connect to TWS...", attempt);
                twsEngine.twsConnect(tws_trade_port);
                Thread.sleep(2000);
                if (twsEngine.isConnected()) {
                    log.info("TWS connected successfully on attempt {}", attempt);
                    placeOrderService.setTwsEngine(twsEngine);
                    updateTwsStatus("connected");
                    return;
                } else {
                    log.warn("TWS connection failed on attempt {}", attempt);
                    updateTwsStatus("retry " + attempt);
                    twsEngine.disconnect();
                    TwsEngine.orderStatusProcessingStarted.set(false);
                    twsEngine = new TwsEngine();
                    placeOrderService.setTwsEngine(twsEngine);
                }
            } catch (Exception e) {
                log.error("Error during TWS connection attempt {}: {}", attempt, e.getMessage());
                updateTwsStatus("retry " + attempt);
            }

            attempt++;
            try {
                log.debug("Waiting {} seconds before next retry", delaySeconds);
                Thread.sleep(delaySeconds * 1000);
            } catch (InterruptedException e) {
                log.info("TWS connection retry sleep interrupted");
                updateTwsStatus("disconnected");
                return;
            }
        }
    }

    private void updateTwsStatus(String status) {
        log.debug("Updating TWS status to: {}", status);
        Platform.runLater(() -> {
            if (status.startsWith("retry")) {
                twsLight.setFill(Color.ORANGE);
                twsStatusLabel.setText("TWS Connection Status: \nRetrying (" + status + ")");
            } else {
                switch (status) {
                    case "connected":
                        twsLight.setFill(Color.GREEN);
                        twsStatusLabel.setText("TWS Connection Status: \nConnected");
                        Platform.runLater(() -> consoleLog.clear());
                        break;
                    case "connecting":
                        twsLight.setFill(Color.ORANGE);
                        twsStatusLabel.setText("TWS Connection Status: \nConnecting");
                        break;
                    case "disconnected":
                    case "error":
                        twsLight.setFill(Color.RED);
                        twsStatusLabel.setText("TWS Connection Status: \n" + (status.equals("error") ? "Error" : "Disconnected"));
                        break;
                }
            }
        });
    }

    private void sendOrdersToApiOnce() {
        try {
            Thread.sleep(2000);
            List<OrderClient> orders = DatabaseConfig.getOrderClientsNotSentToServer();
            Token tokenRecord = DatabaseConfig.getToken();
            String authToken = tokenRecord != null ? tokenRecord.getToken() : "";
            ConnectionEntity conn = DatabaseConfig.getConnectionEntity();
            String connName = conn != null ? conn.getConnectionName() : "";

            for (OrderClient order : orders) {
                log.debug("Sending order from Main App: {}", order);
                Map<String, Object> data = placeOrderService.orderToDict(order);

                try (CloseableHttpClient client = HttpClients.createDefault()) {
                    HttpPost post = new HttpPost("https://api.pickmytrade.io/v2/exe_save_orders");
                    String payload = gson.toJson(data);
                    log.info("payload to send to API: {}", payload);
                    post.setEntity(new StringEntity(payload));
                    post.setHeader("Authorization", authToken + "_" + connName);
                    post.setHeader("Content-Type", "application/json");

                    try (CloseableHttpResponse response = client.execute(post)) {
                        String responseText = EntityUtils.toString(response.getEntity());
                        log.info("Order API response: {}", responseText);

                        Map<String, Object> updateFields = new HashMap<>();
                        if (response.getStatusLine().getStatusCode() == 200) {
                            updateFields.put("sent_to_server", OrderClient.SentToServerStatus.Pushed.toString());
                        } else {
                            updateFields.put("sent_to_server", OrderClient.SentToServerStatus.Failed.toString());
                        }
                        DatabaseConfig.updateOrderClient(order, updateFields);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error sending orders to API: {}", e.getMessage());
            log.error("Error sending orders: {}", e.getMessage());
        }
    }


    private void sendHeartbeatToApiOnce() {

//        heartbeat_auth_token = (String) response.get("connection_name");
//        heartbeat_connection_id = (String) response.get("id");

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String userKey = heartbeat_connection_id + "_" + heartbeat_auth_token;

            HttpPost post = new HttpPost("https://api.pickmytrade.io/wbsk/exe_heartbeat");

            Map<String, String> payloadMap = new HashMap<>();
            payloadMap.put("user_key", userKey);
            String payload = gson.toJson(payloadMap);

            log.info("Heartbeat payload to send to API: {}", payload);

            post.setEntity(new StringEntity(payload));
            post.setHeader("Content-Type", "application/json");

            try (CloseableHttpResponse response = client.execute(post)) {
                String responseText = EntityUtils.toString(response.getEntity());
                log.info("Heartbeat API response: {}", responseText);

                if (response.getStatusLine().getStatusCode() == 200) {
                    log.info("Heartbeat sent successfully for user_key={}", userKey);
                } else {
                    log.warn("Heartbeat failed with status: {}", response.getStatusLine().getStatusCode());
                }
            }
        } catch (Exception e) {
            log.error("Error sending heartbeat: {}", e.getMessage(), e);
        }
    }

    private void sendClosingHeartbeatToApiOnce() {


//        heartbeat_auth_token = (String) response.get("connection_name");
//        heartbeat_connection_id = (String) response.get("id");

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String userKey = heartbeat_connection_id + "_" + heartbeat_auth_token;

            HttpPost post = new HttpPost("https://api.pickmytrade.io/wbsk/exe_closing_heartbeat");

            Map<String, String> payloadMap = new HashMap<>();
            payloadMap.put("user_key", userKey);
            String payload = gson.toJson(payloadMap);

            log.info("Heartbeat payload to send to API: {}", payload);

            post.setEntity(new StringEntity(payload));
            post.setHeader("Content-Type", "application/json");

            try (CloseableHttpResponse response = client.execute(post)) {
                String responseText = EntityUtils.toString(response.getEntity());
                log.info("Heartbeat API response: {}", responseText);

                if (response.getStatusLine().getStatusCode() == 200) {
                    log.info("Heartbeat sent successfully for user_key={}", userKey);
                } else {
                    log.warn("Heartbeat failed with status: {}", response.getStatusLine().getStatusCode());
                }
            }
        } catch (Exception e) {
            log.error("Error sending heartbeat: {}", e.getMessage(), e);
        }
    }

    private void sendotradeconfirmationToApiOnce(String tradeKey, String message) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String userKey = heartbeat_connection_id + "_" + heartbeat_auth_token;

            HttpPost post = new HttpPost("https://api.pickmytrade.io/v2/exe_trade_ack");

            Map<String, String> payloadMap = new HashMap<>();
            payloadMap.put("orders_random_id", tradeKey);
            payloadMap.put("message", message); // Add message to payload
            String payload = gson.toJson(payloadMap);

            log.info("Trade confirmation payload to send to API: {}", payload);

            post.setEntity(new StringEntity(payload));
            post.setHeader("Authorization", userKey);
            post.setHeader("Content-Type", "application/json");

            log.info("Request headers:");
            for (Header header : post.getAllHeaders()) {
                log.info("  {}: {}", header.getName(), header.getValue());
            }

            try (CloseableHttpResponse response = client.execute(post)) {
                String responseText = EntityUtils.toString(response.getEntity());
                log.info("Trade confirmation API response: {}", responseText);

                if (response.getStatusLine().getStatusCode() == 200) {
                    log.info("Trade confirmation sent successfully for orders_random_id={} with message: {}", tradeKey, message);
                } else {
                    log.warn("Trade confirmation failed with status: {}", response.getStatusLine().getStatusCode());
                }
            }
        } catch (Exception e) {
            log.error("Error sending trade confirmation: {}", e.getMessage(), e);
        }
    }

    private void scheduleOrderSender() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                sendOrdersToApiOnce();
            } catch (Exception e) {
                log.error("Scheduled task error: {}", e.getMessage());
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    private void monitorHeartbeatAck(Stage window) {
        log.info("Starting heartbeat acknowledgment monitor");
        scheduler.scheduleAtFixedRate(() -> {
            log.info("Checking heartbeat acknowledgment status");
            try {
                long now = System.currentTimeMillis();
                if (now - lastHeartbeatAck.get() > 62_000) {
                    log.warn("No heartbeat acknowledgment received for over 62 seconds. Reconnecting...");
                    cleanupAndRestartWebsocket(window);
                }
            } catch (Exception e) {
                log.warn("Error in heartbeat acknowledgment monitor: {}", e.getMessage());
                log.error("Error in heartbeat acknowledgment monitor: {}", e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void handleWebSocketMessageAsync(String message, String token, String connectionName) {
        executor.submit(() -> {
            log.info("Asynchronously processing WebSocket message: {}", message);
            try {
                Map<String, Object> response = gson.fromJson(message, new TypeToken<Map<String, Object>>() {
                }.getType());
                if ("Heartbeat acknowledged".equals(response.get("message"))) {
                    log.debug("WebSocket Heartbeat acknowledgment received");
                    lastHeartbeatAck.set(System.currentTimeMillis());
                    // updateWebsocketStatus("connected"); // Disabled as WebSocket is not used
                    return;
                }

                Map<String, Object> data = (Map<String, Object>) response.get("data");
                if (data == null) {
                    log.warn("No data field in WebSocket message: {}", message);
                    return;
                }

                if (data.containsKey("place_order")) {
                    Map<String, Object> placeOrderData = (Map<String, Object>) data.get("place_order");
                    log.info("Received trade placement request from WebSocket: {}", placeOrderData);
                    String order_Random_Id = (String) placeOrderData.get("random_alert_key");
                    log.info("Order Random ID: {}", order_Random_Id);
                    websocket.send(gson.toJson(Map.of("token", token, "trade_data_ack", order_Random_Id, "connection_name", connectionName)));
                    log.info("Processing trade placement request from WebSocket");
                    try (CloseableHttpClient client = HttpClients.createDefault()) {
                        HttpPost post = new HttpPost("http://localhost:" + trade_server_port + "/place-trade");
                        String payload = gson.toJson(placeOrderData);
                        log.debug("Sending trade request to HTTP server: {}", payload);
                        post.setEntity(new StringEntity(payload));
                        post.setHeader("Content-Type", "application/json");
                        try (CloseableHttpResponse apiResponse = client.execute(post)) {
                            String responseText = EntityUtils.toString(apiResponse.getEntity());
                            log.info("HTTP server response: {}", responseText);
                            Map<String, Object> apiResult = gson.fromJson(responseText, new TypeToken<Map<String, Object>>() {
                            }.getType());
                            if (!(boolean) apiResult.get("success")) {
                                log.error("Trade placement failed: {}", apiResult.get("message"));
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error calling HTTP trade server: {}", e.getMessage(), e);
                    }
                } else if (data.containsKey("send_logs")) {
                    log.info("Uploading logs as requested by server");
                    uploadLogs();
                } else if (data.containsKey("ib_rollover")) {
                    log.info("Triggering IB rollover");
                    twsEngine.getIbRollover((List<Integer>) data.get("ib_rollover"));
                }
            } catch (Exception e) {
                log.error("Error processing WebSocket message asynchronously: {}", e.getMessage(), e);
            }
        });
    }

    private void checkWebsocket(Stage window) {
        log.info("Starting WebSocket check");
        String token;
        String connectionName;

        try {
            while (true) {
                log.debug("Retrieving token from database");
                Token tokenRecord = DatabaseConfig.getToken();
                if (tokenRecord != null) {
                    token = tokenRecord.getToken();
                    log.info("Token retrieved: {}", token);
                    break;
                }
                log.debug("No token found, retrying in 1 second");
                Thread.sleep(1000);
            }
            log.debug("Retrieving connection entity from database");
            ConnectionEntity connection = DatabaseConfig.getConnectionEntity();
            connectionName = connection != null ? connection.getConnectionName() : null;
            if (connectionName == null) {
                log.warn("No connection name found, setting stop flag");
                return;
            }
        } catch (Exception e) {
            log.error("Error retrieving token or connection: {}", e.getMessage());
            // updateWebsocketStatus("error"); // Disabled as WebSocket is not used
            return;
        }

        synchronized (websocketTasks) {
            websocketTasks.forEach(task -> task.cancel(true));
            websocketTasks.clear();
        }

        log.info("Checking connection entity: {}", connectionName);
        lastHeartbeatAck.set(System.currentTimeMillis());

        String websocketId = UUID.randomUUID().toString();
        log.info("Creating new WebSocket connection with ID: {}", websocketId);
        final String wsId = websocketId;
        URI uri = URI.create("wss://api.pickmytrade.io/wbsk/live");
        log.info("Initializing WebSocket connection to: {}", uri);

        websocket = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                log.info("Connected to WebSocket server");
                // updateWebsocketStatus("connected"); // Disabled as WebSocket is not used

                synchronized (websocketTasks) {
                    websocketTasks.add(executor.submit(() -> sendHeartbeat(token, connectionName)));
                    websocketTasks.add(executor.submit(() -> sendAccountDataToServer()));
                }
            }

            @Override
            public void onMessage(String message) {
                log.info("Received WebSocket message, delegating to async handler: {}", message);
                handleWebSocketMessageAsync(message, token, connectionName);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.info("WebSocket connection closed: {} - {}", code, reason);
                // updateWebsocketStatus("error"); // Disabled as WebSocket is not used
                lastHeartbeatAck.set(System.currentTimeMillis() - 65_000);
                log.info("setting lastHeartbeatAck to 65 seconds ago");
            }

            @Override
            public void onError(Exception ex) {
                log.error("WebSocket error occurred: {}", ex.getMessage(), ex);
                // updateWebsocketStatus("error"); // Disabled as WebSocket is not used
            }

            @Override
            public void onWebsocketPong(WebSocket conn, Framedata f) {
                log.info("Received Pong from server: {}", new String(f.getPayloadData().array()));
            }

            private void sendHeartbeat(String token, String connectionName) {
                log.info("Starting WebSocket heartbeat");
                while (isOpen() && !Thread.currentThread().isInterrupted()) {
                    try {
                        send(gson.toJson(Map.of("token", token, "heartbeat", "ping", "connection_name", connectionName)));
                        log.debug("WebSocket [{}] token {} heartbeat: ping", wsId, token);
                        Thread.sleep(20_000);
                    } catch (InterruptedException e) {
                        log.info("Heartbeat task interrupted");
                    } catch (Exception e) {
                        log.error("Heartbeat error: {}", e.getMessage());
                    }
                }
                log.info("Heartbeat task stopped");
            }
        };

        connectWebsocket();
    }

    private void connectWebsocket() {
        try {
            log.debug("Attempting to connect to WebSocket");
            // updateWebsocketStatus("connecting"); // Disabled as WebSocket is not used
            websocket.setConnectionLostTimeout(0);
            websocket.connectBlocking();
            log.info("WebSocket connection connection established successfully");
            lastHeartbeatAck.set(System.currentTimeMillis());
        } catch (InterruptedException e) {
            log.info("WebSocket connection interrupted");
            // updateWebsocketStatus("error"); // Disabled as WebSocket is not used
        } catch (Exception e) {
            log.error("Initial WebSocket connection failed: {}", e.getMessage());
            // updateWebsocketStatus("error"); // Disabled as WebSocket is not used
        }
    }

    private void cleanupAndRestartWebsocket(Stage window) {
        if (websocketCleanupLock.tryLock()) {
            try {
                lastHeartbeatAck.set(System.currentTimeMillis());
                log.info("Acquired lock for WebSocket cleanup and restart");
                synchronized (websocketTasks) {
                    websocketTasks.forEach(task -> task.cancel(true));
                    websocketTasks.clear();
                }
                if (websocket != null) {
                    try {
                        websocket.closeBlocking();
                        log.info("WebSocket closed successfully");
                    } catch (Exception e) {
                        log.error("Error closing WebSocket: {}", e.getMessage());
                    } finally {
                        websocket = null;
                    }
                }
                websocketExecutor.submit(() -> checkWebsocket(window));
                log.info("WebSocket cleanup and restart completed");
            } finally {
                websocketCleanupLock.unlock();
                log.debug("Released lock for WebSocket cleanup and restart");
            }
        } else {
            log.info("WebSocket cleanup and restart already in progress, skipping");
        }
    }

    private List<AccountData> retrieveAccountData() {
        log.debug("Retrieving account data from database");
        try {
            return DatabaseConfig.getAccountData();
        } catch (SQLException e) {
            log.error("Error retrieving account data: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void sendAccountDataToServer() {

        while (!Thread.currentThread().isInterrupted()) {
            String token;
            String connectionName;



            try {
                while (true) {
                    log.debug("Retrieving token from database");
                    Token tokenRecord = DatabaseConfig.getToken();
                    if (tokenRecord != null) {
                        token = tokenRecord.getToken();
                        log.info("Token retrieved: {}", token);
                        break;
                    }
                    log.debug("No token found, retrying in 1 second");
                    Thread.sleep(1000);
                }
                log.debug("Retrieving connection entity from database");
                ConnectionEntity connection = DatabaseConfig.getConnectionEntity();
                connectionName = connection != null ? connection.getConnectionName() : null;
                if (connectionName == null) {
                    log.warn("No connection name found, setting stop flag");
                    return;
                }
            } catch (Exception e) {
                log.error("Error retrieving token or connection: {}", e.getMessage());
                // updateWebsocketStatus("error"); // Disabled as WebSocket is not used
                return;
            }
            log.info("Starting task to send account data to server with token: {} and connectionName: {}", token, connectionName);
            try {
                Thread.sleep(15_000);
                log.debug("Retrieving account data for sending");
                List<AccountData> accountData = retrieveAccountData();
                log.info("Retrieved {} account data entries", accountData.size());

                if (!accountData.isEmpty()) {
                    List<String> accountIds = accountData.stream()
                            .map(AccountData::getAccountId)
                            .distinct()
                            .collect(Collectors.toList());
                    log.debug("Extracted account IDs: {}", accountIds);

                    try (CloseableHttpClient client = HttpClients.createDefault()) {
                        HttpPost post = new HttpPost("https://api.pickmytrade.io/v2/exe_save_accounts");
                        String payload = gson.toJson(Map.of("accounts", accountIds));
                        post.setEntity(new StringEntity(payload));
                        post.setHeader("Authorization", token + "_" + connectionName);
                        post.setHeader("Content-Type", "application/json");

                        log.info("Request headers:");
                        for (Header header : post.getAllHeaders()) {
                            log.info("  {}: {}", header.getName(), header.getValue());
                        }

                        log.info("Request URL: {}", post.getURI());
                        log.info("Request method: {}", post.getMethod());
                        log.info("Sending account data to server with payload: {}", payload);

                        try (CloseableHttpResponse response = client.execute(post)) {
                            Thread.sleep(1000);
                            log.info("Response headers:");
                            for (Header header : response.getAllHeaders()) {
                                log.info("  {}: {}", header.getName(), header.getValue());
                            }

                            String responseText = EntityUtils.toString(response.getEntity());
                            int statusCode = response.getStatusLine().getStatusCode();
                            log.info("Account data server response - Status: {}, Body: {}", statusCode, responseText);

                            if (statusCode == 200) {
                                log.info("Account data sent to server successfully");
                                break;
                            } else {
                                log.warn("Failed to send account data - Status: {}, Response: {}", statusCode, responseText);
                            }
                        }
                    }
                } else {
                    log.info("No account data to send");
                }
            } catch (InterruptedException e) {
                log.info("Account data send task interrupted");
                break;
            } catch (Exception e) {
                log.error("Error sending account data to server", e);
            }
        }
        log.info("Account data send task stopped");
    }

    private void placeRemainingTpSlOrderWrapper(long time_var) {
        log.info("Starting task to place remaining TP/SL orders");
        try {
            placeOrderService.placeRemainingTpSlOrder(time_var);
        } catch (Exception e) {
            log.error("Error in placeRemainingTpSlOrder: {}", e.getMessage());
        }
        log.info("Place remaining TP/SL orders task stopped");
    }

    private void resetconnection(){
        DatabaseConfig.emptyconnectionsTable();
    }

    // Modify uploadLogs to return a String message
    private String uploadLogs() {
        String token = heartbeat_connection_id;
        log.info("Uploading logs with token: {}", token);
        String result = "Error uploading logs.";
        File zipperFile = null;
        try {
            File logsDir = new File(System.getProperty("log.path", ""));
            if (!logsDir.exists() || !logsDir.isDirectory()) {
                log.warn("Logs directory does not exist: {}", logsDir.getAbsolutePath());
                return "Logs directory does not exist.";
            }    File[] logFiles = logsDir.listFiles((dir, name) ->
                    name.toLowerCase().startsWith("log") && !name.toLowerCase().equals("logs.zip"));
            if (logFiles == null || logFiles.length == 0) {
                log.warn("No log files found in directory: {}", logsDir.getAbsolutePath());
                return "No log files found.";
            }

            String zipFileName = "logs_" + System.currentTimeMillis() + ".zip";
            zipperFile = new File(logsDir, zipFileName);
            log.debug("Zipping {} log files into {}", logFiles.length, zipperFile.getAbsolutePath());

            synchronized (MainApp.class) {
                try (FileOutputStream fos = new FileOutputStream(zipperFile);
                     ZipOutputStream zos = new ZipOutputStream(fos)) {
                    for (File logFile : logFiles) {
                        log.debug("Adding log file: {}", logFile.getName());
                        try (FileInputStream fis = new FileInputStream(logFile)) {
                            zos.putNextEntry(new ZipEntry(logFile.getName()));
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = fis.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }
                            zos.closeEntry();
                        }
                    }
                }

                try (CloseableHttpClient client = HttpClients.createDefault()) {
                    HttpPost post = new HttpPost("https://api.pickmytrade.io/v2/upload_log");
                    MultipartEntityBuilder builder = MultipartEntityBuilder.create();
                    builder.addBinaryBody("file", zipperFile);
                    post.setEntity(builder.build());
                    post.setHeader("Authorization", token);

                    log.debug("Uploading zipped log files: {}", zipFileName);
                    try (CloseableHttpResponse response = client.execute(post)) {
                        int statusCode = response.getStatusLine().getStatusCode();
                        String responseText = EntityUtils.toString(response.getEntity());
                        log.info("Log upload response: Status={}, Body={}", statusCode, responseText);

                        if (statusCode == 200) {
                            log.info("All log files uploaded successfully!");
                            result = "Logs uploaded successfully!";
                        } else {
                            log.error("Failed to upload log files: Status={}, Response={}", statusCode, responseText);
                            result = "Failed to upload logs: " + responseText;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error uploading logs: {}", e.getMessage(), e);
            result = "Error uploading logs: " + e.getMessage();
        } finally {
            if (zipperFile != null) {
                try {
                    Files.deleteIfExists(zipperFile.toPath());
                    log.debug("Deleted temporary zip file: {}", zipperFile.getAbsolutePath());
                } catch (IOException e) {
                    log.error("Failed to delete temporary zip file {}: {}", zipperFile.getAbsolutePath(), e.getMessage(), e);
                }
            }
        }
        return result;}



}