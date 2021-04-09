package io.scalac.core.util

import akka.actor.PoisonPill
import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.util.Timeout
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should.Matchers
import org.scalatest.{ Inside, LoneElement }

trait ReceptionistOps extends TestOps with Eventually with Inside with LoneElement with Matchers {

  /**
   * Waits until ref is only service for serviceKey
   * @param ref
   * @param serviceKey
   */
  def onlyRef(ref: ActorRef[_], serviceKey: ServiceKey[_])(implicit system: ActorSystem[_], timeout: Timeout): Unit =
    eventually {
      val result = findServices(serviceKey)
      inside(result) { case serviceKey.Listing(res) =>
        val elem = res.loneElement
        elem should sameOrParent(ref)
      }
    }

  def killServices(serviceKey: ServiceKey[_])(implicit system: ActorSystem[_], timeout: Timeout): Unit = {
    val result = findServices(serviceKey)
    result.allServiceInstances(serviceKey).foreach(_.unsafeUpcast[Any] ! PoisonPill)
  }

  def noServices(serviceKey: ServiceKey[_])(implicit system: ActorSystem[_], timeout: Timeout): Unit =
    eventually {
      val result = findServices(serviceKey)
      result.allServiceInstances(serviceKey) should be(empty)
    }

  private def findServices(serviceKey: ServiceKey[_])(implicit system: ActorSystem[_], timeout: Timeout) =
    Receptionist(system).ref.ask[Listing](reply => Receptionist.find(serviceKey, reply)).futureValue

}
