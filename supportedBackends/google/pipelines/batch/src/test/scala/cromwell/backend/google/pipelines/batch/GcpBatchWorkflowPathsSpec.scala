package cromwell.backend.google.pipelines.batch

import com.google.cloud.NoCredentials
import common.collections.EnhancedCollections._
import cromwell.backend.google.pipelines.batch.GcpBatchTestConfig._
import cromwell.backend.{BackendSpec, BackendWorkflowDescriptor}
import cromwell.core.TestKitSuite
import cromwell.util.SampleWdl
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.{JsObject, JsString}
import scala.concurrent.ExecutionContext.Implicits.global

class GcpBatchWorkflowPathsSpec extends TestKitSuite with AnyFlatSpecLike with Matchers {
  import BackendSpec._

  behavior of "GcpBatchWorkflowPaths"

  var workflowDescriptor: BackendWorkflowDescriptor = _
  var workflowPaths: GcpBatchWorkflowPaths = _

  override def beforeAll(): Unit = {
    workflowDescriptor = buildWdlWorkflowDescriptor(
      SampleWdl.HelloWorld.workflowSource(),
      inputFileAsJson = Option(JsObject(SampleWdl.HelloWorld.rawInputs.safeMapValues(JsString.apply)).compactPrint)
    )
    workflowPaths = GcpBatchWorkflowPaths(workflowDescriptor, NoCredentials.getInstance(), NoCredentials.getInstance(), batchConfiguration, pathBuilders(), GcpBatchInitializationActor.defaultStandardStreamNameToFileNameMetadataMapper)
  }

  it should "map the correct paths" in {
    workflowPaths.executionRoot.pathAsString should be("gs://my-cromwell-workflows-bucket/")
    workflowPaths.workflowRoot.pathAsString should
      be(s"gs://my-cromwell-workflows-bucket/wf_hello/${workflowDescriptor.id}/")
    workflowPaths.gcsAuthFilePath.pathAsString should
      be(s"gs://my-cromwell-workflows-bucket/wf_hello/${workflowDescriptor.id}/${workflowDescriptor.id}_auth.json")
  }

  it should "calculate the call cache path prefix from the workflow execution root correctly" in {
    val WorkspaceBucket = "gs://workspace-id"
    val ExecutionRoot = WorkspaceBucket + "/submission-id"
//    GcpBatchWorkflowPaths.callCachePathPrefixFromExecutionRoot(ExecutionRoot) shouldBe WorkspaceBucket
  }
}
