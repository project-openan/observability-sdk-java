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

package net.openan.observability.consumer;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.LogSeverity;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

/** Small standalone Maven consumer for checking the published SDK artifact. */
public final class ConsumerSmoke {
    private ConsumerSmoke() { }

    public static void main(String[] args) {
        String runId = args.length == 0 ? "local" : args[0];
        String serviceName = "sdk-consumer-" + runId;
        var attributes = Map.of(
            "extension.name", "task-t",
            "task.type", "consumer-smoke",
            "task.object", runId);

        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName(serviceName).build())) {
            var observe = sdk.observe();
            try (var span = observe.trace("SendMessage", SignalKind.CLIENT, attributes)) {
                observe.log("message", LogSeverity.INFO, "Consumer smoke " + runId, attributes, span);
                observe.metric("task.duration", 42, Map.of("task.type", "consumer-smoke"), span);
                observe.metric("task.count", 1, Map.of("task.type", "consumer-smoke"), span);
                span.ok();
            }
            if (!sdk.flush(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OTLP export failed or flush timed out");
            }
        }

        System.out.println("Consumer smoke completed for " + serviceName);
    }
}
