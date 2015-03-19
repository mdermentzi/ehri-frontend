package controllers.portal

import auth.AccountManager
import backend.Backend
import com.google.inject.{Inject, Singleton}
import controllers.generic.Search
import controllers.portal.base.{Generic, PortalController}
import models.Link
import utils.search._

/**
 * @author Mike Bryant (http://github.com/mikesname)
 */
@Singleton
case class Links @Inject()(implicit globalConfig: global.GlobalConfig, searchEngine: SearchEngine, searchResolver: SearchItemResolver, backend: Backend,
                                  accounts: AccountManager, pageRelocator: utils.MovedPageLookup)
  extends PortalController
  with Generic[Link]
  with Search
  with FacetConfig {

  private val portalGroupRoutes = controllers.portal.routes.Links

  def browse(id: String) = GetItemAction(id).apply { implicit request =>
    Ok(views.html.link.show(request.item))
  }
}
