// Original CodeFit Java 21 author-level checks for Effective Java Items 75–79.
// NOT publisher source; NOT a learner grader or production component.
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class EffectiveJavaReliabilityV15 {
    private EffectiveJavaReliabilityV15() {}
    private static int checks;
    private static void ok(boolean condition, String id) {
        if (!condition) throw new AssertionError(id);
        checks++;
    }
    private interface Throwing { void run() throws Exception; }
    private static void fails(Class<? extends Throwable> type, Throwing operation, String id) {
        try { operation.run(); }
        catch (Throwable t) {
            if (type.isInstance(t)) { checks++; return; }
            throw new AssertionError(id + " wrong error " + t, t);
        }
        throw new AssertionError(id + " expected failure missing");
    }
    private static final class RangeViolation extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        private final int index, lowerInclusive, upperExclusive;
        RangeViolation(int index, int lowerInclusive, int upperExclusive) {
            super("index="+index+" allowed=["+lowerInclusive+","+upperExclusive+")");
            if (lowerInclusive > upperExclusive) throw new IllegalArgumentException("inverted bounds");
            this.index=index;this.lowerInclusive=lowerInclusive;this.upperExclusive=upperExclusive;
        }
        int index() { return index; }
        int lower() { return lowerInclusive; }
        int upper() { return upperExclusive; }
    }
    private static String validateIndex(int index, int lower, int upper, String secret) {
        Objects.requireNonNull(secret, "secret"); // Required but NEVER formatted into an error
        if(index<lower || index>=upper) throw new RangeViolation(index,lower,upper);
        return "valid:"+index;
    }
    private static void item75() {
        ok(validateIndex(2,0,3,"top-secret").equals("valid:2"),"75-valid");
        RangeViolation violation=null;
        try { validateIndex(3,0,3,"bearer-secret"); } catch (RangeViolation e) { violation=e; }
        ok(violation!=null,"75-range-throws");
        ok(violation.index()==3,"75-index-field");
        ok(violation.lower()==0,"75-lower-field");
        ok(violation.upper()==3,"75-upper-field");
        ok(violation.getMessage().contains("index=3"),"75-index-detail");
        ok(violation.getMessage().contains("[0,3)"),"75-half-open-detail");
        ok(!violation.toString().contains("bearer-secret"),"75-no-secret-toString");
        ok(!violation.getMessage().contains("bearer-secret"),"75-no-secret-message");
        fails(RangeViolation.class,()->validateIndex(-1,0,3,"key"),"75-negative");
        fails(RangeViolation.class,()->validateIndex(Integer.MAX_VALUE,0,3,"key"),"75-extreme");
        fails(NullPointerException.class,()->validateIndex(0,0,3,null),"75-required-arg");
        fails(IllegalArgumentException.class,()->new RangeViolation(2,5,4),"75-bounds-inverted");
        ok(new RangeViolation(Integer.MIN_VALUE,0,4).index()==Integer.MIN_VALUE,"75-min-index");
    }
    private record Rule(String name,int limit) {
        Rule {Objects.requireNonNull(name,"name");if(name.isBlank()||limit<0)throw new IllegalArgumentException("bad rule");}
    }
    private static final class RuleRegistry {
        private final AtomicReference<Map<String,Rule>> current=new AtomicReference<>(Map.of());
        Map<String,Rule> snapshot(){return current.get();}
        void replace(List<Rule> incoming) {
            Objects.requireNonNull(incoming,"incoming");
            Map<String,Rule> next=new LinkedHashMap<>();
            for(Rule rule:incoming) {
                Objects.requireNonNull(rule,"rule");
                if(next.putIfAbsent(rule.name(),rule)!=null)throw new IllegalArgumentException("duplicate rule");
            }
            current.set(Map.copyOf(next)); // Single publication after complete validation
        }
    }
    private static void item76(){
        RuleRegistry reg=new RuleRegistry();
        ok(reg.snapshot().isEmpty(),"76-initial-empty");
        reg.replace(List.of(new Rule("merchant",8)));
        Map<String,Rule> first=reg.snapshot();
        ok(first.get("merchant").limit()==8,"76-published");
        fails(IllegalArgumentException.class,()->reg.replace(List.of(new Rule("x",2),new Rule("x",3))),"76-duplicate");
        ok(reg.snapshot()==first,"76-duplicate-no-write");
        fails(IllegalArgumentException.class,()->reg.replace(List.of(new Rule("",3))),"76-bad-rule");
        ok(reg.snapshot()==first,"76-bad-rule-no-write");
        fails(NullPointerException.class,()->reg.replace(null),"76-null-batch");
        ok(reg.snapshot()==first,"76-null-batch-no-write");
        fails(NullPointerException.class,()->reg.replace(Arrays.asList(new Rule("fine",1),null)),"76-null-member");
        ok(reg.snapshot()==first,"76-null-member-no-write");
        reg.replace(List.of(new Rule("other",2),new Rule("next",4)));
        Map<String,Rule> second=reg.snapshot();
        ok(second.size()==2,"76-two-rules");
        ok(first.size()==1,"76-previous-snapshot-unchanged");
        ok(!second.containsKey("merchant"),"76-replacement-not-merge");
        fails(UnsupportedOperationException.class,()->second.put("wrong",new Rule("wrong",5)),"76-snapshot-immutable");
        fails(UnsupportedOperationException.class,()->first.clear(),"76-prior-snapshot-immutable");
        reg.replace(List.of());ok(reg.snapshot().isEmpty(),"76-empty-replacement");
    }
    private static String mandatory(boolean offline) throws IOException {
        if (offline) throw new IOException("remote unavailable");
        return "committed";
    }
    private static String optional(boolean offline) {
        try { if(offline)throw new IOException("optional enrichment unavailable");return "enhanced"; }
        catch(IOException ignored) { // A documented safe default for NONESSENTIAL enrichment only.
            return "base";
        }
    }
    private static final class BrokenClose implements AutoCloseable {
        public void close() throws IOException { throw new IOException("close failed"); }
    }
    private static void item77() throws Exception {
        ok(mandatory(false).equals("committed"),"77-mandatory-success");
        fails(IOException.class,()->mandatory(true),"77-mandatory-outage-propagated");
        ok(optional(false).equals("enhanced"),"77-optional-success");
        ok(optional(true).equals("base"),"77-optional-safe-fallback");
        IOException observed=null;
        try { mandatory(true); } catch(IOException e) { observed=e; }
        ok(observed!=null,"77-cause-observed");
        ok(observed.getMessage().contains("remote"),"77-diagnostic-context");
        IOException primary=null;
        try (BrokenClose c=new BrokenClose()) { Objects.requireNonNull(c); throw new IOException("primary failed"); }
        catch (IOException e) {primary=e;}
        ok(primary!=null,"77-primary-exception");
        ok(primary.getMessage().equals("primary failed"),"77-primary-preserved");
        ok(primary.getSuppressed().length==1,"77-close-suppressed");
        ok(primary.getSuppressed()[0] instanceof IOException,"77-suppressed-type");
        AtomicBoolean flag=new AtomicBoolean();
        Thread worker=Thread.ofPlatform().start(()->{
            try { throw new InterruptedException("cancel"); }
            catch(InterruptedException e) { Thread.currentThread().interrupt();flag.set(Thread.currentThread().isInterrupted()); }
        });
        worker.join(2000);ok(!worker.isAlive(),"77-worker-completed");
        ok(flag.get(),"77-interrupt-restored");
        fails(IllegalStateException.class,()->{throw new IllegalStateException("unexpected");},"77-unexpected-propagation");
    }
    private static final class Meter {
        private final AtomicLong count=new AtomicLong();
        long increment(){return count.incrementAndGet();}
        long current(){return count.get();}
    }
    private static final class SwitchFlag {volatile boolean stop;}
    private static void item78() throws Exception {
        Meter meter=new Meter();
        ok(meter.current()==0,"78-start-zero");
        ok(meter.increment()==1,"78-first-increment");
        ok(meter.increment()==2,"78-second-increment");
        int workers=4,per=250;
        CountDownLatch start=new CountDownLatch(1),done=new CountDownLatch(workers);
        for(int j=0;j<workers;j++)Thread.ofPlatform().start(()->{
            try{start.await();for(int i=0;i<per;i++)meter.increment();}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally{done.countDown();}
        });
        start.countDown();ok(done.await(3,TimeUnit.SECONDS),"78-concurrent-finished");
        ok(meter.current()==1002,"78-no-lost-atomic-increments");
        AtomicReference<Map<String,Integer>> snapshot=new AtomicReference<>(Map.of("one",1));
        Map<String,Integer> old=snapshot.get();
        snapshot.set(Map.of("one",1,"two",2));
        ok(old.size()==1,"78-old-snapshot");
        ok(snapshot.get().size()==2,"78-published-snapshot");
        fails(UnsupportedOperationException.class,()->snapshot.get().put("x",3),"78-immutable-publication");
        SwitchFlag s=new SwitchFlag();
        AtomicBoolean exited=new AtomicBoolean();
        Thread stopper=Thread.ofPlatform().start(()->{
            while(!s.stop && !Thread.currentThread().isInterrupted())Thread.onSpinWait();
            exited.set(true);
        });
        s.stop=true;stopper.join(2000);
        if(stopper.isAlive()){stopper.interrupt();stopper.join(2000);}
        ok(!stopper.isAlive(),"78-cooperative-stop-completed");
        ok(exited.get(),"78-cooperative-stop-observed");
        AtomicInteger number=new AtomicInteger(Integer.MAX_VALUE);
        ok(number.get()==Integer.MAX_VALUE,"78-atomic-read");
        ok(number.compareAndSet(Integer.MAX_VALUE,0),"78-cas-transition");
        ok(number.get()==0,"78-cas-value");
        ConcurrentHashMap<String,Integer> map=new ConcurrentHashMap<>();
        map.compute("key",(k,v)->v==null?1:v+1);
        map.compute("key",(k,v)->v==null?1:v+1);
        ok(map.get("key")==2,"78-map-compute");
        ok(!map.containsKey("other"),"78-map-distinct-key");
    }
    private static final class ListenerRegistry {
        private final Object monitor=new Object();
        private final List<Consumer<Integer>> listeners=new ArrayList<>();
        void add(Consumer<Integer> l){Objects.requireNonNull(l);synchronized(monitor){listeners.add(l);}}
        void remove(Consumer<Integer> l){synchronized(monitor){listeners.remove(l);}}
        int registered(){synchronized(monitor){return listeners.size();}}
        boolean ownedByCurrentThread(){return Thread.holdsLock(monitor);}
        void notifyValue(int n) {
            List<Consumer<Integer>> snapshot;
            synchronized(monitor){snapshot=List.copyOf(listeners);}
            for(Consumer<Integer> listener:snapshot)listener.accept(n); // No callback under monitor
        }
    }
    private static void item79() {
        ListenerRegistry registry=new ListenerRegistry();
        AtomicInteger seen=new AtomicInteger();AtomicBoolean held=new AtomicBoolean(true);
        @SuppressWarnings("unchecked") Consumer<Integer>[] self=(Consumer<Integer>[])new Consumer<?>[1];
        self[0]=n->{seen.addAndGet(n);held.set(registry.ownedByCurrentThread());registry.remove(self[0]);};
        registry.add(self[0]);
        ok(registry.registered()==1,"79-subscribed");
        registry.notifyValue(3);
        ok(seen.get()==3,"79-first-delivery");
        ok(!held.get(),"79-callback-outside-lock");
        ok(registry.registered()==0,"79-self-removal");
        registry.notifyValue(10);ok(seen.get()==3,"79-self-not-repeated");
        AtomicInteger later=new AtomicInteger();
        Consumer<Integer> a=v->later.addAndGet(v);
        Consumer<Integer> bad=v->{throw new IllegalStateException("callback failure");};
        registry.add(a);registry.add(bad);
        fails(IllegalStateException.class,()->registry.notifyValue(4),"79-callback-error-exposed");
        ok(later.get()==4,"79-earlier-callback-completed");
        ok(registry.registered()==2,"79-registrations-preserved");
        registry.remove(bad);
        registry.notifyValue(2);ok(later.get()==6,"79-following-healthy-delivery");
        ok(registry.registered()==1,"79-removal");
        CopyOnWriteArrayList<String> cow=new CopyOnWriteArrayList<>(List.of("a","b"));
        Iterator<String> snapshot=cow.iterator();cow.add("c");
        ok(snapshot.next().equals("a"),"79-cow-snapshot-first");
        ok(snapshot.next().equals("b"),"79-cow-snapshot-second");
        ok(!snapshot.hasNext(),"79-cow-no-new-member-in-old-iteration");
        ok(cow.size()==3,"79-cow-new-list-size");
        fails(UnsupportedOperationException.class,snapshot::remove,"79-cow-iterator-no-remove");
    }
    public static void main(String[] args) throws Exception {
        item75();item76();item77();item78();item79();
        System.out.println("CodeFit original Java21 reliability assertions="+checks);
    }
}
