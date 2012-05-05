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

package com.sohu.jafka.log;

import static java.lang.String.format;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;

import com.sohu.jafka.api.OffsetRequest;
import com.sohu.jafka.api.PartitionChooser;
import com.sohu.jafka.common.InvalidPartitionException;
import com.sohu.jafka.server.ServerConfig;
import com.sohu.jafka.server.ServerRegister;
import com.sohu.jafka.utils.Closer;
import com.sohu.jafka.utils.IteratorTemplate;
import com.sohu.jafka.utils.KV;
import com.sohu.jafka.utils.Pool;
import com.sohu.jafka.utils.Scheduler;
import com.sohu.jafka.utils.Utils;

/**
 * @author adyliu (imxylz@gmail.com)
 * @since 1.0
 */
public class LogManager implements PartitionChooser, Closeable {

    final ServerConfig config;

    private final Scheduler scheduler;


    final long logCleanupIntervalMs;

    final long logCleanupDefaultAgeMs;

    final boolean needRecovery;

    private final Logger logger = Logger.getLogger(LogManager.class);

    ///////////////////////////////////////////////////////////////////////
    final int numPartitions;

    final File logDir;

    final int flushInterval;

    final Object logCreationLock = new Object();

    final Random random = new Random();

    final CountDownLatch startupLatch;

    //
    private final Pool<String, Pool<Integer, Log>> logs = new Pool<String, Pool<Integer, Log>>();

    private final Scheduler logFlusherScheduler = new Scheduler(1, "jafka-logflusher-", false);

    //
    private final LinkedBlockingQueue<String> topicRegisterTasks = new LinkedBlockingQueue<String>();

    private final AtomicBoolean stopTopicRegisterTasks = new AtomicBoolean(false);

    final Map<String, Integer> logFlushIntervalMap;

    final Map<String, Long> logRetentionMSMap;

    final int logRetentionSize;

    /////////////////////////////////////////////////////////////////////////
    private ServerRegister zookeeper;

    private final Map<String, Integer> topicPartitionsMap;

    private RollingStrategy rollingStategy;

    public LogManager(ServerConfig config, //
            Scheduler scheduler, //
            long logCleanupIntervalMs, //
            long logCleanupDefaultAgeMs, //
            boolean needRecovery) {
        super();
        this.config = config;
        this.scheduler = scheduler;
//        this.time = time;
        this.logCleanupIntervalMs = logCleanupIntervalMs;
        this.logCleanupDefaultAgeMs = logCleanupDefaultAgeMs;
        this.needRecovery = needRecovery;
        //
        this.logDir = Utils.getCanonicalFile(new File(config.getLogDir()));
        this.numPartitions = config.getNumPartitions();
        this.flushInterval = config.getFlushInterval();
        this.topicPartitionsMap = config.getTopicPartitionsMap();
        this.startupLatch = config.getEnableZookeeper() ? new CountDownLatch(1) : null;
        this.logFlushIntervalMap = config.getFlushIntervalMap();
        this.logRetentionSize = config.getLogRetentionSize();
        this.logRetentionMSMap = getLogRetentionMSMap(config.getLogRetentionHoursMap());
        //
    }

    /**
     * @param rollingStategy the rollingStategy to set
     */
    public void setRollingStategy(RollingStrategy rollingStategy) {
        this.rollingStategy = rollingStategy;
    }

