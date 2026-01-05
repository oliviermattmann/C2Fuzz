package fuzzer.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class LoggingConfig {

    private static final Logger INTERNAL_LOGGER = Logger.getLogger(LoggingConfig.class.getName());
    private static FileHandler activeHandler;
    private static Path activeLogPath;
    private static Level currentLevel = Level.INFO;

    public static synchronized void setup(String timestamp, Level level) throws IOException {
        LogManager.getLogManager().reset();
        currentLevel = (level != null) ? level : Level.INFO;

        Path logsDir = Path.of("logs");
        Files.createDirectories(logsDir);
        Path defaultLog = logsDir.resolve("fuzzer" + timestamp + ".log");
        installHandler(defaultLog);
    }

    public static synchronized void redirectToSessionDirectory(Path sessionDir) {
        if (sessionDir == null) {
            return;
        }
        Path targetLog = sessionDir.resolve("fuzzer.log");
        if (targetLog.equals(activeLogPath)) {
            return;
        }

        Path previousLog = activeLogPath;
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException ioe) {
            INTERNAL_LOGGER.log(Level.WARNING, "Failed to create session log directory", ioe);
            return;
        }

        try {
            detachHandler();
            if (previousLog != null && Files.exists(previousLog)) {
                try {
                    Files.createDirectories(targetLog.getParent());
                    Files.move(previousLog, targetLog, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveEx) {
                    INTERNAL_LOGGER.log(Level.WARNING, "Failed to move existing log file to session directory", moveEx);
                }
            }
            installHandler(targetLog);
        } catch (IOException ioe) {
            INTERNAL_LOGGER.log(Level.WARNING, "Failed to redirect log output to session directory", ioe);
            if (previousLog != null) {
                try {
                    installHandler(previousLog);
                } catch (IOException ignored) {
                    // Best-effort fallback; swallow errors to avoid cascading failures
                }
            }
        }
    }

    public static Logger getLogger(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }

    public static void safeLog(Logger logger, Level level, String message) {
        try {
            logger.log(level, message);
        } catch (NullPointerException ignored) {
            // handler already closed during shutdown
        }
    }

    public static void safeInfo(Logger logger, String message) {
        safeLog(logger, Level.INFO, message);
    }

    public static synchronized void setLevel(Level level) {
        if (level == null) {
            return;
        }
        currentLevel = level;
        Logger.getLogger("fuzzer").setLevel(level);
    }

    private static void installHandler(Path logPath) throws IOException {
        Files.createDirectories(logPath.getParent());
        FileHandler fileHandler = new FileHandler(logPath.toString(), true);
        fileHandler.setFormatter(new ThreadAwareFormatter());

        Logger fuzzerLogger = Logger.getLogger("fuzzer");
        fuzzerLogger.setUseParentHandlers(false);
        detachHandler();
        fuzzerLogger.addHandler(fileHandler);
        fuzzerLogger.setLevel(currentLevel);

        activeHandler = fileHandler;
        activeLogPath = logPath;
    }

    private static void detachHandler() {
        if (activeHandler == null) {
            return;
        }
        Logger fuzzerLogger = Logger.getLogger("fuzzer");
        fuzzerLogger.removeHandler(activeHandler);
        try {
            activeHandler.close();
        } catch (Exception ignored) {
            // Ignore close failures; we're reconfiguring anyway
        }
        activeHandler = null;
        activeLogPath = null;
    }
}

class ThreadAwareFormatter extends Formatter {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    @Override
    public String format(LogRecord record) {
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(record.getMillis()));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s [%s] %s: %s%n",
                timestamp,
                Thread.currentThread().getName(),
                record.getLevel(),
                record.getMessage()));

        Throwable thrown = record.getThrown();
        if (thrown != null) {
            StringWriter sw = new StringWriter();
            // Use a PrintWriter to capture the stack trace in a readable format.
            try (PrintWriter pw = new PrintWriter(sw)) {
                thrown.printStackTrace(pw);
            }
            sb.append(sw.toString());
        }
        return sb.toString();
    }
}
