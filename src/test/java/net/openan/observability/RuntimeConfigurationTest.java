/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package net.openan.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;

class RuntimeConfigurationTest {
    @TempDir Path tempDir;

    @Test
    void disableSkipsNewSignalsAndEnableResumesRecording() throws Exception {
        try (Receiver receiver = Receiver.start()) {
            var config = config(receiver.endpoint()).enabled(false).build();
            try (var sdk = ObservabilitySdk.start(config)) {
                record(sdk);
                assertTrue(sdk.flush(3, TimeUnit.SECONDS));
                assertTrue(receiver.paths.isEmpty());
                sdk.setEnabled(true);
                record(sdk);
                assertTrue(sdk.flush(5, TimeUnit.SECONDS));
            }
            assertTrue(receiver.paths.contains("/v1/traces"));
            assertTrue(receiver.paths.contains("/v1/metrics"));
            assertTrue(receiver.paths.contains("/v1/logs"));
        }
    }

    @Test
    void updateExportersRoutesSubsequentBatchesToNewEndpoint() throws Exception {
        try (Receiver first = Receiver.start(); Receiver second = Receiver.start()) {
            try (var sdk = ObservabilitySdk.start(config(first.endpoint()).header("x-target", "first").build())) {
                record(sdk);
                assertTrue(sdk.flush(5, TimeUnit.SECONDS));
                var update = sdk.updateExporters(config(second.endpoint()).header("x-target", "second").build());
                update.join(5, TimeUnit.SECONDS);
                assertTrue(update.isSuccess());
                record(sdk);
                assertTrue(sdk.flush(5, TimeUnit.SECONDS));
            }
            assertTrue(first.headers.stream().allMatch("first"::equals));
            assertTrue(second.headers.stream().allMatch("second"::equals));
            assertTrue(second.paths.contains("/v1/traces"));
            assertTrue(second.paths.contains("/v1/metrics"));
            assertTrue(second.paths.contains("/v1/logs"));
        }
    }

    @Test
    void rejectsIncompleteMutualTlsAndInvalidBatchSettings() {
        assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder()
            .endpoint("https://localhost:4318").clientCertificate(tempDir.resolve("client.pem")).build());
        assertThrows(IllegalArgumentException.class, () -> BatchConfig.builder()
            .maxQueueSize(10).maxExportBatchSize(11).build());
        assertThrows(IllegalArgumentException.class, () -> BatchConfig.builder()
            .scheduleDelay(Duration.ofMillis(-1)).build());
    }

    @Test
    void rejectsInvalidMutualTlsFiles() throws Exception {
        Path certificate = Files.writeString(tempDir.resolve("client.pem"), "not a certificate");
        Path privateKey = Files.writeString(tempDir.resolve("client-key.pem"), "not a private key");
        assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder()
            .endpoint("https://localhost:4318").clientCertificate(certificate).clientPrivateKey(privateKey).build());
    }

    private SdkConfig.Builder config(String endpoint) {
        return SdkConfig.builder().endpoint(endpoint).protocol("http/protobuf").serviceName("runtime-test")
            .metricInterval(Duration.ofHours(1)).exportTimeout(Duration.ofSeconds(2));
    }

    private static void record(ObservabilitySdk sdk) {
        var attributes = Map.<String, Object>of("workflow.id", "wf", "workflow.type", "test", "workflow.status", "ok");
        try (var span = sdk.observe().trace("workflow", SignalKind.INTERNAL, attributes)) {
            sdk.observe().log("workflow", LogSeverity.INFO, "recorded", attributes, span);
            sdk.observe().metric("workflow.count", 1, Map.of("workflow.type", "test", "workflow.status", "ok"));
            span.ok();
        }
    }

    private static final class Receiver implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentLinkedQueue<String> paths = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> headers = new ConcurrentLinkedQueue<>();

        private Receiver(HttpServer server) { this.server = server; }

        static Receiver start() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            Receiver receiver = new Receiver(server);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                receiver.paths.add(exchange.getRequestURI().getPath());
                String target = exchange.getRequestHeaders().getFirst("x-target");
                receiver.headers.add(target == null ? "" : target);
                exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return receiver;
        }

        String endpoint() { return "http://localhost:" + server.getAddress().getPort(); }

        @Override public void close() { server.stop(0); }
    }
}
