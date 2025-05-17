package com.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingConfiguration {
    private static final Logger log = LoggerFactory.getLogger(LoggingConfiguration.class);

    public static void initializeLogging(String logFile) {
        System.setProperty("LOG_FILE", logFile);
        String timezone = System.getProperty("user.timezone");
    }

    public static void initializeNodeLogging(String nodeId, String logDir) {
        String logFile = logDir + "/" + nodeId + ".log";
        initializeLogging(logFile);

    }

    public static void redirectSystemStreams() {
        final Logger stdoutLogger = LoggerFactory.getLogger("system.out");
        final Logger stderrLogger = LoggerFactory.getLogger("system.err");

        System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
            private StringBuilder buffer = new StringBuilder();

            @Override
            public void write(int b) {
                if (b == '\n') {
                    stdoutLogger.info(buffer.toString());
                    buffer.setLength(0);
                } else {
                    buffer.append((char) b);
                }
            }
        }));

        System.setErr(new java.io.PrintStream(new java.io.OutputStream() {
            private StringBuilder buffer = new StringBuilder();

            @Override
            public void write(int b) {
                if (b == '\n') {
                    stderrLogger.error(buffer.toString());
                    buffer.setLength(0);
                } else {
                    buffer.append((char) b);
                }
            }
        }));
    }
}
