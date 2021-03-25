package io.scalac.extension.upstream

import com.typesafe.config.Config
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Labels
import io.scalac.extension.metric.{ ActorMetricMonitor, MetricObserver, MetricRecorder }
import io.scalac.extension.upstream.OpenTelemetryActorMetricsMonitor.MetricNames
import io.scalac.extension.upstream.opentelemetry._

object OpenTelemetryActorMetricsMonitor {

  case class MetricNames(
    mailboxSize: String,
    mailboxTimeAvg: String,
    mailboxTimeMin: String,
    mailboxTimeMax: String,
    mailboxTimeSum: String,
    stashSize: String,
    receivedMessages: String,
    processedMessages: String,
    failedMessages: String,
    processingTimeAvg: String,
    processingTimeMin: String,
    processingTimeMax: String,
    processingTimeSum: String,
    sentMessages: String
  )
  object MetricNames {

    def default: MetricNames =
      MetricNames(
        "mailbox_size",
        "mailbox_time_avg",
        "mailbox_time_min",
        "mailbox_time_max",
        "mailbox_time_sum",
        "stash_size",
        "received_messages",
        "processed_messages",
        "failed_messages",
        "processing_time_avg",
        "processing_time_min",
        "processing_time_max",
        "processing_time_sum",
        "sent_messages"
      )

    def fromConfig(config: Config): MetricNames = {
      import io.scalac.extension.config.ConfigurationUtils._
      val defaultCached = default

      config
        .tryValue("io.scalac.akka-monitoring.metrics.actor-metrics")(
          _.getConfig
        )
        .map { clusterMetricsConfig =>
          val mailboxSize = clusterMetricsConfig
            .tryValue("mailbox-size")(_.getString)
            .getOrElse(defaultCached.mailboxSize)

          val mailboxTimeAvg = clusterMetricsConfig
            .tryValue("mailbox-time-avg")(_.getString)
            .getOrElse(defaultCached.mailboxTimeAvg)

          val mailboxTimeMin = clusterMetricsConfig
            .tryValue("mailbox-time-min")(_.getString)
            .getOrElse(defaultCached.mailboxTimeMin)

          val mailboxTimeMax = clusterMetricsConfig
            .tryValue("mailbox-time-max")(_.getString)
            .getOrElse(defaultCached.mailboxTimeMax)

          val mailboxTimeSum = clusterMetricsConfig
            .tryValue("mailbox-time-sum")(_.getString)
            .getOrElse(defaultCached.mailboxTimeSum)

          val stashSize = clusterMetricsConfig
            .tryValue("stash-size")(_.getString)
            .getOrElse(defaultCached.stashSize)

          val receivedMessages = clusterMetricsConfig
            .tryValue("received-messages")(_.getString)
            .getOrElse(defaultCached.receivedMessages)

          val processedMessages = clusterMetricsConfig
            .tryValue("processed-messages")(_.getString)
            .getOrElse(defaultCached.processedMessages)

          val failedMessages = clusterMetricsConfig
            .tryValue("failed-messages")(_.getString)
            .getOrElse(defaultCached.failedMessages)

          val processingTimeAvg = clusterMetricsConfig
            .tryValue("processing-time-avg")(_.getString)
            .getOrElse(defaultCached.processingTimeAvg)

          val processingTimeMin = clusterMetricsConfig
            .tryValue("processing-time-min")(_.getString)
            .getOrElse(defaultCached.processingTimeMin)

          val processingTimeMax = clusterMetricsConfig
            .tryValue("processing-time-max")(_.getString)
            .getOrElse(defaultCached.processingTimeMax)

          val processingTimeSum = clusterMetricsConfig
            .tryValue("processing-time-sum")(_.getString)
            .getOrElse(defaultCached.processingTimeSum)

          val sentMessages = clusterMetricsConfig
            .tryValue("sent-messages")(_.getString)
            .getOrElse(defaultCached.sentMessages)

          MetricNames(
            mailboxSize,
            mailboxTimeAvg,
            mailboxTimeMin,
            mailboxTimeMax,
            mailboxTimeSum,
            stashSize,
            receivedMessages,
            processedMessages,
            failedMessages,
            processingTimeAvg,
            processingTimeMin,
            processingTimeMax,
            processingTimeSum,
            sentMessages
          )
        }
        .getOrElse(defaultCached)
    }

  }

  def apply(instrumentationName: String, config: Config): OpenTelemetryActorMetricsMonitor =
    new OpenTelemetryActorMetricsMonitor(instrumentationName, MetricNames.fromConfig(config))

}

