// Original CodeFit Java 21 exception-contract behavior tests, NOT publisher examples or learner grading.
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Small executable examples verifying Java 21 exception contracts. */
public final class EffectiveJavaExceptionsV14 {
    private EffectiveJavaExceptionsV14() {}
    private static int checks;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void fails(Class<? extends Throwable> wanted, Throwing body, String name) {
        try { body.run(); }
        catch (Throwable t) {
            if (wanted.isInstance(t)) { checks++; return; }
            throw new AssertionError(name + " wrong exception " + t,t);
        }
        throw new AssertionError(name + " expected exception missing");
    }
    /** Example checked failure for a caller-actionable retry. */
    private static final class RecoverableGatewayException extends Exception {
        private static final long serialVersionUID=1L;
        RecoverableGatewayException(String text){ super(text); }
        RecoverableGatewayException(String text,Throwable cause){ super(text,cause); }
    }
    private static final class Gateway {
        private int calls;
        String send(String request, boolean available) throws RecoverableGatewayException {
            Objects.requireNonNull(request,"request");
            if (request.isBlank()) throw new IllegalArgumentException("blank request");
            calls++;
            if (!available) throw new RecoverableGatewayException("temporarily offline");
            return "accepted:"+request;
        }
    }
    private static void item70() throws Exception {
        Gateway g=new Gateway();
        check(g.send("id-1",true).equals("accepted:id-1"),"70-success");
        fails(NullPointerException.class,()->g.send(null,true),"70-null-contract");
        fails(IllegalArgumentException.class,()->g.send(" ",true),"70-invalid-argument");
        fails(RecoverableGatewayException.class,()->g.send("id-2",false),"70-checked-outage");
        check(g.calls==2,"70-invalid-does-not-dispatch");
        AtomicInteger recovery=new AtomicInteger();
        try {g.send("id-3",false);}catch(RecoverableGatewayException e){recovery.incrementAndGet();}
        check(recovery.get()==1,"70-actionable-catch");
        check(g.calls==3,"70-call-count");
        check(new RecoverableGatewayException("unavailable") instanceof Exception,"70-checked-kind");
        check(!(new IllegalArgumentException() instanceof java.io.Serializable) || IllegalArgumentException.class.getSuperclass()==RuntimeException.class,"70-runtime-kind");
        check(RuntimeException.class.isAssignableFrom(IllegalStateException.class),"70-state-unchecked");
        check(Error.class.isAssignableFrom(LinkageError.class),"70-error-hierarchy");
        check(!RuntimeException.class.isAssignableFrom(RecoverableGatewayException.class),"70-checked-not-runtime");
        check(Objects.requireNonNull("present").equals("present"),"70-require-nonnull");
    }
    private static Optional<String> findLocal(String account) {
        Objects.requireNonNull(account,"account");
        return account.equals("known")?Optional.of("value"):Optional.empty();
    }
    private static Optional<String> findRemote(String account,boolean healthy) throws IOException {
        if (!healthy) throw new IOException("network unavailable");
        return findLocal(account);
    }
    private static void item71() throws Exception {
        check(findLocal("absent").isEmpty(),"71-normal-miss");
        check(findLocal("known").orElseThrow().equals("value"),"71-normal-present");
        fails(NullPointerException.class,()->findLocal(null),"71-null-not-absence");
        fails(IOException.class,()->findRemote("missing",false),"71-outage-not-empty");
        check(findRemote("missing",true).isEmpty(),"71-healthy-missing");
        check(findRemote("known",true).isPresent(),"71-healthy-present");
        AtomicInteger fallback=new AtomicInteger();
        check(findLocal("known").orElseGet(()->"backup-"+fallback.incrementAndGet()).equals("value"),"71-lazy-hit");
        check(fallback.get()==0,"71-hit-no-fallback");
        check(findLocal("missing").orElseGet(()->"backup-"+fallback.incrementAndGet()).equals("backup-1"),"71-lazy-miss");
        check(fallback.get()==1,"71-miss-one-fallback");
        int retries=0;try{findRemote("known",false);}catch(IOException e){retries++;}
        check(retries==1,"71-recovery-decision");
        check(findLocal("missing").orElse("missing").equals("missing"),"71-absence-policy");
        check(findLocal("known").map(String::length).orElse(0)==5,"71-optional-map");
        fails(NoSuchElementException.class,()->findLocal("missing").orElseThrow(),"71-explicit-missing-requirement");
    }
    /** A mutable owner in which state, indices and capability failures differ. */
    private static final class BoundedView {
        private boolean closed;
        private final List<String> records=List.of("one","two");
        String at(int index) {
            if(closed) throw new IllegalStateException("view closed");
            Objects.checkIndex(index,records.size());
            return records.get(index);
        }
        void close(){closed=true;}
        void add(String record){throw new UnsupportedOperationException("immutable view");}
    }
    private static void item72(){
        BoundedView view=new BoundedView();
        check(view.at(0).equals("one"),"72-first");
        check(view.at(1).equals("two"),"72-second");
        fails(IndexOutOfBoundsException.class,()->view.at(-1),"72-negative-index");
        fails(IndexOutOfBoundsException.class,()->view.at(2),"72-exclusive-bound");
        fails(UnsupportedOperationException.class,()->view.add("three"),"72-unsupported-mutation");
        check(List.of(1,2,3).contains(3),"72-standard-list-positive");
        fails(NullPointerException.class,()->Objects.requireNonNull(null),"72-standard-null");
        fails(IllegalArgumentException.class,()->Integer.parseInt("oops"),"72-standard-malformed-input");
        check(Objects.checkFromIndexSize(2,0,2)==2,"72-zero-length-end");
        fails(IndexOutOfBoundsException.class,()->Objects.checkFromIndexSize(2,1,2),"72-window-too-long");
        check(!view.closed,"72-state-before-close");
        view.close();check(view.closed,"72-state-after-close");
        fails(IllegalStateException.class,()->view.at(0),"72-closed-state");
        ArrayList<String> moving=new ArrayList<>(List.of("a","b"));
        Iterator<String> it=moving.iterator();it.next();moving.add("c");
        fails(ConcurrentModificationException.class,it::next,"72-fail-fast-iterator-contract");
        fails(UnsupportedOperationException.class,()->List.of(4).add(5),"72-immutable-list");
        check(IllegalStateException.class.getSuperclass()==RuntimeException.class,"72-runtime-standard-type");
    }
    private static final class PositionLookup {
        private final List<String> items;
        PositionLookup(List<String> items){this.items=List.copyOf(items);}
        String at(int index) {
            if(index<0)throw new IndexOutOfBoundsException("negative index");
            Iterator<String> it=items.iterator();
            try {for(int i=0;i<=index;i++){String current=it.next();if(i==index)return current;}}
            catch(NoSuchElementException cause){
                IndexOutOfBoundsException exception=new IndexOutOfBoundsException("index unavailable");
                exception.initCause(cause);
                throw exception;
            }
            throw new AssertionError("unreachable");
        }
    }
    private static void item73(){
        PositionLookup lookup=new PositionLookup(List.of("x","y"));
        check(lookup.at(0).equals("x"),"73-index-first");
        check(lookup.at(1).equals("y"),"73-index-second");
        fails(IndexOutOfBoundsException.class,()->lookup.at(2),"73-abstraction-appropriate");
        IndexOutOfBoundsException wrapped=null;
        try{lookup.at(3);}catch(IndexOutOfBoundsException e){wrapped=e;}
        check(wrapped!=null,"73-wrapped-not-null");
        check(wrapped.getCause() instanceof NoSuchElementException,"73-preserved-original-cause");
        check(wrapped.getMessage().contains("index"),"73-public-index-meaning");
        check(wrapped.getCause().getCause()==null,"73-cause-leaf");
        IndexOutOfBoundsException negative=null;
        try{lookup.at(-1);}catch(IndexOutOfBoundsException e){negative=e;}
        check(negative!=null&&negative.getCause()==null,"73-precondition-no-false-cause");
        check(lookup.at(0).equals("x"),"73-valid-path-unchanged");
        fails(IndexOutOfBoundsException.class,()->new PositionLookup(List.of()).at(0),"73-empty-case");
        check(new PositionLookup(List.of("one")).at(0).equals("one"),"73-singleton");
        RecoverableGatewayException cause=new RecoverableGatewayException("offline");
        RecoverableGatewayException translated=new RecoverableGatewayException("provider unavailable",cause);
        check(translated.getCause()==cause,"73-checked-cause-chain");
        check(translated.getMessage().contains("provider"),"73-new-abstraction-message");
        check(!(translated.getCause() instanceof RuntimeException),"73-no-misclassified-programming-error");
    }
    /** Public service with a documented contract for both checked and unchecked failures. */
    public static final class DocumentedPageService {
        private DocumentedPageService() {}
        /** Return the requested page as a defensive immutable list.
         * @param key non-null account key
         * @param offset inclusive zero-based offset
         * @param offline whether the fake remote dependency is unavailable
         * @return an unmodifiable list, empty when the valid query has no rows
         * @throws NullPointerException if key is null
         * @throws IndexOutOfBoundsException if offset is negative
         * @throws IOException if the remote operation cannot be completed
         */
        public static List<String> load(String key,int offset,boolean offline) throws IOException {
            Objects.requireNonNull(key,"key");
            if(offset<0)throw new IndexOutOfBoundsException("negative page offset");
            if(offline)throw new IOException("provider disconnected");
            if(key.equals("known")&&offset==0)return List.of("one","two");
            return List.of();
        }
    }
    private static void item74() throws Exception {
        check(DocumentedPageService.load("known",0,false).equals(List.of("one","two")),"74-present");
        check(DocumentedPageService.load("absent",0,false).isEmpty(),"74-empty-normal");
        check(DocumentedPageService.load("known",10,false).isEmpty(),"74-valid-high-page");
        fails(NullPointerException.class,()->DocumentedPageService.load(null,0,false),"74-null-contract");
        fails(IndexOutOfBoundsException.class,()->DocumentedPageService.load("known",-1,false),"74-index-contract");
        fails(IOException.class,()->DocumentedPageService.load("known",0,true),"74-checked-failure");
        check(DocumentedPageService.load("known",0,false).size()==2,"74-correct-count");
        fails(UnsupportedOperationException.class,()->DocumentedPageService.load("known",0,false).add("mutate"),"74-defensive-result");
        check(DocumentedPageService.load("absent",20,false).isEmpty(),"74-valid-empty-page");
        check(IOException.class.getSuperclass()==Exception.class,"74-checked-parent");
        check(RuntimeException.class.isAssignableFrom(IndexOutOfBoundsException.class),"74-unchecked-parent");
        check(DocumentedPageService.class.getDeclaredMethods().length>=1,"74-public-documentable-api");
        check(DocumentedPageService.load("known",0,false).get(0).equals("one"),"74-deterministic-first");
        check(DocumentedPageService.load("known",0,false).get(1).equals("two"),"74-deterministic-last");
        check(!DocumentedPageService.load("known",0,false).isEmpty(),"74-nonempty");
    }
    /** Execute original Java 21 smoke checks.
     * @param args unused command-line arguments
     * @throws Exception if the test setup encounters an unexpected failure
     */
    public static void main(String[] args) throws Exception {
        item70();item71();item72();item73();item74();
        System.out.println("CodeFit original Java21 exceptions assertions="+checks);
    }
}
