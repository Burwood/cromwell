package cromwell.backend.google.pipelines.batch.runnable

import com.google.cloud.batch.v1.Runnable.Container
import com.google.cloud.batch.v1.{Runnable, Volume}
import cromwell.backend.google.pipelines.batch.GcpBatchConfigurationAttributes.GcsTransferConfiguration
import cromwell.backend.google.pipelines.batch.{BatchParameter, GcpBatchInput, GcpBatchOutput}
import cromwell.core.path.Path
import mouse.all.anySyntaxMouse

import scala.jdk.CollectionConverters._

/**
 * Utility singleton to create high level batch runnables.
  *
  * NOTE: While porting this from Pipelines backend, we found out that Batch does not define volumes
  * at the container level but at the task level, hence, this file doesn't deal with volumes while
  * the Pipelines equivalent file deals with disks.
 */
object RunnableBuilder {

  import RunnableLabels._
  import RunnableUtils._

  implicit class EnhancedRunnableBuilder(val builder: Runnable.Builder) extends AnyVal {
    /**
      * Only for use with docker images KNOWN to not have entrypoints already set,
      * or used with accompanying call to setEntrypoint("non-empty-string").
      *
      * Otherwise use the withEntrypointCommand() workaround below since the google issue listed in BA-6406 is not being
      * fixed.
      */
    def withCommand(command: String*): Runnable.Builder = {
      val container = builder.getContainerBuilder.addAllCommands(command.toList.asJava)
      builder.setContainer(container)
    }

    def withEntrypointCommand(command: String*): Runnable.Builder = {
      builder
        .setContainer(
          builder.getContainerBuilder
            .setEntrypoint(command.headOption.orNull)
            .addAllCommands(
              command.drop(1).asJava
            )
        )
    }

    def withVolumes(volumes: List[Volume]): Runnable.Builder = {
      // TODO: Add mount options
      val formattedVolumes = volumes.map(v => s"${v.getMountPath}:${v.getMountPath}")
      builder.setContainer(
        builder.getContainerBuilder.addAllVolumes(formattedVolumes.asJava)
      )
    }

    def withAlwaysRun(alwaysRun: Boolean): Runnable.Builder = builder.setAlwaysRun(alwaysRun)

    def withRunInBackground(runInBackground: Boolean): Runnable.Builder = builder.setBackground(runInBackground)

    //  Runnable has labels in alpha.  Batch team adding to V1
//    def scalaLabels: Map[String, String] = {
//      val list = for {
//        keyValueList <- Option(runnable.getLabels).toList
//        keyValue <- keyValueList.asScala
//      } yield keyValue
//      list.toMap
//    }
  }

  def withImage(image: String): Runnable.Builder = {
    Runnable.newBuilder()
      .setContainer(Container.newBuilder.setImageUri(image))
  }

  private def cloudSdkContainerBuilder: Container.Builder = {
    Container.newBuilder.setImageUri(CloudSdkImage)
  }

  def monitoringImageScriptRunnable(cloudPath: Path, containerPath: Path, volumes: List[Volume])
                                 (implicit gcsTransferConfiguration: GcsTransferConfiguration): Runnable.Builder = {
    val command = RunnableCommands.localizeFile(cloudPath, containerPath)
    val labels = Map(Key.Tag -> Value.Localization)
    cloudSdkShellRunnable(command)(volumes = volumes, labels = labels)
  }

  def backgroundRunnable(image: String,
                       command: List[String],
//                       environment: Map[String, String],
                      ): Runnable.Builder = {
    withImage(image)
      .withEntrypointCommand(command: _*)
      .withRunInBackground(true)
//      .withIgnoreExitStatus(true)
//      .setEnvironment(environment.asJava)
//      .withLabels(Map(Key.Tag -> Value.Monitoring))
//      .setPidNamespace(backgroundActionPidNamespace)
  }


  def terminateBackgroundRunnablesRunnable(): Runnable.Builder = {
    cloudSdkShellRunnable(terminateAllBackgroundRunnablesCommand)(volumes = List.empty, labels = Map(Key.Tag -> Value.Monitoring))
      .withAlwaysRun(true)
//      .setPidNamespace(backgroundActionPidNamespace)
  }

  def gcsFileDeletionRunnable(cloudPath: String, volumes: List[Volume]): Runnable.Builder = {
    cloudSdkShellRunnable(
      s"""gsutil rm '$cloudPath'"""
    )(volumes = volumes, labels = Map(Key.Tag -> Value.Monitoring))
//      .withIgnoreExitStatus(true)
  }

