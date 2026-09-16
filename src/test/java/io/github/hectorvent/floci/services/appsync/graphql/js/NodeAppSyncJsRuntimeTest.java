package io.github.hectorvent.floci.services.appsync.graphql.js;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The pre-configured URL path: a server someone else is already running, with no container
 * management at all. Same contract as {@code floci.services.duck.url}.
 */
class NodeAppSyncJsRuntimeTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
    private final ContainerDetector containerDetector = mock(ContainerDetector.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EmulatorConfig.JsRuntimeConfig jsRuntime = mock(EmulatorConfig.JsRuntimeConfig.class);

    private HttpServer server;
    private final List<String> requestedPaths = new ArrayList<>();
    private NodeAppSyncJsRuntime runtime;

    @BeforeEach
    void startStubRuntime() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            byte[] body = ("/health".equals(exchange.getRequestURI().getPath())
                    ? "{\"ok\":true,\"runtime\":\"stub\"}"
                    : "{\"ok\":true,\"result\":\"from-the-stub\",\"stash\":{},\"earlyReturn\":false,\"errors\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AppSyncServiceConfig appsync = mock(EmulatorConfig.AppSyncServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.appsync()).thenReturn(appsync);
        when(appsync.jsRuntime()).thenReturn(jsRuntime);
        when(jsRuntime.enabled()).thenReturn(true);
        when(jsRuntime.enforceAppsyncSubset()).thenReturn(true);
        when(jsRuntime.evaluationTimeoutSeconds()).thenReturn(5);
        when(jsRuntime.url()).thenReturn(
                Optional.of("http://127.0.0.1:" + server.getAddress().getPort()));

        runtime = new NodeAppSyncJsRuntime(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, mock(RegionResolver.class), new ObjectMapper());
    }

    @AfterEach
    void stopStubRuntime() {
        server.stop(0);
    }

    @Test
    void aPreConfiguredUrlIsUsedWithoutTouchingDocker() {
        JsEvaluation evaluation = runtime.evaluate("export function request() { return {}; }",
                "request", Map.of("arguments", Map.of()));

        assertEquals("from-the-stub", evaluation.result());
        // The point of the knob: no image pull, no container created, adopted or inspected.
        verifyNoInteractions(containerBuilder);
        verifyNoInteractions(lifecycleManager);
        verifyNoInteractions(logStreamer);
        assertTrue(requestedPaths.contains("/health"), "the configured URL is probed before use");
        assertTrue(requestedPaths.contains("/evaluate"), "the evaluation goes to the configured URL");
    }

    @Test
    void stoppingManagedContainersLeavesAPreConfiguredServerAlone() {
        runtime.evaluate("export function request() { return {}; }", "request", Map.of());

        runtime.stopManagedContainers();

        // Not ours to stop: nothing was started, so nothing is removed.
        verifyNoInteractions(lifecycleManager);
    }
}
