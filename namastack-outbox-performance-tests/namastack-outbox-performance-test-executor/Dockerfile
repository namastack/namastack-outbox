FROM grafana/k6:latest

WORKDIR /app
COPY loadtest.js .

CMD ["run", "loadtest.js"]
