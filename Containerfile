FROM eclipse-temurin:25-jre

WORKDIR /app

RUN apt-get update \
  && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/* \
  && addgroup --system peak \
  && adduser --system --ingroup peak peak

COPY --chown=peak:peak build/libs/peak-*.jar /app/peak.jar

USER peak:peak

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/peak.jar"]
