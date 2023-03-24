package cromwell.backend.google.pipelines.batch

import cromwell.backend.BackendJobDescriptor
import cromwell.core.path.Path
import wom.runtime.WomOutputRuntimeExtractor
import cromwell.backend.google.pipelines.batch.io.GcpBatchAttachedDisk
import cromwell.backend.google.pipelines.batch.GcpBatchConfigurationAttributes.VirtualPrivateCloudConfiguration

import scala.concurrent.duration._
import cromwell.backend.google.pipelines.common.monitoring.{CheckpointingConfiguration, MonitoringImage}
import cromwell.backend.google.pipelines.common._ //remove later because from common

trait GcpBatchRequestFactory {

}



object GcpBatchRequestFactory {

  type MountsToEnv = List[String] => Map[String, String]

  /**
   * Input parameters that are not strictly needed by the user's command but are Cromwell byproducts.
   */
  case class DetritusInputParameters(
                                      executionScriptInputParameter: PipelinesApiFileInput,
                                      monitoringScriptInputParameter: Option[PipelinesApiFileInput]
                                    ) {
    def all: List[PipelinesApiFileInput] = List(executionScriptInputParameter) ++ monitoringScriptInputParameter
  }

  /**
   * Output parameters that are not produced by the user's command but are Cromwell byproducts.
   */
  case class DetritusOutputParameters(
                                       monitoringScriptOutputParameter: Option[PipelinesApiFileOutput],
                                       rcFileOutputParameter: PipelinesApiFileOutput,
                                       memoryRetryRCFileOutputParameter: PipelinesApiFileOutput
                                     ) {
    def all: List[PipelinesApiFileOutput] = memoryRetryRCFileOutputParameter :: List(rcFileOutputParameter) ++ monitoringScriptOutputParameter
  }

  /**
   * Bundle containing all input and output parameters to a PAPI job
   * Detrituses and actual inputs / outputs to the job are separated for more clarity and to leave open the possibility
   * to treat them differently.
   */
  case class InputOutputParameters(
                                    detritusInputParameters: DetritusInputParameters,
                                    jobInputParameters: List[PipelinesApiInput],
                                    jobOutputParameters: List[PipelinesApiOutput],
                                    detritusOutputParameters: DetritusOutputParameters,
                                    literalInputParameters: List[PipelinesApiLiteralInput]
                                  ) {
    lazy val fileInputParameters: List[PipelinesApiInput] = jobInputParameters ++ detritusInputParameters.all
    lazy val fileOutputParameters: List[PipelinesApiOutput] = detritusOutputParameters.all ++ jobOutputParameters
  }

  case class CreatePipelineDockerKeyAndToken(key: String, encryptedToken: String)

  case class CreatePipelineParameters(jobDescriptor: BackendJobDescriptor,
                                      runtimeAttributes: GcpBatchRuntimeAttributes,
                                      dockerImage: String,
                                      cloudWorkflowRoot: Path,
                                      cloudCallRoot: Path,
                                      commandScriptContainerPath: Path,
                                      logGcsPath: Path,
                                      inputOutputParameters: InputOutputParameters,
                                      projectId: String,
                                      computeServiceAccount: String,
                                      //googleLabels: Seq[GoogleLabel],
                                      preemptible: Boolean,
                                      pipelineTimeout: FiniteDuration,
                                      jobShell: String,
                                      //privateDockerKeyAndEncryptedToken: Option[CreatePipelineDockerKeyAndToken],
                                      womOutputRuntimeExtractor: Option[WomOutputRuntimeExtractor],
                                      adjustedSizeDisks: Seq[GcpBatchAttachedDisk],
                                      virtualPrivateCloudConfiguration: VirtualPrivateCloudConfiguration,
                                      retryWithMoreMemoryKeys: Option[List[String]],
                                      fuseEnabled: Boolean,
                                      //referenceDisksForLocalizationOpt: Option[List[GcpBatchAttachedDisk]],
                                      //monitoringImage: MonitoringImage,
                                      checkpointingConfiguration: CheckpointingConfiguration,
                                      enableSshAccess: Boolean,
                                      //vpcNetworkAndSubnetworkProjectLabels: Option[VpcAndSubnetworkProjectLabelValues],
                                      //dockerImageCacheDiskOpt: Option[String]
                                     ) {
    def literalInputs = inputOutputParameters.literalInputParameters

    def inputParameters = inputOutputParameters.fileInputParameters

    def outputParameters = inputOutputParameters.fileOutputParameters

    def allParameters = inputParameters ++ outputParameters
  }

}
