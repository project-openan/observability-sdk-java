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
import net.openan.observability.*;

public final class TaskTExample {
    public static void main(String[] args) {
        try (ObservabilitySdk sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName("a2at-demo").build())) {
            Observability observe = sdk.observe();
            Map<String, String> attrs = Map.of("extension.name", "task-t", "task.type", "SPN专线投诉诊断", "task.object", "demo-line");
            try (ObservationSpan span = observe.trace("SendMessage", SignalKind.CLIENT, attrs)) {
                observe.log("message", LogSeverity.INFO, "Task-T 诊断结果已返回", attrs, span);
                observe.metric("task.duration", 42000, Map.of("task.type", "SPN专线投诉诊断"));
                observe.metric("task.count", 1, Map.of("task.type", "SPN专线投诉诊断"));
                observe.trace("invalid-span", SignalKind.CLIENT, attrs).end();
                observe.log("invalid-log", LogSeverity.INFO, "rejected", attrs);
                observe.metric("invalid-metric", 1, Map.of("task.type", "SPN专线投诉诊断"));
                observe.metric("task.count", 999, Map.of("task.type", "SPN专线投诉诊断", "extension.name", "task-t"));
                span.ok();
            }
            if (!sdk.flush(10, TimeUnit.SECONDS)) System.err.println("Observability flush failed or timed out");
        }
    }
}
