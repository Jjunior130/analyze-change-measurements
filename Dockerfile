FROM java:8-alpine

COPY target/uberjar/analyze-change-measurements.jar /analyze-change-measurements/app.jar

EXPOSE 3000

CMD ["java", "-jar", "/analyze-change-measurements/app.jar"]
