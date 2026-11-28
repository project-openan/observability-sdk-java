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

package net.openan.observability.consumer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.openan.observability.LogSeverity;
import net.openan.observability.ObservationSpan;
import net.openan.observability.Observability;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

/** Independent consumer that emits every configured signal family as correlated flows. */
public final class ConsumerAllSignals {
    private static final String MESSAGE_LOG = "gen_ai.agent.a2at.message";

    private ConsumerAllSignals() { }

    public static void main(String[] args) {
        String runId = args.length == 0 ? "local" : args[0];
        String serviceName = "sdk-consumer-all-" + runId;
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName(serviceName).build())) {
            emitWorkflowScenarios(sdk.observe(), runId);
            if (!sdk.flush(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OTLP export failed or flush timed out");
            }
        }
        System.out.println("All configured signals emitted for " + serviceName);
    }

    private static void emitWorkflowScenarios(Observability observe, String runId) {
        emitExecutionWorkflow(observe, runId);
        emitTaskManagementWorkflow(observe, runId);
        emitPushConfigurationWorkflow(observe, runId);
    }

    private static void emitExecutionWorkflow(Observability observe, String runId) {
        emitWorkflow(observe, runId, "execution", "a2a-execution", "completed", "dispatch", 1850.0, 1620.0,
            1400.0, agent -> {
                emitTrace(observe, "GetExtendedAgentCard", SignalKind.CLIENT,
                    a2aAttributes("GetExtendedAgentCard", "execution", runId), agent);
                emitAuthorizationFlow(observe, runId, agent);
                emitTaskFlow(observe, runId, agent);
                emitStreamingFlow(observe, runId, agent);
                emitNegotiationFlow(observe, runId, agent);
                emitNotificationFlow(observe, runId, agent);
            });
    }

    private static void emitTaskManagementWorkflow(Observability observe, String runId) {
        emitWorkflow(observe, runId, "management", "task-management", "cancelled", "manage-task", 520.0, 430.0,
            360.0, agent -> {
                emitOperationPair(observe, "ListTasks", "management", runId, agent);
                emitOperationPair(observe, "GetTask", "management", runId, agent);
                emitOperationPair(observe, "SubscribeToTask", "management", runId, agent);
                emitOperationPair(observe, "CancelTask", "management", runId, agent);
            });
    }

    private static void emitPushConfigurationWorkflow(Observability observe, String runId) {
        emitWorkflow(observe, runId, "push", "push-notification-configuration", "completed", "manage-push", 460.0,
            380.0, 310.0, agent -> {
                emitOperationPair(observe, "CreateTaskPushNotificationConfig", "push", runId, agent);
                emitOperationPair(observe, "GetTaskPushNotificationConfig", "push", runId, agent);
                emitOperationPair(observe, "ListTaskPushNotificationConfigs", "push", runId, agent);
                emitOperationPair(observe, "DeleteTaskPushNotificationConfig", "push", runId, agent);
                emitTrace(observe, "SendPushNotification", SignalKind.CLIENT,
                    merge(a2aAttributes("SendPushNotification", "push", runId), Map.of(
                        "config_id", "config-push-" + runId, "push_notification.event_kind", "task-status")), agent);
            });
    }

    private static void emitWorkflow(Observability observe, String runId, String scenario, String workflowType,
            String workflowStatus, String stepName, double workflowDuration, double stepDuration, double substepDuration,
            Consumer<ObservationSpan> agentWork) {
        Map<String, Object> workflow = Map.of(
            "workflow.id", "workflow-" + scenario + "-" + runId, "workflow.type", workflowType,
            "workflow.status", workflowStatus, "workflow.session_id", "session-" + scenario + "-" + runId);
        Map<String, Object> step = Map.of(
            "workflow.step.name", stepName, "workflow.step.id", "step-1", "workflow.step.status", workflowStatus,
            "workflow.step.owner_agent", "consumer-agent");
        try (var workflowSpan = observe.trace("a2at.workflow", SignalKind.INTERNAL, workflow)) {
            try (var workflowScope = workflowSpan.makeCurrent()) {
                observe.log("gen_ai.agent.a2at.workflow", LogSeverity.INFO, "Workflow started", Map.ofEntries(
                    Map.entry("workflow.id", "workflow-" + scenario + "-" + runId), Map.entry("workflow.type", workflowType),
                    Map.entry("workflow.status", "running"), Map.entry("workflow.session_id", "session-" + scenario + "-" + runId),
                    Map.entry("business.domain", "observability"), Map.entry("business.customer", "consumer")), workflowSpan);
                try (var stepSpan = observe.trace("a2at.step.collect", SignalKind.INTERNAL, step, workflowSpan)) {
                    try (var stepScope = stepSpan.makeCurrent()) {
                        observe.log("gen_ai.agent.a2at.step", LogSeverity.INFO, "Workflow step started", Map.of(
                            "workflow.step.name", stepName, "workflow.step.id", "step-1", "workflow.step.status", "running",
                            "workflow.step.owner_agent", "consumer-agent"), stepSpan);
                        emitAgentSignals(observe, runId, stepName, substepDuration, stepSpan, agentWork);
                        observe.metric("step.duration", stepDuration, Map.of(
                            "workflow.step.status", "completed".equals(workflowStatus) ? "success" : "failed", "gen_ai.agent.name", "consumer-agent"));
                        observe.log("gen_ai.agent.a2at.step", LogSeverity.INFO, "Workflow step " + workflowStatus, step, stepSpan);
                        stepSpan.ok();
                    }
                }
                observe.metric("workflow.duration", workflowDuration, Map.of("workflow.type", workflowType, "workflow.status", workflowStatus));
                observe.metric("workflow.count", 1, Map.of("workflow.type", workflowType, "workflow.status", workflowStatus));
                observe.log("gen_ai.agent.a2at.workflow", LogSeverity.INFO, "Workflow " + workflowStatus, Map.ofEntries(
                    Map.entry("workflow.id", "workflow-" + scenario + "-" + runId), Map.entry("workflow.type", workflowType),
                    Map.entry("workflow.status", workflowStatus), Map.entry("workflow.session_id", "session-" + scenario + "-" + runId),
                    Map.entry("business.domain", "observability"), Map.entry("business.customer", "consumer")), workflowSpan);
                workflowSpan.ok();
            }
        }
    }

    private static void emitTaskFlow(Observability observe, String runId, ObservationSpan parent) {
        Map<String, Object> task = merge(messageAttributes("SendMessage", "execution", runId, "task-t"), Map.of(
            "extension.name", "task-t", "task.type", "fault-diagnosis", "task.object", "alarm-cluster-17",
            "response", "accepted"));
        try (var client = observe.trace("SendMessage", SignalKind.CLIENT, task, parent)) {
            try (var clientScope = client.makeCurrent()) {
                observe.log(MESSAGE_LOG, LogSeverity.INFO, "Task request dispatched", Map.of(
                    "extension.name", "task-t", "task.type", "fault-diagnosis", "task.object", "alarm-cluster-17"), client);
                observe.metric("task.duration", 950.0, Map.of("task.type", "fault-diagnosis", "task.object", "alarm-cluster-17", "task.status", "completed"));
                observe.metric("task.count", 1, Map.of("task.type", "fault-diagnosis", "task.object", "alarm-cluster-17", "task.status", "completed"));
                observe.metric("gen_ai.client.operation.duration", 0.18, Map.of("operation.name", "SendMessage"));
                try (var server = observe.trace("SendMessage", SignalKind.SERVER, task, client)) {
                    try (var serverScope = server.makeCurrent()) {
                        server.ok();
                    }
                }
                client.ok();
            }
        }
    }

    private static void emitOperationPair(Observability observe, String operation, String scenario, String runId,
            ObservationSpan parent) {
        Map<String, Object> attributes = a2aAttributes(operation, scenario, runId);
        try (var client = observe.trace(operation, SignalKind.CLIENT, attributes, parent)) {
            try (var server = observe.trace(operation, SignalKind.SERVER, attributes, client)) {
                server.ok();
            }
            client.ok();
        }
    }

    private static void emitStreamingFlow(Observability observe, String runId, ObservationSpan parent) {
        Map<String, Object> streamAttributes = messageAttributes("SendStreamingMessage", "execution", runId, "task-t");
        try (var streamClient = observe.trace("SendStreamingMessage", SignalKind.CLIENT, streamAttributes, parent)) {
            try (var streamScope = streamClient.makeCurrent()) {
                observe.metric("gen_ai.agent.a2a.streaming.duration", 0.62, Map.of(
                    "operation.name", "SendStreamingMessage"));
                try (var streamServer = observe.trace("SendStreamingMessage", SignalKind.SERVER, streamAttributes, streamClient)) {
                    try (var negotiation = observe.trace("SendStreamingMessage-negotiation", SignalKind.SERVER, Map.of(
                        "extension.name", "negotiation-t", "negotiation.id", "negotiation-execution-" + runId,
                        "negotiation.round", 1, "negotiation.total_rounds", 3, "negotiation.max_rounds", 3,
                        "negotiation.result", "accepted", "negotiation.performative", "propose"), streamServer)) {
                        negotiation.ok();
                    }
                    streamServer.ok();
                }
                emitTrace(observe, "SendStreamingMessage-event", SignalKind.CLIENT, Map.ofEntries(
                    Map.entry("operation.name", "SendStreamingMessage"), Map.entry("task_id", "task-execution-" + runId),
                    Map.entry("context_id", "context-execution-" + runId), Map.entry("message_id", "stream-execution-" + runId),
                    Map.entry("streaming-event", "status-update"), Map.entry("task.status.state", "working"),
                    Map.entry("artifact.name", "diagnostic-report"), Map.entry("artifact.append", true),
                    Map.entry("artifact.last_chunk", false), Map.entry("usage.input_tokens", 680),
                    Map.entry("usage.output_tokens", 190)), streamClient);
                emitTrace(observe, "SendStreamingMessage-end", SignalKind.CLIENT, Map.of(
                    "operation.name", "SendStreamingMessage", "task_id", "task-execution-" + runId), streamClient);
                streamClient.ok();
            }
        }
    }

    private static void emitAgentSignals(Observability observe, String runId, String stepName,
            double substepDuration, ObservationSpan parent, Consumer<ObservationSpan> agentWork) {
        Map<String, Object> substep = Map.of(
            "gen_ai.agent.name", "consumer-agent", "substep.name", "analyze", "substep.status", "completed");
        try (var span = observe.trace("a2at.substep.analyze", SignalKind.INTERNAL, substep, parent)) {
            try (var scope = span.makeCurrent()) {
                observe.log("gen_ai.agent.a2at.agent", LogSeverity.INFO, "Agent started", Map.of(
                    "gen_ai.agent.name", "consumer-agent", "agent.role", "observer",
                    "agent.skills", List.of("analyze", "report"), "agent.state", "running"), span);
                observe.log("gen_ai.agent.a2at.substep", LogSeverity.INFO, "Agent substep started", Map.of(
                    "gen_ai.agent.name", "consumer-agent", "substep.name", "analyze", "substep.status", "running",
                    "workflow.step.name", stepName, "workflow.step.id", "step-1"), span);
                agentWork.accept(span);
                observe.metric("substep.duration", substepDuration, Map.of(
                    "substep.status", "success", "gen_ai.agent.name", "consumer-agent"));
                observe.metric("gen_ai.client.inference.usage.input_tokens", 680, Map.of(
                    "gen_ai.operation.name", "chat", "gen_ai.provider.name", "openai", "gen_ai.token.modality", "text",
                    "gen_ai.agent.name", "consumer-agent", "gen_ai.request.model", "model-1"));
                observe.metric("gen_ai.client.inference.usage.output_tokens", 190, Map.of(
                    "gen_ai.operation.name", "chat", "gen_ai.provider.name", "openai", "gen_ai.token.modality", "text",
                    "gen_ai.agent.name", "consumer-agent", "gen_ai.request.model", "model-1"));
                observe.log("gen_ai.agent.a2at.substep", LogSeverity.INFO, "Agent substep completed", Map.of(
                    "gen_ai.agent.name", "consumer-agent", "substep.name", "analyze", "substep.status", "completed",
                    "workflow.step.name", stepName, "workflow.step.id", "step-1"), span);
                observe.log("gen_ai.agent.a2at.agent", LogSeverity.INFO, "Agent completed", Map.of(
                    "gen_ai.agent.name", "consumer-agent", "agent.role", "observer",
                    "agent.skills", List.of("analyze", "report"), "agent.state", "completed"), span);
                span.ok();
            }
        }
    }

    private static void emitNegotiationFlow(Observability observe, String runId, ObservationSpan parent) {
        emitExtensionFlow(observe, runId, "execution", parent, "negotiation-t", Map.of(
            "extension.name", "negotiation-t", "negotiation.id", "negotiation-" + runId,
            "negotiation.round", 1, "negotiation.total_rounds", 3, "negotiation.result", "accepted",
            "negotiation.performative", "propose"), Map.of(
            "extension.name", "negotiation-t", "negotiation.id", "negotiation-" + runId,
            "negotiation.round", 1, "negotiation.total_rounds", 3, "negotiation.max_rounds", 3,
            "negotiation.result", "accepted", "negotiation.performative", "propose"), server -> {
                observe.metric("negotiation.rounds", 2, Map.of(
                    "negotiation.id", "negotiation-" + runId, "negotiation.result", "accepted"));
                observe.metric("negotiation.count", 1, Map.of("negotiation.result", "accepted"));
            });
    }

    private static void emitNotificationFlow(Observability observe, String runId, ObservationSpan parent) {
        emitExtensionFlow(observe, runId, "execution", parent, "notification-t", Map.of(
            "extension.name", "notification-t", "notification.topic", "diagnostic.completed",
            "push.notification.url", "https://example.com/notifications/diagnostic"), Map.of(
            "extension.name", "notification-t", "notification.topic", "diagnostic.completed",
            "push.notification.url", "https://example.com/notifications/diagnostic"), server ->
                observe.metric("notification.count", 1, Map.of("notification.topic", "diagnostic.completed", "notification.result", "sent")));
    }

    private static void emitAuthorizationFlow(Observability observe, String runId, ObservationSpan parent) {
        emitExtensionFlow(observe, runId, "execution", parent, "authorization-t", Map.of(
            "extension.name", "authorization-t", "authorization.stage", "execute",
            "authorization.policy.operation.type", "create"), Map.of(
            "extension.name", "authorization-t", "authorization.stage", "execute",
            "authorization.policy.operation.type", "create"),             server -> {
                observe.metric("authorization.preauthorize.count", 1, Map.of("authorization.policy.operation.type", "create", "authorization.result", "allowed"));
                observe.metric("authorization.preauthorize.duration", 45.0, Map.of("authorization.policy.operation.type", "create", "authorization.result", "allowed"));
                observe.metric("authorization.execute.count", 1, Map.of("authorization.policy.operation.type", "create", "authorization.result", "allowed"));
            });
    }

    private static void emitExtensionFlow(Observability observe, String runId, String scenario, ObservationSpan parent,
            String extension, Map<String, ?> spanAttributes, Map<String, ?> logAttributes, Consumer<ObservationSpan> serverWork) {
        Map<String, Object> attributes = merge(messageAttributes("SendMessage", scenario, runId, extension), spanAttributes);
        try (var span = observe.trace("SendMessage", SignalKind.CLIENT, attributes, parent)) {
            try (var scope = span.makeCurrent()) {
                observe.log(MESSAGE_LOG, LogSeverity.INFO, "Extension request " + extension, logAttributes, span);
                try (var server = observe.trace("SendMessage", SignalKind.SERVER, attributes, span)) {
                    try (var serverScope = server.makeCurrent()) {
                        serverWork.accept(server);
                        emitExtensionStream(observe, runId, scenario, spanAttributes, server);
                        server.ok();
                    }
                }
                span.ok();
            }
        }
    }

    private static void emitExtensionStream(Observability observe, String runId, String scenario,
            Map<String, ?> extensionAttributes, ObservationSpan parent) {
        String extension = String.valueOf(extensionAttributes.get("extension.name"));
        Map<String, Object> attributes = merge(messageAttributes("SendStreamingMessage", scenario, runId, extension), extensionAttributes);
        try (var client = observe.trace("SendStreamingMessage", SignalKind.CLIENT, attributes, parent)) {
            try (var server = observe.trace("SendStreamingMessage", SignalKind.SERVER, attributes, client)) {
                server.ok();
            }
            client.ok();
        }
    }

    private static Map<String, Object> a2aAttributes(String operation, String scenario, String runId) {
        return Map.ofEntries(
            Map.entry("operation.name", operation), Map.entry("task_id", "task-" + scenario + "-" + runId),
            Map.entry("context_id", "context-" + scenario + "-" + runId), Map.entry("message_id", operation + "-" + scenario + "-" + runId),
            Map.entry("role", "agent"),
            Map.entry("gen_ai.provider.name", "openan"),
            Map.entry("gen_ai.operation.name", operation), Map.entry("gen_ai.agent.name", "consumer-agent"),
            Map.entry("gen_ai.agent.id", "agent-1"), Map.entry("gen_ai.agent.version", "1.0.0"),
            Map.entry("transport", "http"), Map.entry("protocol.version", "0.3"),
            Map.entry("gen_ai.response.model", "model-1"));
    }

    private static Map<String, Object> messageAttributes(String operation, String scenario, String runId,
            String extension) {
        return merge(a2aAttributes(operation, scenario, runId), Map.of(
            "extensions", extension, "parts.number", 1));
    }

    private static Map<String, Object> merge(Map<String, ?> first, Map<String, ?> second) {
        Map<String, Object> merged = new HashMap<>();
        first.forEach(merged::put);
        second.forEach(merged::put);
        return Map.copyOf(merged);
    }

    private static void emitTrace(Observability observe, String name, SignalKind kind, Map<String, ?> attributes,
            ObservationSpan parent) {
        try (var span = observe.trace(name, kind, attributes, parent)) {
            span.ok();
        }
    }
}