    public void load() throws IOException {
        if (this.rollingStategy == null) {
            this.rollingStategy = new FixedSizeRollingStategy(config.getLogFileSize());
        }
        if (!logDir.exists()) {
            logger.info("No log directory found, creating '" + logDir.getAbsolutePath() + "'");
            logDir.mkdirs();
        }
        if (!logDir.isDirectory() || !logDir.canRead()) {
            throw new IllegalArgumentException(logDir.getAbsolutePath() + " is not a readable log directory.");
        }
        File[] subDirs = logDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                if (!dir.isDirectory()) {
                    logger.warn("Skipping unexplainable file '" + dir.getAbsolutePath() + "'--should it be there?");
                } else {
                    logger.info("Loading log from " + dir.getAbsolutePath());
                    Log log = new Log(dir, this.rollingStategy, flushInterval, needRecovery);
                    KV<String, Integer> topicPartion = Utils.getTopicPartition(dir.getName());
                    logs.putIfNotExists(topicPartion.k, new Pool<Integer, Log>());
                    Pool<Integer, Log> parts = logs.get(topicPartion.k);
                    parts.put(topicPartion.v, log);
                }
            }
        }

        /* Schedule the cleanup task to delete old logs */
        if (this.scheduler != null) {
            logger.info("starting log cleaner every " + logCleanupIntervalMs + " ms");
            this.scheduler.scheduleWithRate(new Runnable() {

                public void run() {
                    try {
                        cleanupLogs();
                    } catch (IOException e) {
                        logger.error("cleanup log failed.", e);
                    }
                }

            }, 60 * 1000, logCleanupIntervalMs);
        }
        //
        if (config.getEnableZookeeper()) {
            final ServerRegister zk = new ServerRegister(config, this);
            this.zookeeper = zk;
            zk.startup();
            Utils.newThread("jafka.logmanager", new Runnable() {

                public void run() {
                    while (!stopTopicRegisterTasks.get()) {
                        try {
                            String topic = topicRegisterTasks.take();
                            if (topic.length() == 0) continue;
                            zk.registerTopicInZk(topic);
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                    logger.info("stop registing topic");
                }
            }, true).start();
        }
    }

    private Map<String, Long> getLogRetentionMSMap(Map<String, Integer> logRetentionHourMap) {
        Map<String, Long> ret = new HashMap<String, Long>();
        for (Map.Entry<String, Integer> e : logRetentionHourMap.entrySet()) {
            ret.put(e.getKey(), e.getValue() * 60 * 60 * 1000L);
        }
        return ret;
    }

    public void close() {
        logFlusherScheduler.shutdown();
        Iterator<Log> iter = getLogIterator();
        while (iter.hasNext()) {
            Closer.closeQuietly(iter.next(), logger);
        }
        if (config.getEnableZookeeper()) {
            stopTopicRegisterTasks.set(true);
            //wake up
            //TODO: to be changed
            topicRegisterTasks.add("");
            zookeeper.close();
        }
    }

    /**
     * Runs through the log removing segments older than a certain age
     * 
     * @throws IOException
     */
    private void cleanupLogs() throws IOException {
        logger.trace("Beginning log cleanup...");
        int total = 0;
        Iterator<Log> iter = getLogIterator();
        long startMs = System.currentTimeMillis();
        while (iter.hasNext()) {
            Log log = iter.next();
            total += cleanupExpiredSegments(log) + cleanupSegmentsToMaintainSize(log);
        }
        if (total > 0) {
            logger.warn("Log cleanup completed. " + total + " files deleted in " + (System.currentTimeMillis() - startMs) / 1000 + " seconds");
        } else {
            logger.trace("Log cleanup completed. " + total + " files deleted in " + (System.currentTimeMillis() - startMs) / 1000 + " seconds");
        }
    }

    /**
     * Runs through the log removing segments until the size of the log is at least
     * logRetentionSize bytes in size
     * 
     * @throws IOException
     */
    private int cleanupSegmentsToMaintainSize(final Log log) throws IOException {
        if (logRetentionSize < 0 || log.size() < logRetentionSize) return 0;

        List<LogSegment> toBeDeleted = log.markDeletedWhile(new LogSegmentFilter() {

            long diff = log.size() - logRetentionSize;

            public boolean filter(LogSegment segment) {
                diff -= segment.size();
                return diff >= 0;
            }
        });
        return deleteSegments(log, toBeDeleted);
    }

    private int cleanupExpiredSegments(Log log) throws IOException {
        final long startMs = System.currentTimeMillis();
        String topic = Utils.getTopicPartition(log.dir.getName()).k;
        Long logCleanupThresholdMS = logRetentionMSMap.get(topic);
        if (logCleanupThresholdMS == null) {
            logCleanupThresholdMS = this.logCleanupDefaultAgeMs;
        }
        final long expiredThrshold = logCleanupThresholdMS.longValue();
        List<LogSegment> toBeDeleted = log.markDeletedWhile(new LogSegmentFilter() {

            public boolean filter(LogSegment segment) {
                //check file which has not been modified in expiredThrshold millionseconds
                return startMs - segment.getFile().lastModified() > expiredThrshold;
            }
        });
        return deleteSegments(log, toBeDeleted);
    }

    /**
     * Attemps to delete all provided segments from a log and returns how many it was able to
     */
    private int deleteSegments(Log log, List<LogSegment> segments) {
        int total = 0;
        for (LogSegment segment : segments) {
            boolean deleted = false;
            try {
                try {
                    segment.getMessageSet().close();
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (!segment.getFile().delete()) {
                    deleted = true;
                } else {
                    total += 1;
                }
            } finally {
                logger.warn(String.format("DELETE_LOG[%s] %s => %s", log.name, segment.getFile().getAbsolutePath(),
                        deleted));
            }
        }
        return total;
    }

    /**
     * Register this broker in ZK for the first time.
     */
    public void startup() {
        if (config.getEnableZookeeper()) {
            zookeeper.registerBrokerInZk();
            for (String topic : getAllTopics()) {
                zookeeper.registerTopicInZk(topic);
            }
            startupLatch.countDown();
        }
        logger.info("Starting log flusher every " + config.getFlushSchedulerThreadRate() + " ms with the following overrides " + logFlushIntervalMap);
        logFlusherScheduler.scheduleWithRate(new Runnable() {

            public void run() {
                flushAllLogs();
            }
        }, config.getFlushSchedulerThreadRate(), config.getFlushSchedulerThreadRate());
    }

    private void flushAllLogs() {
        Iterator<Log> iter = getLogIterator();
        while (iter.hasNext()) {
            Log log = iter.next();
            try {
                long timeSinceLastFlush = System.currentTimeMillis() - log.getLastFlushedTime();
                Integer logFlushInterval = logFlushIntervalMap.get(log.getTopicName());
                if (logFlushInterval == null) {
                    logFlushInterval = config.getDefaultFlushIntervalMs();
                }
                final String flushLogFormat = "[%s] flush interval %d, last flushed %d, need flush? %s";
                final boolean needFlush = timeSinceLastFlush >= logFlushInterval.intValue();
                logger.trace(String.format(flushLogFormat, log.getTopicName(), logFlushInterval,
                        log.getLastFlushedTime(), needFlush));
                if (needFlush) {
                    log.flush();
                }
            } catch (IOException ioe) {
                logger.error("Error flushing topic " + log.getTopicName(), ioe);
                logger.fatal("Halting due to unrecoverable I/O error while flushing logs: " + ioe.getMessage(), ioe);
                Runtime.getRuntime().halt(1);
            } catch (Exception e) {
                logger.error("Error flushing topic " + log.getTopicName(), e);
            }
        }
    }

    private Collection<String> getAllTopics() {
        return logs.keySet();
    }

    private Iterator<Log> getLogIterator() {
        return new IteratorTemplate<Log>() {

            final Iterator<Pool<Integer, Log>> iterator = logs.values().iterator();

            Iterator<Log> logIter;

            @Override
            protected Log makeNext() {
                while (true) {
                    if (logIter != null && logIter.hasNext()) {
                        return logIter.next();
                    }
                    if (!iterator.hasNext()) {
                        return allDone();
                    }
                    logIter = iterator.next().values().iterator();
                }
            }
        };
    }

    private void awaitStartup() {
        if (config.getEnableZookeeper()) {
            try {
                startupLatch.await();
            } catch (InterruptedException e) {
                logger.warn(e.getMessage(), e);
            }
        }
    }

    private Pool<Integer, Log> getLogPool(String topic, int partition) {
        awaitStartup();
        if (topic.length() <= 0) {
            throw new IllegalArgumentException("topic name can't be empty");
        }
        //
        Integer definePartition = this.topicPartitionsMap.get(topic);
        if (definePartition == null) {
            definePartition = numPartitions;
        }
        if (partition < 0 || partition >= definePartition.intValue()) {
            String msg = "Wrong partition [%d] for topic [%s], valid partitions(0,%d)";
            msg = format(msg, partition, topic, definePartition.intValue() - 1);
            logger.warn(msg);
            throw new InvalidPartitionException(msg);
        }
        return logs.get(topic);
    }

    /**
     * Get the log if exists or return null
     * 
     * @param topic
     * @param partition
     * @return
     */
    public Log getLog(String topic, int partition) {
        Pool<Integer, Log> p = getLogPool(topic, partition);
        return p == null ? null : p.get(partition);
    }

    /**
     * Create the log if it does not exist, if it exists just return it
     * 
     * @param topic
     * @param partition
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    public Log getOrCreateLog(String topic, int partition) throws InterruptedException, IOException {
        boolean hasNewTopic = false;
        Pool<Integer, Log> parts = getLogPool(topic, partition);
        if (parts == null) {
            Pool<Integer, Log> found = logs.putIfNotExists(topic, new Pool<Integer, Log>());
            if (found == null) {
                hasNewTopic = true;
            }
            parts = logs.get(topic);
        }
        //
        Log log = parts.get(partition);
        if (log == null) {
            log = createLog(topic, partition);
            Log found = parts.putIfNotExists(partition, log);
            if (found != null) {
                Closer.closeQuietly(log, logger);
                log = found;
            } else {
                logger.info(format("Created log for [%s-%d]", topic, partition));
            }
        }
        if (hasNewTopic) {
            registerNewTopicInZK(topic);
        }
        return log;
    }

    private Log createLog(String topic, int partition) throws IOException {
        synchronized (logCreationLock) {
            File d = new File(logDir, topic + "-" + partition);
            d.mkdirs();
            return new Log(d, this.rollingStategy, flushInterval, false);
        }
    }

    private int getPartition(String topic) {
        Integer p = topicPartitionsMap.get(topic);
        return p != null ? p.intValue() : this.numPartitions;
    }

    /**
     * Pick a random partition from the given topic
     */
    public int choosePartition(String topic) {
        return random.nextInt(getPartition(topic));
    }

    /**
     * @param offsetRequest
     * @return
     */
    public List<Long> getOffsets(OffsetRequest offsetRequest) {
        Log log = getLog(offsetRequest.getTopic(), offsetRequest.getPartition());
        if (log != null) {
            return log.getOffsetsBefore(offsetRequest);
        }
        return Log.getEmptyOffsets(offsetRequest);
    }

    private void registerNewTopicInZK(String topic) throws InterruptedException {
        topicRegisterTasks.put(topic);
    }

    /**
     * @return the topicPartitionsMap
     */
    public Map<String, Integer> getTopicPartitionsMap() {
        return topicPartitionsMap;
    }

}
