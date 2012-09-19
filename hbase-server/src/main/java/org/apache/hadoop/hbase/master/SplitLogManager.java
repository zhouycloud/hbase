/**
  * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master;

import static org.apache.hadoop.hbase.master.SplitLogManager.ResubmitDirective.CHECK;
import static org.apache.hadoop.hbase.master.SplitLogManager.ResubmitDirective.FORCE;
import static org.apache.hadoop.hbase.master.SplitLogManager.TerminationStatus.DELETED;
import static org.apache.hadoop.hbase.master.SplitLogManager.TerminationStatus.FAILURE;
import static org.apache.hadoop.hbase.master.SplitLogManager.TerminationStatus.IN_PROGRESS;
import static org.apache.hadoop.hbase.master.SplitLogManager.TerminationStatus.SUCCESS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Chore;
import org.apache.hadoop.hbase.SplitLogCounters;
import org.apache.hadoop.hbase.DeserializationException;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.SplitLogTask;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.master.SplitLogManager.TaskFinisher.Status;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.monitoring.TaskMonitor;
import org.apache.hadoop.hbase.regionserver.SplitLogWorker;
import org.apache.hadoop.hbase.regionserver.wal.HLogSplitter;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.zookeeper.ZKSplitLog;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperListener;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.hadoop.util.StringUtils;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

/**
 * Distributes the task of log splitting to the available region servers.
 * Coordination happens via zookeeper. For every log file that has to be split a
 * znode is created under <code>/hbase/splitlog</code>. SplitLogWorkers race to grab a task.
 *
 * <p>SplitLogManager monitors the task znodes that it creates using the
 * timeoutMonitor thread. If a task's progress is slow then
 * {@link #resubmit(String, Task, ResubmitDirective)} will take away the task from the owner
 * {@link SplitLogWorker} and the task will be up for grabs again. When the task is done then the
 * task's znode is deleted by SplitLogManager.
 *
 * <p>Clients call {@link #splitLogDistributed(Path)} to split a region server's
 * log files. The caller thread waits in this method until all the log files
 * have been split.
 *
 * <p>All the zookeeper calls made by this class are asynchronous. This is mainly
 * to help reduce response time seen by the callers.
 *
 * <p>There is race in this design between the SplitLogManager and the
 * SplitLogWorker. SplitLogManager might re-queue a task that has in reality
 * already been completed by a SplitLogWorker. We rely on the idempotency of
 * the log splitting task for correctness.
 *
 * <p>It is also assumed that every log splitting task is unique and once
 * completed (either with success or with error) it will be not be submitted
 * again. If a task is resubmitted then there is a risk that old "delete task"
 * can delete the re-submission.
 */
@InterfaceAudience.Private
public class SplitLogManager extends ZooKeeperListener {
  private static final Log LOG = LogFactory.getLog(SplitLogManager.class);

  public static final int DEFAULT_TIMEOUT = 25000; // 25 sec
  public static final int DEFAULT_ZK_RETRIES = 3;
  public static final int DEFAULT_MAX_RESUBMIT = 3;
  public static final int DEFAULT_UNASSIGNED_TIMEOUT = (3 * 60 * 1000); //3 min

  private final Stoppable stopper;
  private final ServerName serverName;
  private final TaskFinisher taskFinisher;
  private FileSystem fs;
  private Configuration conf;

  private long zkretries;
  private long resubmit_threshold;
  private long timeout;
  private long unassignedTimeout;
  private long lastNodeCreateTime = Long.MAX_VALUE;
  public boolean ignoreZKDeleteForTesting = false;

  private ConcurrentMap<String, Task> tasks = new ConcurrentHashMap<String, Task>();
  private TimeoutMonitor timeoutMonitor;

  private volatile Set<ServerName> deadWorkers = null;
  private Object deadWorkersLock = new Object();

  /**
   * Wrapper around {@link #SplitLogManager(ZooKeeperWatcher, Configuration,
   * Stoppable, String, TaskFinisher)} that provides a task finisher for
   * copying recovered edits to their final destination. The task finisher
   * has to be robust because it can be arbitrarily restarted or called
   * multiple times.
   * 
   * @param zkw
   * @param conf
   * @param stopper
   * @param serverName
   */
  public SplitLogManager(ZooKeeperWatcher zkw, final Configuration conf,
      Stoppable stopper, ServerName serverName) {
    this(zkw, conf, stopper, serverName, new TaskFinisher() {
      @Override
      public Status finish(ServerName workerName, String logfile) {
        try {
          HLogSplitter.finishSplitLogFile(logfile, conf);
        } catch (IOException e) {
          LOG.warn("Could not finish splitting of log file " + logfile, e);
          return Status.ERR;
        }
        return Status.DONE;
      }
    });
  }

