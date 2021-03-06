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
package com.flipkart.connekt.busybees.streams.errors

import scala.util.control.NoStackTrace

class ConnektException(message: String, cause: Throwable = null) extends RuntimeException(message, cause) with NoStackTrace

case class ConnektPNStageException(messageId: String,
                                 client: String,
                                 destinations: Set[String],
                                 eventType: String,
                                 appName: String,
                                 platform: String,
                                 context: String,
                                 meta: Map[String, Any],
                                 message: String,
                                 cause: Throwable,
                                 timeStamp: Long = System.currentTimeMillis()) extends ConnektException(message, cause)

case class ConnektStageException(messageId: String,
                                 client: String,
                                 destinations: Set[String],
                                 eventType: String,
                                 appName: String,
                                 channel: String,
                                 context: String,
                                 meta: Map[String, Any],
                                 message: String,
                                 cause: Throwable,
                                 timeStamp: Long = System.currentTimeMillis()) extends ConnektException(message, cause)

