# Business Metrics View

This document organizes the raw observability signals recorded in the [Signal Catalog](signal-catalog.md) into business-readable metrics: each entry gives its computation (PromQL-style example), source signal, and acquisition method. Metrics are organized in four progressive layers; when an upper-layer metric regresses, drill down layer by layer to locate the root cause.

**Computation principle**: the SDK only records raw facts as declared in YAML (counters, histograms, spans, logs); derived metrics such as ratios, percentiles, and rankings are always computed in the storage/query backend. Ratios cannot be re-aggregated across instances or dimensions (counters and histograms are additive); changing scope only requires a backend query change, not an SDK release.

**Where computation runs** (the `deploy/local-storage/` stack of this repository): Prometheus/Thanos computes Metrics, Jaeger computes Traces, and OpenSearch computes Logs. Formulas use short metric names for readability; Prometheus series names are the OTel full names (counters additionally carry the `_total` suffix), for example `workflow.count` → `gen_ai.agent.a2at.workflow.count_total`. Windows and denominator scope (for example, whether `cancelled` counts) are defined by dashboards and alert rules.

## Progressive structure

```
L1 Business outcome ── success rate · E2E duration · throughput
                       ↓ success rate drops / duration regresses
L2 Collaboration   ── task / negotiation / authorization / notification
                       success rates · A2A operation error rate
                       ↓ locates the collaboration segment
L3 Execution       ── step / substep failure rates and duration ·
                       slowest segments · agent activity
                       ↓ attributes cost to a segment
L4 Resource spend  ── token distribution · per-task / per-workflow
                       cost · output efficiency
```

## L1 Business outcome layer

| Business metric | Computation (example) | Source signal | Acquisition |
|---|---|---|---|
| Business success rate | `rate(workflow.count{workflow.status="completed"}) ÷ rate(workflow.count)` grouped by `workflow.type` | `workflow.count` | Ratio aggregation |
| E2E processing duration | `histogram_quantile(0.95, rate(workflow.duration_bucket[5m]))` | `workflow.duration` | Percentile aggregation |
| Business throughput | `rate(workflow.count[5m])` grouped by `workflow.type` | `workflow.count` | Direct read |

## L2 Collaboration quality layer

| Business metric | Computation (example) | Source signal | Acquisition |
|---|---|---|---|
| Task success rate | `rate(task.count{task.status="completed"}) ÷ rate(task.count)` grouped by `task.type` | `task.count` | Ratio aggregation |
| Task duration distribution | `histogram_quantile(0.95, rate(task.duration_bucket[5m]))`, splittable by `task.status` | `task.duration` | Percentile aggregation |
| Negotiation success rate | `rate(negotiation.count{negotiation.result="accepted"}) ÷ rate(negotiation.count)` | `negotiation.count` | Ratio aggregation |
| Negotiation rounds | `histogram_quantile(0.95, rate(negotiation.rounds_bucket[5m]))` | `negotiation.rounds` | Percentile aggregation |
| Authorization pass rate | `rate(authorization.preauthorize.count{authorization.result="allowed"}) ÷ rate(authorization.preauthorize.count)` | `authorization.preauthorize.count` | Ratio aggregation |
| Notification delivery rate | `rate(notification.count{notification.result="sent"}) ÷ rate(notification.count)` grouped by `notification.topic` | `notification.count` | Ratio aggregation |
| A2A operation error rate | `Σ rate(operation.duration_count{error.type!=""}) ÷ Σ rate(operation.duration_count)` grouped by `operation.name` | `gen_ai.client.operation.duration` | Ratio aggregation (`error.type` appears only on errors) |
| Streaming error rate | Same as above | `gen_ai.agent.a2a.streaming.duration` | Ratio aggregation |
| Push failure rate | Share of spans carrying `error.type` | `SendPushNotification` | Trace aggregation |

## L3 Execution quality layer

| Business metric | Computation (example) | Source signal | Acquisition |
|---|---|---|---|
| Step failure rate | `rate(step.count{workflow.step.status="failed"}) ÷ rate(step.count)` grouped by `gen_ai.agent.name` | `step.count` | Ratio aggregation |
| Step duration distribution | `histogram_quantile(0.95, rate(step.duration_bucket[5m]))` grouped by agent | `step.duration` | Percentile aggregation |
| Substep failure rate / duration | Same pattern with `substep.count` / `substep.duration` grouped by agent | `substep.count` / `substep.duration` | Ratio / percentile aggregation |
| Slowest step ranking | Aggregate p95 duration by span name `step.<name>` | `step.*` traces | Trace aggregation |
| Streaming time-to-first-event | Time from the streaming parent span start to the first `-event` span start (linked via span link) | `SendStreamingMessage` / `-event` traces | Trace aggregation |
| Active agent count | Distinct count of `agent.state` = running | `agent` log | Log aggregation |

## L4 Resource spend layer

| Business metric | Computation (example) | Source signal | Acquisition |
|---|---|---|---|
| Total token consumption | `rate(input_tokens) + rate(output_tokens)` | `gen_ai.client.inference.usage.*` | Direct read |
| Token distribution | Same as above grouped by `gen_ai.provider.name` / `gen_ai.request.model` / `gen_ai.token.modality` / `gen_ai.agent.name` / `gen_ai.operation.name` | Same (per-agent requires the optional dimension) | Direct grouped read |
| Token cost per task | `Σ rate(tokens) ÷ rate(task.count)` grouped by `task.type` | Token metrics ÷ `task.count` | Cross-metric ratio |
| Token cost per workflow | `Σ rate(tokens) ÷ rate(workflow.count)` | Token metrics ÷ `workflow.count` | Cross-metric ratio |
| Average tokens per call | `Σ rate(tokens) ÷ Σ rate(operation.duration_count)` | Token metrics ÷ operation count | Cross-metric ratio |
| Output efficiency | `rate(output_tokens) ÷ rate(input_tokens)` | Token metrics | Ratio aggregation |
| Per-call / per-task token attribution | Sum span `usage.input_tokens` / `usage.output_tokens` by `task_id` | A2A protocol spans | Trace aggregation |

## Acquisition methods

| Acquisition | Where it runs | Description |
|---|---|---|
| Direct (grouped) read | Prometheus | Read counter/histogram series directly, grouped by dimensions |
| Ratio aggregation | Prometheus | Divide two series from the same source (for example, completed ÷ total) |
| Percentile aggregation | Prometheus | `histogram_quantile` over `_bucket` series for p50/p95/p99 |
| Cross-metric ratio | Prometheus | Divide series from different metrics; the backend aligns the time window |
| Trace aggregation | Jaeger | Filter and count by span attributes, aggregate duration by span name, correlate via span links / `task_id` |
| Log aggregation | OpenSearch | Filter and deduplicate by log event and attributes |

## Cross-layer drill-down

- **Metric → trace**: histogram metrics carry `trace_id` / `span_id` through Prometheus Exemplars, so a metric sample drills straight into its trace (enabled in `deploy/local-storage/`).
- **Cross-signal correlation**: one execution is tied together by `workflow.id` / `workflow.step.id` / `task_id` across Metrics, Traces, and Logs.
- **Typical progressive diagnosis**: business success rate drops (L1) → a certain `task.type` shows a high failure rate (L2) → failures concentrate in one agent's steps (L3) → drill into that span via Exemplars and check its token consumption and duration (L4).

## References

- [Signal Catalog](signal-catalog.md): raw signal definitions, attribute allowlists, and value ranges
- `deploy/local-storage/`: local Collector and storage stack (including the Prometheus Exemplar setup)
