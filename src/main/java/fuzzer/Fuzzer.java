package fuzzer;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.runtime.FuzzerConfig;
import fuzzer.runtime.SessionController;
import fuzzer.util.LoggingConfig;

public class Fuzzer {

    private static final Logger LOGGER = LoggingConfig.getLogger(Fuzzer.class);
    private static String timestamp;

    public void run(String[] args) {
        FuzzerConfig config = FuzzerConfig.fromArgs(args, timestamp, LOGGER);
        SessionController controller = new SessionController(config);
        controller.run();
    }

    public static void main(String[] args) {
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
