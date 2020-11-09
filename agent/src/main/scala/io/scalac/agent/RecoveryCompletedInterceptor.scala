package io.scalac.agent

import java.lang.reflect.Method

import akka.actor.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import io.scalac.metrics.akka.persistence.AkkaPersistenceMetrics
import net.bytebuddy.asm.Advice

import scala.collection.JavaConverters._
class RecoveryCompletedInterceptor

object RecoveryCompletedInterceptor {

  lazy val config = ConfigFactory.load()
  lazy val metrics = AkkaPersistenceMetrics.instance(
    "node",
    config.getStringList("io.scalac.akka-persistence-monitoring.entities").asScala.toSet
  )

  @Advice.OnMethodEnter
  def enter(
    @Advice.Origin method: Method,
    @Advice.AllArguments parameters: Array[Object],
    @Advice.This thiz: Object
  ): Unit = {
    System.out.println("Recovery completion intercepted. Method: " + method + ", This: " + thiz)
    val actorPath      = parameters(0).asInstanceOf[ActorContext[_]].self.path.toStringWithoutAddress
    val recoveryTimeMs = System.currentTimeMillis - AkkaPersistenceAgentState.recoveryStarted.get(actorPath)
    System.out.println("Recovery took " + recoveryTimeMs + "ms for actor " + actorPath)
    AkkaPersistenceAgentState.recoveryMeasurements.put(actorPath, recoveryTimeMs)
    metrics.persistentActorRecoveryTime.get(actorPath).foreach(_.record(recoveryTimeMs))
  }
}
