package com.codefit.diagnostics;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.instrument.*;
import java.security.ProtectionDomain;

/** Opt-in test JVM only. All matching is checked before the scenario can execute. */
public final class TimeoutAgent {
    private static final String EVENTS = "com/codefit/diagnostics/TimeoutEvents";

    public static void premain(String ignored, Instrumentation instrumentation) {
        TimeoutEvents.initialize();
        instrumentation.addTransformer(
                new ClassFileTransformer() {
                    public byte[] transform(
                            ClassLoader loader,
                            String name,
                            Class<?> type,
                            ProtectionDomain domain,
                            byte[] bytes) {
                        if (name == null) return null;
                        if (!name.equals("com/codefit/service/JavaCodeRunner")
                                && !name.equals("com/codefit/service/RunCancellationToken")
                                && !name.equals(
                                        "com/codefit/evolution/evaluation/JavaChallengeEvaluator"))
                            return null;
                        try {
                            ClassNode c = new ClassNode();
                            new ClassReader(bytes).accept(c, 0);
                            int anchors = 0;
                            for (MethodNode m : c.methods) {
                                if (name.endsWith("JavaCodeRunner")) anchors += runner(m);
                                if (name.endsWith("RunCancellationToken")
                                        && m.name.equals("killTree")) {
                                    InsnList hook = new InsnList();
                                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                    hook.add(call("terminating", "(Ljava/lang/Process;)V"));
                                    m.instructions.insert(hook);
                                    anchors++;
                                }
                                if (name.endsWith("JavaChallengeEvaluator")
                                        && m.name.equals("evaluate")) {
                                    int check =
                                            m.localVariables.stream()
                                                    .filter(v -> v.name.equals("check"))
                                                    .mapToInt(v -> v.index)
                                                    .max()
                                                    .orElseThrow();
                                    for (AbstractInsnNode n : m.instructions.toArray()) {
                                        if (n instanceof MethodInsnNode i
                                                && i.owner.equals("com/codefit/service/CodeRunner")
                                                && i.name.equals("run")) {
                                            InsnList hook = new InsnList();
                                            hook.add(new VarInsnNode(Opcodes.ALOAD, check));
                                            hook.add(
                                                    new MethodInsnNode(
                                                            Opcodes.INVOKEVIRTUAL,
                                                            "com/codefit/evolution/quest/ChallengeRubric$Check",
                                                            "id",
                                                            "()Ljava/lang/String;",
                                                            false));
                                            hook.add(call("check", "(Ljava/lang/String;)V"));
                                            m.instructions.insertBefore(n, hook);
                                            anchors++;
                                        }
                                    }
                                }
                            }
                            int expected = name.endsWith("JavaCodeRunner") ? 12 : 1;
                            if (anchors != expected)
                                throw new IllegalStateException("Anchor mismatch");
                            ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                            c.accept(w);
                            TimeoutEvents.instrumented(name, anchors);
                            return w.toByteArray();
                        } catch (Throwable invalid) {
                            TimeoutEvents
                                    .instrumentationFailed(); // JVM ignores transformer exceptions:
                            // scenario MUST fail closed.
                            return null;
                        }
                    }
                });
    }

    private static int runner(MethodNode m) {
        int count = 0;
        boolean compile = m.name.equals("compile") && m.desc.contains("RunLimits;");
        boolean run = m.name.equals("run");
        if (compile || run) {
            InsnList begin = new InsnList();
            begin.add(new LdcInsnNode(compile ? "COMPILE" : "RUN"));
            begin.add(call("begin", "(Ljava/lang/String;)V"));
            m.instructions.insert(begin);
            count++;
            int process = -1;
            for (AbstractInsnNode n : m.instructions.toArray()) {
                if (n instanceof MethodInsnNode i) {
                    if (i.owner.equals("java/lang/System") && i.name.equals("nanoTime")) {
                        AbstractInsnNode store = nextCode(n);
                        if (store instanceof VarInsnNode v && v.getOpcode() == Opcodes.LSTORE) {
                            InsnList hook = new InsnList();
                            hook.add(new VarInsnNode(Opcodes.LLOAD, v.var));
                            hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
                            hook.add(
                                    new MethodInsnNode(
                                            Opcodes.INVOKEVIRTUAL,
                                            "com/codefit/service/RunLimits",
                                            "timeoutSeconds",
                                            "()I",
                                            false));
                            hook.add(call("deadline", "(JI)V"));
                            m.instructions.insert(store, hook);
                            count++;
                        }
                    }
                    if (i.owner.equals("java/lang/ProcessBuilder") && i.name.equals("start")) {
                        AbstractInsnNode store = nextCode(n);
                        if (!(store instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ASTORE)
                            throw new IllegalStateException();
                        process = v.var;
                        InsnList hook = new InsnList();
                        hook.add(new VarInsnNode(Opcodes.ALOAD, process));
                        hook.add(call("spawned", "(Ljava/lang/Process;)V"));
                        m.instructions.insert(store, hook);
                        count++;
                    }
                    if (i.owner.equals("com/codefit/service/RunCancellationToken")
                            && i.name.equals("attach")) {
                        if (process < 0) throw new IllegalStateException();
                        InsnList hook = new InsnList();
                        hook.add(new VarInsnNode(Opcodes.ALOAD, process));
                        hook.add(call("registered", "(Ljava/lang/Process;)V"));
                        m.instructions.insert(n, hook);
                        count++;
                    }
                    if (i.name.equals("waitUntilDeadline")) {
                        m.instructions.insertBefore(n, call("waiting", "()V"));
                        count++;
                    }
                }
                if (n.getOpcode() == Opcodes.ARETURN) {
                    InsnList hook = new InsnList();
                    hook.add(new InsnNode(Opcodes.DUP));
                    hook.add(call("end", "(Ljava/lang/Object;)V"));
                    m.instructions.insertBefore(n, hook);
                }
            }
            // Multiple return sites are expected; only structural phase anchors counted.
        }
        if (m.name.equals("waitUntilDeadline")) {
            for (AbstractInsnNode n : m.instructions.toArray())
                if (n.getOpcode() == Opcodes.IRETURN) {
                    InsnList hook = new InsnList();
                    hook.add(new InsnNode(Opcodes.DUP));
                    hook.add(call("waited", "(Z)V"));
                    m.instructions.insertBefore(n, hook);
                    count++;
                }
        }
        return count;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode n) {
        do {
            n = n.getNext();
        } while (n != null && n.getOpcode() < 0);
        return n;
    }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, EVENTS, name, desc, false);
    }
}
