package io.scalac.extension.util

import java.util.UUID

import akka.actor.typed.ActorRef
import org.scalatest.matchers.{MatchResult, Matcher}

trait TestOps {

  def createUniqueId: String = UUID.randomUUID().toString

  def sameOrParent(ref: ActorRef[_]): Matcher[ActorRef[_]] = left => {
    val test = ref.path == left.path || left.path.parent == ref.path

    MatchResult(test, s"${ref} is not same or parent of ${left}", s"${ref} is same as or parent of ${left}")
  }

}
