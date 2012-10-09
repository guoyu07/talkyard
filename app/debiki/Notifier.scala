/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */

package debiki

import akka.actor._
import akka.actor.Actor._
import akka.util.duration._
import com.amazonaws.AmazonClientException
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.simpleemail._
import com.amazonaws.services.simpleemail.model._
import com.debiki.v0._
import java.{util => ju}
import play.api._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import play.api.Play.current
import Prelude._


object Notifier {

  /**
   * Starts a single notifier actor.
   *
   * BUG: SHOULD terminate it before shutdown, in a manner that
   * doesn't accidentally forget forever to send some notifications.
   * (Also se object Mailer.)
   */
  def startNewActor(daoFactory: DaoFactory): ActorRef = {
    val actorRef = Akka.system.actorOf(Props(
       new Notifier(daoFactory)), name = "NotifierActor")
    Akka.system.scheduler.schedule(0 seconds, 20 seconds, actorRef, "SendNotfs")
    actorRef
  }

}


/**
 * Loads pending notifications from the database, and asks
 * Mailer to send those notifications. (For example, asks Mailer to notify
 * someone of a reply to his/her comment.)
 *
 * Updates the notfs so no one else also attempts to construct and send
 * the same emails.
 *
 * Thread safe.
 */
class Notifier(val daoFactory: DaoFactory) extends Actor {


  val logger = play.api.Logger("app.notifier")


  def receive = {
    case "SendNotfs" =>
      val notfsToMail =
        daoFactory.systemDao.loadNotfsToMailOut(
           delayInMinutes = 0, numToLoad = 11)
      logger.trace("Loaded "+ notfsToMail.notfsByTenant.size +
         " notfs, to "+ notfsToMail.usersByTenantAndId.size +" users.")
      _trySendEmailNotfs(notfsToMail)
  }


  /**
   * Sends notifications, for all tenants and notifications specified.
   */
  def _trySendEmailNotfs(notfsToMail: NotfsToMail) {

    for {
      (tenantId, tenantNotfs) <- notfsToMail.notfsByTenant
      notfsByUserId: Map[String, Seq[NotfOfPageAction]] =
         tenantNotfs.groupBy(_.recipientUserId)
      (userId, userNotfs) <- notfsByUserId
    }{
      logger.debug("Considering "+ userNotfs.size +" notfs to user "+ userId)

      val tenantDao = daoFactory.buildTenantDao(
         QuotaConsumers(tenantId = tenantId))
      val tenant = tenantDao.loadTenant()
      val userOpt = notfsToMail.usersByTenantAndId.get(tenantId -> userId)

      // Send email, or remember why we didn't.
      val problemOpt = (tenant.chost, userOpt.map(_.emailNotfPrefs)) match {
        case (Some(chost), Some(EmailNotfPrefs.Receive)) =>
          _constructAndSendEmail(tenantDao, chost, userOpt.get, userNotfs)
          None
        case (None, _) =>
          val problem = "No chost for tenant id: "+ tenantId
          logger.warn("Skipping email to user id "+ userId +": "+ problem)
          Some(problem)
        case (_, None) =>
          val problem = "User not found"
          logger.warn("Skipping email to user id "+ userId +": "+ problem)
          Some(problem)
        case (_, Some(_)) =>
          Some("User declines emails")
      }

      // If we decided not to send the email, remember not to try again.
      problemOpt foreach { problem =>
        tenantDao.skipEmailForNotfs(userNotfs,
           debug = "Email skipped: "+ problem)
      }
    }
  }


  def _constructAndSendEmail(tenantDao: TenantDao, chost: TenantHost,
        user: User, userNotfs: Seq[NotfOfPageAction]) {
    // Save the email in the db, before sending it, so even if the server
    // crashes it'll always be found, should the receiver attempt to
    // unsubscribe. (But if you first send it, then save it, the server
    // might crash inbetween and it wouldn't be possible to unsubscribe.)
    val origin =
      (chost.https.required ? "https://" | "http://") + chost.address
    val email = _constructEmail(origin, user, userNotfs)
    tenantDao.saveUnsentEmailConnectToNotfs(email, userNotfs)

    logger.debug("About to send email to "+ email.sentTo)
    Debiki.sendEmail(email, tenantDao.tenantId)
  }


  def _constructEmail(origin: String, user: User,
        notfs: Seq[NotfOfPageAction]): Email = {

    val rcptEmailAddr =
      if (user.email == "kajmagnus@debiki.se"
       || user.email == "support@debiki.se"
       || user.email == "non-existing-address@debiki.se"
       || user.email == "kajmagnus79@gmail.com"
       || user.email == "kajmagnus79d@gmail.com"
       || user.email == "kaj.lindberg@telia.com") {
      // These addresses are AWS SES verified addresses.
      user.email
    } else {
      // Direct all email to this verified address, for now.
      "kajmagnus@debiki.se"
    }

    val subject: String =
      if (notfs.size == 1) "You have a reply, to one of your comments"
      else "You have replies, to comments of yours"

    val email = Email(sendTo = rcptEmailAddr, subject = subject,
      bodyHtmlText = "?")

    def notfToHtml(notf: NotfOfPageAction): xml.Node = {
      // For now, don't bother about the
      // redirect from "/-pageId" to the actual page path.
      val pageUrl = origin +"/-"+ notf.pageId
      // Currently eventActionId is always a post (because
      // Notification.calcFrom currently only generates notfs for replies).
      if (notf.eventType != NotfOfPageAction.Type.PersonalReply) {
        logger.warn("Unsupported notification type: "+ notf.eventType)
      }
      val eventUrl = pageUrl +"#post-"+ notf.eventActionId
      // Include only one link per notification, or people will (I guess)
      // not click the link to the actual reply (that link also highlights
      // the reply :-)). I'd guess they instead would click the
      // visually *largest* link, e.g. to the page, and then not find the new
      // reply, and feel annoyed.
      <p>
        You have a reply, <a href={eventUrl}>here</a>,<br/>
        on page <i>{notf.pageTitle}</i>,<br/>
        written by <i>{notf.eventUserDispName}</i>.
      </p>
    }

    val htmlContent =
      <div>
        <p>Dear {user.displayName},</p>
        { notfs.map(notfToHtml _): xml.NodeSeq }
        <p>
          Kind regards,<br/>
          <a href="http://www.debiki.com">Debiki</a>
        </p>
        <p style='font-size: 80%; opacity: 0.65; margin-top: 2em;'>
          <a href={origin +"/?unsubscribe&email-id="+ email.id}>Unsubscribe</a>
        </p>
      </div>.toString

    email.copy(bodyHtmlText = htmlContent)
  }

}
