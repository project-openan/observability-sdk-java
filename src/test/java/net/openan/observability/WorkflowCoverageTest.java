/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
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

import net.openan.observability.internal.Definitions;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowCoverageTest {
    private static final String P = Definitions.PREFIX;
    private static final String INVALID_PARENT_SPAN_ID = "0000000000000000";

    @Test void exportsWorkflowTraceMetricsAndLogsWithConfiguredAttributeSets() {
        var spans = InMemorySpanExporter.create();
        var logs = InMemoryLogRecordExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build()).build()) {
            var observe = new Observability(sdk);
            var workflow = Map.of("workflow.id", "wf-1", "workflow.type", "fault-diagnosis", "workflow.status", "running", "workflow.session_id", "s-1");
            var step = Map.of("workflow.step.name", "collect-alarm", "workflow.step.id", "step-1", "workflow.step.status", "active", "workflow.step.owner_agent", "alarm-agent");
            var workflowSpan = observe.trace("workflow", SignalKind.INTERNAL, workflow);
            var stepSpan = observe.trace("step.collect-alarm", SignalKind.INTERNAL, step, workflowSpan);
            observe.log("workflow", LogSeverity.INFO, "workflow started",
                Map.of("workflow.id", "wf-1", "workflow.type", "fault-diagnosis", "workflow.status", "running", "business.domain", "transport", "business.customer", "customer-1"), workflowSpan);
            observe.log("step", LogSeverity.INFO, "step started", step, stepSpan);
            observe.metric("workflow.duration", 42, Map.of("workflow.type", "fault-diagnosis", "workflow.status", "completed"));
            observe.metric("workflow.count", 1, Map.of("workflow.type", "fault-diagnosis", "workflow.status", "completed"));
            observe.metric("step.duration", 5, Map.of("workflow.step.status", "success", "gen_ai.agent.name", "alarm-agent"));
            observe.metric("step.count", 1, Map.of("workflow.step.status", "success", "gen_ai.agent.name", "alarm-agent"));
            stepSpan.end(); workflowSpan.end();

            assertEquals(2, spans.getFinishedSpanItems().size());
            assertEquals("workflow", spans.getFinishedSpanItems().get(1).getName());
            assertEquals("wf-1", spans.getFinishedSpanItems().get(1).getAttributes().get(AttributeKey.stringKey(P + "workflow.id")));
            assertEquals(2, logs.getFinishedLogRecordItems().size());
            assertEquals("transport", logs.getFinishedLogRecordItems().get(0).getAttributes().get(AttributeKey.stringKey(P + "business.domain")));
            assertEquals(4, metrics.collectAllMetrics().size());
        }
    }

    @Test void rejectsWorkflowNamesKindsAndCrossSignalAttributes() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        var logs = InMemoryLogRecordExporter.create();
        try (var sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build()).build()) {
            var observe = new Observability(sdk);
            observe.trace("workflow", SignalKind.CLIENT, Map.of("workflow.id", "wf-1", "workflow.type", "diagnosis", "workflow.status", "running")).end();
            observe.trace("step.", SignalKind.INTERNAL, Map.of("workflow.step.name", "s", "workflow.step.id", "s-1", "workflow.step.status", "active")).end();
            observe.metric("workflow.duration", 1, Map.of("workflow.type", "diagnosis", "business.domain", "transport"));
            observe.log("workflow", LogSeverity.INFO, "invalid", Map.of("workflow.id", "wf-1", "workflow.type", "diagnosis", "workflow.status", "running", "agent.state", "running"));
            assertTrue(spans.getFinishedSpanItems().isEmpty());
            assertTrue(metrics.collectAllMetrics().isEmpty());
            assertTrue(logs.getFinishedLogRecordItems().isEmpty());
        }
    }

    @Test void modelsWorkflowAsTheRootOfAnAgentA2AOperationTrace() {
        var spans = InMemorySpanExporter.create();
        try (var sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans)).build()).build()) {
            var observe = new Observability(sdk);
            var workflow = observe.trace("workflow", SignalKind.INTERNAL, Map.of(
                "workflow.id", "wf-1", "workflow.type", "fault-diagnosis", "workflow.status", "running"));
            var step = observe.trace("step.collect", SignalKind.INTERNAL, Map.of(
                "workflow.step.name", "collect", "workflow.step.id", "step-1", "workflow.step.status", "running"), workflow);
            var agent = observe.trace("substep.analyze", SignalKind.INTERNAL, Map.of(
                "gen_ai.agent.name", "diagnosis-agent", "substep.name", "analyze", "substep.status", "running"), step);
            var client = observe.trace("SendMessage", SignalKind.CLIENT, Map.of(
                "extension.name", "task-t", "task.type", "diagnosis", "task.object", "fault"), agent);
            var server = observe.trace("SendMessage", SignalKind.SERVER, Map.of(
                "extension.name", "task-t", "task.type", "diagnosis", "task.object", "fault"), client);
            server.end(); client.end(); agent.end(); step.end(); workflow.end();

            var exported = spans.getFinishedSpanItems();
            var workflowData = find(exported, "workflow", SpanKind.INTERNAL);
            var stepData = find(exported, "step.collect", SpanKind.INTERNAL);
            var agentData = find(exported, "substep.analyze", SpanKind.INTERNAL);
            var clientData = find(exported, "SendMessage", SpanKind.CLIENT);
            var serverData = find(exported, "SendMessage", SpanKind.SERVER);
            assertEquals(INVALID_PARENT_SPAN_ID, workflowData.getParentSpanId());
            assertChild(workflowData, stepData);
            assertChild(stepData, agentData);
            assertChild(agentData, clientData);
            assertChild(clientData, serverData);
        }
    }

    private static io.opentelemetry.sdk.trace.data.SpanData find(
            java.util.List<io.opentelemetry.sdk.trace.data.SpanData> spans, String name, SpanKind kind) {
        return spans.stream().filter(span -> name.equals(span.getName()) && kind == span.getKind())
            .findFirst().orElseThrow();
    }

    private static void assertChild(io.opentelemetry.sdk.trace.data.SpanData parent,
            io.opentelemetry.sdk.trace.data.SpanData child) {
        assertEquals(parent.getSpanId(), child.getParentSpanId());
        assertEquals(parent.getTraceId(), child.getTraceId());
    }
}
