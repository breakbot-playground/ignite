/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.task;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.events.*;
import org.apache.ignite.fs.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.marshaller.*;
import org.apache.ignite.resources.*;
import org.gridgain.grid.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.managers.deployment.*;
import org.gridgain.grid.kernal.processors.job.*;
import org.gridgain.grid.kernal.processors.timeout.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.worker.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.compute.ComputeJobResultPolicy.*;
import static org.apache.ignite.events.IgniteEventType.*;
import static org.gridgain.grid.kernal.GridTopic.*;
import static org.gridgain.grid.kernal.managers.communication.GridIoPolicy.*;
import static org.gridgain.grid.kernal.processors.task.GridTaskThreadContextKey.*;

/**
 * Grid task worker. Handles full task life cycle.
 * @param <T> Task argument type.
 * @param <R> Task return value type.
 */
class GridTaskWorker<T, R> extends GridWorker implements GridTimeoutObject {
    /** Split size threshold. */
    private static final int SPLIT_WARN_THRESHOLD = 1000;

    /** {@code True} for internal tasks. */
    private boolean internal;

    /** */
    private enum State {
        /** */
        WAITING,

        /** */
        REDUCING,

        /** */
        REDUCED,

        /** */
        FINISHING
    }

    /** Static logger to avoid re-creation. */
    private static final AtomicReference<IgniteLogger> logRef = new AtomicReference<>();

    /** */
    private final GridKernalContext ctx;

    /** */
    private final IgniteLogger log;

    /** */
    private final IgniteMarshaller marsh;

    /** */
    private final GridTaskSessionImpl ses;

    /** */
    private final GridTaskFutureImpl<R> fut;

    /** */
    private final T arg;

    /** */
    private final GridTaskEventListener evtLsnr;

    /** */
    private Map<IgniteUuid, GridJobResultImpl> jobRes;

    /** */
    private State state = State.WAITING;

    /** */
    private final GridDeployment dep;

    /** Task class. */
    private final Class<?> taskCls;

    /** Optional subgrid. */
    private final Map<GridTaskThreadContextKey, Object> thCtx;

    /** */
    private ComputeTask<T, R> task;

    /** */
    private final Queue<GridJobExecuteResponse> delayedRess = new ConcurrentLinkedDeque8<>();

    /** */
    private boolean continuous;

    /** */
    private final Object mux = new Object();

    /** */
    private boolean lockRespProc = true;

    /** */
    private final boolean resCache;

    /** */
    private final boolean noFailover;

    /** */
    private final UUID subjId;

    /** Continuous mapper. */
    private final ComputeTaskContinuousMapper mapper = new ComputeTaskContinuousMapper() {
        /** {@inheritDoc} */
        @Override public void send(ComputeJob job, ClusterNode node) throws GridException {
            A.notNull(job, "job");
            A.notNull(node, "node");

            processMappedJobs(Collections.singletonMap(job, node));
        }

        /** {@inheritDoc} */
        @Override public void send(Map<? extends ComputeJob, ClusterNode> mappedJobs) throws GridException {
            A.notNull(mappedJobs, "mappedJobs");

            processMappedJobs(mappedJobs);
        }

        /** {@inheritDoc} */
        @Override public void send(ComputeJob job) throws GridException {
            A.notNull(job, "job");

            send(Collections.singleton(job));
        }

        /** {@inheritDoc} */
        @Override public void send(Collection<? extends ComputeJob> jobs) throws GridException {
            A.notNull(jobs, "jobs");

            if (jobs.isEmpty())
                throw new GridException("Empty jobs collection passed to send(...) method.");

            ComputeLoadBalancer balancer = ctx.loadBalancing().getLoadBalancer(ses, getTaskTopology());

            for (ComputeJob job : jobs) {
                if (job == null)
                    throw new GridException("Null job passed to send(...) method.");

                processMappedJobs(Collections.singletonMap(job, balancer.getBalancedNode(job, null)));
            }
        }
    };

    /**
     * @param ctx Kernal context.
     * @param arg Task argument.
     * @param ses Grid task session.
     * @param fut Task future.
     * @param taskCls Task class.
     * @param task Task instance that might be null.
     * @param dep Deployed task.
     * @param evtLsnr Event listener.
     * @param thCtx Thread-local context from task processor.
     * @param subjId Subject ID.
     */
    GridTaskWorker(
        GridKernalContext ctx,
        @Nullable T arg,
        GridTaskSessionImpl ses,
        GridTaskFutureImpl<R> fut,
        @Nullable Class<?> taskCls,
        @Nullable ComputeTask<T, R> task,
        GridDeployment dep,
        GridTaskEventListener evtLsnr,
        @Nullable Map<GridTaskThreadContextKey, Object> thCtx,
        UUID subjId) {
        super(ctx.config().getGridName(), "grid-task-worker", ctx.config().getGridLogger());

        assert ses != null;
        assert fut != null;
        assert evtLsnr != null;
        assert dep != null;

        this.arg = arg;
        this.ctx = ctx;
        this.fut = fut;
        this.ses = ses;
        this.taskCls = taskCls;
        this.task = task;
        this.dep = dep;
        this.evtLsnr = evtLsnr;
        this.thCtx = thCtx;
        this.subjId = subjId;

        log = U.logger(ctx, logRef, this);

        marsh = ctx.config().getMarshaller();

        resCache = dep.annotation(taskCls, ComputeTaskNoResultCache.class) == null;

        Boolean noFailover = getThreadContext(TC_NO_FAILOVER);

        this.noFailover = noFailover != null ? noFailover : false;
    }

