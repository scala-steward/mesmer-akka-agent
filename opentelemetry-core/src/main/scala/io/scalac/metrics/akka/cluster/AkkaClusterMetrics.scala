package io.scalac.metrics.akka.cluster

import io.opentelemetry.common.Labels
import io.opentelemetry.metrics.LongValueRecorder.BoundLongValueRecorder
import io.opentelemetry.metrics.Meter
import io.scalac.metrics.meter

final class AkkaClusterMetrics private[metrics] (node: String, meter: Meter) {

  val shardsAmountRecorder: BoundLongValueRecorder = meter
    .longValueRecorderBuilder("akka.cluster.shards")
    .setDescription("Amount of shards on a node")
    .build()
    .bind(Labels.of("node", node))

  val entitiesAmountRecorder: BoundLongValueRecorder = meter
    .longValueRecorderBuilder("akka.cluster.entities")
    .setDescription("Amount of entities")
    .build()
    .bind(Labels.of("node", node))
}

object AkkaClusterMetrics {

  private var instance: AkkaClusterMetrics = null

  def instance(node: String): AkkaClusterMetrics = synchronized {
    if (instance == null)
      instance = new AkkaClusterMetrics(node, meter)
    instance
  }
}
