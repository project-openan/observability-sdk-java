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

import net.openan.observability.internal.AttributesConverter;
import net.openan.observability.internal.Definitions;

import static org.junit.jupiter.api.Assertions.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ObservabilityTest {
    final InMemorySpanExporter spans = InMemorySpanExporter.create();
    final InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
    final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
        .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build()).build();
    final Observability observe = new Observability(sdk);
    @AfterEach void close() { sdk.close(); }

    @Test void taskSignalsAreConvertedAndLinked() {
        Map<String, String> attrs = Map.of("extension.name", "TASK-T", "task.type", "diagnosis", "task.object", "demo");
        ObservationSpan span = observe.trace("SendMessage", SignalKind.CLIENT, attrs);
        observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "token=abc https://u:p@example.com/path?token=x", attrs, span);
        observe.metric("task.duration", 42000, Map.of("task.type", "diagnosis", "task.object", "demo"), span);
        observe.metric("task.count", 1, Map.of("task.type", "diagnosis", "task.status", "completed"), span);
        span.ok(); span.end(); span.end();
        var data = spans.getFinishedSpanItems().get(0);
        assertEquals(1, spans.getFinishedSpanItems().size());
        assertEquals("task-t", data.getAttributes().get(AttributeKey.stringKey(Definitions.PREFIX + "extension.name")));
        var log = logs.getFinishedLogRecordItems().get(0);
        assertEquals("gen_ai.agent.a2at.message", log.getEventName());
        assertEquals(data.getTraceId(), log.getSpanContext().getTraceId());
        assertEquals(data.getSpanId(), log.getSpanContext().getSpanId());
        assertFalse(log.getBody().asString().contains("abc"));
        assertFalse(log.getBody().asString().contains("?token"));
        assertFalse(log.getBody().asString().contains("u:p"));
        MetricData duration = metric("task.duration");
        assertEquals("ms", duration.getUnit());
        var point = duration.getHistogramData().getPoints().iterator().next();
        assertEquals(1, point.getCount()); assertEquals(42000, point.getSum());
        var exemplar = point.getExemplars().get(0);
        assertEquals(data.getTraceId(), exemplar.getSpanContext().getTraceId());
        assertEquals(data.getSpanId(), exemplar.getSpanContext().getSpanId());
        var countPoint = metric("task.count").getLongSumData().getPoints().iterator().next();
        assertEquals(1, countPoint.getValue());
        assertEquals("completed", countPoint.getAttributes().get(AttributeKey.stringKey(Definitions.PREFIX + "task.status")));
    }
    @Test void bundledSignalConfigurationHasVersionsAndMetricAttributeRules() {
        assertEquals("1.0.0", Definitions.CONFIGURATION_VERSIONS.get("a2at"));
        assertEquals("1.0.0", Definitions.CONFIGURATION_VERSIONS.get("agent"));
        assertEquals("1.0.0", Definitions.CONFIGURATION_VERSIONS.get("workflow"));
        observe.metric("task.duration", 1, Map.of("task.type", "diagnosis", "task.object", "incident"));
        observe.metric("task.duration", 1, Map.of("task.type", "diagnosis", "notification.topic", "wrong-extension"));
        var points = metric("task.duration").getHistogramData().getPoints();
        assertEquals(1, points.size());
        assertEquals("incident", points.iterator().next().getAttributes().get(AttributeKey.stringKey(Definitions.PREFIX + "task.object")));
        observe.metric("gen_ai.client.inference.usage.input_tokens", 10, Map.of(
            "gen_ai.operation.name", "chat", "gen_ai.provider.name", "openai", "gen_ai.token.modality", "text"));
        observe.metric("gen_ai.client.inference.usage.input_tokens", 20, Map.of(
            "gen_ai.operation.name", "chat", "gen_ai.provider.name", "openai", "gen_ai.token.modality", "text",
            "gen_ai.agent.name", "diagnosis-agent", "gen_ai.request.model", "model-1"));
        observe.metric("gen_ai.client.inference.usage.input_tokens", 30, Map.of(
            "gen_ai.operation.name", "chat", "gen_ai.provider.name", "openai", "gen_ai.token.modality", "text", "workflow.type", "fault-diagnosis"));
        var tokenPoints = metric("gen_ai.client.inference.usage.input_tokens").getLongSumData().getPoints();
        assertEquals(2, tokenPoints.size());
    }
    @Test void yamlDefinesAttributeTypesNamesAndExtensionValues() {
        var extension = Definitions.attribute("extension.name");
        assertEquals("gen_ai.agent.a2at.extension.name", extension.name());
        assertEquals(Definitions.AttributeType.STRING, extension.type());
        assertEquals(Set.of("task-t", "negotiation-t", "notification-t", "authorization-t"), extension.values());
        var stepId = Definitions.attribute("workflow.step.id");
        assertEquals(Definitions.AttributeType.STRING, stepId.type());
        assertEquals("gen_ai.agent.a2at.workflow.step.id", stepId.name());
        assertEquals(Definitions.AttributeType.STRING_ARRAY, Definitions.attribute("agent.skills").type());
        assertTrue(Definitions.attribute("workflow.status").values().isEmpty());
        assertEquals("gen_ai.agent.a2a.operation.name", Definitions.attribute("operation.name").name());
        assertEquals("SM", Definitions.attribute("operation.name").signals());
        assertEquals("gen_ai.agent.a2at.task.status", Definitions.attribute("task.status").name());
        assertEquals("gen_ai.agent.a2at.notification.result", Definitions.attribute("notification.result").name());
        assertEquals("gen_ai.agent.a2at.authorization.result", Definitions.attribute("authorization.result").name());
    }
    @Test void allConfiguredMetricsUseSpecifiedTypesUnitsAndDimensions() {
        Definitions.METRICS.forEach((name, definition) -> {
            Map<String, Object> attrs = new HashMap<>();
            definition.dimensions().forEach(dimension -> attrs.put(dimension, metricValue(dimension)));
            observe.metric(name, 2, attrs);
        });
        assertEquals(Definitions.METRICS.size(), metrics.collectAllMetrics().size());
        Definitions.METRICS.forEach((name, definition) -> {
            MetricData data = metric(name);
            assertEquals(definition.unit(), data.getUnit());
            if (definition.histogram()) assertEquals(2, data.getHistogramData().getPoints().iterator().next().getSum());
            else assertEquals(2, data.getLongSumData().getPoints().iterator().next().getValue());
        });
    }
    private static Object metricValue(String dimension) {
        return switch (dimension) {
            case "operation.name" -> "SendMessage";
            case "gen_ai.system" -> "a2a";
            case "negotiation.result" -> "accept";
            case "workflow.status" -> "completed";
            case "workflow.step.status" -> "done";
            default -> "test";
        };
    }
    @Test void invalidInputsAreDiscardedWithoutThrowing() {
        Map<String, Object> duplicate = new HashMap<>(); duplicate.put("task.type", "x"); duplicate.put(Definitions.PREFIX + "task.type", "y");
        observe.metric("task.duration", 1, Map.of("task.object", "x"));
        observe.metric("task.count", -1, Map.of());
        observe.metric("task.count", 1.5, Map.of());
        observe.metric("negotiation.rounds", 1.5, Map.of());
        observe.metric("task.duration", Double.NaN, Map.of());
        observe.metric("task.duration", 1, duplicate);
        observe.trace("test", SignalKind.CLIENT, Map.of("negotiation.max_rounds", 1)).end();
        observe.log("test", LogSeverity.INFO, "body", Map.of("negotiation.round", "1"));
        observe.log("test", LogSeverity.INFO, "body", Map.of("negotiation.result", "unknown"));
        assertTrue(metrics.collectAllMetrics().isEmpty());
        assertTrue(spans.getFinishedSpanItems().isEmpty()); assertTrue(logs.getFinishedLogRecordItems().isEmpty());
    }
    @Test void attributesAreValidatedAndSensitiveValuesAreCleaned() {
        var converted = AttributesConverter.convert(Map.of("push.notification.url", "https://user:password@example.com/path?token=123", "negotiation.round", 2), 'L', null);
        assertEquals("https://example.com/path", converted.get(AttributeKey.stringKey(Definitions.PREFIX + "push.notification.url")));
        assertEquals(2L, converted.get(AttributeKey.longKey(Definitions.PREFIX + "negotiation.round")));
        assertEquals(2048, AttributesConverter.clean("x".repeat(3000)).length());
        assertFalse(AttributesConverter.clean("Authorization: Bearer abc123").contains("abc123"));
        assertFalse(AttributesConverter.clean("{\"token\":\"abc123\"}").contains("abc123"));
        assertThrows(IllegalArgumentException.class, () -> AttributesConverter.convert(Map.of("unknown", "x"), 'S', null));
        assertThrows(IllegalArgumentException.class, () -> AttributesConverter.convert(Map.of("task_id", "task-1"), 'M', null));
        assertEquals("x", AttributesConverter.convert(Map.of("task.object", "x"), 'M', Set.of("task.object"))
            .get(AttributeKey.stringKey(Definitions.PREFIX + "task.object")));
    }
    @Test void acceptsCustomValuesForNonExtensionAttributes() {
        var metrics = AttributesConverter.convert(Map.of(
            "negotiation.result", "counter-offer", "workflow.status", "paused",
            "agent.state", "draining", "gen_ai.token.modality", "cached"), 'M', null);
        var logs = AttributesConverter.convert(Map.of("authorization.stage", "manual-review", "substep.status", "waiting"), 'L', null);
        assertEquals("counter-offer", metrics.get(AttributeKey.stringKey(Definitions.PREFIX + "negotiation.result")));
        assertEquals("paused", metrics.get(AttributeKey.stringKey(Definitions.PREFIX + "workflow.status")));
        assertEquals("draining", metrics.get(AttributeKey.stringKey(Definitions.PREFIX + "agent.state")));
        assertEquals("cached", metrics.get(AttributeKey.stringKey("gen_ai.token.modality")));
        assertEquals("manual-review", logs.get(AttributeKey.stringKey(Definitions.PREFIX + "authorization.stage")));
        assertEquals("waiting", logs.get(AttributeKey.stringKey(Definitions.PREFIX + "substep.status")));
    }
    @Test void explicitAndCurrentContextCreateParentChild() {
        ObservationSpan parent = observe.trace("SendMessage", SignalKind.SERVER, task());
        ObservationSpan child = observe.trace("SendMessage", SignalKind.CLIENT, task(), parent);
        child.end(); parent.error(); parent.end();
        var childData = spans.getFinishedSpanItems().get(0); var parentData = spans.getFinishedSpanItems().get(1);
        assertEquals(parentData.getSpanId(), childData.getParentSpanId());
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, parentData.getStatus().getStatusCode());
    }
    private static Map<String, String> task() { return Map.of("extension.name", "task-t", "task.type", "diagnosis"); }
    @Test void whitelistRequiredFieldsAndAtomicUpdates() {
        observe.trace("unknown", SignalKind.CLIENT, task()).end();
        observe.trace("SendMessage", SignalKind.INTERNAL, task()).end();
        observe.trace("SendMessage", SignalKind.CLIENT, Map.of("extension.name", "task-t")).end();
        observe.trace("SendMessage", SignalKind.CLIENT, Map.of("extension.name", "negotiation-t", "task.type", "x")).end();
        observe.trace("SendMessage", SignalKind.CLIENT, Map.of("extension.name", "task-t", "task.type", " ")).end();
        observe.trace("SendMessage", SignalKind.CLIENT, Map.of("extension.name", "task-t", "task.type", "x", "negotiation.id", "x")).end();
        observe.log("unknown", LogSeverity.INFO, "body", task());
        observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, null, task());
        observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "body", Map.of("extension.name", "task-t"));
        observe.metric("unknown", 1, task());
        observe.metric("task.count", 1, Map.of());
        observe.metric("task.count", 999, Map.of("task.type", "x", "extension.name", "task-t"));
        assertTrue(spans.getFinishedSpanItems().isEmpty());
        assertTrue(logs.getFinishedLogRecordItems().isEmpty());
        assertTrue(metrics.collectAllMetrics().isEmpty());
        var span = observe.trace("SendMessage", SignalKind.CLIENT, task());
        span.attributes(Map.of("task.object", "before"));
        span.attributes(Map.of("task.object", "after", "negotiation.id", "invalid"));
        span.attributes(Map.of("extension.name", "negotiation-t"));
        span.end();
        assertEquals("before", spans.getFinishedSpanItems().get(0).getAttributes().get(AttributeKey.stringKey(Definitions.PREFIX + "task.object")));
    }
    @Test void rejectedHandlePreservesCurrentContext() {
        var parent = observe.trace("SendMessage", SignalKind.SERVER, task());
        try (var scope = parent.makeCurrent()) {
            var rejected = observe.trace("unknown", SignalKind.CLIENT, task());
            try (var ignored = rejected.makeCurrent()) {
                observe.trace("SendMessage", SignalKind.CLIENT, task()).end();
                observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "body", task(), rejected);
            }
            observe.trace("SendMessage", SignalKind.CLIENT, task(), rejected).end();
        }
        parent.end();
        var items = spans.getFinishedSpanItems();
        var parentData = items.get(2);
        assertEquals(parentData.getSpanId(), items.get(0).getParentSpanId());
        assertEquals(parentData.getSpanId(), items.get(1).getParentSpanId());
        assertEquals(parentData.getSpanId(), logs.getFinishedLogRecordItems().get(0).getSpanContext().getSpanId());
    }
    @Test void aliasesAggregationAndConversion() {
        var full = Map.of(Definitions.PREFIX + "extension.name", "TASK-T", Definitions.PREFIX + "task.type", "diagnosis", Definitions.PREFIX + "task.object", "token=abc");
        var span = observe.trace("SendMessage", SignalKind.CLIENT, full);
        observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "body", full, span);
        observe.metric(Definitions.PREFIX + "task.duration", 2.5, Map.of(Definitions.PREFIX + "task.type", "diagnosis"));
        observe.metric("task.duration", 3.5, Map.of("task.type", "diagnosis"));
        span.ok(); span.end();
        var data = spans.getFinishedSpanItems().get(0);
        assertEquals(io.opentelemetry.api.trace.SpanKind.CLIENT, data.getKind());
        assertEquals(io.opentelemetry.api.trace.StatusCode.OK, data.getStatus().getStatusCode());
        assertFalse(data.getAttributes().get(AttributeKey.stringKey(Definitions.PREFIX + "task.object")).contains("abc"));
        assertEquals(io.opentelemetry.api.logs.Severity.INFO, logs.getFinishedLogRecordItems().get(0).getSeverity());
        var point = metric("task.duration").getHistogramData().getPoints().iterator().next();
        assertEquals(2, point.getCount()); assertEquals(6, point.getSum());
    }
    private MetricData metric(String name) {
        return metrics.collectAllMetrics().stream()
            .filter(metric -> metric.getName().equals(Definitions.METRICS.get(Definitions.shortName(name)).name()))
            .findFirst().orElseThrow();
    }
}
