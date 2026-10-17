// Independent CodeFit Java 21 language smoke checks; NOT copied from a book.
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

public final class EffectiveJavaLambdasV8 {
  private static int passed;
  private static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);passed++;}
  private static void reject(Class<? extends Throwable> type,Runnable task,String msg){
    try{task.run();}catch(Throwable e){if(type.isInstance(e)){passed++;return;}throw new AssertionError(msg,e);}
    throw new AssertionError(msg+" did not reject");
  }
  private record Event(String id,int risk){}
  @FunctionalInterface private interface Rule {
    boolean allow(Event event);
    default Rule and(Rule next){return event->allow(event)&&next.allow(event);}
  }
  private enum RetryPolicy {
    LINEAR(n->n+1), DOUBLE(n->n*2);
    final IntUnaryOperator next;
    RetryPolicy(IntUnaryOperator next){this.next=next;}
    int apply(int attempt){return next.applyAsInt(attempt);}
  }
  private abstract static class AbstractHandler {abstract int handle(int n);}
  private interface MultiHandler {int add(int n);int subtract(int n);}
  private static final class Binding {
    final String label;Binding(String label){this.label=label;}
    Supplier<String> lambdaThis(){return ()->this.label;}
    Supplier<String> anonymousThis(){return new Supplier<>() {
      @Override public String get(){return this.getClass().getSimpleName();}
    };}
  }
  private static void item42(){
    Rule lowRisk=e->e.risk()<10;
    Rule named=e->e.id()!=null&&!e.id().isBlank();
    check(lowRisk.allow(new Event("p",2)),"simple lambda");
    check(!lowRisk.allow(new Event("p",12)),"predicate rejection");
    check(lowRisk.and(named).allow(new Event("p",3)),"functional interface default");
    check(!lowRisk.and(named).allow(new Event("",3)),"composed rule rejection");
    List<Event> events=new ArrayList<>(List.of(new Event("b",20),new Event("a",3)));
    events.sort(Comparator.comparingInt(Event::risk));
    check(events.getFirst().id().equals("a"),"typed comparator lambda/method reference");
    int offset=3;
    IntUnaryOperator captured=x->x+offset;
    check(captured.applyAsInt(7)==10,"effectively final local capture");
    check(RetryPolicy.LINEAR.apply(4)==5,"enum lambda behavior");
    check(RetryPolicy.DOUBLE.apply(4)==8,"enum different lambda behavior");
    AbstractHandler handler=new AbstractHandler(){@Override int handle(int n){return n*n;}};
    check(handler.handle(4)==16,"anonymous abstract subclass");
    MultiHandler multi=new MultiHandler(){public int add(int n){return n+1;}public int subtract(int n){return n-1;}};
    check(multi.add(8)==9,"anonymous multi-method interface");
    check(multi.subtract(8)==7,"second abstract method");
    Binding binding=new Binding("outer");
    check(binding.lambdaThis().get().equals("outer"),"lambda this lexically enclosing");
    check(!binding.anonymousThis().get().equals("outer"),"anonymous this is different");
  }
  private static final class Formatter {
    final String prefix;Formatter(String prefix){this.prefix=prefix;}
    String format(String value){return prefix+value;}
  }
  private static void item43(){
    Function<String,Integer> staticRef=Integer::parseInt;
    check(staticRef.apply("42")==42,"static reference");
    Formatter f=new Formatter("old-");
    Function<String,String> bound=f::format;
    check(bound.apply("x").equals("old-x"),"bound reference");
    Function<String,String> unbound=String::strip;
    check(unbound.apply("  abc  ").equals("abc"),"unbound receiver as argument");
    Supplier<List<String>> ctor=ArrayList::new;
    check(ctor.get().isEmpty(),"class constructor reference");
    IntFunction<String[]> arrayCtor=String[]::new;
    check(arrayCtor.apply(3).length==3,"array constructor reference");
    Map<String,Integer> counts=new HashMap<>();
    counts.merge("ok",1,Integer::sum);
    counts.merge("ok",2,Integer::sum);
    check(counts.get("ok")==3,"merge static sum reference");
    check(counts.size()==1,"merge no extra key");
    AtomicReference<Formatter> service=new AtomicReference<>(f);
    Function<String,String> early=service.get()::format;
    Function<String,String> late=value->service.get().format(value);
    service.set(new Formatter("new-"));
    check(early.apply("x").equals("old-x"),"bound receiver captured early");
    check(late.apply("x").equals("new-x"),"lambda receiver resolved late");
    Function<String,String> direct=String::toUpperCase;
    check(direct.apply("ok").equals("OK"),"unbound method mapping");
    Function<String,String> readable=value->value.equals("VIP")?"priority":"normal";
    check(readable.apply("VIP").equals("priority"),"lambda clearer for conditional mapping");
    check(readable.apply("REGULAR").equals("normal"),"lambda alternative branch");
    reject(NumberFormatException.class,()->staticRef.apply("invalid"),"parse invalid input");
  }
  public static void main(String[] args){item42();item43();System.out.println("EffectiveJavaLambdasV8 checks="+passed+" PASS");}
}
