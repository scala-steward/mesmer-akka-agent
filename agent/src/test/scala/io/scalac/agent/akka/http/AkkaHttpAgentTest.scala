package io.scalac.agent.akka.http

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.Receptionist.{ Deregister, Register }
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{ RouteTestTimeout, ScalatestRouteTest }

import com.typesafe.config.{ Config, ConfigFactory }
import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.dynamic.scaffold.TypeValidation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import io.scalac.agent.utils.AgentLoaderOps
import io.scalac.extension.event.HttpEvent
import io.scalac.extension.event.HttpEvent.{ RequestCompleted, RequestStarted }
import io.scalac.extension.httpServiceKey

class AkkaHttpAgentTest extends AnyFlatSpec with ScalatestRouteTest with Matchers with AgentLoaderOps {

  // implicit val askTimeout = Timeout(1 minute)

  override def testConfig: Config = ConfigFactory.load("application-test")

  def loadAgent(): Unit = {
    val instrumentation = ByteBuddyAgent.install()
    val builder         = new AgentBuilder.Default().`with`(new ByteBuddy().`with`(TypeValidation.DISABLED))
    val modules         = Map(AkkaHttpAgent.moduleName -> AkkaHttpAgent.defaultVersion)
    AkkaHttpAgent.agent.installOn(builder, instrumentation, modules)
  }

  type Fixture = TestProbe[HttpEvent]

  val testRoute: Route = path("test") {
    get {
      complete("")
    }
  }

  def test(body: Fixture => Any): Any = {
    implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
    val monitor                                          = TestProbe[HttpEvent]
    Receptionist(typedSystem).ref ! Register(httpServiceKey, monitor.ref)
    body(monitor)
    Receptionist(typedSystem).ref ! Deregister(httpServiceKey, monitor.ref)
  }

  "AkkaHttpAgent" should "instrument routes to generate events on http requests" in test { monitor =>
    implicit val timeout = RouteTestTimeout(5 seconds)
    Get("/test") ~!> testRoute ~> check {
      status should be(StatusCodes.OK)
    }
    monitor.expectMessageType[RequestStarted]
    monitor.expectMessageType[RequestCompleted]
  }

}
