package cromwell.backend.google.pipelines.batch

import akka.actor.{Actor, ActorLogging, ActorRef}
import com.google.cloud.batch.v1.JobName

trait BatchApiAbortClient { this: Actor with ActorLogging =>

  def abortJob(jobName: JobName, backendSingletonActor: ActorRef): Unit = {
    backendSingletonActor ! GcpBatchBackendSingletonActor.Action.AbortJob(jobName)
  }

  def abortActorClientReceive: Actor.Receive = {
    case GcpBatchBackendSingletonActor.Event.JobAbortRequestSent(job) =>
      log.info(s"Job aborted on GCP: ${job.getName}")

    case GcpBatchBackendSingletonActor.Event.ActionFailed(jobName, cause) =>
      val msg = s"Failed to abort job ($jobName) from GCP"
      log.error(cause, msg)
  }
}
