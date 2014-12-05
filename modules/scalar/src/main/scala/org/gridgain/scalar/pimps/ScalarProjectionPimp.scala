/* @scala.file.header */

/*
 * ________               ______                    ______   _______
 * __  ___/_____________ ____  /______ _________    __/__ \  __  __ \
 * _____ \ _  ___/_  __ `/__  / _  __ `/__  ___/    ____/ /  _  / / /
 * ____/ / / /__  / /_/ / _  /  / /_/ / _  /        _  __/___/ /_/ /
 * /____/  \___/  \__,_/  /_/   \__,_/  /_/         /____/_(_)____/
 *
 */

package org.gridgain.scalar.pimps

import org.apache.ignite.cluster.{ClusterGroupEmptyException, ClusterGroup, ClusterNode}
import org.apache.ignite.lang.{IgniteFutureCancelledException, IgniteFuture, IgnitePredicate}
import org.gridgain.grid._
import org.jetbrains.annotations._

/**
 * Companion object.
 */
object ScalarProjectionPimp {
    /**
     * Creates new Scalar projection pimp with given Java-side implementation.
     *
     * @param impl Java-side implementation.
     */
    def apply(impl: ClusterGroup) = {
        if (impl == null)
            throw new NullPointerException("impl")

        val pimp = new ScalarProjectionPimp[ClusterGroup]

        pimp.impl = impl

        pimp
    }
}

/**
 * ==Overview==
 * Defines Scalar "pimp" for `GridProjection` on Java side.
 *
 * Essentially this class extends Java `GridProjection` interface with Scala specific
 * API adapters using primarily implicit conversions defined in `ScalarConversions` object. What
 * it means is that you can use functions defined in this class on object
 * of Java `GridProjection` type. Scala will automatically (implicitly) convert it into
 * Scalar's pimp and replace the original call with a call on that pimp.
 *
 * Note that Scalar provide extensive library of implicit conversion between Java and
 * Scala GridGain counterparts in `ScalarConversions` object
 *
 * ==Suffix '$' In Names==
 * Symbol `$` is used in names when they conflict with the names in the base Java class
 * that Scala pimp is shadowing or with Java package name that your Scala code is importing.
 * Instead of giving two different names to the same function we've decided to simply mark
 * Scala's side method with `$` suffix.
 */
