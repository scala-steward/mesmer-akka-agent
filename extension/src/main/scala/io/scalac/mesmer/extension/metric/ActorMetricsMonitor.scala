package io.scalac.mesmer.extension.metric

import io.scalac.mesmer.core.LabelSerializable
import io.scalac.mesmer.core.model._

object ActorMetricsMonitor {
  final case class Labels(actorPath: ActorPath, node: Option[Node] = None, tags: Set[Tag] = Set.empty)
      extends LabelSerializable {
    val serialize: RawLabels = node.serialize ++ actorPath.serialize ++ tags.flatMap(_.serialize)
  }

  trait BoundMonitor extends Bound {
    def mailboxSize: MetricObserver[Long, Labels]
    // TODO Create an abstraction to aggregate multiple metrics (e.g: mailboxTimeAgg: MetricObserverAgg[Long])
    def mailboxTimeAvg: MetricObserver[Long, Labels]
    def mailboxTimeMin: MetricObserver[Long, Labels]
    def mailboxTimeMax: MetricObserver[Long, Labels]
    def mailboxTimeSum: MetricObserver[Long, Labels]
    def stashSize: MetricObserver[Long, Labels]
    def receivedMessages: MetricObserver[Long, Labels]
    def processedMessages: MetricObserver[Long, Labels]
    def failedMessages: MetricObserver[Long, Labels]
    def processingTimeAvg: MetricObserver[Long, Labels]
    def processingTimeMin: MetricObserver[Long, Labels]
    def processingTimeMax: MetricObserver[Long, Labels]
    def processingTimeSum: MetricObserver[Long, Labels]
    def sentMessages: MetricObserver[Long, Labels]
    def droppedMessages: MetricObserver[Long, Labels]

  }
}
