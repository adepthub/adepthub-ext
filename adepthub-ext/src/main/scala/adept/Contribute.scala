package adept

import java.io.{File, FileInputStream, FileOutputStream}
import java.util.zip.{ZipEntry, ZipOutputStream}

import adept.lockfile.{Lockfile, LockfileContext}
import adept.logging.Logging
import adept.repository.GitRepository
import adept.services.JsonService
import adepthub.models.ContributionResult
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.entity.mime.{HttpMultipartMode, MultipartEntityBuilder}
import org.apache.http.impl.client.HttpClientBuilder
import org.eclipse.jgit.lib.ProgressMonitor

object Contribute extends Logging {

  def walkTree(file: File): Iterable[File] = {
    val children = new Iterable[File] {
      def iterator = if (file.isDirectory) file.listFiles.iterator else Iterator.empty
    }
    Seq(file) ++: children.flatMap(walkTree)
  }
  def getZipEntry(file: File, baseDir: File) = {
    new ZipEntry(file.getAbsolutePath.replace(baseDir.getAbsolutePath, ""))
  }
  def compress(importsDir: File): File = {
    val zipFile = File.createTempFile("adept-", "-import.zip")
    logger.debug(s"Compressing $importsDir to: ${zipFile.getAbsolutePath}")
    var totalBytesRead = 0
    val output = new FileOutputStream(zipFile)
    val zipOutput = new ZipOutputStream(output)
    try {
      val BufferSize = 4096 //random number
      walkTree(importsDir).filter(_.isFile).foreach { file =>
        val zipEntry = getZipEntry(file, importsDir)
        val is = new FileInputStream(file)
        try {
          val bytes = new Array[Byte](BufferSize)
          var bytesRead = is.read(bytes)
          zipOutput.putNextEntry(zipEntry)
          while (bytesRead != -1) {
            totalBytesRead += bytesRead
            zipOutput.write(bytes, 0, bytesRead)
            bytesRead = is.read(bytes)
          }
        } finally {
          is.close()
        }
      }

      if (totalBytesRead <= 0) {
        throw new Exception(s"There were no files to compress in imports directory ${importsDir}")
      }

      zipFile
    } finally {
      zipOutput.finish()
      output.close()
      zipOutput.close()
    }
  }

  def updateWithContributions(lockfile: Lockfile, contributions: Set[ContributionResult]) = {
    import scala.collection.JavaConverters._
    val contributionByRepos = contributions.groupBy(_.repository)
    val newContext = lockfile.getContext.asScala.map { value =>
      contributionByRepos.get(adept.repository.models.RepositoryName(value.repository.value))
      match {
        case Some(matchingContribs) =>
          if (matchingContribs.size != 1) {
            throw new Exception(
              "Got more than one contribution per repository. This is unexpected so we fail. Contributions:\n" +
                contributions.mkString("\n"))
          }
          val matchingContrib = matchingContribs.head
          new LockfileContext(value.info, value.id, value.repository,
            matchingContrib.locations.map {
            new adept.lockfile.RepositoryLocation(_) }.toSet.asJava, new adept.lockfile.Commit(
            matchingContrib.commit.value), value.hash)
        case None =>
          value
      }
    }
    new Lockfile(lockfile.getRequirements, newContext.asJava, lockfile.getArtifacts)
  }

  def sendFile(url: String, baseDir: File, passphrase: Option[String], progress:
  ProgressMonitor)(archive: File) = {
    ///TODO: future me, I present my sincere excuses for this code: http client sucks!
    val requestBuilder = RequestBuilder.post()
    requestBuilder.setUri(url + "/api/ivy/import")
    val multipartBuilder = MultipartEntityBuilder.create()
    multipartBuilder.setMode(HttpMultipartMode.STRICT)
    multipartBuilder.addBinaryBody("contribution-zipped-file", archive)
    val entity = multipartBuilder.build()
    requestBuilder.setEntity(entity)
    val httpClientBuilder = HttpClientBuilder.create()
    val httpClient = httpClientBuilder.build()
    try {
      logger.info("Uploading contribution to AdeptHub - this might take a while...")
      val response = httpClient.execute(requestBuilder.build())
      try {
        val status = response.getStatusLine
        if (status.getStatusCode == 200) {
          val (results, _) = JsonService.parseJsonSet(response.getEntity.getContent,
            ContributionResult.fromJson)
          results.foreach { result =>
            val repository = new GitRepository(baseDir, result.repository)
            if (!repository.exists) {
              if (result.locations.size > 1) logger.warn("Ignoring locations: " +
                result.locations.tail)
              val uri = result.locations.head
              repository.clone(uri, passphrase, progress)
            } else if (repository.exists) {
              result.locations.foreach { location =>
                repository.addRemoteUri(GitRepository.DefaultRemote, location)
              }
              repository.pull(GitRepository.DefaultRemote, GitRepository.DefaultBranchName,
                passphrase)
            } else {
              logger.warn("Ignoring " + result)
            }
          }
          results
        } else {
          val json = io.Source.fromInputStream(response.getEntity.getContent).getLines()
            .mkString("\n")
          throw new Exception("AdeptHub returned with: " + status + ":\n" + json)
        }
      } finally {
        response.close()
      }
    } finally {
      httpClient.close()
    }
  }

  def contribute(url: String, baseDir: File, passphrase: Option[String], progress: ProgressMonitor,
                 importsDir: File): Set[ContributionResult] = {
    sendFile(url, baseDir, passphrase, progress)(compress(importsDir)).toSet
  }
}
