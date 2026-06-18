FROM eclipse-temurin:25-jre

WORKDIR /app

RUN addgroup --system peak && adduser --system --ingroup peak peak

COPY build/libs/peak-*.jar /app/peak.jar

USER peak:peak

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD java -version >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/peak.jar"]
