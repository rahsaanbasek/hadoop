/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.server.datanode.checker;

import static org.apache.hadoop.hdfs.DFSConfigKeys.*;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.server.datanode.StorageLocation;
import org.apache.hadoop.hdfs.server.datanode.StorageLocation.CheckContext;
import org.apache.hadoop.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A utility class that encapsulates checking storage locations during DataNode
 * startup.
 *
 * Some of this code was extracted from the DataNode class.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class StorageLocationChecker {
  public static final Logger LOG = LoggerFactory.getLogger(
      StorageLocationChecker.class);
  private final AsyncChecker<CheckContext, VolumeCheckResult> delegateChecker;
  private final Timer timer;

  /**
   * Max allowed time for a disk check in milliseconds. If the check
   * doesn't complete within this time we declare the disk as dead.
   */
  private final long maxAllowedTimeForCheckMs;


  /**
   * Expected filesystem permissions on the storage directory.
   */
  private final FsPermission expectedPermission;

  /**
   * Maximum number of volume failures that can be tolerated without
   * declaring a fatal error.
   */
  private final int maxVolumeFailuresTolerated;

  public StorageLocationChecker(Configuration conf, Timer timer) {
    maxAllowedTimeForCheckMs = conf.getTimeDuration(
        DFSConfigKeys.DFS_DATANODE_DISK_CHECK_TIMEOUT_KEY,
        DFSConfigKeys.DFS_DATANODE_DISK_CHECK_TIMEOUT_DEFAULT,
        TimeUnit.MILLISECONDS);

    expectedPermission = new FsPermission(
        conf.get(DFS_DATANODE_DATA_DIR_PERMISSION_KEY,
            DFS_DATANODE_DATA_DIR_PERMISSION_DEFAULT));

    maxVolumeFailuresTolerated = conf.getInt(
        DFS_DATANODE_FAILED_VOLUMES_TOLERATED_KEY,
        DFS_DATANODE_FAILED_VOLUMES_TOLERATED_DEFAULT);

    this.timer = timer;

    delegateChecker = new ThrottledAsyncChecker<>(
        timer,
        conf.getTimeDuration(
            DFSConfigKeys.DFS_DATANODE_DISK_CHECK_MIN_GAP_KEY,
            DFSConfigKeys.DFS_DATANODE_DISK_CHECK_MIN_GAP_DEFAULT,
            TimeUnit.MILLISECONDS),
        Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
                .setNameFormat("StorageLocationChecker thread %d")
                .setDaemon(true)
                .build()));
  }

  /**
   * Initiate a check of the supplied storage volumes and return
   * a list of failed volumes.
   *
   * @param conf HDFS configuration.
   * @param dataDirs list of volumes to check.
   * @return returns a list of failed volumes. Returns the empty list if
   *         there are no failed volumes.
   *
   * @throws InterruptedException if the check was interrupted.
   * @throws IOException if the number of failed volumes exceeds the
   *                     maximum allowed or if there are no good
   *                     volumes.
   */
  public List<StorageLocation> check(
      final Configuration conf,
      final Collection<StorageLocation> dataDirs)
      throws InterruptedException, IOException {

    final ArrayList<StorageLocation> goodLocations = new ArrayList<>();
    final Set<StorageLocation> failedLocations = new HashSet<>();
    final Map<StorageLocation, ListenableFuture<VolumeCheckResult>> futures =
        Maps.newHashMap();
    final LocalFileSystem localFS = FileSystem.getLocal(conf);
    final CheckContext context = new CheckContext(localFS, expectedPermission);

    // Start parallel disk check operations on all StorageLocations.
    for (StorageLocation location : dataDirs) {
      futures.put(location,
          delegateChecker.schedule(location, context));
    }

    final long checkStartTimeMs = timer.monotonicNow();

    // Retrieve the results of the disk checks.
    for (Map.Entry<StorageLocation,
             ListenableFuture<VolumeCheckResult>> entry : futures.entrySet()) {

      // Determine how much time we can allow for this check to complete.
      // The cumulative wait time cannot exceed maxAllowedTimeForCheck.
      final long waitSoFarMs = (timer.monotonicNow() - checkStartTimeMs);
      final long timeLeftMs = Math.max(0,
          maxAllowedTimeForCheckMs - waitSoFarMs);
      final StorageLocation location = entry.getKey();

      try {
        final VolumeCheckResult result =
            entry.getValue().get(timeLeftMs, TimeUnit.MILLISECONDS);
        switch (result) {
        case HEALTHY:
          goodLocations.add(entry.getKey());
          break;
        case DEGRADED:
          LOG.warn("StorageLocation {} appears to be degraded.", location);
          break;
        case FAILED:
          LOG.warn("StorageLocation {} detected as failed.", location);
          failedLocations.add(location);
          break;
        default:
          LOG.error("Unexpected health check result {} for StorageLocation {}",
              result, location);
          goodLocations.add(entry.getKey());
        }
      } catch (ExecutionException|TimeoutException e) {
        LOG.warn("Exception checking StorageLocation " + location,
            e.getCause());
        failedLocations.add(location);
      }
    }

    if (failedLocations.size() > maxVolumeFailuresTolerated) {
      throw new IOException(
          "Too many failed volumes: " + failedLocations.size() +
          ". The configuration allows for a maximum of " +
          maxVolumeFailuresTolerated + " failed volumes.");
    }

    if (goodLocations.size() == 0) {
      throw new IOException("All directories in "
          + DFS_DATANODE_DATA_DIR_KEY + " are invalid: "
          + failedLocations);
    }

    return goodLocations;
  }

  public void shutdownAndWait(int gracePeriod, TimeUnit timeUnit) {
    try {
      delegateChecker.shutdownAndWait(gracePeriod, timeUnit);
    } catch (InterruptedException e) {
      LOG.warn("StorageLocationChecker interrupted during shutdown.");
      Thread.currentThread().interrupt();
    }
  }
}
