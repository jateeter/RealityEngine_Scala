package com.realityengine.actors

import akka.actor.{Actor, Props}
import com.realityengine.models._

/**
 * MachineActor — wraps a Machine instance so all processInput calls are
 * serialised through the actor's mailbox (FIFO, no locks required).
 *
 * Per-machine atomicity guarantee: messages are processed one at a time,
 * so sequence-state mutations inside Machine.processInput are never
 * interleaved with another concurrent processInput call on the same machine.
 *
 * Actors for different machines run concurrently, providing the throughput
 * of a parallel pipeline across machines while keeping intra-machine
 * processing strictly sequential.
 */
object MachineActor {
  sealed trait Command
  case class ProcessInput(
    inputVector:            Vector[Double],
    matchAlgorithmOverride: Option[ComparatorType] = None
  ) extends Command
  case object Reset       extends Command
  case object GetSnapshot extends Command
  case class AddSeq(seq: CriticalEventSequence)  extends Command
  case class RemoveSeq(seqId: String) extends Command

  case class ProcessInputResult(machineId: String, result: MachineTransitionResult)
  case class SnapshotResult(machine: Machine)

  def props(machine: Machine): Props = Props(new MachineActor(machine))
}

class MachineActor(private val machine: Machine) extends Actor {
  import MachineActor._

  def receive: Receive = {
    case ProcessInput(inputVector, matchAlgorithmOverride) =>
      sender() ! ProcessInputResult(machine.id, machine.processInput(inputVector, matchAlgorithmOverride))

    case Reset =>
      machine.reset()

    case GetSnapshot =>
      sender() ! SnapshotResult(machine.clone())

    case AddSeq(seq) =>
      machine.addSequence(seq)

    case RemoveSeq(seqId) =>
      machine.removeSequence(seqId)
  }
}
