package adept

import adept.resolution.models._
import adept.repository.models._
import adepthub.models._
import adept.repository._
import adept.repository.metadata._
import scala.Option.option2Iterable
import java.io.File
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import org.apache.http.client.methods.HttpPost
import play.api.libs.json.Json
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder

class AdeptHubRecoverableException(msg: String) extends Exception

private[adept] object Search {

  def mergeSearchResults(imports: Set[ImportSearchResult], offline: Set[GitSearchResult], online: Set[GitSearchResult], alwaysIncludeImports: Boolean): Set[SearchResult] = {
    val offlineRepoCommit = offline.map { result =>
      result.repository -> result.commit
    }

    val gitResults = offline ++ online.filter { result =>
      //remove all online results which are available offline (prefer Offline > Online):
      !offlineRepoCommit(result.repository -> result.commit)
    }
    val gitVariants = gitResults.map { result =>
      result.variant
    }

    gitResults ++ imports.filter { result =>
      //remove imported variants that can be found in Git (prefer Git > Imports):
      alwaysIncludeImports || !gitVariants(result.variant)
    }
  }

  
  def onlineSearch(url: String)(term: String, constraints: Set[Constraint], executionContext: ExecutionContext): Future[Set[GitSearchResult]] = {
    Future {
      ///TODO: future me, I present my sincere excuses for this code: http client sucks! Rewrite this!
      val postRequest = new HttpPost(url + "/api/search")
      postRequest.addHeader("Content-Type", "application/json")
      val jsonRequest = Json.prettyPrint(Json.toJson(SearchRequest(term, constraints)))
      val entity = new StringEntity(jsonRequest)
      postRequest.setEntity(entity)
      val httpClientBuilder = HttpClientBuilder.create()
      val httpClient = httpClientBuilder.build()
      try {
        val response = httpClient.execute(postRequest)
        try {
          val status = response.getStatusLine()
          val responseString = io.Source.fromInputStream(response.getEntity().getContent()).getLines.mkString("\n")

          if (status.getStatusCode() == 200) {
            val jsonString = responseString
            Json.fromJson[Set[GitSearchResult]](Json.parse(jsonString)).asEither match {
              case Right(results) =>
                results.map(_.copy(isLocal = false))
              case Left(error) =>
                throw new Exception("Could not parse AdeptHub response as search results. Got:\n" + responseString)
            }
          } else {
            throw new AdeptHubRecoverableException("AdeptHub returned with: " + status + ":\n" + responseString)
          }
        } finally {
          response.close()
        }
      } finally {
        httpClient.close()
      }
    }(executionContext)
  }

  //TODO: remove duplicate code in Adept.localSearch
  def searchImportRepository(adeptHub: AdeptHub)(term: String, name: RepositoryName, constraints: Set[Constraint] = Set.empty): Set[ImportSearchResult] = {
    val repository = new Repository(adeptHub.importsDir, name)
    if (repository.exists) {
      VariantMetadata.listIds(repository).flatMap { id =>
        if (adeptHub.matches(term, id)) {
          val variants = RankingMetadata.listRankIds(id, repository).flatMap { rankId =>
            val ranking = RankingMetadata.read(id, rankId, repository)
              .getOrElse(throw new Exception("Could not read rank id: " + (id, rankId, repository.dir.getAbsolutePath)))
            ranking.variants.map { hash =>
              VariantMetadata.read(id, hash, repository, checkHash = true).map(_.toVariant(id))
                .getOrElse(throw new Exception("Could not read variant: " + (rankId, id, hash, repository.dir.getAbsolutePath)))
            }.filter { variant =>
              constraints.isEmpty ||
                AttributeConstraintFilter.matches(variant.attributes.toSet, constraints)
            }.map(_ -> rankId)
          }

          variants.map {
            case (variant, rankId) =>
              ImportSearchResult(variant, rankId, repository.name)
          }
        } else {
          Set.empty[ImportSearchResult]
        }
      }
    } else {
      Set.empty[ImportSearchResult]
    }
  }

  def searchImports(adeptHub: AdeptHub)(term: String, constraints: Set[Constraint] = Set.empty): Set[ImportSearchResult] = {
    Repository.listRepositories(adeptHub.importsDir).flatMap { name =>
      searchImportRepository(adeptHub)(term, name, constraints)
    }
  }
}