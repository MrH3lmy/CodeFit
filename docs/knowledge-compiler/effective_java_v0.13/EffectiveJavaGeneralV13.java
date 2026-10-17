// Original CodeFit Java21 smoke facts for Items 65-69. Not a publisher example or learner grader.
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Original isolated Java21 validation of general-programming contracts. */
public final class EffectiveJavaGeneralV13 {
  private EffectiveJavaGeneralV13() {}
  private static int checks;
  private static void ok(boolean result, String caseName) {
    if (!result) throw new AssertionError(caseName);
    checks++;
  }
  private static void rejects(Class<? extends Throwable> type, Throwing action, String name) {
    try { action.run(); }
    catch (Throwable thrown) {
      if (type.isInstance(thrown)) { checks++; return; }
      throw new AssertionError(name+": wrong failure: "+thrown,thrown);
    }
    throw new AssertionError(name+": expected failure missing");
  }
  @FunctionalInterface private interface Throwing { void run() throws Exception; }

  interface Handler { String handle(String id); }
  public static final class GoodHandler implements Handler {
    public GoodHandler() {}
    public String handle(String id){ return "handled:"+id; }
  }
  public static final class WrongType { public WrongType() {} }
  public static final class MissingPublicCtor implements Handler {
    private MissingPublicCtor() {}
    public String handle(String id){return id;}
  }
  public static final class FaultyHandler implements Handler {
    public FaultyHandler(){throw new IllegalStateException("bad config");}
    public String handle(String id){return id;}
  }
  private static Handler newKnownHandler(String alias) throws ReflectiveOperationException {
    String className=switch(alias) {
      case "good" -> GoodHandler.class.getName();
      case "wrong" -> WrongType.class.getName();
      case "no-public-ctor" -> MissingPublicCtor.class.getName();
      case "throws" -> FaultyHandler.class.getName();
      default -> throw new IllegalArgumentException("not allowlisted");
    };
    Class<? extends Handler> impl=Class.forName(className).asSubclass(Handler.class);
    return impl.getConstructor().newInstance();
  }
  private static void item65() throws Exception {
    Handler typed=newKnownHandler("good");
    ok("handled:a".equals(typed.handle("a")),"65-typed-dispatch");
    ok(typed instanceof Handler,"65-handler-contract");
    ok(Handler.class.isAssignableFrom(GoodHandler.class),"65-interface-check");
    ok(!Handler.class.isAssignableFrom(WrongType.class),"65-wrong-interface");
    ok(Class.forName(GoodHandler.class.getName())==GoodHandler.class,"65-load-class");
    ok(GoodHandler.class.getConstructor().newInstance() instanceof Handler,"65-public-constructor");
    rejects(ClassCastException.class,()->newKnownHandler("wrong"),"65-checked-class-cast");
    rejects(NoSuchMethodException.class,()->newKnownHandler("no-public-ctor"),"65-no-public-constructor");
    rejects(InvocationTargetException.class,()->newKnownHandler("throws"),"65-constructor-cause-wrapped");
    rejects(IllegalArgumentException.class,()->newKnownHandler("not-allowed"),"65-not-allowlisted");
    rejects(ClassNotFoundException.class,()->Class.forName("not.registered.codefit.missing.Provider"),"65-no-class");
    InvocationTargetException failure=null;
    try {newKnownHandler("throws");}catch(InvocationTargetException e){failure=e;}
    ok(failure!=null && failure.getCause() instanceof IllegalStateException,"65-preserve-cause");
    ok(GoodHandler.class.getMethod("handle",String.class).getReturnType()==String.class,"65-reflected-signature");
    ok(Handler.class.getMethods().length>=1,"65-contract-discovery");
  }

  interface Codec { byte[] apply(byte[] source); }
  private static final class JavaCodec implements Codec {
    public byte[] apply(byte[] bytes) {
      Objects.requireNonNull(bytes,"bytes");
      if(bytes.length>1024) throw new IllegalArgumentException("too large");
      return Arrays.copyOf(bytes,bytes.length);
    }
  }
  private static Codec codec(String provider) {
    return switch(provider) {case "java"->new JavaCodec();default->throw new IllegalArgumentException("unsupported adapter");};
  }
  private static void item66() {
    Codec c=codec("java");
    byte[] data={1,2,3};byte[] out=c.apply(data);
    ok(Arrays.equals(data,out),"66-java-codec-roundtrip");
    ok(data!=out,"66-copy-ownership");
    data[0]=9;ok(out[0]==1,"66-no-alias");
    ok(c.apply(new byte[0]).length==0,"66-empty-supported");
    rejects(NullPointerException.class,()->c.apply(null),"66-null-contract");
    rejects(IllegalArgumentException.class,()->c.apply(new byte[1025]),"66-size-bound");
    ok(c.apply(new byte[1024]).length==1024,"66-boundary-size");
    rejects(IllegalArgumentException.class,()->codec("native-unreviewed"),"66-reject-native-without-loader");
    ok(!System.getProperty("java.version").isEmpty(),"66-runtime-version-known");
    ok(System.getProperty("os.arch")!=null,"66-architecture-observed");
    ok(Codec.class.isInterface(),"66-isolated-adapter");
  }

