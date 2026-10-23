# バイトコードはプラットフォーム非依存なので、ビルドはビルドホストのネイティブで行う
FROM --platform=$BUILDPLATFORM gradle:9.7.1-jdk25 AS builder
WORKDIR /src
COPY . .
RUN gradle :fake-cdj:installDist --no-daemon

FROM eclipse-temurin:25-jre
COPY --from=builder /src/fake-cdj/build/install/fake-cdj/ /app/
EXPOSE 17090
ENTRYPOINT ["/app/bin/fake-cdj"]
