FROM eclipse-temurin:21-jdk AS build

WORKDIR /work

COPY . .
RUN --mount=type=cache,target=/root/.gradle --mount=type=cache,target=/root/.konan \
    sed -i 's/\r$//' gradlew && sh ./gradlew linkReleaseExecutableNative --no-daemon --console=plain && \
    cp build/bin/native/releaseExecutable/Nojang.kexe /Nojang

FROM ubuntu:24.04

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /Nojang .

ENTRYPOINT ["./Nojang"]