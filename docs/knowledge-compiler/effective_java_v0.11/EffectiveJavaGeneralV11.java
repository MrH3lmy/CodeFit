// Original CodeFit Java 21 language-behavior smoke fixtures for Items 55–59.
// Author validation only: NOT production learner assessment, not publisher source.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.random.RandomGenerator;
import java.security.SecureRandom;

/** Original public demonstration API with intentionally explicit return contracts. */
public final class EffectiveJavaGeneralV11 {
    private EffectiveJavaGeneralV11() {}
    private static int checks;
    private static void check(boolean ok,String id) { if(!ok) throw new AssertionError(id); checks++; }
    private static void rejects(Class<? extends Throwable> type,Runnable code,String id) {
        try { code.run(); } catch(Throwable x) { if(type.isInstance(x)) { checks++; return; } throw new AssertionError(id+" wrong exception "+x,x); }
        throw new AssertionError(id+" no exception");
    }

    /** Returns the parsed severity or an empty result when the input is unrecognized.
     * @param code the possibly null severity code
     * @return non-null optional containing a known severity
     */
    public static Optional<Integer> severity(String code) {
        if(code==null) return Optional.empty();
        return switch(code) { case "LOW"->Optional.of(1);case "HIGH"->Optional.of(5);default->Optional.empty(); };
    }
    private static void item55() {
        check(severity("HIGH").orElseThrow()==5,"55-present");
        check(severity("LOW").isPresent(),"55-low-present");
        check(severity("UNKNOWN").isEmpty(),"55-missing");
        check(severity(null).isEmpty(),"55-null-input-policy");
        check(Optional.ofNullable(null).isEmpty(),"55-ofnullable-null");
        rejects(NullPointerException.class,()->Optional.of(null),"55-of-null");
        rejects(NoSuchElementException.class,()->Optional.empty().orElseThrow(),"55-orElseThrow-empty");
        AtomicInteger fallback=new AtomicInteger();
        int present=severity("HIGH").orElseGet(()->fallback.incrementAndGet());
        check(present==5 && fallback.get()==0,"55-lazy-present");
        int missing=severity("MISSING").orElseGet(()->fallback.incrementAndGet());
        check(missing==1 && fallback.get()==1,"55-lazy-missing");
        check(severity("LOW").map(n->n*10).orElse(-1)==10,"55-map");
        check(severity("LOW").filter(n->n>2).isEmpty(),"55-filter");
        check(severity("HIGH").flatMap(n->Optional.of(n+1)).orElseThrow()==6,"55-flatMap");
        check(List.of(Optional.of("x"),Optional.<String>empty(),Optional.of("y")).stream().flatMap(Optional::stream).toList().equals(List.of("x","y")),"55-optional-stream");
        check(OptionalInt.of(6).getAsInt()==6,"55-primitive-present");
        check(OptionalInt.empty().isEmpty(),"55-primitive-empty");
        AtomicInteger eager=new AtomicInteger();
        check(severity("HIGH").orElse(eager.incrementAndGet())==5&&eager.get()==1,"55-eager-fallback");
    }

    /** Returns a defensive immutable copy.
     * @param <T> element type
     * @param values the supplied non-null collection with no null elements
     * @return a non-null unmodifiable list; empty when {@code values} is empty
     * @throws NullPointerException if {@code values} or a member is null
     */
    public static <T> List<T> freeze(Collection<? extends T> values) { return List.copyOf(values); }
    /** An immutable inclusive start, exclusive end window with nonnegative indexes.
     * @param start the inclusive lower bound
     * @param end the exclusive upper bound
     */
    public record Window(int start,int end) {
        /** Creates the window.
         * @param start inclusive starting index
         * @param end exclusive ending index
         * @throws IllegalArgumentException if bounds are inverted or negative
         */
        public Window {
            if(start<0 || end<start) throw new IllegalArgumentException("invalid window");
        }
        /** Returns the number of positions in the window.
         * @return nonnegative length
         */
        public int length() { return end-start; }
    }
    private static void item56() {
        check(freeze(List.of(2,4)).equals(List.of(2,4)),"56-freeze");
        check(freeze(List.<Integer>of()).isEmpty(),"56-empty");
        rejects(NullPointerException.class,()->freeze(null),"56-null-collection");
        rejects(NullPointerException.class,()->freeze(Arrays.asList("a",null)),"56-null-member");
        rejects(UnsupportedOperationException.class,()->freeze(List.of("a")).add("b"),"56-immutable-contract");
        check(new Window(2,2).length()==0,"56-zero-length");
        check(new Window(2,7).length()==5,"56-exclusive-end");
        rejects(IllegalArgumentException.class,()->new Window(-1,2),"56-negative-start");
        rejects(IllegalArgumentException.class,()->new Window(5,4),"56-inverted");
    }

