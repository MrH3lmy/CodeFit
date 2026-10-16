package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.CheckStatus;

import com.sun.source.tree.*;
import com.sun.source.util.*;

import java.net.URI;
import java.util.*;

import javax.lang.model.element.*;
import javax.tools.*;

/**
 * Conservative, closed-source-form verifier, not a security sandbox or general Java theorem prover.
 * Unknown mechanisms are unsupported. Functional tests remain necessary even for admitted forms.
 */
public final class JavaMechanismVerifier {
    public record Inspection(boolean admitted, CheckStatus boundary, String detail) {}

    private static final Map<String, String> REVIEWED =
            Map.of(
                    "389edbf0381b280617e54275bd6882472844a24ad0b7262317b5a92646cde9bd",
                            "Reviewed intrinsic-monitor route counter with pure JDK reduction"
                                    + " callbacks",
                    "1c82a7b15786c242258a9512b4b2c70151238679836c9c3e16aca04063bf971b",
                            "Reviewed fixed two-lock min/max ordering, no lock replacement,"
                                    + " blocking callback or dropped operation; bounded termination"
                                    + " remains separate",
                    "7c0fa946ed6ab8b1fd22aa509c461843c73505ef07c4d90fda63492f207ab2f0",
                            "Reviewed intrinsic-monitor expiry with pure scalar removal predicate",
                    "dd6ff9e9c575c82933b46f7d2e20a7a4871f152ea6901ab746f992fdcb1f30bb",
                            "Reviewed intrinsic-monitor ledger with private immutable Entry values",
                    "8d1d3ff161844fcd6eb556ac8e7d163c8cf55c330135bd4bc4e9d39f6311e36b",
                            "Reviewed stateless cursor filter; no concurrency guarantee claimed");
    private static final Set<String> TYPES =
            Set.of(
                    "java.lang.Object",
                    "java.lang.String",
                    "java.lang.Integer",
                    "java.lang.Long",
                    "java.lang.Boolean",
                    "java.lang.IllegalArgumentException",
                    "java.lang.IllegalStateException",
                    "java.lang.Math",
                    "java.util.Map",
                    "java.util.Set",
                    "java.util.HashMap",
                    "java.util.HashSet",
                    "java.util.ArrayDeque",
                    "java.util.Deque",
                    "java.util.Queue",
                    "java.util.Arrays",
                    "java.util.Collection",
                    "java.util.Iterator",
                    "java.util.Map.Entry",
                    "java.util.concurrent.atomic.AtomicLong",
                    "java.util.concurrent.atomic.AtomicInteger",
                    "java.util.stream.IntStream",
                    "java.util.stream.Stream");
    private static final Set<String> METHODS =
            Set.of(
                    "<init>",
                    "get",
                    "getOrDefault",
                    "put",
                    "putIfAbsent",
                    "remove",
                    "containsKey",
                    "contains",
                    "add",
                    "addLast",
                    "removeFirst",
                    "pollFirst",
                    "peekFirst",
                    "poll",
                    "peek",
                    "size",
                    "isEmpty",
                    "keySet",
                    "values",
                    "entrySet",
                    "getKey",
                    "getValue",
                    "removeIf",
                    "min",
                    "max",
                    "stream",
                    "filter",
                    "limit",
                    "toArray",
                    "mapToInt",
                    "sum",
                    "intValue",
                    "longValue",
                    "equals",
                    "addAndGet",
                    "getAndAdd",
                    "incrementAndGet",
                    "getAndIncrement");

