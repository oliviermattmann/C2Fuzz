package fuzzer;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
import fuzzer.runtime.FuzzerConfig;
import fuzzer.runtime.SessionController;

public class Fuzzer {

    private static final Logger LOGGER = LoggingConfig.getLogger(Fuzzer.class);
    private static String timestamp;

    public void run(String[] args) {
        FuzzerConfig config = FuzzerConfig.fromArgs(args, timestamp, LOGGER);
        LoggingConfig.setLevel(config.logLevel());
        LOGGER.info(String.format(
                "Logger level set to %s",
                config.logLevel().getName()));
        SessionController controller = new SessionController(config);
        controller.run();
    }

    public static void main(String[] args) {
        // setup logging now, so we catch log statements from config parsing already
        timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        try {
            LoggingConfig.setup(timestamp, Level.INFO);
        } catch (IOException e) {
            System.err.println("Failed to set up logging configuration.");
            return;
        }
        new Fuzzer().run(args);
    }
}