class OpenTelemetryActorMetricsMonitor(instrumentationName: String, metricNames: MetricNames)
    extends ActorMetricMonitor {

  private val meter = OpenTelemetry.getGlobalMeter(instrumentationName)

  private val mailboxSizeObserver = new LongMetricObserverBuilderAdapter(
    meter
      .longValueObserverBuilder(metricNames.mailboxSize)
      .setDescription("Tracks the size of an Actor's mailbox")
  )

  private val mailboxTimeAvgObserver = new LongMetricObserverBuilderAdapter(
    meter
      .longValueObserverBuilder(metricNames.mailboxTimeAvg)
      .setDescription("Tracks the average time of an message in an Actor's mailbox")
  )

  private val mailboxTimeMinObserver = new LongMetricObserverBuilderAdapter(
    meter
      .longValueObserverBuilder(metricNames.mailboxTimeMin)
      .setDescription("Tracks the minimum time of an message in an Actor's mailbox")
  )

  private val mailboxTimeMaxObserver = new LongMetricObserverBuilderAdapter(
    meter
      .longValueObserverBuilder(metricNames.mailboxTimeMax)
      .setDescription("Tracks the maximum time of an message in an Actor's mailbox")
  )

  private val mailboxTimeSumObserver = new LongMetricObserverBuilderAdapter(
    meter
      .longValueObserverBuilder(metricNames.mailboxTimeSum)
      .setDescription("Tracks the sum time of the messages in an Actor's mailbox")
  )

  private val stashSizeCounter = meter
    .longValueRecorderBuilder(metricNames.stashSize)
    .setDescription("Tracks the size of an Actor's stash")
    .build()

  private val receivedMessagesSumObserver = new LongSumObserverBuilderAdapter(
    meter
      .longSumObserverBuilder(metricNames.receivedMessages)
      .setDescription("Tracks the sum of received messages in an Actor")
  )

  private val processedMessagesSumObserver = new LongSumObserverBuilderAdapter(
    meter
      .longSumObserverBuilder(metricNames.processedMessages)
      .setDescription("Tracks the sum of processed messages in an Actor")
  )

  private val failedMessagesSumObserver = new LongSumObserverBuilderAdapter(
    meter
      .longSumObserverBuilder(metricNames.failedMessages)
      .setDescription("Tracks the sum of failed messages in an Actor")
  )

  private val processingTimeAvgObserver = new LongMetricObserverBuilderAdapter(
    meter
      .longValueObserverBuilder(metricNames.processingTimeAvg)
      .setDescription("Tracks the average processing time of an message in an Actor's receive handler")
  )

  private val processingTimeMinObserver = new LongMetricObserverBuilderAdapter(
    meter
      .longValueObserverBuilder(metricNames.processingTimeMin)
      .setDescription("Tracks the miminum processing time of an message in an Actor's receive handler")
  )

  private val processingTimeMaxObserver = new LongMetricObserverBuilderAdapter(
    meter
      .longValueObserverBuilder(metricNames.processingTimeMax)
      .setDescription("Tracks the maximum processing time of an message in an Actor's receive handler")
  )

  private val processingTimeSumObserver = new LongMetricObserverBuilderAdapter(
    meter
      .longValueObserverBuilder(metricNames.processingTimeSum)
      .setDescription("TTracks the sum processing time of an message in an Actor's receive handler")
  )

  private val sentMessagesObserver = new LongSumObserverBuilderAdapter(
    meter
      .longSumObserverBuilder(metricNames.sentMessages)
      .setDescription("Tracks the sum of sent messages in an Actor")
  )

  override def bind(labels: ActorMetricMonitor.Labels): OpenTelemetryBoundMonitor =
    new OpenTelemetryBoundMonitor(
      LabelsFactory.of(labels.serialize)
    )

  class OpenTelemetryBoundMonitor(labels: Labels) extends ActorMetricMonitor.BoundMonitor with Synchronized {

    val mailboxSize: MetricObserver[Long] =
      mailboxSizeObserver.createObserver(labels)

    val mailboxTimeAvg: MetricObserver[Long] =
      mailboxTimeAvgObserver.createObserver(labels)

    val mailboxTimeMin: MetricObserver[Long] =
      mailboxTimeMinObserver.createObserver(labels)

    val mailboxTimeMax: MetricObserver[Long] =
      mailboxTimeMaxObserver.createObserver(labels)

    val mailboxTimeSum: MetricObserver[Long] =
      mailboxTimeSumObserver.createObserver(labels)

    val stashSize: MetricRecorder[Long] with WrappedSynchronousInstrument[Long] =
      WrappedLongValueRecorder(stashSizeCounter, labels)

    val receivedMessages: MetricObserver[Long] =
      receivedMessagesSumObserver.createObserver(labels)

    val processedMessages: MetricObserver[Long] =
      processedMessagesSumObserver.createObserver(labels)

    val failedMessages: MetricObserver[Long] =
      failedMessagesSumObserver.createObserver(labels)

    val processingTimeAvg: MetricObserver[Long] =
      processingTimeAvgObserver.createObserver(labels)

    val processingTimeMin: MetricObserver[Long] =
      processingTimeMinObserver.createObserver(labels)

    val processingTimeMax: MetricObserver[Long] =
      processingTimeMaxObserver.createObserver(labels)

    val processingTimeSum: MetricObserver[Long] =
      processingTimeSumObserver.createObserver(labels)

    val sentMessages: MetricObserver[Long] =
      sentMessagesObserver.createObserver(labels)

    def unbind(): Unit = {
      mailboxSizeObserver.removeObserver(labels)
      mailboxTimeAvgObserver.removeObserver(labels)
      mailboxTimeMinObserver.removeObserver(labels)
      mailboxTimeMaxObserver.removeObserver(labels)
      mailboxTimeSumObserver.removeObserver(labels)
      stashSize.unbind()
      receivedMessagesSumObserver.removeObserver(labels)
      processedMessagesSumObserver.removeObserver(labels)
      failedMessagesSumObserver.removeObserver(labels)
      processingTimeAvgObserver.removeObserver(labels)
      processingTimeMinObserver.removeObserver(labels)
      processingTimeMaxObserver.removeObserver(labels)
      processingTimeSumObserver.removeObserver(labels)
      sentMessagesObserver.removeObserver(labels)
    }

  }

}