package io.funcqrs.akka

import _root_.akka.actor._
import _root_.akka.pattern.pipe
import _root_.akka.persistence._
import io.funcqrs._
import io.funcqrs.akka.AggregateActor._
import io.funcqrs.behavior.Behavior
import io.funcqrs.interpreters.AsyncInterpreter

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

class AggregateActor[A <: AggregateLike](
  identifier: A#Id,
  behavior: Behavior[A],
  inactivityTimeout: Option[Duration] = None
)
    extends AggregateAliases with PersistentActor with ActorLogging {

  type Aggregate = A

  val interpreter = AsyncInterpreter(behavior)

  import context.dispatcher

  // persistenceId is always defined as the Aggregate.Identifier
  val persistenceId = identifier.value

  /** The aggregate instance if initialized, None otherwise */
  private var aggregateOpt: Option[Aggregate] = None

  /**
   * The lifecycle of the aggregate, by default [[Uninitialized]]
   */
  protected var state: State = Uninitialized

  private var eventsSinceLastSnapshot = 0

  // always compose with defaultReceive
  override def receiveCommand: Receive = initializing orElse defaultReceive

  /**
   * PartialFunction to handle commands when the Actor is in the [[Uninitialized]] state
   */
  protected def initializing: Receive = {

    val initialReceive: Receive = {

      case cmd: Command =>
        log.debug(s"Received creation cmd: $cmd")
        val eventualEvents = interpreter.handleCommand(cmd)
        val origSender = sender()

        eventualEvents map {
          events => SuccessfulCreation(events, origSender)
        } recover {
          case NonFatal(cause: DomainException) =>
            FailedCommand(cause, origSender, Uninitialized)
          case NonFatal(cause) =>
            log.error(cause, s"Error while processing creational command: $cmd")
            FailedCommand(cause, origSender, Uninitialized)
        } pipeTo self

        changeState(Busy)

    }

    // always compose with defaultReceive
    initialReceive orElse defaultReceive
  }

  /**
   * PartialFunction to handle commands when the Actor is in the [[Available]] state
   */
  protected def available: Receive = {

    val availableReceive: Receive = {

      case cmd: Command =>
        log.debug(s"Received cmd: $cmd")
        val eventualEvents = interpreter.handleCommand(aggregateOpt.get, cmd)
        val origSender = sender()

        eventualEvents.map {
          events => SuccessfulUpdate(events, origSender)
        } recover {
          case NonFatal(cause: DomainException) =>
            FailedCommand(cause, origSender, Available)
          case NonFatal(cause) =>
            log.error(cause, s"Error while processing update command: $cmd")
            FailedCommand(cause, origSender, Available)
        } pipeTo self

        changeState(Busy)
    }

    // always compose with defaultReceive
    availableReceive orElse defaultReceive
  }

  def onCommandFailure(failedCmd: FailedCommand): Unit = {
    failedCmd.origSender ! Status.Failure(failedCmd.cause)
    changeState(failedCmd.state)
  }

  private def busy: Receive = {

    case StateRequest(requester) => sendState(requester)
    case SuccessfulCreation(events, origSender) => onSuccessfulCreation(events, origSender)
    case SuccessfulUpdate(events, origSender) => onSuccessfulUpdate(events, origSender)
    case failedCmd: FailedCommand => onCommandFailure(failedCmd)

    case anyOther =>
      log.debug(s"received $anyOther while processing another command")
      stash()
  }

  protected def defaultReceive: Receive = {
    case StateRequest(requester) => sendState(requester)
    case Exists(requester) => requester ! aggregateOpt.isDefined
    case KillAggregate => context.stop(self)
  }

  /**
   * This method should be used as a callback handler for persist() method.
   * It will:
   * - apply the event on the aggregate effectively changing its state
   * - check if a snapshot needs to be saved.
   * @param evt DomainEvent that has been persisted
   */
  protected def afterEventPersisted(evt: Event): Unit = {

    aggregateOpt = applyEvent(evt)

    eventsSinceLastSnapshot += 1

    for {
      aggregate <- aggregateOpt
      if eventsSinceLastSnapshot >= eventsPerSnapshot
    } yield {
      log.debug(s"$eventsPerSnapshot events reached, saving snapshot")
      saveSnapshot(aggregate)
      eventsSinceLastSnapshot = 0
    }
  }

  /**
   * send a message containing the aggregate's state back to the requester
   * @param replyTo actor to send message to
   */
  protected def sendState(replyTo: ActorRef): Unit = {
    aggregateOpt match {
      case Some(aggregate) =>
        log.debug(s"sending aggregate state $aggregate to $replyTo")
        replyTo ! aggregate
      case None =>
        replyTo ! Status.Failure(new NoSuchElementException(s"aggregate $persistenceId not initialized"))
    }
  }

  /**
   * Apply event on the AggregateRoot.
   *
   * Creational events are only applied if Aggregate is not yet initialized (ie: None)
   * Update events are only applied on already initialized Aggregates (ie: Some(aggregate))
   *
   * All other combinations will be ignored and the current Aggregate state is returned.
   */
  def applyEvent(event: DomainEvent): Option[Aggregate] = {

    (aggregateOpt, event) match {

      // apply CreateEvent if not yet initialized
      case (None, evt: Event) => Some(behavior.onEvent(evt))

      // Update events are applied on current state
      case (Some(aggregate), evt: Event) => Some(behavior.onEvent(aggregate, evt))

      // Covers:
      // (Some, CreateEvent) and (None, UpdateEvent)
      // in both cases we must ignore it and return current state
      case _ => aggregateOpt
    }
  }

  /**
   * Recovery handler that receives persisted events during recovery. If a state snapshot
   * has been captured and saved, this handler will receive a [[SnapshotOffer]] message
   * followed by events that are younger than the offered snapshot.
   *
   * This handler must not have side-effects other than changing persistent actor state i.e. it
   * should not perform actions that may fail, such as interacting with external services,
   * for example.
   *
   */
  override val receiveRecover: Receive = {

    case SnapshotOffer(metadata, (state: State, data: Option[Aggregate] @unchecked)) =>
      eventsSinceLastSnapshot = 0
      log.debug("recovering aggregate from snapshot")
      restoreState(metadata, state, data)

    case SnapshotOffer(metadata, data: Aggregate @unchecked) =>
      eventsSinceLastSnapshot = 0
      log.debug("recovering aggregate from snapshot")
      restoreState(metadata, Available, Some(data))

    case RecoveryCompleted =>
      log.debug(s"aggregate '$persistenceId' has recovered, state = '$state'")

    case event: DomainEvent => onEvent(event)

    case unknown => log.debug(s"Unknown message on recovery")
  }

  protected def onEvent(evt: DomainEvent): Unit = {
    log.debug(s"Reapplying event $evt")
    eventsSinceLastSnapshot += 1
    aggregateOpt = applyEvent(evt)
    log.debug(s"State after event $aggregateOpt")

    changeState(Available)
  }

  /**
   * restore the lifecycle and state of the aggregate from a snapshot
   * @param metadata snapshot metadata
   * @param state the state of the aggregate
   * @param data the data of the aggregate
   */
  protected def restoreState(metadata: SnapshotMetadata, state: State, data: Option[Aggregate]) = {
    changeState(state)
    log.debug(s"restoring data $data")
    aggregateOpt = data
  }

  def changeState(state: State): Unit = {
    this.state = state
    this.state match {
      case Uninitialized =>
        log.debug(s"Initializing")
        context become initializing
        unstashAll() // actually not need, but we never know :-)

      case Available =>
        log.debug(s"Accepting commands...")
        context become available
        unstashAll()

      case Busy =>
        log.debug(s"Busy, only answering to GetState and command results.")
        context become busy
    }
  }

  /**
   * When a Creation Command completes we must:
   * - persist the event
   * - apply the event, ie: create the aggregate
   * - notify the original sender
   */
  private def onSuccessfulCreation(events: Events, origSender: ActorRef): Unit = {

    // extra check! persist it only if a listener is defined for each event
    if (behavior.canHandleEvents(events)) {

      // forall on an empty Seq always returns 'true' !!!!
      // and akka-persistence throw exception if an empty list of events are sent!
      if (events.nonEmpty) {
        persistAll(events) { evt =>
          afterEventPersisted(evt)
        }
      }

      origSender ! events

    } else {

      // collect events with listener
      val badEventsNames = events.collect {
        case e if !behavior.canHandleEvent(e) => e.getClass.getSimpleName
      }
      origSender ! Status.Failure(new CommandException(s"No event listeners defined for events: ${badEventsNames.mkString(",")}"))
    }

    changeState(Available)

  }

  /**
   * When a Update Command completes we must:
   * - persist the events
   * - apply the events to the current aggregate state
   * - notify the original sender
   */
  private def onSuccessfulUpdate(events: Events, origSender: ActorRef): Unit = {

    val aggregate = aggregateOpt.get

    // extra check! persist it only if a listener is defined for each event
    if (behavior.canHandleEvents(events, aggregate)) {

      // forall on an empty Seq always returns 'true' !!!!
      // and akka-persistence throw exception if an empty list of events are sent!
      if (events.nonEmpty) {
        persistAll(events) { evt =>
          afterEventPersisted(evt)
        }
      }
      origSender ! events

    } else {

      // collect events with listener
      val badEventsNames = events.collect {
        case e if !behavior.canHandleEvent(e, aggregate) => e.getClass.getSimpleName
      }
      origSender ! Status.Failure(new CommandException(s"No event listeners defined for events: ${badEventsNames.mkString(",")}"))
    }

    changeState(Available)
  }

  override def preStart() {
    inactivityTimeout.foreach { t =>
      log.debug(s"Setting timeout to $t")
      context.setReceiveTimeout(t)
    }
  }

  override def unhandled(message: Any) = {
    message match {
      case ReceiveTimeout =>
        log.info("Stopping")
        context.stop(self)
      case _ => super.unhandled(message)
    }
  }

  /**
   * Internal representation of a completed update command.
   */
  private case class SuccessfulCreation(events: Events, origSender: ActorRef)

  private case class SuccessfulUpdate(events: Events, origSender: ActorRef)

  private case class FailedCommand(cause: Throwable, origSender: ActorRef, state: State)

}

object AggregateActor {

  /**
   * state of Aggregate Root
   */
  sealed trait State

  case object Uninitialized extends State

  case object Available extends State

  case object Busy extends State

  /**
   * We don't want the aggregate to be killed if it hasn't fully restored yet,
   * thus we need some non AutoReceivedMessage that can be handled by akka persistence.
   */
  case object KillAggregate

  case class StateRequest(requester: ActorRef)
  case class Exists(requester: ActorRef)

  /**
   * Specifies how many events should be processed before new snapshot is taken.
   * TODO: make configurable
   */
  val eventsPerSnapshot = 10

}

class DomainException extends RuntimeException
