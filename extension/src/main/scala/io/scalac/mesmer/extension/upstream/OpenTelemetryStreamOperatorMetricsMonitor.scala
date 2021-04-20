package io.scalac.mesmer.extension.upstream

import com.typesafe.config.Config
import io.opentelemetry.api.metrics.Meter

import io.scalac.mesmer.extension.metric.MetricObserver
import io.scalac.mesmer.extension.metric.RegisterRoot
import io.scalac.mesmer.extension.metric.StreamOperatorMetricsMonitor
import io.scalac.mesmer.extension.metric.StreamOperatorMetricsMonitor.BoundMonitor
import io.scalac.mesmer.extension.metric.StreamOperatorMetricsMonitor.Labels
import io.scalac.mesmer.extension.upstream.OpenTelemetryStreamOperatorMetricsMonitor.MetricNames
import io.scalac.mesmer.extension.upstream.opentelemetry._

object OpenTelemetryStreamOperatorMetricsMonitor {
  case class MetricNames(operatorProcessed: String, connections: String, runningOperators: String, demand: String)

  object MetricNames {
    private val defaults: MetricNames =
      MetricNames(
        "akka_streams_operator_processed_total",
        "akka_streams_operator_connections",
        "akka_streams_running_operators",
        "akka_streams_operator_demand"
      )

    def fromConfig(config: Config): MetricNames = {
      import io.scalac.mesmer.extension.config.ConfigurationUtils._

      config.tryValue("io.scalac.akka-monitoring.metrics.stream-metrics")(_.getConfig).map { streamMetricsConfig =>
        val operatorProcessed = streamMetricsConfig
          .tryValue("operator-processed")(_.getString)
          .getOrElse(defaults.operatorProcessed)

        val operatorConnections = streamMetricsConfig
          .tryValue("operator-connections")(_.getString)
          .getOrElse(defaults.connections)

        val runningOperators = streamMetricsConfig
          .tryValue("running-operators")(_.getString)
          .getOrElse(defaults.runningOperators)

        val demand = streamMetricsConfig
          .tryValue("operator-demand")(_.getString)
          .getOrElse(defaults.demand)

        MetricNames(operatorProcessed, operatorConnections, runningOperators, demand)
      }
    }.getOrElse(defaults)
  }

  def apply(meter: Meter, config: Config): OpenTelemetryStreamOperatorMetricsMonitor =
    new OpenTelemetryStreamOperatorMetricsMonitor(meter, MetricNames.fromConfig(config))
}

class OpenTelemetryStreamOperatorMetricsMonitor(meter: Meter, metricNames: MetricNames)
    extends StreamOperatorMetricsMonitor {

  private val processedMessageAdapter = new LongSumObserverBuilderAdapter[Labels](
    meter
      .longSumObserverBuilder(metricNames.operatorProcessed)
      .setDescription("Amount of messages process by operator")
  )

  private val operatorsAdapter = new LongMetricObserverBuilderAdapter[Labels](
    meter
      .longValueObserverBuilder(metricNames.runningOperators)
      .setDescription("Amount of operators in a system")
  )

  private val demandAdapter = new LongSumObserverBuilderAdapter[Labels](
    meter
      .longSumObserverBuilder(metricNames.demand)
      .setDescription("Amount of messages demanded by operator")
  )

  def bind(): StreamOperatorMetricsMonitor.BoundMonitor = new BoundMonitor with RegisterRoot {

    lazy val processedMessages: MetricObserver[Long, Labels] =
      processedMessageAdapter.createObserver(this)

    lazy val operators: MetricObserver[Long, Labels] = operatorsAdapter.createObserver(this)

    lazy val demand: MetricObserver[Long, Labels] = demandAdapter.createObserver(this)
  }
}