  /**
   * Its OK to construct this object even when region-servers are not online. It
   * does lookup the orphan tasks in zk but it doesn't block waiting for them
   * to be done.
   *
   * @param zkw
   * @param conf
   * @param stopper
   * @param serverName
   * @param tf task finisher 
   */
  public SplitLogManager(ZooKeeperWatcher zkw, Configuration conf,
      Stoppable stopper, ServerName serverName, TaskFinisher tf) {
    super(zkw);
    this.taskFinisher = tf;
    this.conf = conf;
    this.stopper = stopper;
    this.zkretries = conf.getLong("hbase.splitlog.zk.retries", DEFAULT_ZK_RETRIES);
    this.resubmit_threshold = conf.getLong("hbase.splitlog.max.resubmit", DEFAULT_MAX_RESUBMIT);
    this.timeout = conf.getInt("hbase.splitlog.manager.timeout", DEFAULT_TIMEOUT);
    this.unassignedTimeout =
      conf.getInt("hbase.splitlog.manager.unassigned.timeout", DEFAULT_UNASSIGNED_TIMEOUT);
    LOG.debug("timeout = " + timeout);
    LOG.debug("unassigned timeout = " + unassignedTimeout);

    this.serverName = serverName;
    this.timeoutMonitor =
      new TimeoutMonitor(conf.getInt("hbase.splitlog.manager.timeoutmonitor.period", 1000), stopper);
  }

  public void finishInitialization(boolean masterRecovery) {
    if (!masterRecovery) {
      Threads.setDaemonThreadRunning(timeoutMonitor.getThread(), serverName
          + ".splitLogManagerTimeoutMonitor");
    }
    // Watcher can be null during tests with Mock'd servers.
    if (this.watcher != null) {
      this.watcher.registerListener(this);
      lookForOrphans();
    }
  }

  private FileStatus[] getFileList(List<Path> logDirs) throws IOException {
    List<FileStatus> fileStatus = new ArrayList<FileStatus>();
    for (Path hLogDir : logDirs) {
      this.fs = hLogDir.getFileSystem(conf);
      if (!fs.exists(hLogDir)) {
        LOG.warn(hLogDir + " doesn't exist. Nothing to do!");
        continue;
      }
      // TODO filter filenames?
      FileStatus[] logfiles = FSUtils.listStatus(fs, hLogDir, null);
      if (logfiles == null || logfiles.length == 0) {
        LOG.info(hLogDir + " is empty dir, no logs to split");
      } else {
        for (FileStatus status : logfiles)
          fileStatus.add(status);
      }
    }
    FileStatus[] a = new FileStatus[fileStatus.size()];
    return fileStatus.toArray(a);
  }

  /**
   * @param logDir
   *            one region sever hlog dir path in .logs
   * @throws IOException
   *             if there was an error while splitting any log file
   * @return cumulative size of the logfiles split
   * @throws IOException 
   */
  public long splitLogDistributed(final Path logDir) throws IOException {
    List<Path> logDirs = new ArrayList<Path>();
    logDirs.add(logDir);
    return splitLogDistributed(logDirs);
  }
  /**
   * The caller will block until all the log files of the given region server
   * have been processed - successfully split or an error is encountered - by an
   * available worker region server. This method must only be called after the
   * region servers have been brought online.
   *
   * @param logDirs List of log dirs to split
   * @throws IOException If there was an error while splitting any log file
   * @return cumulative size of the logfiles split
   */
  public long splitLogDistributed(final List<Path> logDirs) throws IOException {
    MonitoredTask status = TaskMonitor.get().createStatus(
          "Doing distributed log split in " + logDirs);
    FileStatus[] logfiles = getFileList(logDirs);
    status.setStatus("Checking directory contents...");
    LOG.debug("Scheduling batch of logs to split");
    SplitLogCounters.tot_mgr_log_split_batch_start.incrementAndGet();
    LOG.info("started splitting logs in " + logDirs);
    long t = EnvironmentEdgeManager.currentTimeMillis();
    long totalSize = 0;
    TaskBatch batch = new TaskBatch();
    for (FileStatus lf : logfiles) {
      // TODO If the log file is still being written to - which is most likely
      // the case for the last log file - then its length will show up here
      // as zero. The size of such a file can only be retrieved after
      // recover-lease is done. totalSize will be under in most cases and the
      // metrics that it drives will also be under-reported.
      totalSize += lf.getLen();
      if (enqueueSplitTask(lf.getPath().toString(), batch) == false) {
        throw new IOException("duplicate log split scheduled for " + lf.getPath());
      }
    }
    waitForSplittingCompletion(batch, status);
    if (batch.done != batch.installed) {
      batch.isDead = true;
      SplitLogCounters.tot_mgr_log_split_batch_err.incrementAndGet();
      LOG.warn("error while splitting logs in " + logDirs +
      " installed = " + batch.installed + " but only " + batch.done + " done");
      String msg = "error or interrupted while splitting logs in "
        + logDirs + " Task = " + batch;
      status.abort(msg);
      throw new IOException(msg);
    }
    for(Path logDir: logDirs){
      status.setStatus("Cleaning up log directory...");
      try {
        if (fs.exists(logDir) && !fs.delete(logDir, false)) {
          LOG.warn("Unable to delete log src dir. Ignoring. " + logDir);
        }
      } catch (IOException ioe) {
        FileStatus[] files = fs.listStatus(logDir);
        if (files != null && files.length > 0) {
          LOG.warn("returning success without actually splitting and " + 
              "deleting all the log files in path " + logDir);
        } else {
          LOG.warn("Unable to delete log src dir. Ignoring. " + logDir, ioe);
        }
      }
      SplitLogCounters.tot_mgr_log_split_batch_success.incrementAndGet();
    }
    String msg = "finished splitting (more than or equal to) " + totalSize +
        " bytes in " + batch.installed + " log files in " + logDirs + " in " +
        (EnvironmentEdgeManager.currentTimeMillis() - t) + "ms";
    status.markComplete(msg);
    LOG.info(msg);
    return totalSize;
  }