    /**
     * Gets value from thread-local context.
     *
     * @param key Thread-local context key.
     * @return Thread-local context value, if any.
     */
    @SuppressWarnings({"unchecked"})
    @Nullable private <V> V getThreadContext(GridTaskThreadContextKey key) {
        return thCtx == null ? null : (V)thCtx.get(key);
    }

    /**
     * @return Task session ID.
     */
    IgniteUuid getTaskSessionId() {
        return ses.getId();
    }

    /**
     * @return Task session.
     */
    GridTaskSessionImpl getSession() {
        return ses;
    }

    /**
     * @return Task future.
     */
    GridTaskFutureImpl<R> getTaskFuture() {
        return fut;
    }

    /**
     * Gets property dep.
     *
     * @return Property dep.
     */
    GridDeployment getDeployment() {
        return dep;
    }

    /**
     * @return Grid task.
     */
    public ComputeTask<T, R> getTask() {
        return task;
    }

    /**
     * @param task Deployed task.
     */
    public void setTask(ComputeTask<T, R> task) {
        this.task = task;
    }

    /**
     * @return {@code True} if task is internal.
     */
    public boolean isInternal() {
        return internal;
    }

    /** {@inheritDoc} */
    @Override public IgniteUuid timeoutId() {
        return ses.getId();
    }

    /** {@inheritDoc} */
    @Override public void onTimeout() {
        synchronized (mux) {
            if (state != State.WAITING)
                return;
        }

        U.warn(log, "Task has timed out: " + ses);

        recordTaskEvent(EVT_TASK_TIMEDOUT, "Task has timed out.");

        Throwable e = new ComputeTaskTimeoutException("Task timed out (check logs for error messages): " + ses);

        finishTask(null, e);
    }

    /** {@inheritDoc} */
    @Override public long endTime() {
        return ses.getEndTime();
    }

    /**
     * @param taskCls Task class.
     * @return Task instance.
     * @throws GridException Thrown in case of any instantiation error.
     */
    private ComputeTask<T, R> newTask(Class<? extends ComputeTask<T, R>> taskCls) throws GridException {
        ComputeTask<T, R> task = dep.newInstance(taskCls);

        if (task == null)
            throw new GridException("Failed to instantiate task (is default constructor available?): " + taskCls);

        return task;
    }

    /**
     *
     */
    private void initializeSpis() {
        ComputeTaskSpis spis = dep.annotation(taskCls, ComputeTaskSpis.class);

        if (spis != null) {
            ses.setLoadBalancingSpi(spis.loadBalancingSpi());
            ses.setFailoverSpi(spis.failoverSpi());
            ses.setCheckpointSpi(spis.checkpointSpi());
        }
    }

    /**
     * Maps this task's jobs to nodes and sends them out.
     */
    @SuppressWarnings({"unchecked"})
    @Override protected void body() {
        evtLsnr.onTaskStarted(this);

        try {
            // Use either user task or deployed one.
            if (task == null) {
                assert taskCls != null;
                assert ComputeTask.class.isAssignableFrom(taskCls);

                try {
                    task = newTask((Class<? extends ComputeTask<T, R>>)taskCls);
                }
                catch (GridException e) {
                    // If cannot instantiate task, then assign internal flag based
                    // on information available.
                    internal = dep.internalTask(null, taskCls);

                    recordTaskEvent(EVT_TASK_STARTED, "Task started.");

                    throw e;
                }
            }

            internal = dep.internalTask(task, taskCls);

            recordTaskEvent(EVT_TASK_STARTED, "Task started.");

            initializeSpis();

            ses.setClassLoader(dep.classLoader());

            final List<ClusterNode> shuffledNodes = getTaskTopology();

            // Load balancer.
            ComputeLoadBalancer balancer = ctx.loadBalancing().getLoadBalancer(ses, shuffledNodes);

            continuous = ctx.resource().isAnnotationPresent(dep, task, IgniteTaskContinuousMapperResource.class);

            if (log.isDebugEnabled())
                log.debug("Injected task resources [continuous=" + continuous + ']');

            // Inject resources.
            ctx.resource().inject(dep, task, ses, balancer, mapper);

            Map<? extends ComputeJob, ClusterNode> mappedJobs = U.wrapThreadLoader(dep.classLoader(),
                new Callable<Map<? extends ComputeJob, ClusterNode>>() {
                    @Override public Map<? extends ComputeJob, ClusterNode> call() throws GridException {
                        return task.map(shuffledNodes, arg);
                    }
                });

            if (log.isDebugEnabled())
                log.debug("Mapped task jobs to nodes [jobCnt=" + (mappedJobs != null ? mappedJobs.size() : 0) +
                    ", mappedJobs=" + mappedJobs + ", ses=" + ses + ']');

            if (F.isEmpty(mappedJobs)) {
                synchronized (mux) {
                    // Check if some jobs are sent from continuous mapper.
                    if (F.isEmpty(jobRes))
                        throw new GridException("Task map operation produced no mapped jobs: " + ses);
                }
            }
            else
                processMappedJobs(mappedJobs);

            synchronized (mux) {
                lockRespProc = false;
            }

            processDelayedResponses();
        }
        catch (ClusterGroupEmptyException e) {
            U.warn(log, "Failed to map task jobs to nodes (topology projection is empty): " + ses);

            finishTask(null, e);
        }
        catch (GridException e) {
            if (!fut.isCancelled()) {
                U.error(log, "Failed to map task jobs to nodes: " + ses, e);

                finishTask(null, e);
            }
            else if (log.isDebugEnabled())
                log.debug("Failed to map task jobs to nodes due to task cancellation: " + ses);
        }
        // Catch throwable to protect against bad user code.
        catch (Throwable e) {
            String errMsg = "Failed to map task jobs to nodes due to undeclared user exception" +
                " [cause=" + e.getMessage() + ", ses=" + ses + "]";

            U.error(log, errMsg, e);

            finishTask(null, new ComputeUserUndeclaredException(errMsg, e));
        }
    }

