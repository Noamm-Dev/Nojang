FROM eclipse-temurin:21-jdk AS build

WORKDIR /work

COPY . .
RUN --mount=type=cache,target=/root/.gradle --mount=type=cache,target=/root/.konan \
    sed -i 's/\r$//' gradlew && sh ./gradlew linkReleaseExecutableNative --no-daemon --console=plain && \
    cp build/bin/native/releaseExecutable/Nojang.kexe /Nojang

FROM ubuntu:24.04

ENV TZ=Asia/Jerusalem
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates tzdata && rm -rf /var/lib/apt/lists/* && \
    ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app

COPY --from=build /Nojang .

EXPOSE 8080
ENTRYPOINT ["./Nojang"]