  /**
   * Add a task entry to splitlog znode if it is not already there.
   * 
   * @param taskname the path of the log to be split
   * @param batch the batch this task belongs to
   * @return true if a new entry is created, false if it is already there.
   */
  boolean enqueueSplitTask(String taskname, TaskBatch batch) {
    SplitLogCounters.tot_mgr_log_split_start.incrementAndGet();
    // This is a znode path under the splitlog dir with the rest of the path made up of an
    // url encoding of the passed in log to split.
    String path = ZKSplitLog.getEncodedNodeName(watcher, taskname);
    Task oldtask = createTaskIfAbsent(path, batch);
    if (oldtask == null) {
      // publish the task in zk
      createNode(path, zkretries);
      return true;
    }
    return false;
  }

  private void waitForSplittingCompletion(TaskBatch batch, MonitoredTask status) {
    synchronized (batch) {
      while ((batch.done + batch.error) != batch.installed) {
        try {
          status.setStatus("Waiting for distributed tasks to finish. "
              + " scheduled=" + batch.installed
              + " done=" + batch.done
              + " error=" + batch.error);
          int remaining = batch.installed - (batch.done + batch.error);
          int actual = activeTasks(batch);
          if (remaining != actual) {
            LOG.warn("Expected " + remaining
              + " active tasks, but actually there are " + actual);
          }
          int remainingInZK = remainingTasksInZK();
          if (remainingInZK >= 0 && actual > remainingInZK) {
            LOG.warn("Expected at least" + actual
              + " tasks in ZK, but actually there are " + remainingInZK);
          }
          if (remainingInZK == 0 || actual == 0) {
            LOG.warn("No more task remaining (ZK or task map), splitting "
              + "should have completed. Remaining tasks in ZK " + remainingInZK
              + ", active tasks in map " + actual);
            return;
          }
          batch.wait(100);
          if (stopper.isStopped()) {
            LOG.warn("Stopped while waiting for log splits to be completed");
            return;
          }
        } catch (InterruptedException e) {
          LOG.warn("Interrupted while waiting for log splits to be completed");
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private int activeTasks(final TaskBatch batch) {
    int count = 0;
    for (Task t: tasks.values()) {
      if (t.batch == batch && t.status == TerminationStatus.IN_PROGRESS) {
        count++;
      }
    }
    return count;
  }

  private int remainingTasksInZK() {
    int count = 0;
    try {
      List<String> tasks =
        ZKUtil.listChildrenNoWatch(watcher, watcher.splitLogZNode);
      if (tasks != null) {
        for (String t: tasks) {
          if (!ZKSplitLog.isRescanNode(watcher, t)) {
            count++;
          }
        }
      }
    } catch (KeeperException ke) {
      LOG.warn("Failed to check remaining tasks", ke);
      count = -1;
    }
    return count;
  }

  private void setDone(String path, TerminationStatus status) {
    Task task = tasks.get(path);
    if (task == null) {
      if (!ZKSplitLog.isRescanNode(watcher, path)) {
        SplitLogCounters.tot_mgr_unacquired_orphan_done.incrementAndGet();
        LOG.debug("unacquired orphan task is done " + path);
      }
    } else {
      synchronized (task) {
        if (task.status == IN_PROGRESS) {
          if (status == SUCCESS) {
            SplitLogCounters.tot_mgr_log_split_success.incrementAndGet();
            LOG.info("Done splitting " + path);
          } else {
            SplitLogCounters.tot_mgr_log_split_err.incrementAndGet();
            LOG.warn("Error splitting " + path);
          }
          task.status = status;
          if (task.batch != null) {
            synchronized (task.batch) {
              if (status == SUCCESS) {
                task.batch.done++;
              } else {
                task.batch.error++;
              }
              task.batch.notify();
            }
          }
        }
      }
    }
    // delete the task node in zk. Keep trying indefinitely - its an async
    // call and no one is blocked waiting for this node to be deleted. All
    // task names are unique (log.<timestamp>) there is no risk of deleting
    // a future task.
    deleteNode(path, Long.MAX_VALUE);
    return;
  }

  private void createNode(String path, Long retry_count) {
    SplitLogTask slt = new SplitLogTask.Unassigned(serverName);
    ZKUtil.asyncCreate(this.watcher, path, slt.toByteArray(), new CreateAsyncCallback(), retry_count);
    SplitLogCounters.tot_mgr_node_create_queued.incrementAndGet();
    return;
  }

  private void createNodeSuccess(String path) {
    lastNodeCreateTime = EnvironmentEdgeManager.currentTimeMillis();
    LOG.debug("put up splitlog task at znode " + path);
    getDataSetWatch(path, zkretries);
  }

  private void createNodeFailure(String path) {
    // TODO the Manager should split the log locally instead of giving up
    LOG.warn("failed to create task node" + path);
    setDone(path, FAILURE);
  }


  private void getDataSetWatch(String path, Long retry_count) {
    this.watcher.getRecoverableZooKeeper().getZooKeeper().
        getData(path, this.watcher,
        new GetDataAsyncCallback(), retry_count);
    SplitLogCounters.tot_mgr_get_data_queued.incrementAndGet();
  }

  private void tryGetDataSetWatch(String path) {
    // A negative retry count will lead to ignoring all error processing.
    this.watcher.getRecoverableZooKeeper().getZooKeeper().
        getData(path, this.watcher,
        new GetDataAsyncCallback(), Long.valueOf(-1) /* retry count */);
    SplitLogCounters.tot_mgr_get_data_queued.incrementAndGet();
  }

  private void getDataSetWatchSuccess(String path, byte[] data, int version)
  throws DeserializationException {
    if (data == null) {
      if (version == Integer.MIN_VALUE) {
        // assume all done. The task znode suddenly disappeared.
        setDone(path, SUCCESS);
        return;
      }
      SplitLogCounters.tot_mgr_null_data.incrementAndGet();
      LOG.fatal("logic error - got null data " + path);
      setDone(path, FAILURE);
      return;
    }
    data = this.watcher.getRecoverableZooKeeper().removeMetaData(data);
    SplitLogTask slt = SplitLogTask.parseFrom(data);
    if (slt.isUnassigned()) {
      LOG.debug("task not yet acquired " + path + " ver = " + version);
      handleUnassignedTask(path);
    } else if (slt.isOwned()) {
      heartbeat(path, version, slt.getServerName());
    } else if (slt.isResigned()) {
      LOG.info("task " + path + " entered state: " + slt.toString());
      resubmitOrFail(path, FORCE);
    } else if (slt.isDone()) {
      LOG.info("task " + path + " entered state: " + slt.toString());
      if (taskFinisher != null && !ZKSplitLog.isRescanNode(watcher, path)) {
        if (taskFinisher.finish(slt.getServerName(), ZKSplitLog.getFileName(path)) == Status.DONE) {
          setDone(path, SUCCESS);
        } else {
          resubmitOrFail(path, CHECK);
        }
      } else {
        setDone(path, SUCCESS);
      }
    } else if (slt.isErr()) {
      LOG.info("task " + path + " entered state: " + slt.toString());
      resubmitOrFail(path, CHECK);
    } else {
      LOG.fatal("logic error - unexpected zk state for path = " + path + " data = " + slt.toString());
      setDone(path, FAILURE);
    }
  }

  private void getDataSetWatchFailure(String path) {
    LOG.warn("failed to set data watch " + path);
    setDone(path, FAILURE);
  }

  /**
   * It is possible for a task to stay in UNASSIGNED state indefinitely - say
   * SplitLogManager wants to resubmit a task. It forces the task to UNASSIGNED
   * state but it dies before it could create the RESCAN task node to signal
   * the SplitLogWorkers to pick up the task. To prevent this scenario the
   * SplitLogManager resubmits all orphan and UNASSIGNED tasks at startup.
   *
   * @param path
   */
  private void handleUnassignedTask(String path) {
    if (ZKSplitLog.isRescanNode(watcher, path)) {
      return;
    }
    Task task = findOrCreateOrphanTask(path);
    if (task.isOrphan() && (task.incarnation == 0)) {
      LOG.info("resubmitting unassigned orphan task " + path);
      // ignore failure to resubmit. The timeout-monitor will handle it later
      // albeit in a more crude fashion
      resubmit(path, task, FORCE);
    }
  }

  private void heartbeat(String path, int new_version, ServerName workerName) {
    Task task = findOrCreateOrphanTask(path);
    if (new_version != task.last_version) {
      if (task.isUnassigned()) {
        LOG.info("task " + path + " acquired by " + workerName);
      }
      task.heartbeat(EnvironmentEdgeManager.currentTimeMillis(), new_version, workerName);
      SplitLogCounters.tot_mgr_heartbeat.incrementAndGet();
    } else {
      // duplicate heartbeats - heartbeats w/o zk node version
      // changing - are possible. The timeout thread does
      // getDataSetWatch() just to check whether a node still
      // exists or not
    }
    return;
  }

  private boolean resubmit(String path, Task task, ResubmitDirective directive) {
    // its ok if this thread misses the update to task.deleted. It will fail later
    if (task.status != IN_PROGRESS) {
      return false;
    }
    int version;
    if (directive != FORCE) {
      if ((EnvironmentEdgeManager.currentTimeMillis() - task.last_update) <
          timeout) {
        return false;
      }
      if (task.unforcedResubmits >= resubmit_threshold) {
        if (!task.resubmitThresholdReached) {
          task.resubmitThresholdReached = true;
          SplitLogCounters.tot_mgr_resubmit_threshold_reached.incrementAndGet();
          LOG.info("Skipping resubmissions of task " + path +
              " because threshold " + resubmit_threshold + " reached");
        }
        return false;
      }
      // race with heartbeat() that might be changing last_version
      version = task.last_version;
    } else {
      version = -1;
    }
    LOG.info("resubmitting task " + path);
    task.incarnation++;
    try {
      // blocking zk call but this is done from the timeout thread
      SplitLogTask slt = new SplitLogTask.Unassigned(this.serverName);
      if (ZKUtil.setData(this.watcher, path, slt.toByteArray(), version) == false) {
        LOG.debug("failed to resubmit task " + path +
            " version changed");
        task.heartbeatNoDetails(EnvironmentEdgeManager.currentTimeMillis());
        return false;
      }
    } catch (NoNodeException e) {
      LOG.warn("failed to resubmit because znode doesn't exist " + path +
          " task done (or forced done by removing the znode)");
      try {
        getDataSetWatchSuccess(path, null, Integer.MIN_VALUE);
      } catch (DeserializationException e1) {
        LOG.debug("Failed to re-resubmit task " + path + " because of deserialization issue", e1);
        task.heartbeatNoDetails(EnvironmentEdgeManager.currentTimeMillis());
        return false;
      }
      return false;
    } catch (KeeperException.BadVersionException e) {
      LOG.debug("failed to resubmit task " + path + " version changed");
      task.heartbeatNoDetails(EnvironmentEdgeManager.currentTimeMillis());
      return false;
    } catch (KeeperException e) {
      SplitLogCounters.tot_mgr_resubmit_failed.incrementAndGet();
      LOG.warn("failed to resubmit " + path, e);
      return false;
    }
    // don't count forced resubmits
    if (directive != FORCE) {
      task.unforcedResubmits++;
    }
    task.setUnassigned();
    createRescanNode(Long.MAX_VALUE);
    SplitLogCounters.tot_mgr_resubmit.incrementAndGet();
    return true;
  }

  private void resubmitOrFail(String path, ResubmitDirective directive) {
    if (resubmit(path, findOrCreateOrphanTask(path), directive) == false) {
      setDone(path, FAILURE);
    }
  }

  private void deleteNode(String path, Long retries) {
    SplitLogCounters.tot_mgr_node_delete_queued.incrementAndGet();
    // Once a task znode is ready for delete, that is it is in the TASK_DONE
    // state, then no one should be writing to it anymore. That is no one
    // will be updating the znode version any more.
    this.watcher.getRecoverableZooKeeper().getZooKeeper().
      delete(path, -1, new DeleteAsyncCallback(),
        retries);
  }

  private void deleteNodeSuccess(String path) {
    if (ignoreZKDeleteForTesting) {
      return;
    }
    Task task;
    task = tasks.remove(path);
    if (task == null) {
      if (ZKSplitLog.isRescanNode(watcher, path)) {
        SplitLogCounters.tot_mgr_rescan_deleted.incrementAndGet();
      }
      SplitLogCounters.tot_mgr_missing_state_in_delete.incrementAndGet();
      LOG.debug("deleted task without in memory state " + path);
      return;
    }
    synchronized (task) {
      task.status = DELETED;
      task.notify();
    }
    SplitLogCounters.tot_mgr_task_deleted.incrementAndGet();
  }

  private void deleteNodeFailure(String path) {
    LOG.fatal("logic failure, failing to delete a node should never happen " +
        "because delete has infinite retries");
    return;
  }

  /**
   * signal the workers that a task was resubmitted by creating the
   * RESCAN node.
   * @throws KeeperException 
   */
  private void createRescanNode(long retries) {
    // The RESCAN node will be deleted almost immediately by the
    // SplitLogManager as soon as it is created because it is being
    // created in the DONE state. This behavior prevents a buildup
    // of RESCAN nodes. But there is also a chance that a SplitLogWorker
    // might miss the watch-trigger that creation of RESCAN node provides.
    // Since the TimeoutMonitor will keep resubmitting UNASSIGNED tasks
    // therefore this behavior is safe.
    SplitLogTask slt = new SplitLogTask.Done(this.serverName);
    this.watcher.getRecoverableZooKeeper().getZooKeeper().
      create(ZKSplitLog.getRescanNode(watcher), slt.toByteArray(),
        Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL,
        new CreateRescanAsyncCallback(), Long.valueOf(retries));
  }

  private void createRescanSuccess(String path) {
    lastNodeCreateTime = EnvironmentEdgeManager.currentTimeMillis();
    SplitLogCounters.tot_mgr_rescan.incrementAndGet();
    getDataSetWatch(path, zkretries);
  }

  private void createRescanFailure() {
    LOG.fatal("logic failure, rescan failure must not happen");
  }

  /**
   * @param path
   * @param batch
   * @return null on success, existing task on error
   */
  private Task createTaskIfAbsent(String path, TaskBatch batch) {
    Task oldtask;
    // batch.installed is only changed via this function and
    // a single thread touches batch.installed.
    Task newtask = new Task();
    newtask.batch = batch;
    oldtask = tasks.putIfAbsent(path, newtask);
    if (oldtask == null) {
      batch.installed++;
      return  null;
    }
    // new task was not used.
    synchronized (oldtask) {
      if (oldtask.isOrphan()) {
        if (oldtask.status == SUCCESS) {
          // The task is already done. Do not install the batch for this
          // task because it might be too late for setDone() to update
          // batch.done. There is no need for the batch creator to wait for
          // this task to complete.
          return (null);
        }
        if (oldtask.status == IN_PROGRESS) {
          oldtask.batch = batch;
          batch.installed++;
          LOG.debug("Previously orphan task " + path + " is now being waited upon");
          return null;
        }
        while (oldtask.status == FAILURE) {
          LOG.debug("wait for status of task " + path + " to change to DELETED");
          SplitLogCounters.tot_mgr_wait_for_zk_delete.incrementAndGet();
          try {
            oldtask.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted when waiting for znode delete callback");
            // fall through to return failure
            break;
          }
        }
        if (oldtask.status != DELETED) {
          LOG.warn("Failure because previously failed task" +
              " state still present. Waiting for znode delete callback" +
              " path=" + path);
          return oldtask;
        }
        // reinsert the newTask and it must succeed this time
        Task t = tasks.putIfAbsent(path, newtask);
        if (t == null) {
          batch.installed++;
          return  null;
        }
        LOG.fatal("Logic error. Deleted task still present in tasks map");
        assert false : "Deleted task still present in tasks map";
        return t;
      }
      LOG.warn("Failure because two threads can't wait for the same task; path=" + path);
      return oldtask;
    }
  }

  Task findOrCreateOrphanTask(String path) {
    Task orphanTask = new Task();
    Task task;
    task = tasks.putIfAbsent(path, orphanTask);
    if (task == null) {
      LOG.info("creating orphan task " + path);
      SplitLogCounters.tot_mgr_orphan_task_acquired.incrementAndGet();
      task = orphanTask;
    }
    return task;
  }

  @Override
  public void nodeDataChanged(String path) {
    Task task;
    task = tasks.get(path);
    if (task != null || ZKSplitLog.isRescanNode(watcher, path)) {
      if (task != null) {
        task.heartbeatNoDetails(EnvironmentEdgeManager.currentTimeMillis());
      }
      getDataSetWatch(path, zkretries);
    }
  }

  public void stop() {
    if (timeoutMonitor != null) {
      timeoutMonitor.interrupt();
    }
  }

  private void lookForOrphans() {
    List<String> orphans;
    try {
       orphans = ZKUtil.listChildrenNoWatch(this.watcher,
          this.watcher.splitLogZNode);
      if (orphans == null) {
        LOG.warn("could not get children of " + this.watcher.splitLogZNode);
        return;
      }
    } catch (KeeperException e) {
      LOG.warn("could not get children of " + this.watcher.splitLogZNode +
          " " + StringUtils.stringifyException(e));
      return;
    }
    int rescan_nodes = 0;
    for (String path : orphans) {
      String nodepath = ZKUtil.joinZNode(watcher.splitLogZNode, path);
      if (ZKSplitLog.isRescanNode(watcher, nodepath)) {
        rescan_nodes++;
        LOG.debug("found orphan rescan node " + path);
      } else {
        LOG.info("found orphan task " + path);
      }
      getDataSetWatch(nodepath, zkretries);
    }
    LOG.info("found " + (orphans.size() - rescan_nodes) + " orphan tasks and " +
        rescan_nodes + " rescan nodes");
  }

  /**
   * Keeps track of the batch of tasks submitted together by a caller in
   * splitLogDistributed(). Clients threads use this object to wait for all
   * their tasks to be done.
   * <p>
   * All access is synchronized.
   */
  static class TaskBatch {
    int installed = 0;
    int done = 0;
    int error = 0;
    volatile boolean isDead = false;

    @Override
    public String toString() {
      return ("installed = " + installed + " done = " + done + " error = " + error);
    }
  }

  /**
   * in memory state of an active task.
   */
  static class Task {
    volatile long last_update;
    volatile int last_version;
    volatile ServerName cur_worker_name;
    TaskBatch batch;
    volatile TerminationStatus status;
    volatile int incarnation;
    volatile int unforcedResubmits;
    volatile boolean resubmitThresholdReached;

    @Override
    public String toString() {
      return ("last_update = " + last_update +
          " last_version = " + last_version +
          " cur_worker_name = " + cur_worker_name +
          " status = " + status +
          " incarnation = " + incarnation +
          " resubmits = " + unforcedResubmits +
          " batch = " + batch);
    }

    Task() {
      incarnation = 0;
      last_version = -1;
      status = IN_PROGRESS;
      setUnassigned();
    }

    public boolean isOrphan() {
      return (batch == null || batch.isDead);
    }

    public boolean isUnassigned() {
      return (cur_worker_name == null);
    }

    public void heartbeatNoDetails(long time) {
      last_update = time;
    }

    public void heartbeat(long time, int version, ServerName worker) {
      last_version = version;
      last_update = time;
      cur_worker_name = worker;
    }

    public void setUnassigned() {
      cur_worker_name = null;
      last_update = -1;
    }
  }

  void handleDeadWorker(ServerName workerName) {
    // resubmit the tasks on the TimeoutMonitor thread. Makes it easier
    // to reason about concurrency. Makes it easier to retry.
    synchronized (deadWorkersLock) {
      if (deadWorkers == null) {
        deadWorkers = new HashSet<ServerName>(100);
      }
      deadWorkers.add(workerName);
    }
    LOG.info("dead splitlog worker " + workerName);
  }

  void handleDeadWorkers(List<ServerName> serverNames) {
    List<ServerName> workerNames = new ArrayList<ServerName>(serverNames.size());
    for (ServerName serverName : serverNames) {
      workerNames.add(serverName);
    }
    synchronized (deadWorkersLock) {
      if (deadWorkers == null) {
        deadWorkers = new HashSet<ServerName>(100);
      }
      deadWorkers.addAll(workerNames);
    }
    LOG.info("dead splitlog workers " + workerNames);
  }

  /**
   * Periodically checks all active tasks and resubmits the ones that have timed
   * out
   */
  private class TimeoutMonitor extends Chore {
    public TimeoutMonitor(final int period, Stoppable stopper) {
      super("SplitLogManager Timeout Monitor", period, stopper);
    }

    @Override
    protected void chore() {
      int resubmitted = 0;
      int unassigned = 0;
      int tot = 0;
      boolean found_assigned_task = false;
      Set<ServerName> localDeadWorkers;

      synchronized (deadWorkersLock) {
        localDeadWorkers = deadWorkers;
        deadWorkers = null;
      }

      for (Map.Entry<String, Task> e : tasks.entrySet()) {
        String path = e.getKey();
        Task task = e.getValue();
        ServerName cur_worker = task.cur_worker_name;
        tot++;
        // don't easily resubmit a task which hasn't been picked up yet. It
        // might be a long while before a SplitLogWorker is free to pick up a
        // task. This is because a SplitLogWorker picks up a task one at a
        // time. If we want progress when there are no region servers then we
        // will have to run a SplitLogWorker thread in the Master.
        if (task.isUnassigned()) {
          unassigned++;
          continue;
        }
        found_assigned_task = true;
        if (localDeadWorkers != null && localDeadWorkers.contains(cur_worker)) {
          SplitLogCounters.tot_mgr_resubmit_dead_server_task.incrementAndGet();
          if (resubmit(path, task, FORCE)) {
            resubmitted++;
          } else {
            handleDeadWorker(cur_worker);
            LOG.warn("Failed to resubmit task " + path + " owned by dead " +
                cur_worker + ", will retry.");
          }
        } else if (resubmit(path, task, CHECK)) {
          resubmitted++;
        }
      }
      if (tot > 0) {
        LOG.debug("total tasks = " + tot + " unassigned = " + unassigned);
      }
      if (resubmitted > 0) {
        LOG.info("resubmitted " + resubmitted + " out of " + tot + " tasks");
      }
      // If there are pending tasks and all of them have been unassigned for
      // some time then put up a RESCAN node to ping the workers.
      // ZKSplitlog.DEFAULT_UNASSIGNED_TIMEOUT is of the order of minutes
      // because a. it is very unlikely that every worker had a
      // transient error when trying to grab the task b. if there are no
      // workers then all tasks wills stay unassigned indefinitely and the
      // manager will be indefinitely creating RESCAN nodes. TODO may be the
      // master should spawn both a manager and a worker thread to guarantee
      // that there is always one worker in the system
      if (tot > 0 && !found_assigned_task &&
          ((EnvironmentEdgeManager.currentTimeMillis() - lastNodeCreateTime) >
          unassignedTimeout)) {
        for (Map.Entry<String, Task> e : tasks.entrySet()) {
          String path = e.getKey();
          Task task = e.getValue();
          // we have to do task.isUnassigned() check again because tasks might
          // have been asynchronously assigned. There is no locking required
          // for these checks ... it is OK even if tryGetDataSetWatch() is
          // called unnecessarily for a task
          if (task.isUnassigned() && (task.status != FAILURE)) {
            // We just touch the znode to make sure its still there
            tryGetDataSetWatch(path);
          }
        }
        createRescanNode(Long.MAX_VALUE);
        SplitLogCounters.tot_mgr_resubmit_unassigned.incrementAndGet();
        LOG.debug("resubmitting unassigned task(s) after timeout");
      }
    }
  }

  /**
   * Asynchronous handler for zk create node results.
   * Retries on failures.
   */
  class CreateAsyncCallback implements AsyncCallback.StringCallback {
    private final Log LOG = LogFactory.getLog(CreateAsyncCallback.class);

    @Override
    public void processResult(int rc, String path, Object ctx, String name) {
      SplitLogCounters.tot_mgr_node_create_result.incrementAndGet();
      if (rc != 0) {
        if (rc == KeeperException.Code.NODEEXISTS.intValue()) {
          // What if there is a delete pending against this pre-existing
          // znode? Then this soon-to-be-deleted task znode must be in TASK_DONE
          // state. Only operations that will be carried out on this node by
          // this manager are get-znode-data, task-finisher and delete-znode.
          // And all code pieces correctly handle the case of suddenly
          // disappearing task-znode.
          LOG.debug("found pre-existing znode " + path);
          SplitLogCounters.tot_mgr_node_already_exists.incrementAndGet();
        } else {
          Long retry_count = (Long)ctx;
          LOG.warn("create rc =" + KeeperException.Code.get(rc) + " for " +
              path + " remaining retries=" + retry_count);
          if (retry_count == 0) {
            SplitLogCounters.tot_mgr_node_create_err.incrementAndGet();
            createNodeFailure(path);
          } else {
            SplitLogCounters.tot_mgr_node_create_retry.incrementAndGet();
            createNode(path, retry_count - 1);
          }
          return;
        }
      }
      createNodeSuccess(path);
    }
  }

  /**
   * Asynchronous handler for zk get-data-set-watch on node results.
   * Retries on failures.
   */
  class GetDataAsyncCallback implements AsyncCallback.DataCallback {
    private final Log LOG = LogFactory.getLog(GetDataAsyncCallback.class);

    @Override
    public void processResult(int rc, String path, Object ctx, byte[] data,
        Stat stat) {
      SplitLogCounters.tot_mgr_get_data_result.incrementAndGet();
      if (rc != 0) {
        if (rc == KeeperException.Code.SESSIONEXPIRED.intValue()) {
          LOG.error("ZK session expired. Master is expected to shut down. Abandoning retries.");
          return;
        }
        if (rc == KeeperException.Code.NONODE.intValue()) {
          SplitLogCounters.tot_mgr_get_data_nonode.incrementAndGet();
          // The task znode has been deleted. Must be some pending delete
          // that deleted the task. Assume success because a task-znode is
          // is only deleted after TaskFinisher is successful.
          LOG.warn("task znode " + path + " vanished.");
          try {
            getDataSetWatchSuccess(path, null, Integer.MIN_VALUE);
          } catch (DeserializationException e) {
            LOG.warn("Deserialization problem", e);
          }
          return;
        }
        Long retry_count = (Long) ctx;

        if (retry_count < 0) {
          LOG.warn("getdata rc = " + KeeperException.Code.get(rc) + " " +
              path + ". Ignoring error. No error handling. No retrying.");
          return;
        }
        LOG.warn("getdata rc = " + KeeperException.Code.get(rc) + " " +
            path + " remaining retries=" + retry_count);
        if (retry_count == 0) {
          SplitLogCounters.tot_mgr_get_data_err.incrementAndGet();
          getDataSetWatchFailure(path);
        } else {
          SplitLogCounters.tot_mgr_get_data_retry.incrementAndGet();
          getDataSetWatch(path, retry_count - 1);
        }
        return;
      }
      try {
        getDataSetWatchSuccess(path, data, stat.getVersion());
      } catch (DeserializationException e) {
        LOG.warn("Deserialization problem", e);
      }
      return;
    }
  }

  /**
   * Asynchronous handler for zk delete node results.
   * Retries on failures.
   */
  class DeleteAsyncCallback implements AsyncCallback.VoidCallback {
    private final Log LOG = LogFactory.getLog(DeleteAsyncCallback.class);

    @Override
    public void processResult(int rc, String path, Object ctx) {
      SplitLogCounters.tot_mgr_node_delete_result.incrementAndGet();
      if (rc != 0) {
        if (rc != KeeperException.Code.NONODE.intValue()) {
          SplitLogCounters.tot_mgr_node_delete_err.incrementAndGet();
          Long retry_count = (Long) ctx;
          LOG.warn("delete rc=" + KeeperException.Code.get(rc) + " for " +
              path + " remaining retries=" + retry_count);
          if (retry_count == 0) {
            LOG.warn("delete failed " + path);
            deleteNodeFailure(path);
          } else {
            deleteNode(path, retry_count - 1);
          }
          return;
        } else {
        LOG.debug(path +
            " does not exist. Either was created but deleted behind our" +
            " back by another pending delete OR was deleted" +
            " in earlier retry rounds. zkretries = " + (Long) ctx);
        }
      } else {
        LOG.debug("deleted " + path);
      }
      deleteNodeSuccess(path);
    }
  }

  /**
   * Asynchronous handler for zk create RESCAN-node results.
   * Retries on failures.
   * <p>
   * A RESCAN node is created using PERSISTENT_SEQUENTIAL flag. It is a signal
   * for all the {@link SplitLogWorker}s to rescan for new tasks.
   */
  class CreateRescanAsyncCallback implements AsyncCallback.StringCallback {
    private final Log LOG = LogFactory.getLog(CreateRescanAsyncCallback.class);

    @Override
    public void processResult(int rc, String path, Object ctx, String name) {
      if (rc != 0) {
        if (rc == KeeperException.Code.SESSIONEXPIRED.intValue()) {
          LOG.error("ZK session expired. Master is expected to shut down. Abandoning retries.");
          return;
        }
        Long retry_count = (Long)ctx;
        LOG.warn("rc=" + KeeperException.Code.get(rc) + " for "+ path +
            " remaining retries=" + retry_count);
        if (retry_count == 0) {
          createRescanFailure();
        } else {
          createRescanNode(retry_count - 1);
        }
        return;
      }
      // path is the original arg, name is the actual name that was created
      createRescanSuccess(name);
    }
  }

  /**
   * {@link SplitLogManager} can use objects implementing this interface to
   * finish off a partially done task by {@link SplitLogWorker}. This provides
   * a serialization point at the end of the task processing. Must be
   * restartable and idempotent.
   */
  static public interface TaskFinisher {
    /**
     * status that can be returned finish()
     */
    static public enum Status {
      /**
       * task completed successfully
       */
      DONE(),
      /**
       * task completed with error
       */
      ERR();
    }
    /**
     * finish the partially done task. workername provides clue to where the
     * partial results of the partially done tasks are present. taskname is the
     * name of the task that was put up in zookeeper.
     * <p>
     * @param workerName
     * @param taskname
     * @return DONE if task completed successfully, ERR otherwise
     */
    public Status finish(ServerName workerName, String taskname);
  }

  enum ResubmitDirective {
    CHECK(),
    FORCE();
  }

  enum TerminationStatus {
    IN_PROGRESS("in_progress"),
    SUCCESS("success"),
    FAILURE("failure"),
    DELETED("deleted");

    String statusMsg;
    TerminationStatus(String msg) {
      statusMsg = msg;
    }
    
    @Override
    public String toString() {
      return statusMsg;
    }
  }
  
  /**
   * Completes the initialization
   */
  public void finishInitialization() {
    finishInitialization(false);
  }
  
}