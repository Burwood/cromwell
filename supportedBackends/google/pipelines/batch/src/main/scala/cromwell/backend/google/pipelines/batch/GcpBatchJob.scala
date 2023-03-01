package cromwell.backend.google.pipelines.batch
import com.google.api.gax.rpc.{FixedHeaderProvider, HeaderProvider}
import com.google.cloud.batch.v1.{AllocationPolicy, BatchServiceClient, BatchServiceSettings, ComputeResource, CreateJobRequest, Job, LogsPolicy, Runnable, TaskGroup, TaskSpec}
import com.google.cloud.batch.v1.AllocationPolicy.{InstancePolicy, InstancePolicyOrTemplate, LocationPolicy, NetworkInterface, NetworkPolicy}
import com.google.cloud.batch.v1.Runnable.Container
import cromwell.backend.google.pipelines.batch.GcpBatchBackendSingletonActor.BatchRequest
import com.google.protobuf.Duration
import com.google.cloud.batch.v1.LogsPolicy.Destination
import com.google.common.collect.ImmutableMap
import java.util.concurrent.TimeUnit
import org.slf4j.{Logger, LoggerFactory}


final case class GcpBatchJob (
                             jobSubmission: BatchRequest,
                             cpu: Long,
                             memory: Long,
                             machineType: String,
                             runtimeAttributes: GcpBatchRuntimeAttributes
                            ) {

  val log: Logger = LoggerFactory.getLogger(RunStatus.toString)

  // VALUES HERE
  private val entryPoint = "/bin/sh"
  private val retryCount = 2
  private val durationInSeconds: Long = 3600
  private val taskCount: Long = 1
  private val gcpBatchCommand: String = jobSubmission.gcpBatchCommand

  // set user agent
  private val user_agent_header = "user-agent"
  private val customUserAgentValue = "cromwell"
  private lazy val headerProvider: HeaderProvider = FixedHeaderProvider
    .create(ImmutableMap
      .of(user_agent_header, customUserAgentValue))

  private lazy val batchSettings = BatchServiceSettings.newBuilder.setHeaderProvider(headerProvider).build

  lazy val batchServiceClient = BatchServiceClient.create(batchSettings)

  lazy val parent = (String
    .format("projects/%s/locations/%s", jobSubmission
      .projectId, jobSubmission
      .region))
  private val cpuPlatform =  runtimeAttributes.cpuPlatform.getOrElse("")
  //private val bootDiskSize = runtimeAttributes.bootDiskSize
  private val noAddress = runtimeAttributes.noAddress
  private val zones = "zones/" + runtimeAttributes.zones.mkString(",")
  println(zones)

  private val preemption = runtimeAttributes.preemptible
  println(preemption)

  log.info(cpuPlatform)

  private def createRunnable(dockerImage: String, entryPoint: String): Runnable = {
    val runnable = Runnable.newBuilder.setContainer((Container.newBuilder.setImageUri(dockerImage).setEntrypoint(entryPoint).addCommands("-c").addCommands(gcpBatchCommand).build)).build
    runnable
  }

  private def createComputeResource(cpu: Long, memory: Long) = {
    ComputeResource
      .newBuilder
      .setCpuMilli(cpu)
      .setMemoryMib(memory)
      .build
  }

  private def createInstancePolicy = {
    val instancePolicy = InstancePolicy
      .newBuilder
      .setMachineType(machineType)
      //.setMinCpuPlatform(cpuPlatform)
      .build
    instancePolicy
  }


  private def createNetworkInterface(noAddress: Boolean) = {
    NetworkInterface
      .newBuilder
      .setNoExternalIpAddress(noAddress)
      .setNetwork("projects/batch-testing-350715/global/networks/default")
      .setSubnetwork("regions/us-central1/subnetworks/default")
      .build
  }

  private def createNetworkPolicy(networkInterface: NetworkInterface) = {
    NetworkPolicy
      .newBuilder
      .addNetworkInterfaces(0, networkInterface)
      .build()
  }

  private def createTaskSpec(runnable: Runnable, computeResource: ComputeResource, retryCount: Int, durationInSeconds: Long) = {
    TaskSpec
      .newBuilder
      .addRunnables(runnable)
      .setComputeResource(computeResource)
      .setMaxRetryCount(retryCount)
      .setMaxRunDuration(Duration
        .newBuilder
        .setSeconds(durationInSeconds)
        .build)
  }

  private def createTaskGroup(taskCount: Long, task: TaskSpec.Builder): TaskGroup = {
    TaskGroup
      .newBuilder
      .setTaskCount(taskCount)
      .setTaskSpec(task)
      .build

  }

  private def createAllocationPolicy(locationPolicy: LocationPolicy,  instancePolicy: InstancePolicy) = {
    AllocationPolicy
      .newBuilder
      .setLocation(locationPolicy)
      //.setNetwork(networkPolicy)
      .addInstances(InstancePolicyOrTemplate
        .newBuilder
        .setPolicy(instancePolicy)
        .build)
      .build
  }

  def submitJob(): Unit = {

    try {
      val runnable = createRunnable(dockerImage = runtimeAttributes.dockerImage, entryPoint = entryPoint)
      val networkInterface = createNetworkInterface(noAddress)
      val networkPolicy = createNetworkPolicy(networkInterface)
      val computeResource = createComputeResource(cpu, memory)
      val taskSpec = createTaskSpec(runnable, computeResource, retryCount, durationInSeconds)
      val taskGroup: TaskGroup = createTaskGroup(taskCount, taskSpec)
      val instancePolicy = createInstancePolicy
      val locationPolicy = LocationPolicy.newBuilder.addAllowedLocations(zones).build
      val allocationPolicy = createAllocationPolicy(locationPolicy, instancePolicy)
      val job = Job
        .newBuilder
        .addTaskGroups(taskGroup)
        .setAllocationPolicy(allocationPolicy)
        .putLabels("submitter", "cromwell") // label to signify job submitted by cromwell for larger tracking purposes within GCP batch
        .putLabels("cromwell-workflow-id", jobSubmission.workflowId.toString) // label to make it easier to match Cromwell workflows with multiple GCP batch jobs
        .putLabels("env", "testing")
        .putLabels("type", "script")
        .setLogsPolicy(LogsPolicy
          .newBuilder
          .setDestination(Destination
            .CLOUD_LOGGING)
          .build)

      val createJobRequest = CreateJobRequest
        .newBuilder
        .setParent(parent)
        .setJob(job)
        .setJobId(jobSubmission
          .jobName)
        .build()
      val result = batchServiceClient
        .createJobCallable
        .futureCall(createJobRequest)
        .get(5, TimeUnit
          .SECONDS)
      log.info("job submitted")
      batchServiceClient.close()
      log.info(result.getName)

    }
    catch  {
      case e: Throwable => log.info(s"Job failed with ${e}")
    }

  }

}