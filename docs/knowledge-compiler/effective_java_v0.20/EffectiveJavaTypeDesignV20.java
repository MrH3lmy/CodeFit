import java.lang.reflect.Modifier;
import java.util.*;

/** Original Java 21 author-smoke scenarios. These are NOT learner graders. */
public final class EffectiveJavaTypeDesignV20 {
    private static int assertions;
    private static void ok(boolean condition, String explanation) {
        assertions++;
        if (!condition) throw new AssertionError("check " + assertions + ": " + explanation);
    }
    // EJ19: illustrate virtual dispatch before a subclass has initialized fields.
    static class UnsafeBase {
        String observed;
        UnsafeBase() { observed = describe(); }
        String describe() { return "base"; }
    }
    static final class EarlyChild extends UnsafeBase {
        final String marker;
        EarlyChild() { marker = "ready"; }
        @Override String describe() { return marker; }
    }
    static class SafeBase {
        final List<String> events = new ArrayList<>();
        SafeBase() { initializeInternal(); }
        private void initializeInternal() { events.add("prepared"); }
        void process() { events.add("process"); }
    }
    static final class SafeChild extends SafeBase {
        final String marker = "ready";
        @Override void process() { events.add(marker); }
    }
    interface Policy { boolean permit(String id); }
    static final class PolicyWrapper implements Policy {
        private final Policy delegate;
        PolicyWrapper(Policy delegate) { this.delegate = Objects.requireNonNull(delegate); }
        @Override public boolean permit(String id) { return id != null && delegate.permit(id); }
    }
    // EJ20: interface primitives + an optional skeleton.
    interface Quota { int used(); int allowance(); default int available() { return Math.max(0, allowance()-used()); } }
    static abstract class AbstractQuota implements Quota {
        final int cap;
        AbstractQuota(int cap) { this.cap = cap; }
        public int allowance() { return cap; }
    }
    static class ExistingFrameworkBase { String parent() { return "framework"; } }
    interface Audit { String audit(); }
    static final class StandaloneQuota extends ExistingFrameworkBase implements Quota, Audit {
        public int used() { return 3; }
        public int allowance() { return 10; }
        public String audit() { return "checked"; }
    }
    static final class SkeletonQuota extends AbstractQuota {
        SkeletonQuota(int cap) { super(cap); }
        public int used() { return 4; }
    }
    // EJ21: adding an interface default does not magically preserve decorator invariants.
    interface BatchCounter {
        void append(int value);
        default void appendBoth(int first, int second) { append(first); append(second); }
    }
    static class RawBatch implements BatchCounter {
        final List<Integer> history = new ArrayList<>();
        public void append(int n) { history.add(n); }
    }
    static final class OldAtomicWrapper implements BatchCounter {
        final RawBatch raw = new RawBatch();
        int locks;
        public synchronized void append(int n) { locks++;raw.append(n); }
    }
    static final class NewAtomicWrapper implements BatchCounter {
        final RawBatch raw = new RawBatch();
        int locks;
        public synchronized void append(int n) { locks++; raw.append(n); }
        @Override public synchronized void appendBoth(int first, int second) {
            locks++; raw.append(first); raw.append(second);
        }
    }
    // EJ22: constants and types are distinct mechanisms.
    static final class ProtocolNumbers {
        static final int MAX_RETRIES = 4;
        static final long TIMEOUT_MS = 1200L;
        private ProtocolNumbers() { throw new AssertionError("not instantiable"); }
    }
    enum Channel { CARD, WALLET, BANK }
    interface MessageHandler { String handle(String msg); }
    static final class Handler implements MessageHandler { public String handle(String msg) { return msg.strip(); } }
    // EJ23: closed domain sum type as opposed to optional-field tagged classes.
    sealed interface Decision permits Approved, Rejected, Delayed {}
    record Approved(String authorizationId) implements Decision {
        Approved { Objects.requireNonNull(authorizationId); if (authorizationId.isBlank()) throw new IllegalArgumentException("blank"); }
    }
    record Rejected(String reason) implements Decision {
        Rejected { Objects.requireNonNull(reason); }
    }
    record Delayed(int retrySeconds) implements Decision {
        Delayed { if (retrySeconds < 1) throw new IllegalArgumentException("retry"); }
    }
    static String status(Decision d) { return switch (d) {
        case Approved a -> "ok:" + a.authorizationId();
        case Rejected r -> "no:" + r.reason();
        case Delayed delay -> "later:" + delay.retrySeconds();
    }; }
    // EJ24: static helper versus genuine inner adapter.
    static final class QueueOwner {
        final String identity;
        QueueOwner(String identity) { this.identity = identity; }
        static final class DetachedTask {
            final long jobId;
            DetachedTask(long jobId) { this.jobId = jobId; }
        }
        final class BoundAdapter { String ownerIdentity() { return QueueOwner.this.identity; } }
    }
    // EJ25: auxiliary types live as private static members under a single top-level owner.
    private static final class Encoder { private Encoder(){} static String encode(String value) { return "(" + value + ")"; } }
    private static final class Decoder { private Decoder(){} static String decode(String value) { return value.substring(1,value.length()-1); } }
    private EffectiveJavaTypeDesignV20(){}
    private static void item19() {
        EarlyChild early=new EarlyChild();
        ok(early.observed==null,"constructor dispatch observes uninitialized extension");
        ok("ready".equals(early.describe()),"postconstruction extension is initialized");
        ok("ready".equals(early.marker),"final extension field eventually initialized");
        SafeChild safe=new SafeChild();
        ok(safe.events.equals(List.of("prepared")),"safe base initialization uses private method");
        safe.process();
        ok(safe.events.equals(List.of("prepared","ready")),"extension hook invoked after construction");
        Policy wrapped=new PolicyWrapper(x->x.startsWith("pay-"));
        ok(wrapped.permit("pay-123"),"wrapper retains contract");
        ok(!wrapped.permit("bad"),"wrapper controls delegation");
        ok(!wrapped.permit(null),"wrapper rejects null");
    }
    private static void item20() {
        StandaloneQuota direct=new StandaloneQuota();
        ok(direct.available()==7,"default computes from interface primitives");
        ok(direct.used()==3,"direct implementer used primitive");
        ok(direct.allowance()==10,"direct implementer allowance primitive");
        ok(direct.parent().equals("framework"),"unrelated parent still supported");
        ok(direct.audit().equals("checked"),"mixin capability supported");
        SkeletonQuota skeleton=new SkeletonQuota(12);
        ok(skeleton.available()==8,"skeletal reusable default");
        ok(skeleton.allowance()==12,"skeletal implementation shares state");
        ok(Quota.class.isInterface(),"interface is a type not a base class");
    }
    private static void item21() {
        OldAtomicWrapper old=new OldAtomicWrapper();
        old.appendBoth(5,6);
        ok(old.raw.history.equals(List.of(5,6)),"default runs two primitive operations");
        ok(old.locks==2,"default calls two synchronized primitive operations separately");
        NewAtomicWrapper modern=new NewAtomicWrapper();
        modern.appendBoth(5,6);
        ok(modern.raw.history.equals(List.of(5,6)),"explicit override preserves payload");
        ok(modern.locks==1,"explicit override protects entire batch once");
        modern.append(7);
        ok(modern.locks==2,"single operation still synchronizes");
        ok(modern.raw.history.size()==3,"history length correct");
        ok(modern.raw.history.get(2)==7,"single update applied");
        ok(old.raw.history.size()==2,"legacy unaffected by modern instance");
    }
    private static void item22() {
        ok(ProtocolNumbers.MAX_RETRIES==4,"constants retained in utility holder");
        ok(ProtocolNumbers.TIMEOUT_MS==1200,"long constant accessible explicitly");
        ok(Modifier.isFinal(ProtocolNumbers.class.getModifiers()),"utility holder closed");
        ok(Modifier.isPrivate(ProtocolNumbers.class.getDeclaredConstructors()[0].getModifiers()),"constructor private");
        ok(Channel.values().length==3,"enum models closed channels");
        ok(Channel.valueOf("WALLET")==Channel.WALLET,"typed enum identity");
        MessageHandler h=new Handler();
        ok(h.handle("  token ").equals("token"),"meaningful interface behavior");
        ok(h instanceof MessageHandler,"contract substitutability");
    }
    private static void item23() {
        Decision d=new Approved("abc");
        ok(status(d).equals("ok:abc"),"approved variant");
        ok(status(new Rejected("risk")).equals("no:risk"),"rejected variant");
        ok(status(new Delayed(10)).equals("later:10"),"retry variant");
        ok(Decision.class.isSealed(),"closed type hierarchy");
        ok(Decision.class.getPermittedSubclasses().length==3,"three known variants");
        try { new Delayed(0); throw new AssertionError("invalid accepted"); }
        catch (IllegalArgumentException expected) { ok(true,"invalid retry rejected"); }
        try { new Approved(" "); throw new AssertionError("invalid accepted"); }
        catch (IllegalArgumentException expected) { ok(true,"blank auth rejected"); }
        ok(((Approved)d).authorizationId().equals("abc"),"typed accessors preserve identity");
    }
    private static void item24() {
        QueueOwner owner=new QueueOwner("node-a");
        QueueOwner.DetachedTask detached=new QueueOwner.DetachedTask(55);
        QueueOwner.BoundAdapter bound=owner.new BoundAdapter();
        ok(detached.jobId==55,"static member independent construction");
        ok(bound.ownerIdentity().equals("node-a"),"real inner adapter uses owner");
        ok(Modifier.isStatic(QueueOwner.DetachedTask.class.getModifiers()),"declared static member");
        ok(!Modifier.isStatic(QueueOwner.BoundAdapter.class.getModifiers()),"declared nonstatic member");
        ok(QueueOwner.DetachedTask.class.getDeclaredFields().length==1,"detached fields contain only jobId");
        ok(Arrays.stream(QueueOwner.BoundAdapter.class.getDeclaredFields()).anyMatch(f->f.getType()==QueueOwner.class),"bound adapter has enclosing reference");
        ok(QueueOwner.DetachedTask.class.getEnclosingClass()==QueueOwner.class,"static lexical owner");
        ok(QueueOwner.BoundAdapter.class.getEnclosingClass()==QueueOwner.class,"inner lexical owner");
    }
    private static void item25() {
        ok(Encoder.encode("x").equals("(x)"),"nested encoder");
        ok(Decoder.decode("(x)").equals("x"),"nested decoder");
        ok(Decoder.decode(Encoder.encode("abc")).equals("abc"),"roundtrip");
        ok(Encoder.class.getEnclosingClass()==EffectiveJavaTypeDesignV20.class,"helper lexical owner");
        ok(Decoder.class.getEnclosingClass()==EffectiveJavaTypeDesignV20.class,"decoder lexical owner");
        ok(Modifier.isPrivate(Encoder.class.getModifiers()),"private helper not public API");
        ok(Modifier.isStatic(Decoder.class.getModifiers()),"helper independently instantiated");
        ok(EffectiveJavaTypeDesignV20.class.getName().equals("EffectiveJavaTypeDesignV20"),"deterministic primary type name");
    }
    public static void main(String[] args) {
        item19();item20();item21();item22();item23();item24();item25();
        ok(assertions==56,"exactly seven times eight author assertions");
        System.out.println("type-design-v20 assertions="+assertions);
    }
}