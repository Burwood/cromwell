package cromwell.backend.google.pipelines.batch.util

import cromwell.backend.google.pipelines.batch.models.{GcpBatchRuntimeAttributes, N1CustomMachineType, N2CustomMachineType, N2DCustomMachineType}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import org.slf4j.Logger
import wdl4s.parser.MemoryUnit
import wom.format.MemorySize

object GcpBatchMachineConstraints {
  def machineType(memory: MemorySize,
                  cpu: Int Refined Positive,
                  cpuPlatformOption: Option[String],
                  googleLegacyMachineSelection: Boolean,
                  jobLogger: Logger,
                 ): String = {
    if (googleLegacyMachineSelection) {
      s"predefined-$cpu-${memory.to(MemoryUnit.MB).amount.intValue()}"
    } else {
      // If someone requests Intel Cascade Lake as their CPU platform then switch the machine type to n2.
      // Similarly, CPU platform of AMD Rome corresponds to the machine type n2d.  
      val customMachineType =
        cpuPlatformOption match {
          case Some(GcpBatchRuntimeAttributes.CpuPlatformIntelCascadeLakeValue) => N2CustomMachineType
          case Some(GcpBatchRuntimeAttributes.CpuPlatformAMDRomeValue)          => N2DCustomMachineType
          case _ => N1CustomMachineType
        }
      customMachineType.machineType(memory, cpu, jobLogger)
    }
  }
}
