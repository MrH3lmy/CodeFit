import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.*;

/** Original Java 21 language/API fixtures; NOT grading actual learner work. */
public final class EffectiveJavaEnumsV7 {
  private static int checks = 0;
  static void check(boolean pass, String label) { checks++; if (!pass) throw new AssertionError(label); }
  enum BillingStatus {
    PENDING("P", Mode.BLOCK), APPROVED("A", Mode.ALLOW), SUSPENDED("S", Mode.BLOCK);
    private final String code; private final Mode policy;
    BillingStatus(String code, Mode policy) { this.code=code; this.policy=policy; }
    String code() { return code; } boolean allowed() { return policy==Mode.ALLOW; }
  }
  enum Mode { ALLOW, BLOCK }
  enum Tier { BASIC(10), GOLD(40), SILVER(20), LEGACY_GOLD(40);
    private final int code; Tier(int code){this.code=code;} int code(){return code;}
  }
  enum Permission { READ, MODIFY, AUDIT }
  enum Stage { CREATED, REVIEWING, SETTLED, FAILED }
  interface Calculator { int apply(int x,int y); }
  enum BaseCalc implements Calculator { ADD { public int apply(int x,int y){return x+y;} } }
  enum PluginCalc implements Calculator { MULTIPLY { public int apply(int x,int y){return x*y;} } }
  static <T extends Enum<T> & Calculator> int invoke(Class<T> type,int a,int b) {
    return Arrays.stream(type.getEnumConstants()).mapToInt(x -> x.apply(a,b)).sum();
  }
  static int invokeMixed(Collection<? extends Calculator> calculators,int a,int b) {
    return calculators.stream().mapToInt(x -> x.apply(a,b)).sum();
  }
  @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) @Repeatable(Rules.class)
  @interface Rule { String value(); }
  @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
  @interface Rules { Rule[] value(); }
  static class Annotated {
    @Rule("a") static void once() { }
    @Rule("a") @Rule("b") static void twice() { }
  }
  static class ValueKey {
    final String value;
    ValueKey(String value){this.value=value;}
    @Override public boolean equals(Object other){return other instanceof ValueKey v && value.equals(v.value);}
    @Override public int hashCode(){return value.hashCode();}
  }
  interface Transport { String destination(); }
  interface TrustedTransport extends Transport { }
  static class Smtp implements TrustedTransport { public String destination(){return "mx";} }
  static String send(TrustedTransport t) {return t.destination();}
  @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) @interface Verified { }
  @Verified static class LabeledButUntrusted implements Transport {public String destination(){return "untrusted";} }
  static Map<Stage, Set<String>> groups() {
    var out = new EnumMap<Stage, Set<String>>(Stage.class);
    for(var s:Stage.values()) out.put(s, new HashSet<>());
    out.get(Stage.CREATED).add("order-1");
    return out;
  }
  static void test34() {
    check(BillingStatus.SUSPENDED!=BillingStatus.APPROVED,"new state has own identity");
    check(!BillingStatus.SUSPENDED.allowed(),"new state explicitly blocked");
    check(BillingStatus.APPROVED.allowed(),"approved strategy");
    check(BillingStatus.valueOf("APPROVED")==BillingStatus.APPROVED,"name lookup");
    check(Arrays.stream(BillingStatus.values()).map(BillingStatus::code).distinct().count()==3,"codes distinct");
  }
  static void test35() {
    check(Tier.GOLD.code()==Tier.LEGACY_GOLD.code(),"duplicate properties possible");
    check(Tier.GOLD.ordinal()!=Tier.LEGACY_GOLD.ordinal(),"ordinal distinct from property");
    check(Tier.SILVER.code()==20,"sparse codes supported");
    check(Tier.GOLD.code()!=Tier.GOLD.ordinal(),"code not ordinal");
  }
  static void test36() {
    EnumSet<Permission> source=EnumSet.of(Permission.READ,Permission.AUDIT);
    check(source.contains(Permission.AUDIT),"enumset membership");
    EnumSet<Permission> copy=EnumSet.copyOf(source);
    source.remove(Permission.AUDIT);
    check(copy.contains(Permission.AUDIT),"independent defensive copy");
    Set<Permission> immutable=Collections.unmodifiableSet(copy);
    try {immutable.add(Permission.MODIFY);throw new AssertionError("must not add");}
    catch(UnsupportedOperationException ok){check(true,"readonly wrapper rejects mutation");}
    EnumSet<Permission> empty=EnumSet.noneOf(Permission.class);
    check(empty.isEmpty(),"typed empty set");
    check(EnumSet.complementOf(copy).equals(EnumSet.of(Permission.MODIFY)),"set complement");
  }
  static void test37() {
    Map<Stage,Set<String>> map=groups();
    check(map instanceof EnumMap,"enum map instance");
    check(map.size()==4,"prepopulated all states");
    check(map.get(Stage.REVIEWING).isEmpty(),"empty bucket represented");
    check(map.get(Stage.CREATED).contains("order-1"),"keyed lookup");
    EnumMap<Stage,EnumMap<Stage,String>> transitions=new EnumMap<>(Stage.class);
    transitions.put(Stage.CREATED,new EnumMap<>(Stage.class));
    transitions.get(Stage.CREATED).put(Stage.REVIEWING,"submit");
    check("submit".equals(transitions.get(Stage.CREATED).get(Stage.REVIEWING)),"nested map");
    check(transitions.get(Stage.CREATED).get(Stage.FAILED)==null,"missing transition explicit");
    try {map.get(null);check(true,"null get may return null");} catch(NullPointerException ignored){check(true,"implementation permits null key probe rejection");}
  }
  static void test38() {
    check(invoke(BaseCalc.class,2,4)==6,"bounded enum type token");
    check(invoke(PluginCalc.class,2,4)==8,"extension enum token");
    check(invokeMixed(List.of(BaseCalc.ADD,PluginCalc.MULTIPLY),2,4)==14,"heterogeneous collection");
    check(BaseCalc.ADD instanceof Calculator,"interface contract");
  }
  static void test39() throws Exception {
    Method one=Annotated.class.getDeclaredMethod("once"),many=Annotated.class.getDeclaredMethod("twice");
    check(one.getAnnotationsByType(Rule.class).length==1,"single annotation");
    check(many.getAnnotationsByType(Rule.class).length==2,"repeatable annotation flatten");
    check(one.isAnnotationPresent(Rule.class),"direct presence");
    check(!many.isAnnotationPresent(Rule.class),"repeatable container distinction");
    check(many.isAnnotationPresent(Rules.class),"container visible");
    check(many.getAnnotationsByType(Rule.class)[1].value().equals("b"),"repeat content");
    check(Rule.class.getAnnotation(Retention.class).value()==RetentionPolicy.RUNTIME,"runtime retention");
    check(Rule.class.getAnnotation(Target.class).value()[0]==ElementType.METHOD,"method target");
  }
  static void test40(){
    var unique=new HashSet<>(List.of(new ValueKey("same"),new ValueKey("same")));
    check(unique.size()==1,"equals override in hash set");
    check(new ValueKey("a").equals(new ValueKey("a")),"value equality");
    check(!new ValueKey("a").equals(null),"null check");
    check(new ValueKey("a").hashCode()==new ValueKey("a").hashCode(),"hash contract");
  }
  static void test41(){
    check(send(new Smtp()).equals("mx"),"typed marker API");
    check(Smtp.class.isAssignableFrom(Smtp.class),"type identity");
    check(TrustedTransport.class.isAssignableFrom(Smtp.class),"marker as supertype");
    check(!TrustedTransport.class.isAssignableFrom(LabeledButUntrusted.class),"annotation does not add marker interface");
    check(LabeledButUntrusted.class.isAnnotationPresent(Verified.class),"annotation is metadata");
  }
  public static void main(String[] args) throws Exception {
    test34();test35();test36();test37();test38();test39();test40();test41();
    System.out.println("EffectiveJavaEnumsV7 checks="+checks+" PASS");
  }
}
