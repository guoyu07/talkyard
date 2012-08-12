/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */


import com.debiki.v0._
import com.debiki.v0.Prelude._
import com.twitter.ostrich.stats.Stats
import com.twitter.ostrich.{admin => toa}
import debiki._
import play.api._
import play.api.mvc._
import DebikiHttp._


object Global extends GlobalSettings {


  /**
   * Query string based routing and tenant ID lookup.
   */
  override def onRouteRequest(request: RequestHeader): Option[Handler] = {
    try {
      _routeRequestOrThrow(request)
    } catch {
      case DebikiHttp.ResultException(result) =>
        Some(Action(BodyParsers.parse.empty){ _ => result })
    }
  }


  private def _routeRequestOrThrow(request: RequestHeader): Option[Handler] = {

    // Ignore the internal API and Javascript and CSS etcetera, in /-/.
    // Right now, when porting from Lift-Web, /classpath/ is also magic.
    if (request.path.startsWith("/-/") ||
        request.path.startsWith("/classpath/"))
      return super.onRouteRequest(request)

    val tenantId = DebikiHttp.lookupTenantIdOrThrow(request, Debiki.SystemDao)

    // Parse URL path: find folder, page id and slug.
    val pagePath = PagePath.fromUrlPath(tenantId, request.path) match {
      case PagePath.Parsed.Good(pagePath) => pagePath
      case PagePath.Parsed.Bad(error) => throwBadReq("DwE0kI3E4", error)
      case PagePath.Parsed.Corrected(newPath) => throwRedirect(newPath)
    }

    // BUG: debiki-core html.scala places view=<root> just after '?',
    // so `view' will always be the main funcion.
    // Possible solution: Require that the main function start with
    // version number? v0-reply-to=... but don't require it to be the
    // first one. Also rename `view' to `root' when it identifies the
    // root post -- no, don't rename it, root=title doesn't look nice,
    // but view=title looks nice.

    // Find API version and main function in query string.
    // Example: page?v0-reply-to=123 means version 0 and function `reply-to'.
    val versionAndMainFun =
      request.rawQueryString.takeWhile(x => x != '=' && x != '&')
    // Later:
    //val versionSeparator = versionAndMainFun.indexOf('-')
    //val (versionPrefix, mainFun) =
    // versionAndMainFun.splitAt(versionSeparator)
    //val version = versionPrefix.drop(1).dropRight(1).toInt
    // For now: (until I've updated all HTTP calls to include 'v0-')
    val version = 0
    val versionPrefix = ""
    val mainFun = versionAndMainFun

    // Find parameters common to almost all requests.
    // COULD move pageRoot to Actions.PageRequest member, better.
    val pageRoot: PageRoot =
      request.queryString.get("view").map(rootPosts => rootPosts.size match {
        case 1 => PageRoot(rootPosts.head)
        // It seems this cannot hapen with Play Framework:
        case 0 => assErr("DwE03kI8", "Query string param with no value")
        case _ => throwBadReq("DwE0k35", "Too many `view' values")
      }) getOrElse PageRoot.TheBody

    // Query string param value lookup.
    def firstValueOf(param: String): Option[String] =
      request.queryString.get(param).map(_.headOption).getOrElse(None)
    def mainFunVal: String =  // COULD be Option instead, change "" to None
      firstValueOf(versionAndMainFun) getOrElse ""
    lazy val mainFunVal_! : String = firstValueOf(versionAndMainFun).getOrElse(
      throwBadReq("DwE0k32", "No post specified"))

    // Route based on the query string.
    import controllers._
    val App = Application
    val GET = "GET"
    val POST = "POST"
    val action = (mainFun, request.method) match {
      case ("edit", GET) =>
        AppEdit.showEditForm(pagePath, pageRoot, postId = mainFunVal_!)
      case ("edit", POST) =>
        AppEdit.handleEditForm(pagePath, pageRoot, postId = mainFunVal_!)
      case ("view", GET) =>
        App.viewPost(pagePath, pageRoot)
      case ("reply", GET) =>
        AppReply.showForm(pagePath, pageRoot, postId = mainFunVal_!)
      case ("reply", POST) =>
        AppReply.handleForm(pagePath, pageRoot, postId = mainFunVal_!)
      case ("rate", POST) =>
        App.handleRateForm(pagePath, pageRoot, postId = mainFunVal_!)
      case ("flag", POST) =>
        App.handleFlagForm(pagePath, pageRoot, postId = mainFunVal_!)
      case ("delete", POST) =>
        App.handleDeleteForm(pagePath, pageRoot, postId = mainFunVal_!)
      case ("viewedits", GET) =>
        AppEditHistory.showForm(pagePath, pageRoot, postId = mainFunVal_!)
      case ("applyedits", POST) =>
        AppEditHistory.handleForm(pagePath, pageRoot, postId = mainFunVal_!)
      case ("create-page", GET) =>
        AppCreatePage.showForm(pagePath)
      case ("create-page", POST) =>
        AppCreatePage.handleForm(pagePath)
      case ("move-page", GET) =>
        AppMoveRenamePage.showMovePageForm(pagePath)
      case ("move-page", POST) =>
        AppMoveRenamePage.handleMovePageForm(pagePath)
      case ("rename-page", GET) =>
        AppMoveRenamePage.showRenamePageForm(pagePath)
      case ("rename-page", POST) =>
        AppMoveRenamePage.handleRenamePageForm(pagePath)
      case ("list-pages", GET) =>
        App.listPages(pagePath, DebikiHttp.ContentType.Html)
      case ("list-pages.json", GET) =>
        App.listPages(pagePath, DebikiHttp.ContentType.Json)
      case ("list-actions", GET) =>
        App.listActions(pagePath, DebikiHttp.ContentType.Html)
      case ("list-actions.json", GET) =>
        App.listActions(pagePath, DebikiHttp.ContentType.Json)
      case ("feed", GET) =>
        App.feed(pagePath)
      case ("act", GET) =>
        Application.showActionLinks(pagePath, pageRoot, postId = mainFunVal_!)
      case ("page-info", GET) =>
        Application.showPageInfo(pagePath)
      case ("config-user", GET) =>
        AppConfigUser.showForm(pagePath, pageRoot, userId = mainFunVal_!)
      case ("config-user", POST) =>
        AppConfigUser.handleForm(pagePath, pageRoot, userId = mainFunVal_!)
      case ("unsubscribe", GET) =>
        AppUnsubscribe.showForm(tenantId)
      case ("unsubscribe", POST) =>
        AppUnsubscribe.handleForm(tenantId)
      // If no main function specified:
      case ("", GET) =>
        pagePath.suffix match {
          case "css" => App.rawBody(pagePath)
          case "js" => App.rawBody(pagePath)
          case _ => App.viewPost(pagePath, pageRoot)
        }
      // If invalid function specified:
      case (fun, met) => throwBadReq(
        "DwEQ435", "Bad method or query string: "+
           met +" ?"+ fun)
    }
    Some(action)
  }


