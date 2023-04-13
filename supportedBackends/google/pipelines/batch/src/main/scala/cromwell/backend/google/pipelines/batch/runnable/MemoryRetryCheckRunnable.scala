package cromwell.backend.google.pipelines.batch.runnable

import com.google.cloud.batch.v1.{Runnable, Volume}
import cromwell.backend.google.pipelines.batch.api.GcpBatchRequestFactory.CreatePipelineParameters

trait MemoryRetryCheckRunnable {

  def checkForMemoryRetryRunnables(createPipelineParameters: CreatePipelineParameters, mounts: List[Volume]): List[Runnable] = {
    createPipelineParameters.retryWithMoreMemoryKeys match {
      case Some(keys) => List(RunnableBuilder.checkForMemoryRetryRunnable(keys)).map(_.build)
      case None => List.empty[Runnable]
    }
  }
}
