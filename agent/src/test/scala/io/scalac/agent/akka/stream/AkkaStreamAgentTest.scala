package io.scalac.agent.akka.stream

import akka.Done
import akka.actor.ActorRef
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.receptionist.Receptionist._
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior }
import akka.stream.scaladsl._
import akka.stream.{ Attributes, BufferOverflowException, OverflowStrategy, QueueOfferResult }
import io.scalac.agent.utils.{ InstallAgent, SafeLoadSystem }
import io.scalac.core.akka.model.PushMetrics
import io.scalac.core.event.StreamEvent.{ LastStreamStats, StreamInterpreterStats }
import io.scalac.core.event.{ Service, StreamEvent, TagEvent }
import io.scalac.core.util.TestCase.CommonMonitorTestFactory
import org.scalatest._
import org.scalatest.concurrent.{ Futures, ScalaFutures }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class AkkaStreamAgentTest
    extends InstallAgent
    with AnyFlatSpecLike
    with Matchers
    with SafeLoadSystem
    with BeforeAndAfterAll
    with BeforeAndAfter
    with LoneElement
    with Inspectors
    with ScalaFutures
    with Futures
    with Inside
    with CommonMonitorTestFactory {

  protected def createMonitorBehavior(implicit context: Context): Behavior[_] = Behaviors.setup[StreamEvent] {
    actorContext =>
      actorContext.system.receptionist ! Register(Service.streamService.serviceKey, actorContext.self)

      Behaviors.receiveMessage[StreamEvent] { case event =>
        context.monitor.ref ! event
        Behaviors.same
      }
  }

  protected val serviceKey: ServiceKey[_] = Service.streamService.serviceKey

  type Monitor = TestProbe[StreamEvent]
  protected def createMonitor(implicit system: ActorSystem[_]): Monitor = TestProbe()

  implicit var streamService: ActorStreamRefService = _

  def actors(num: Int)(implicit refService: ActorStreamRefService): Seq[ActorRef] = refService.actors(num)
  def clear(implicit refService: ActorStreamRefService): Unit                     = refService.clear

  override def beforeAll(): Unit = {
    super.beforeAll() // order is important!
    streamService = new ActorStreamRefService()
  }

  after {
    clear
  }

  final class ActorStreamRefService {
    private val probe = TestProbe[TagEvent]("stream_refs")

    def actors(number: Int): Seq[ActorRef] = probe
      .within(2.seconds) {
        val messages = probe.receiveMessages(number)
        probe.expectNoMessage(probe.remaining)
        messages.map(_.ref)
      }

    /**
     * Make sure no more actors are created
     */
    def clear: Unit = probe.expectNoMessage(2.seconds)

    system.receptionist ! Register(Service.tagService.serviceKey, probe.ref)
  }

  def offerMany[T](input: SourceQueue[T], elements: List[T])(implicit ec: ExecutionContext): Future[Done] = {
    def loop(remaining: List[T]): Future[Done] = remaining match {
      case Nil => Future.successful(Done)
      case head :: tail =>
        input.offer(head).flatMap {
          case QueueOfferResult.Enqueued => loop(tail)
          case _                         => Future.failed(BufferOverflowException(""))
        }
    }
    loop(elements)
  }

  def expectMany[T](output: SinkQueue[T], num: Long)(implicit ec: ExecutionContext): Future[Done] = {
    def loop(remaining: Long): Future[Done] =
      if (remaining <= 0) {
        Future.successful(Done)
      } else
        output
          .pull()
          .flatMap {
            case Some(_) =>
              loop(remaining - 1)
            case None =>
              Future.failed(new RuntimeException("Stream terminated before specified amount of elements was received"))
          }

    loop(num)
  }

  "AkkaStreamAgentTest" should "accurately detect push / demand" in testCase { implicit c =>
    implicit val ec: ExecutionContext = system.executionContext

    val Demand           = 5L
    val ExpectedElements = Demand + 1L // somehow sinkQueue demand 1 element in advance

    val (inputQueue, outputQueue) = Source
      .queue[Int](1024, OverflowStrategy.dropNew, 1)
      .named("InQueue")
      .via(
        Flow[Int]
          .filter(_ % 2 == 0)
          .named("Mod2")
          .map(x => if (x == 0) 0 else x / 2)
          .named("Div2")
      )
      .toMat(Sink.queue[Int]().withAttributes(Attributes.inputBuffer(1, 1)).named("OutQueue"))(Keep.both)
      .run()

    val elements = offerMany(inputQueue, List.tabulate(100)(identity))
      .zipWith(expectMany(outputQueue, Demand))((_, _) => Done)

    whenReady(elements) { _ =>
      val ref = actors(1).loneElement

      ref ! PushMetrics

      val stats = monitor.expectMessageType[StreamInterpreterStats]

      val (stages, connections) = stats.shellInfo.loneElement

      stages should have size 4
      connections should have size 3

      val Array(sinkMap, mapFilter, filterSource) = connections

      sinkMap.push shouldBe ExpectedElements
      sinkMap.pull shouldBe ExpectedElements

      mapFilter.push shouldBe ExpectedElements
      mapFilter.pull shouldBe ExpectedElements

      filterSource.push should be((ExpectedElements * 2) +- 1)
      filterSource.pull should be((ExpectedElements * 2) +- 1)
    }
  }

  it should "find correct amount of actors for async streams" in testCase { implicit c =>
    Source
      .single(())
      .async
      .map(_ => ())
      .async
      .to(Sink.ignore)
      .run()

    actors(3)
  }

  it should "find accurate amount of push / demand for async streams" in testCase { implicit c =>
    implicit val ec: ExecutionContext = system.executionContext

    // seems like all input / output boundaries introduce off by one changes in demand
    val Demand                 = 1L
    val SinkExpectedElements   = Demand + 1L // somehow sinkQueue demand 1 element in advance
    val FlowExpectedElements   = SinkExpectedElements + 1L
    val SourceExpectedElements = (FlowExpectedElements + 1L) * 2

    val (inputQueue, outputQueue) = Source
      .queue[Int](1024, OverflowStrategy.dropNew, 1)
      .withAttributes(Attributes.inputBuffer(1, 1))
      .async
      .filter(_ % 2 == 0)
      .map(x => if (x == 0) 0 else x / 2)
      .withAttributes(Attributes.inputBuffer(1, 1))
      .async
      .toMat(
        Sink
          .queue[Int]()
          .withAttributes(Attributes.inputBuffer(1, 1))
          .named("queueEnd")
      )(Keep.both)
      .run()

    val elements = offerMany(inputQueue, List.tabulate(100)(identity))
      .zipWith(expectMany(outputQueue, Demand))((_, _) => Done)

    whenReady(elements) { _ =>
      // are started in reverse order
      val Seq(sinkRef, flowRef, sourceRef) = actors(3)

      sinkRef ! PushMetrics

      val (sinkStages, sinkConnections) = monitor.expectMessageType[StreamInterpreterStats].shellInfo.loneElement

      sinkStages should have size 2
      sinkConnections should have size 1

      forAll(sinkConnections.toSeq) { connection =>
        connection.push should be(SinkExpectedElements)
        connection.pull should be(SinkExpectedElements)
      }

      flowRef ! PushMetrics

      val (flowStages, flowConnections) = monitor.expectMessageType[StreamInterpreterStats].shellInfo.loneElement

      flowStages should have size 4

      inside(flowConnections) { case Array(filterMap, inputFilter, mapOutput) =>
        filterMap.push should be(FlowExpectedElements)
        filterMap.pull should be(FlowExpectedElements)
        inputFilter.push should be(SourceExpectedElements - 1L)
        inputFilter.pull should be(SourceExpectedElements - 1L)
        mapOutput.push should be(FlowExpectedElements)
        mapOutput.pull should be(FlowExpectedElements)
      }

      sourceRef ! PushMetrics

      val (sourceStages, sourceConnections) = monitor.expectMessageType[StreamInterpreterStats].shellInfo.loneElement

      sourceStages should have size 2

      sourceConnections should have size 1

      forAll(sourceConnections.toSeq) { connection =>
        connection.push should be(SourceExpectedElements)
        connection.pull should be(SourceExpectedElements)
      }
    }
  }

  it should "receive stats of short living streams in" in testCase { implicit c =>
    def runShortStream: Unit = Source
      .single(())
      .to(Sink.ignore)
      .run()

    val StreamCount = 10

    for {
      _ <- 0 until StreamCount
    } runShortStream

    actors(StreamCount)

    forAll(monitor.receiveMessages(StreamCount)) {
      inside(_) { case LastStreamStats(_, _, shellInfo) =>
        val (stages, connectionStats) = shellInfo
        stages should have size (2)
        val connection = connectionStats.toSeq.loneElement
        connection.push should be(1L)
        connection.pull should be(1L)
      }
    }
  }

  it should "push information on shells interpreting flatten stream" in testCase { implicit c =>
    implicit val ec: ExecutionContext = system.executionContext

    val Demand = 40L

    val (inputQueue, outputQueue) = Source
      .queue[Int](1024, OverflowStrategy.dropNew, 1)
      .flatMapConcat(element => Source(List.fill(10)(element)).via(Flow[Int].map(_ + 100)))
      .toMat(
        Sink
          .queue[Int]()
          .withAttributes(Attributes.inputBuffer(1, 1))
          .named("queueEnd")
      )(Keep.both)
      .run()

    val elements = offerMany(inputQueue, List.tabulate(100)(identity))
      .zipWith(expectMany(outputQueue, Demand))((_, _) => Done)

    whenReady(elements) { _ =>
      val ref = actors(1).loneElement
      ref ! PushMetrics

      monitor.expectMessageType[StreamInterpreterStats].shellInfo should have size (2)
    }
  }
}
