FROM eclipse-temurin:21-jre

WORKDIR /app

COPY dist/ /app

RUN chmod +x /app/start-server.sh && \
    mkdir -p /app/config /app/data /app/logs

EXPOSE 8080

CMD ["/app/start-server.sh"]
