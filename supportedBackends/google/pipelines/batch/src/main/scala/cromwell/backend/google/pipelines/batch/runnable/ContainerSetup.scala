package cromwell.backend.google.pipelines.batch.runnable

import com.google.cloud.batch.v1.{Runnable, Volume}
import cromwell.backend.google.pipelines.batch.io.GcpBatchWorkingDisk

trait ContainerSetup {
  import RunnableLabels._

  // TODO: Use volumes or remove the argument
  def containerSetupRunnables(volumes: List[Volume]): List[Runnable] = {
    val containerRoot = GcpBatchWorkingDisk.MountPoint.pathAsString

    // As opposed to V1, the container root does not have a 777 umask, which can cause issues for docker running as non root
    // Run a first action to create the root and give it the right permissions
    val containerRootSetup = RunnableBuilder
      .cloudSdkShellAction(s"mkdir -p $containerRoot && chmod -R a+rwx $containerRoot")(
        labels = Map(Key.Tag -> Value.ContainerSetup)
      )

    RunnableBuilder.annotateTimestampedRunnable("container setup", Value.ContainerSetup)(List(containerRootSetup)).map(_.build())
  }
}
