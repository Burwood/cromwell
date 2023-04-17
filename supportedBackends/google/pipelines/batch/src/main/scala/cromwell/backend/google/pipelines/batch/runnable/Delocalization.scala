package cromwell.backend.google.pipelines.batch.runnable

import com.google.cloud.batch.v1.{Runnable, Volume}
import common.util.StringUtil._
import cromwell.backend.google.pipelines.batch.GcpBatchConfigurationAttributes.GcsTransferConfiguration
import cromwell.backend.google.pipelines.batch.GcpBatchJobPaths.GcsDelocalizationScriptName
import cromwell.backend.google.pipelines.batch.GcpBatchParameterConversions._
import cromwell.backend.google.pipelines.batch.ToParameter.ops._
import cromwell.backend.google.pipelines.batch._
import cromwell.backend.google.pipelines.batch.api.GcpBatchRequestFactory.CreatePipelineParameters
import cromwell.core.path.{DefaultPathBuilder, Path}
import wom.runtime.WomOutputRuntimeExtractor

import java.util.UUID
import scala.concurrent.duration._

trait Delocalization {

  import RunnableBuilder._
  import RunnableCommands._
  import RunnableLabels._
  import RunnableUtils._

  private def delocalizeLogsRunnable(gcsLogPath: Path)(implicit gcsTransferConfiguration: GcsTransferConfiguration) = {
    cloudSdkShellRunnable(
      delocalizeDirectory(DefaultPathBuilder.build(logsRoot).get, gcsLogPath, GcpBatchAsyncBackendJobExecutionActor.plainTextContentType)
    )(labels = Map(Key.Tag -> Value.Delocalization))
      .withAlwaysRun(true)
  }

  // Used for the final copy of the logs to make sure we have the most up to date version before terminating the job
  private def copyAggregatedLogToLegacyPath(gcsLegacyLogPath: Path)
                                           (implicit gcsTransferConfiguration: GcsTransferConfiguration): Runnable.Builder = {
    cloudSdkShellRunnable(
      delocalizeFileTo(DefaultPathBuilder.build(aggregatedLog).get, gcsLegacyLogPath, GcpBatchAsyncBackendJobExecutionActor.plainTextContentType)
    )(labels = Map(Key.Tag -> Value.Delocalization)).withAlwaysRun(true)
  }

  // Periodically copies the logs out to GCS
  private def copyAggregatedLogToLegacyPathPeriodic(gcsLegacyLogPath: Path)
                                                   (implicit gcsTransferConfiguration: GcsTransferConfiguration): Runnable.Builder = {
    cloudSdkShellRunnable(
      every(30.seconds) { delocalizeFileTo(DefaultPathBuilder.build(aggregatedLog).get, gcsLegacyLogPath, GcpBatchAsyncBackendJobExecutionActor.plainTextContentType) }
    )(labels = Map(Key.Tag -> Value.Background))
      .withRunInBackground(true)
  }

  private def runtimeOutputExtractorRunnable(containerCallRoot: String,
                                           outputFile: String,
                                           womOutputRuntimeExtractor: WomOutputRuntimeExtractor): Runnable.Builder = {
    val commands = List(
      "-c",
      // Create the directory where the fofn will be written
      s"mkdir -p $$(dirname $outputFile) && " +
        s"cd $containerCallRoot && " +
        """echo "Runtime output files to be delocalized:" && """ +
        s"${womOutputRuntimeExtractor.command} | tee $outputFile"
    )

    RunnableBuilder
      .withImage(womOutputRuntimeExtractor.dockerImage.getOrElse(CloudSdkImage))
      .withCommand(commands: _*) // TODO: Both calls likely need to be together
      .withEntrypointCommand("/bin/bash")
      // Saves us some time if something else fails before we get to run this runnable
//      .withDisableImagePrefetch(true)
//      .withLabels(Map(Key.Tag -> Value.Delocalization))
  }

