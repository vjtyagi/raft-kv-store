FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

# Parent POM
COPY pom.xml .
# Module POMs
COPY raft-core/pom.xml raft-core/
COPY kv-store/pom.xml kv-store/
COPY networking/pom.xml networking/
COPY node-runner/pom.xml node-runner/

# Create empty src directories to satisfy Maven's directory structure expectations
RUN mkdir -p raft-core/src/main/java && \
    mkdir -p kv-store/src/main/java && \
    mkdir -p networking/src/main/java && \
    mkdir -p node-runner/src/main/java

# Download dependencies - this layer will be cached unless POM files change
# The -o flag works in offline mode using cached dependencies when possible
RUN mvn dependency:go-offline -B

COPY raft-core/src raft-core/src/
COPY kv-store/src kv-store/src/
COPY networking/src networking/src/
COPY node-runner/src node-runner/src/

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

COPY --from=build /app/node-runner/target/*.jar app.jar

RUN mkdir -p /data/raft

EXPOSE 8080 9090

ENV NODE_ID=node1 \
    PEERS=node2,node3 \
    STORAGE_DIR=/data/raft \
    CLIENT_PORT=8080 \
    RPC_PORT=9090

ENTRYPOINT [ "java", "-jar", "app.jar" ]