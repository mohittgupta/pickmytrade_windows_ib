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

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(LoggingConfig.class);

    private static final int MAX_LOG_FILES = 5;
    private static final long TWO_DAYS_MILLIS = 2L * 24 * 60 * 60 * 1000;

    public static void configure(int port) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        Path logFilePath = Config.LOG_DIR.resolve("log_" + port + ".log");
        String logFile = logFilePath.toString();

        RollingFileAppender rfAppender = new RollingFileAppender();
        rfAppender.setContext(context);
        rfAppender.setFile(logFile);

        FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(rfAppender);
        rollingPolicy.setFileNamePattern(Config.LOG_DIR.resolve("log_" + port + ".%i.log").toString());
        rollingPolicy.setMinIndex(1);
        rollingPolicy.setMaxIndex(MAX_LOG_FILES);
        rollingPolicy.start();

        SizeBasedTriggeringPolicy triggeringPolicy = new SizeBasedTriggeringPolicy();
        triggeringPolicy.setContext(context);
        triggeringPolicy.setMaxFileSize(FileSize.valueOf("100MB"));
        triggeringPolicy.start();

        rfAppender.setRollingPolicy(rollingPolicy);
        rfAppender.setTriggeringPolicy(triggeringPolicy);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS Z} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();

        rfAppender.setEncoder(encoder);
        rfAppender.start();

        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.toLevel(Config.LOG_TYPE, Level.DEBUG));
        rootLogger.addAppender(rfAppender);

        log.info("Logging configured for port {} → {}", port, logFile);
    }

    public static void cleanupOldLogs() {
        Path logDir = Config.LOG_DIR;
        if (!Files.exists(logDir)) return;

        try (Stream<Path> files = Files.list(logDir)) {
            Map<String, List<Path>> groups = new HashMap<>();

            Pattern p = Pattern.compile("log_(\\d+)\\.log|log_(\\d+)\\.\\d+\\.log");

            files.forEach(file -> {
                String name = file.getFileName().toString();
                Matcher m = p.matcher(name);
                if (m.matches()) {
                    String port = m.group(1) != null ? m.group(1) : m.group(2);
                    groups.computeIfAbsent(port, k -> new ArrayList<>()).add(file);
                }
            });

            for (var entry : groups.entrySet()) {
                String port = entry.getKey();
                List<Path> logs = entry.getValue();

                // Newest first
                logs.sort(Comparator.comparing((Path f) -> {
                    try {
                        return Files.getLastModifiedTime(f);
                    } catch (Exception e) {
                        return FileTime.fromMillis(0);
                    }
                }).reversed());

                // Keep only latest N files
                if (logs.size() > MAX_LOG_FILES) {
                    logs.subList(MAX_LOG_FILES, logs.size())
                            .forEach(f -> deleteQuietly(f, port));
                }

                // Delete files > 2 days old
                long now = System.currentTimeMillis();
                for (Path f : logs) {
                    try {
                        if (now - Files.getLastModifiedTime(f).toMillis() > TWO_DAYS_MILLIS) {
                            deleteQuietly(f, port);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException e) {
            log.error("Cannot clean old logs in {}", logDir, e);
        }
    }

    private static void deleteQuietly(Path p, String port) {
        try {
            Files.deleteIfExists(p);
            log.info("Deleted old/excess log for port {}: {}", port, p.getFileName());
        } catch (IOException e) {
            log.warn("Cannot delete {}: {}", p, e.getMessage());
        }
    }
}