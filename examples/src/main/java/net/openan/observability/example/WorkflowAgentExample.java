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

package net.openan.observability.example;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.LogSeverity;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

/** Emits one workflow, step, and agent substep through the public SDK API. */
public final class WorkflowAgentExample {
    private WorkflowAgentExample() { }

    public static void main(String[] args) {
        String runId = args.length == 0 ? "local" : args[0];
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName("workflow-agent-" + runId).build())) {
            var observe = sdk.observe();
            Map<String, Object> workflow = Map.of(
                "workflow.id", "wf-" + runId,
                "workflow.type", "fault-diagnosis",
                "workflow.status", "running",
                "workflow.session_id", "session-" + runId);
            try (var workflowSpan = observe.trace("a2at.workflow", SignalKind.INTERNAL, workflow);
                 var workflowScope = workflowSpan.makeCurrent()) {
                runStep(observe, runId);
                observe.metric("workflow.duration", 180, Map.of(
                    "workflow.type", "fault-diagnosis", "workflow.status", "completed"));
                observe.metric("workflow.count", 1, Map.of(
                    "workflow.type", "fault-diagnosis", "workflow.status", "completed"));
                observe.log("gen_ai.agent.a2at.workflow", LogSeverity.INFO, "workflow completed " + runId, Map.of(
                    "workflow.id", "wf-" + runId,
                    "workflow.type", "fault-diagnosis",
                    "workflow.status", "completed",
                    "workflow.session_id", "session-" + runId), workflowSpan);
                workflowSpan.ok();
            }
            if (!sdk.flush(10, TimeUnit.SECONDS)) throw new IllegalStateException("OTLP export failed or flush timed out");
        }
    }

    private static void runStep(net.openan.observability.Observability observe, String runId) {
        Map<String, Object> step = Map.of(
            "workflow.step.name", "retrieve-alarms",
            "workflow.step.id", "step-1",
            "workflow.step.status", "completed",
            "workflow.step.owner_agent", "diagnosis-agent");
        try (var stepSpan = observe.trace("a2at.step.retrieve-alarms", SignalKind.INTERNAL, step);
             var stepScope = stepSpan.makeCurrent()) {
            observe.metric("step.duration", 75, Map.of(
                "workflow.step.status", "success", "gen_ai.agent.name", "diagnosis-agent"));
            observe.log("gen_ai.agent.a2at.step", LogSeverity.INFO, "step completed " + runId, step, stepSpan);
            runAgent(observe, runId);
            stepSpan.ok();
        }
    }

    private static void runAgent(net.openan.observability.Observability observe, String runId) {
        Map<String, String> substep = Map.of(
            "gen_ai.agent.name", "diagnosis-agent",
            "substep.name", "retrieve-alarms",
            "substep.status", "completed");
        try (var agentSpan = observe.trace("a2at.substep.retrieve-alarms", SignalKind.INTERNAL, substep)) {
            observe.metric("substep.duration", 50, Map.of(
                "substep.status", "success", "gen_ai.agent.name", "diagnosis-agent"));
            observe.metric("gen_ai.client.inference.usage.input_tokens", 120, Map.of(
                "gen_ai.operation.name", "chat",
                "gen_ai.provider.name", "openai",
                "gen_ai.token.modality", "text",
                "gen_ai.agent.name", "diagnosis-agent",
                "gen_ai.request.model", "model-1"));
            observe.log("gen_ai.agent.a2at.agent", LogSeverity.INFO, "agent completed " + runId, Map.of(
                "gen_ai.agent.name", "diagnosis-agent",
                "agent.role", "fault-diagnosis",
                "agent.skills", List.of("alarm-search"),
                "agent.state", "completed"), agentSpan);
            observe.log("gen_ai.agent.a2at.substep", LogSeverity.INFO, "agent substep completed " + runId, Map.of(
                "gen_ai.agent.name", "diagnosis-agent",
                "substep.name", "retrieve-alarms",
                "substep.status", "completed",
                "workflow.step.name", "retrieve-alarms",
                "workflow.step.id", "step-1"), agentSpan);
            agentSpan.ok();
        }
    }
}
