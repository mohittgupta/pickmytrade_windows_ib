package com.pickmytrade.ibapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config {

//    private static final Logger log = LoggerFactory.getLogger(Config.class);

    private static final String APP_DIR_NAME = "PickMyTrade";
    private static final String APP_DIR_NAME_LINUX = ".pickmytrade";

    public static final Path APP_HOME_DIR;
    public static final Path LOG_DIR;
    public static final Path CONFIGS_FILE;
    public static final Path UPDATES_DIR;

    static {
        String os = System.getProperty("os.name").toLowerCase();
        Path baseDir;

        if (os.contains("mac") || os.contains("darwin")) {
            baseDir = Paths.get(System.getProperty("user.home"), APP_DIR_NAME);
        } else if (os.contains("linux") || os.contains("unix")) {
            baseDir = Paths.get(System.getProperty("user.home"), APP_DIR_NAME_LINUX);
        } else {
            // Windows + fallback
            String appdata = System.getenv("APPDATA");
            baseDir = (appdata != null && !appdata.isBlank())
                    ? Paths.get(appdata, APP_DIR_NAME)
                    : Paths.get(System.getProperty("user.home"), "AppData", "Roaming", APP_DIR_NAME);
        }

        APP_HOME_DIR = baseDir;

        // Create root directory
        if (!Files.exists(APP_HOME_DIR)) {
            try {
                Files.createDirectories(APP_HOME_DIR);
                System.out.println("Created application home" + APP_HOME_DIR);
            } catch (Exception e) {
                System.out.println("Cannot create app home directory " + APP_HOME_DIR + e);
            }
        }

        LOG_DIR     = APP_HOME_DIR.resolve("logs");
        CONFIGS_FILE = APP_HOME_DIR.resolve("configs.json");
        UPDATES_DIR = APP_HOME_DIR.resolve("updates");

        // Create subdirectories
        for (Path dir : new Path[]{LOG_DIR, UPDATES_DIR}) {
            if (!Files.exists(dir)) {
                try {
                    Files.createDirectories(dir);
                    System.out.println("Created directory " + dir);
                } catch (Exception e) {
                    System.out.println("Cannot create directory " + dir + e);
                }
            }
        }

        // Logback path (forward slashes)
        System.setProperty("log.path", LOG_DIR.toString().replace("\\", "/"));
    }

    // ────────────────────────────────────────────────
    //  Database helpers
    // ────────────────────────────────────────────────

    public static Path getDatabasePath(int port) {
        return APP_HOME_DIR.resolve("IB_" + port + ".db");
    }

    public static String getDatabaseUrl(int port) {
        return "jdbc:sqlite:" + getDatabasePath(port).toString().replace("\\", "/");
    }

    public static final String DEFAULT_DB_URL = getDatabaseUrl(7497);

    // Logging type fallback
    public static final String LOG_TYPE = System.getenv("LOG_TYPE") != null
            ? System.getenv("LOG_TYPE")
            : "DEBUG";

    public static final Logger log = LoggerFactory.getLogger("com.pickmytrade.ibapp");
}