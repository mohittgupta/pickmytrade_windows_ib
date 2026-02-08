package com.pickmytrade.ibapp.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class LoggingConfig {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(LoggingConfig.class);
    private static final int MAX_LOG_FILES = 5;
    private static final long TWO_DAYS_MILLIS = 2 * 24 * 60 * 60 * 1000; // 2 days in milliseconds

    public static void configure(int port) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        // RollingFileAppender
        RollingFileAppender rfAppender = new RollingFileAppender();
        rfAppender.setContext(context);
        String logFile = Config.LOG_DIR.resolve("log_" + port + ".log").toString();
        rfAppender.setFile(logFile);

        // FixedWindowRollingPolicy for count-based rollover
        FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(rfAppender);
        rollingPolicy.setFileNamePattern(Config.LOG_DIR.resolve("log_" + port + ".%i.log").toString());
        rollingPolicy.setMinIndex(1);
        rollingPolicy.setMaxIndex(MAX_LOG_FILES);
        rollingPolicy.start();

        // SizeBasedTriggeringPolicy for size-based triggering
        SizeBasedTriggeringPolicy triggeringPolicy = new SizeBasedTriggeringPolicy();
        triggeringPolicy.setContext(context);
        triggeringPolicy.start();

        rfAppender.setRollingPolicy(rollingPolicy);
        rfAppender.setTriggeringPolicy(triggeringPolicy);

        // Encoder
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS Z} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();

        rfAppender.setEncoder(encoder);
        rfAppender.start();

        // Root logger
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.toLevel(Config.LOG_TYPE, Level.DEBUG));
        rootLogger.addAppender(rfAppender);

        log.info("Logging configured for port: {} with file: {}", port, logFile);
    }

    public static void cleanupOldLogs() {
        Path logDir = Config.LOG_DIR; // Use LOG_DIR from Config
        try (Stream<Path> filesStream = Files.list(logDir)) {
            Map<String, List<Path>> portGroups = new HashMap<>();
            Pattern pattern = Pattern.compile("log_(\\d+)\\.log|log_(\\d+)\\.\\d+\\.log");

            filesStream.forEach(p -> {
                String name = p.getFileName().toString();
                Matcher matcher = pattern.matcher(name);
                if (matcher.matches()) {
                    String port = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    portGroups.computeIfAbsent(port, k -> new ArrayList<>()).add(p);
                } else if (name.startsWith("log") && name.endsWith(".log") && !name.equals("logs.zip")) {
                    // Handle legacy log files
                    portGroups.computeIfAbsent("legacy", k -> new ArrayList<>()).add(p);
                }
            });

            for (Map.Entry<String, List<Path>> entry : portGroups.entrySet()) {
                String port = entry.getKey();
                List<Path> logFiles = entry.getValue();

                // Sort by last modified time, newest first
                logFiles.sort(Comparator.comparing((Path p2) -> {
                    try {
                        return Files.getLastModifiedTime(p2);
                    } catch (IOException e) {
                        return FileTime.fromMillis(0);
                    }
                }).reversed());

                // Delete files beyond the max limit (keep newest 5)
                if (logFiles.size() > MAX_LOG_FILES) {
                    for (Path p : logFiles.subList(MAX_LOG_FILES, logFiles.size())) {
                        try {
                            Files.delete(p);
                            log.info("Deleted old log file for port {} (exceeded max count): {}", port, p);
                        } catch (IOException e) {
                            log.error("Error deleting log file {}: {}", p, e.getMessage());
                        }
                    }
                }

                // Delete files older than 2 days
                long now = System.currentTimeMillis();
                for (Path p : new ArrayList<>(logFiles)) { // Copy to avoid concurrent modification
                    try {
                        FileTime modTime = Files.getLastModifiedTime(p);
                        if (now - modTime.toMillis() > TWO_DAYS_MILLIS) {
                            Files.delete(p);
                            log.info("Deleted old log file for port {} (older than 2 days): {}", port, p);
                        }
                    } catch (IOException e) {
                        log.error("Error checking or deleting log file {}: {}", p, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error listing log files in {}: {}", logDir, e.getMessage());
        }
    }
}