  /**
   * The Twitter Ostrich admin service, listens on port 9100.
   */
  /*
  private val _ostrichAdminService = new toa.AdminHttpService(9100, 20, Stats,
    new toa.RuntimeEnvironment(getClass))
   */

  /**
   * Ensures lazy values are initialized early, so everything
   * fails fast.  And starts Twitter Ostrich.
   */
  override def onStart(app: Application) {
    Debiki.SystemDao

    // For now, disable in dev mode — because of the port conflict that
    // causes an error on reload and restart, see below (search for "conflict").
    /*
    _ostrichAdminService.start()
    Logger.info("Twitter Ostrich listening on port "+
       _ostrichAdminService.address.getPort)
     */
  }


  /**
   * Stops Twitter Ostrich admin service, and
   * SHOULD stop the Mailer and QuotaManager without losing in memory data.
   */
  override def onStop(app: Application) {
    Logger.info("Shutting down, gracefully...")
    //_ostrichAdminService.shutdown()

    // COULD stop Twitter Ostrich on reload too -- currently there's a
    // port conflict on reload.
    // See: <https://groups.google.com/
    //    forum/?fromgroups#!topic/play-framework/g6uixxX2BVw>
    // "There is an Actor system reserved for the application code that is
    // automatically shutdown when the application restart. You can access it
    // in:  play.api.libs.Akka.system"

  }

}


// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqn list ft=scala

