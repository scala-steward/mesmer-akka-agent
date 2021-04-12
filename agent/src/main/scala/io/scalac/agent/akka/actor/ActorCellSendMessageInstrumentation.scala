package io.scalac.agent.akka.actor

import akka.actor.Actor
import net.bytebuddy.asm.Advice._

import io.scalac.core.actor.ActorCellDecorator
import io.scalac.core.util.ActorRefOps

class ActorCellSendMessageInstrumentation
object ActorCellSendMessageInstrumentation {

  @OnMethodEnter
  def onEnter(@Argument(0) envelope: Object): Unit =
    if (envelope != null) {
      EnvelopeDecorator.setTimestamp(envelope)
      val sender = EnvelopeOps.getSender(envelope)
      if (sender != Actor.noSender)
        for {
          cell <- ActorRefOps.Local.cell(sender)
          spy  <- ActorCellDecorator.get(cell)
        } spy.sentMessages.inc()

      /**
       * @todo I don't like here that this module depends on core. Please consider making it as a non-dependency module
       *       with well defined interface with an adapter defined in a main module (connecting agent and extension).
       */
    }

}
