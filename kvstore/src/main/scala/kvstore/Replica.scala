package kvstore

import akka.actor.{ OneForOneStrategy, Props, ActorRef, Actor }
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ ask, pipe }
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.PoisonPill
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.util.Timeout
import scala.language.postfixOps

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */
  
  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  arbiter ! Join

  val persistence = context.actorOf(persistenceProps)

  def receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    case Get(key, id) =>
      val valueOption = kv.get(key)
      sender ! GetResult(key, valueOption, id)
    case Insert(key, value, id) =>
      kv += (key -> value)
      sender ! OperationAck(id)
    case Remove(key, id) =>
      kv -= key
      sender ! OperationAck(id)
  }

  var snapshotSeq = 0
  var acks = Map.empty[Long, ActorRef]

  /* TODO Behavior for the replica role. */
  val replica: Receive = {

    case Get(key, id) =>
      val valueOption = kv.get(key)
      sender ! GetResult(key, valueOption, id)

    case Snapshot(key, valueOption, seq) =>
      if(seq < snapshotSeq)
        sender ! SnapshotAck(key, seq)

      if (seq == snapshotSeq) {
        valueOption match {
          case None => kv -= key
          case Some(value) => kv += key -> value
        }
        snapshotSeq += 1
        acks += seq -> sender
        def resendPersist: Unit = {
          if(acks.contains(seq)) {
            persistence ! Persist(key, valueOption, seq)
            context.system.scheduler.scheduleOnce(100 millis)(resendPersist)
          }
        }
        resendPersist
      }

    case Persisted(key, id) =>
       val sender = acks(id)
       acks -= id
       sender ! SnapshotAck(key, id)


  }

}
