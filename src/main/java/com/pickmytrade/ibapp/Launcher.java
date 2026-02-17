package com.pickmytrade.ibapp;

/**
 * Non-JavaFX launcher class to work around the fat JAR module issue.
 * JavaFX requires a non-Application class as the entry point when
 * running from a shaded/fat JAR (without --module-path).
 */
public class Launcher {
    public static void main(String[] args) {
        MainApp.main(args);
    }
}
