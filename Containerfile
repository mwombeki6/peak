FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0

WORKDIR /app

RUN apk upgrade --no-cache \
  && addgroup -S peak \
  && adduser -S -G peak peak

COPY --chown=peak:peak build/libs/peak.jar /app/peak.jar

USER peak:peak

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/peak.jar"]