  //privateDockerKeyAndToken: Option[CreatePipelineDockerKeyAndToken],
  //fuseEnabled: Boolean)
  def userRunnable(docker: String,
                   scriptContainerPath: String,
                   jobShell: String,
                   volumes: List[Volume]): Runnable.Builder = {

//    val dockerImageIdentifier = DockerImageIdentifier.fromString(docker)
//    val secret = for {
//      imageId <- dockerImageIdentifier.toOption
//      if DockerHub.isValidDockerHubHost(imageId.host) // This token only works for Docker Hub and not other repositories.
//      keyAndToken <- privateDockerKeyAndToken
//      s = new Secret().setKeyName(keyAndToken.key).setCipherText(keyAndToken.encryptedToken)
//    } yield s

    val container = Container.newBuilder
      .setImageUri(docker)
      .setEntrypoint(jobShell)
      .addCommands(scriptContainerPath)
    Runnable.newBuilder()
      .setContainer(container)
      .withVolumes(volumes)
    //.withLabels(labels)
    //.withTimeout(timeout)
  }

  def checkForMemoryRetryRunnable(retryLookupKeys: List[String], volumes: List[Volume]): Runnable.Builder = {
    cloudSdkShellRunnable(RunnableCommands.checkIfStderrContainsRetryKeys(retryLookupKeys))(
      volumes = volumes,
      labels = Map(Key.Tag -> Value.RetryWithMoreMemory)
    ).withAlwaysRun(true)
  }

  //  Needs label support
  // Creates a Runnable that logs the docker command for the passed in runnable.
  def describeDocker(description: String, runnable: Runnable.Builder): Runnable.Builder = {
    logTimestampedRunnable(
      s"Running $description: ${toDockerRun(runnable)}",
      List.empty,
      Map.empty
    )
  }

  private def timestampedMessage(message: String): String =
    s"""printf '%s %s\\n' "$$(date -u '+%Y/%m/%d %H:%M:%S')" ${shellEscaped(message)}"""

  private def logTimestampedRunnable(message: String, volumes: List[Volume], labels: Map[String, String]): Runnable.Builder = {
    // Uses the cloudSdk image as that image will be used for other operations as well.
    cloudSdkShellRunnable(
      timestampedMessage(message)
    )(volumes, labels)
  }

  // TODO: Use labels
  def cloudSdkShellRunnable(shellCommand: String)(volumes: List[Volume], labels: Map[String, String]): Runnable.Builder = {
    Runnable.newBuilder.setContainer(cloudSdkContainerBuilder)
      .withVolumes(volumes)
      .withEntrypointCommand(
        "/bin/sh",
        "-c",
        if (shellCommand.contains("\n")) shellCommand |> RunnableCommands.multiLineCommand else shellCommand
      )
  }

    def annotateTimestampedRunnable(description: String, loggingLabelValue: String, isAlwaysRun: Boolean = false)
                                (volumes: List[Volume], runnables: List[Runnable.Builder]): List[Runnable.Builder] = {

    val labels = Map(Key.Logging -> loggingLabelValue)
    val starting = logTimestampedRunnable(s"Starting $description.", volumes, labels).withAlwaysRun(isAlwaysRun)
    val done = logTimestampedRunnable(s"Done $description.", volumes, labels).withAlwaysRun(isAlwaysRun)
    List(starting) ++ runnables ++ List(done)
  }

  /**
    * Returns a set of labels for a parameter.
    *
    * @param parameter Input or output parameter to label.
    * @return The labels.
    */
  def parameterLabels(parameter: BatchParameter): Map[String, String] = {
    parameter match {
      case _: GcpBatchInput =>
        Map(
          Key.Tag -> Value.Localization,
          Key.InputName -> parameter.name
        )
      case _: GcpBatchOutput =>
        Map(
          Key.Tag -> Value.Delocalization,
          Key.OutputName -> parameter.name
        )
    }
  }

  /** Creates a Runnable that describes the parameter localization or delocalization. */
  def describeParameter(parameter: BatchParameter, volumes: List[Volume], labels: Map[String, String]): Runnable.Builder = {
    parameter match {
      case _: GcpBatchInput =>
        val message = "Localizing input %s -> %s".format(
          shellEscaped(parameter.cloudPath),
          shellEscaped(parameter.containerPath),
        )
        logTimestampedRunnable(message, volumes, labels)
      case _: GcpBatchOutput =>
        val message = "Delocalizing output %s -> %s".format(
          shellEscaped(parameter.containerPath),
          shellEscaped(parameter.cloudPath),
        )
        logTimestampedRunnable(message, volumes, labels).withAlwaysRun(true)
    }
  }

  // Converts an Runnable to a `docker run ...` command runnable in the shell.
  private[runnable] def toDockerRun(runnable: Runnable.Builder): String = {
    // TODO: Handle extra arguments like volumes
    runnable.getContainer
      .getCommandsList
      .asScala
      .toList
      .map { cmd => shellEscaped(cmd) }
      .mkString(" ")
  }
}
