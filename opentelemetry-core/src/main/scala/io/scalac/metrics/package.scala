package io.scalac

import io.opentelemetry.OpenTelemetry
import io.opentelemetry.metrics.Meter

package object metrics {
  private[metrics] val meter: Meter = OpenTelemetry.getMeter("io.scalac.monitoring")
}
