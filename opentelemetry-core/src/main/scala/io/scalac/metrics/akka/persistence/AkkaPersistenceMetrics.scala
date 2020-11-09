package io.scalac.metrics.akka.persistence

import io.opentelemetry.common.Labels
import io.opentelemetry.metrics.LongValueRecorder.BoundLongValueRecorder
import io.opentelemetry.metrics.Meter
import io.scalac.metrics.meter

final class AkkaPersistenceMetrics private[metrics] (node: String, entities: Set[String], meter: Meter) {

  val persistentActorRecoveryTime: Map[String, BoundLongValueRecorder] = entities
    .map(entityName =>
      entityName -> meter
        .longValueRecorderBuilder(s"akka.persistence.recovery-time.$entityName")
        .setDescription("Time spent on persistent actor recovery")
        .build()
        .bind(Labels.of("node", node))
    )
    .toMap
}

object AkkaPersistenceMetrics {
  private var instance: AkkaPersistenceMetrics = null

  def instance(node: String, entities: Set[String]): AkkaPersistenceMetrics = synchronized {
    if (instance == null)
      instance = new AkkaPersistenceMetrics(node, entities, meter)
    instance
  }
}
