FROM debian:testing-slim AS builder
WORKDIR /build

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# drop only the built JDK images to keep layers small
COPY jdk/build/linux-x86_64-server-release/images/jdk   /opt/jdk-release
COPY jdk/build/linux-x86_64-server-fastdebug/images/jdk /opt/jdk-debug

# point JAVA_HOME at the release image
ENV JAVA_HOME=/opt/jdk-release
ENV PATH=$JAVA_HOME/bin:$PATH

# compile javac_server with your release JDK
COPY javac_server/ javac_server/
RUN mkdir -p javac_server/out \
    && find javac_server/src/main/java -name '*.java' -print0 \
         | xargs -0 javac -d javac_server/out

# build the fuzzer (Maven or plain javac—your choice; this keeps Maven)
COPY pom.xml .
RUN mvn -B -DskipTests -Dmaven.test.skip=true dependency:go-offline
COPY src src
RUN mvn -B -DskipTests -Dmaven.test.skip=true package

# ---------- runtime ----------
FROM debian:testing-slim
WORKDIR /app

# copy both JDK builds (including support dirs) and build artifacts
COPY --from=builder /opt/jdk-debug   /opt/jdk-debug
COPY --from=builder /opt/jdk-release /opt/jdk-release
COPY --from=builder /build/javac_server/out javac_server/out
COPY --from=builder /build/target target
COPY docker/entrypoint.sh /entrypoint.sh
COPY docker/wait-for-port.sh /wait-for-port.sh
RUN chmod +x /entrypoint.sh /wait-for-port.sh \
    && mkdir -p /app/seeds

# environment defaults
ENV JAVAC_JAVA_HOME=/opt/jdk-release \
    FUZZER_JAVA_HOME=/opt/jdk-debug \
    JAVAC_HOST=127.0.0.1 \
    JAVAC_PORT=8090 \
    C2FUZZ_DEBUG_JDK=/opt/jdk-debug/bin \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

ENTRYPOINT ["/entrypoint.sh"]
CMD ["--mode", "FUZZ", "--seeds", "/app/seeds/selfcontained_jtreg_compiler"]
