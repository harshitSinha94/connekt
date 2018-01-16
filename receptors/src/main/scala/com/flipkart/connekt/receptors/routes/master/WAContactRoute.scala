/*
 *         -╥⌐⌐⌐⌐            -⌐⌐⌐⌐-
 *      ≡╢░░░░⌐\░░░φ     ╓╝░░░░⌐░░░░╪╕
 *     ╣╬░░`    `░░░╢┘ φ▒╣╬╝╜     ░░╢╣Q
 *    ║╣╬░⌐        ` ╤▒▒▒Å`        ║╢╬╣
 *    ╚╣╬░⌐        ╔▒▒▒▒`«╕        ╢╢╣▒
 *     ╫╬░░╖    .░ ╙╨╨  ╣╣╬░φ    ╓φ░╢╢Å
 *      ╙╢░░░░⌐"░░░╜     ╙Å░░░░⌐░░░░╝`
 *        ``˚¬ ⌐              ˚˚⌐´
 *
 *      Copyright © 2016 Flipkart.com
 */
package com.flipkart.connekt.receptors.routes.master

import akka.connekt.AkkaHelpers._
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile, ServiceFactory}
import com.flipkart.connekt.commons.iomodels._
import com.flipkart.connekt.commons.services.{ConnektConfig, GuardrailService}
import com.flipkart.connekt.receptors.routes.BaseJsonHandler
import com.flipkart.connekt.receptors.routes.helper.{PhoneNumberHelper, WAContactCheckHelper}
import com.flipkart.connekt.commons.utils.StringUtils
import com.flipkart.connekt.commons.entities.Channel
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.{Failure, Success}

case class WAContactResponse(contactDetails: Any, invalidNumbers: List[String])
case class WACheckContactResp(exists: String, @JsonInclude(Include.NON_NULL) subscribed: Any)

class WAContactRoute(implicit am: ActorMaterializer) extends BaseJsonHandler {

  private implicit val ioDispatcher = am.getSystem.dispatchers.lookup("akka.actor.route-blocking-dispatcher")

  private lazy implicit val stencilService = ServiceFactory.getStencilService
  private val checkContactInterval = ConnektConfig.getInt("wa.check.contact.interval.days").get

  val route =
    authenticate {
      user =>
        pathPrefix("v1") {
          pathPrefix("whatsapp" / "checkcontact" / Segment) {
            (appName: String) =>
              pathEndOrSingleSlash {
                post {
                  meteredResource("checkcontact") {
                    authorize(user, "CHECK_CONTACT", s"CHECK_CONTACT_$appName") {
                      entity(as[ObjectNode]) { obj =>
                        complete {
                          Future {
                            profile(s"whatsapp.post.checkcontact.$appName") {
                              val destinations = obj.get("destinations").asInstanceOf[ArrayNode].elements().asScala.map(_.asText).toSet
                              val formattedDestination = ListBuffer[String]()
                              val invalidDestinations = ListBuffer[String]()
                              destinations.foreach(d => {
                                PhoneNumberHelper.validateNFormatNumber(appName, d) match {
                                  case Some(n) => formattedDestination += n
                                  case None => invalidDestinations += d
                                }
                              })
                              val (waValidUsers, waInvalidUsers) = WAContactCheckHelper.checkContact(appName, formattedDestination.toSet)
                              GenericResponse(StatusCodes.OK.intValue, null, Response("Some message", WAContactResponse(waValidUsers ::: waInvalidUsers, invalidDestinations.toList)))
                            }
                          }(ioDispatcher)
                        }
                      }
                    }
                  }
                }
              } ~ pathPrefix(Segment) {
                (destination: String) =>
                  pathEndOrSingleSlash {
                    get {
                      meteredResource("checkcontact") {
                        authorize(user, "CHECK_CONTACT", s"CHECK_CONTACT_$appName") {
                          parameterMap { params =>
                            val bucket = params.getOrElse("bucket","")
                            val subBucket = params.getOrElse("subBucket","")
                            val bucketsNonEmpty = !(StringUtils.isNullOrEmpty(bucket) || StringUtils.isNullOrEmpty(subBucket))
                            require(bucketsNonEmpty || params.isEmpty, "Both bucket and subBucket should be non empty")
                            complete {
                              Future {
                                profile(s"whatsapp.get.checkcontact.$appName") {
                                  PhoneNumberHelper.validateNFormatNumber(appName, destination) match {
                                    case Some(n) =>
                                      val subscribed = if (bucketsNonEmpty) {
                                        GuardrailService.isGuarded[String, Boolean, Map[_,_]](appName, Channel.WA, n, params + ("domain" -> appName)) match {
                                          case Success(sub) => !sub
                                          case Failure(_) => false
                                        }
                                      } else null
                                      val (waValidUsers, waInvalidUsers) = WAContactCheckHelper.checkContact(appName, Set(n))
                                      GenericResponse(StatusCodes.OK.intValue, null, Response(s"WA status for destination $destination", WACheckContactResp((waValidUsers ::: waInvalidUsers).head.exists, subscribed)))
                                    case None =>
                                      ConnektLogger(LogFile.PROCESSORS).error(s"Dropping whatsapp invalid numbers: $destination")
                                      GenericResponse(StatusCodes.BadRequest.intValue, null, Response(s"Dropping whatsapp invalid numbers $destination", null))
                                  }
                                }
                              }(ioDispatcher)
                            }
                          }
                        }
                      }
                    }
                  }
              }
          }
        }
    }
}
