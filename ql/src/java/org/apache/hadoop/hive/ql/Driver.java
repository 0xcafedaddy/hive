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

package org.apache.hadoop.hive.ql;

import java.io.DataInput;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;

import org.apache.hadoop.hive.ql.parse.ASTNode;

import org.apache.commons.lang.StringUtils;

import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.parse.ParseDriver;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.parse.ParseException;
import org.apache.hadoop.hive.ql.parse.BaseSemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.SemanticAnalyzerFactory;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.FetchTask;
import org.apache.hadoop.hive.ql.exec.TaskFactory;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.history.HiveHistory;
import org.apache.hadoop.hive.ql.history.HiveHistory.Keys;
import org.apache.hadoop.hive.ql.plan.tableDesc;
import org.apache.hadoop.hive.serde2.ByteStream;
import org.apache.hadoop.hive.conf.HiveConf;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class Driver implements CommandProcessor {

  static final private Log LOG = LogFactory.getLog("hive.ql.Driver");
  private int maxRows = 100;
  ByteStream.Output bos = new ByteStream.Output();

  private ParseDriver pd;
  private HiveConf conf;
  private DataInput resStream;
  private LogHelper console;
  private Context ctx;
  private BaseSemanticAnalyzer sem;

  public int countJobs(List<Task<? extends Serializable>> tasks) {
    if (tasks == null)
      return 0;
    int jobs = 0;
    for (Task<? extends Serializable> task : tasks) {
      if (task.isMapRedTask()) {
        jobs++;
      }
      jobs += countJobs(task.getChildTasks());
    }
    return jobs;
  }

  /**
   * Return the Thrift DDL string of the result
   */
  public String getSchema() throws Exception {
    if (sem != null && sem.getFetchTask() != null) {
      if (!sem.getFetchTaskInit()) {
        sem.setFetchTaskInit(true);
        sem.getFetchTask().initialize(conf);
      }
      FetchTask ft = (FetchTask) sem.getFetchTask();

      tableDesc td = ft.getTblDesc();
      String tableName = "result";
      List<FieldSchema> lst = MetaStoreUtils.getFieldsFromDeserializer(
          tableName, td.getDeserializer());
      String schema = MetaStoreUtils.getDDLFromFieldSchema(tableName, lst);
      return schema;
    }
    return null;
  }

  /**
   * Return the maximum number of rows returned by getResults
   */
  public int getMaxRows() {
    return maxRows;
  }

  /**
   * Set the maximum number of rows returned by getResults
   */
  public void setMaxRows(int maxRows) {
    this.maxRows = maxRows;
  }

  public boolean hasReduceTasks(List<Task<? extends Serializable>> tasks) {
    if (tasks == null)
      return false;

    boolean hasReduce = false;
    for (Task<? extends Serializable> task : tasks) {
      if (task.hasReduce()) {
        return true;
      }

      hasReduce = (hasReduce || hasReduceTasks(task.getChildTasks()));
    }
    return hasReduce;
  }

  /**
   * for backwards compatibility with current tests
   */
  public Driver(HiveConf conf) {
    console = new LogHelper(LOG);
    this.conf = conf;
    ctx = new Context(conf);
  }

  public Driver() {
    console = new LogHelper(LOG);
    if (SessionState.get() != null) {
      conf = SessionState.get().getConf();
      ctx = new Context(conf);
    }
  }

  private  String makeQueryId() {
    GregorianCalendar gc = new GregorianCalendar();
    String userid = System.getProperty("user.name");

    return userid + "_" +
      String.format("%1$4d%2$02d%3$02d%4$02d%5$02d%5$02d", gc.get(Calendar.YEAR),
                    gc.get(Calendar.MONTH) + 1,
                    gc.get(Calendar.DAY_OF_MONTH),
                    gc.get(Calendar.HOUR_OF_DAY),
                    gc.get(Calendar.MINUTE), gc.get(Calendar.SECOND));
  }

  
  public int run(String command) {

    boolean noName = StringUtils.isEmpty(conf
        .getVar(HiveConf.ConfVars.HADOOPJOBNAME));
    int maxlen = conf.getIntVar(HiveConf.ConfVars.HIVEJOBNAMELENGTH);
    int jobs = 0;

    conf.setVar(HiveConf.ConfVars.HIVEQUERYSTRING, command);
    
    String queryId = makeQueryId();
    conf.setVar(HiveConf.ConfVars.HIVEQUERYID, queryId);

    try {

      TaskFactory.resetId();
      LOG.info("Starting command: " + command);

      ctx.clear();
      ctx.makeScratchDir();

      if (SessionState.get() != null)
        SessionState.get().getHiveHistory().startQuery(command, conf.getVar(HiveConf.ConfVars.HIVEQUERYID) );

      resStream = null;

      pd = new ParseDriver();
      ASTNode tree = pd.parse(command);

      while ((tree.getToken() == null) && (tree.getChildCount() > 0)) {
        tree = (ASTNode) tree.getChild(0);
      }

      sem = SemanticAnalyzerFactory.get(conf, tree);

      // Do semantic analysis and plan generation
      sem.analyze(tree, ctx);
      LOG.info("Semantic Analysis Completed");

      jobs = countJobs(sem.getRootTasks());
      if (jobs > 0) {
        console.printInfo("Total MapReduce jobs = " + jobs);
      }
      if (SessionState.get() != null)
        SessionState.get().getHiveHistory().setQueryProperty(queryId,
            Keys.QUERY_NUM_TASKS, String.valueOf(jobs));

      String jobname = Utilities.abbreviate(command, maxlen - 6);
      int curJob = 0;
      for (Task<? extends Serializable> rootTask : sem.getRootTasks()) {
        // assumption that only top level tasks are map-reduce tasks
        if (rootTask.isMapRedTask()) {
          curJob++;
          if (noName) {
            conf.setVar(HiveConf.ConfVars.HADOOPJOBNAME, jobname + "(" + curJob
                + "/" + jobs + ")");
          }
        }
        rootTask.initialize(conf);
      }

      // A very simple runtime that keeps putting runnable takss
      // on a list and when a job completes, it puts the children at the back of
      // the list
      // while taking the job to run from the front of the list
      Queue<Task<? extends Serializable>> runnable = new LinkedList<Task<? extends Serializable>>();

      for (Task<? extends Serializable> rootTask : sem.getRootTasks()) {
        if (runnable.offer(rootTask) == false) {
          LOG.error("Could not insert the first task into the queue");
          return (1);
        }
      }

      while (runnable.peek() != null) {
        Task<? extends Serializable> tsk = runnable.remove();

        if (SessionState.get() != null)
          SessionState.get().getHiveHistory().startTask(queryId, tsk,
              tsk.getClass().getName());

        int exitVal = tsk.execute();
        if (SessionState.get() != null) {
          SessionState.get().getHiveHistory().setTaskProperty(queryId,
              tsk.getId(), Keys.TASK_RET_CODE, String.valueOf(exitVal));
          SessionState.get().getHiveHistory().endTask(queryId, tsk);
        }
        if (exitVal != 0) {
          console.printError("FAILED: Execution Error, return code " + exitVal
              + " from " + tsk.getClass().getName());
          return 9;
        }
        tsk.setDone();

        if (tsk.getChildTasks() == null) {
          continue;
        }

        for (Task<? extends Serializable> child : tsk.getChildTasks()) {
          // Check if the child is runnable
          if (!child.isRunnable()) {
            continue;
          }

          if (runnable.offer(child) == false) {
            LOG.error("Could not add child task to queue");
          }
        }
      }
      if (SessionState.get() != null)
        SessionState.get().getHiveHistory().setQueryProperty(queryId,
            Keys.QUERY_RET_CODE, String.valueOf(0));
    } catch (SemanticException e) {
      if (SessionState.get() != null)
        SessionState.get().getHiveHistory().setQueryProperty(queryId,
            Keys.QUERY_RET_CODE, String.valueOf(10));
      console.printError("FAILED: Error in semantic analysis: "
          + e.getMessage(), "\n"
          + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return (10);
    } catch (ParseException e) {
      if (SessionState.get() != null)
        SessionState.get().getHiveHistory().setQueryProperty(queryId,
            Keys.QUERY_RET_CODE, String.valueOf(11));
      console.printError("FAILED: Parse Error: " + e.getMessage(), "\n"
          + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return (11);
    } catch (Exception e) {
      if (SessionState.get() != null)
        SessionState.get().getHiveHistory().setQueryProperty(queryId,
            Keys.QUERY_RET_CODE, String.valueOf(12));
      // Has to use full name to make sure it does not conflict with
      // org.apache.commons.lang.StringUtils
      console.printError("FAILED: Unknown exception : " + e.getMessage(), "\n"
          + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return (12);
    } finally {
      if (SessionState.get() != null)
        SessionState.get().getHiveHistory().endQuery(queryId);
      if (noName) {
        conf.setVar(HiveConf.ConfVars.HADOOPJOBNAME, "");
      }
    }

    console.printInfo("OK");
    return (0);
  }

  public boolean getResults(Vector<String> res) {
    if (sem != null && sem.getFetchTask() != null) {
      if (!sem.getFetchTaskInit()) {
        sem.setFetchTaskInit(true);
        sem.getFetchTask().initialize(conf);
      }
      FetchTask ft = (FetchTask) sem.getFetchTask();
      ft.setMaxRows(maxRows);
      return ft.fetch(res);
    }

    if (resStream == null)
      resStream = ctx.getStream();
    if (resStream == null)
      return false;

    int numRows = 0;
    String row = null;

    while (numRows < maxRows) {
      if (resStream == null) {
        if (numRows > 0)
          return true;
        else
          return false;
      }

      bos.reset();
      Utilities.streamStatus ss;
      try {
        ss = Utilities.readColumn(resStream, bos);
        if (bos.getCount() > 0)
          row = new String(bos.getData(), 0, bos.getCount(), "UTF-8");
        else if (ss == Utilities.streamStatus.TERMINATED)
          row = new String();

        if (row != null) {
          numRows++;
          res.add(row);
        }
      } catch (IOException e) {
        console.printError("FAILED: Unexpected IO exception : "
            + e.getMessage());
        res = null;
        return false;
      }

      if (ss == Utilities.streamStatus.EOF)
        resStream = ctx.getStream();
    }
    return true;
  }

  public int close() {
    try {
      // Delete the scratch directory from the context
      ctx.removeScratchDir();
      ctx.clear();
    } catch (Exception e) {
      console.printError("FAILED: Unknown exception : " + e.getMessage(), "\n"
          + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return (13);
    }

    return (0);
  }
}
