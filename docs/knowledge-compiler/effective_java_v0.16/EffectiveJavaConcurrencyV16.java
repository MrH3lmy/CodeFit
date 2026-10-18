// Original Java 21 concurrency-language contract fixtures for CodeFit Items 80–84.
// Author smoke assertions only: NOT race-freedom proof, user grading, or publisher code.
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Executable behavioral facts about modern task execution and safe coordination. */
public final class EffectiveJavaConcurrencyV16 {
    private EffectiveJavaConcurrencyV16() {}
    private static int checks;
    private static void ok(boolean value,String id){if(!value)throw new AssertionError(id);checks++;}
    @FunctionalInterface private interface Task {void run() throws Exception;}
    private static void rejects(Class<? extends Throwable> type,Task task,String id){
        try{task.run();}catch(Throwable t){if(type.isInstance(t)){checks++;return;}throw new AssertionError(id+" wrong failure "+t,t);}
        throw new AssertionError(id+" no exception");
    }

    private static void item80() throws Exception {
        ThreadPoolExecutor pool=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
             new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch blocker=new CountDownLatch(1),entered=new CountDownLatch(1);
        try {
            Future<String> first=pool.submit(()->{entered.countDown();blocker.await();return "first";});
            ok(entered.await(2,TimeUnit.SECONDS),"80-first-running");
            Future<String> second=pool.submit(()->"second");
            ok(pool.getQueue().size()==1,"80-one-bounded-queued");
            rejects(RejectedExecutionException.class,()->pool.submit(()->"third"),"80-overcapacity-rejected");
            ok(!second.isDone(),"80-pending-not-complete");
            blocker.countDown();
            ok("first".equals(first.get(2,TimeUnit.SECONDS)),"80-first-result");
            ok("second".equals(second.get(2,TimeUnit.SECONDS)),"80-second-result");
            ok(first.isDone(),"80-first-done");
            ok(second.isDone(),"80-second-done");
            Future<Integer> boom=pool.submit(()->{throw new IllegalArgumentException("fail");});
            rejects(ExecutionException.class,()->boom.get(2,TimeUnit.SECONDS),"80-task-failure-wrapped");
            ExecutionException cause=null;
            try {boom.get(2,TimeUnit.SECONDS);}catch(ExecutionException e){cause=e;}
            ok(cause!=null&&cause.getCause() instanceof IllegalArgumentException,"80-root-cause");
        } finally {blocker.countDown();pool.shutdownNow();ok(pool.awaitTermination(3,TimeUnit.SECONDS),"80-shutdown-complete");}
        ok(pool.isShutdown(),"80-is-shutdown");
        ok(pool.isTerminated(),"80-is-terminated");
        try(ExecutorService virtual=Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> v=virtual.submit(()->Thread.currentThread().isVirtual());
            ok(v.get(2,TimeUnit.SECONDS),"80-virtual-thread-exec");
            ok(virtual.getClass()!=null,"80-executor-type");
        }
    }

    private static void item81() throws Exception {
        ConcurrentHashMap<String,Integer> map=new ConcurrentHashMap<>();
        ok(map.putIfAbsent("k",1)==null,"81-put-if-absent-new");
        ok(map.putIfAbsent("k",2)==1,"81-put-if-absent-existing");
        ok(map.get("k")==1,"81-existing-not-overwritten");
        ok(map.compute("k",(key,old)->old==null?1:old+1)==2,"81-compute-atomic-key");
        ok(map.merge("k",5,Integer::sum)==7,"81-merge");
        ok(map.getOrDefault("missing",9)==9,"81-default-read");
        ok(!map.containsKey("missing"),"81-default-not-inserted");
        ArrayBlockingQueue<Integer> queue=new ArrayBlockingQueue<>(1);
        ok(queue.offer(7),"81-queue-offer");
        ok(!queue.offer(8),"81-queue-full");
        ok(queue.size()==1,"81-queue-size");
        ok(queue.take()==7,"81-take");
        ok(queue.poll()==null,"81-empty-poll");
        ok(queue.remainingCapacity()==1,"81-capacity-restored");
        CountDownLatch ready=new CountDownLatch(1);
        ok(!ready.await(1,TimeUnit.MILLISECONDS),"81-before-countdown");
        ready.countDown();
        ok(ready.await(1,TimeUnit.SECONDS),"81-after-countdown");
        ok(ready.getCount()==0,"81-count-zero");
        ready.countDown();ok(ready.getCount()==0,"81-latch-one-shot");
        Semaphore permits=new Semaphore(1);
        ok(permits.tryAcquire(),"81-first-permit");
        ok(!permits.tryAcquire(),"81-second-permit-blocked");
        permits.release();ok(permits.availablePermits()==1,"81-permit-released");
    }

