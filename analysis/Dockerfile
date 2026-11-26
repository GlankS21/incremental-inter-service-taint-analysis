# vkr-taint: incremental inter-service taint analysis of Spring Cloud repositories.
#   docker build -t vkr-taint analysis
#   docker run --rm -v "$PWD:/repo" -v vkr-cache:/cache vkr-taint /repo --build none
# Micans (AGPL-3.0, Zenodo 10.5281/zenodo.15050505) is built here from its published source with the
# patches of taint/patches; neither the source nor the jar is part of this repository.

# --- Micans from source (its Gradle toolchain is Java 17)
FROM eclipse-temurin:25-jdk AS micans
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends xz-utils patch curl > /dev/null \
    && rm -rf /var/lib/apt/lists/*
# only what the build reads, so editing the patch notes does not rebuild Micans
COPY taint/patches/build_micans.sh taint/patches/*.patch /build/patches/
RUN sh /build/patches/build_micans.sh /out/micans.jar

# --- channel-extractor (Java 21)
FROM maven:3.9.9-eclipse-temurin-21 AS extractor
COPY channel-extractor /build
RUN cd /build && mvn -B -q -ntp -DskipTests package \
    && cp target/channel-extractor-0.1.0-SNAPSHOT.jar /out.jar

# --- runtime: JDK 24 runs Micans; the JRE model is built once into the image
FROM eclipse-temurin:25-jdk
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends python3 unzip git > /dev/null \
    && rm -rf /var/lib/apt/lists/*
COPY . /opt/vkr
COPY --from=micans /out/micans.jar /opt/micans/micans.jar
COPY --from=extractor /out.jar /opt/vkr/channel-extractor/target/channel-extractor-0.1.0-SNAPSHOT.jar
ENV VKR_MICANS_JAR=/opt/micans/micans.jar \
    VKR_JRE_HOME=/opt/vkr-jre/jre-home \
    VKR_WORK=/cache \
    VKR_HEAP=12g \
    VKR_PYTHON=python3 \
    PYTHONUTF8=1
RUN VKR_WORK=/opt/vkr-jre sh /opt/vkr/taint/scripts/prepare.sh --jre-only \
    && rm -rf /opt/vkr-jre/jdkx /opt/vkr-jre/stubs \
    && chmod +x /opt/vkr/vkr-taint
VOLUME /cache
ENTRYPOINT ["/opt/vkr/vkr-taint"]
