FROM maven:3.8.1-jdk-11

COPY . /app
WORKDIR /app

RUN mvn clean package -DskipTests

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "target/order-service-1.0.0.jar"]