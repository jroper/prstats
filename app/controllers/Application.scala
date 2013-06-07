package controllers

import play.api.mvc._
import play.api.libs.iteratee.Concurrent
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.ws.WS
import com.ning.http.client.Realm.AuthScheme
import scala.concurrent.Future
import play.api.libs.Comet
import play.api.libs.json.{Json, JsArray}

object Application extends Controller {

  def index = Action {
    Ok(views.html.index(creds.map(_._1)))
  }

  var creds: Option[(String, String)] = None

  def ghcreds = Action { req =>
    for {
      json <- req.body.asJson
      username <- (json \ "username").asOpt[String] if !username.isEmpty
      password <- (json \ "password").asOpt[String] if !password.isEmpty
    } yield {
      println("Got creds for " + username)
      creds = Some((username, password))
    }
    Ok
  }

  def ghstats(repo: String, exclude: String) = Action {
    val excludes = exclude.split(",").map(_.trim).filterNot(_.isEmpty)

    val body = Concurrent.unicast[String](onStart = { channel =>
      doGhstats(repo, excludes, msg => {
        println(msg)
        channel.push(msg)
      }, () => channel.end())
    })

    Ok.stream(body &> Comet(callback = "parent.cometMessage"))
  }

  def doGhstats(repo: String, excludes: Seq[String], console: String => Unit, done: () => Unit) {
    console("Processing pull request stats for repo " + repo)
    creds.foreach(c => console("Using credentials for user ") + c._1)
    for {
      responses <- Future.sequence(excludes.map { org =>
        console("Looking up members of organisation " + org)
        authWS("https://api.github.com/orgs/" + org + "/members").get()
      })
      openPullRequests <- getPullRequests("https://api.github.com/repos/" + repo + "/pulls?state=open&per_page=100", "open", console)
      closedPullRequests <- getPullRequests("https://api.github.com/repos/" + repo + "/pulls?state=closed&per_page=100", "closed", console)
    } yield {

      val pullRequests = openPullRequests ::: closedPullRequests

      val excludedUsers = responses.flatMap(r => (r.json \\ "login").map(_.as[String])).toSet
      console("Retrieved " + excludedUsers.size + " members of excluded organisations")

      // Add the excludes to the excluded users
      val allExcludes = excludedUsers ++ excludes

      console("Retrieved " + pullRequests.size + " pull requests in total")
      val filtered = pullRequests.filterNot(allExcludes.contains)
      console(filtered.size + " pull requests from the community")
      val individuals = filtered.groupBy(identity).toSeq
      console(individuals.size + " unique community contributors")
      console(individuals.filter(_._2.size >= 5).size + " community contributors have contributed 5 or more pull requests")

      console("Individuals are:")
      individuals.map(_._1).foreach(console)

      done()
    }
  }

  def getPullRequests(url: String, state: String, console: String => Unit, page: Int = 1): Future[List[String]] = {
    for {
      response <- {
        console("Retrieving " + state + " pull request page " + page)
        authWS(url).get()
      }
      rest <- {
        if (response.status >= 400) {
          console("Page " + page + " returned status " + response.status)
          console("Message: " + response.body)
        }
        response.header("Link") match {
          case Some(RelNext(nextUrl)) => getPullRequests(nextUrl, state, console, page + 1)
          case _ => {
            console("All " + state + " pull requests retrieved")
            Future.successful(Nil)
          }
        }
      }
    } yield {
      response.json.as[JsArray].value.flatMap { pull =>
        (pull \ "head" \ "user" \ "login").asOpt[String].orElse {
          (pull \ "user" \ "login").asOpt[String]
        }
      }.toList ::: rest
    }
  }

  val RelNext = """.*<([^>]*)>;\s+rel="next".*""".r

  def authWS(url: String) = creds.map { c =>
    WS.url(url).withAuth(c._1, c._2, AuthScheme.BASIC)
  } getOrElse WS.url(url)

}