    /**
     * @param jobs Map of jobs.
     * @throws GridException Thrown in case of any error.
     */
    private void processMappedJobs(Map<? extends ComputeJob, ClusterNode> jobs) throws GridException {
        if (F.isEmpty(jobs))
            return;

        Collection<GridJobResultImpl> jobResList = new ArrayList<>(jobs.size());

        Collection<ComputeJobSibling> sibs = new ArrayList<>(jobs.size());

        // Map jobs to nodes for computation.
        for (Map.Entry<? extends ComputeJob, ClusterNode> mappedJob : jobs.entrySet()) {
            ComputeJob job = mappedJob.getKey();
            ClusterNode node = mappedJob.getValue();

            if (job == null)
                throw new GridException("Job can not be null [mappedJob=" + mappedJob + ", ses=" + ses + ']');

            if (node == null)
                throw new GridException("Node can not be null [mappedJob=" + mappedJob + ", ses=" + ses + ']');

            IgniteUuid jobId = IgniteUuid.fromUuid(node.id());

            GridJobSiblingImpl sib = new GridJobSiblingImpl(ses.getId(), jobId, node.id(), ctx);

            jobResList.add(new GridJobResultImpl(job, jobId, node, sib));

            // Do not add siblings if result cache is disabled.
            if (resCache)
                sibs.add(sib);

            recordJobEvent(EVT_JOB_MAPPED, jobId, node, "Job got mapped.");
        }

        synchronized (mux) {
            if (state != State.WAITING)
                throw new GridException("Task is not in waiting state [state=" + state + ", ses=" + ses + ']');

            // Do not add siblings if result cache is disabled.
            if (resCache)
                ses.addJobSiblings(sibs);

            if (jobRes == null)
                jobRes = new HashMap<>();

            // Populate all remote mappedJobs into map, before mappedJobs are sent.
            // This is done to avoid race condition when we start
            // getting results while still sending out references.
            for (GridJobResultImpl res : jobResList) {
                if (jobRes.put(res.getJobContext().getJobId(), res) != null)
                    throw new GridException("Duplicate job ID for remote job found: " + res.getJobContext().getJobId());

                res.setOccupied(true);

                if (resCache && jobRes.size() > ctx.discovery().size() && jobRes.size() % SPLIT_WARN_THRESHOLD == 0)
                    LT.warn(log, null, "Number of jobs in task is too large for task: " + ses.getTaskName() +
                        ". Consider reducing number of jobs or disabling job result cache with " +
                        "@GridComputeTaskNoResultCache annotation.");
            }
        }

        // Set mapped flag.
        ses.onMapped();

        // Send out all remote mappedJobs.
        for (GridJobResultImpl res : jobResList) {
            evtLsnr.onJobSend(this, res.getSibling());

            try {
                sendRequest(res);
            }
            finally {
                // Open job for processing results.
                synchronized (mux) {
                    res.setOccupied(false);
                }
            }
        }

        processDelayedResponses();
    }

    /**
     * @return Topology for this task.
     * @throws GridException Thrown in case of any error.
     */
    private List<ClusterNode> getTaskTopology() throws GridException {
        Collection<UUID> top = ses.getTopology();

        Collection<? extends ClusterNode> subgrid = top != null ? ctx.discovery().nodes(top) : ctx.discovery().allNodes();

        int size = subgrid.size();

        if (size == 0)
            throw new ClusterGroupEmptyException("Topology projection is empty.");

        List<ClusterNode> shuffledNodes = new ArrayList<>(size);

        for (ClusterNode node : subgrid)
            shuffledNodes.add(node);

        if (shuffledNodes.size() > 1)
            // Shuffle nodes prior to giving them to user.
            Collections.shuffle(shuffledNodes);

        // Load balancer.
        return shuffledNodes;
    }

    /**
     *
     */
    private void processDelayedResponses() {
        GridJobExecuteResponse res = delayedRess.poll();

        if (res != null)
            onResponse(res);
    }

