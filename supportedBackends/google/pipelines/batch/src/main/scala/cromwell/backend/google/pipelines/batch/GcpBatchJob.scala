package cromwell.backend.google.pipelines.batch
import com.google.api.gax.rpc.{FixedHeaderProvider, HeaderProvider}
//import com.google.cloud.batch.v1.AllocationPolicy._
import com.google.cloud.batch.v1.{AllocationPolicy, BatchServiceClient, BatchServiceSettings, ComputeResource, CreateJobRequest, Job, LogsPolicy, Runnable, TaskGroup, TaskSpec}
import com.google.cloud.batch.v1.AllocationPolicy.{InstancePolicy, InstancePolicyOrTemplate, LocationPolicy, NetworkInterface, NetworkPolicy, ProvisioningModel}
import cromwell.backend.google.pipelines.batch.GcpBatchBackendSingletonActor.GcpBatchRequest
//import com.google.cloud.batch.v1.AllocationPolicy.{InstancePolicy, InstancePolicyOrTemplate, LocationPolicy, NetworkInterface, NetworkPolicy}
import com.google.cloud.batch.v1.Runnable.Container
import com.google.protobuf.Duration
import com.google.cloud.batch.v1.LogsPolicy.Destination
import com.google.common.collect.ImmutableMap
import java.util.concurrent.TimeUnit
import org.slf4j.{Logger, LoggerFactory}
import scala.jdk.CollectionConverters._


