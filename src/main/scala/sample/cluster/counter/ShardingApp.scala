package sample.cluster.counter

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.Props
import akka.contrib.pattern.ClusterSharding
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.actor.Identify
import akka.actor.ActorPath
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.ActorIdentity
import akka.persistence.journal.leveldb.SharedLeveldbJournal

object ShardingApp {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty)
      startup(Seq("2551", "2552", "0"))
    else
      startup(args)
  }

  def startup(ports: Seq[String]): Unit = {
    ports foreach { port =>
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load())

      // Create an Akka system
      val system = ActorSystem("ClusterSystem", config)

      startupSharedJournal(system, startStore = (port == "2551"), path =
        ActorPath.fromString("akka.tcp://ClusterSystem@127.0.0.1:2551/user/store"))

      ClusterSharding(system).start(
        typeName = "Counter",
        entryProps = Some(Props[Counter]),
        idExtractor = Counter.idExtractor,
        shardResolver = Counter.shardResolver)

      ClusterSharding(system).start(
        typeName = "AnotherCounter",
        entryProps = Some(Props[Counter]),
        idExtractor = Counter.idExtractor,
        shardResolver = Counter.shardResolver)

      system.actorOf(Props[Bot], "bot")
    }

    def startupSharedJournal(system: ActorSystem, startStore: Boolean, path: ActorPath): Unit = {
      // Start the shared journal one one node (don't crash this SPOF)
      // This will not be needed with a distributed journal
      if (startStore)
        system.actorOf(Props[SharedLeveldbStore], "store")
      // register the shared journal
      import system.dispatcher
      implicit val timeout = Timeout(1.minute)
      val f = (system.actorSelection(path) ? Identify(None))
      f.onSuccess {
        case ActorIdentity(_, Some(ref)) => SharedLeveldbJournal.setStore(ref, system)
        case _ =>
          system.log.error("Shared journal not started at {}", path)
          system.shutdown()
      }
      f.onFailure {
        case _ =>
          system.log.error("Lookup of shared journal at {} timed out", path)
          system.shutdown()
      }
    }

  }

}