    /**
     * @param msg Job execution response.
     */
    void onResponse(GridJobExecuteResponse msg) {
        assert msg != null;

        if (fut.isDone()) {
            if (log.isDebugEnabled())
                log.debug("Ignoring job response since task has finished: " + msg);

            return;
        }

        GridJobExecuteResponse res = msg;

        while (res != null) {
            GridJobResultImpl jobRes = null;

            // Flag indicating whether occupied flag for
            // job response was changed in this method apply.
            boolean selfOccupied = false;

            try {
                synchronized (mux) {
                    // If task is not waiting for responses,
                    // then there is no point to proceed.
                    if (state != State.WAITING) {
                        if (log.isDebugEnabled())
                            log.debug("Ignoring response since task is already reducing or finishing [res=" + res +
                                ", job=" + ses + ", state=" + state + ']');

                        return;
                    }

                    jobRes = this.jobRes.get(res.getJobId());

                    if (jobRes == null) {
                        if (log.isDebugEnabled())
                            U.warn(log, "Received response for unknown child job (was job presumed failed?): " + res);

                        return;
                    }

                    // Only process 1st response and ignore following ones. This scenario
                    // is possible if node has left topology and and fake failure response
                    // was created from discovery listener and when sending request failed.
                    if (jobRes.hasResponse()) {
                        if (log.isDebugEnabled())
                            log.debug("Received redundant response for a job (will ignore): " + res);

                        return;
                    }

                    if (!jobRes.getNode().id().equals(res.getNodeId())) {
                        if (log.isDebugEnabled())
                            log.debug("Ignoring stale response as job was already resent to other node [res=" + res +
                                ", jobRes=" + jobRes + ']');

                        // Prevent processing 2 responses for the same job simultaneously.
                        jobRes.setOccupied(true);

                        selfOccupied = true;

                        // We can not return here because there can be more delayed messages in the queue.
                        continue;
                    }

                    if (jobRes.isOccupied()) {
                        if (log.isDebugEnabled())
                            log.debug("Adding response to delayed queue (job is either being sent or processing " +
                                "another response): " + res);

                        delayedRess.offer(res);

                        return;
                    }

                    if (lockRespProc) {
                        delayedRess.offer(res);

                        return;
                    }

                    lockRespProc = true;

                    selfOccupied = true;

                    // Prevent processing 2 responses for the same job simultaneously.
                    jobRes.setOccupied(true);

                    // We don't keep reference to job if results are not cached.
                    if (!resCache)
                        this.jobRes.remove(res.getJobId());
                }

                if (res.getFakeException() != null)
                    jobRes.onResponse(null, res.getFakeException(), null, false);
                else {
                    ClassLoader clsLdr = dep.classLoader();

                    try {
                        boolean loc = ctx.localNodeId().equals(res.getNodeId()) && !ctx.config().isMarshalLocalJobs();

                        Object res0 = loc ? res.getJobResult() : marsh.unmarshal(res.getJobResultBytes(), clsLdr);

                        GridException ex = loc ? res.getException() :
                            marsh.<GridException>unmarshal(res.getExceptionBytes(), clsLdr);

                        Map<Object, Object> attrs = loc ? res.getJobAttributes() :
                            marsh.<Map<Object, Object>>unmarshal(res.getJobAttributesBytes(), clsLdr);

                        jobRes.onResponse(res0, ex, attrs, res.isCancelled());

                        if (loc)
                            ctx.resource().invokeAnnotated(dep, jobRes.getJob(), ComputeJobAfterSend.class);
                    }
                    catch (GridException e) {
                        U.error(log, "Error deserializing job response: " + res, e);

                        finishTask(null, e);
                    }
                }

                List<ComputeJobResult> results;

                if (!resCache)
                    results = Collections.emptyList();
                else {
                    synchronized (mux) {
                        results = getRemoteResults();
                    }
                }

                ComputeJobResultPolicy plc = result(jobRes, results);

                if (plc == null) {
                    String errMsg = "Failed to obtain remote job result policy for result from GridComputeTask.result(..) " +
                        "method that returned null (will fail the whole task): " + jobRes;

                    finishTask(null, new GridException(errMsg));

                    return;
                }

                synchronized (mux) {
                    // If task is not waiting for responses,
                    // then there is no point to proceed.
                    if (state != State.WAITING) {
                        if (log.isDebugEnabled())
                            log.debug("Ignoring GridComputeTask.result(..) value since task is already reducing or" +
                                "finishing [res=" + res + ", job=" + ses + ", state=" + state + ']');

                        return;
                    }

                    switch (plc) {
                        // Start reducing all results received so far.
                        case REDUCE: {
                            state = State.REDUCING;

                            break;
                        }

                        // Keep waiting if there are more responses to come,
                        // otherwise, reduce.
                        case WAIT: {
                            assert results.size() <= this.jobRes.size();

                            // If there are more results to wait for.
                            // If result cache is disabled, then we reduce
                            // when both collections are empty.
                            if (results.size() == this.jobRes.size()) {
                                plc = ComputeJobResultPolicy.REDUCE;

                                // All results are received, proceed to reduce method.
                                state = State.REDUCING;
                            }

                            break;
                        }

                        case FAILOVER: {
                            if (!failover(res, jobRes, getTaskTopology()))
                                plc = null;

                            break;
                        }
                    }
                }

                // Outside of synchronization.
                if (plc != null) {
                    // Handle failover.
                    if (plc == FAILOVER)
                        sendFailoverRequest(jobRes);
                    else {
                        evtLsnr.onJobFinished(this, jobRes.getSibling());

                        if (plc == ComputeJobResultPolicy.REDUCE)
                            reduce(results);
                    }
                }
            }
            catch (GridException e) {
                U.error(log, "Failed to obtain topology [ses=" + ses + ", err=" + e + ']', e);

                finishTask(null, e);
            }
            finally {
                // Open up job for processing responses.
                // Only unset occupied flag, if it was
                // set in this method.
                if (selfOccupied) {
                    assert jobRes != null;

                    synchronized (mux) {
                        jobRes.setOccupied(false);

                        lockRespProc = false;
                    }

                    // Process delayed responses if there are any.
                    res = delayedRess.poll();
                }
            }
        }
    }

