package io.scalac.mesmer.agent.akka.actor.impl

import akka.actor.typed.TypedActorContext
import io.scalac.mesmer.core.actor.ActorCellDecorator
import net.bytebuddy.asm.Advice.Argument
import net.bytebuddy.asm.Advice.OnMethodExit

object SupervisorHandleReceiveExceptionInstrumentation {

  @OnMethodExit(onThrowable = classOf[Throwable])
  def onExit(@Argument(0) context: TypedActorContext[_]): Unit =
    ActorCellDecorator.get(ClassicActorContextProviderOps.classicActorContext(context)).foreach { actorMetrics =>
      actorMetrics.failedMessages.inc()
      actorMetrics.exceptionHandledMarker.mark()
    }

}