    /** Fully thread-safe by private synchronization; returns immutable snapshots. */
    private static final class LockedRegistry {
        private final Object lock=new Object();
        private final Map<String,Integer> state=new HashMap<>();
        void bump(String key){synchronized(lock){state.merge(key,1,Integer::sum);}}
        Map<String,Integer> snapshot(){synchronized(lock){return Map.copyOf(state);}}
        boolean usesPrivateMonitor(){return lock!=this;}
    }
    private static void item82() throws Exception {
        LockedRegistry r=new LockedRegistry();
        ok(r.usesPrivateMonitor(),"82-private-lock");
        int workers=3;int each=100;
        CountDownLatch begin=new CountDownLatch(1),done=new CountDownLatch(workers);
        for(int j=0;j<workers;j++)Thread.ofPlatform().start(()->{
            try{begin.await();for(int k=0;k<each;k++)r.bump("hit");}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally{done.countDown();}
        });
        begin.countDown();ok(done.await(3,TimeUnit.SECONDS),"82-all-writers-finished");
        Map<String,Integer> copy=r.snapshot();
        ok(copy.get("hit")==300,"82-synchronized-count");
        ok(copy.size()==1,"82-snapshot-size");
        rejects(UnsupportedOperationException.class,()->copy.put("x",4),"82-unmodifiable-snapshot");
        r.bump("hit");ok(copy.get("hit")==300,"82-snapshot-not-live");
        ok(r.snapshot().get("hit")==301,"82-new-snapshot");
        Map<String,Integer> wrapper=Collections.synchronizedMap(new HashMap<>());
        wrapper.put("one",1);wrapper.put("two",2);
        int sum=0;
        synchronized(wrapper){for(Integer n:wrapper.values())sum+=n;}
        ok(sum==3,"82-explicit-wrapper-monitor");
        ok(wrapper.keySet().size()==2,"82-view-visible");
        ok(wrapper.get("one")==1,"82-wrapper-get");
        ok(!Thread.holdsLock(wrapper),"82-lock-not-retained");
    }

    private static final AtomicInteger HOLDER_BUILDS=new AtomicInteger();
    private static final class Holder {
        private static final String VALUE=makeHolder();
    }
    private static String makeHolder(){HOLDER_BUILDS.incrementAndGet();return "registry-ready";}
    private static String holderValue(){return Holder.VALUE;}
    private static final class InstanceLazy {
        private final AtomicInteger runs=new AtomicInteger();
        private String field;
        synchronized String get(){if(field==null){runs.incrementAndGet();field="initialized";}return field;}
        int builds(){return runs.get();}
    }
    private static final class DoubleChecked {
        private final AtomicInteger runs=new AtomicInteger();
        private volatile Map<String,Integer> cache;
        Map<String,Integer> get(){
            Map<String,Integer> result=cache;
            if(result==null){synchronized(this){result=cache;if(result==null){result=Map.of("value",42);runs.incrementAndGet();cache=result;}}}
            return result;
        }
        int builds(){return runs.get();}
    }
    private static void item83() throws Exception {
        ok(HOLDER_BUILDS.get()==0,"83-holder-not-loaded-before-use");
        ok("registry-ready".equals(holderValue()),"83-first-holder-use");
        ok(HOLDER_BUILDS.get()==1,"83-one-holder-build");
        ok("registry-ready".equals(holderValue()),"83-second-holder-use");
        ok(HOLDER_BUILDS.get()==1,"83-holder-no-rebuild");
        InstanceLazy instance=new InstanceLazy();
        ok(instance.builds()==0,"83-instance-not-eager");
        ok(instance.get().equals("initialized"),"83-instance-first");
        ok(instance.get().equals("initialized"),"83-instance-reuse");
        ok(instance.builds()==1,"83-instance-build-once");
        DoubleChecked dcl=new DoubleChecked();
        int n=4;CountDownLatch go=new CountDownLatch(1),done=new CountDownLatch(n);
        AtomicReference<Throwable> error=new AtomicReference<>();
        for(int i=0;i<n;i++)Thread.ofPlatform().start(()->{
            try{go.await();for(int t=0;t<20;t++)if(dcl.get().get("value")!=42)throw new AssertionError("incorrect value");}
            catch(Throwable e){error.compareAndSet(null,e);}
            finally{done.countDown();}
        });
        go.countDown();ok(done.await(3,TimeUnit.SECONDS),"83-threads-finished");
        ok(error.get()==null,"83-thread-results-correct");
        ok(dcl.builds()==1,"83-volatile-dcl-builds-once");
        ok(dcl.get().get("value")==42,"83-immutable-cache-result");
        rejects(UnsupportedOperationException.class,()->dcl.get().put("x",1),"83-published-immutable");
    }

    private static void item84() throws Exception {
        ArrayBlockingQueue<String> work=new ArrayBlockingQueue<>(1);
        CountDownLatch complete=new CountDownLatch(1);
        AtomicReference<String> observed=new AtomicReference<>();
        AtomicBoolean interrupted=new AtomicBoolean();
        Thread worker=Thread.ofPlatform().start(()->{
            try { observed.set(work.take()); complete.countDown();work.take(); }
            catch(InterruptedException e){Thread.currentThread().interrupt();interrupted.set(Thread.currentThread().isInterrupted());}
        });
        try {
            ok(work.offer("message",2,TimeUnit.SECONDS),"84-offer-message");
            ok(complete.await(2,TimeUnit.SECONDS),"84-consumer-progress");
            ok("message".equals(observed.get()),"84-take-delivered");
            ok(work.isEmpty(),"84-consumed-queue-empty");
            ok(work.remainingCapacity()==1,"84-queue-capacity");
        } finally {worker.interrupt();worker.join(2000);}
        ok(!worker.isAlive(),"84-worker-stopped");
        ok(interrupted.get(),"84-interrupt-restored");
        ok(!Thread.interrupted(),"84-test-thread-not-interrupted");
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<Integer> one=pool.submit(()->40+2);
            ok(one.get(2,TimeUnit.SECONDS)==42,"84-scheduled-work-complete");
            ok(one.isDone(),"84-future-done");
        } finally {pool.shutdownNow();ok(pool.awaitTermination(2,TimeUnit.SECONDS),"84-executor-stopped");}
        ok(pool.isTerminated(),"84-executor-terminated");
    }

    /** @param args unused command-line options @throws Exception if test setup unexpectedly fails */
    public static void main(String[] args) throws Exception {
        item80();item81();item82();item83();item84();
        System.out.println("CodeFit original Java21 concurrency assertions="+checks);
    }
}
