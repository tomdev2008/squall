/*
 * Copyright (c) 2011-2015 EPFL DATA Laboratory
 * Copyright (c) 2014-2015 The Squall Collaboration (see NOTICE)
 *
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.epfl.data.squall.test

import ch.epfl.data.squall.main.Main
import ch.epfl.data.squall.query_plans.QueryBuilder
import ch.epfl.data.squall.storage.{BasicStore, KeyValueStore}
import ch.epfl.data.squall.utilities.{LocalMergeResults, StormWrapper, SystemParameters, SquallContext}
import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import java.io._
import org.scalatest._
import org.slf4j.LoggerFactory

class TestSuite extends FunSuite with BeforeAndAfterAll {

  abstract sealed class Mode {
    def getConf(confName: String): java.util.Map[_, _]
  }

  case class PlanRunner() extends Mode {
    def confDir = new File("../test/squall_plan_runner/confs/local")

    override def getConf(confName: String) = {
      val confPath = confDir + "/" + confName
      SystemParameters.fileToStormConfig(confPath)
    }
  }

  case class SQL() extends Mode {
    def confDir = new File("../test/squall/confs/local")

    override def getConf(confName: String) = {
      val parser = new ch.epfl.data.squall.api.sql.main.ParserMain()
      val confPath = confDir + "/" + confName
      SystemParameters.mapToStormConfig(parser.createConfig(confPath))
    }
  }

  object Logging {
    var fileAppender: FileAppender[ILoggingEvent] = null;
    var logbackLogger: Logger = null;

    def beginLog(confName: String) = {
      // http://stackoverflow.com/questions/7824620/logback-set-log-file-name-programatically
      val loggerContext: LoggerContext = LoggerFactory.getILoggerFactory().asInstanceOf[LoggerContext]
      logbackLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

      val encoder: PatternLayoutEncoder = new PatternLayoutEncoder()
      encoder.setContext(loggerContext)
      encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n")
      encoder.start()

      val verbosity = System.getenv("SQUALL_LOG_VERBOSE")
      if (verbosity != "TRUE") {
        logbackLogger.detachAppender("STDOUT")
      }

      fileAppender = new FileAppender()
      fileAppender.setContext(loggerContext)
      fileAppender.setName(confName)
      // set the file name
      val tempFile = File.createTempFile(confName, ".log")
      println("\tWriting test output to " + tempFile.getAbsolutePath())
      fileAppender.setFile(tempFile.getAbsolutePath())
      fileAppender.setEncoder(encoder)
      fileAppender.start()

      logbackLogger.addAppender(fileAppender)

      // OPTIONAL: print logback internal status messages
      //  StatusPrinter.print(loggerContext)
    }

    def endLog() = {
      logbackLogger.detachAppender(fileAppender)
      fileAppender.stop()
    }
  }

  def runQuery(confName: String): BasicStore[Object] = {
    Logging.beginLog(confName)

    val conf = PlanRunner().getConf(confName)
    val queryPlan = Main.chooseQueryPlan(conf)

    SystemParameters.putInMap(conf, "DIP_TOPOLOGY_NAME", confName)
    val context = new SquallContext(conf);

    val builder = queryPlan.createTopology(context)

    val result = StormWrapper.localSubmitAndWait(context, queryPlan)

    Logging.endLog()
    result
  }

  def runSQL(confName: String): BasicStore[Object] = {
    Logging.beginLog(confName)
    val parser = new ch.epfl.data.squall.api.sql.main.ParserMain()

    val conf = SQL().getConf(confName)

    val queryPlan = parser.generatePlan(conf)
    parser.putAckers(queryPlan, conf)
    SystemParameters.putInMap(conf, "DIP_TOPOLOGY_NAME", confName)
    val context = new SquallContext(conf);

    val builder = queryPlan.createTopology(context)
    val result = StormWrapper.localSubmitAndWait(context, queryPlan)

    Logging.endLog()
    result
  }

  def expectedResultFor(result: BasicStore[Object], confName: String, mode: Mode = PlanRunner()): BasicStore[Object] = {
    expectedResultFor(result, confName, x => x.toDouble.asInstanceOf[Object], mode)
  }

  def expectedResultFor[T](result: BasicStore[T], confName: String, convert: (String => T), mode: Mode): BasicStore[T] = {
    val conf = mode.getConf(confName)
    val resultsFile = LocalMergeResults.getResultFilePath(conf)

    val expected = new KeyValueStore[String,T](conf)

    val source = scala.io.Source.fromFile(resultsFile)
    source.getLines foreach { l =>
      val value: T = convert(l.split(" = ").last)

      val key = if (l.split(" = ").length > 1) {
        l.split(" = ")(0)
      } else {
        "SEK"
      }

      expected.insert( key, value.asInstanceOf[Object] )
    }

    expected
  }

  def testSQL(confName: String) = {
    test(confName) {
      val result = runSQL(confName)
      assert(result.equals(expectedResultFor(result, confName, mode = SQL())))
    }
  }

  // for (confFile <- confDir.listFiles()) {
  //   test(confFile.getName()) {
  //       ....
  //   }
  // }

  // test("") {
  //   val query = ""
  //   val result = runQuery(query)
  //   assert(result.equals(expectedResultFor(result, query)))
  // }


}

