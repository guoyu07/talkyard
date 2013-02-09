/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */

package test.e2e

import com.debiki.v0.{Page, PageRole}
import com.debiki.v0.Prelude._
import java.{lang => jl}


/**
 * Runs the StyleSiteSpec suite
 * in SBT:
 *  test-only test.e2e.StyleSiteSpecRunner
 * in SBT's test:console:
 *  (new test.e2e.StyleSiteSpecRunner {}).execute()
 */
class StyleSiteSpecRunner extends org.scalatest.Suites(
  new StyleSiteSpecSpec {})
  with ChromeSuiteMixin


/**
 * Styles a website, and verifies that pages and styles and config pages
 * are refreshed when things have been edited. That is, tests that there's
 * no stale stuff in any cache.
 */
// From ScalaTest 2.0-M5 and above, use this: `@DoNotDiscover`
// instead of `abstract`.
abstract class StyleSiteSpecSpec extends DebikiBrowserSpec {


  val NextBackgroundColor = "rgba(0, 0, 255, 1)" // green
  val LastBackgroundColor = "rgba(227, 228, 250, 1)"  // lavender

  var dashboardWindow: WindowTarget = null
  var homepageWindow: WindowTarget = null
  var stylesheetWindow: WindowTarget = null
  var genericPageWindow: WindowTarget = null


  "One can style a website" - {


    "create new site and goto dashboard" - {
      val siteName = clickCreateSite()
      clickWelcomeLoginToDashboard(siteName)
      "boo" in { dashboardWindow = window(webDriver.getWindowHandle) }
    }


    "check homepage background color" - {
      "open homepage" in {
        homepageWindow = openAndSwitchToFirstPage(DefaultHomepageTitle)
      }

      s"find the background painted in $DefaultBackgroundColor" in {
        val background = getBackgroundColor
        background must be === DefaultBackgroundColor
      }
    }


    "refreshes cached pages and asset bundles, when optional asset created" - {

      "create and edit /themes/local/styles.css" - {
        "create style sheet" in {
          switch to dashboardWindow
          clickCreateNewPage(PageRole.Code, "css")
          stylesheetWindow = window(webDriver.getWindowHandle)
        }

        s"edit stylesheet: change background to $NextBackgroundColor" in {
          editBackgroundTo(NextBackgroundColor)
        }
      }


      "create a new page, check background color" - {
        // This tests if asset bundles are rebuilt when an optional dependency
        // is created, after the bundle has been cached.

        "create new page" in {
          switch to dashboardWindow
          clickCreateNewPage(PageRole.Generic)
          genericPageWindow = window(webDriver.getWindowHandle)
        }

        s"find the background painted in $NextBackgroundColor" in {
          val background = getBackgroundColor
          background must be === NextBackgroundColor
        }
      }


      "reload the homepage, check background color" - {
        // This tests if cached pages (the homepage) are refreshed when the asset bundle(s)
        // they're using have been modified.

        "reload homepage (cached version, with wrong color, should be discarded)" in {
          switch to homepageWindow
          reloadPage()
        }

        s"find the background painted in $NextBackgroundColor" in {
          val background = getBackgroundColor
          background must be === NextBackgroundColor
        }
      }
    }


    "refreshes cached pages and asset bundles when asset edited" - {

      s"edit stylesheet, change background to $LastBackgroundColor" in {
        switch to stylesheetWindow
        editBackgroundTo(LastBackgroundColor) //
      }

      "reload the homepage, check background color" - {
        // This tests both if the asset bundle has been regenerated,
        // and if cached pages are refreshed.

        "reload homepage (cached version, with wrong color, should be discarded)" in {
          switch to homepageWindow
          reloadPage()
        }

        s"find the background painted in $LastBackgroundColor" in {
          val background = getBackgroundColor
          background must be === LastBackgroundColor
        }
      }
    }
  }


  private def getBackgroundColor: String =
    find(cssSelector("body"))
      .getOrElse(fail("Body not found"))
      .underlying.getCssValue("background-color")


  private def editBackgroundTo(color: String) {
    clickAndEdit(Page.BodyId, i"""
      |body {
      |background-color: $color !important;
      |}
      |""")
  }

}
