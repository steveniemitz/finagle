package com.twitter.finagle.postgresql

import com.twitter.finagle.Stack
import com.twitter.finagle.dispatch.ClientDispatcher.wrapWriteException
import com.twitter.finagle.dispatch.GenSerialClientDispatcher
import com.twitter.finagle.param.Stats
import com.twitter.finagle.postgresql.Params.Credentials
import com.twitter.finagle.postgresql.Params.Database
import com.twitter.finagle.postgresql.machine.HandshakeMachine
import com.twitter.finagle.postgresql.machine.SimpleQueryMachine
import com.twitter.finagle.postgresql.machine.StateMachine
import com.twitter.finagle.postgresql.transport.MessageDecoder
import com.twitter.finagle.postgresql.transport.MessageEncoder
import com.twitter.finagle.postgresql.transport.Packet
import com.twitter.finagle.transport.Transport
import com.twitter.util.Future
import com.twitter.util.Promise

class ClientDispatcher(
  transport: Transport[Packet, Packet],
  params: Stack.Params,
) extends GenSerialClientDispatcher[Request, Response, Packet, Packet](
  transport,
  params[Stats].statsReceiver
) {

  def write[M <: Messages.FrontendMessage](msg: M)(implicit encoder: MessageEncoder[M]): Future[Unit] =
    transport
      .write(encoder.toPacket(msg))
      .rescue {
        case exc => wrapWriteException(exc)
      }

  def read(): Future[Messages.BackendMessage] =
    transport.read().map(rep => MessageDecoder.fromPacket(rep)).lowerFromTry // TODO: better error handling

  def exchange[M <: Messages.FrontendMessage : MessageEncoder](msg: M): Future[Messages.BackendMessage] =
    write(msg) before read()

  def run[S,R](machine: StateMachine[S, R]) = {

    var state: S = null.asInstanceOf[S] // TODO

    def step(transition: StateMachine.TransitionResult[S, R]): Future[StateMachine.Complete[R]] = transition match {
      case StateMachine.Transition(s) =>
        state = s
        readAndStep
      case t@StateMachine.TransitionAndSend(s, msg) =>
        state = s
        write(msg)(t.encoder) before readAndStep
      case c: StateMachine.Complete[R] => Future.value(c)
    }

    def readAndStep =
      read().flatMap { msg => step(machine.receive(state, msg)) }

    step(machine.start)
  }

  def dispatch[S,R](machine: StateMachine[S,R], promise: Promise[R]): Future[Messages.ReadyForQuery] = {
    run(machine)
      .flatMap { case StateMachine.Complete(response, signal) =>
        promise.setValue(response)
        signal
      }
  }

  val handshakeResult: Promise[HandshakeMachine.HandshakeResult] = new Promise()

  val startup = dispatch(HandshakeMachine(params[Credentials], params[Database]), handshakeResult).unit

  override def apply(req: Request): Future[Response] =
    startup before { super.apply(req) }

  override protected def dispatch(req: Request, p: Promise[Response]): Future[Unit] =
    req match {
      case Sync =>
        val resp = exchange(Messages.Sync)
        p.become(resp.map(BackendResponse))
        resp.unit
      case Query(q) => dispatch(new SimpleQueryMachine(q), p).unit
    }
}
