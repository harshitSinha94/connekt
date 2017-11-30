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
package com.flipkart.connekt.busybees.streams.flows.dispatchers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream._
import com.flipkart.connekt.busybees.models._
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile}
import com.flipkart.connekt.commons.services.ConnektConfig
import com.typesafe.config.Config
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.{TrustManagerConfig, TrustStoreConfig}

import scala.concurrent.ExecutionContextExecutor

class HttpDispatcher(actorSystemConf: Config) {

  implicit val httpSystem: ActorSystem = ActorSystem("http-out", actorSystemConf)
  implicit val httpMat: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = httpSystem.dispatcher

  private val gcmPoolClientFlow = Http().superPool[GCMRequestTracker]()(httpMat)

  private val wnsPoolClientFlow = Http().superPool[WNSRequestTracker]()(httpMat)

  private val smsPoolClientFlow = Http().superPool[SmsRequestTracker]()(httpMat)

  private val waPoolInsecureClientFlow = {
    //    TODO: Remove this code once move to signed certified certs
    val certPath = ConnektConfig.getString("wa.certificate.path").get
    val trustStoreConfig = TrustStoreConfig(None, Some(certPath)).withStoreType("PEM")
    val trustManagerConfig = TrustManagerConfig().withTrustStoreConfigs(List(trustStoreConfig))
    val badSslConfig = AkkaSSLConfig().mapSettings(s => s.withLoose(s.loose
      .withAcceptAnyCertificate(true)
      .withDisableHostnameVerification(true)
    ).withTrustManagerConfig(trustManagerConfig))

    val maxConnections = ConnektConfig.getInt("wa.topology.max.parallel.connections").get
    val maxOpenRequests = ConnektConfig.getInt("wa.topology.max.open.requests").get
    val maxPipelineLimit = ConnektConfig.getInt("wa.topology.max.pipeline.limit").get

    Http().superPool[RequestTracker](
      Http().createClientHttpsContext(badSslConfig),
      ConnectionPoolSettings(httpSystem).withMaxOpenRequests(maxOpenRequests).withMaxConnections(maxConnections).withPipeliningLimit(maxPipelineLimit)
    )(httpMat)
  }

  private val openWebPoolClientFlow = Http().superPool[OpenWebRequestTracker]()(httpMat)

  private val emailPoolClientFlow = Http().superPool[EmailRequestTracker]()(httpMat)

}

object HttpDispatcher {

  private var instance: Option[HttpDispatcher] = None

  def init(actorSystemConf: Config) = {
    if (instance.isEmpty) {
      ConnektLogger(LogFile.SERVICE).info(s"Creating HttpDispatcher actor-system with conf: ${actorSystemConf.toString}")
      instance = Some(new HttpDispatcher(actorSystemConf))
    }
  }

  def gcmPoolClientFlow = instance.map(_.gcmPoolClientFlow).get

  def smsPoolClientFlow = instance.map(_.smsPoolClientFlow).get

  def waPoolClientFlow = instance.map(_.waPoolInsecureClientFlow).get

  def wnsPoolClientFlow = instance.map(_.wnsPoolClientFlow).get

  def openWebPoolClientFlow = instance.map(_.openWebPoolClientFlow).get

  def emailPoolClientFlow = instance.map(_.emailPoolClientFlow).get


}
