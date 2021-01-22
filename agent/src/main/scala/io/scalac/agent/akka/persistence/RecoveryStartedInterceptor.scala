package io.scalac.agent.akka.persistence

import _root_.akka.actor.typed.scaladsl.ActorContext
import _root_.akka.persistence.typed.PersistenceId
import io.scalac.core.util.Timestamp
import io.scalac.extension.event.EventBus
import io.scalac.extension.event.PersistenceEvent.RecoveryStarted
import net.bytebuddy.asm.Advice

import scala.util.Try

class RecoveryStartedInterceptor

object RecoveryStartedInterceptor {
  import AkkaPersistenceAgent.logger

  private val setupField = {
    val setup = Class.forName("akka.persistence.typed.internal.ReplayingSnapshot").getDeclaredField("setup")
    setup.setAccessible(true)
    setup
  }
  private val persistenceIdField = {
    val persistenceId = Class.forName("akka.persistence.typed.internal.BehaviorSetup").getDeclaredField("persistenceId")
    persistenceId.setAccessible(true)
    persistenceId
  }

  val persistenceIdExtractor: Any => Try[PersistenceId] = ref => {
    for {
      setup         <- Try(setupField.get(ref))
      persistenceId <- Try(persistenceIdField.get(setup))
    } yield persistenceId.asInstanceOf[PersistenceId]
  }

  @Advice.OnMethodEnter
  def enter(
    @Advice.Argument(0) context: ActorContext[_],
    @Advice.This thiz: AnyRef
  ): Unit = {
    val path = context.self.path
    logger.trace("Started actor {} recovery", path)
    persistenceIdExtractor(thiz).fold(
      _.printStackTrace(),
      persistenceId =>
        EventBus(context.system)
          .publishEvent(RecoveryStarted(path.toString, persistenceId.id, Timestamp.create()))
    )
  }
}