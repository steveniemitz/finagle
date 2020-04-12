package com.twitter.finagle.postgresql.machine

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import com.twitter.finagle.postgresql.Messages
import com.twitter.finagle.postgresql.Messages.BackendKeyData
import com.twitter.finagle.postgresql.Messages.BackendMessage
import com.twitter.finagle.postgresql.Messages.ParameterStatus
import com.twitter.finagle.postgresql.Params
import com.twitter.io.Buf
import com.twitter.util.Future

case class HandshakeMachine(credentials: Params.Credentials, database: Params.Database) extends StateMachine[HandshakeMachine.State, HandshakeMachine.HandshakeResult] {
  import HandshakeMachine._

  override def start: StateMachine.TransitionResult[State, HandshakeResult] =
    StateMachine.Transition(Authenticating, Some(Messages.StartupMessage(user = credentials.username, database = database.name)))

  override def receive(state: State, msg: BackendMessage): StateMachine.TransitionResult[State, HandshakeResult] = (state, msg) match {
    case (Authenticating, Messages.AuthenticationMD5Password(salt)) =>
      def hex(input: Array[Byte]) = input.map(s => f"$s%02x").mkString
      def bytes(str: String) = str.getBytes(StandardCharsets.UTF_8)
      def md5(input: Array[Byte]*): String =
        hex(input.foldLeft(MessageDigest.getInstance("MD5")) { case(d,v) => d.update(v);d }.digest())

      val hashed = md5(
        bytes(md5(bytes(credentials.password.getOrElse("")), bytes(credentials.username))),
        Buf.ByteArray.Owned.extract(salt)
      )

      StateMachine.Transition(Authenticating, Some(Messages.PasswordMessage(s"md5$hashed")))
    case (Authenticating, Messages.AuthenticationOk) => // This can happen at Startup when there's no password
      StateMachine.Transition(BackendStarting(Nil, None), None)
    case (BackendStarting(params, bkd), p: Messages.ParameterStatus) =>
      StateMachine.Transition(BackendStarting(p :: params, bkd), None)
    case (BackendStarting(params, _), bkd: Messages.BackendKeyData) =>
      StateMachine.Transition(state = BackendStarting(params, Some(bkd)), None)
    case (BackendStarting(params, bkd), ready: Messages.ReadyForQuery) =>
      StateMachine.Complete(HandshakeResult(params, bkd.get), Future.value(ready))
    case _ =>
      sys.error(s"Unexpected msg $msg in state $state")
  }
}

object HandshakeMachine {
  sealed trait State
  case object Authenticating extends State
  case class BackendStarting(params: List[ParameterStatus], bkd: Option[BackendKeyData]) extends State

  case class HandshakeResult(parameters: List[ParameterStatus], backendData: BackendKeyData)
}
