package io.scalac.mesmer.agent.akka.actor.impl

import io.scalac.mesmer.core.actor.ActorCellDecorator
import net.bytebuddy.asm.Advice.OnMethodExit
import net.bytebuddy.asm.Advice.This

object ActorUnhandledInstrumentation {

  @OnMethodExit
  def onExit(@This actor: Object): Unit =
    ActorCellDecorator.get(ClassicActorOps.getContext(actor)).foreach(_.unhandledMessages.inc())

}
