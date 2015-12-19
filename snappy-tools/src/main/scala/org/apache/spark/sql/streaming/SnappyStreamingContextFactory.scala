package org.apache.spark.sql.streaming

import com.typesafe.config.Config
import io.snappydata.impl.LeadImpl
import spark.jobserver.context.SparkContextFactory
import spark.jobserver.{ContextLike, SparkJobBase}

import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Milliseconds, StreamingContext}


/**
  * Created by soubhikc on 14/12/15.
  */
trait SnappyStreamingJob extends SparkJobBase {
  type C = StreamingSnappyContext
}


/**
  * Created by soubhikc on 14/12/15.
  */
class SnappyStreamingContextFactory extends SparkContextFactory {

  override type C = StreamingSnappyContext with ContextLike

  override def makeContext(sparkConf: SparkConf, config: Config, contextName: String): C = {
    val interval = config.getInt("streaming.batch_interval")

    new StreamingSnappyContext(
      new StreamingContext(LeadImpl.getInitializingSparkContext(), Milliseconds(interval))
    ) with ContextLike {

      override def isValidJob(job: SparkJobBase): Boolean = job.isInstanceOf[SnappyStreamingJob]

      override def stop(): Unit = {
        val stopGracefully = config.getBoolean("streaming.stopGracefully")
        streamingContext.stop(false, stopGracefully)
      }
    }
  }
}