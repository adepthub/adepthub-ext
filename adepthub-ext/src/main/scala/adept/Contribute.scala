package adept

import java.io.File
import java.util.zip.ZipEntry
import java.io.FileOutputStream
import adept.logging.Logging
import java.util.zip.ZipOutputStream
import java.io.FileInputStream
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.entity.mime.HttpMultipartMode
import org.apache.http.impl.client.HttpClientBuilder
import adepthub.models.ContributionResult
import adept.repository.GitRepository
import org.eclipse.jgit.lib.ProgressMonitor
import adept.lockfile.{Commit => LockfileCommit}
import adept.lockfile.{RepositoryLocation => LockfileRepositoryLocation}
import adept.lockfile.LockfileContext
import adept.lockfile.Lockfile
import adept.services.JsonService

object Contribute extends Logging {

  def walkTree(file: File): Iterable[File] = {
    val children = new Iterable[File] {
      def iterator = if (file.isDirectory) file.listFiles.iterator else Iterator.empty
    }
    Seq(file) ++: children.flatMap(walkTree(_))
  }
  def getZipEntry(file: File, baseDir: File) = {
    new ZipEntry(file.getAbsolutePath().replace(baseDir.getAbsolutePath(), ""))
  }
  def compress(importsDir: File) = {
    val zipFile = File.createTempFile("adept-", "-import.zip")
    logger.debug("Compressing to: " + zipFile.getAbsolutePath)
    val output = new FileOutputStream(zipFile)
    val zipOutput = new ZipOutputStream(output)
    val BufferSize = 4096 //random number
    var bytes = new Array[Byte](BufferSize)
    try {
      walkTree(importsDir).filter(_.isFile).foreach { file =>
        val zipEntry = getZipEntry(file, importsDir)
        val is = new FileInputStream(file)
        try {
          var bytesRead = is.read(bytes)
          zipOutput.putNextEntry(zipEntry)
          while (bytesRead != -1) {
            zipOutput.write(bytes, 0, bytesRead)
            bytesRead = is.read(bytes)
          }
        } finally {
          is.close()
        }
      }
      zipFile
    } finally {
      zipOutput.finish()
      output.close()
      zipOutput.close()
    }
  }
  
  
  def updateWithContributions(lockfile: Lockfile, contributions: Set[ContributionResult]) = {
    import collection.JavaConverters._
    val contributionByRepos = contributions.groupBy(_.repository)
    val newContext = lockfile.getContext().asScala.map { value =>
      contributionByRepos.get(adept.repository.models.RepositoryName(value.repository.value)) match {
        case Some(matchingContribs) =>
          if (matchingContribs.size != 1) throw new Exception("Got more than one contribution per repository. This is unexpected so we fail. Contributions:\n" + contributions.mkString("\n"))
          val matchingContrib = matchingContribs.head
          new LockfileContext(value.info, value.id, value.repository, matchingContrib.locations.map { new adept.lockfile.RepositoryLocation(_) }.toSet.asJava, new adept.lockfile.Commit(matchingContrib.commit.value), value.hash)
        case None =>
          value
      }
    }
    new Lockfile(lockfile.getRequirements(), newContext.asJava, lockfile.getArtifacts())
  }

  def sendFile(url: String, baseDir: File, passphrase: Option[String], progress: ProgressMonitor)( file: File) = {
    ///TODO: future me, I present my sincere excuses for this code: http client sucks!
    val requestBuilder = RequestBuilder.post()
    requestBuilder.setUri(url + "/api/ivy/import")
    val multipartBuilder = MultipartEntityBuilder.create()
    multipartBuilder.setMode(HttpMultipartMode.STRICT)
    multipartBuilder.addBinaryBody("contribution-zipped-file", file)
    val entity = multipartBuilder.build()
    requestBuilder.setEntity(entity)
    val httpClientBuilder = HttpClientBuilder.create()
    val httpClient = httpClientBuilder.build()
    try {
      logger.info("Uploading contribution to AdeptHub - this might take a while...")
      val response = httpClient.execute(requestBuilder.build())
      try {
        val status = response.getStatusLine()
        var results = Seq[ContributionResult]()
        val json = JsonService.parseJson(response.getEntity().getContent, (parser, fieldName) => {
          results = JsonService.parseSeq(parser, () => ContributionResult.fromJson(parser))
        })

        if (status.getStatusCode() == 200) {
          results.foreach { result =>
            val repository = new GitRepository(baseDir, result.repository)
            if (!repository.exists) {
              if (result.locations.size > 1) logger.warn("Ignoring locations: " + result.locations.tail)
              val uri = result.locations.head
              repository.clone(uri, passphrase, progress)
            } else if (repository.exists) {
              result.locations.foreach { location =>
                repository.addRemoteUri(GitRepository.DefaultRemote, location)
              }
              repository.pull(GitRepository.DefaultRemote, GitRepository.DefaultBranchName, passphrase)
            } else {
              logger.warn("Ignoring " + result)
            }
          }
          results

//          Json.fromJson[Seq[ContributionResult]](Json.parse(jsonString)).asEither match {
//            case Left(errors) =>
//              logger.debug(errors.mkString(","))
//              throw new Exception("Could not parse contribution from AdeptHub! Aborting...")
//            case Right(results) =>
//              results.foreach { result =>
//                val repository = new GitRepository(baseDir, result.repository)
//                if (!repository.exists) {
//                  if (result.locations.size > 1) logger.warn("Ignoring locations: " + result.locations.tail)
//                  val uri = result.locations.head
//                  repository.clone(uri, passphrase, progress)
//                } else if (repository.exists) {
//                  result.locations.foreach { location =>
//                    repository.addRemoteUri(GitRepository.DefaultRemote, location)
//                  }
//                  repository.pull(GitRepository.DefaultRemote, GitRepository.DefaultBranchName, passphrase)
//                } else {
//                  logger.warn("Ignoring " + result)
//                }
//              }
//              results
//          }
        } else {
          throw new Exception("AdeptHub returned with: " + status + ":\n" + json)
        }
      } finally {
        response.close()
      }
    } finally {
      httpClient.close()
    }
  }
  

  def contribute(url: String, baseDir: File, passphrase: Option[String], progress: ProgressMonitor, importsDir:
  File): Set[ContributionResult] = {
    sendFile(url, baseDir, passphrase, progress)(compress(importsDir)).toSet
  }
}
