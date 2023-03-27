package example

import io.circe.Json
import io.scalac.mesmer.e2e.ExampleTestHarness
import org.scalatest.EitherValues
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ExampleAkkaTest
    extends AnyWordSpec
    with ExampleTestHarness
    with Matchers
    with Eventually
    with IntegrationPatience
    with EitherValues {

  "Akka example" should {
    "produce metrics" in withExample("exampleAkka/run", startTestString = "Starting http server at") { container =>
      eventually {
        prometheusApiRequest(container)(
          "promexample_mesmer_akka_actor_mailbox_size",
          response =>
            response.hcursor
              .downField("data")
              .downField("result")
              .as[Seq[Json]]
              .value should not be empty
        )
      }
    }
  }
}