final case class GcpBatchJob (
                             jobSubmission: GcpBatchRequest,
                             //cpu: Long,
                             //memory: Long,
                             machineType: String
                            ) extends BatchUtilityConversions{

  val log: Logger = LoggerFactory.getLogger(RunStatus.toString)

  // VALUES HERE
  private val entryPoint = "/bin/sh"
  private val retryCount = jobSubmission.gcpBatchParameters.runtimeAttributes.preemptible
  private val durationInSeconds: Long = 3600
  private val taskCount: Long = 1
  private val gcpBatchCommand: String = jobSubmission.gcpBatchCommand

  private val vpcNetwork: String = jobSubmission.vpcNetwork
  private val vpcSubnetwork: String = jobSubmission.vpcSubnetwork
  private lazy val gcpBootDiskSizeMb = (jobSubmission.gcpBatchParameters.runtimeAttributes.bootDiskSize * 1000).toLong

  //def toAccelerator(gpuResource: GpuResource): Accelerator.Builder = Accelerator.newBuilder.setCount(gpuResource.gpuCount.value.toLong).setType(gpuResource.gpuType.toString)
  //def toAccelerator(gpuResource: GpuResource): Accelerator = new Accelerator().setCount(gpuResource.gpuCount.value.toLong).setType(gpuResource.gpuType.toString)

  val accelerators = jobSubmission.gcpBatchParameters.runtimeAttributes
    .gpuResource.map(toAccelerator).toList.asJava

  val gpuType = jobSubmission.gcpBatchParameters.runtimeAttributes
    .gpuResource.map{ gpuType => gpuType.gpuType}

  val gpuCount = jobSubmission.gcpBatchParameters.runtimeAttributes
    .gpuResource.map{ gpuCount => gpuCount.gpuCount.toString}

  println(f"gputype ${gpuType}")
  println(f"gpuCount ${gpuCount}")


  //val gpuConfig = Accelerator.newBuilder.setType(accelerators.get(0).toString).setCount(accelerators.get(1).toString.toLong)

  // set user agent
  private val user_agent_header = "user-agent"
  private val customUserAgentValue = "cromwell"
  private lazy val headerProvider: HeaderProvider = FixedHeaderProvider
    .create(ImmutableMap
      .of(user_agent_header, customUserAgentValue))

  private lazy val batchSettings = BatchServiceSettings.newBuilder.setHeaderProvider(headerProvider).build

  lazy val batchServiceClient = BatchServiceClient.create(batchSettings)

  lazy val parent = (String
    .format("projects/%s/locations/%s", jobSubmission.gcpBatchParameters
      .projectId, jobSubmission.gcpBatchParameters
      .region))

  //convert to millicores for Batch
  private val cpu = jobSubmission.gcpBatchParameters.runtimeAttributes.cpu
  val cpuCores = toCpuCores(cpu.toString.toLong)

  private val cpuPlatform =  jobSubmission.gcpBatchParameters.runtimeAttributes.cpuPlatform.getOrElse("")
  println(cpuPlatform)
  //private val gpuModel =  jobSubmission.gcpBatchParameters.runtimeAttributes.gpuResource.getOrElse("")
  //println(gpuModel)

  //private val memory = jobSubmission.gcpBatchParameters.runtimeAttributes.memory
  //private val memoryConvert = memory.toString.toLong
  //println(memoryConvert)

  val memTemp: Long = 400

  //private val bootDiskSize = runtimeAttributes.bootDiskSize
 // private val noAddress = runtimeAttributes.noAddress
  private val zones = "zones/" + jobSubmission.gcpBatchParameters.runtimeAttributes.zones.mkString(",")
  println(zones)

  // parse preemption value and set value for Spot. Spot is replacement for preemptible
  val spotModel = toProvisioningModel(jobSubmission.gcpBatchParameters.runtimeAttributes.preemptible)


  private def createRunnable(dockerImage: String, entryPoint: String): Runnable = {
    val runnable = Runnable.newBuilder.setContainer((Container.newBuilder.setImageUri(dockerImage).setEntrypoint(entryPoint).addCommands("-c").addCommands(gcpBatchCommand).build)).build
    runnable
  }

  private def createComputeResource(cpu: Long, memory: Long, bootDiskSizeMb: Long) = {
    ComputeResource
      .newBuilder
      .setCpuMilli(cpu)
      .setMemoryMib(memory)
      .setBootDiskMib(bootDiskSizeMb)
      .build
  }

  private def createInstancePolicy(spotModel: ProvisioningModel) = {
    val instancePolicy = InstancePolicy
      .newBuilder
      .setMachineType(machineType)
      .setProvisioningModel(spotModel)
      //.addAccelerators(accelerators)
      //.addAcceleratorsBuilder(accelerators)
      //.setAccelerators(accelerators)
      //.setMinCpuPlatform(cpuPlatform)
      .build
    instancePolicy
  }



  private def createNetworkInterface(noAddress: Boolean) = {
    NetworkInterface
      .newBuilder
      .setNoExternalIpAddress(noAddress)
      .setNetwork(vpcNetwork)
      .setSubnetwork(vpcSubnetwork)
      .build
  }
    //.setNetwork("projects/batch-testing-350715/global/networks/default")
    //.setSubnetwork("regions/us-central1/subnetworks/default")



  private def createNetworkPolicy(networkInterface: NetworkInterface): NetworkPolicy = {
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

  private def createAllocationPolicy(locationPolicy: LocationPolicy,  instancePolicy: InstancePolicy, networkPolicy: NetworkPolicy) = {
    AllocationPolicy
      .newBuilder
      .setLocation(locationPolicy)
      .setNetwork(networkPolicy)
      .addInstances(InstancePolicyOrTemplate
        .newBuilder
        .setPolicy(instancePolicy)
        .build)
      .build
  }

  def submitJob(): Unit = {

    try {
      val runnable = createRunnable(dockerImage = jobSubmission.gcpBatchParameters.runtimeAttributes.dockerImage, entryPoint = entryPoint)

      val networkInterface = createNetworkInterface(false)
      val networkPolicy = createNetworkPolicy(networkInterface)
      val computeResource = createComputeResource(cpuCores, memTemp, gcpBootDiskSizeMb)
      val taskSpec = createTaskSpec(runnable, computeResource, retryCount, durationInSeconds)
      val taskGroup: TaskGroup = createTaskGroup(taskCount, taskSpec)
      val instancePolicy = createInstancePolicy(spotModel)
      val locationPolicy = LocationPolicy.newBuilder.addAllowedLocations(zones).build
      //val gpuConfig = Accelerator.newBuilder.setType("nvidia-tesla-t4").setCount(1)
      val allocationPolicy = createAllocationPolicy(locationPolicy, instancePolicy, networkPolicy)
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