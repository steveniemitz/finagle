package com.twitter.finagle.postgresql.machine

import com.twitter.finagle.postgresql.BackendMessage
import com.twitter.finagle.postgresql.BackendMessage.DataRow
import com.twitter.finagle.postgresql.BackendMessage.EmptyQueryResponse
import com.twitter.finagle.postgresql.BackendMessage.RowDescription
import com.twitter.finagle.postgresql.FrontendMessage
import com.twitter.finagle.postgresql.PgSqlServerError
import com.twitter.finagle.postgresql.PropertiesSpec
import com.twitter.finagle.postgresql.Response
import com.twitter.finagle.postgresql.machine.StateMachine.Complete
import com.twitter.finagle.postgresql.machine.StateMachine.Respond
import com.twitter.finagle.postgresql.machine.StateMachine.Send
import com.twitter.finagle.postgresql.machine.StateMachine.Transition
import com.twitter.util.Return
import com.twitter.util.Throw
import org.specs2.matcher.MatchResult

class SimpleQueryMachineSpec extends MachineSpec[Response] with PropertiesSpec {

  def mkMachine(q: String): SimpleQueryMachine = new SimpleQueryMachine(q)

  val readyForQuery = BackendMessage.ReadyForQuery(BackendMessage.NoTx)

  def checkQuery(q: String) =
    checkResult("sends a query message") {
      case Transition(_, Send(FrontendMessage.Query(str))) =>
        str must_== q
    }

  def checkCompletes =
    checkResult("completes") {
      case Complete(ready, response) =>
        ready must beEqualTo(readyForQuery)
        response must beNone
    }

  "SimpleQueryMachine" should {

    "send the provided query string" in prop { query: String =>
      machineSpec(mkMachine(query)) {
        checkQuery(query)
      }
    }

    "support empty queries" in {
      machineSpec(mkMachine(""))(
        checkQuery(""),
        receive(BackendMessage.EmptyQueryResponse),
        checkResult("responds") {
          case Transition(_, Respond(value)) =>
            value.asScala must beASuccessfulTry.withValue(beEqualTo(Response.BackendResponse(EmptyQueryResponse)))
        },
        receive(readyForQuery),
        checkCompletes
      )
    }

    "support commands" in prop { (command: String, commandTag: String) =>
      val commandComplete = BackendMessage.CommandComplete(commandTag)
      machineSpec(mkMachine(command))(
        checkQuery(command),
        receive(commandComplete),
        checkResult("responds") {
          case Transition(_, Respond(value)) =>
            value.asScala must beASuccessfulTry.withValue(beEqualTo(Response.BackendResponse(commandComplete)))
        },
        receive(readyForQuery),
        checkCompletes
      )
    }

    def resultSetSpec(query: String, rowDesc: RowDescription, rows: List[DataRow])(f: Seq[DataRow] => MatchResult[_]) = {
      var rowReader: Option[Response.ResultSet] = None

      val prep = List(
        checkQuery(query),
        receive(rowDesc),
        checkResult("responds") {
          case Transition(_, Respond(value)) =>
            value.asScala must beASuccessfulTry
            value.get must beLike {
              case rs@Response.ResultSet(desc, _) =>
                rowReader = Some(rs)
                desc must beEqualTo(rowDesc)
            }
        }
      )

      val sendRows = rows.map(receive(_))

      val post = List(
        receive(BackendMessage.CommandComplete("command tag")),
        receive(readyForQuery),
        checkCompletes
      )

      oneMachineSpec(mkMachine(query))(prep ++ sendRows ++ post: _*)
      rowReader must beSome
      rowReader.get.toSeq.map(f)

      rowReader = None
      // NOTE: machineErrorSpec returns a Prop which we combine with another using &&
      //   It's kind of weird, but specs2 isn't really helping here.
      machineErrorSpec(mkMachine(query))(prep ++ sendRows ++ post: _*) && {
        // NOTE: the randomization of the error makes it possible that:
        //   * we read no rows at all
        //   * we read all rows (and the error isn't surfaced)
        //   * we read partial rows and then an exception
        rowReader match {
          case None => ok
          case Some(r) =>
            rowReader = None // TODO: the statefulness of the test is pretty brittle
            r.toSeq.liftToTry.map {
              case Return(rows) => f(rows) // if we read all rows, then we should check that they're what we expect
              case Throw(t) => t must beAnInstanceOf[PgSqlServerError] // the error should surface here
            }
        }
      }
    }

    "support empty result sets" in prop { rowDesc: RowDescription =>
      resultSetSpec("select 1;", rowDesc, Nil) { rows =>
        rows must beEmpty
      }
    }

    "return rows in order" in prop { rs: TestResultSet =>
      resultSetSpec("select 1;", rs.desc, rs.rows) { rows =>
        rows must beEqualTo(rs.rows)
      }
    }
  }
}
