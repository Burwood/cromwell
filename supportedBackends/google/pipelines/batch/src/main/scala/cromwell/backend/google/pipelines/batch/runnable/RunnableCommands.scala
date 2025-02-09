package cromwell.backend.google.pipelines.batch.runnable

import akka.http.scaladsl.model.ContentType
import common.util.StringUtil._
import cromwell.backend.google.pipelines.batch.models.GcpBatchConfigurationAttributes.GcsTransferConfiguration
import cromwell.core.path.Path
import cromwell.filesystems.gcs.GcsPath
import cromwell.filesystems.gcs.RequesterPaysErrors._
import mouse.all._
import org.apache.commons.codec.binary.Base64
import org.apache.commons.text.StringEscapeUtils

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration._

object RunnableCommands {

  import RunnableUtils._

  implicit val waitBetweenRetries: FiniteDuration = 5.seconds

  implicit class EnhancedCromwellPath(val path: Path) extends AnyVal {
    def projectId: String = path match {
      case gcs: GcsPath => gcs.projectId
      case _ => ""
    }
  }

  implicit class ShellPath(val path: Path) extends AnyVal {
    // The command String runs in Bourne shell so shell metacharacters in filenames must be escaped
    def escape: String = StringEscapeUtils.escapeXSI(path.pathAsString)
  }