    /**
     * @param jobRes Job result.
     * @param results Existing job results.
     * @return Job result policy.
     */
    @SuppressWarnings({"CatchGenericClass"})
    @Nullable private ComputeJobResultPolicy result(final ComputeJobResult jobRes, final List<ComputeJobResult> results) {
        assert !Thread.holdsLock(mux);

        return U.wrapThreadLoader(dep.classLoader(), new CO<ComputeJobResultPolicy>() {
            @Nullable @Override public ComputeJobResultPolicy apply() {
                try {
                    // Obtain job result policy.
                    ComputeJobResultPolicy plc = null;

                    try {
                        plc = task.result(jobRes, results);

                        if (plc == FAILOVER && noFailover) {
                            GridException e = jobRes.getException();

                            if (e != null)
                                throw e;

                            plc = WAIT;
                        }
                    }
                    finally {
                        recordJobEvent(EVT_JOB_RESULTED, jobRes.getJobContext().getJobId(),
                            jobRes.getNode(), "Job got resulted with: " + plc);
                    }

                    if (log.isDebugEnabled())
                        log.debug("Obtained job result policy [policy=" + plc + ", ses=" + ses + ']');

                    return plc;
                }
                catch (GridException e) {
                    if (X.hasCause(e, GridInternalException.class) ||
                        X.hasCause(e, IgniteFsOutOfSpaceException.class)) {
                        // Print internal exceptions only if debug is enabled.
                        if (log.isDebugEnabled())
                            U.error(log, "Failed to obtain remote job result policy for result from " +
                                "GridComputeTask.result(..) method (will fail the whole task): " + jobRes, e);
                    }
                    else if (X.hasCause(e, ComputeJobFailoverException.class)) {
                        GridException e0 = new GridException(" Job was not failed over because " +
                            "GridComputeJobResultPolicy.FAILOVER was not returned from " +
                            "GridTask.result(...) method for job result with GridComputeJobFailoverException.", e);

                        finishTask(null, e0);

                        return null;
                    }
                    else
                        U.error(log, "Failed to obtain remote job result policy for result from " +
                            "GridComputeTask.result(..) method (will fail the whole task): " + jobRes, e);

                    finishTask(null, e);

                    return null;
                }
                catch (GridRuntimeException e) {
                    if (X.hasCause(e, GridInternalException.class) ||
                        X.hasCause(e, IgniteFsOutOfSpaceException.class)) {
                        // Print internal exceptions only if debug is enabled.
                        if (log.isDebugEnabled())
                            U.error(log, "Failed to obtain remote job result policy for result from " +
                                "GridComputeTask.result(..) method (will fail the whole task): " + jobRes, e);
                    }
                    else if (X.hasCause(e, ComputeJobFailoverException.class)) {
                        GridException e0 = new GridException(" Job was not failed over because " +
                            "GridComputeJobResultPolicy.FAILOVER was not returned from " +
                            "GridTask.result(...) method for job result with GridComputeJobFailoverException.", e);

                        finishTask(null, e0);

                        return null;
                    }
                    else
                        U.error(log, "Failed to obtain remote job result policy for result from" +
                            "GridComputeTask.result(..) method (will fail the whole task): " + jobRes, e);

                    finishTask(null, e);

                    return null;
                }
                catch (Throwable e) {
                    String errMsg = "Failed to obtain remote job result policy for result from" +
                        "GridComputeTask.result(..) method due to undeclared user exception " +
                        "(will fail the whole task): " + jobRes;

                    U.error(log, errMsg, e);

                    Throwable tmp = new ComputeUserUndeclaredException(errMsg, e);

                    // Failed to successfully obtain result policy and
                    // hence forced to fail the whole deployed task.
                    finishTask(null, tmp);

                    return null;
                }
            }
        });
    }

