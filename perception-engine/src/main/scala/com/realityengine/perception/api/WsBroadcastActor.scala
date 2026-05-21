package com.realityengine.perception.api

import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}

import scala.collection.mutable

object WsBroadcastActor {
  case class Subscribe(queue: SourceQueueWithComplete[String])
  case class Unsubscribe(queue: SourceQueueWithComplete[String])
  case class BroadcastMsg(payload: String)

  def props(): Props = Props(new WsBroadcastActor)

  /**
   * Build a WS Flow for a single client connection and register its queue
   * with the broadcast actor so it receives all future broadcasts.
   *
   * Inbound client messages are discarded (perception engine is push-only).
   */
  def buildWsFlow(actor: ActorRef)(implicit mat: Materializer): Flow[Message, Message, Any] = {
    val (queue, source) = Source
      .queue[String](256, OverflowStrategy.dropHead)
      .preMaterialize()

    actor ! Subscribe(queue)

    // When the stream completes/fails, unsubscribe the queue
    val outSource = source
      .map[Message](TextMessage(_))
      .watchTermination() { (_, done) =>
        done.onComplete { _ =>
          actor ! Unsubscribe(queue)
        }(mat.executionContext)
        akka.NotUsed
      }

    Flow.fromSinkAndSource(Sink.ignore, outSource)
  }
}

class WsBroadcastActor extends Actor {
  import WsBroadcastActor._

  private val clients: mutable.ListBuffer[SourceQueueWithComplete[String]] =
    mutable.ListBuffer.empty

  def receive: Receive = {
    case Subscribe(q)   =>
      clients += q

    case Unsubscribe(q) =>
      clients -= q

    case BroadcastMsg(payload) =>
      val dead = mutable.ListBuffer.empty[SourceQueueWithComplete[String]]
      for (q <- clients) {
        import akka.stream.QueueOfferResult
        q.offer(payload).foreach {
          case QueueOfferResult.QueueClosed => dead += q
          case _                            =>
        }(context.dispatcher)
      }
      clients --= dead
  }
}