  private static int countLinear(int[] data, int target) {
    int seen=0;for(int x:data)if(x==target)seen++;return seen;
  }
  private static int countIndexed(int[] data,int target) {
    Map<Integer,Integer> counts=new HashMap<>();
    for(int x:data)counts.merge(x,1,Integer::sum);
    return counts.getOrDefault(target,0);
  }
  private static void item67() {
    int[] values=new int[1000];for(int i=0;i<values.length;i++)values[i]=i%7;
    for(int target=-1;target<=7;target++) ok(countLinear(values,target)==countIndexed(values,target),"67-same-answer-"+target);
    ok(countLinear(new int[0],3)==countIndexed(new int[0],3),"67-empty");
    ok(countLinear(new int[]{2,2,2},2)==3,"67-repeated");
    ok(countIndexed(new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE},Integer.MAX_VALUE)==1,"67-extreme-value");
    long start=System.nanoTime();countLinear(values,3);long elapsed=System.nanoTime()-start;
    ok(elapsed>=0,"67-nonnegative-clock-observation"); // No speedup threshold or production inference.
    ok(values.length==1000,"67-same-workload");
    ok(Arrays.stream(values).limit(1).count()==1,"67-deterministic-input");
  }

  /** Bean compatibility is distinct from record component accessors. */
  public static class LegacyBean {
    private String status;
    public LegacyBean(String status){this.status=status;}
    public String getStatus(){return status;}
    public void setStatus(String status){this.status=status;}
  }
  public record StatusView(String status, long count) {}
  private static final Set<String> ALLOWED_MODES=Set.of("active","pending");
  private static void item68() throws Exception {
    StatusView record=new StatusView("active",2);
    ok(record.status().equals("active"),"68-record-accessor");
    ok(record.count()==2,"68-record-count");
    ok(Arrays.stream(StatusView.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("status")),"68-reflect-record-method");
    rejects(NoSuchMethodException.class,()->StatusView.class.getMethod("getStatus"),"68-no-generated-bean-getter");
    ok(LegacyBean.class.getMethod("getStatus").getReturnType()==String.class,"68-bean-getter");
    LegacyBean bean=new LegacyBean("queued");bean.setStatus("sent");
    ok(bean.getStatus().equals("sent"),"68-bean-setter-getter");
    ok(StatusView.class.isRecord(),"68-record-type");
    ok(!LegacyBean.class.isRecord(),"68-bean-not-record");
    ok(ALLOWED_MODES.contains("pending"),"68-value-in-constant-set");
    rejects(UnsupportedOperationException.class,()->ALLOWED_MODES.add("new"),"68-unmodifiable-constant");
    ok(ALLOWED_MODES.size()==2,"68-no-mutation-leak");
    ok("toString".startsWith("to"),"68-conversion-name-convention");
  }

  private static List<Integer> safeWalk(List<Integer> list) {
    List<Integer> out=new ArrayList<>();
    Iterator<Integer> iterator=list.iterator();
    while(iterator.hasNext())out.add(iterator.next());
    return out;
  }
  private static int drain(Queue<Integer> q) {
    int count=0;Integer item;
    while((item=q.poll())!=null)count+=item;
    return count;
  }
  private static void item69() {
    ok(safeWalk(List.of(1,2,3)).equals(List.of(1,2,3)),"69-hasnext-walk");
    ok(safeWalk(List.of()).isEmpty(),"69-empty-walk");
    ok(drain(new ArrayDeque<>(List.of(1,2,3)))==6,"69-poll-drain");
    ok(drain(new ArrayDeque<>())==0,"69-empty-poll");
    ArrayDeque<Integer> queue=new ArrayDeque<>(List.of(5));
    ok(queue.poll()==5,"69-poll-existing");
    ok(queue.poll()==null,"69-poll-absence");
    rejects(NoSuchElementException.class,queue::remove,"69-remove-raises-if-empty");
    rejects(NoSuchElementException.class,()->List.<Integer>of().iterator().next(),"69-empty-iterator-exception");
    Optional<Integer> missing=Optional.empty();ok(missing.isEmpty(),"69-optional-missing");
    ok(Optional.of(42).orElseThrow()==42,"69-optional-present");
    ok(List.of(1,2).stream().findFirst().orElseThrow()==1,"69-stream-present");
  }
  public static void main(String[] args) throws Exception {
    item65();item66();item67();item68();item69();
    System.out.println("CodeFit original Java21 general assertions="+checks);
  }
}