    /**
     * @param results Job results.
     */
    private void reduce(final List<ComputeJobResult> results) {
        R reduceRes = null;
        Throwable userE = null;

        try {
            try {
                // Reduce results.
                reduceRes = U.wrapThreadLoader(dep.classLoader(), new Callable<R>() {
                    @Nullable @Override public R call() throws GridException {
                        return task.reduce(results);
                    }
                });
            }
            finally {
                synchronized (mux) {
                    assert state == State.REDUCING : "Invalid task state: " + state;

                    state = State.REDUCED;
                }
            }

            if (log.isDebugEnabled())
                log.debug("Reduced job responses [reduceRes=" + reduceRes + ", ses=" + ses + ']');

            recordTaskEvent(EVT_TASK_REDUCED, "Task reduced.");
        }
        catch (ClusterTopologyException e) {
            U.warn(log, "Failed to reduce job results for task (any nodes from task topology left grid?): " + task);

            userE = e;
        }
        catch (GridException e) {
            U.error(log, "Failed to reduce job results for task: " + task, e);

            userE = e;
        }
        // Catch Throwable to protect against bad user code.
        catch (Throwable e) {
            String errMsg = "Failed to reduce job results due to undeclared user exception [task=" + task +
                ", err=" + e + ']';

            U.error(log, errMsg, e);

            userE = new ComputeUserUndeclaredException(errMsg ,e);
        }
        finally {
            finishTask(reduceRes, userE);
        }
    }

    /**
     * @param res Execution response.
     * @param jobRes Job result.
     * @param top Topology.
     * @return {@code True} if fail-over SPI returned a new node.
     */
    private boolean failover(GridJobExecuteResponse res, GridJobResultImpl jobRes, Collection<? extends ClusterNode> top) {
        assert Thread.holdsLock(mux);

        try {
            ctx.resource().invokeAnnotated(dep, jobRes.getJob(), ComputeJobBeforeFailover.class);

            // Map to a new node.
            ClusterNode node = ctx.failover().failover(ses, jobRes, new ArrayList<>(top));

            if (node == null) {
                String msg = "Failed to failover a job to another node (failover SPI returned null) [job=" +
                    jobRes.getJob() + ", node=" + jobRes.getNode() + ']';

                if (log.isDebugEnabled())
                    log.debug(msg);

                Throwable e = new ClusterTopologyException(msg, jobRes.getException());

                finishTask(null, e);

                return false;
            }

            if (log.isDebugEnabled())
                log.debug("Resolved job failover [newNode=" + node + ", oldNode=" + jobRes.getNode() +
                    ", job=" + jobRes.getJob() + ", resMsg=" + res + ']');

            jobRes.setNode(node);
            jobRes.resetResponse();

            if (!resCache) {
                synchronized (mux) {
                    // Store result back in map before sending.
                    this.jobRes.put(res.getJobId(), jobRes);
                }
            }

            return true;
        }
        // Catch Throwable to protect against bad user code.
        catch (Throwable e) {
            String errMsg = "Failed to failover job due to undeclared user exception [job=" +
                jobRes.getJob() + ", err=" + e + ']';

            U.error(log, errMsg, e);

            finishTask(null, new ComputeUserUndeclaredException(errMsg, e));

            return false;
        }
    }

    /**
     * @param jobRes Job result.
     */
    private void sendFailoverRequest(GridJobResultImpl jobRes) {
        // Internal failover notification.
        evtLsnr.onJobFailover(this, jobRes.getSibling(), jobRes.getNode().id());

        long timeout = ses.getEndTime() - U.currentTimeMillis();

        if (timeout > 0) {
            recordJobEvent(EVT_JOB_FAILED_OVER, jobRes.getJobContext().getJobId(),
                jobRes.getNode(), "Job failed over.");

            // Send new reference to remote nodes for execution.
            sendRequest(jobRes);
        }
        else
            // Don't apply 'finishTask(..)' here as it will
            // be called from 'onTimeout(..)' callback.
            U.warn(log, "Failed to fail-over job due to task timeout: " + jobRes);
    }

    /**
     * Interrupts child jobs on remote nodes.
     */
    private void cancelChildren() {
        Collection<GridJobResultImpl> doomed = new LinkedList<>();

        synchronized (mux) {
            // Only interrupt unfinished jobs.
            if (jobRes != null)
                for (GridJobResultImpl res : jobRes.values())
                    if (!res.hasResponse())
                        doomed.add(res);
        }

        // Send cancellation request to all unfinished children.
        for (GridJobResultImpl res : doomed) {
            UUID nodeId = res.getNode().id();

            if (nodeId.equals(ctx.localNodeId()))
                // Cancel local jobs.
                ctx.job().cancelJob(ses.getId(), res.getJobContext().getJobId(), /*courtesy*/true);
            else {
                try {
                    ClusterNode node = ctx.discovery().node(nodeId);

                    if (node != null)
                        ctx.io().send(node,
                            TOPIC_JOB_CANCEL,
                            new GridJobCancelRequest(ses.getId(), res.getJobContext().getJobId(), /*courtesy*/true),
                            PUBLIC_POOL);
                }
                catch (GridException e) {
                    if (!isDeadNode(nodeId))
                        U.error(log, "Failed to send cancel request to node (will ignore) [nodeId=" +
                            nodeId + ", taskName=" + ses.getTaskName() +
                            ", taskSesId=" + ses.getId() + ", jobSesId=" + res.getJobContext().getJobId() + ']', e);
                }
            }
        }
    }

