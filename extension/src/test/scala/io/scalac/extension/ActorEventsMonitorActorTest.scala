package io.scalac.extension

import akka.actor.PoisonPill
import akka.actor.testkit.typed.javadsl.FishingOutcomes
import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ Behaviors, StashBuffer }
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, SupervisorStrategy }
import akka.util.Timeout
import akka.{ actor => classic }
import io.scalac.core.actor.{ ActorMetrics, MutableActorMetricsStorage }
import io.scalac.core.model._
import io.scalac.core.util.AggMetric.LongValueAggMetric
import io.scalac.core.util.TestCase._
import io.scalac.core.util.probe.ObserverCollector.ScheduledCollectorImpl
import io.scalac.core.util.{ ActorPathOps, ReceptionistOps, TestOps }
import io.scalac.extension.ActorEventsMonitorActor._
import io.scalac.extension.ActorEventsMonitorActorTest._
import io.scalac.extension.metric.ActorMetricMonitor.Labels
import io.scalac.extension.service.ActorTreeService
import io.scalac.extension.service.ActorTreeService.GetActors
import io.scalac.extension.util.probe.ActorMonitorTestProbe
import io.scalac.extension.util.probe.BoundTestProbe.{ CounterCommand, Inc, MetricObserved, MetricObserverCommand }
import org.scalatest.concurrent.{ PatienceConfiguration, ScaledTimeSpans }
import org.scalatest.{ LoneElement, TestSuite }

import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NoStackTrace

trait ActorEventMonitorActorTestConfig {
  this: TestSuite with ScaledTimeSpans with ReceptionistOps with PatienceConfiguration =>

  protected val pingOffset: FiniteDuration     = scaled(1.seconds)
  protected val reasonableTime: FiniteDuration = 3 * pingOffset
  override lazy val patienceConfig             = PatienceConfig(reasonableTime, scaled(100.millis))
}