  private def delocalizeRuntimeOutputsScript(fofnPath: String, workflowRoot: Path, cloudCallRoot: Path)(implicit gcsTransferConfiguration: GcsTransferConfiguration) = {
    val gsutilCommand: String => String = { flag =>
      s"""rm -f $$HOME/.config/gcloud/gce && gsutil -m $flag cp -r $$line "${cloudCallRoot.pathAsString.ensureSlashed}$$gcs_path""""
    }

    def sedStripPrefix(prefix: String) = s"""sed -e "s/^${prefix.ensureSedEscaped}//""""

    // See RuntimeOutputMapping.prefixFilters for more info on why this is needed
    val prefixFilters = RuntimeOutputMapping
      .prefixFilters(workflowRoot)
      .map(sedStripPrefix)
      .mkString(" | ")

    /*
     * Delocalize all the files returned by the runtime output extractor
     */
    s"""|#!/bin/bash
        |
        |set -x
        |
        |if [ -f $fofnPath ]; then
        |  while IFS= read line
        |  do
        |    gcs_path=$$(echo $$line | $prefixFilters)
        |    (
        |       ${retry(recoverRequesterPaysError(cloudCallRoot)(gsutilCommand))}
        |    )
        |  done  <$fofnPath
        |fi""".stripMargin
  }

  private def delocalizeRuntimeOutputsRunnable(cloudCallRoot: Path, inputFile: String, workflowRoot: Path)(implicit gcsTransferConfiguration: GcsTransferConfiguration): Runnable.Builder = {
    val command = multiLineCommand(delocalizeRuntimeOutputsScript(inputFile, workflowRoot, cloudCallRoot))
    RunnableBuilder.cloudSdkShellRunnable(command)(labels = Map(Key.Tag -> Value.Delocalization))
//      .withDisableImagePrefetch(true)
  }

  def deLocalizeRunnables(createPipelineParameters: CreatePipelineParameters,
                          volumes: List[Volume])(implicit gcsTransferConfiguration: GcsTransferConfiguration): List[Runnable] = {
    val cloudCallRoot = createPipelineParameters.cloudCallRoot
    val callExecutionContainerRoot = createPipelineParameters.commandScriptContainerPath.parent

    // TODO: Consider renaming to batch-logs, find where this is created
    val gcsLogDirectoryPath = createPipelineParameters.cloudCallRoot / "pipelines-logs"
    val gcsLegacyLogPath = createPipelineParameters.logGcsPath

    /*
     * Ideally temporaryFofnForRuntimeOutputFiles should be somewhere else than the execution directory (we could mount anther directory)
     * However because it runs after everything else there's no risk of polluting the task's results and the random ID ensures we don't override anything
     */
    val temporaryFofnDirectoryForRuntimeOutputFiles = callExecutionContainerRoot.pathAsString.ensureSlashed + UUID.randomUUID().toString.split("-")(0)
    val temporaryFofnForRuntimeOutputFiles = temporaryFofnDirectoryForRuntimeOutputFiles + "/runtime_output_files.txt"

    val runtimeExtractionRunnables = createPipelineParameters.womOutputRuntimeExtractor.toList flatMap { extractor =>
      List (
        runtimeOutputExtractorRunnable(callExecutionContainerRoot.pathAsString, temporaryFofnForRuntimeOutputFiles, extractor),
        delocalizeRuntimeOutputsRunnable(cloudCallRoot, temporaryFofnForRuntimeOutputFiles, createPipelineParameters.cloudWorkflowRoot)
      )
    }

    val gcsDelocalizationContainerPath = createPipelineParameters.commandScriptContainerPath.sibling(GcsDelocalizationScriptName)

    val delocalizationLabel = Map(Key.Tag -> Value.Delocalization)
    val runGcsDelocalizationScript = cloudSdkShellRunnable(
      s"/bin/bash $gcsDelocalizationContainerPath")(labels = delocalizationLabel)

    val annotatedRunnables: List[Runnable.Builder] = runGcsDelocalizationScript ::
      createPipelineParameters.outputParameters.flatMap(_.toRunnables(volumes)) ++
        runtimeExtractionRunnables

    // TODO: Rename to logs
    val _ = List(
      copyAggregatedLogToLegacyPath(gcsLegacyLogPath),
      copyAggregatedLogToLegacyPathPeriodic(gcsLegacyLogPath),
      delocalizeLogsRunnable(gcsLogDirectoryPath)
    )
    val all = RunnableBuilder.annotateTimestampedRunnable("delocalization", Value.Delocalization)(
      annotatedRunnables
    ) // ++ logs
    // TODO: Enable these ^^ once /google directory gets mounted or once we figure out where it is

    all.map(_.build)
  }
}
