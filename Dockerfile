FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-spa tesseract-ocr-eng && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
RUN mkdir -p /tmp/cuentaclara-uploads && chmod 1777 /tmp/cuentaclara-uploads && chmod -R a+rX /app
ENV TEMP_STORAGE_DIR=/tmp/cuentaclara-uploads
EXPOSE 8080 7860
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:--XX:InitialRAMPercentage=20.0 -XX:MaxRAMPercentage=50.0 -XX:+UseSerialGC -Xss512k} -jar /app/app.jar"]
