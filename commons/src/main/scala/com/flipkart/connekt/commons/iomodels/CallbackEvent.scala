package com.flipkart.connekt.commons.iomodels

import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}

/**
 *
 *
 * @author durga.s
 * @version 12/8/15
 */
@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.PROPERTY,
  property = "type"
)
@JsonSubTypes(Array(
  new Type(value = classOf[PNCallbackEvent], name = "PN"),
  new Type(value = classOf[EmailCallbackEvent], name = "EMAIL")
))
abstract class CallbackEvent