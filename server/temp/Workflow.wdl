workflow myWorkflow {
    call myTask
}

task myTask {
    command {
        echo "hello world"
    }

    runtime {
      docker: "debian:latest"
      bootDiskSizeGb: 50
      zones: "us-south1-a"
    }

}