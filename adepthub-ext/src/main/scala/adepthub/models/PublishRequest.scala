package adepthub.models

import adept.repository.models.RepositoryName
import adept.repository.models.Commit

case class PublishRequest(userToken: String, repository: RepositoryName, commit: Commit)