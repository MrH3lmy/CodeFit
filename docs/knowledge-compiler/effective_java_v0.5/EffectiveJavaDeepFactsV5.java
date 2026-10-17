import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Original offline verification fixtures for eight educational Effective Java v0.5 drafts.
 * Standalone language-fact smoke tests, NOT a secure evaluator or production mission grader.
 */
public final class EffectiveJavaDeepFactsV5 {
    private static int checked = 0;
    private static void verify(boolean condition, String meaning) {
        if (!condition) throw new AssertionError(meaning);
        checked++;
    }
    private static void fails(Class<? extends Throwable> type, Runnable task, String meaning) {
        try {task.run();throw new AssertionError("expected error: " + meaning);}
        catch (Throwable t) {
            if(t instanceof AssertionError) throw (AssertionError)t;
            verify(type.isInstance(t), meaning + ", got " + t);
        }
    }
    sealed interface RetryPlan permits NoRetry, FixedDelay, ExponentialDelay {}
    record NoRetry() implements RetryPlan {}
    record FixedDelay(int ms) implements RetryPlan {}
    record ExponentialDelay(int firstMs, int capMs) implements RetryPlan {}
    static final class Plans {
        private Plans() {throw new AssertionError("utility class");}
        private static final NoRetry NONE = new NoRetry();
        static RetryPlan none() {return NONE;}
        static RetryPlan fixed(int ms) {
            if (ms <= 0) throw new IllegalArgumentException("ms");
            return new FixedDelay(ms);
        }
        static RetryPlan exponential(int start, int cap) {
            if(start<=0||cap<start)throw new IllegalArgumentException("range");
            return new ExponentialDelay(start,cap);
        }
    }
    static void item1() {
        verify(Plans.none() == Plans.none(),"documented shared sentinel identity");
        verify(Plans.fixed(250).equals(Plans.fixed(250)),"same value across distinct factory invocations");
        verify(Plans.fixed(250) != Plans.fixed(250),"factory may allocate new objects");
        verify(Plans.fixed(250) instanceof FixedDelay,"factory returns implementation through API");
        verify(Plans.exponential(25,250) instanceof ExponentialDelay,"different factory names have different intent");
        fails(IllegalArgumentException.class,()->Plans.fixed(0),"no zero fixed delay");
        fails(IllegalArgumentException.class,()->Plans.exponential(500,100),"invalid range");
    }
    static final class NotificationPolicy {
        private final String destination;
        private final int retries;
        private final int openHour,closeHour;
        private final List<String> headers;
        private NotificationPolicy(Builder builder) {
            this.destination=Objects.requireNonNull(builder.destination);
            this.retries=builder.retries;
            this.openHour=builder.openHour;
            this.closeHour=builder.closeHour;
            this.headers=List.copyOf(builder.headers);
            if(retries<0||openHour<0||openHour>23||closeHour<0||closeHour>24||openHour>=closeHour)
                throw new IllegalArgumentException("invalid notification policy");
        }
        static final class Builder {
            private final String destination;
            private int retries,openHour=9,closeHour=18;
            private List<String> headers=new ArrayList<>();
            Builder(String destination){this.destination=destination;}
            Builder retries(int x){this.retries=x;return this;}
            Builder hours(int from,int to){this.openHour=from;this.closeHour=to;return this;}
            Builder header(String s){this.headers.add(s);return this;}
            NotificationPolicy build(){return new NotificationPolicy(this);}
        }
        int retries(){return retries;} List<String> headers(){return headers;}
    }
    static void item2() {
        NotificationPolicy.Builder b=new NotificationPolicy.Builder("notify:customer").retries(2).hours(8,20).header("trace-a");
        NotificationPolicy n=b.build();
        b.retries(99).header("trace-b");
        verify(n.retries()==2,"snapshot preserves old builder state");
        verify(n.headers().equals(List.of("trace-a")),"snapshot freezes collection state");
        fails(UnsupportedOperationException.class,()->n.headers().add("no"),"built collection is unmodifiable");
        fails(IllegalArgumentException.class,()->new NotificationPolicy.Builder("x").hours(22,3).build(),"invalid cross-field interval");
        fails(IllegalArgumentException.class,()->new NotificationPolicy.Builder("x").retries(-1).build(),"invalid numeric state");
        verify(new NotificationPolicy.Builder("x").build().retries()==0,"default optional retry count");
    }
    static final class TrackedClose implements AutoCloseable {
        private final String name;private final List<String> history;private final boolean throwsOnClose;
        TrackedClose(String name,List<String> h,boolean fail){this.name=name;this.history=h;throwsOnClose=fail;}
        @Override public void close(){history.add(name);if(throwsOnClose)throw new IllegalStateException("closing-"+name);}
    }
    static void item9() {
        List<String> closeOrder=new ArrayList<>(); Throwable failure=null;
        try (TrackedClose first=new TrackedClose("first",closeOrder,true);
             TrackedClose second=new TrackedClose("second",closeOrder,true)) {
            throw new IllegalArgumentException("import-body");
        } catch (Throwable e) {failure=e;}
        verify(failure instanceof IllegalArgumentException,"body failure primary");
        verify(closeOrder.equals(List.of("second","first")),"close in reverse declaration order");
        verify(failure.getSuppressed().length==2,"both closing failures suppressed");
        verify(failure.getSuppressed()[0].getMessage().equals("closing-second"),"first suppressed matches reversed closing order");
        verify(failure.getSuppressed()[1].getMessage().equals("closing-first"),"second suppressed retained");
        Throwable closeOnly=null;
        try(TrackedClose first=new TrackedClose("only",new ArrayList<>(),true)){}catch(Throwable e){closeOnly=e;}
        verify(closeOnly instanceof IllegalStateException,"close exception primary when body succeeds");
    }
    static final class EventId {
        private final String provider;private final long sequence;
        EventId(String provider,long sequence){this.provider=Objects.requireNonNull(provider);this.sequence=sequence;}
        @Override public boolean equals(Object other){
            if(this==other)return true;
            if(!(other instanceof EventId e))return false;
            return sequence==e.sequence && provider.equals(e.provider);
        }
        @Override public int hashCode(){return 31*provider.hashCode()+Long.hashCode(sequence);}
    }
    static void item10() {
        EventId a=new EventId("bank",3), b=new EventId("bank",3), c=new EventId("bank",3), d=new EventId("bank",4);
        verify(a.equals(a),"reflexivity");
        verify(a.equals(b)&&b.equals(a),"symmetry");
        verify(a.equals(b)&&b.equals(c)&&a.equals(c),"transitivity");
        verify(a.equals(b)&&a.equals(b),"consistency in stable state");
        verify(!a.equals(null),"null not equal");
        verify(!a.equals("bank-3"),"unrelated type not equal");
        verify(!a.equals(d),"different sequence not equal");
        verify(!a.equals(new EventId("other",3)),"different provider not equal");
    }
    record CollisionKey(String id) { @Override public int hashCode(){return 7;} }
    static void item11() {
        EventId a=new EventId("bank",8), b=new EventId("bank",8);
        verify(a.hashCode()==b.hashCode(),"equal event IDs share hash");
        Map<EventId,String> lookedUp=new HashMap<>();lookedUp.put(a,"settled");
        verify("settled".equals(lookedUp.get(b)),"new equal key finds existing map entry");
        CollisionKey x=new CollisionKey("x"),y=new CollisionKey("y");
        verify(x.hashCode()==y.hashCode(),"unequal instances may have same hash");
        verify(!x.equals(y),"collision does not imply equal");
        Map<CollisionKey,Integer> m=new HashMap<>();m.put(x,11);m.put(y,22);
        verify(m.get(x)==11&&m.get(y)==22,"hash map disambiguates colliding keys");
        verify(m.size()==2,"hash collisions do not merge different keys");
    }
    static final class ImmutableSnapshot {
        private final String id;private final int[] tierLimits;
        ImmutableSnapshot(String id,int[] input){this.id=Objects.requireNonNull(id);tierLimits=input.clone();}
        int[] tiers(){return tierLimits.clone();}String id(){return id;}
    }
    static void item17() {
        int[] input={1,2,3};ImmutableSnapshot snapshot=new ImmutableSnapshot("quote-1",input);
        input[0]=777;
        verify(snapshot.tiers()[0]==1,"input alias cannot mutate immutable snapshot");
        int[] leaked=snapshot.tiers();leaked[2]=888;
        verify(snapshot.tiers()[2]==3,"output array cannot mutate internal snapshot");
        verify("quote-1".equals(snapshot.id()),"stable field retained");
        verify(!List.of("a").isEmpty(),"immutable standard library value sample");
    }
    static final class AuditSet extends AbstractSet<String> {
        private final Set<String> delegate=new HashSet<>();private int successfulAdds;
        @Override public Iterator<String> iterator(){return delegate.iterator();}
        @Override public int size(){return delegate.size();}
        @Override public boolean add(String value){
            boolean added=delegate.add(value); if(added)successfulAdds++;return added;
        }
        int successfulAdds(){return successfulAdds;}
    }
    static void item18() {
        AuditSet a=new AuditSet();
        verify(a.add("invoice-a"),"first add succeeds");
        verify(!a.add("invoice-a"),"duplicate add not counted");
        verify(a.addAll(List.of("invoice-a","invoice-b","invoice-c")),"bulk add inserts new IDs");
        verify(a.successfulAdds()==3,"wrapping counts successful additions exactly");
        verify(a.size()==3,"delegate tracks unique identifiers");
        verify(!a.addAll(List.of("invoice-a","invoice-b")),"duplicate-only bulk add reports no modification");
        verify(a.successfulAdds()==3,"duplicate-only bulk add not recounted");
    }
    static final class TimeWindow {
        private final Date from,to;
        TimeWindow(Date start,Date end){
            from=new Date(Objects.requireNonNull(start).getTime());
            to=new Date(Objects.requireNonNull(end).getTime());
            if(from.after(to))throw new IllegalArgumentException("reversed range");
        }
        Date from(){return new Date(from.getTime());}
        Date to(){return new Date(to.getTime());}
    }
    static void item50() {
        Date start=new Date(1000),end=new Date(2000);TimeWindow w=new TimeWindow(start,end);
        start.setTime(5000);end.setTime(100);
        verify(w.from().getTime()==1000,"caller mutation of start cannot affect owner");
        verify(w.to().getTime()==2000,"caller mutation of end cannot affect owner");
        Date returned=w.to();returned.setTime(-10);
        verify(w.to().getTime()==2000,"getter does not expose mutable internals");
        fails(IllegalArgumentException.class,()->new TimeWindow(new Date(4),new Date(2)),"reject reversed dates");
        verify(new TimeWindow(new Date(2),new Date(2)).from().equals(new Date(2)),"equal endpoint permitted");
    }
    public static void main(String[] args){
        item1();item2();item9();item10();item11();item17();item18();item50();
        System.out.println("JAVA21_V5_CHECKS_PASS="+checked);
    }
}