  private def makeContentTypeFlag(contentType: Option[ContentType]) = contentType.map(ct => s"""-h "Content-Type: $ct"""").getOrElse("")

  def makeContainerDirectory(containerPath: Path) = s"mkdir -p ${containerPath.escape}"

  def delocalizeDirectory(containerPath: Path, cloudPath: Path, contentType: Option[ContentType])
                         (implicit gcsTransferConfiguration: GcsTransferConfiguration): String = {
    retry {
      recoverRequesterPaysError(cloudPath) { flag =>
        s"rm -f $$HOME/.config/gcloud/gce && " +
          s"gsutil $flag ${contentType |> makeContentTypeFlag} -m rsync -r ${containerPath.escape} ${cloudPath.escape}"
      }
    }
  }

  /**
    * As per https://cloud.google.com/storage/docs/gsutil/addlhelp/HowSubdirectoriesWork, rule #2
    * If one attempts a
    * gsutil cp /local/file.txt gs://bucket/subdir/file.txt
    * AND
    * there exists a folder gs://bucket/subdir/file.txt_thisCouldBeAnything
    * then gs://bucket/subdir/file.txt will be treated as a directory, and /local/file.txt will be copied under gs://bucket/subdir/file.txt/file.txt
    * and not gs://bucket/subdir/file.txt.
    *
    * By instead using the parent directory (and ensuring it ends with a slash), gsutil will treat that as a directory and put the file under it.
    * So the final gsutil command will look something like gsutil cp /local/file.txt gs://bucket/subdir/
    */
  def delocalizeFile(containerPath: Path, cloudPath: Path, contentType: Option[ContentType])
                    (implicit gcsTransferConfiguration: GcsTransferConfiguration): String = {
    retry {
      recoverRequesterPaysError(cloudPath) { flag =>
        s"rm -f $$HOME/.config/gcloud/gce && " +
          s"gsutil $flag ${contentType |> makeContentTypeFlag} cp ${containerPath.escape} ${cloudPath.parent.escape.ensureSlashed}"
      }
    }
  }

  /**
    * delocalizeFile necessarily copies the file to the same name. Use this if you want to to specify a name different from the original
    * Make sure that there's no object named "yourfinalname_something" (see above) in the same cloud directory.
    */
  def delocalizeFileTo(containerPath: Path, cloudPath: Path, contentType: Option[ContentType])
                      (implicit gcsTransferConfiguration: GcsTransferConfiguration): String = {
    retry {
      recoverRequesterPaysError(cloudPath) { flag =>
        s"rm -f $$HOME/.config/gcloud/gce && " +
          s"gsutil $flag ${contentType |> makeContentTypeFlag} cp ${containerPath.escape} ${cloudPath.escape}"
      }
    }
  }

  def ifExist(containerPath: Path)(f: => String) = s"if [ -e ${containerPath.escape} ]; then $f; fi"

  def every(duration: FiniteDuration)(f: => String): String =
    s"""while true; do
       |  (
       |    $f
       |  ) > /dev/null 2>&1
       |  sleep ${duration.toSeconds}
       |done""".stripMargin

  def retry(f: => String)(implicit gcsTransferConfiguration: GcsTransferConfiguration, wait: FiniteDuration): String = {
    s"""for i in $$(seq ${gcsTransferConfiguration.transferAttempts}); do
       |  (
       |    $f
       |  )
       |  RC=$$?
       |  if [ "$$RC" = "0" ]; then
       |    break
       |  fi
       |  if [ $$i -lt ${gcsTransferConfiguration.transferAttempts} ]; then
       |    ${s"""Waiting ${wait.toSeconds} seconds and retrying""" |> timestampedMessage}
       |    sleep ${wait.toSeconds}
       |  fi
       |done
       |exit "$$RC"""".stripMargin
  }

  def delocalizeFileOrDirectory(containerPath: Path, cloudPath: Path, contentType: Option[ContentType])
                               (implicit gcsTransferConfiguration: GcsTransferConfiguration): String = {
    s"""if [ -d ${containerPath.escape} ]; then
       |  ${delocalizeDirectory(containerPath, cloudPath, contentType)}
       |else
       |  ${delocalizeFile(containerPath, cloudPath, contentType)}
       |fi""".stripMargin
  }

  def localizeDirectory(cloudPath: Path, containerPath: Path)
                       (implicit gcsTransferConfiguration: GcsTransferConfiguration): String = {
    retry {
      recoverRequesterPaysError(cloudPath) { flag =>
        s"${containerPath |> makeContainerDirectory} && " +
          s"rm -f $$HOME/.config/gcloud/gce && gsutil $flag -m rsync -r ${cloudPath.escape} ${containerPath.escape}"
      }
    }
  }

  def localizeFile(cloudPath: Path, containerPath: Path)
                  (implicit gcsTransferConfiguration: GcsTransferConfiguration): String = {
    retry {
      recoverRequesterPaysError(cloudPath) { flag =>
        s"rm -f $$HOME/.config/gcloud/gce && gsutil $flag cp ${cloudPath.escape} ${containerPath.escape}"
      }
    }
  }

  def recoverRequesterPaysError(path: Path)(f: String => String): String = {
    val commandWithoutProject = f("")
    val commandWithProject = f(s"-u ${path.projectId}")

    s"""$commandWithoutProject > gsutil_output.txt 2>&1
       |# Record the exit code of the gsutil command without project flag
       |RC_GSUTIL=$$?
       |if [ "$$RC_GSUTIL" != "0" ]; then
       |  ${s"$commandWithoutProject failed" |> timestampedMessage}
       |  # Print the reason of the failure
       |  cat gsutil_output.txt
       |
       |  # Check if it matches the BucketIsRequesterPaysErrorMessage
       |  if grep -q "$BucketIsRequesterPaysErrorMessage" gsutil_output.txt; then
       |    ${"Retrying with user project" |> timestampedMessage}
       |    $commandWithProject
       |  else
       |    exit "$$RC_GSUTIL"
       |  fi
       |else
       |  exit 0
       |fi""".stripMargin
  }

  def checkIfStderrContainsRetryKeys(retryLookupKeys: List[String]): String = {
    val lookupKeysAsString = retryLookupKeys.mkString("|")
    s"grep -E -q '$lookupKeysAsString' /cromwell_root/stderr ; echo $$? > /cromwell_root/memory_retry_rc"
  }

  def multiLineCommandTransformer(shell: String)(commandString: String): String = {
    val randomUuid = UUID.randomUUID().toString
    val withBashShebang = s"#!/bin/bash\n\n$commandString"
    val base64EncodedScript = Base64.encodeBase64String(withBashShebang.getBytes(StandardCharsets.UTF_8))
    val scriptPath = s"/tmp/$randomUuid.sh"

    s"""python3 -c 'import base64; print(base64.b64decode("$base64EncodedScript").decode("utf-8"));' """ +
      s"""> $scriptPath && """ +
      s"chmod u+x $scriptPath && " +
      s"$shell $scriptPath"
  }

  def multiLineCommand(commandString: String): String = multiLineCommandTransformer("sh")(commandString)

  def multiLineBinBashCommand(commandString: String): String = multiLineCommandTransformer("/bin/bash")(commandString)

}