class ScalarProjectionPimp[A <: ClusterGroup] extends PimpedType[A] with Iterable[ClusterNode]
    with ScalarTaskThreadContext[A] {
    /** */
    lazy val value: A = impl

    /** */
    protected var impl: A = _

    /** Type alias for '() => Unit'. */
    protected type Run = () => Unit

    /** Type alias for '() => R'. */
    protected type Call[R] = () => R

    /** Type alias for '(E1) => R'. */
    protected type Call1[E1, R] = (E1) => R

    /** Type alias for '(E1, E2) => R'. */
    protected type Call2[E1, E2, R] = (E1, E2) => R

    /** Type alias for '(E1, E2, E3) => R'. */
    protected type Call3[E1, E2, E3, R] = (E1, E2, E3) => R

    /** Type alias for '() => Boolean'. */
    protected type Pred = () => Boolean

    /** Type alias for '(E1) => Boolean'. */
    protected type Pred1[E1] = (E1) => Boolean

    /** Type alias for '(E1, E2) => Boolean'. */
    protected type Pred2[E1, E2] = (E1, E2) => Boolean

    /** Type alias for '(E1, E2, E3) => Boolean'. */
    protected type Pred3[E1, E2, E3] = (E1, E2, E3) => Boolean

    /** Type alias for node filter predicate. */
    protected type NF = IgnitePredicate[ClusterNode]

    /**
     * Gets iterator for this projection's nodes.
     */
    def iterator = nodes$(null).iterator

    /**
     * Utility function to workaround issue that `GridProjection` does not permit `null` predicates.
     *
     * @param p Optional predicate.
     * @return If `p` not `null` return projection for this predicate otherwise return pimped projection.
     */
    private def forPredicate(@Nullable p: NF): ClusterGroup =
        if (p != null) value.forPredicate(p) else value

    /**
     * Gets sequence of all nodes in this projection for given predicate.
     *
     * @param p Optional node filter predicates. It `null` provided - all nodes will be returned.
     * @see `org.gridgain.grid.GridProjection.nodes(...)`
     */
    def nodes$(@Nullable p: NF): Seq[ClusterNode] =
        toScalaSeq(forPredicate(p).nodes())

    /**
     * Gets sequence of all remote nodes in this projection for given predicate.
     *
     * @param p Optional node filter predicate. It `null` provided - all remote nodes will be returned.
     * @see `org.gridgain.grid.GridProjection.remoteNodes(...)`
     */
    def remoteNodes$(@Nullable p: NF = null): Seq[ClusterNode] =
        toScalaSeq(forPredicate(p).forRemotes().nodes())

    /**
     * <b>Alias</b> for method `send$(...)`.
     *
     * @param obj Optional object to send. If `null` - this method is no-op.
     * @param p Optional node filter predicates. If none provided or `null` -
     *      all nodes in the projection will be used.
     * @see `org.gridgain.grid.GridProjection.send(...)`
     */
    def !<(@Nullable obj: AnyRef, @Nullable p: NF) {
        value.grid().message(forPredicate(p)).send(null, obj)
    }

    /**
     * <b>Alias</b> for method `send$(...)`.
     *
     * @param seq Optional sequence of objects to send. If empty or `null` - this
     *      method is no-op.
     * @param p Optional node filter predicate. If none provided or `null` -
     *      all nodes in the projection will be used.
     * @see `org.gridgain.grid.GridProjection.send(...)`
     */
    def !<(@Nullable seq: Seq[AnyRef], @Nullable p: NF) {
        value.grid().message(forPredicate(p)).send(null, seq)
    }

    /**
     * Sends given object to the nodes in this projection.
     *
     * @param obj Optional object to send. If `null` - this method is no-op.
     * @param p Optional node filter predicate. If none provided or `null` -
     *      all nodes in the projection will be used.
     * @see `org.gridgain.grid.GridProjection.send(...)`
     */
    def send$(@Nullable obj: AnyRef, @Nullable p: NF) {
        value.grid().message(forPredicate(p)).send(null, obj)
    }

    /**
     * Sends given object to the nodes in this projection.
     *
     * @param seq Optional sequence of objects to send. If empty or `null` - this
     *      method is no-op.
     * @param p Optional node filter predicate. If  `null` provided - all nodes in the projection will be used.
     * @see `org.gridgain.grid.GridProjection.send(...)`
     */
    def send$(@Nullable seq: Seq[AnyRef], @Nullable p: NF) {
        value.grid().message(forPredicate(p)).send(null, seq)
    }

    /**
     * Synchronous closures call on this projection with return value.
     * This call will block until all results are received and ready.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method is no-op and returns `null`.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Sequence of result values from all nodes where given closures were executed or `null` (see above).
     */
    def call$[R](@Nullable s: Seq[Call[R]], @Nullable p: NF): Seq[R] =
        toScalaSeq(callAsync$(s, p).get)

    /**
     * Synchronous closures call on this projection with return value.
     * This call will block until all results are received and ready. If this projection
     * is empty than `dflt` closure will be executed and its result returned.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method is no-op and returns `null`.
     * @param dflt Closure to execute if projection is empty.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Sequence of result values from all nodes where given closures were executed or `null` (see above).
     */
    def callSafe[R](@Nullable s: Seq[Call[R]], dflt: () => Seq[R], @Nullable p: NF): Seq[R] = {
        assert(dflt != null)

        try
            call$(s, p)
        catch {
            case _: ClusterGroupEmptyException => dflt()
        }
    }

    /**
     * <b>Alias</b> for the same function `call$`.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method is no-op and returns `null`.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Sequence of result values from all nodes where given closures were executed or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.call(...)`
     */
    def #<[R](@Nullable s: Seq[Call[R]], @Nullable p: NF): Seq[R] =
        call$(s, p)

    /**
     * Synchronous closure call on this projection with return value.
     * This call will block until all results are received and ready.
     *
     * @param s Optional closure to call. If `null` - this method is no-op and returns `null`.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Sequence of result values from all nodes where given closures were executed or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.call(...)`
     */
    def call$[R](@Nullable s: Call[R], @Nullable p: NF): Seq[R] =
        call$(Seq(s), p)

    /**
     * Synchronous closure call on this projection with return value.
     * This call will block until all results are received and ready. If this projection
     * is empty than `dflt` closure will be executed and its result returned.
     *
     * @param s Optional closure to call. If `null` - this method is no-op and returns `null`.
     * @param dflt Closure to execute if projection is empty.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Sequence of result values from all nodes where given closures were executed or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.call(...)`
     */
    def callSafe[R](@Nullable s: Call[R], dflt: () => Seq[R], @Nullable p: NF): Seq[R] = {
        assert(dflt != null)

        try
            call$(Seq(s), p)
        catch {
            case _: ClusterGroupEmptyException => dflt()
        }
    }

    /**
     * <b>Alias</b> for the same function `call$`.
     *
     * @param s Optional closure to call. If `null` - this method is no-op and returns `null`.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Sequence of result values from all nodes where given closures were executed
     *      or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.call(...)`
     */
    def #<[R](@Nullable s: Call[R], @Nullable p: NF): Seq[R] =
        call$(s, p)

    /**
     * Synchronous closures call on this projection without return value.
     * This call will block until all executions are complete.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method is no-op.
     * @param p Optional node filter predicate. If `null` provided- all nodes in projection will be used.
     * @see `org.gridgain.grid.GridProjection.run(...)`
     */
    def run$(@Nullable s: Seq[Run], @Nullable p: NF) {
        runAsync$(s, p).get
    }

    /**
     * Synchronous broadcast closure call on this projection without return value.
     *
     * @param r Closure to run all nodes in projection.
     * @param p Optional node filter predicate. If `null` provided- all nodes in projection will be used.
     */
    def bcastRun(@Nullable r: Run, @Nullable p: NF) {
        value.grid().compute(forPredicate(p)).broadcast(toRunnable(r))
    }

    /**
     * Synchronous closures call on this projection without return value.
     * This call will block until all executions are complete. If this projection
     * is empty than `dflt` closure will be executed.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this
     *      method is no-op.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @param dflt Closure to execute if projection is empty.
     * @see `org.gridgain.grid.GridProjection.run(...)`
     */
    def runSafe(@Nullable s: Seq[Run], @Nullable dflt: Run, @Nullable p: NF) {
        try {
            run$(s, p)
        }
        catch {
            case _: ClusterGroupEmptyException => if (dflt != null) dflt() else ()
        }
    }

    /**
     * <b>Alias</b> alias for the same function `run$`.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method is no-op.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @see `org.gridgain.grid.GridProjection.run(...)`
     */
    def *<(@Nullable s: Seq[Run], @Nullable p: NF) {
        run$(s, p)
    }

    /**
     * Synchronous closure call on this projection without return value.
     * This call will block until all executions are complete.
     *
     * @param s Optional closure to call. If empty or `null` - this method is no-op.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @see `org.gridgain.grid.GridProjection.run(...)`
     */
    def run$(@Nullable s: Run, @Nullable p: NF) {
        run$(Seq(s), p)
    }

    /**
     * Synchronous closure call on this projection without return value.
     * This call will block until all executions are complete. If this projection
     * is empty than `dflt` closure will be executed.
     *
     * @param s Optional closure to call. If empty or `null` - this method is no-op.
     * @param dflt Closure to execute if projection is empty.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @see `org.gridgain.grid.GridProjection.run(...)`
     */
    def runSafe(@Nullable s: Run, @Nullable dflt: Run, @Nullable p: NF) {
        try {
            run$(s, p)
        }
        catch {
            case _: ClusterGroupEmptyException => if (dflt != null) dflt() else ()
        }
    }

    /**
     * <b>Alias</b> for the same function `run$`.
     *
     * @param s Optional closure to call. If empty or `null` - this method is no-op.
     * @param p Optional node filter predicate. If none provided or `null` - all nodes in projection will be used.
     * @see `org.gridgain.grid.GridProjection.run(...)`
     */
    def *<(@Nullable s: Run, @Nullable p: NF) {
        run$(s, p)
    }

    /**
     * Asynchronous closures call on this projection with return value. This call will
     * return immediately with the future that can be used to wait asynchronously for the results.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method
     *      is no-op and finished future over `null` is returned.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Future of Java collection containing result values from all nodes where given
     *      closures were executed or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.call(...)`
     */
    def callAsync$[R](@Nullable s: Seq[Call[R]], @Nullable p: NF):
        IgniteFuture[java.util.Collection[R]] = {
        val comp = value.grid().compute(forPredicate(p)).enableAsync()

        comp.call[R](toJavaCollection(s, (f: Call[R]) => toCallable(f)))

        comp.future()
    }

    /**
     * <b>Alias</b> for the same function `callAsync$`.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method
     *      is no-op and finished future over `null` is returned.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Future of Java collection containing result values from all nodes where given
     *      closures were executed or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.call(...)`
     */
    def #?[R](@Nullable s: Seq[Call[R]], @Nullable p: NF): IgniteFuture[java.util.Collection[R]] = {
        callAsync$(s, p)
    }

    /**
     * Asynchronous closure call on this projection with return value. This call will
     * return immediately with the future that can be used to wait asynchronously for the results.
     *
     * @param s Optional closure to call. If `null` - this method is no-op and finished
     *      future over `null` is returned.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Future of Java collection containing result values from all nodes where given
     *      closures were executed or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.call(...)`
     */
    def callAsync$[R](@Nullable s: Call[R], @Nullable p: NF): IgniteFuture[java.util.Collection[R]] = {
        callAsync$(Seq(s), p)
    }

    /**
     * <b>Alias</b> for the same function `callAsync$`.
     *
     * @param s Optional closure to call. If `null` - this method is no-op and finished
     *      future over `null` is returned.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Future of Java collection containing result values from all nodes where given
     *      closures were executed or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.call(...)`
     */
    def #?[R](@Nullable s: Call[R], @Nullable p: NF): IgniteFuture[java.util.Collection[R]] = {
        callAsync$(s, p)
    }

    /**
     * Asynchronous closures call on this projection without return value. This call will
     * return immediately with the future that can be used to wait asynchronously for the results.
     *
     * @param s Optional sequence of absolute closures to call. If empty or `null` - this method
     *      is no-op and finished future over `null` will be returned.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @see `org.gridgain.grid.GridProjection.call(...)`
     */
    def runAsync$(@Nullable s: Seq[Run], @Nullable p: NF): IgniteFuture[_] = {
        val comp = value.grid().compute(forPredicate(p)).enableAsync()

        comp.run(toJavaCollection(s, (f: Run) => toRunnable(f)))

        comp.future()
    }

    /**
     * <b>Alias</b> for the same function `runAsync$`.
     *
     * @param s Optional sequence of absolute closures to call. If empty or `null` - this method
     *      is no-op and finished future over `null` will be returned.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @see `org.gridgain.grid.GridProjection.call(...)`
     */
    def *?(@Nullable s: Seq[Run], @Nullable p: NF): IgniteFuture[_] = {
        runAsync$(s, p)
    }

    /**
     * Asynchronous closure call on this projection without return value. This call will
     * return immediately with the future that can be used to wait asynchronously for the results.
     *
     * @param s Optional absolute closure to call. If `null` - this method
     *      is no-op and finished future over `null` will be returned.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @see `org.gridgain.grid.GridProjection.run(...)`
     */
    def runAsync$(@Nullable s: Run, @Nullable p: NF): IgniteFuture[_] = {
        runAsync$(Seq(s), p)
    }

    /**
     * <b>Alias</b> for the same function `runAsync$`.
     *
     * @param s Optional absolute closure to call. If `null` - this method
     *      is no-op and finished future over `null` will be returned.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @see `org.gridgain.grid.GridProjection.run(...)`
     */
    def *?(@Nullable s: Run, @Nullable p: NF): IgniteFuture[_] = {
        runAsync$(s, p)
    }

    /**
     * Asynchronous closures execution on this projection with reduction. This call will
     * return immediately with the future that can be used to wait asynchronously for the results.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method
     *      is no-op and will return finished future over `null`.
     * @param r Optional reduction function. If `null` - this method
     *      is no-op and will return finished future over `null`.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Future over the reduced result or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.reduce(...)`
     */
    def reduceAsync$[R1, R2](s: Seq[Call[R1]], r: Seq[R1] => R2, @Nullable p: NF): IgniteFuture[R2] = {
        assert(s != null && r != null)

        val comp = value.grid().compute(forPredicate(p)).enableAsync()

        comp.call(toJavaCollection(s, (f: Call[R1]) => toCallable(f)), r)

        comp.future()
    }

    /**
     * <b>Alias</b> for the same function `reduceAsync$`.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method
     *      is no-op and will return finished future over `null`.
     * @param r Optional reduction function. If `null` - this method
     *      is no-op and will return finished future over `null`.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Future over the reduced result or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.reduce(...)`
     */
    def @?[R1, R2](s: Seq[Call[R1]], r: Seq[R1] => R2, @Nullable p: NF): IgniteFuture[R2] = {
        reduceAsync$(s, r, p)
    }

    /**
     * Synchronous closures execution on this projection with reduction.
     * This call will block until all results are reduced.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method
     *      is no-op and will return `null`.
     * @param r Optional reduction function. If `null` - this method
     *      is no-op and will return `null`.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Reduced result or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.reduce(...)`
     */
    def reduce$[R1, R2](@Nullable s: Seq[Call[R1]], @Nullable r: Seq[R1] => R2, @Nullable p: NF): R2 =
        reduceAsync$(s, r, p).get

    /**
     * Synchronous closures execution on this projection with reduction.
     * This call will block until all results are reduced. If this projection
     * is empty than `dflt` closure will be executed and its result returned.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method
     *      is no-op and will return `null`.
     * @param r Optional reduction function. If `null` - this method
     *      is no-op and will return `null`.
     * @param dflt Closure to execute if projection is empty.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Reduced result or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.reduce(...)`
     */
    def reduceSafe[R1, R2](@Nullable s: Seq[Call[R1]], @Nullable r: Seq[R1] => R2,
        dflt: () => R2, @Nullable p: NF): R2 = {
        assert(dflt != null)

        try
            reduceAsync$(s, r, p).get
        catch {
            case _: ClusterGroupEmptyException => dflt()
        }
    }

    /**
     * <b>Alias</b> for the same function `reduce$`.
     *
     * @param s Optional sequence of closures to call. If empty or `null` - this method is no-op and will return `null`.
     * @param r Optional reduction function. If `null` - this method is no-op and will return `null`.
     * @param p Optional node filter predicate. If `null` provided - all nodes in projection will be used.
     * @return Reduced result or `null` (see above).
     * @see `org.gridgain.grid.GridProjection.reduce(...)`
     */
    def @<[R1, R2](@Nullable s: Seq[Call[R1]], @Nullable r: Seq[R1] => R2, @Nullable p: NF): R2 =
        reduceAsync$(s, r, p).get

    /**
     * Executes given closure on the nodes where data for provided affinity key is located. This
     * is known as affinity co-location between compute grid (a closure) and in-memory data grid
     * (value with affinity key). Note that implementation of multiple executions of the same closure will
     * be wrapped as a single task that splits into multiple `job`s that will be mapped to nodes
     * with provided affinity keys.
     *
     * This method will block until its execution is complete or an exception is thrown.
     * All default SPI implementations configured for this grid instance will be
     * used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement `GridComputeTask` which will provide you with full control over the execution.
     *
     * Notice that `Runnable` and `Callable` implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * `org.gridgain.grid.lang` package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param cacheName Name of the cache to use for affinity co-location.
     * @param affKey Affinity key.
     * @param r Closure to affinity co-located on the node with given affinity key and execute.
     *      If `null` - this method is no-op.
     * @param p Optional filtering predicate. If `null` provided - all nodes in this projection will be used for topology.
     * @throws GridException Thrown in case of any error.
     * @throws ClusterGroupEmptyException Thrown in case when this projection is empty.
     *      Note that in case of dynamic projection this method will take a snapshot of all the
     *      nodes at the time of this call, apply all filtering predicates, if any, and if the
     *      resulting collection of nodes is empty - the exception will be thrown.
     * @throws GridInterruptedException Subclass of `GridException` thrown if the wait was interrupted.
     * @throws IgniteFutureCancelledException Subclass of `GridException` thrown if computation was cancelled.
     */
    def affinityRun$(cacheName: String, @Nullable affKey: Any, @Nullable r: Run, @Nullable p: NF) {
        affinityRunAsync$(cacheName, affKey, r, p).get
    }

    /**
     * Executes given closure on the nodes where data for provided affinity key is located. This
     * is known as affinity co-location between compute grid (a closure) and in-memory data grid
     * (value with affinity key). Note that implementation of multiple executions of the same closure will
     * be wrapped as a single task that splits into multiple `job`s that will be mapped to nodes
     * with provided affinity keys.
     *
     * Unlike its sibling method `affinityRun(String, Collection, Runnable, GridPredicate[])` this method does
     * not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement `GridComputeTask` which will provide you with full control over the execution.
     *
     * Note that class `GridAbsClosure` implements `Runnable` and class `GridOutClosure`
     * implements `Callable` interface. Note also that class `GridFunc` and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * `Runnable` and `Callable` allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in `org.gridgain.grid.lang`
     * package.
     *
     * Notice that `Runnable` and `Callable` implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * `org.gridgain.grid.lang` package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param cacheName Name of the cache to use for affinity co-location.
     * @param affKey Affinity key.
     * @param r Closure to affinity co-located on the node with given affinity key and execute.
     *      If `null` - this method is no-op.
     * @param p Optional filtering predicate. If `null` provided - all nodes in this projection will be used for topology.
     * @throws GridException Thrown in case of any error.
     * @throws ClusterGroupEmptyException Thrown in case when this projection is empty.
     *      Note that in case of dynamic projection this method will take a snapshot of all the
     *      nodes at the time of this call, apply all filtering predicates, if any, and if the
     *      resulting collection of nodes is empty - the exception will be thrown.
     * @return Non-cancellable future of this execution.
     * @throws GridInterruptedException Subclass of `GridException` thrown if the wait was interrupted.
     * @throws IgniteFutureCancelledException Subclass of `GridException` thrown if computation was cancelled.
     */
    def affinityRunAsync$(cacheName: String, @Nullable affKey: Any, @Nullable r: Run,
        @Nullable p: NF): IgniteFuture[_] = {
        val comp = value.grid().compute(forPredicate(p)).enableAsync()

        comp.affinityRun(cacheName, affKey, toRunnable(r))

        comp.future()
    }
}
