package fuzzer.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class LoggingConfig {
    
    public static void setup(String timestamp, Level level) throws IOException {
        LogManager.getLogManager().reset();
        
        FileHandler fileHandler = new FileHandler("logs/fuzzer" + timestamp + ".log", true);
        fileHandler.setFormatter(new ThreadAwareFormatter());

        // Configure the top-level package logger.
        Logger fuzzerLogger = Logger.getLogger("fuzzer");
        fuzzerLogger.addHandler(fileHandler);


        fuzzerLogger.setLevel(level);

        // // Keep the root logger for general messages, but don't add the file handler to it.
        // // The fuzzer logger will handle all messages for its children.
        // Logger rootLogger = Logger.getLogger("");
        // rootLogger.setLevel(Level.INFO);
    }

    public static Logger getLogger(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }
}

class ThreadAwareFormatter extends Formatter {
    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s] %s: %s%n",
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