    /**
     * @param res Job result.
     */
    private void sendRequest(ComputeJobResult res) {
        assert res != null;

        GridJobExecuteRequest req = null;

        ClusterNode node = res.getNode();

        try {
            ClusterNode curNode = ctx.discovery().node(node.id());

            // Check if node exists prior to sending to avoid cases when a discovery
            // listener notified about node leaving after topology resolution. Note
            // that we make this check because we cannot count on exception being
            // thrown in case of send failure.
            if (curNode == null) {
                U.warn(log, "Failed to send job request because remote node left grid (if fail-over is enabled, " +
                    "will attempt fail-over to another node) [node=" + node + ", taskName=" + ses.getTaskName() +
                    ", taskSesId=" + ses.getId() + ", jobSesId=" + res.getJobContext().getJobId() + ']');

                ctx.resource().invokeAnnotated(dep, res.getJob(), ComputeJobAfterSend.class);

                GridJobExecuteResponse fakeRes = new GridJobExecuteResponse(node.id(), ses.getId(),
                    res.getJobContext().getJobId(), null, null, null, null, null, null, false);

                fakeRes.setFakeException(new ClusterTopologyException("Failed to send job due to node failure: " + node));

                onResponse(fakeRes);
            }
            else {
                long timeout = ses.getEndTime() == Long.MAX_VALUE ? Long.MAX_VALUE :
                    ses.getEndTime() - U.currentTimeMillis();

                if (timeout > 0) {
                    boolean loc = node.id().equals(ctx.discovery().localNode().id()) &&
                        !ctx.config().isMarshalLocalJobs();

                    Map<Object, Object> sesAttrs = ses.isFullSupport() ? ses.getAttributes() : null;
                    Map<? extends Serializable, ? extends Serializable> jobAttrs =
                        (Map<? extends Serializable, ? extends Serializable>)res.getJobContext().getAttributes();

                    boolean forceLocDep = internal || !ctx.deploy().enabled();

                    req = node.version().compareTo(GridJobProcessor.SUBJECT_ID_ADDED_SINCE_VER) < 0 ?
                        new GridJobExecuteRequest(
                            ses.getId(),
                            res.getJobContext().getJobId(),
                            ses.getTaskName(),
                            ses.getUserVersion(),
                            ses.getTaskClassName(),
                            loc ? null : marsh.marshal(res.getJob()),
                            loc ? res.getJob() : null,
                            ses.getStartTime(),
                            timeout,
                            ses.getTopology(),
                            loc ? null : marsh.marshal(ses.getJobSiblings()),
                            loc ? ses.getJobSiblings() : null,
                            loc ? null : marsh.marshal(sesAttrs),
                            loc ? sesAttrs : null,
                            loc ? null: marsh.marshal(jobAttrs),
                            loc ? jobAttrs : null,
                            ses.getCheckpointSpi(),
                            dep.classLoaderId(),
                            dep.deployMode(),
                            continuous,
                            dep.participants(),
                            forceLocDep,
                            ses.isFullSupport(),
                            internal) :
                        new GridJobExecuteRequestV2(
                            ses.getId(),
                            res.getJobContext().getJobId(),
                            ses.getTaskName(),
                            ses.getUserVersion(),
                            ses.getTaskClassName(),
                            loc ? null : marsh.marshal(res.getJob()),
                            loc ? res.getJob() : null,
                            ses.getStartTime(),
                            timeout,
                            ses.getTopology(),
                            loc ? null : marsh.marshal(ses.getJobSiblings()),
                            loc ? ses.getJobSiblings() : null,
                            loc ? null : marsh.marshal(sesAttrs),
                            loc ? sesAttrs : null,
                            loc ? null: marsh.marshal(jobAttrs),
                            loc ? jobAttrs : null,
                            ses.getCheckpointSpi(),
                            dep.classLoaderId(),
                            dep.deployMode(),
                            continuous,
                            dep.participants(),
                            forceLocDep,
                            ses.isFullSupport(),
                            internal,
                            subjId);

                    if (loc)
                        ctx.job().processJobExecuteRequest(ctx.discovery().localNode(), req);
                    else {
                        // Send job execution request.
                        ctx.io().send(node, TOPIC_JOB, req, internal ? MANAGEMENT_POOL : PUBLIC_POOL);

                        if (log.isDebugEnabled())
                            log.debug("Sent job request [req=" + req + ", node=" + node + ']');
                    }

                    if (!loc)
                        ctx.resource().invokeAnnotated(dep, res.getJob(), ComputeJobAfterSend.class);
                }
                else
                    U.warn(log, "Job timed out prior to sending job execution request: " + res.getJob());
            }
        }
        catch (GridException e) {
            boolean deadNode = isDeadNode(res.getNode().id());

            // Avoid stack trace if node has left grid.
            if (deadNode)
                U.warn(log, "Failed to send job request because remote node left grid (if failover is enabled, " +
                    "will attempt fail-over to another node) [node=" + node + ", taskName=" + ses.getTaskName() +
                    ", taskSesId=" + ses.getId() + ", jobSesId=" + res.getJobContext().getJobId() + ']');
            else
                U.error(log, "Failed to send job request: " + req, e);

            GridJobExecuteResponse fakeRes = new GridJobExecuteResponse(node.id(), ses.getId(),
                res.getJobContext().getJobId(), null, null, null, null, null, null, false);

            if (deadNode)
                fakeRes.setFakeException(new ClusterTopologyException("Failed to send job due to node failure: " +
                    node, e));
            else
                fakeRes.setFakeException(e);

            onResponse(fakeRes);
        }
    }

