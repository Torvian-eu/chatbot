FROM eclipse-temurin:21-jre

WORKDIR /app

COPY dist/lib/ /app/lib/
COPY dist/start-server.sh /app/start-server.sh
COPY dist/config/application.json /app/default-config/application.json
COPY dist/config/setup.json /app/default-config/setup.json
COPY dist/config/env-mapping.json /app/default-config/env-mapping.json

RUN chmod +x /app/start-server.sh && \
    mkdir -p /app/config /app/data /app/logs /app/default-config

EXPOSE 8080

CMD ["/app/start-server.sh"]

