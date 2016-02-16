package com.flipkart.connekt.receptors.routes.common

import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import com.flipkart.connekt.commons.entities.Credentials
import com.flipkart.connekt.commons.iomodels.{GenericResponse, Response}
import com.flipkart.connekt.receptors.routes.BaseHandler
import com.flipkart.connekt.receptors.service.{AuthenticationService, TokenService}

/**
 * Created by avinash.h on 1/21/16.
 */

class LdapAuthRoute(implicit am: ActorMaterializer) extends BaseHandler {

  val route =
    pathPrefix("v1") {
      path("auth" / "ldap") {
        post {
          entity(as[Credentials]) {
            user =>
              AuthenticationService.authenticateLdap(user.username, user.password) match {
                case true =>
                  TokenService.set(user.username) match {
                    case Some(tokenId) =>
                      complete(GenericResponse(StatusCodes.OK.intValue, null, Response("Logged in successfully. Please note your tokenId.", Map("tokenId" -> tokenId))))
                    case None =>
                      complete(GenericResponse(StatusCodes.InternalServerError.intValue, null, Response("Unable to generate token", null)))
                  }
                case false =>
                  complete(GenericResponse(StatusCodes.Unauthorized.intValue, null, Response("Unauthorised, Invalid Username/Password", null)))
              }

          }
        }
      }
    }
}
