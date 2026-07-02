FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN apk upgrade --no-cache \
  && apk add --no-cache curl \
  && addgroup -S peak \
  && adduser -S -G peak peak

COPY --chown=peak:peak build/libs/peak.jar /app/peak.jar

USER peak:peak

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/peak.jar"]
