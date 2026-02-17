package com.pickmytrade.ibapp.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class LoggingConfig {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(LoggingConfig.class);

    private static final int MAX_LOG_FILES = 5;

    // 2 days in milliseconds
    private static final long TWO_DAYS_MILLIS =
            2L * 24 * 60 * 60 * 1000;

    public static void configure(int port) {

        LoggerContext context =
                (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        String logFile =
                Config.LOG_DIR.resolve("log_" + port + ".log").toString();

        // -------------------------
        // Rolling File Appender
        // -------------------------
        RollingFileAppender rfAppender = new RollingFileAppender();
        rfAppender.setContext(context);
        rfAppender.setFile(logFile);

        // -------------------------
        // Rolling Policy (Max 5 files)
        // -------------------------
        FixedWindowRollingPolicy rollingPolicy =
                new FixedWindowRollingPolicy();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(rfAppender);
        rollingPolicy.setFileNamePattern(
                Config.LOG_DIR.resolve("log_" + port + ".%i.log").toString()
        );
        rollingPolicy.setMinIndex(1);
        rollingPolicy.setMaxIndex(MAX_LOG_FILES);
        rollingPolicy.start();

        // -------------------------
        // Trigger at 100MB
        // -------------------------
        SizeBasedTriggeringPolicy triggeringPolicy =
                new SizeBasedTriggeringPolicy();
        triggeringPolicy.setContext(context);

        // 🔥 THIS is what fixes your 10MB issue
        triggeringPolicy.setMaxFileSize(
                FileSize.valueOf("100MB")
        );

        triggeringPolicy.start();

        rfAppender.setRollingPolicy(rollingPolicy);
        rfAppender.setTriggeringPolicy(triggeringPolicy);

        // -------------------------
        // Encoder
        // -------------------------
        PatternLayoutEncoder encoder =
                new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(
                "%d{yyyy-MM-dd HH:mm:ss.SSS Z} [%thread] %-5level %logger{36} - %msg%n"
        );
        encoder.start();

        rfAppender.setEncoder(encoder);
        rfAppender.start();

        // -------------------------
        // Root Logger
        // -------------------------
        Logger rootLogger =
                context.getLogger(Logger.ROOT_LOGGER_NAME);

        rootLogger.setLevel(
                Level.toLevel(Config.LOG_TYPE, Level.DEBUG)
        );

        rootLogger.addAppender(rfAppender);

        log.info("Logging configured for port {} with file {}", port, logFile);
    }

    public static void cleanupOldLogs() {

        Path logDir = Config.LOG_DIR;

        try (Stream<Path> filesStream = Files.list(logDir)) {

            Map<String, List<Path>> portGroups = new HashMap<>();

            Pattern pattern =
                    Pattern.compile("log_(\\d+)\\.log|log_(\\d+)\\.\\d+\\.log");

            filesStream.forEach(p -> {

                String name = p.getFileName().toString();
                Matcher matcher = pattern.matcher(name);

                if (matcher.matches()) {

                    String port = matcher.group(1) != null
                            ? matcher.group(1)
                            : matcher.group(2);

                    portGroups
                            .computeIfAbsent(port, k -> new ArrayList<>())
                            .add(p);
                }
            });

            for (Map.Entry<String, List<Path>> entry : portGroups.entrySet()) {

                String port = entry.getKey();
                List<Path> logFiles = entry.getValue();

                // Sort newest first
                logFiles.sort(
                        Comparator.comparing((Path p) -> {
                            try {
                                return Files.getLastModifiedTime(p);
                            } catch (IOException e) {
                                return FileTime.fromMillis(0);
                            }
                        }).reversed()
                );

                // Keep only latest MAX_LOG_FILES
                if (logFiles.size() > MAX_LOG_FILES) {
                    for (Path p : logFiles.subList(MAX_LOG_FILES, logFiles.size())) {
                        try {
                            Files.deleteIfExists(p);
                            log.info("Deleted excess log file for port {}: {}", port, p);
                        } catch (IOException e) {
                            log.error("Error deleting log file {}: {}", p, e.getMessage());
                        }
                    }
                }

                // Delete logs older than 2 days
                long now = System.currentTimeMillis();

                for (Path p : logFiles) {
                    try {
                        FileTime modTime = Files.getLastModifiedTime(p);
                        if (now - modTime.toMillis() > TWO_DAYS_MILLIS) {
                            Files.deleteIfExists(p);
                            log.info("Deleted old log file (>2 days) for port {}: {}", port, p);
                        }
                    } catch (IOException e) {
                        log.error("Error checking/deleting {}: {}", p, e.getMessage());
                    }
                }
            }

        } catch (IOException e) {
            log.error("Error listing log files in {}: {}", logDir, e.getMessage());
        }
    }
}
