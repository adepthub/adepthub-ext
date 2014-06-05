package adept.ext.models

import adept.resolution.resolver.models.ResolveResult

case class ResolveErrorReport(msg: String, result: ResolveResult)