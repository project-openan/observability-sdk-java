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

import net.openan.observability.internal.Definitions;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentCoverageTest {
    @Test void exportsAgentSubstepTokenUsageAndLifecycleSignals() {
        var spans = InMemorySpanExporter.create();
        var logs = InMemoryLogRecordExporter.create();
        var metrics = InMemoryMetricReader.create();
        try (var sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build()).build()) {
            var observe = new Observability(sdk);
            var substep = Map.of("gen_ai.agent.name", "diagnosis-agent", "substep.name", "retrieve-alarms", "substep.status", "active");
            var span = observe.trace("substep.retrieve-alarms", SignalKind.INTERNAL, substep);
            observe.log("agent", LogSeverity.INFO, "agent started", Map.of(
                "gen_ai.agent.name", "diagnosis-agent", "agent.role", "diagnosis", "agent.skills", List.of("retrieve", "reason"), "agent.state", "running"), span);
            observe.log("substep", LogSeverity.INFO, "substep started", Map.of(
                "gen_ai.agent.name", "diagnosis-agent", "substep.name", "retrieve-alarms", "substep.status", "active", "workflow.step.name", "diagnosis", "workflow.step.id", "step-1"), span);
            observe.metric("substep.duration", 8, Map.of("substep.status", "success", "gen_ai.agent.name", "diagnosis-agent"));
            observe.metric("substep.count", 1, Map.of("substep.status", "success", "gen_ai.agent.name", "diagnosis-agent"));
            observe.metric("gen_ai.client.inference.usage.input_tokens", 120, Map.of("gen_ai.operation.name", "chat", "gen_ai.provider.name", "openai",
                "gen_ai.token.modality", "text", "gen_ai.agent.name", "diagnosis-agent", "gen_ai.request.model", "model-1"));
            observe.metric("gen_ai.client.inference.usage.output_tokens", 30, Map.of("gen_ai.operation.name", "chat", "gen_ai.provider.name", "openai",
                "gen_ai.token.modality", "text", "gen_ai.agent.name", "diagnosis-agent", "gen_ai.request.model", "model-1"));
            span.end();

            assertEquals(1, spans.getFinishedSpanItems().size());
            assertEquals("substep.retrieve-alarms", spans.getFinishedSpanItems().get(0).getName());
            assertEquals(List.of("retrieve", "reason"), logs.getFinishedLogRecordItems().get(0).getAttributes()
                .get(AttributeKey.stringArrayKey(Definitions.PREFIX + "agent.skills")));
            assertEquals(4, metrics.collectAllMetrics().size());
            assertTrue(metrics.collectAllMetrics().stream().anyMatch(metric -> metric.getName().equals("gen_ai.client.inference.usage.input_tokens")));
        }
    }

    @Test void rejectsCrossSignalAndInvalidTypeAttributes() {
        var spans = InMemorySpanExporter.create();
        var metrics = InMemoryMetricReader.create();
        var logs = InMemoryLogRecordExporter.create();
        try (var sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build()).build()) {
            var observe = new Observability(sdk);
            observe.trace("substep.work", SignalKind.INTERNAL, Map.of("gen_ai.agent.name", "a", "substep.name", "work", "substep.status", "custom", "workflow.id", "wf-1")).end();
            observe.metric("gen_ai.client.inference.usage.input_tokens", 1, Map.of("gen_ai.operation.name", "chat", "gen_ai.provider.name", "openai", "gen_ai.token.modality", 1));
            observe.log("agent", LogSeverity.INFO, "invalid", Map.of("gen_ai.agent.name", "a", "agent.state", "running", "workflow.id", "wf-1"));
            assertTrue(spans.getFinishedSpanItems().isEmpty());
            assertTrue(metrics.collectAllMetrics().isEmpty());
            assertTrue(logs.getFinishedLogRecordItems().isEmpty());
        }
    }
}
