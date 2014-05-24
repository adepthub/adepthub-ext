adept.sbt.AdeptPlugin.adeptSettings

resolvers += "Opencast project" at "http://repository.opencastproject.org/nexus/content/repositories/terracotta/"


resolvers += "Jgit Repository" at "https://repo.eclipse.org/content/groups/releases/"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += Resolver.url("Typesafe Repository (non maven compat)",  url("http://repo.typesafe.com/typesafe/releases"))(Resolver.ivyStylePatterns)