    private static int countGreater(List<Integer> values,int threshold) {
        int count=0;
        for(int value:values) { if(value>threshold) count++; }
        return count;
    }
    private static int secondarySum(List<Integer> first,List<Integer> second) {
        int result=0;
        for(int item:first) { result+=item; }
        for(int item:second) { result+=item; }
        return result;
    }
    private static void item57() {
        check(countGreater(List.of(1,4,7),3)==2,"57-bound-local-count");
        check(countGreater(List.of(1,4,7),7)==0,"57-strict-threshold");
        check(countGreater(List.of(),0)==0,"57-empty");
        check(secondarySum(List.of(2),List.of(8,10))==20,"57-separate-loops");
        check(secondarySum(List.of(),List.of(5))==5,"57-first-empty");
        check(secondarySum(List.of(5),List.of())==5,"57-second-empty");
        int[] array={3,4,5};int sum=0;
        for(int i=0,n=array.length;i<n;i++) sum+=array[i];
        check(sum==12,"57-stable-index-bound");
        Object candidate="ready";
        if(candidate instanceof String text) check(text.length()==5,"57-pattern-flow-scope");
        else throw new AssertionError("57-unreachable");
        var locallyTyped=List.of("a","b");
        check(locallyTyped.getFirst().equals("a"),"57-inferred-local-static-type");
        int retryTotal=0;
        for(int retry=0;retry<3;retry++) retryTotal+=retry;
        check(retryTotal==3,"57-retry-loop-local");
        check(secondarySum(List.of(9),List.of(1))==10,"57-independent-source");
        check(countGreater(List.of(-1,0,1),-1)==2,"57-negative-threshold");
    }

    private record Task(String region,String severity) {}
    private static List<Task> combinations(List<String> regions,List<String> severity) {
        List<Task> tasks=new ArrayList<>();
        for(String region:regions) for(String level:severity) tasks.add(new Task(region,level));
        return List.copyOf(tasks);
    }
    private static List<String> zip(List<String> a,List<String> b) {
        if(a.size()!=b.size()) throw new IllegalArgumentException("different lengths");
        List<String> out=new ArrayList<>();
        for(int i=0;i<a.size();i++) out.add(a.get(i)+":"+b.get(i));
        return List.copyOf(out);
    }
    private static void item58() {
        var list=combinations(List.of("us","eu","ap"),List.of("warn","critical"));
        check(list.size()==6,"58-cross-product-count");
        check(list.getFirst().equals(new Task("us","warn")),"58-first-pair");
        check(list.getLast().equals(new Task("ap","critical")),"58-last-pair");
        check(combinations(List.of(),List.of("w")).isEmpty(),"58-zero-left");
        check(combinations(List.of("a"),List.of()).isEmpty(),"58-zero-right");
        check(zip(List.of("x","y"),List.of("1","2")).equals(List.of("x:1","y:2")),"58-zip");
        check(zip(List.of(),List.of()).isEmpty(),"58-zip-empty");
        rejects(IllegalArgumentException.class,()->zip(List.of("a"),List.of()),"58-zip-mismatched");
        List<String> old=new ArrayList<>(List.of("keep","remove","keep2"));
        check(old.removeIf(s->s.equals("remove")),"58-removeif-changed");
        check(old.equals(List.of("keep","keep2")),"58-removeif-state");
        List<String> iterList=new ArrayList<>(List.of("x","y","z"));
        Iterator<String> it=iterList.iterator();
        while(it.hasNext()) if(it.next().equals("y"))it.remove();
        check(iterList.equals(List.of("x","z")),"58-iterator-remove");
        int total=0;for(int num:new int[]{3,8}) total+=num;
        check(total==11,"58-array-enhancedfor");
        Iterable<String> wrap=()->List.of("one","two").iterator();
        int seen=0;for(String ignored:wrap) seen++;
        check(seen==2,"58-noncollection-iterable");
        List<String> swapped=new ArrayList<>(List.of("one","two"));
        for(int i=0;i<swapped.size();i++)swapped.set(i,swapped.get(i).toUpperCase(Locale.ROOT));
        check(swapped.equals(List.of("ONE","TWO")),"58-indexed-replacement");
        check(combinations(List.of("x"),List.of("a","b")).size()==2,"58-product-asymmetric");
    }

    private static long seededSequence(long seed) {
        RandomGenerator generator=new SplittableRandom(seed);
        return generator.nextLong();
    }
    private static void item59() throws IOException {
        RandomGenerator deterministic=new SplittableRandom(12345L);
        int value=deterministic.nextInt(17);
        check(value>=0 && value<17,"59-bounded-random");
        check(seededSequence(1999)==seededSequence(1999),"59-seeded-repeatable");
        check(new SplittableRandom(123L).nextInt(1)==0,"59-singleton-range");
        rejects(IllegalArgumentException.class,()->new SplittableRandom().nextInt(0),"59-bad-bound");
        int tl=ThreadLocalRandom.current().nextInt(10);
        check(tl>=0&&tl<10,"59-threadlocal-bounded");
        byte[] bytes="payload".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        long copied=new ByteArrayInputStream(bytes).transferTo(out);
        check(copied==bytes.length,"59-transfer-count");
        check(Arrays.equals(bytes,out.toByteArray()),"59-transfer-content");
        check(new ByteArrayInputStream(new byte[0]).transferTo(new ByteArrayOutputStream())==0,"59-empty-stream");
        var generated=RandomGenerator.of("L64X128MixRandom");
        int n=generated.nextInt(21);
        check(n>=0&&n<21,"59-factory-algorithm");
        SecureRandom crypto=new SecureRandom();
        byte[] token=new byte[16];crypto.nextBytes(token);
        check(token.length==16,"59-crypto-token-size");
        check(crypto instanceof Random,"59-secure-random-contract");
        check(List.of(3,1,2).stream().sorted().toList().equals(List.of(1,2,3)),"59-library-sort");
        check(Collections.frequency(List.of("x","y","x"),"x")==2,"59-library-frequency");
        check(Math.floorMod(-1,7)==6,"59-library-floormod");
    }
    /** Executes original language-behavior smoke checks.
     * @param args unused program arguments
     * @throws IOException if an in-memory stream operation fails
     */
    public static void main(String[] args) throws IOException {
        item55();item56();item57();item58();item59();
        System.out.println("CodeFit Java21 original general-programming assertions="+checks);
    }
}
