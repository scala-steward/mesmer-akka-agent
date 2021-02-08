package io.scalac.extension

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors

import org.scalatest.Inspectors
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import io.scalac.extension.ActorEventsMonitor.{ ActorTreeRunner, ReflectiveActorTreeRunner }
import io.scalac.extension.metric.ActorMetricMonitor
import io.scalac.extension.util.TestConfig.localActorProvider
import io.scalac.extension.util.probe.ActorMonitorTestProbe
import io.scalac.extension.util.probe.BoundTestProbe.MetricRecorded

class ActorEventsMonitorTest
    extends ScalaTestWithActorTestKit(localActorProvider)
    with AnyFlatSpecLike
    with Matchers
    with Inspectors {

  testActorTreeRunner(ReflectiveActorTreeRunner)
  testMonitor()

  def testActorTreeRunner(actorTreeRunner: ActorTreeRunner): Unit = {

    val system = createActorSystem()

    s"ActorTreeRunner instance (${actorTreeRunner.getClass.getName})" should "getRoot properly" in {
      val root = actorTreeRunner.getRootGuardian(system.classicSystem)
      root.path.toStringWithoutAddress should be("/user")
    }

    it should "getChildren properly" in {
      val root     = actorTreeRunner.getRootGuardian(system.classicSystem)
      val children = actorTreeRunner.getChildren(root)
      children.map(_.path.toStringWithoutAddress) should contain theSameElementsAs (Set(
        "/user/actorA",
        "/user/actorB"
      ))
    }

    it should "terminate actorSystem" in {
      system.terminate()
      Await.ready(system.whenTerminated, 5.seconds)
    }

  }

  def testMonitor(): Unit = {

    implicit val system: ActorSystem[String] = createActorSystem()
    val monitor                              = new ActorMonitorTestProbe()
    val pingOffset                           = 2.seconds

    ActorEventsMonitor.start(monitor, system, None, pingOffset)

    "ActorEventsMonitor" should "record mailbox size" in {
      val n = 10
      system ! "idle"
      for (_ <- 0 until n) system ! "Record it"
      val bound   = monitor.bind(ActorMetricMonitor.Labels("/user/actorB/idle", None))
      val records = bound.mailboxSizeProbe.receiveMessages(2, 3 * pingOffset)
      records should contain(MetricRecorded(n))
    }

    it should "terminate actorSystem" in {
      system.terminate()
      Await.ready(system.whenTerminated, 5.seconds)
    }
  }

  private def createActorSystem(): ActorSystem[String] =
    ActorSystem[String](
      Behaviors.setup[String] { ctx =>
        val actorA = ctx.spawn[String](
          Behaviors.setup[String] { ctx =>
            import ctx.log

            val actorAA = ctx.spawn[String](Behaviors.setup { ctx =>
              import ctx.log
              Behaviors.receiveMessage { msg =>
                log.info(s"[actorAA] received a message: $msg")
                Behaviors.same
              }
            }, "actorAA")

            Behaviors.receiveMessage { msg =>
              log.info(s"[actorA] received a message: $msg")
              actorAA ! msg
              Behaviors.same
            }
          },
          "actorA"
        )

        val actorB = ctx.spawn[String](
          Behaviors.setup { ctx =>
            import ctx.log

            val actorBIdle = ctx.spawn[String](
              Behaviors.setup { ctx =>
                import ctx.log
                Behaviors.receiveMessage {
                  case "idle" =>
                    log.info("[idle] ...")
                    Thread.sleep(5.seconds.toMillis)
                    Behaviors.same
                  case _ =>
                    Behaviors.same
                }
              },
              "idle"
            )

            Behaviors.receiveMessage { msg =>
              log.info(s"[actorB] received a message: $msg")
              actorBIdle ! msg
              Behaviors.same
            }
          },
          "actorB"
        )

        Behaviors.receiveMessage { msg =>
          actorA ! msg
          actorB ! msg
          Behaviors.same
        }
      },
      "testActorTreeRunner"
    )

}
