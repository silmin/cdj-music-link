FROM gradle:9.7.1-jdk25 AS builder
WORKDIR /src
COPY . .
RUN gradle :server:installDist --no-daemon

FROM eclipse-temurin:25-jre
COPY --from=builder /src/server/build/install/server/ /app/
ENV JAVA_TOOL_OPTIONS="-Xmx512m"
EXPOSE 17080
ENTRYPOINT ["/app/bin/server"]
