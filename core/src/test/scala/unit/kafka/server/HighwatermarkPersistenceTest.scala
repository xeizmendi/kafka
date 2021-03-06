/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package kafka.server

import kafka.log.LogManager
import org.I0Itec.zkclient.ZkClient
import org.scalatest.junit.JUnit3Suite
import org.easymock.EasyMock
import org.junit._
import org.junit.Assert._
import kafka.common.KafkaException
import kafka.cluster.Replica
import kafka.utils.{SystemTime, KafkaScheduler, TestUtils, MockTime, Utils}
import java.util.concurrent.atomic.AtomicBoolean

class HighwatermarkPersistenceTest extends JUnit3Suite {

  val configs = TestUtils.createBrokerConfigs(2).map(new KafkaConfig(_))
  val topic = "foo"
  val logManagers = configs.map(config => new LogManager(config, new KafkaScheduler(1), new MockTime))
    
  @After
  def teardown() {
    for(manager <- logManagers; dir <- manager.logDirs)
      Utils.rm(dir)
  }

  def testHighWatermarkPersistenceSinglePartition() {
    // mock zkclient
    val zkClient = EasyMock.createMock(classOf[ZkClient])
    EasyMock.replay(zkClient)
    
    // create kafka scheduler
    val scheduler = new KafkaScheduler(2)
    scheduler.startup
    // create replica manager
    val replicaManager = new ReplicaManager(configs.head, new MockTime(), zkClient, scheduler, logManagers(0), new AtomicBoolean(false))
    replicaManager.startup()
    replicaManager.checkpointHighWatermarks()
    var fooPartition0Hw = hwmFor(replicaManager, topic, 0)
    assertEquals(0L, fooPartition0Hw)
    val partition0 = replicaManager.getOrCreatePartition(topic, 0, 1)
    // create leader and follower replicas
    val log0 = logManagers(0).getOrCreateLog(topic, 0)
    val leaderReplicaPartition0 = new Replica(configs.head.brokerId, partition0, SystemTime, 0, Some(log0))
    partition0.addReplicaIfNotExists(leaderReplicaPartition0)
    val followerReplicaPartition0 = new Replica(configs.last.brokerId, partition0, SystemTime)
    partition0.addReplicaIfNotExists(followerReplicaPartition0)
    replicaManager.checkpointHighWatermarks()
    fooPartition0Hw = hwmFor(replicaManager, topic, 0)
    assertEquals(leaderReplicaPartition0.highWatermark, fooPartition0Hw)
    try {
      followerReplicaPartition0.highWatermark
      fail("Should fail with KafkaException")
    }catch {
      case e: KafkaException => // this is ok
    }
    // set the highwatermark for local replica
    partition0.getReplica().get.highWatermark = 5L
    replicaManager.checkpointHighWatermarks()
    fooPartition0Hw = hwmFor(replicaManager, topic, 0)
    assertEquals(leaderReplicaPartition0.highWatermark, fooPartition0Hw)
    EasyMock.verify(zkClient)
  }

  def testHighWatermarkPersistenceMultiplePartitions() {
    val topic1 = "foo1"
    val topic2 = "foo2"
    // mock zkclient
    val zkClient = EasyMock.createMock(classOf[ZkClient])
    EasyMock.replay(zkClient)
    // create kafka scheduler
    val scheduler = new KafkaScheduler(2)
    scheduler.startup
    // create replica manager
    val replicaManager = new ReplicaManager(configs.head, new MockTime(), zkClient, scheduler, logManagers(0), new AtomicBoolean(false))
    replicaManager.startup()
    replicaManager.checkpointHighWatermarks()
    var topic1Partition0Hw = hwmFor(replicaManager, topic1, 0)
    assertEquals(0L, topic1Partition0Hw)
    val topic1Partition0 = replicaManager.getOrCreatePartition(topic1, 0, 1)
    // create leader log
    val topic1Log0 = logManagers(0).getOrCreateLog(topic1, 0)
    // create a local replica for topic1
    val leaderReplicaTopic1Partition0 = new Replica(configs.head.brokerId, topic1Partition0, SystemTime, 0, Some(topic1Log0))
    topic1Partition0.addReplicaIfNotExists(leaderReplicaTopic1Partition0)
    replicaManager.checkpointHighWatermarks()
    topic1Partition0Hw = hwmFor(replicaManager, topic1, 0)
    assertEquals(leaderReplicaTopic1Partition0.highWatermark, topic1Partition0Hw)
    // set the highwatermark for local replica
    topic1Partition0.getReplica().get.highWatermark = 5L
    replicaManager.checkpointHighWatermarks()
    topic1Partition0Hw = hwmFor(replicaManager, topic1, 0)
    assertEquals(5L, leaderReplicaTopic1Partition0.highWatermark)
    assertEquals(5L, topic1Partition0Hw)
    // add another partition and set highwatermark
    val topic2Partition0 = replicaManager.getOrCreatePartition(topic2, 0, 1)
    // create leader log
    val topic2Log0 = logManagers(0).getOrCreateLog(topic2, 0)
    // create a local replica for topic2
    val leaderReplicaTopic2Partition0 =  new Replica(configs.head.brokerId, topic2Partition0, SystemTime, 0, Some(topic2Log0))
    topic2Partition0.addReplicaIfNotExists(leaderReplicaTopic2Partition0)
    replicaManager.checkpointHighWatermarks()
    var topic2Partition0Hw = hwmFor(replicaManager, topic2, 0)
    assertEquals(leaderReplicaTopic2Partition0.highWatermark, topic2Partition0Hw)
    // set the highwatermark for local replica
    topic2Partition0.getReplica().get.highWatermark = 15L
    assertEquals(15L, leaderReplicaTopic2Partition0.highWatermark)
    // change the highwatermark for topic1
    topic1Partition0.getReplica().get.highWatermark = 10L
    assertEquals(10L, leaderReplicaTopic1Partition0.highWatermark)
    replicaManager.checkpointHighWatermarks()
    // verify checkpointed hw for topic 2
    topic2Partition0Hw = hwmFor(replicaManager, topic2, 0)
    assertEquals(15L, topic2Partition0Hw)
    // verify checkpointed hw for topic 1
    topic1Partition0Hw = hwmFor(replicaManager, topic1, 0)
    assertEquals(10L, topic1Partition0Hw)
    EasyMock.verify(zkClient)
  }

  def hwmFor(replicaManager: ReplicaManager, topic: String, partition: Int): Long = {
    replicaManager.highWatermarkCheckpoints(replicaManager.config.logDirs(0)).read(topic, partition)
  }
  
}