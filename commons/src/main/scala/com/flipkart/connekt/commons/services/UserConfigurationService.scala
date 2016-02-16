package com.flipkart.connekt.commons.services

import com.flipkart.connekt.commons.cache.{LocalCacheManager, LocalCacheType}
import com.flipkart.connekt.commons.core.Wrappers._
import com.flipkart.connekt.commons.dao.DaoFactory
import com.flipkart.connekt.commons.entities.AppUserConfiguration
import com.flipkart.connekt.commons.entities.Channel.Channel
import com.flipkart.connekt.commons.metrics.Instrumented
import com.flipkart.metrics.Timed

import scala.util.Try

/**
 * Created by kinshuk.bairagi on 15/02/16.
 */
object UserConfigurationService extends Instrumented {

  @Timed("add")
  def add(config: AppUserConfiguration): Try[Unit] = Try_ {
    DaoFactory.getUserConfigurationDao.addUserConfiguration(config)
  }

  @Timed("get")
  def get(user: String, channel: Channel): Try[Option[AppUserConfiguration]] = Try_ {
    val cacheKey = s"$user-${channel.toString}"

    LocalCacheManager.getCache(LocalCacheType.UserConfiguration).get[AppUserConfiguration](cacheKey).orElse {
      val data = DaoFactory.getUserConfigurationDao.getUserConfiguration(user, channel)
      data.foreach(d => LocalCacheManager.getCache(LocalCacheType.UserConfiguration).put[AppUserConfiguration](cacheKey, d))
      data
    }

  }

}
