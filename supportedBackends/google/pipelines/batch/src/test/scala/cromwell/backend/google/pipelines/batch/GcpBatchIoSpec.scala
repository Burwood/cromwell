package cromwell.backend.google.pipelines.batch

import com.google.api.client.http.HttpResponseException
import com.google.api.client.testing.http.{HttpTesting, MockHttpTransport, MockLowLevelHttpRequest, MockLowLevelHttpResponse}
import common.assertion.CromwellTimeoutSpec
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GcpBatchIoSpec extends AnyFlatSpec with CromwellTimeoutSpec with Matchers {

  behavior of "io"

  it should "consider 403 as a fatal exception" in {
    val transport = mockTransport(403)
    val request = transport.createRequestFactory().buildGetRequest(HttpTesting.SIMPLE_GENERIC_URL)
    val mockedResponse = intercept[HttpResponseException](request.execute())
    io.isFatalJesException(mockedResponse) should be(true)
  }

  it should "consider 429 as a transient exception" in {
    val transport = mockTransport(429)
    val request = transport.createRequestFactory().buildGetRequest(HttpTesting.SIMPLE_GENERIC_URL)
    val mockedResponse = intercept[HttpResponseException](request.execute())
    io.isTransientJesException(mockedResponse) should be(true)
  }

  private def mockTransport(statusCode: Int) = new MockHttpTransport() {
    override def buildRequest(method: String, url: String) = {
      new MockLowLevelHttpRequest() {
        override def execute() = new MockLowLevelHttpResponse().setStatusCode(statusCode)
      }
    }
  }
}
