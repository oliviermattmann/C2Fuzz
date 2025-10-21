package com.example.javacserver;

import com.sun.net.httpserver.HttpServer;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class JavacServer {
    private JavacServer() {
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.fromArgs(args);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("No Java compiler available. Run this server using a full JDK (your custom build) instead of a JRE.");
            System.exit(1);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
        server.createContext("/compile", new CompilationHandler(compiler));
        server.createContext("/health", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendPlainText(exchange, 405, "GET required");
                return;
            }
            HttpUtils.sendPlainText(exchange, 200, "ok");
        });

        ExecutorService executor = Executors.newFixedThreadPool(config.workerThreads);
        server.setExecutor(executor);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(server, executor)));

        System.out.printf(Locale.ROOT, "javac server ready on %s:%d (threads=%d)%n",
                config.bindAddress, config.port, config.workerThreads);
    }

    private static void shutdown(HttpServer server, ExecutorService executor) {
        System.out.println("Shutting down javac server...");
        server.stop(0);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static final class Config {
        private final String bindAddress;
        private final int port;
        private final int workerThreads;

        private Config(String bindAddress, int port, int workerThreads) {
            this.bindAddress = bindAddress;
            this.port = port;
            this.workerThreads = workerThreads;
        }

        static Config fromArgs(String[] args) {
            String bindAddress = "127.0.0.1";
            int port = 8090;
            int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors());

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port":
                        port = parseIntArg(args, ++i, "--port");
                        break;
                    case "--bind":
                    case "--host":
                        bindAddress = requireArg(args, ++i, "--bind");
                        break;
                    case "--threads":
                        workerThreads = Math.max(1, parseIntArg(args, ++i, "--threads"));
                        break;
                    case "--help":
                    case "-h":
                        printUsageAndExit();
                        break;
                    default:
                        System.err.println("Unknown option: " + args[i]);
                        printUsageAndExit();
                        break;
                }
            }

            return new Config(bindAddress, port, workerThreads);
        }

        private static void printUsageAndExit() {
            String usage = String.join(System.lineSeparator(), List.of(
                    "Usage: java com.example.javacserver.JavacServer [options]",
                    "Options:",
                    "  --port <port>            TCP port to listen on (default 8090)",
                    "  --bind <address>         Bind address (default 127.0.0.1)",
                    "  --threads <count>        Worker threads for compilation (default cpu cores)",
                    "  --help                   Show this message"
            ));
            System.out.println(usage);
            System.exit(0);
        }

        private static String requireArg(String[] args, int index, String option) {
            if (index >= args.length) {
                System.err.printf("Missing value for %s%n", option);
                System.exit(1);
            }
            return args[index];
        }

        private static int parseIntArg(String[] args, int index, String option) {
            String value = requireArg(args, index, option);
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                System.err.printf("Invalid integer for %s: %s%n", option, value);
                System.exit(1);
                return -1; // Unreachable
            }
        }
    }
}
