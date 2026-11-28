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

package net.openan.observability.internal;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Internal signal definitions; not a supported public API. */
public final class Definitions {
    public static final String PREFIX = "gen_ai.agent.a2at.";
    public enum AttributeType { STRING, INTEGER, BOOLEAN, STRING_ARRAY }
    public record Attribute(String name, String signals, AttributeType type, Set<String> values) {}
    public record Metric(String name, boolean histogram, boolean integer, boolean a2a, String unit, Set<String> dimensions, Set<String> requiredDimensions) {}
    public record Signal(String extension, Set<String> attributes, Set<String> required) {}
    public record NamedSignal(String name, String namePrefix, Signal signal) {
        public boolean matches(String candidate) { return name != null ? name.equals(candidate) : candidate.startsWith(namePrefix) && candidate.length() > namePrefix.length(); }
    }
    private static final SignalConfigurationLoader.Catalog CONFIGURATION = SignalConfigurationLoader.load();
    private static final SignalConfigurationLoader.A2aProtocol A2A = CONFIGURATION.a2a();
    public static final String A2A_PREFIX = A2A.attributePrefix();
    public static final String MESSAGE_LOG = CONFIGURATION.messageLog();
    public static final String MESSAGE_SPAN = A2A.message();
    public static final String STREAM_SPAN = A2A.stream();
    public static final String STREAM_EVENT = A2A.streamEvent();
    public static final String STREAM_ERROR = A2A.streamError();
    public static final String SUBSCRIBE_EVENT = A2A.subscribeEvent();
    public static final String SUBSCRIBE_END = A2A.subscribeEnd();
    public static final Set<String> A2A_SPAN_NAMES = A2A.traceNames();
    public static final Set<String> A2A_CLIENT_ONLY_SPAN_NAMES = A2A.clientOnlyTraces();
    public static final Set<String> A2A_SERVER_ONLY_SPAN_NAMES = A2A.serverOnlyTraces();
    public static final Map<String, Signal> SPANS = CONFIGURATION.spans();
    public static final Map<String, Signal> LOGS = CONFIGURATION.logs();
    public static final Map<String, NamedSignal> NAMED_TRACES = CONFIGURATION.traces();
    public static final Map<String, NamedSignal> NAMED_LOGS = CONFIGURATION.namedLogs();
    public static final Map<String, String> CONFIGURATION_VERSIONS = CONFIGURATION.versions();
    public static final Map<String, Metric> METRICS = CONFIGURATION.metrics();
    public static Signal span(String name, Signal extension) {
        if (extension == null) return eventAttributes(name) ? A2A.streamEventSpan() : A2A.span();
        if (!MESSAGE_SPAN.equals(name) && !STREAM_SPAN.equals(name)) throw new IllegalArgumentException();
        return new Signal(extension.extension(), union(A2A.span().attributes(), extension.attributes()), extension.required());
    }
    public static boolean linkedSpan(String name) {
        return STREAM_EVENT.equals(name) || STREAM_ERROR.equals(name) || SUBSCRIBE_EVENT.equals(name) || SUBSCRIBE_END.equals(name);
    }
    public static Signal namedTrace(String name) { return find(NAMED_TRACES, name); }
    public static Signal namedLog(String name) { return find(NAMED_LOGS, name); }
    private static Signal find(Map<String, NamedSignal> definitions, String name) {
        return definitions.values().stream().filter(definition -> definition.matches(name)).map(NamedSignal::signal).findFirst().orElse(null);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> values = new HashSet<>(first);
        values.addAll(second);
        return Set.copyOf(values);
    }
    private static boolean eventAttributes(String name) {
        return STREAM_EVENT.equals(name) || SUBSCRIBE_EVENT.equals(name);
    }

    public static String shortName(String name) { return name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name; }
    public static Attribute attribute(String key) {
        return CONFIGURATION.attributes().get(key);
    }
    public static String attributeName(String key) {
        Attribute attribute = attribute(key);
        return attribute == null ? PREFIX + key : attribute.name();
    }
    private Definitions() {}
}