import org.scalatest.Inspectors
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class ActorEventsMonitorActorTest
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with Inspectors
    with ProvidedActorSystemTestCaseFactory
    with AbstractMonitorTestCaseFactory
    with ScaledTimeSpans
    with LoneElement
    with TestOps
    with ReceptionistOps
    with ActorEventMonitorActorTestConfig {

  final val ActorsPerCase = 10

  private val FailingReaderFactory: MetricsContext => ActorMetricsReader = _ => {
    _ => throw new RuntimeException("Planned failure") with NoStackTrace
  }

  private val FakeReaderFactory: MetricsContext => ActorMetricsReader = metrics => {
    _ => {
      import metrics._
      Some(
        ActorMetrics(
          mailboxSize = Some(fakeMailboxSize),
          mailboxTime = Some(fakeMailboxTime),
          receivedMessages = Some(fakeReceivedMessages),
          unhandledMessages = Some(fakeUnhandledMessages),
          failedMessages = Some(fakeFailedMessages),
          processingTime = Some(fakeProcessingTimes),
          sentMessages = Some(fakeSentMessages),
          stashSize = Some(fakeStashedMessages)
        )
      )
    }
  }

  private val constRefsActorServiceTree
    : (ActorRef[CounterCommand], Seq[classic.ActorRef]) => Behavior[ActorTreeService.Command] = (monitor, refs) =>
    Behaviors.receiveMessagePartial {
      case GetActors(Tag.all, reply) =>
        monitor ! Inc(1L)
        reply ! refs
        Behaviors.same
      case GetActors(_, reply) =>
        reply ! Seq.empty
        Behaviors.same
    }

  private val noReplyActorServiceTree
    : (ActorRef[CounterCommand], Seq[classic.ActorRef]) => Behavior[ActorTreeService.Command] = (monitor, refs) =>
    Behaviors.receiveMessagePartial { case GetActors(Tag.all, reply) =>
      monitor ! Inc(1L)
      reply ! refs
      Behaviors.same
    }

  override implicit val timeout: Timeout = pingOffset

  protected def createMonitor(implicit system: ActorSystem[_]): ActorMonitorTestProbe = ActorMonitorTestProbe(
    new ScheduledCollectorImpl(pingOffset)
  )
  protected def createContextFromMonitor(monitor: ActorMonitorTestProbe)(implicit
    system: ActorSystem[_]
  ): Context = TestContext(monitor, TestProbe(), FakeReaderFactory, constRefsActorServiceTree)

  protected type Setup = (Seq[classic.ActorRef], ActorRef[ActorEventsMonitorActor.Command])

  protected def setUp(c: Context): Setup = {

    val testActors = Seq
      .fill(ActorsPerCase)(system.systemActorOf(Behaviors.ignore, createUniqueId).toClassic)

    val treeServiceBehavior = c.actorTreeServiceBehaviorFactory(c.actorTreeServiceProbe.ref, testActors)

    val treeService = system.systemActorOf(treeServiceBehavior, createUniqueId)

    val sut = system.systemActorOf(
      Behaviors
        .supervise(
          Behaviors.setup[Command] { context =>
            Behaviors.withTimers[Command] { scheduler =>
              new ActorEventsMonitorActor(
                context,
                monitor(c),
                None,
                pingOffset,
                () => MutableActorMetricsStorage.empty,
                scheduler,
                c.TestActorMetricsReader
              ).start(treeService)
            }
          }
        )
        .onFailure(SupervisorStrategy.restart),
      createUniqueId
    )

    (testActors :+ treeService.toClassic, sut)
  }

  protected def tearDown(setup: Setup): Unit = {
    val (refs, sut) = setup
    (refs :+ sut.toClassic).foreach(_.unsafeUpcast[Any] ! PoisonPill)
  }

  type Monitor = ActorMonitorTestProbe
  type Context = TestContext

  private def TakeLabel(implicit setup: Setup): Labels = {
    val ref = Random.shuffle(refs).head
    Labels(ActorPathOps.getPathString(ref))
  }

  def refs(implicit setup: Setup): Seq[classic.ActorRef] = setup._1

  def sut(implicit setup: Setup): ActorRef[ActorEventsMonitorActor.Command] = setup._2

  def metrics(implicit context: Context): MetricsContext = context.metrics

  "ActorEventsMonitor" should "record mailbox size" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(monitor.mailboxSizeProbe, TakeLabel, _.fakeMailboxSize, _.fakeMailboxSize += 1)
  }

  it should "record stash size" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(monitor.stashSizeProbe, TakeLabel, _.fakeStashedMessages, _.fakeStashedMessages += 10)
  }

  it should "record avg mailbox time" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.mailboxTimeAvgProbe,
      TakeLabel,
      _.fakeMailboxTime.avg,
      MailboxTimeModify(incAverage)
    )
  }

  it should "record min mailbox time" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.mailboxTimeMinProbe,
      TakeLabel,
      _.fakeMailboxTime.min,
      MailboxTimeModify(incMin)
    )
  }

  it should "record max mailbox time" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.mailboxTimeMaxProbe,
      TakeLabel,
      _.fakeMailboxTime.max,
      MailboxTimeModify(incMax)
    )
  }

  it should "record sum mailbox time" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.mailboxTimeSumProbe,
      TakeLabel,
      _.fakeMailboxTime.sum,
      MailboxTimeModify(incSum)
    )
  }

  it should "record received messages" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.receivedMessagesProbe,
      TakeLabel,
      _.fakeReceivedMessages,
      _.fakeReceivedMessages += 1
    )
  }

  it should "record processed messages" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.processedMessagesProbe,
      TakeLabel,
      _.fakeProcessedMessages,
      _.fakeProcessedMessages += 1
    )
  }

  it should "record failed messages" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.failedMessagesProbe,
      TakeLabel,
      _.fakeFailedMessages,
      _.fakeFailedMessages += 1
    )
  }

  it should "record avg processing time" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.processingTimeAvgProbe,
      TakeLabel,
      _.fakeProcessingTimes.avg,
      ProcessingTimeModify(incAverage)
    )
  }

  it should "record min processing time" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.processingTimeMinProbe,
      TakeLabel,
      _.fakeProcessingTimes.min,
      ProcessingTimeModify(incMin)
    )
  }

  it should "record max processing time" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.processingTimeMaxProbe,
      TakeLabel,
      _.fakeProcessingTimes.max,
      ProcessingTimeModify(incMax)
    )
  }

  it should "record sum processing time" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(
      monitor.processingTimeSumProbe,
      TakeLabel,
      _.fakeProcessingTimes.sum,
      ProcessingTimeModify(incSum)
    )
  }

  it should "record the sent messages" in testCaseSetupContext { implicit setup => implicit context =>
    shouldObserveWithChange(monitor.sentMessagesProbe, TakeLabel, _.fakeSentMessages, _.fakeSentMessages += 1)
  }

  it should "unbind monitors on restart" in testCaseWith(_.copy(metricReaderFactory = FailingReaderFactory)) {
    implicit context =>
      eventually {
        monitor.unbinds should be(1)
        monitor.binds should be(2)
      }
  }

  it should "not product any metrics until actroTreeService is availabe" in testCaseWith(
    _.copy(actorTreeServiceBehaviorFactory = noReplyActorServiceTree)
  ) { implicit context =>
    context.actorTreeServiceProbe.expectMessage(Inc(1L))
    monitor.sentMessagesProbe.expectNoMessage()
  }

  it should "retry to get actor refs" in testCaseWith(
    _.copy(actorTreeServiceBehaviorFactory = noReplyActorServiceTree)
  ) { implicit context =>
    context.actorTreeServiceProbe.expectMessage(Inc(1L))
    context.actorTreeServiceProbe.expectMessage(Inc(1L))
    context.actorTreeServiceProbe.expectMessage(Inc(1L))
  }

  private val incAverage: LongValueAggMetric => LongValueAggMetric = agg => agg.copy(avg = agg.avg + 1)
  private val incMax: LongValueAggMetric => LongValueAggMetric     = agg => agg.copy(max = agg.max + 1)
  private val incSum: LongValueAggMetric => LongValueAggMetric     = agg => agg.copy(sum = agg.sum + 1)
  private val incMin: LongValueAggMetric => LongValueAggMetric     = agg => agg.copy(min = agg.min + 1)

  private val ProcessingTimeModify = lens(m => agg => m.fakeProcessingTimes = agg, _.fakeProcessingTimes) _
  private val MailboxTimeModify    = lens(m => agg => m.fakeMailboxTime = agg, _.fakeMailboxTime) _

  private def lens(
    setter: MetricsContext => LongValueAggMetric => Unit,
    getter: MetricsContext => LongValueAggMetric
  )(map: LongValueAggMetric => LongValueAggMetric): MetricsContext => Unit = metrics => {
    setter(metrics)(map(getter(metrics)))
  }

  def shouldObserve(probe: TestProbe[MetricObserverCommand[Labels]], labels: Labels, metric: Long): Unit =
    probe
      .fishForMessage(reasonableTime) {
        case MetricObserved(`metric`, `labels`) => FishingOutcomes.complete()
        case _                                  => FishingOutcomes.continueAndIgnore()
      }

  def shouldObserveWithChange(
    probe: TestProbe[MetricObserverCommand[Labels]],
    labels: Labels,
    metric: MetricsContext => Long,
    change: MetricsContext => Unit
  )(implicit c: Context): Unit = {
    shouldObserve(probe, labels, metric(metrics))
    change(metrics)
    shouldObserve(probe, labels, metric(metrics))
  }
}