    public Inspection inspect(String source, String family) {
        // Bound recursion before javac's in-process parser sees input. Excessively complex code is
        // explicitly unsupported, not evidence that the learner lacks a capability.
        if (source.length() > 60000
                || source.chars().filter(c -> c == '{' || c == '(' || c == '[').count() > 3000)
            return unsupported("Source exceeds supported inspection complexity");
        int depth = 0;
        for (char ch : source.toCharArray()) {
            if (ch == '{' || ch == '(' || ch == '[') {
                if (++depth > 80) return unsupported("Source nesting exceeds inspection bound");
            } else if (ch == '}' || ch == ')' || ch == ']') depth--;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return unsupported("JDK compiler model unavailable");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files =
                compiler.getStandardFileManager(
                        diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var file =
                    new SimpleJavaFileObject(
                            URI.create("string:///Exercise.java"), JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignore) {
                            return source;
                        }
                    };
            var task =
                    (JavacTask)
                            compiler.getTask(
                                    null,
                                    files,
                                    diagnostics,
                                    List.of("-proc:none", "--release", "21", "-classpath", ""),
                                    null,
                                    List.of(file));
            var units = task.parse().iterator();
            var unit = units.next();
            String canonical = unit.toString();
            // Explicitly reviewed authored mechanisms with callbacks/nested immutable records or
            // multiple locks. AST fingerprints accept formatting/comments only. New templates
            // require evaluator-version review and mutant tests, never automatic catalog trust.
            String template =
                    REVIEWED.get(com.codefit.evolution.quest.CatalogCodec.digest(canonical));
            if (template != null) return new Inspection(true, CheckStatus.PASSED, template);
            if (unit.getPackageName() != null
                    || unit.getModule() != null
                    || unit.getTypeDecls().size() != 1
                    || !(unit.getTypeDecls().getFirst() instanceof ClassTree cls)
                    || !cls.getSimpleName().contentEquals("Exercise")
                    || cls.getKind() != Tree.Kind.CLASS
                    || !Set.of(Modifier.FINAL).containsAll(cls.getModifiers().getFlags())
                    || cls.getExtendsClause() != null
                    || !cls.getImplementsClause().isEmpty()
                    || !cls.getTypeParameters().isEmpty())
                return unsupported("Only the package-private Exercise is editable");
            if (unit.getImports().stream()
                    .anyMatch(
                            i ->
                                    i.isStatic()
                                            || i.getQualifiedIdentifier()
                                                    .toString()
                                                    .endsWith(".*")))
                return unsupported("Explicit type imports only");
            boolean[] complexTypes = {false};
            new TreeScanner<Void, Integer>() {
                @Override
                public Void visitTypeParameter(TypeParameterTree t, Integer depth) {
                    complexTypes[0] = true;
                    return null;
                }

                @Override
                public Void visitParameterizedType(ParameterizedTypeTree t, Integer depth) {
                    int next = depth == null ? 1 : depth + 1;
                    if (next > 3) {
                        complexTypes[0] = true;
                        return null;
                    }
                    return super.visitParameterizedType(t, next);
                }
            }.scan(unit, 0);
            if (complexTypes[0])
                return unsupported("Generic type complexity is outside this verifier");
            task.analyze();
            if (diagnostics.getDiagnostics().stream()
                    .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
                return unsupported(
                        "Source model could not be resolved; compile diagnostics are separate");
            Trees trees = Trees.instance(task);
            var audit =
                    new TreePathScanner<Void, Void>() {
                        boolean ok = true;

                        @Override
                        public Void visitClass(ClassTree t, Void p) {
                            if (t != cls) ok = false;
                            return super.visitClass(t, p);
                        }

                        @Override
                        public Void visitAnnotation(AnnotationTree t, Void p) {
                            ok = false;
                            return null;
                        }

                        @Override
                        public Void visitLambdaExpression(LambdaExpressionTree t, Void p) {
                            ok = false;
                            return null;
                        }

                        @Override
                        public Void visitMemberReference(MemberReferenceTree t, Void p) {
                            ok = false;
                            return null;
                        }

                        @Override
                        public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
                            var e = trees.getElement(getCurrentPath());
                            if (!(e instanceof ExecutableElement method)) ok = false;
                            else {
                                String owner = method.getEnclosingElement().toString();
                                // javac inserts super() in the constructor; Object has no other
                                // allowed call.
                                if (!TYPES.contains(owner)
                                        || !METHODS.contains(method.getSimpleName().toString())
                                        || (owner.equals("java.lang.Object")
                                                && !method.getSimpleName().contentEquals("<init>")))
                                    ok = false;
                            }
                            return super.visitMethodInvocation(t, p);
                        }

                        @Override
                        public Void visitNewClass(NewClassTree t, Void p) {
                            var e = trees.getElement(getCurrentPath());
                            if (t.getClassBody() != null
                                    || !(e instanceof ExecutableElement m)
                                    || !TYPES.contains(m.getEnclosingElement().toString()))
                                ok = false;
                            return super.visitNewClass(t, p);
                        }

                        @Override
                        public Void visitIdentifier(IdentifierTree t, Void p) {
                            var e = trees.getElement(getCurrentPath());
                            if (e instanceof TypeElement type
                                    && !type.getQualifiedName().contentEquals("Exercise")
                                    && !TYPES.contains(type.getQualifiedName().toString()))
                                ok = false;
                            if (t.getName().contentEquals("this")
                                    || t.getName().contentEquals("super")) {
                                // Only the compiler-generated constructor super() is permitted.
                                var parent = getCurrentPath().getParentPath().getLeaf();
                                if (!(parent instanceof MethodInvocationTree)
                                        && !(parent instanceof ParenthesizedTree)) ok = false;
                                if (t.getName().contentEquals("this")) ok = false;
                            }
                            return super.visitIdentifier(t, p);
                        }

                        @Override
                        public Void visitMemberSelect(MemberSelectTree t, Void p) {
                            var e = trees.getElement(getCurrentPath());
                            if (e instanceof TypeElement type
                                    && !TYPES.contains(type.getQualifiedName().toString()))
                                ok = false;
                            return super.visitMemberSelect(t, p);
                        }
                    };
            audit.scan(unit, null);
            if (!audit.ok)
                return unsupported(
                        "Code uses operations outside the closed local checking vocabulary");
            var methods = new ArrayList<MethodTree>();
            boolean atomic = false;
            VariableElement atomicField = null;
            for (var member : cls.getMembers()) {
                if (member instanceof VariableTree field) {
                    var flags = field.getModifiers().getFlags();
                    if (!flags.contains(Modifier.PRIVATE) || flags.contains(Modifier.STATIC))
                        return unsupported("State must be private instance state");
                    String resolvedType =
                            trees.getTypeMirror(TreePath.getPath(unit, field.getType())).toString();
                    if (Set.of(
                                    "java.util.concurrent.atomic.AtomicLong",
                                    "java.util.concurrent.atomic.AtomicInteger")
                            .contains(resolvedType)) {
                        if (!flags.contains(Modifier.FINAL))
                            return unsupported("Atomic reference must be final");
                        atomic = true;
                        atomicField =
                                (VariableElement) trees.getElement(TreePath.getPath(unit, field));
                    }
                } else if (member instanceof MethodTree method) {
                    if (method.getBody() == null
                            || method.getModifiers().getFlags().contains(Modifier.NATIVE)
                            || method.getModifiers().getFlags().contains(Modifier.STATIC))
                        return unsupported("Only concrete instance methods supported");
                    if (!method.getName().contentEquals("<init>")) methods.add(method);
                } else
                    return unsupported("Initializers and nested types are outside this verifier");
            }
            // No object/array can escape from the API. Scalar parameters prevent injected
            // callbacks.
            for (var m : methods) {
                if (!scalar(m.getReturnType().toString())
                        || m.getParameters().stream()
                                .anyMatch(p -> !scalar(p.getType().toString())))
                    return unsupported("Only scalar API boundaries supported");
            }
            if (methods.isEmpty()) return unsupported("No operations to verify");
            if (methods.stream()
                            .allMatch(
                                    m ->
                                            m.getModifiers()
                                                    .getFlags()
                                                    .contains(Modifier.SYNCHRONIZED))
                    && !atomic) {
                // A constructor may initialize fields; every subsequent state access is in the same
                // intrinsic monitor. Nested synchronization would require a separate ordering
                // proof.
                if (contains(cls, Tree.Kind.SYNCHRONIZED))
                    return unsupported("Nested monitors require a separate ordering proof");
                return new Inspection(
                        true,
                        CheckStatus.PASSED,
                        "All scalar instance operations use the same intrinsic monitor; finite"
                                + " functional cases remain necessary");
            }
            if (atomic) {
                // Restrict atomic form to one scalar AtomicLong/Integer, no other mutable state,
                // and a single primitive operation per API call (no split get/set or conditional
                // CAS).
                long fields =
                        cls.getMembers().stream().filter(t -> t instanceof VariableTree).count();
                boolean one = fields == 1;
                var ownedAtomicField = atomicField;
                for (var m : methods) {
                    var calls = new ArrayList<MethodInvocationTree>();
                    new TreeScanner<Void, Void>() {
                        @Override
                        public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
                            calls.add(t);
                            return super.visitMethodInvocation(t, p);
                        }
                    }.scan(m.getBody(), null);
                    var atomicCalls =
                            calls.stream()
                                    .filter(
                                            t -> {
                                                var e =
                                                        (ExecutableElement)
                                                                trees.getElement(
                                                                        TreePath.getPath(unit, t));
                                                return e.getEnclosingElement()
                                                        .toString()
                                                        .startsWith("java.util.concurrent.atomic.");
                                            })
                                    .toList();
                    one &=
                            atomicCalls.size() == 1
                                    && !contains(m.getBody(), Tree.Kind.SYNCHRONIZED)
                                    && !contains(m.getBody(), Tree.Kind.WHILE_LOOP)
                                    && !contains(m.getBody(), Tree.Kind.FOR_LOOP)
                                    && !contains(m.getBody(), Tree.Kind.DO_WHILE_LOOP)
                                    && !contains(m.getBody(), Tree.Kind.ENHANCED_FOR_LOOP);
                    if (!atomicCalls.isEmpty()) {
                        var call = atomicCalls.getFirst();
                        if (!(call.getMethodSelect() instanceof MemberSelectTree selected))
                            return unsupported("Atomic receiver must be the owned scalar field");
                        ExpressionTree receiver = selected.getExpression();
                        while (receiver instanceof ParenthesizedTree parens)
                            receiver = parens.getExpression();
                        one &=
                                ownedAtomicField.equals(
                                        trees.getElement(TreePath.getPath(unit, receiver)));
                        var path = TreePath.getPath(unit, call).getParentPath();
                        // The atomic call must be a top-level expression/return, never a condition
                        // or
                        // a branch-dependent update after another atomic read.
                        one &=
                                (path.getLeaf() instanceof ExpressionStatementTree
                                                || path.getLeaf() instanceof ReturnTree)
                                        && path.getParentPath().getLeaf() == m.getBody();
                    }
                }
                if (one)
                    return new Inspection(
                            true,
                            CheckStatus.PASSED,
                            "One primitive atomic operation per scalar API call; no split"
                                    + " read/write accepted");
                return unsupported("Compound atomic protocols require another validated mechanism");
            }
            // Ordered account locking is admitted only in the exact authored closed AST form.
            // This is a versioned mechanism template, not arbitrary textual lock-order inference.
            // Other lock-based solutions can use the supported single-monitor form above.
            boolean[] unprotected = {false};
            for (var method : methods)
                if (!method.getModifiers().getFlags().contains(Modifier.SYNCHRONIZED)
                        && !contains(method.getBody(), Tree.Kind.SYNCHRONIZED))
                    new TreePathScanner<Void, Void>() {
                        private void write(ExpressionTree variable) {
                            var e = trees.getElement(TreePath.getPath(unit, variable));
                            if (e instanceof VariableElement v
                                    && v.getKind() == ElementKind.FIELD
                                    && v.getEnclosingElement()
                                            .getSimpleName()
                                            .contentEquals("Exercise")) unprotected[0] = true;
                        }

                        @Override
                        public Void visitAssignment(AssignmentTree t, Void p) {
                            var target = trees.getElement(TreePath.getPath(unit, t.getVariable()));
                            if (target instanceof VariableElement v
                                    && v.getKind() == ElementKind.FIELD) {
                                boolean[] reads = {false};
                                new TreePathScanner<Void, Void>() {
                                    @Override
                                    public Void visitIdentifier(IdentifierTree id, Void x) {
                                        if (target.equals(trees.getElement(getCurrentPath())))
                                            reads[0] = true;
                                        return super.visitIdentifier(id, x);
                                    }
                                }.scan(TreePath.getPath(unit, t.getExpression()), null);
                                if (reads[0]) write(t.getVariable());
                            }
                            return super.visitAssignment(t, p);
                        }

                        @Override
                        public Void visitCompoundAssignment(CompoundAssignmentTree t, Void p) {
                            write(t.getVariable());
                            return super.visitCompoundAssignment(t, p);
                        }

                        @Override
                        public Void visitUnary(UnaryTree t, Void p) {
                            if (Set.of(
                                            Tree.Kind.POSTFIX_INCREMENT,
                                            Tree.Kind.POSTFIX_DECREMENT,
                                            Tree.Kind.PREFIX_INCREMENT,
                                            Tree.Kind.PREFIX_DECREMENT)
                                    .contains(t.getKind())) write(t.getExpression());
                            return super.visitUnary(t, p);
                        }
                    }.scan(TreePath.getPath(unit, method.getBody()), null);
            if (unprotected[0])
                return new Inspection(
                        true,
                        CheckStatus.FAILED,
                        "Unsynchronized compound update to shared state; volatile does not make"
                                + " read-modify-write atomic");
            return new Inspection(
                    true,
                    CheckStatus.UNSUPPORTED,
                    "Unprotected or multi-monitor operations require a validated"
                            + " ordering/transition proof");
        } catch (Exception | StackOverflowError e) {
            return unsupported("Compiler model inspection unavailable");
        }
    }

    private static boolean scalar(String t) {
        return Set.of("void", "boolean", "int", "long", "String", "java.lang.String").contains(t);
    }

    private static boolean contains(Tree tree, Tree.Kind kind) {
        return Boolean.TRUE.equals(
                new TreeScanner<Boolean, Void>() {
                    @Override
                    public Boolean scan(Tree t, Void p) {
                        if (t == null) return false;
                        return t.getKind() == kind || Boolean.TRUE.equals(super.scan(t, p));
                    }

                    @Override
                    public Boolean reduce(Boolean a, Boolean b) {
                        return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
                    }
                }.scan(tree, null));
    }

    private static Inspection unsupported(String detail) {
        return new Inspection(false, CheckStatus.UNSUPPORTED, detail);
    }
}
