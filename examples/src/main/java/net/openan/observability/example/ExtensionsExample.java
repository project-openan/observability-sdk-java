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

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.openan.observability.*;

/** Fixed inputs for validating all four extensions against a Collector. */
public final class ExtensionsExample {
    public static void main(String[] args) {
        String serviceName = args.length == 0 ? "a2at-extensions-demo" : args[0];
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName(serviceName).build())) {
            var o = sdk.observe();
            send(o, Map.of("extension.name", "task-t", "task.type", "diagnosis", "task.object", "demo-line"), Map.of(), span -> {
                o.metric("task.duration", 42000, Map.of("task.type", "diagnosis", "task.status", "completed"), span);
                o.metric("task.count", 1, Map.of("task.type", "diagnosis", "task.status", "completed"), span);
            });
            send(o, Map.of("extension.name", "negotiation-t", "negotiation.id", "session-1", "negotiation.round", 2,
                "negotiation.total_rounds", 3, "negotiation.result", "ACCEPT", "negotiation.performative", "PROPOSE"), Map.of("negotiation.max_rounds", 4), span -> {
                o.metric("negotiation.rounds", 2, Map.of("negotiation.id", "session-1", "negotiation.result", "accept"), span);
                o.metric("negotiation.count", 1, Map.of("negotiation.result", "accept"), span);
            });
            send(o, Map.of("extension.name", "notification-t", "notification.topic", "demo-topic", "push.notification.url", "https://user:password@example.com/path?token=secret"), Map.of(),
                span -> o.metric("notification.count", 1, Map.of("notification.topic", "demo-topic", "notification.result", "sent"), span));
            send(o, Map.of("extension.name", "authorization-t", "authorization.stage", "EXECUTE", "authorization.policy.operation.type", "create"), Map.of(), span -> {
                o.metric("authorization.preauthorize.count", 1, Map.of("authorization.policy.operation.type", "create", "authorization.result", "allowed"), span);
                o.metric("authorization.preauthorize.duration", 25, Map.of("authorization.policy.operation.type", "create", "authorization.result", "allowed"), span);
                o.metric("authorization.execute.count", 1, Map.of("authorization.policy.operation.type", "create", "authorization.result", "allowed"), span);
            });
            o.trace("SendMessage", SignalKind.CLIENT, Map.of("extension.name", "unknown-extension", "task.type", "invalid-marker")).end();
            o.log("message", LogSeverity.INFO, "invalid-marker", Map.of("extension.name", "notification-t", "negotiation.id", "invalid-marker"));
            o.trace("SendMessage", SignalKind.CLIENT, Map.of("extension.name", "negotiation-t", "negotiation.max_rounds", 999)).end();
            o.metric("task.count", 999, Map.of("task.type", "diagnosis", "notification.topic", "invalid-marker"));
            if (!sdk.flush(10, TimeUnit.SECONDS)) throw new IllegalStateException("Flush failed or timed out");
        }
    }
    private static void send(Observability o, Map<String, Object> attrs, Map<String, Object> extra, Consumer<ObservationSpan> metrics) {
        var logAttrs = new java.util.HashMap<>(attrs); logAttrs.putAll(extra);
        try (var span = o.trace("SendMessage", SignalKind.CLIENT, attrs)) {
            String body = "extension sample " + attrs.get("extension.name");
            o.log("message", LogSeverity.INFO, body, logAttrs, span);
            metrics.accept(span);
            span.ok();
        }
    }
}
