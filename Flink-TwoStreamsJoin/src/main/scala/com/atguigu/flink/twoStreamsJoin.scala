package com.atguigu.flink

import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.co.CoProcessFunction
import org.apache.flink.streaming.api.scala.{OutputTag, StreamExecutionEnvironment}
import org.apache.flink.util.Collector
import org.apache.flink.streaming.api.scala._

object twoStreamsJoin {

  case class OrderEvent(orderId:String,orderType:String,orderTime:String)
  case class PayEvent(orderId:String,opeartorType:String,orderTime:String)

  val unmatchedOrders = new OutputTag[OrderEvent]("unmatchedOrders"){}
  val unmatchedPays = new OutputTag[PayEvent]("unmatchedPays"){}

  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    val orders = env
      .fromCollection(List(
        OrderEvent("1", "create", "1558430842"),
        OrderEvent("2", "create", "1558430843"),
        OrderEvent("1", "pay", "1558430844"),
        OrderEvent("2", "pay", "1558430845"),
        OrderEvent("3", "create", "1558430849"),
        OrderEvent("3", "pay", "1558430849")
      )).assignAscendingTimestamps(_.orderTime.toLong * 1000)
      .keyBy("orderId")

    val pays = env
      .fromCollection(List(
        PayEvent("1", "weixin", "1558430847"),
        PayEvent("2", "zhifubao", "1558430848"),
        PayEvent("4", "zhifubao", "1558430850")
      ))
      .assignAscendingTimestamps(_.orderTime.toLong * 1000)
      .keyBy("orderId")

    val processed = orders.connect(pays).process(new EnrichmentFunction)

    processed.getSideOutput[PayEvent](unmatchedPays).print()

    env.execute("ExpiringState (scala)")


  }

  class EnrichmentFunction extends  CoProcessFunction[OrderEvent,PayEvent,(OrderEvent,PayEvent)]{

    lazy val orderState:ValueState[OrderEvent] = getRuntimeContext.getState(
      new ValueStateDescriptor[OrderEvent]("saved ride",classOf[OrderEvent])
    )
    lazy val payState:ValueState[PayEvent] = getRuntimeContext.getState(
      new ValueStateDescriptor[PayEvent]("saved fare",classOf[PayEvent])
    )
    override def processElement1(order: OrderEvent, context: CoProcessFunction[OrderEvent, PayEvent, (OrderEvent, PayEvent)]#Context, collector: Collector[(OrderEvent, PayEvent)]): Unit = {
      val pay = payState.value
      if(pay != null){
        payState.clear
        collector.collect((order,pay))
      }else{
        orderState.update(order)
        context.timerService.registerEventTimeTimer(order.orderTime.toLong * 1000)
      }
    }


    override def processElement2(pay: PayEvent, context: CoProcessFunction[OrderEvent, PayEvent, (OrderEvent, PayEvent)]#Context, collector: Collector[(OrderEvent, PayEvent)]): Unit = {
      val order = orderState.value
      if(order!=null){
        orderState.clear()
        collector.collect((order,pay))
      }else{
        payState.update(pay)
        context.timerService.registerEventTimeTimer(pay.orderTime.toLong * 1000)
      }
    }

    //回调函数
    override def onTimer(timestamp: Long, ctx: CoProcessFunction[OrderEvent, PayEvent, (OrderEvent, PayEvent)]#OnTimerContext, out: Collector[(OrderEvent, PayEvent)]): Unit = {

      if(payState.value() != null){
        ctx.output(unmatchedPays,payState.value)
        payState.clear()
      }
      if (orderState.value() != null){
        ctx.output(unmatchedOrders,orderState.value())
        orderState.clear()
      }
    }
  }

}