    /**
     * @param nodeId Node ID.
     */
    void onNodeLeft(UUID nodeId) {
        Collection<GridJobExecuteResponse> resList = null;

        synchronized (mux) {
            // First check if job cares about future responses.
            if (state != State.WAITING)
                return;

            if (jobRes != null) {
                for (GridJobResultImpl jr : jobRes.values()) {
                    if (!jr.hasResponse() && jr.getNode().id().equals(nodeId)) {
                        if (log.isDebugEnabled())
                            log.debug("Creating fake response because node left grid [job=" + jr.getJob() +
                                ", nodeId=" + nodeId + ']');

                        // Artificial response in case if a job is waiting for a response from
                        // non-existent node.
                        GridJobExecuteResponse fakeRes = new GridJobExecuteResponse(nodeId, ses.getId(),
                            jr.getJobContext().getJobId(), null, null, null, null, null, null, false);

                        fakeRes.setFakeException(new ClusterTopologyException("Node has left grid: " + nodeId));

                        if (resList == null)
                            resList = new ArrayList<>();

                        resList.add(fakeRes);
                    }
                }
            }
        }

        if (resList == null)
            return;

        // Simulate responses without holding synchronization.
        for (GridJobExecuteResponse res : resList) {
            if (log.isDebugEnabled())
                log.debug("Simulating fake response from left node [res=" + res + ", nodeId=" + nodeId + ']');

            onResponse(res);
        }
    }

    /**
     * @param evtType Event type.
     * @param msg Event message.
     */
    private void recordTaskEvent(int evtType, String msg) {
        if (!internal && ctx.event().isRecordable(evtType)) {
            IgniteEvent evt = new IgniteTaskEvent(
                ctx.discovery().localNode(),
                msg,
                evtType,
                ses.getId(),
                ses.getTaskName(),
                ses.getTaskClassName(),
                internal,
                subjId);

            ctx.event().record(evt);
        }
    }

    /**
     * @param evtType Event type.
     * @param jobId Job ID.
     * @param evtNode Event node.
     * @param msg Event message.
     */
    private void recordJobEvent(int evtType, IgniteUuid jobId, ClusterNode evtNode, String msg) {
        if (ctx.event().isRecordable(evtType)) {
            IgniteJobEvent evt = new IgniteJobEvent();

            evt.message(msg);
            evt.node(ctx.discovery().localNode());
            evt.taskName(ses.getTaskName());
            evt.taskClassName(ses.getTaskClassName());
            evt.taskSessionId(ses.getId());
            evt.taskNode(evtNode);
            evt.jobId(jobId);
            evt.type(evtType);
            evt.taskSubjectId(ses.subjectId());

            ctx.event().record(evt);
        }
    }

    /**
     * @return Collection of job results.
     */
    private List<ComputeJobResult> getRemoteResults() {
        assert Thread.holdsLock(mux);

        List<ComputeJobResult> results = new ArrayList<>(jobRes.size());

        for (GridJobResultImpl jobResult : jobRes.values())
            if (jobResult.hasResponse())
                results.add(jobResult);

        return results;
    }

    /**
     * @param res Task result.
     * @param e Exception.
     */
    void finishTask(@Nullable R res, @Nullable Throwable e) {
        finishTask(res, e, true);
    }

    /**
     * @param res Task result.
     * @param e Exception.
     * @param cancelChildren Whether to cancel children in case the task become cancelled.
     */
    void finishTask(@Nullable R res, @Nullable Throwable e, boolean cancelChildren) {
        // Avoid finishing a job more than once from
        // different threads.
        synchronized (mux) {
            if (state == State.REDUCING || state == State.FINISHING)
                return;

            state = State.FINISHING;
        }

        try {
            if (e == null)
                recordTaskEvent(EVT_TASK_FINISHED, "Task finished.");
            else
                recordTaskEvent(EVT_TASK_FAILED, "Task failed.");

            // Clean resources prior to finishing future.
            evtLsnr.onTaskFinished(this);

            if (cancelChildren)
                cancelChildren();
        }
        // Once we marked task as 'Finishing' we must complete it.
        finally {
            fut.onDone(res, e);

            ses.onDone();
        }
    }

    /**
     * Checks whether node is alive or dead.
     *
     * @param uid UID of node to check.
     * @return {@code true} if node is dead, {@code false} is node is alive.
     */
    private boolean isDeadNode(UUID uid) {
        return ctx.discovery().node(uid) == null || !ctx.discovery().pingNode(uid);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public boolean equals(Object obj) {
        if (this == obj)
            return true;

        if (obj == null)
            return false;

        assert obj instanceof GridTaskWorker;

        return ses.getId().equals(((GridTaskWorker<T, R>)obj).ses.getId());
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return ses.getId().hashCode();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridTaskWorker.class, this);
    }
}
