# Built from build/dist, which `gradlew packageDistribution` produces. Run `gradlew dockerBuild`
# rather than `docker build` directly, so the jar in the context is always the current one.
FROM eclipse-temurin:21-jre-noble

# glibc rather than musl: the proxy's HTTP stack and DNS behave exactly as they do on the platforms
# it is tested on, and a long-running single-user container has no use for the ~60 MB alpine saves.

# Set explicitly: the base image supplies its own org.opencontainers.image.version (Ubuntu's, "24.04"),
# which is inherited and would otherwise be published as though it were ours.
ARG VERSION=dev

LABEL org.opencontainers.image.title="mcp-proxy" \
      org.opencontainers.image.description="Identity-stripping MCP proxy" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.source="https://github.com/vdenisov/mcp-proxy"

RUN useradd --system --uid 10001 --create-home mcpproxy \
    && mkdir -p /data \
    && chown mcpproxy:mcpproxy /data

COPY build/dist/mcp-proxy.jar /opt/mcp-proxy/mcp-proxy.jar

# Configs, tokens, registrations and logs all live here - mount it, or a restart loses the tokens
# and the next login registers a brand new OAuth client.
ENV MCP_PROXY_HOME=/data
VOLUME /data

USER mcpproxy

# No EXPOSE: the port comes from server.yaml, and a hardcoded one here would be documentation that
# lies. Publish whatever you configured, to 127.0.0.1.
#
# Exec form, java directly rather than the launcher script: the JVM is then PID 1, so `docker stop`'s
# SIGTERM reaches its shutdown hook and sessions close gracefully. A shell wrapper would swallow it.
ENTRYPOINT ["java", "-jar", "/opt/mcp-proxy/mcp-proxy.jar"]
CMD ["--serve", "server"]
