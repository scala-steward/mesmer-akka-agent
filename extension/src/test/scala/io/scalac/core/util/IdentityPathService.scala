package io.scalac.core.util

import io.scalac.core.model.Path
import io.scalac.extension.service.PathService

object IdentityPathService extends PathService {
  override def template(path: Path): Path = path
}