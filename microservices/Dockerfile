FROM docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-17 AS build
ARG MODULE
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp \
    -s /workspace/settings.xml \
    -U \
    -Dmaven.wagon.http.retryHandler.count=5 \
    -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:17-jre-alpine-3.22
ARG MODULE
RUN apk update && apk upgrade --no-cache openssl && rm -rf /var/cache/apk/*
WORKDIR /app
COPY --from=build /workspace/${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
