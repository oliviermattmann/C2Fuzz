FROM ubuntu:24.04 AS builder
WORKDIR /build

RUN apt-get update \
    && apt-get install -y maven \
    && rm -rf /var/lib/apt/lists/*

# drop both JDK builds (retain their support/ layout so symlinks resolve)
COPY jdk/build/linux-x86_64-server-release   /opt/jdk-release-build
COPY jdk/build/linux-x86_64-server-fastdebug /opt/jdk-debug-build

# point JAVA_HOME at the release image (with support tree intact)
ENV JAVA_HOME=/opt/jdk-release-build/images/jdk
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
COPY seeds seeds   
RUN mvn -B -DskipTests -Dmaven.test.skip=true package

# ---------- runtime ----------
FROM ubuntu:24.04
WORKDIR /app

# copy both JDK builds (including support dirs) and build artifacts
COPY --from=builder /opt/jdk-debug-build   /opt/jdk-debug
COPY --from=builder /opt/jdk-release-build /opt/jdk-release
COPY --from=builder /build/javac_server/out javac_server/out
COPY --from=builder /build/target target
COPY --from=builder /build/seeds seeds
COPY docker/entrypoint.sh /entrypoint.sh
COPY docker/wait-for-port.sh /wait-for-port.sh
RUN chmod +x /entrypoint.sh /wait-for-port.sh

# environment defaults
ENV JAVAC_JAVA_HOME=/opt/jdk-release/images/jdk \
    FUZZER_JAVA_HOME=/opt/jdk-debug/images/jdk \
    JAVAC_HOST=127.0.0.1 \
    JAVAC_PORT=8090 \
    C2FUZZ_DEBUG_JDK=/opt/jdk-debug/images/jdk/bin \
    C2FUZZ_RELEASE_JDK=/opt/jdk-release/images/jdk/bin \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

EXPOSE 7680
ENTRYPOINT ["/entrypoint.sh"]
CMD ["--mode", "FUZZ", "--seeds", "/app/seeds/selfcontained_jtreg_compiler"]
