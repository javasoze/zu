package zu.core.cluster;

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.twitter.common.zookeeper.ZooKeeperClient;

public class ZuClusterManager implements ClusterManager, Watcher {

  private static final Logger logger = LoggerFactory.getLogger(ZookeeperClientBuilder.class);

  private final ZooKeeperClient zkClient;
  private AtomicReference<Set<String>> clusters;
  private final String clusterPrefix;
  private final Map<String, Cluster> clusterMap = Collections.synchronizedMap(Maps.newHashMap());
  private boolean closeOnShutdown;

  private static String getHostPort(String clusterUrl) {
    URI uri = URI.create(clusterUrl);
    int port = uri.getPort();
    return port == -1 ? uri.getHost() : uri.getHost() + ":" + port;
  }

  /**
   * @param clusterUrl a URL in the form of zk://host:port/path/to/cluster
   */
  public ZuClusterManager(String clusterUrl) {
    this(getHostPort(clusterUrl), URI.create(clusterUrl).getPath());
  }

  public ZuClusterManager(String zkHostPort, String clusterPrefix) {
    this(new ZookeeperClientBuilder().setZookeeperUrl(zkHostPort).build(), clusterPrefix, true);
  }

  public ZuClusterManager(ZooKeeperClient zkClient, String clusterPrefix, boolean closeOnShutdown) {
    this.clusterPrefix = clusterPrefix.startsWith("/") ? clusterPrefix : "/" + clusterPrefix;
    this.zkClient = zkClient;
    clusters = new AtomicReference<>();
    // if prefix does not exist, create it
    try {
      ZooKeeper zk = zkClient.get();
      if (zk.exists(this.clusterPrefix, false) == null) {
        logger.info(this.clusterPrefix + " does not exist, creating a persistent node");
        String[] paths = this.clusterPrefix.split("/");
        StringBuilder buf = new StringBuilder("/");
        for (String path : paths) {
          path = path.trim();
          if (path.length() > 0) {
            buf.append(path);
            zk.create(buf.toString(), null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            buf.append("/");
          }
        }

      }
    } catch(Exception e) {
      logger.error(e == null ? "cannot create prefix path" : e.getMessage());
    }
    zkClient.register(this);
    watchForClusters();
    this.closeOnShutdown = closeOnShutdown;
  }

  @Override
  public String getClusterPrefix() {
    return clusterPrefix;
  }

  @Override
  public Set<String> getAvailableClusters() {
    return clusters.get();
  }

  private void watchForClusters() {
    Set<String> clusterSet = ZookeeperClientBuilder.getAvailableClusters(zkClient, clusterPrefix, true);
    // remove cluster new longer applicable
    Set<String> tobeRemoved = Sets.newHashSet();
    for (String cluster : clusterMap.keySet()) {
      if (!clusterSet.contains(cluster)) {
        clusterSet.add(cluster);
      }
    }

    for(String c : tobeRemoved) {
      Cluster zuCluster = clusterMap.remove(c);
      zuCluster.shutdown();
    }

    for (String cluster : clusterSet) {
      if (!clusterMap.containsKey(cluster)) {
        try {
          ZuCluster zuCluster = new ZuCluster(zkClient, clusterPrefix, cluster, false);
          clusterMap.put(cluster, zuCluster);
        } catch(Exception e) {
          logger.error("problem creating cluster: " + cluster);
        }
      }
    }

    clusters.set(clusterSet);
    logger.info("updated cluster list: " + clusters.get());
  }

  @Override
  public Cluster getCluster(String clusterName) {
    return clusterName != null ? clusterMap.get(clusterName) : null;
  }

  @Override
  public void process(WatchedEvent event) {
    try {
      watchForClusters();
    } catch(Exception e) {
      logger.error(e == null ? "problem watching for cluster changes" : e.getMessage());
    }
  }

  @Override
  public void shutdown() {
    if (zkClient != null) {
      zkClient.unregister(this);
      if (closeOnShutdown) {
        zkClient.close();
      }
    }
  }
}
