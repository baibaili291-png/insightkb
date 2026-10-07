# InsightKB 多阶段构建镜像
#
# 构建期：用 Maven 把源码打成可执行 jar（含所有依赖，spring-boot 重新打包过）。
# 运行期：只拿一个 JRE 基础镜像跑 jar，镜像体积小、攻击面小。
# 密钥（LLM_API_KEY / DB_PASSWORD）一律走环境变量注入，绝不写进镜像层。

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# 先拷 pom 再拷源码；如果只想加速依赖下载，可在这里先 `mvn dependency:go-offline`
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/insightkb.jar /app/app.jar
# 应用默认监听 8080（见 application.yml 的 server.port），由 docker-compose 映射到宿主机
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
