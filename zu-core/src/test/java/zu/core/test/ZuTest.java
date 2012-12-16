package zu.core.test;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.TestCase;

import org.apache.zookeeper.server.NIOServerCnxn;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import zu.core.cluster.ZuCluster;
import zu.core.cluster.ZuClusterEventListener;
import zu.core.cluster.util.Util;

import com.twitter.common.zookeeper.ServerSet.EndpointStatus;


public class ZuTest {
  
  static int zkport = 21818;

  static NIOServerCnxn.Factory standaloneServerFactory;
  static File dir = new File("/tmp/zu-core-test");
  
  
  @BeforeClass
  public static void init() throws Exception{
    standaloneServerFactory = Util.startZkServer(zkport, dir);
  }
  
  static void validate(Map<Integer,Set<Integer>> expected, Map<Integer,ArrayList<InetSocketAddress>> view){
    for (Entry<Integer,ArrayList<InetSocketAddress>> entry : view.entrySet()){
      Integer key = entry.getKey();
      ArrayList<InetSocketAddress> list = entry.getValue();
      HashSet<Integer> ports = new HashSet<Integer>();
      for (InetSocketAddress svc : list){
        ports.add(svc.getPort());
      }
      Set<Integer> expectedSet = expected.remove(key);
      TestCase.assertNotNull(expectedSet);
      TestCase.assertEquals(expectedSet, ports);
    }
    TestCase.assertTrue(expected.isEmpty());
  }
  
  @Test
  public void testBasic() throws Exception{
    ZuCluster<MockZuService> mockCluster = new ZuCluster<MockZuService>(new InetSocketAddress(zkport), MockZuService.PartitionReader, "/core/test1");
    
    MockZuService s1 = new MockZuService(new InetSocketAddress(1));
    
    final Map<Integer,Set<Integer>> answer = new HashMap<Integer,Set<Integer>>();
    
    answer.put(0, new HashSet<Integer>(Arrays.asList(1)));
    answer.put(1, new HashSet<Integer>(Arrays.asList(1)));
    
    
    
    final AtomicBoolean flag = new AtomicBoolean(false);
    
    mockCluster.addClusterEventListener(new ZuClusterEventListener() {
      
      @Override
      public void clusterChanged(Map<Integer, ArrayList<InetSocketAddress>> clusterView) {
        validate(answer,clusterView);
        flag.set(true);
      }

      @Override
      public void nodesRemovedFromCluster(List<InetSocketAddress> nodes) {
        
      }
    });

   
    EndpointStatus e1 = mockCluster.join(s1.getAddress());
    
    while(!flag.get()){
      Thread.sleep(10);
    }
    
    mockCluster.leave(e1);
  }
  
  @Test
  public void testAllNodesJoined() throws Exception{
    ZuCluster<MockZuService> mockCluster = new ZuCluster<MockZuService>(new InetSocketAddress(zkport), MockZuService.PartitionReader, "/core/test2");
    
    MockZuService s1 = new MockZuService(new InetSocketAddress(1));
    MockZuService s2 = new MockZuService(new InetSocketAddress(2));
    MockZuService s3 = new MockZuService(new InetSocketAddress(3));
    
    final Map<Integer,Set<Integer>> answer = new HashMap<Integer,Set<Integer>>();
    
    answer.put(0, new HashSet<Integer>(Arrays.asList(1)));
    answer.put(1, new HashSet<Integer>(Arrays.asList(1,2)));
    answer.put(2, new HashSet<Integer>(Arrays.asList(2,3)));
    answer.put(3, new HashSet<Integer>(Arrays.asList(3)));
    
    final AtomicBoolean flag = new AtomicBoolean(false);
    
    mockCluster.addClusterEventListener(new ZuClusterEventListener() {  
      @Override
      public void clusterChanged(Map<Integer, ArrayList<InetSocketAddress>> clusterView) {
        int numPartsJoined = clusterView.size();
        if (numPartsJoined == 4){
          validate(answer,clusterView);
          flag.set(true);
        }
      }

      @Override
      public void nodesRemovedFromCluster(List<InetSocketAddress> nodes) {
        
      }
    });

   
    EndpointStatus e1 = mockCluster.join(s1.getAddress());
    EndpointStatus e2 = mockCluster.join(s2.getAddress());
    EndpointStatus e3 = mockCluster.join(s3.getAddress());
    
    while(!flag.get()){
      Thread.sleep(10);
    }
    
    mockCluster.leave(e1);
    mockCluster.leave(e2);
    mockCluster.leave(e3);
  }
  
  
  @AfterClass
  public static void tearDown(){
    standaloneServerFactory.shutdown();
    Util.rmDir(dir);
  }

}
