FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y tesseract-ocr tesseract-ocr-spa tesseract-ocr-eng && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
RUN mkdir -p /tmp/cuentaclara-uploads && chmod 1777 /tmp/cuentaclara-uploads && chmod -R a+rX /app
ENV TEMP_STORAGE_DIR=/tmp/cuentaclara-uploads
EXPOSE 8080 7860
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=75.0} -jar /app/app.jar"]
