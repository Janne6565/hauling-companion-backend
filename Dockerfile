FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
COPY src/ src/
RUN ./mvnw -DskipTests package -q

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-eng
WORKDIR /app
COPY --from=build /app/target/hauling-companion-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