object ActorEventsMonitorActorTest {

  final class MetricsContext() {
    @volatile var fakeMailboxSize       = 10
    @volatile var fakeReceivedMessages  = 12
    @volatile var fakeProcessedMessages = 10
    @volatile var fakeFailedMessages    = 2
    @volatile var fakeSentMessages      = 10
    @volatile var fakeStashedMessages   = 19

    @volatile var fakeMailboxTime: LongValueAggMetric     = LongValueAggMetric(1, 2, 1, 4, 3)
    @volatile var fakeProcessingTimes: LongValueAggMetric = LongValueAggMetric(1, 2, 1, 4, 3)

    def fakeUnhandledMessages: Long = fakeReceivedMessages - fakeProcessedMessages
  }

  final case class TestContext(
    monitor: ActorMonitorTestProbe,
    actorTreeServiceProbe: TestProbe[CounterCommand],
    metricReaderFactory: MetricsContext => ActorMetricsReader,
    actorTreeServiceBehaviorFactory: (
      ActorRef[CounterCommand],
      Seq[classic.ActorRef]
    ) => Behavior[ActorTreeService.Command]
  )(implicit
    val system: ActorSystem[_]
  ) extends MonitorTestCaseContext[ActorMonitorTestProbe] {

    val metrics = new MetricsContext()

    val TestActorMetricsReader: ActorMetricsReader = metricReaderFactory(metrics)

    sealed trait Command

    final case object Open extends Command

    final case object Close extends Command

    final case class Message(text: String) extends Command

    //TODO delete
    object StashActor {
      def apply(capacity: Int): Behavior[Command] =
        Behaviors.withStash(capacity)(buffer => new StashActor(buffer).closed())
    }

    class StashActor(buffer: StashBuffer[Command]) {
      private def closed(): Behavior[Command] =
        Behaviors.receiveMessagePartial {
          case Open =>
            buffer.unstashAll(open())
          case msg =>
            buffer.stash(msg)
            Behaviors.same
        }

      private def open(): Behavior[Command] = Behaviors.receiveMessagePartial {
        case Close =>
          closed()
        case Message(_) =>
          Behaviors.same
      }

    }

  }
}
