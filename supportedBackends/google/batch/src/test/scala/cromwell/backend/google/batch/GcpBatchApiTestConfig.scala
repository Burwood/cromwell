package cromwell.backend.google.batch

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import cromwell.backend.BackendConfigurationDescriptor
import cromwell.backend.google.batch.models.{GcpBatchConfiguration, GcpBatchConfigurationAttributes}
//import cromwell.backend.google.pipelines.common.api.{PipelinesApiFactoryInterface, PipelinesApiRequestFactory}
import cromwell.cloudsupport.gcp.GoogleConfiguration
import cromwell.core.WorkflowOptions
import cromwell.core.filesystem.CromwellFileSystems
import cromwell.core.path.PathBuilder

import scala.concurrent.Await
import scala.concurrent.duration._

object GcpBatchApiTestConfig {

  // TODO: Update me
  private val GcpBatchBackendConfigString =
    """
      |project = "my-cromwell-workflows"
      |root = "gs://my-cromwell-workflows-bucket"
      |
      |genomics {
      |  auth = "application-default"
      |  endpoint-url = "https://lifesciences.googleapis.com/"
      |  location = "us-central1"
      |}
      |
      |filesystems.gcs.auth = "application-default"
      |
      |request-workers = 1
      |
      |default-runtime-attributes {
      |    cpu: 1
      |    failOnStderr: false
      |    continueOnReturnCode: 0
      |    docker: "ubuntu:latest"
      |    memory: "2048 MB"
      |    bootDiskSizeGb: 10
      |    disks: "local-disk 10 SSD"
      |    noAddress: false
      |    preemptible: 0
      |    zones:["us-central1-b", "us-central1-a"]
      |}
      |
      |""".stripMargin

  private val NoDefaultsConfigString =
    """
      |project = "my-cromwell-workflows"
      |root = "gs://my-cromwell-workflows-bucket"
      |
      |genomics {
      |  auth = "application-default"
      |  endpoint-url = "https://genomics.googleapis.com/"
      |}
      |
      |filesystems {
      |  gcs {
      |    auth = "application-default"
      |  }
      |}
      |""".stripMargin

  private val GcpBatchGlobalConfigString =
    s"""
       |google {
       |  application-name = "cromwell"
       |  auths = [
       |    {
       |      name = mock
       |      scheme = mock
       |    }
       |    {
       |      # legacy `application-default` auth that actually just mocks
       |      name = "application-default"
       |      scheme = "mock"
       |    }
       |  ]
       |}
       |
       |filesystems {
       |  gcs {
       |    class = "cromwell.filesystems.gcs.GcsPathBuilderFactory"
       |  }
       |}
       |
       |backend {
       |  default = "JES"
       |  providers {
       |    JES {
       |      actor-factory = "cromwell.backend.google.pipelines.common.PipelinesApiBackendLifecycleActorFactory"
       |      config {
       |      $GcpBatchBackendConfigString
       |      }
       |    }
       |  }
       |}
       |
       |""".stripMargin

  val GcpBatchBackendConfig: Config = ConfigFactory.parseString(GcpBatchBackendConfigString)
  val GcpBatchGlobalConfig: Config = ConfigFactory.parseString(GcpBatchGlobalConfigString)
  val GcpBatchBackendNoDefaultConfig: Config = ConfigFactory.parseString(NoDefaultsConfigString)
  val GcpBatchBackendConfigurationDescriptor: BackendConfigurationDescriptor = {
    new BackendConfigurationDescriptor(GcpBatchBackendConfig, GcpBatchGlobalConfig) {
      override private[backend] lazy val cromwellFileSystems = new CromwellFileSystems(GcpBatchGlobalConfig)
    }
  }
  val NoDefaultsConfigurationDescriptor: BackendConfigurationDescriptor =
    BackendConfigurationDescriptor(GcpBatchBackendNoDefaultConfig, GcpBatchGlobalConfig)
  def pathBuilders()(implicit as: ActorSystem): List[PathBuilder] =
    Await.result(GcpBatchBackendConfigurationDescriptor.pathBuilders(WorkflowOptions.empty), 5.seconds)
  val googleConfiguration: GoogleConfiguration = GoogleConfiguration(GcpBatchGlobalConfig)
  val gcpBatchAttributes: GcpBatchConfigurationAttributes =
    GcpBatchConfigurationAttributes(googleConfiguration, GcpBatchBackendConfig, "batch")
  val gcpBatchConfiguration = new GcpBatchConfiguration(GcpBatchBackendConfigurationDescriptor, googleConfiguration, gcpBatchAttributes)
}
