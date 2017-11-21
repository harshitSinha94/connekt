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
package com.flipkart.connekt.busybees.streams.topologies

import akka.NotUsed
import akka.stream.{SourceShape, _}
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl.{GraphDSL, Merge, Source, _}
import com.flipkart.connekt.busybees.streams.ConnektTopology
import com.flipkart.connekt.busybees.streams.flows.{FlowMetrics, WATrackingFlow}
import com.flipkart.connekt.busybees.streams.flows.dispatchers.{HttpDispatcher, WAMediaDispatcher}
import com.flipkart.connekt.busybees.streams.flows.profilers.TimedFlowOps._
import com.flipkart.connekt.busybees.streams.flows.reponsehandlers.{WAMediaResponseHandler, WAResponseHandler}
import com.flipkart.connekt.busybees.streams.flows.transformers.WAProviderPrepare
import com.flipkart.connekt.busybees.streams.sources.KafkaSource
import com.flipkart.connekt.busybees.streams.topologies.WATopology._
import com.flipkart.connekt.commons.core.Wrappers.Try_
import com.flipkart.connekt.commons.entities.Channel
import com.flipkart.connekt.commons.factories.{ConnektLogger, LogFile, ServiceFactory}
import com.flipkart.connekt.commons.iomodels._
import com.flipkart.connekt.commons.services.ConnektConfig
import com.flipkart.connekt.commons.sync.SyncType.SyncType
import com.flipkart.connekt.commons.sync.{SyncDelegate, SyncType}
import com.flipkart.connekt.commons.utils.StringUtils._
import com.typesafe.config.Config

import scala.concurrent.ExecutionContextExecutor

/**
  * Created by saurabh.mimani on 14/11/17.
  */
class WATopology(kafkaConsumerConfig: Config) extends ConnektTopology[WACallbackEvent] with SyncDelegate {

  override def onUpdate(_type: SyncType, args: List[AnyRef]): Any = {
    _type match {
      case SyncType.CLIENT_QUEUE_CREATE => Try_ {
        ConnektLogger(LogFile.SERVICE).info(s"Busybees Restart for CLIENT_QUEUE_CREATE Client: ${args.head}, New Topic: ${args.last} ")
        restart
      }
      case _ =>
    }
  }


  override def sources: Map[CheckPointGroup, Source[ConnektRequest, NotUsed]] = {
    List(Channel.WA).flatMap {value =>
      ServiceFactory.getMessageService(Channel.WA).getTopicNames(Channel.WA, None).get match {
        case platformTopics if platformTopics.nonEmpty => Option(value.toString -> createMergedSource(value, platformTopics))
        case _ => None
      }
    }.toMap
  }

  private def createMergedSource(checkpointGroup: CheckPointGroup, topics: Seq[String]): Source[ConnektRequest, NotUsed] = Source.fromGraph(GraphDSL.create() { implicit b =>

    val groupId = kafkaConsumerConfig.getString("group.id")
    ConnektLogger(LogFile.PROCESSORS).info(s"WA:: Creating composite source for topics: ${topics.toString()}")

    val merge = b.add(Merge[ConnektRequest](topics.size))

    for (portNum <- 0 until merge.n) {
      val consumerGroup = s"${groupId}_$checkpointGroup"
      new KafkaSource[ConnektRequest](kafkaConsumerConfig, topic = topics(portNum), consumerGroup) ~> merge.in(portNum)
    }

    SourceShape(merge.out)
  })

  override def sink: Sink[WACallbackEvent, NotUsed] = Sink.fromGraph(GraphDSL.create() { implicit b =>
    val metrics = b.add(new FlowMetrics[WACallbackEvent](Channel.WA).flow)
    metrics ~> Sink.ignore

    SinkShape(metrics.in)
  })


  override def transformers: Map[CheckPointGroup, Flow[ConnektRequest, WACallbackEvent, NotUsed]] = {
    Map(Channel.WA.toString -> waTransformFlow(ioMat,ioDispatcher))
  }
}

object WATopology {

  def waTransformFlow(implicit ioMat:ActorMaterializer, ioDispatcher:  ExecutionContextExecutor): Flow[ConnektRequest, WACallbackEvent, NotUsed] = Flow.fromGraph(GraphDSL.create() { implicit b  =>

    /**
      * Whatsapp Topology
      *
      *                     +-------------------+        +----------+     +-----------------+      +-----------------------+     +---------------------+     +----------------+     +----------------------------+     +---------------------+     +-------------------------+      +--..
      *  ConnektRequest --> |SmsChannelFormatter| |----> |  Merger  | --> | ChooseProvider  |  --> | SeparateIntlReceivers | --> |  SmsProviderPrepare | --> |  SmsDispatcher | --> |SmsProviderResponseFormatter| --> |  SmsResponseHandler | --> |Response / Error Splitter| -+-> |Merger
      *                     +-------------------+ |      +----------+     +-----------------+      +-----------------------+     +---------------------+     +----------------+     +----------------------------+     +---------------------+     +-------------------------+  |   +-----
      *                                           +-------------------------------------------------------------------------------------------------------------------------------------------------------------------+---------------------------------------------------------+
      */

//    val render = b.add(new RenderFlow().flow)
    val mediaPartitioner = b.add(Partition[ConnektRequest](2,
      _.channelData.asInstanceOf[WARequestData].attachment match {
        case Some(_:Attachment) => 0
        case _ => 1
      }))
    val trackSmsParallelism = ConnektConfig.getInt("topology.sms.tracking.parallelism").get

    val waMediaDispatcher = b.add(new WAMediaDispatcher().flow)
    val waHttpPoolMediaFlow = b.add(HttpDispatcher.waPoolClientFlow.timedAs("waMediaRTT"))
    val waMediaResponseHandler = b.add(new WAMediaResponseHandler().flow)
    val waPrepare = b.add(new WAProviderPrepare().flow)
    val tracking = b.add(new WATrackingFlow(5)(ioDispatcher).flow)
    val merge = b.add(Merge[ConnektRequest](2))
    val waHttpPoolFlow = b.add(HttpDispatcher.waPoolClientFlow.timedAs("waRTT"))

    val waResponseHandler = b.add(new WAResponseHandler()(ioMat,ioDispatcher).flow)

    mediaPartitioner.out(0) ~> waMediaDispatcher ~> waHttpPoolMediaFlow ~> waMediaResponseHandler ~> merge.in(0)
    mediaPartitioner.out(1) ~>                                                                       merge.in(1)
    merge.out ~> tracking ~> waPrepare ~> waHttpPoolFlow ~> waResponseHandler

    FlowShape(mediaPartitioner.in, waResponseHandler.out)
  })


}
